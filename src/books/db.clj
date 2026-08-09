(ns books.db
  "Database access: DATABASE_URL conversion, connectivity checks, migrations.

  Railway (and Heroku-style platforms) hand the app a libpq URL:
  postgresql://user:pass@host:port/db. This namespace turns that into a
  next.jdbc **db-spec map**, where the credentials are ordinary map values.

  That shape is the security contract: a credentialed JDBC URL string is one
  `println`, log line or exception message away from leaking the password,
  and nothing downstream can redact a secret it cannot find. Migratus, for
  one, censors `:password` in a spec map and cannot censor a `:jdbcUrl`
  string. Credentials therefore never appear in a URL string here."
  (:require [clojure.string :as str]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection])
  (:import (java.net URI URISyntaxException)))

(def ^:private supported-dbtypes #{"postgres" "postgresql"})

(def ^:private timeouts
  "pgjdbc connection properties, in seconds. Every wait is bounded so that an
  unreachable database fails fast instead of pinning a request thread."
  {:connectTimeout 5
   :loginTimeout 5
   :socketTimeout 5})

(def check-ttl-ms
  "How long a connectivity result stays good. /health is unauthenticated, so
  the probe is cached rather than run once per request."
  5000)

(defn- parse-uri
  "Parse the URL, converting the JDK's failure into one that is safe to log:
  URISyntaxException quotes the whole input, and the input embeds the
  password."
  ^URI [database-url]
  (try
    (URI. database-url)
    (catch URISyntaxException _
      (throw (ex-info "malformed DATABASE_URL" {})))))

(defn db-spec
  "Convert a postgresql:// (or postgres://) DATABASE_URL into a next.jdbc
  db-spec map. Pure.

  `next.jdbc.connection/uri->db-spec` does the bulk of the work; this adds the
  three things it gets wrong for our purposes:

  * an unsupported dbtype is rejected, naming the dbtype and nothing else;
  * an absent port arrives as the sentinel -1, which is dropped rather than
    passed to the driver;
  * the userinfo is re-split on the FIRST colon only, so a password containing
    ':' survives (uri->db-spec splits it unbounded and truncates).

  Query parameters — `sslmode` included — are carried through untouched."
  [database-url]
  (let [uri (parse-uri database-url)
        spec (connection/uri->db-spec database-url)
        dbtype (:dbtype spec)]
    (when-not (contains? supported-dbtypes dbtype)
      (throw (ex-info "DATABASE_URL must use the postgresql:// or postgres:// scheme"
                      {:dbtype dbtype})))
    (let [[user password] (some-> (.getUserInfo uri) (str/split #":" 2))]
      (cond-> (dissoc spec :user :password)
        (not (pos? (or (:port spec) -1))) (dissoc :port)
        (seq user) (assoc :user user)
        (seq password) (assoc :password password)))))

(defn connection-spec
  "The db-spec the app actually connects with: the URL's own settings on top
  of the default timeouts, so an operator can tune them per deploy."
  [database-url]
  (merge timeouts (db-spec database-url)))

(defn datasource
  "A datasource for the given DATABASE_URL, or nil when the URL is absent
  (the service runs database-less until one is provisioned)."
  [database-url]
  (when database-url
    (jdbc/get-datasource (connection-spec database-url))))

(defn migrate!
  "Run all pending Migratus migrations (resources/migrations on the
  classpath) against the database named by the given DATABASE_URL.

  `:db` is the spec map, not a URL: Migratus logs its config on every
  migration and only knows how to censor a `:password` key."
  [database-url]
  (migratus/migrate {:store :database
                     :migration-dir "migrations"
                     :db (connection-spec database-url)}))

(defn check
  "Connectivity state of the given datasource:
  :not-configured (nil datasource), :ok (ping succeeded), :unreachable."
  [ds]
  (if (nil? ds)
    :not-configured
    (try
      (jdbc/execute-one! ds ["SELECT 1"])
      :ok
      (catch Exception e
        ;; Class and message only. No stack trace, and no datasource or URL —
        ;; the parse guard above keeps the password out of our own messages,
        ;; and the driver's messages name the host, never the credentials.
        (binding [*out* *err*]
          (println (str "db check failed: " (.getName (class e)) ": " (ex-message e))))
        :unreachable))))

(defn checker
  "A zero-arity fn answering the connectivity state of `ds`, caching the
  result for `:ttl-ms`. `:probe` and `:clock` exist so the caching behaviour
  is testable without a database or a wall clock."
  ([ds] (checker ds {}))
  ([ds {:keys [probe clock ttl-ms]
        :or {probe check
             clock #(System/currentTimeMillis)
             ttl-ms check-ttl-ms}}]
   (let [cache (atom nil)]
     (fn []
       (let [now (clock)
             {:keys [at state]} @cache]
         (if (and at (< (- now at) ttl-ms))
           state
           (:state (reset! cache {:at now :state (probe ds)}))))))))
