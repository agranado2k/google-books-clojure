(ns books.db-test
  "Unit tests for the DATABASE_URL -> db-spec conversion and the connectivity
  probe, plus the migration path against a real local Postgres. Where that
  database comes from — and the command that starts one — is in
  `books.test-db`."
  (:require [books.db :as db]
            [books.test-db :as test-db]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]))

(use-fixtures :once test-db/reset-migrations-fixture)

;; ---------------------------------------------------------------------------
;; DATABASE_URL -> db-spec map
;; ---------------------------------------------------------------------------

(deftest builds-a-spec-map-from-a-full-railway-url
  (testing "user:pass@host:port/db becomes spec keys, never a URL string"
    (is (= {:dbtype "postgresql" :host "db.example" :port 5432 :dbname "books"
            :user "alice" :password "s3cret"}
           (db/db-spec "postgresql://alice:s3cret@db.example:5432/books")))))

(deftest accepts-the-short-postgres-scheme
  (testing "postgres:// is the same contract as postgresql://"
    (is (= {:dbtype "postgres" :host "db.example" :port 5432 :dbname "books"
            :user "alice" :password "s3cret"}
           (db/db-spec "postgres://alice:s3cret@db.example:5432/books")))))

(deftest keeps-a-password-containing-a-colon
  (testing "the userinfo splits on the FIRST colon only, so ':' survives"
    (is (= "s3cr:et" (:password (db/db-spec "postgresql://alice:s3cr:et@h/d"))))))

(deftest decodes-percent-escaped-credentials
  (testing "credentials travel as decoded map values, not as URL text"
    (let [spec (db/db-spec "postgresql://u:p%40ss%2Fw@h:9999/d")]
      (is (= "u" (:user spec)))
      (is (= "p@ss/w" (:password spec))))))

(deftest omits-an-absent-port
  (testing "no port in the source URL leaves :port out (never the -1 sentinel)"
    (is (= {:dbtype "postgresql" :host "h" :dbname "d" :user "u" :password "p"}
           (db/db-spec "postgresql://u:p@h/d")))))

(deftest handles-a-url-without-credentials
  (testing "no userinfo means no :user/:password keys"
    (is (= {:dbtype "postgresql" :host "h" :port 5432 :dbname "d"}
           (db/db-spec "postgresql://h:5432/d")))))

(deftest handles-a-user-without-a-password
  (testing "a userinfo with no colon is a user, and no :password key appears"
    (let [spec (db/db-spec "postgresql://alice@h:5432/d")]
      (is (= "alice" (:user spec)))
      (is (not (contains? spec :password))))))

(deftest passes-query-parameters-through-unchanged
  (testing "sslmode is the operator's call: carried through, never forced"
    (is (= "require" (:sslmode (db/db-spec "postgresql://u:p@h:5432/d?sslmode=require"))))))

(deftest rejects-a-non-postgres-scheme
  (testing "anything but postgres/postgresql is a configuration error"
    (let [e (try (db/db-spec "mysql://u:s3cret@h:3306/d")
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= {:dbtype "mysql"} (ex-data e)))
      (is (not (str/includes? (str (ex-message e) (pr-str (ex-data e))) "s3cret"))
          "the error names the dbtype only — never the URL that carries the password"))))

(deftest rejects-a-malformed-url-without-echoing-it
  (testing "URISyntaxException's own message quotes the whole URL; ours must not"
    (let [e (try (db/db-spec "postgresql://u:s3cret@h:5432/d b")
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= "malformed DATABASE_URL" (ex-message e)))
      (is (not (str/includes? (pr-str [(ex-message e) (ex-data e)]) "s3cret"))))))

;; ---------------------------------------------------------------------------
;; The connection spec and the datasource built from it
;; ---------------------------------------------------------------------------

(deftest connection-spec-bounds-every-wait
  (testing "connect, login and socket waits are bounded, in seconds"
    (let [spec (db/connection-spec "postgresql://u:p@h:5432/d")]
      (is (= 5 (:connectTimeout spec)))
      (is (= 5 (:loginTimeout spec)))
      (is (= 5 (:socketTimeout spec))))))

(deftest connection-spec-lets-the-url-override-a-timeout
  (testing "the timeouts are defaults, so an operator can tune them per deploy"
    (is (= "20" (:connectTimeout (db/connection-spec
                                  "postgresql://u:p@h:5432/d?connectTimeout=20"))))))

(deftest datasource-never-carries-credentials-in-its-printed-form
  (testing "logs and error reports print the datasource, so it must be clean"
    (let [ds (db/datasource "postgresql://alice:s3cret@db.example:5432/books")]
      (is (not (str/includes? (str ds) "s3cret")))
      (is (not (str/includes? (pr-str ds) "s3cret"))))))

(deftest datasource-is-nil-without-a-database-url
  (is (nil? (db/datasource nil))))

;; ---------------------------------------------------------------------------
;; check / checker
;; ---------------------------------------------------------------------------

(deftest check-reports-not-configured-for-a-nil-datasource
  (is (= :not-configured (db/check nil))))

(deftest check-reports-ok-against-a-reachable-database
  (is (= :ok (db/check (db/datasource test-db/test-database-url)))))

(deftest check-reports-unreachable-and-logs-one-clean-line
  (testing "a failed probe logs the exception class and message, and nothing else"
    (let [captured (java.io.StringWriter.)
          state (binding [*err* captured]
                  (db/check (db/datasource (test-db/unreachable-database-url))))
          logged (str captured)]
      (is (= :unreachable state))
      (is (= 1 (count (str/split-lines logged))) "one line, not a stack trace")
      (is (str/includes? logged "PSQLException"))
      (is (not (str/includes? logged "password=")))
      (is (not (str/includes? logged ":p@"))))))

(deftest checker-caches-the-probe-result-for-the-ttl
  (testing "unauthenticated /health must not open a connection per request"
    (let [now (atom 0)
          probes (atom 0)
          check (db/checker ::datasource {:clock #(deref now)
                                          :ttl-ms 5000
                                          :probe (fn [_] (swap! probes inc) :ok)})]
      (is (= :ok (check)))
      (is (= :ok (check)))
      (is (= 1 @probes) "the second call is served from the cache")
      (reset! now 4999)
      (check)
      (is (= 1 @probes) "still inside the TTL")
      (reset! now 5000)
      (check)
      (is (= 2 @probes) "the TTL expired, so the database is probed again"))))

(deftest checker-reflects-a-state-change-after-the-ttl
  (let [now (atom 0)
        states (atom [:ok :unreachable])
        check (db/checker ::datasource {:clock #(deref now)
                                        :ttl-ms 5000
                                        :probe (fn [_] (ffirst (swap-vals! states rest)))})]
    (is (= :ok (check)))
    (reset! now 6000)
    (is (= :unreachable (check)))))

;; ---------------------------------------------------------------------------
;; Migrations
;; ---------------------------------------------------------------------------

(deftest migrate-records-the-baseline-migration
  (testing "migrate! runs Migratus and records the baseline in schema_migrations"
    (db/migrate! test-db/test-database-url)
    (let [ds (db/datasource test-db/test-database-url)]
      (is (= 1 (:n (jdbc/execute-one!
                    ds
                    ["select count(*) as n from schema_migrations where id = ?"
                     20260809120000])))))))
