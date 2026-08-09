(ns books.server
  (:require [books.handler :as handler]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(defn port
  "Port to listen on: the given PORT env value, or a local default."
  [env-port]
  (if env-port (Long/parseLong env-port) 3000))

(defn -main [& _args]
  (jetty/run-jetty handler/app
                   {:host "0.0.0.0"
                    :port (port (System/getenv "PORT"))
                    :join? true}))
