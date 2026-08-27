(ns books.handler
  (:require [books.bookmarks :as bookmarks]
            [books.catalog :as catalog]
            [books.clerk :as clerk]
            [books.db :as db]
            [books.reader :as reader]
            [books.views :as views]
            [clojure.string :as str]
            [jsonista.core :as json]
            [reitit.ring :as ring]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.middleware.params :refer [wrap-params]])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(def ^:private health-states
  {:ok          {:http-status 200 :body {:status "ok" :db "ok"}}
   :unreachable {:http-status 503 :body {:status "degraded" :db "unreachable"}}})

(defn- not-configured-state
  "No DATABASE_URL is a fault by default: a deploy that silently lost its
  database variable must fail its health check rather than report itself
  healthy. DB_OPTIONAL is the explicit opt-out for running database-less."
  [db-optional?]
  (if db-optional?
    {:http-status 200 :body {:status "ok" :db "not-configured"}}
    {:http-status 503 :body {:status "degraded" :db "not-configured"}}))

(defn- health [check db-optional?]
  (fn [_request]
    (let [state (check)
          {:keys [http-status body]} (or (health-states state)
                                         (not-configured-state db-optional?))]
      {:status http-status
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string body)})))

(defn- html
  "An HTML response. The only place a content type is spelled for a page."
  [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- landing [clerk]
  (fn [_request] (html (views/landing-page clerk))))

(defn- sign-in
  "The sign-in page. Public by necessity: gating it would redirect a signed-out
  Reader to a page that redirects them to itself.

  The page renders no credential and reaches no Clerk API. ClerkJS mounts the
  form in the browser and runs the Google flow from there; all this handler
  contributes is where to land afterwards."
  [clerk]
  (fn [request]
    (-> (html (views/sign-in-page clerk (get-in request [:params "redirect_url"])))
        ;; A sign-in page is per-visitor by definition, and the return path in
        ;; it is per-request. Nothing between us and the reader may keep it.
        (assoc-in [:headers "Cache-Control"] "no-store"))))

(defn- header-is-true? [request name]
  (= "true" (get-in request [:headers name])))

(defn- fragment-request?
  "Whether to answer the results fragment rather than the whole page.

  htmx sends `HX-Request: true` on every request it makes, and a plain form GET
  does not — so that header alone looks like the whole test. It is not. On a
  history cache miss htmx REPLAYS the entry as an hx-request: it sends
  `HX-Request: true` (config `historyRestoreAsHxRequest`, true by default) AND
  `HX-History-Restore-Request: true`, then swaps the answer into
  `document.body`. Answering the fragment there replaces the entire document
  with the bare results region — pressing Back after a search destroys the page.

  A restore therefore wants the same thing a browser navigation wants: the
  whole page."
  [request]
  (and (header-is-true? request "hx-request")
       (not (header-is-true? request "hx-history-restore-request"))))

(def ^:private search-cache-headers
  {;; One URL, two representations, chosen by REQUEST headers — so any cache
   ;; between us and the reader has to key on them or it will replay a bare
   ;; fragment into a document navigation (a page with no <html>, no header, no
   ;; stylesheet).
   ;;
   ;; BOTH headers `fragment-request?` reads, not just the first. A history
   ;; restore sends `HX-Request: true` too, so a cache keyed on that alone can
   ;; answer a restore out of a stored fragment — and htmx swaps a restore into
   ;; `document.body`, which is precisely the page-destroying swap the restore
   ;; handling exists to prevent. This list and that predicate have to name the
   ;; same headers.
   "Vary" "HX-Request, HX-History-Restore-Request"
   ;; …and `Vary` alone is not enough to rely on: intermediaries have a long
   ;; history of normalising away headers they do not recognise, and this one
   ;; is not a standard content-negotiation header. So the belt as well as the
   ;; braces — but `private, no-cache`, NOT `no-store`.
   ;;
   ;; `no-store` was the first answer here and it cost more than it bought.
   ;; Chrome blocklists any main-frame response carrying it from the
   ;; back/forward cache, and Firefox does the same, so pressing Back onto a
   ;; /search entry forced a full re-navigation and a fresh live catalog call —
   ;; partially undoing the Back-button fix on the very path (the no-JS full
   ;; page GET) that fix exists to protect.
   ;;
   ;; `private, no-cache` keeps everything `no-store` was wanted for: `private`
   ;; bars a shared cache from holding it at all, and `no-cache` forces
   ;; revalidation before any reuse — and this response sends no validator, so
   ;; revalidating can only mean fetching it again. bfcache is a browser-
   ;; internal snapshot rather than an HTTP cache, and this directive does not
   ;; disqualify the page from it.
   "Cache-Control" "private, no-cache"})

(defn- search
  "GET /search, answering the same content two ways: the results fragment when
  htmx asks for it, the whole page otherwise — so the form still works without
  JavaScript and a shared URL still shows its results.

  Always 200, including for a failed search. htmx does not swap a non-2xx
  response, and the rendered error region IS the answer: the page rendered
  fine; the search did not."
  [book-search clerk datasource]
  (fn [request]
    (let [query (catalog/query {:title (get-in request [:params "title"])
                                :author (get-in request [:params "author"])
                                :start-index (get-in request [:params "start"])})
          state (if (catalog/blank-query? query)
                  {:outcome :prompt}
                  (book-search query))
          ;; Which of the Volumes about to be rendered this Reader already
          ;; keeps — ONE query for the whole page, never one per card. The
          ;; Reader comes from `:reader`, which the gate attached from a
          ;; verified session; a parameter could name anyone.
          bookmarked (bookmarks/bookmarked-ids datasource
                                               (get-in request [:reader :id])
                                               (mapv :id (:volumes state)))]
      (-> (html (if (fragment-request? request)
                  (views/search-results query state bookmarked)
                  (views/search-page clerk query state bookmarked)))
          (update :headers merge search-cache-headers)))))

;; ---------------------------------------------------------------------------
;; Keeping a Volume.
;;
;; POST adds a Bookmark, DELETE removes one, and both answer the control alone
;; for htmx to swap over the one it submitted. The Volume travels as form
;; fields — the snapshot ADR-0006 stores — which is why the DELETE reads them
;; too: its answer is the *not-bookmarked* control for that Volume, and drawing
;; it needs the same fields. htmx puts a DELETE's parameters in the URL rather
;; than the body (`methodsThatUseUrlParams`), and `wrap-params` reads both.
;; ---------------------------------------------------------------------------

(def ^:private bookmarks-path "/bookmarks")

(defn- one
  "One value for a request parameter, whatever shape the parameter middleware
  handed us — `wrap-params` answers a vector for a repeated name. The first
  wins, as it does for a search field (`books.catalog`)."
  [v]
  (if (sequential? v) (first v) v))

(defn- many
  "Every value a repeated request parameter carried, as a vector. The authors of
  a Volume arrive this way, and a single author arrives as a bare string."
  [v]
  (cond
    (sequential? v) (vec v)
    (some? v) [v]
    :else []))

(defn- volume-snapshot
  "The Volume a toggle names, rebuilt from the control's own hidden fields.

  It is the browser's account of the Volume rather than the Catalog's, and
  ADR-0006 weighs that deliberately: it is the Reader's own row, seen by nobody
  else, and rendered escaped like every other string."
  [params]
  {:id (one (get params "volume"))
   :title (one (get params "title"))
   :authors (many (get params "author"))
   :published-date (one (get params "published-date"))
   :thumbnail (one (get params "thumbnail"))})

(defn- toggled
  "The re-rendered control, which is the whole of a toggle's answer."
  [volume state]
  (html (views/bookmark-toggle volume state)))

(def ^:private nameless-volume
  "A toggle that names no Volume is a request no control could have made. An
  empty body, so htmx has nothing to swap and the card stays as it was."
  {:status 400
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body ""})

(defn- keep-volume
  "POST /bookmarks — the Reader keeps this Volume. Bookmarking one already kept
  is the same answer again, not an error (`books.bookmarks/save!`)."
  [datasource]
  (fn [request]
    (let [volume (volume-snapshot (:params request))]
      (if (str/blank? (:id volume))
        nameless-volume
        (do (bookmarks/save! datasource (get-in request [:reader :id]) volume)
            (toggled volume :bookmarked))))))

(defn- drop-volume
  "DELETE /bookmarks — the Reader stops keeping this Volume. Scoped to them, so
  it can only ever reach their own row."
  [datasource]
  (fn [request]
    (let [volume (volume-snapshot (:params request))]
      (if (str/blank? (:id volume))
        nameless-volume
        (do (bookmarks/remove! datasource (get-in request [:reader :id]) (:id volume))
            (toggled volume :not-bookmarked))))))

;; ---------------------------------------------------------------------------
;; The gate.
;;
;; Everything except the landing page, the health probe and the sign-in page
;; requires a Reader. The gated set is the named map below, so a new gated route
;; joins it by adding its path and wrapping its route data in `gated` — two
;; lines, in one place, reviewable as a diff.
;; ---------------------------------------------------------------------------

(def gated-paths
  "The paths that require a signed-in Reader, and the request methods each
  answers.

  Public, because it is the seam: `books.auth-test` asserts that every path
  named here refuses an anonymous request by every method it answers, so a path
  added to this map without being wired through `gated` fails the suite instead
  of shipping open. The methods are part of the seam rather than decoration — a
  mutation route probed with a GET would 404 and look refused.

  It is also what bounds the sign-in return path — see `return-path`."
  {"/search" #{:get :head}
   "/bookmarks" #{:post :delete}})

(def ^:private sign-in-path "/sign-in")

(def ^:private bearer-prefix "Bearer ")

;; The two transports ADR-0005 accepts a session token over, named — because
;; which of them a request used is the whole of this app's CSRF story, and a
;; bare token string cannot say.
(def ^:private page-transports
  "How a request to READ a gated page may prove itself.

  Both, and the header wins where both arrive: htmx mints a token per request
  with `getToken()`, while the `__session` cookie an ordinary document
  navigation carries may be up to a minute older. A cross-site GET is accepted
  here because it changes nothing."
  #{:bearer-header :session-cookie})

(def ^:private mutation-transports
  "How a request that CHANGES something may prove itself: the bearer header
  alone, and that is this app's CSRF defence (ADR-0007).

  A cross-origin form can POST urlencoded data with no preflight and the browser
  will attach our cookie — whose token is genuine and whose `azp` is ours, so
  every check in ADR-0005 passes. It CANNOT set an `Authorization` header
  without a permissive CORS preflight, and this app answers no OPTIONS route and
  sends no `Access-Control-Allow-*` header anywhere. The defence is therefore a
  property of the browser's request model rather than of the `SameSite`
  attribute of a cookie Clerk sets and this repo cannot see."
  #{:bearer-header})

(defn- session-credential
  "The session token the request carries and the transport that carried it, or
  nil. The header wins where both are present — see `page-transports`."
  [request]
  (let [authorization (get-in request [:headers "authorization"])]
    (if (and (string? authorization) (str/starts-with? authorization bearer-prefix))
      {:transport :bearer-header :token (subs authorization (count bearer-prefix))}
      (when-let [cookie (get-in request [:cookies "__session" :value])]
        {:transport :session-cookie :token cookie}))))

(defn- returnable?
  "Whether a gated path is one a Reader can be sent back to after signing in: a
  page they can GET. A mutation route is not — returning them to a POST-only
  path lands them on a 404."
  [uri]
  (contains? (get gated-paths uri) :get))

(defn- return-path
  "Where to send the Reader after they sign in.

  Chosen from `gated-paths` rather than echoed out of the request, which is what
  makes an open redirect structurally impossible here: the path can only be one
  this repo wrote down. A request URI is caller-controlled, and `//evil.example`
  is a perfectly ordinary-looking one — echoing it would hand an attacker a
  phishing page hosted behind this app's own sign-in flow.

  The query string rides along so a search survives the round trip; it is
  encoded into one parameter value, so it cannot add parameters of its own."
  [request]
  (let [uri (:uri request)
        query (:query-string request)]
    (if (returnable? uri)
      (cond-> uri (seq query) (str "?" query))
      "/")))

(defn- sign-in-url [request]
  (str sign-in-path "?redirect_url="
       (URLEncoder/encode ^String (return-path request) StandardCharsets/UTF_8)))

(def ^:private credential-headers
  "The request headers the gate's answer depends on. Any cache between us and
  the Reader has to key on both, or it will serve one Reader's gated page to the
  next visitor."
  "Authorization, Cookie")

(def ^:private refusal-cache-headers
  {;; A refusal is about the credential that was missing, not about the URL.
   ;; Storing one would sign the next visitor out of a page they can see.
   "Cache-Control" "no-store"
   "Vary" credential-headers})

(defn- vary-on-credentials
  "Adds the credential headers to whatever the handler already varies on.

  ADDS rather than replaces, and that is the whole point of this function: the
  search page varies on the two htmx headers for reasons worked out in ticket
  #5, and a gate that overwrote `Vary` would silently undo them — a cache would
  then be free to replay a bare fragment into a document navigation."
  [response]
  (update-in response [:headers "Vary"]
             #(if (seq %) (str % ", " credential-headers) credential-headers)))

(defn- privately-cached
  "A gated response is one Reader's. `private, no-cache` says so without saying
  `no-store`, which browsers read as a reason to skip the back/forward cache —
  a cost ticket #5 measured and deliberately refused to pay. A handler that has
  already stated its own policy keeps it; one that has not gets this."
  [response]
  (update-in response [:headers "Cache-Control"] #(or % "private, no-cache")))

(defn- refuse
  "The refusal a signed-out request gets, which depends on how it asked.

  A document request is redirected: the browser follows it and the Reader lands
  on the sign-in page. An htmx request is NOT — an XHR follows a 302
  transparently, so htmx would swap a whole sign-in document into the results
  region and the Reader would see a form nested inside a page. `HX-Redirect` is
  htmx's own answer to that: it navigates the window instead of swapping."
  [request]
  (let [target (sign-in-url request)]
    (-> (if (header-is-true? request "hx-request")
          {:status 401 :headers {"HX-Redirect" target} :body ""}
          {:status 302 :headers {"Location" target} :body ""})
        (update :headers merge refusal-cache-headers))))

(defn- unavailable
  "The answer when this deployment cannot sign anyone in at all.

  Deliberately NOT a redirect: sending a Reader to a sign-in page that has no
  Clerk instance behind it is a loop that ends in a blank form. 503 is the same
  answer `/health` gives for a database this deploy was supposed to have, and it
  means the same thing — configuration is missing, and that is a fault."
  [clerk]
  (-> {:status 503
       :headers {"Content-Type" "text/html; charset=utf-8"}
       ;; The sign-in page's own not-configured state, which already says the
       ;; true thing: nobody can sign in here, so every page behind sign-in
       ;; stays closed. One page, so the two cannot drift into saying different
       ;; things about the same deployment.
       :body (views/sign-in-page clerk nil)}
      (update :headers merge refusal-cache-headers)))

(defn- wrap-require-reader
  "Only a signed-in Reader reaches `handler`; everyone else is refused.

  `accepted-transports` is which credentials count on this route — a token that
  arrived any other way is not checked at all, so a cookie-only mutation reads
  as a request carrying no credential and is refused exactly as a signed-out one
  is (ADR-0007 clause 6). It is the same fact from the app's side.

  The verified Reader is attached to the request as `:reader`, which is how
  `books.bookmarks` knows whose rows to answer. Nothing downstream ever reads a
  token."
  [session-check clerk accepted-transports handler]
  (fn [request]
    (let [{:keys [transport token]} (session-credential request)
          outcome (session-check (when (accepted-transports transport) token))]
      (cond
        (reader/signed-in? outcome)
        (some-> (handler (assoc request :reader (:reader outcome)))
                (vary-on-credentials)
                (privately-cached))

        (= :not-configured (:reason outcome)) (unavailable clerk)

        :else (refuse request)))))

(defn- gated
  "Route data that requires a Reader. Wrapping a route in this is the whole of
  what it takes to gate it — see `gated-paths`."
  [require-reader route-data]
  (assoc route-data :middleware [require-reader]))

(def ^:private stylesheet-cache-control
  ;; The stylesheet URL is unversioned (/css/app.css), so a cached copy can
  ;; outlive the deploy that changed it. Revalidate every time — correctness
  ;; over bytes — until a cache-busting scheme exists (ADR-0004).
  "public, max-age=0, must-revalidate")

(def ^:private script-cache-control
  ;; The opposite case, and the reason ADR-0004 clause 6 was written as a
  ;; coupling rather than a blanket rule: the vendored script's URL carries its
  ;; version (/js/htmx-<version>.min.js), so the bytes behind it can never
  ;; change. A new version is a new URL, so this one can be cached forever.
  "public, max-age=31536000, immutable")

(defn- wrap-request-methods
  "Only requests whose method is in `methods` reach `handler`; the rest get a
  nil response, so `ring/routes` falls through to the next handler."
  [methods handler]
  (fn
    ([request]
     (when (methods (:request-method request)) (handler request)))
    ([request respond raise]
     (if (methods (:request-method request))
       (handler request respond raise)
       (respond nil)))))

(defn- wrap-cache-control
  "Stamps `value` as Cache-Control on any response `handler` actually produces."
  [value handler]
  (letfn [(stamp [response]
            (some-> response (assoc-in [:headers "Cache-Control"] value)))]
    (fn
      ([request] (stamp (handler request)))
      ([request respond raise] (handler request (comp respond stamp) raise)))))

;; ---------------------------------------------------------------------------
;; Security response headers.
;;
;; ADR-0004 clause 7 deferred these to "the authentication ticket" on the
;; grounds that the header set should be chosen once, against a real threat
;; model, rather than twice. This is that ticket: there is now a session
;; credential in a cookie and a third-party script on every page, and ADR-0005
;; records what was chosen and what was deliberately left out.
;; ---------------------------------------------------------------------------

(defn- directive
  "One CSP directive, or nil when it has no sources to name."
  [name sources]
  (when (seq sources) (str/join " " (cons name sources))))

(defn- content-security-policy
  "The policy every response carries.

  Clerk's origins are folded in from `books.clerk/csp-sources`, which answers
  nothing at all when no instance is configured — so an unconfigured deployment
  sends the strict same-origin policy rather than a policy naming a vendor it
  never contacts.

  Two directives are looser than the rest, and both are Clerk's requirement
  rather than this app's taste (see ADR-0005): `style-src 'unsafe-inline'`,
  which Clerk's components need because they style themselves at runtime, and
  `script-src 'unsafe-inline'`. `'unsafe-eval'` is documented by Clerk as a
  Next.js development need and is NOT sent here."
  [publishable-key]
  (let [{:keys [script-src connect-src img-src frame-src]} (clerk/csp-sources publishable-key)]
    (->> [(directive "default-src" ["'self'"])
          (directive "script-src" (into ["'self'" "'unsafe-inline'"] script-src))
          (directive "connect-src" (into ["'self'"] connect-src))
          ;; 'self' for the layout's own assets, the Catalog's origins for the
          ;; Volume covers, and Clerk's for an avatar in the user button.
          (directive "img-src" (into (into ["'self'" "data:"] catalog/cover-origins) img-src))
          (directive "style-src" ["'self'" "'unsafe-inline'"])
          (directive "worker-src" ["'self'" "blob:"])
          (directive "frame-src" (into ["'self'"] frame-src))
          (directive "form-action" ["'self'"])
          ;; Nothing here is ever framed: this app has no embeddable surface,
          ;; and the sign-in flow navigates rather than embeds.
          (directive "frame-ancestors" ["'none'"])]
         (remove nil?)
         (str/join "; "))))

(defn- security-headers [publishable-key]
  {"Content-Security-Policy" (content-security-policy publishable-key)
   ;; A stylesheet or a script must be served as one to be treated as one. This
   ;; is the header that stops a Volume description or an uploaded byte stream
   ;; from being sniffed into an executable content type.
   "X-Content-Type-Options" "nosniff"
   ;; A search URL carries what a Reader typed. Send the origin off-site and the
   ;; path nowhere: a full URL in a `Referer` hands the Catalog's own image host
   ;; the Reader's query.
   "Referrer-Policy" "strict-origin-when-cross-origin"})

(defn- wrap-security-headers
  "Stamps the security headers onto every response, including the static roots
  and the 404 — a policy with a hole in it is not a policy, and the one response
  nobody remembers to cover is the one an attacker looks for.

  Stamped rather than merged over: these are this app's answer, not a per-route
  preference, and a handler that could override them would be the hole."
  [publishable-key handler]
  (let [headers (security-headers publishable-key)]
    (letfn [(stamp [response] (some-> response (update :headers merge headers)))]
      (fn
        ([request] (stamp (handler request)))
        ([request respond raise] (handler request (comp respond stamp) raise))))))

;; ---------------------------------------------------------------------------
;; The last line: nothing internal reaches the caller
;; ---------------------------------------------------------------------------

(def ^:private server-error-body
  "The entire body of a 500. No exception class, no stack frames, no namespace
  names, no framework or server identifiers — an anonymous caller learns that
  the request failed and nothing whatsoever about what runs here.

  Written as one literal rather than through `books.views` deliberately: this
  page has to render when the code that renders pages is what broke."
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<title>Something went wrong</title></head>"
       "<body><h1>Something went wrong</h1>"
       "<p>The request could not be completed. Please try again.</p>"
       "</body></html>"))

(defn- server-error []
  {:status 500
   :headers {"Content-Type" "text/html; charset=utf-8"
             "Cache-Control" "no-store"}
   :body server-error-body})

(defn- report-failure!
  "One stderr line per unhandled fault: exception class and message, redacted,
  and nothing else. Never the request, the URI, the params or the ex-data —
  every one of those can carry reader input or a credential, and this line is
  the operator's, not the caller's.

  `redact` is why the message is not printed raw. The message belongs to
  whatever threw, not to us: `books.google-books/book-search` catches
  `Exception`, so an `Error` raised on the fetch path is not turned into an
  outcome there and arrives here carrying whatever text it was built with —
  the API key included. `google-books/report!` redacts for that reason on its
  own path; this is the same reasoning at the outer boundary."
  [redact ^Throwable t]
  (binding [*out* *err*]
    (println (redact (str "unhandled request failure: "
                          (.getName (class t)) ": " (ex-message t))))))

(defn- wrap-error-page
  "Catch everything, so no handler can serve internals. Ring has no error
  middleware of its own and Jetty's fallback is its own error page — a body
  naming the failing namespace, the framework frames and the server version.
  That page is a disclosure, so this one is wrapped OUTSIDE every route,
  including the static roots and the default handler.

  Throwable rather than Exception: an Error (a bad cast compiled by an
  unexpected type, an assertion) is exactly as disclosing and exactly as
  survivable at the request boundary.

  `redact` strips a known secret out of the reported line — see
  `report-failure!`. It defaults to `identity` in `make-app`, so an app wired
  without one still reports."
  [redact handler]
  (letfn [(fail! [t]
            ;; Reporting runs INSIDE the catch, so anything IT throws — a
            ;; closed `*err*`, a `getMessage` that throws — used to escape this
            ;; middleware and hand the request straight back to Jetty's own
            ;; error page: the exact disclosure this exists to prevent, reached
            ;; by the one path nobody watches. The 500 is now produced whether
            ;; or not the fault could be reported; an unreportable fault is
            ;; still a fault the caller must not see the inside of.
            (try (report-failure! redact t)
                 (catch Throwable _ nil))
            (server-error))]
    (fn
      ([request]
       (try (handler request)
            (catch Throwable t (fail! t))))
      ([request respond raise]
       (try (handler request respond (fn [t] (respond (fail! t))))
            (catch Throwable t (respond (fail! t))))))))

(defn- static-root
  "A read-only static surface: the classpath tree `root`, mounted at `path`,
  with its own cache policy.

  Each root is named explicitly and rooted BELOW public/ — never at public/
  itself and never at `/` — so that adding one publishes exactly the tree it
  names and nothing that happens to sit beside it on the classpath (a keeper
  file, the migrations, a dependency jar's own public/ assets)."
  [{:keys [path root cache-control]}]
  (->> (ring/create-resource-handler {:path path :root root})
       (wrap-not-modified)
       (wrap-cache-control cache-control)
       (wrap-request-methods #{:get :head})))

(def ^:private stylesheets
  "The Tailwind-built stylesheet under /css/ (generated; see ADR-0004)."
  (static-root {:path "/css/" :root "public/css"
                :cache-control stylesheet-cache-control}))

(def ^:private scripts
  "The vendored htmx under /js/ (committed and digest-pinned; see
  `books.assets` and the 2026-08-10 amendment to ADR-0004)."
  (static-root {:path "/js/" :root "public/js"
                :cache-control script-cache-control}))

(def ^:private app-scripts
  "This repo's OWN browser code under /app/ — a third scoped root, kept separate
  from /js/ so that 'what we wrote' and 'what we vendored' are different URLs
  with different policies. Its URL carries no version, so it revalidates like
  the stylesheet rather than caching forever (ADR-0004 clause 6)."
  (static-root {:path "/app/" :root "public/app"
                :cache-control stylesheet-cache-control}))

(defn make-app
  "The Ring handler with its dependencies injected: the database, and the Book
  search port the search page is served by. `datasource` is nil when no
  DATABASE_URL is configured.

  Options:
  * `:db-optional?` — treat an absent DATABASE_URL as healthy (default false);
  * `:probe` — the connectivity probe, for tests;
  * `:book-search` — a Book search (see `books.catalog`): a function of the
    query map. Defaults to the not-configured one, so an app wired without a
    Book search renders the search page's error state instead of failing to
    boot.
  * `:redact` — a function of one string, applied to the last-line fault report
    before it is printed. Defaults to `identity`; `books.server/run` passes one
    that strips the Books API key, because only the boot path knows it.

  The connectivity result is cached for `db/check-ttl-ms`: /health is
  unauthenticated, so it must not open a database connection per request."
  [datasource {:keys [db-optional? probe book-search redact publishable-key session-check]
               :or {db-optional? false redact identity}}]
  (let [check (db/checker datasource (cond-> {} probe (assoc :probe probe)))
        health-handler (health check db-optional?)
        ;; What every page needs to load ClerkJS, and nothing more. Derived once
        ;; at boot rather than per request, and carrying no secret: a publishable
        ;; key is rendered into every page by design.
        ;;
        ;; **nil when there is no instance**, and that is load-bearing: it is the
        ;; single value every page and the closed gate read to decide whether
        ;; sign-in exists here at all. A key that does not decode is no key.
        clerk (when-let [url (clerk/script-url publishable-key)]
                {:publishable-key publishable-key :script-url url})
        landing-handler (landing clerk)
        sign-in-handler (sign-in clerk)
        search-handler (search (or book-search catalog/not-configured) clerk datasource)
        keep-handler (keep-volume datasource)
        drop-handler (drop-volume datasource)
        ;; A gate per route, differing only in which transports it accepts —
        ;; so "may this credential change something?" is data at the route.
        require-reader (fn [accepted-transports]
                         (partial wrap-require-reader
                                  (or session-check reader/not-configured)
                                  clerk
                                  accepted-transports))]
    (wrap-error-page
     redact
     (wrap-security-headers
      publishable-key
      (ring/ring-handler
       (ring/router
        [["/" {:get landing-handler :head landing-handler}]
         ["/health" {:get health-handler :head health-handler}]
         [sign-in-path {:get sign-in-handler :head sign-in-handler}]
         ;; HEAD as well as GET, like every other page route: without it this was
         ;; the one path answering 405 — and reitit sends no `Allow` header with
         ;; it, so a probe or link checker learned nothing from the refusal.
         ["/search" (gated (require-reader page-transports)
                           {:get search-handler :head search-handler})]
         ;; A write, so the bearer header is the only credential that counts.
         [bookmarks-path (gated (require-reader mutation-transports)
                                {:post keep-handler :delete drop-handler})]]
        ;; Query parameters, for the search form and a DELETE's fields; cookies,
        ;; for the session token a document navigation carries. Router-scoped
        ;; rather than wrapped around everything: the static roots read neither.
        {:data {:middleware [wrap-params wrap-cookies]}})
       (ring/routes
        stylesheets
        scripts
        app-scripts
        (ring/create-default-handler)))))))
