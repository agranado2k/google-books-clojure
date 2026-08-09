(ns books.handler-test
  "Handler-seam tests: pass a Ring request map into the app, assert on the
  response map.

  The db-ok test needs a real local Postgres. Start one with:

    docker run -d --rm --name books-test-pg -p 5544:5432 \\
      -e POSTGRES_PASSWORD=test postgres:16

  and stop it afterwards with `docker stop books-test-pg`. Point
  TEST_DATABASE_URL elsewhere to use a different instance."
  (:require [books.db :as db]
            [books.handler :as handler]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]))

(def test-database-url
  (or (System/getenv "TEST_DATABASE_URL")
      "postgresql://postgres:test@localhost:5544/postgres"))

(defn- health-response [app method]
  (app {:request-method method :uri "/health"}))

(defn- json-body [response]
  (json/read-value (:body response)))

(deftest health-reports-db-ok-when-connected
  (testing "GET /health against a reachable Postgres answers 200 db ok"
    (let [app (handler/make-app (db/datasource test-database-url))
          response (health-response app :get)]
      (is (= 200 (:status response)))
      (is (= {"status" "ok" "db" "ok"} (json-body response))))))

(deftest health-reports-not-configured-without-database-url
  (testing "no DATABASE_URL: the app still serves and /health stays 200"
    (let [app (handler/make-app nil)
          response (health-response app :get)]
      (is (= 200 (:status response)))
      (is (= {"status" "ok" "db" "not-configured"} (json-body response))))))

(deftest health-reports-degraded-when-db-unreachable
  (testing "a configured but unreachable database answers 503 degraded"
    (let [app (handler/make-app (db/datasource "postgresql://u:p@localhost:5545/nope"))
          response (health-response app :get)]
      (is (= 503 (:status response)))
      (is (= {"status" "degraded" "db" "unreachable"} (json-body response))))))

(deftest health-endpoint-head
  (testing "HEAD /health answers 200 for probes that do not GET"
    (let [app (handler/make-app nil)
          response (health-response app :head)]
      (is (= 200 (:status response))))))

(deftest unknown-route
  (testing "an unrouted path answers 404"
    (let [response (handler/app {:request-method :get :uri "/nope"})]
      (is (= 404 (:status response))))))
