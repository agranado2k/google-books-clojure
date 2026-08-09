(ns books.handler
  (:require [reitit.ring :as ring]))

(defn- health [_request]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "ok"})

(def app
  (ring/ring-handler
   (ring/router
    [["/health" {:get health :head health}]])
   (ring/create-default-handler)))
