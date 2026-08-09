(ns books.test-db
  "Shared Postgres test support: the one place the suite learns where its test
  database lives, how to start one, and how to reset migration state.

  Start a local instance with:

    docker run -d --rm --name books-test-pg -p 5544:5432 \\
      -e POSTGRES_PASSWORD=test postgres:16

  and stop it afterwards with `docker stop books-test-pg`. Point
  TEST_DATABASE_URL elsewhere to use a different instance.

  Not a test namespace: the name deliberately does not end in `-test`, so the
  runner loads it only as a dependency of the namespaces that require it."
  (:require [books.db :as db]
            [next.jdbc :as jdbc])
  (:import (java.net ServerSocket)))

(def test-database-url
  "The database every test in this suite talks to."
  (or (System/getenv "TEST_DATABASE_URL")
      "postgresql://postgres:test@localhost:5544/postgres"))

(def ^:private start-command
  "docker run -d --rm --name books-test-pg -p 5544:5432 -e POSTGRES_PASSWORD=test postgres:16")

(defn require-postgres!
  "Fail fast when the test database is unreachable. An absent database is a
  setup error rather than a test failure, so it gets the command that fixes it
  instead of a connection stack trace."
  []
  (try
    (jdbc/execute-one! (db/datasource test-database-url) ["SELECT 1"])
    (catch Exception e
      (throw (ex-info (str "Postgres not reachable at " test-database-url
                           " — run: " start-command)
                      {:test-database-url test-database-url}
                      e)))))

(defn reset-migrations-fixture
  "A `:once` fixture that drops Migratus's tracking table before the namespace
  runs, so `migrate!` is genuinely re-proven on every run rather than
  short-circuited by state a previous run left behind."
  [f]
  (require-postgres!)
  (jdbc/execute-one! (db/datasource test-database-url)
                     ["drop table if exists schema_migrations"])
  (f))

(defn closed-port
  "A port that was bound and then released, so nothing is listening on it.
  Beats hard-coding a port number and hoping it is free."
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn unreachable-database-url
  "A well-formed DATABASE_URL pointing at a port nothing listens on."
  []
  (str "postgresql://u:p@localhost:" (closed-port) "/nope"))
