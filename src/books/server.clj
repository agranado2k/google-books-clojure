(ns books.server
  (:require [books.db :as db]
            [books.handler :as handler]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(defn port
  "Port to listen on: the given PORT env value, or a local default."
  [env-port]
  (if env-port (Long/parseLong env-port) 3000))

(defn start
  "Start the HTTP server on the given port, bound to all interfaces.
  Returns the running server."
  ([http-port] (start http-port handler/app))
  ([http-port app]
   (jetty/run-jetty app
                    {:host "0.0.0.0"
                     :port http-port
                     :join? false})))

(defn run
  "The full boot path: migrate when a database is configured, then start
  the server with that database wired into the handler. A nil
  database-url boots database-less. Returns the running server."
  [{:keys [http-port database-url]}]
  (when database-url
    (db/migrate! database-url))
  (start http-port (handler/make-app (db/datasource database-url))))

(defn -main [& _args]
  (.join (run {:http-port (port (System/getenv "PORT"))
               :database-url (System/getenv "DATABASE_URL")})))
