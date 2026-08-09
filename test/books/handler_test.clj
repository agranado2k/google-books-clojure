(ns books.handler-test
  "Handler-seam tests: pass a Ring request map into the app, assert on the
  response map. The database these tests talk to — and the command that starts
  one — is in `books.test-db`."
  (:require [books.db :as db]
            [books.handler :as handler]
            [books.test-db :as test-db]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]))

(defn- health-response [app method]
  (app {:request-method method :uri "/health"}))

(defn- json-body [response]
  (json/read-value (:body response)))

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

(deftest unknown-route
  (testing "an unrouted path answers 404"
    (let [app (handler/make-app nil {})
          response (app {:request-method :get :uri "/nope"})]
      (is (= 404 (:status response))))))
