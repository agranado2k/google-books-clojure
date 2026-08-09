(ns books.db-test
  "Unit tests for the DATABASE_URL -> JDBC URL conversion.

  The conversion is pure: Railway hands the app a libpq-style URL
  (postgresql://user:pass@host:port/db) and the JDBC driver wants
  jdbc:postgresql://host:port/db?user=...&password=...."
  (:require [books.db :as db]
            [clojure.test :refer [deftest is testing]]))

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
