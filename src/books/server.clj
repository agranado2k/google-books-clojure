(ns books.server
  (:require [books.db :as db]
            [books.google-books :as google]
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
  database is configured, then start the server with that database and the
  Book search port injected into the handler. A failed migration or an
  unreachable database crashes the boot deliberately — the platform restarts us.

  An absent `:books-api-key` deliberately does NOT: the adapter is built
  anyway and every search answers `:not-configured`, which the search page
  renders. A missing key is a degraded feature, not a dead service.

  Returns the running server."
  [{:keys [http-port database-url db-optional? books-api-key]}]
  (when database-url
    (db/migrate! database-url))
  (start http-port
         (handler/make-app (db/datasource database-url)
                           {:db-optional? (boolean db-optional?)
                            :book-search (google/book-search {:api-key books-api-key})})))

(defn -main [& _args]
  (.join (run {:http-port (port (System/getenv "PORT"))
               :database-url (System/getenv "DATABASE_URL")
               :db-optional? (db-optional? (System/getenv "DB_OPTIONAL"))
               ;; Read here and nowhere else, and never logged: it is a
               ;; credential, and it travels in the URL the adapter builds.
               :books-api-key (System/getenv "GOOGLE_BOOKS_API_KEY")})))
