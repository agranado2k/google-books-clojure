(ns books.server-test
  (:require [books.server :as server]
            [clojure.test :refer [deftest is testing]]))

(deftest port-selection
  (testing "uses the PORT value when present"
    (is (= 8080 (server/port "8080"))))
  (testing "falls back to the local default when PORT is absent"
    (is (= 3000 (server/port nil)))))
