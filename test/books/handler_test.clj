(ns books.handler-test
  "Handler-seam tests: pass a Ring request map into the app, assert on the
  response map. The database these tests talk to — and the command that starts
  one — is in `books.test-db`."
  (:require [books.assets :as assets]
            [books.clerk :as clerk]
            [books.db :as db]
            [books.handler :as handler]
            [books.stub-session :as session]
            [books.test-db :as test-db]
            [books.test-jwt :as test-jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]))

(defn- health-response [app method]
  (app {:request-method method :uri "/health"}))

(defn- json-body [response]
  (json/read-value (:body response)))

;; The page and static-surface tests need no database: an app built with
;; :db-optional? true boots database-less and still routes everything else.
;; ONE instance, shared by every `request` below — a per-call app would let a
;; conditional-GET test echo a Last-Modified produced by a different instance,
;; which would quietly stop testing revalidation the moment anything in the
;; static chain gains per-app state (an ETag cache, a pool).
(def ^:private pages-app (delay (handler/make-app nil {:db-optional? true})))

(defn- request
  ([method uri] (request method uri nil))
  ([method uri headers]
   (@pages-app (cond-> {:request-method method :uri uri}
                 headers (assoc :headers headers)))))

(defn- header [response name]
  (get-in response [:headers name]))

(deftest health-reports-db-ok-when-connected
  (testing "GET /health against a reachable Postgres answers 200 db ok"
    (let [app (handler/make-app (db/datasource test-db/test-database-url) {})
          response (health-response app :get)]
      (is (= 200 (:status response)))
      (is (= {"status" "ok" "db" "ok"} (json-body response))))))

(deftest health-reports-degraded-when-database-url-is-absent
  (testing "a deploy that lost its DATABASE_URL must fail its health check"
    (let [app (handler/make-app nil {})
          response (health-response app :get)]
      (is (= 503 (:status response)))
      (is (= {"status" "degraded" "db" "not-configured"} (json-body response))))))

(deftest health-reports-ok-without-a-database-url-when-db-is-optional
  (testing "running database-less is fine when it was asked for explicitly"
    (let [app (handler/make-app nil {:db-optional? true})
          response (health-response app :get)]
      (is (= 200 (:status response)))
      (is (= {"status" "ok" "db" "not-configured"} (json-body response))))))

(deftest db-optional-does-not-excuse-an-unreachable-database
  (testing "the gate covers 'no URL', not 'the URL does not work'"
    (let [app (handler/make-app (db/datasource (test-db/unreachable-database-url))
                                {:db-optional? true})
          response (health-response app :get)]
      (is (= 503 (:status response)))
      (is (= {"status" "degraded" "db" "unreachable"} (json-body response))))))

(deftest health-reports-degraded-when-db-unreachable
  (testing "a configured but unreachable database answers 503 degraded"
    (let [app (handler/make-app (db/datasource (test-db/unreachable-database-url)) {})
          response (health-response app :get)]
      (is (= 503 (:status response)))
      (is (= {"status" "degraded" "db" "unreachable"} (json-body response))))))

(deftest health-probes-the-database-at-most-once-per-ttl
  (testing "/health is unauthenticated, so it must not open a connection per request"
    (let [probes (atom 0)
          app (handler/make-app ::datasource
                                {:probe (fn [_] (swap! probes inc) :ok)})]
      (dotimes [_ 5] (health-response app :get))
      (is (= 1 @probes)))))

(deftest health-endpoint-head
  (testing "HEAD /health answers 200 for probes that do not GET"
    (let [app (handler/make-app nil {:db-optional? true})
          response (health-response app :head)]
      (is (= 200 (:status response))))))

(deftest landing-page-is-html
  (testing "GET / answers 200 with an HTML content type"
    (let [response (request :get "/")]
      (is (= 200 (:status response)))
      (is (str/starts-with? (header response "Content-Type") "text/html")))))

(deftest landing-page-head
  (testing "HEAD / answers 200 so uptime probes need not fetch the page"
    (is (= 200 (:status (request :head "/"))))))

(deftest landing-page-explains-the-product
  ;; Every string below appears ONLY in the landing body — never in the shared
  ;; header or footer — so each assertion can fail for the reason it states.
  (testing "the landing copy offers search, and promises bookmarks and a later sign-in"
    (let [body (:body (request :get "/"))]
      (is (str/includes? body "Search the Google Books catalog. Keep the books that matter."))
      ;; Search shipped, so the roadmap card says so rather than "coming next".
      (is (str/includes? body "Find a book in the Google Books catalog by title, by author, or by both."))
      ;; Tied to the SEARCH card, not merely present on the page: a bare
      ;; "Available now" is equally there if Bookmarks became :now and Search
      ;; :next — the one regression this line exists to catch. The bounded gap
      ;; is the status paragraph's own open tag and nothing else; the next
      ;; card's status is several hundred characters further on.
      (is (re-find #"(?s)Find a book in the Google Books catalog by title, by author, or by both\..{0,120}Available now"
                   body))
      (is (str/includes? body "Save the books you care about and find them again in one place."))
      (is (str/includes? body "an account so your library follows you around")))))

(deftest landing-page-uses-the-shared-layout
  (testing "the landing page is framed by the shared layout: header, footer, stylesheet"
    (let [body (:body (request :get "/"))]
      (is (str/includes? body "<header"))
      (is (str/includes? body "<footer"))
      (is (str/includes? body "/css/app.css")))))

(deftest unknown-route
  (testing "an unrouted path answers 404"
    ;; Built with the DEFAULT (non-db-optional) options — the wiring production
    ;; actually runs — so routing stays pinned there and not only under the
    ;; opt-out config the page tests use.
    (let [app (handler/make-app nil {})]
      (is (= 404 (:status (app {:request-method :get :uri "/nope"})))))))

;; ---------------------------------------------------------------------------
;; A handler that throws must not become a disclosure.
;; Regression: with no error middleware, Jetty answered its own error page —
;; a 4.7 KB body naming the failing namespace, the reitit and Jetty stack
;; frames, and the server version — to an anonymous caller.
;; ---------------------------------------------------------------------------

(defn- throwing-app
  "An app whose search port is defective: it throws rather than answering an
  outcome. Nothing else can reach the error path on purpose, which is the
  point — the middleware has to hold for the handler nobody predicted."
  []
  (handler/make-app nil {:db-optional? true
                         :session-check (session/signed-in)
                         :book-search (fn [_query]
                                        (throw (ex-info "port blew up" {:secret "s3cret"})))}))

(deftest a-throwing-handler-answers-a-minimal-500
  (let [errors (java.io.StringWriter.)
        response (binding [*err* errors]
                   ((throwing-app) {:request-method :get :uri "/search"
                                    :query-string "title=clojure"}))
        body (str (:body response))]
    (testing "the caller gets a 500 that is an HTML page, not a framework dump"
      (is (= 500 (:status response)))
      (is (str/starts-with? (str (header response "Content-Type")) "text/html")))
    (testing "and nothing internal is in it"
      (doseq [leak ["exception" "clojure." "reitit" "jetty" "s3cret" "port blew up" "\tat "]]
        (is (not (str/includes? (str/lower-case body) (str/lower-case leak)))
            (str "the 500 body must not mention " (pr-str leak)))))
    (testing "the fault is reported server-side — class and message only"
      (let [reported (str errors)]
        (is (str/includes? reported "ExceptionInfo"))
        (is (str/includes? reported "port blew up"))
        (testing "never the request, the params, or the ex-data — they carry reader input"
          (is (not (str/includes? reported "s3cret")))
          (is (not (str/includes? reported "title=clojure"))))))))

(defn- unreportable
  "A fault whose own message cannot be read: `getMessage` throws. It stands in
  for every way reporting a fault can itself fail — a closed `*err*` is the
  other — so the test below is about the reporting throwing, not about one
  particular way it does."
  []
  (proxy [RuntimeException] ["unreadable"]
    (getMessage [] (throw (IllegalStateException. "getMessage exploded")))))

(deftest a-fault-whose-own-reporting-throws-still-answers-the-error-page
  ;; `fail!` runs INSIDE the catch: it reports, then builds the 500. Reporting
  ;; that throws therefore escaped this middleware entirely and handed the
  ;; request back to Jetty's own error page — the exact disclosure the
  ;; middleware exists to prevent, reached by the one path nobody watches.
  (let [app (handler/make-app nil {:db-optional? true
                                   :session-check (session/signed-in)
                                   :book-search (fn [_query] (throw (unreportable)))})
        response (binding [*err* (java.io.StringWriter.)]
                   (app {:request-method :get :uri "/search" :query-string "title=clojure"}))]
    (is (= 500 (:status response)))
    (is (str/includes? (str (:body response)) "Something went wrong"))))

(deftest a-reported-fault-is-redacted-before-it-reaches-stderr
  ;; `books.google-books/book-search` catches Exception, so an Error from the
  ;; fetch path arrives here unfiltered — and its message belongs to whatever
  ;; threw it, not to us. `google-books/report!` redacts for exactly that
  ;; reason; this line prints the same kind of text and did not.
  (let [secret "test-key-not-a-real-one"
        errors (java.io.StringWriter.)
        app (handler/make-app nil
                              {:db-optional? true
                               :session-check (session/signed-in)
                               :redact #(str/replace % secret "[redacted]")
                               :book-search (fn [_query]
                                              (throw (AssertionError.
                                                      (str "upstream said " secret))))})
        response (binding [*err* errors]
                   (app {:request-method :get :uri "/search" :query-string "title=clojure"}))]
    (is (= 500 (:status response)) "precondition: the Error reached the error page")
    (is (not (str/includes? (str errors) secret)) "a credential must never reach a log line")
    (is (str/includes? (str errors) "[redacted]") "…but the fault is still reported")))

;; ---------------------------------------------------------------------------
;; The static surface: /css/ and nothing else.
;; Backed by test-resources/public/css/fixture.css so these never depend on the
;; generated (gitignored) stylesheet having been built.
;; ---------------------------------------------------------------------------

(deftest stylesheet-is-served
  (testing "GET a /css/ resource answers 200 with a CSS content type"
    (let [response (request :get "/css/fixture.css")]
      (is (= 200 (:status response)))
      (is (str/starts-with? (header response "Content-Type") "text/css")))))

(deftest stylesheet-carries-a-revalidating-cache-policy
  (testing "the unversioned stylesheet URL must be revalidated, never blindly reused"
    (is (= "public, max-age=0, must-revalidate"
           (header (request :get "/css/fixture.css") "Cache-Control")))))

(deftest stylesheet-revalidates-to-304
  (testing "a conditional GET with an up-to-date If-Modified-Since answers 304, no body"
    (let [fresh (request :get "/css/fixture.css")
          last-modified (header fresh "Last-Modified")
          response (request :get "/css/fixture.css" {"if-modified-since" last-modified})]
      (is (some? last-modified))
      (is (= 304 (:status response)))
      (is (nil? (:body response))))))

(deftest stylesheet-rejects-write-methods
  (testing "only GET and HEAD reach the resource handler; writes fall through to the router"
    (doseq [method [:post :put :delete :patch]]
      (is (not= 200 (:status (request method "/css/fixture.css")))
          (str (str/upper-case (name method)) " on a stylesheet must not answer 200")))))

(deftest static-surface-is-scoped-to-css-and-js
  (testing "classpath resources outside public/css and public/js are not web-reachable"
    ;; Regression: a handler rooted at "public" and mounted at "/" served
    ;; /.gitkeep, and would serve any public/ asset a dependency jar carries.
    ;; test-resources/public/not-served.txt stands in for exactly that.
    (is (= 404 (:status (request :get "/not-served.txt"))))
    ;; And nothing outside public/ at all: migrations ship on the same
    ;; classpath, so a re-rooted handler would publish the schema.
    (is (= 404 (:status (request :get "/migrations/20260809120000-schema-baseline.up.sql"))))
    (is (= 404 (:status (request :get "/css/../migrations/20260809120000-schema-baseline.up.sql"))))
    ;; The /js/ root added for htmx is a second scoped root, not a re-rooting.
    (is (= 404 (:status (request :get "/js/../migrations/20260809120000-schema-baseline.up.sql"))))
    (is (= 404 (:status (request :get "/js/../not-served.txt"))))))

;; ---------------------------------------------------------------------------
;; The second static root: the vendored htmx under /js/.
;; Unlike the stylesheet this file is COMMITTED, so these run against the real
;; asset the browser gets — `books.assets-test` is what proves its bytes.
;; ---------------------------------------------------------------------------

(deftest vendored-script-is-served
  (testing "GET the vendored htmx answers 200 with a JavaScript content type"
    (let [response (request :get assets/htmx-path)]
      (is (= 200 (:status response)))
      (is (re-find #"javascript" (str (header response "Content-Type")))))))

(deftest vendored-script-is-cached-forever
  (testing "the URL carries the version, so the bytes behind it can never change"
    (is (= "public, max-age=31536000, immutable"
           (header (request :get assets/htmx-path) "Cache-Control")))))

(deftest vendored-script-rejects-write-methods
  (testing "only GET and HEAD reach the script root"
    (doseq [method [:post :put :delete :patch]]
      (is (not= 200 (:status (request method assets/htmx-path)))
          (str (str/upper-case (name method)) " on a script must not answer 200")))))
