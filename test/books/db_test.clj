(ns books.db-test
  "Unit tests for the DATABASE_URL -> JDBC URL conversion, plus the
  migration path against a real local Postgres. Start one with:

    docker run -d --rm --name books-test-pg -p 5544:5432 \\
      -e POSTGRES_PASSWORD=test postgres:16

  and stop it afterwards with `docker stop books-test-pg`. Point
  TEST_DATABASE_URL elsewhere to use a different instance."
  (:require [books.db :as db]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]))

(def test-database-url
  (or (System/getenv "TEST_DATABASE_URL")
      "postgresql://postgres:test@localhost:5544/postgres"))

(deftest converts-a-full-railway-url
  (testing "user:pass@host:port/db moves the credentials into query params"
    (is (= "jdbc:postgresql://db.example:5432/books?user=alice&password=s3cret"
           (db/database-url->jdbc-url "postgresql://alice:s3cret@db.example:5432/books")))))

(deftest accepts-the-short-postgres-scheme
  (testing "postgres:// is the same contract as postgresql://"
    (is (= "jdbc:postgresql://db.example:5432/books?user=alice&password=s3cret"
           (db/database-url->jdbc-url "postgres://alice:s3cret@db.example:5432/books")))))

(deftest preserves-url-encoded-password-characters
  (testing "a percent-encoded password survives the round trip still encoded"
    (is (= "jdbc:postgresql://h:9999/d?user=u&password=p%40ss%2Fw"
           (db/database-url->jdbc-url "postgresql://u:p%40ss%2Fw@h:9999/d")))))

(deftest omits-an-absent-port
  (testing "no port in the source URL means no port in the JDBC URL"
    (is (= "jdbc:postgresql://h/d?user=u&password=p"
           (db/database-url->jdbc-url "postgresql://u:p@h/d")))))

(deftest handles-a-url-without-credentials
  (testing "no userinfo means no user/password query params"
    (is (= "jdbc:postgresql://h:5432/d"
           (db/database-url->jdbc-url "postgresql://h:5432/d")))))

(deftest carries-existing-query-params-through
  (testing "params already on the URL (e.g. sslmode) are kept"
    (is (= "jdbc:postgresql://h:5432/d?sslmode=require&user=u&password=p"
           (db/database-url->jdbc-url "postgresql://u:p@h:5432/d?sslmode=require")))))

(deftest rejects-a-non-postgres-scheme
  (testing "anything but postgres/postgresql is a configuration error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (db/database-url->jdbc-url "mysql://u:p@h:3306/d")))))

(deftest migrate-applies-the-baseline-migration
  (testing "migrate! runs the Migratus migrations, creating schema_baseline"
    (db/migrate! test-database-url)
    (let [ds (db/datasource test-database-url)]
      (is (= 1 (:n (jdbc/execute-one!
                    ds
                    ["select count(*) as n from information_schema.tables
                      where table_schema = 'public' and table_name = 'schema_baseline'"])))))))
