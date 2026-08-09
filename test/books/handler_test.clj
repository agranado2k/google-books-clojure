(ns books.handler-test
  (:require [books.handler :as handler]
            [clojure.test :refer [deftest is testing]]))

(deftest health-endpoint
  (testing "GET /health answers 200 with a small body"
    (let [response (handler/app {:request-method :get :uri "/health"})]
      (is (= 200 (:status response)))
      (is (= "ok" (:body response))))))

(deftest health-endpoint-head
  (testing "HEAD /health answers 200 for probes that do not GET"
    (let [response (handler/app {:request-method :head :uri "/health"})]
      (is (= 200 (:status response))))))

(deftest unknown-route
  (testing "an unrouted path answers 404"
    (let [response (handler/app {:request-method :get :uri "/nope"})]
      (is (= 404 (:status response))))))
