(ns books.handler-test
  (:require [books.handler :as handler]
            [clojure.string :as str]
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

(deftest landing-page
  (testing "GET / renders the landing page as HTML"
    (let [response (handler/app {:request-method :get :uri "/"})]
      (is (= 200 (:status response)))
      (is (str/starts-with? (get-in response [:headers "Content-Type"] "")
                            "text/html"))))
  (testing "the landing page explains the product: search + bookmark, sign-in later"
    (let [body (:body (handler/app {:request-method :get :uri "/"}))]
      (is (str/includes? body "Google Books"))
      (is (str/includes? body "Search"))
      (is (str/includes? body "Bookmark"))
      (is (str/includes? body "Sign-in"))))
  (testing "the landing page uses the shared layout: header, footer, stylesheet"
    (let [body (:body (handler/app {:request-method :get :uri "/"}))]
      (is (str/includes? body "<header"))
      (is (str/includes? body "<footer"))
      (is (str/includes? body "/css/app.css")))))

(deftest unknown-route
  (testing "an unrouted path answers 404"
    (let [response (handler/app {:request-method :get :uri "/nope"})]
      (is (= 404 (:status response))))))
