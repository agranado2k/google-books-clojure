(ns books.handler
  (:require [books.views :as views]
            [reitit.ring :as ring]))

(defn- health [_request]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "ok"})

(defn- landing [_request]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (views/landing-page)})

(def app
  (ring/ring-handler
   (ring/router
    [["/" {:get landing :head landing}]
     ["/health" {:get health :head health}]])
   (ring/routes
    ;; Static assets from resources/public — notably the Tailwind-built
    ;; stylesheet at /css/app.css (generated, not committed).
    (ring/create-resource-handler {:path "/"})
    (ring/create-default-handler))))
