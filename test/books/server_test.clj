(ns books.server-test
  (:require [books.server :as server]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]))

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
