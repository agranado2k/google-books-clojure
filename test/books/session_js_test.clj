(ns books.session-js-test
  "There is no browser in this suite (ADR-0005's honest limitation), so
  `session.js` gets no JS test harness. What this asserts instead: the served
  asset's `DEFAULT_RETURN_URL` literal names a path this app actually routes.

  It reads the constant out of the SERVED bytes rather than duplicating the
  literal in Clojure, so the two cannot drift — a wrong constant here would
  otherwise pass a test that quietly checked a different string. Issue #24 was
  exactly that drift: `/library` was never a route this app answered."
  (:require [books.handler :as handler]
            [books.stub-book-search :as stub]
            [books.test-jwt :as test-jwt]
            [clojure.test :refer [deftest is testing]]))

(defn- app []
  (handler/make-app nil {:db-optional? true
                          :book-search (stub/found [stub/brave-and-true])
                          :publishable-key test-jwt/publishable-key}))

(defn- served-session-js
  "The response body of `GET /app/session.js`, as a string. Ring's static-file
  middleware answers a `java.io.File` rather than a string body — `slurp`
  reads either."
  []
  (slurp (:body ((app) {:request-method :get :uri "/app/session.js"}))))

(defn- default-return-url
  "The `DEFAULT_RETURN_URL` literal, read off the served file rather than
  reimplemented — so a future edit to the constant is exercised here, not
  bypassed by it."
  []
  (some-> (re-find #"DEFAULT_RETURN_URL\s*=\s*'([^']*)'" (served-session-js))
          second))

(deftest default-return-url-names-a-route-this-app-answers
  (let [return-url (default-return-url)]
    (testing "the constant was found in the served file"
      (is (some? return-url)))
    (testing "an unauthenticated request to it is never a 404 — a redirect to
             sign-in proves the route exists, a 404 proves it does not"
      (is (not= 404 (:status ((app) {:request-method :get :uri return-url})))
          (str "DEFAULT_RETURN_URL is " (pr-str return-url)
               ", which this app does not route (see handler/gated-paths and "
               "the router in handler/make-app)")))))

(deftest default-return-url-is-the-search-page
  (testing "PRD #1 story 3: sign-in with no explicit return-to lands on search"
    (is (= "/search" (default-return-url)))))
