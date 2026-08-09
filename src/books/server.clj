(ns books.server
  (:require [books.handler :as handler]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(defn port
  "Port to listen on: the given PORT env value, or a local default."
  [env-port]
  (if env-port (Long/parseLong env-port) 3000))

(defn start
  "Start the HTTP server on the given port, bound to all interfaces.
  Returns the running server."
  [http-port]
  (jetty/run-jetty handler/app
                   {:host "0.0.0.0"
                    :port http-port
                    :join? false}))

(defn -main [& _args]
  (.join (start (port (System/getenv "PORT")))))
