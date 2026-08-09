(ns books.handler-test
  "Handler-seam tests: pass a Ring request map into the app, assert on the
  response map. The database these tests talk to — and the command that starts
  one — is in `books.test-db`."
  (:require [books.db :as db]
            [books.handler :as handler]
            [books.test-db :as test-db]
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
  (testing "the landing copy promises search, bookmarks, and a later sign-in"
    (let [body (:body (request :get "/"))]
      (is (str/includes? body "Search the Google Books catalog. Keep the books that matter."))
      (is (str/includes? body "Find any book in the Google Books catalog by title, author, or keyword."))
      (is (str/includes? body "Save the books you care about and find them again in one place."))
      (is (str/includes? body "an account so they follow you around")))))

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

(deftest static-surface-is-scoped-to-css
  (testing "classpath resources outside public/css are not web-reachable"
    ;; Regression: a handler rooted at "public" and mounted at "/" served
    ;; /.gitkeep, and would serve any public/ asset a dependency jar carries.
    ;; test-resources/public/not-served.txt stands in for exactly that.
    (is (= 404 (:status (request :get "/not-served.txt"))))
    ;; And nothing outside public/ at all: migrations ship on the same
    ;; classpath, so a re-rooted handler would publish the schema.
    (is (= 404 (:status (request :get "/migrations/20260809120000-schema-baseline.up.sql"))))
    (is (= 404 (:status (request :get "/css/../migrations/20260809120000-schema-baseline.up.sql"))))))
