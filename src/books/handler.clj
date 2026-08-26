(ns books.handler
  (:require [books.catalog :as catalog]
            [books.db :as db]
            [books.views :as views]
            [jsonista.core :as json]
            [reitit.ring :as ring]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.middleware.params :refer [wrap-params]]))

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

(defn- landing [_request]
  (html (views/landing-page)))

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
  [book-search]
  (fn [request]
    (let [query (catalog/query {:title (get-in request [:params "title"])
                                :author (get-in request [:params "author"])})
          state (if (catalog/blank-query? query)
                  {:outcome :prompt}
                  (book-search query))]
      (-> (html (if (fragment-request? request)
                  (views/search-results state)
                  (views/search-page query state)))
          (update :headers merge search-cache-headers)))))

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
  [datasource {:keys [db-optional? probe book-search redact]
               :or {db-optional? false redact identity}}]
  (let [check (db/checker datasource (cond-> {} probe (assoc :probe probe)))
        health-handler (health check db-optional?)
        search-handler (search (or book-search catalog/not-configured))]
    (wrap-error-page
     redact
     (ring/ring-handler
      (ring/router
       [["/" {:get landing :head landing}]
        ["/health" {:get health-handler :head health-handler}]
        ;; HEAD as well as GET, like every other page route: without it this was
        ;; the one path answering 405 — and reitit sends no `Allow` header with
        ;; it, so a probe or link checker learned nothing from the refusal.
        ["/search" {:get search-handler :head search-handler}]]
       ;; Query parameters, for the search form. Router-scoped rather than
       ;; wrapped around everything: the static roots take no parameters.
       {:data {:middleware [wrap-params]}})
      (ring/routes
       stylesheets
       scripts
       (ring/create-default-handler))))))
