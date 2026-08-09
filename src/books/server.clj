(ns books.server
  (:require [books.db :as db]
            [books.handler :as handler]
            [clojure.string :as str]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(defn port
  "Port to listen on: the given PORT env value, or a local default."
  [env-port]
  (if env-port (Long/parseLong env-port) 3000))

(defn db-optional?
  "Whether running without a DATABASE_URL is deliberate. Anything but an
  explicit \"true\" is false: the safe default is that a missing database
  variable is a fault."
  [env-value]
  (= "true" (str/lower-case (or env-value ""))))

(defn start
  "Start the HTTP server on the given port, bound to all interfaces.
  Returns the running server."
  [http-port app]
  (jetty/run-jetty app
                   {:host "0.0.0.0"
                    :port http-port
                    :join? false}))

(defn run
  "The full boot path, and the only place the app is wired: migrate when a
  database is configured, then start the server with that database injected
  into the handler. A failed migration or an unreachable database crashes the
  boot deliberately — the platform restarts us. Returns the running server."
  [{:keys [http-port database-url db-optional?]}]
  (when database-url
    (db/migrate! database-url))
  (start http-port
         (handler/make-app (db/datasource database-url)
                           {:db-optional? (boolean db-optional?)})))

(defn -main [& _args]
  (.join (run {:http-port (port (System/getenv "PORT"))
               :database-url (System/getenv "DATABASE_URL")
               :db-optional? (db-optional? (System/getenv "DB_OPTIONAL"))})))
