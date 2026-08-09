(ns books.handler-test
  (:require [books.handler :as handler]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- request
  ([method uri] (request method uri nil))
  ([method uri headers]
   (handler/app (cond-> {:request-method method :uri uri}
                  headers (assoc :headers headers)))))

(defn- header [response name]
  (get-in response [:headers name]))

(deftest health-endpoint
  (testing "GET /health answers 200 with a small body"
    (let [response (request :get "/health")]
      (is (= 200 (:status response)))
      (is (= "ok" (:body response))))))

(deftest health-endpoint-head
  (testing "HEAD /health answers 200 for probes that do not GET"
    (is (= 200 (:status (request :head "/health"))))))

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
    (is (= 404 (:status (request :get "/nope"))))))

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
    (is (= 404 (:status (request :get "/.gitkeep"))))))
