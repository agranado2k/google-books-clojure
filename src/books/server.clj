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
                            :book-search (google/book-search {:api-key books-api-key})
                            ;; The handler's last-line fault report prints a
                            ;; message built by whatever threw. `book-search`
                            ;; catches Exception, so an Error from the fetch
                            ;; path is not converted to an outcome and reaches
                            ;; that line with its own text — the key included.
                            ;; This is the only layer that knows the key, so it
                            ;; is the layer that hands down the means to strip
                            ;; it.
                            :redact #(google/redact % books-api-key)})))

(defn -main [& _args]
  (.join (run {:http-port (port (System/getenv "PORT"))
               :database-url (System/getenv "DATABASE_URL")
               :db-optional? (db-optional? (System/getenv "DB_OPTIONAL"))
               ;; Read here and nowhere else, and never logged: it is a
               ;; credential. It travels as a request header, never in a URL
               ;; (ADR-0003 clause 2, amended 2026-08-10).
               :books-api-key (System/getenv "GOOGLE_BOOKS_API_KEY")})))
