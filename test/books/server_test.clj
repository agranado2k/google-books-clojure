(ns books.server-test
  "Boot-path tests. The full-boot test needs the same local Postgres as
  books.handler-test (docker one-liner documented there); it reads
  TEST_DATABASE_URL with the same localhost:5544 default."
  (:require [books.server :as server]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]))

(def test-database-url
  (or (System/getenv "TEST_DATABASE_URL")
      "postgresql://postgres:test@localhost:5544/postgres"))

(deftest port-uses-env-value
  (testing "uses the PORT value when present"
    (is (= 8080 (server/port "8080")))))

(deftest port-defaults-when-absent
  (testing "falls back to the local default when PORT is absent"
    (is (= 3000 (server/port nil)))))

(deftest started-server-serves-health
  (testing "a started server answers /health over real HTTP"
    (let [jetty (server/start 0)
          http-port (.getLocalPort (aget (.getConnectors jetty) 0))]
      (try
        (let [body (slurp (str "http://localhost:" http-port "/health"))]
          (is (= "ok" (get (json/read-value body) "status"))))
        (finally (.stop jetty))))))

(deftest full-boot-migrates-and-reports-db-ok
  (testing "run with a DATABASE_URL migrates, then serves /health with db ok"
    (let [jetty (server/run {:http-port 0 :database-url test-database-url})
          http-port (.getLocalPort (aget (.getConnectors jetty) 0))]
      (try
        (let [body (slurp (str "http://localhost:" http-port "/health"))]
          (is (= {"status" "ok" "db" "ok"} (json/read-value body))))
        (finally (.stop jetty))))))

(deftest full-boot-without-database-url-still-serves
  (testing "run with no DATABASE_URL boots and reports not-configured"
    (let [jetty (server/run {:http-port 0 :database-url nil})
          http-port (.getLocalPort (aget (.getConnectors jetty) 0))]
      (try
        (let [body (slurp (str "http://localhost:" http-port "/health"))]
          (is (= {"status" "ok" "db" "not-configured"} (json/read-value body))))
        (finally (.stop jetty))))))
