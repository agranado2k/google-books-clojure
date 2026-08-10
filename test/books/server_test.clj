(ns books.server-test
  "Boot-path tests. `books.server/run` is the only production wiring, so these
  exercise it directly. The database they use — and the command that starts one
  — is in `books.test-db`."
  (:require [books.handler :as handler]
            [books.server :as server]
            [books.test-db :as test-db]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defn- get-path
  "GET `path` off a running server over real HTTP, answering status and body.
  Not `slurp`: that throws away the body of any non-2xx response, and the
  degraded responses are the point."
  [jetty path]
  (let [http-port (.getLocalPort (aget (.getConnectors jetty) 0))
        request (-> (HttpRequest/newBuilder
                     (URI. (str "http://localhost:" http-port path)))
                    (.GET)
                    (.build))
        response (.send (HttpClient/newHttpClient) request
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)}))

(defn- health-get
  "GET /health over real HTTP, as a probe would."
  [jetty]
  (update (get-path jetty "/health") :body json/read-value))

(deftest port-uses-env-value
  (testing "uses the PORT value when present"
    (is (= 8080 (server/port "8080")))))

(deftest port-defaults-when-absent
  (testing "falls back to the local default when PORT is absent"
    (is (= 3000 (server/port nil)))))

(deftest db-optional-defaults-to-false
  (testing "an unset DB_OPTIONAL means a missing DATABASE_URL is a fault"
    (is (false? (server/db-optional? nil)))))

(deftest db-optional-reads-true
  (testing "only an explicit opt-in turns the not-configured state green"
    (is (true? (server/db-optional? "true")))
    (is (true? (server/db-optional? "TRUE")))
    (is (false? (server/db-optional? "")))
    (is (false? (server/db-optional? "yes")))))

(deftest started-server-serves-health
  (testing "a started server answers /health over real HTTP"
    (let [jetty (server/start 0 (handler/make-app nil {:db-optional? true}))]
      (try
        (is (= {:status 200 :body {"status" "ok" "db" "not-configured"}}
               (health-get jetty)))
        (finally (.stop jetty))))))

(deftest full-boot-migrates-and-reports-db-ok
  (testing "run with a DATABASE_URL migrates, then serves /health with db ok"
    (let [jetty (server/run {:http-port 0 :database-url test-db/test-database-url})]
      (try
        (is (= {:status 200 :body {"status" "ok" "db" "ok"}} (health-get jetty)))
        (finally (.stop jetty))))))

(deftest full-boot-without-database-url-is-degraded
  (testing "run with no DATABASE_URL boots, but reports itself unhealthy"
    (let [jetty (server/run {:http-port 0 :database-url nil})]
      (try
        (is (= {:status 503 :body {"status" "degraded" "db" "not-configured"}}
               (health-get jetty)))
        (finally (.stop jetty))))))

(deftest full-boot-without-database-url-is-ok-when-db-is-optional
  (testing "run database-less deliberately: /health stays green"
    (let [jetty (server/run {:http-port 0 :database-url nil :db-optional? true})]
      (try
        (is (= {:status 200 :body {"status" "ok" "db" "not-configured"}}
               (health-get jetty)))
        (finally (.stop jetty))))))

(deftest full-boot-without-an-api-key-serves-a-degraded-search-page
  (testing "no GOOGLE_BOOKS_API_KEY must not crash the boot — the page says so instead"
    (let [jetty (server/run {:http-port 0 :database-url nil :db-optional? true
                             :books-api-key nil})]
      (try
        (let [{:keys [status body]} (get-path jetty "/search?title=clojure")]
          (is (= 200 status))
          (is (str/includes? body "Search is not configured here")))
        (finally (.stop jetty))))))
