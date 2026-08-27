(ns books.bookmarks-test
  "The Bookmark store, against a real Postgres.

  Two things are proved here that no double could prove: that the **database**
  refuses a duplicate pair, and that a statement scoped to one Reader cannot
  reach another's row. Both are properties of the schema and the SQL, so a stub
  standing in for either would be a test of the stub.

  Where the database comes from — and the command that starts one — is in
  `books.test-db`."
  (:require [books.bookmarks :as bookmarks]
            [books.db :as db]
            [books.test-db :as test-db]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]))

(use-fixtures :once test-db/migrated-fixture)
(use-fixtures :each test-db/empty-bookmarks-fixture)

(def ^:private reader-a "user_alice")
(def ^:private reader-b "user_bob")

(def ^:private brave-and-true
  "A fully-described Volume — every field the snapshot keeps."
  {:id "3IGvBQAAQBAJ"
   :title "Clojure for the Brave and True"
   :authors ["Daniel Higginbotham" "A Second Author"]
   :published-date "2015-10-15"
   :description "Learn to program with Clojure, one silly illustration at a time."
   :thumbnail "https://books.example.test/cover.jpg"})

(def ^:private nameless
  "A Volume the Catalog described as thinly as it ever does: an id and nothing
  else. `books.google-books/volume` omits an absent field rather than writing a
  nil-valued key, so this is a shape the store really receives."
  {:id "kQ7fAAAAMAAJ"})

(defn- ds [] (db/datasource test-db/test-database-url))

(defn- rows-for
  "Every stored Bookmark of `reader-id`, read back with raw SQL rather than
  through the namespace under test."
  [reader-id]
  (jdbc/execute! (ds)
                 ["select volume_id, title, authors, thumbnail, published_date
                   from bookmarks where reader_id = ?" reader-id]))

;; ---------------------------------------------------------------------------
;; The snapshot: what a Bookmark keeps about its Volume
;; ---------------------------------------------------------------------------

(deftest a-saved-bookmark-keeps-the-volume-snapshot
  (testing "the four fields a Volume card draws are stored at bookmark time"
    (bookmarks/save! (ds) reader-a brave-and-true)
    (let [[row & more] (rows-for reader-a)]
      (is (empty? more) "one Volume, one row")
      (is (= "3IGvBQAAQBAJ" (:bookmarks/volume_id row)))
      (is (= "Clojure for the Brave and True" (:bookmarks/title row)))
      (is (= ["Daniel Higginbotham" "A Second Author"]
             (vec (.getArray ^java.sql.Array (:bookmarks/authors row)))))
      (is (= "https://books.example.test/cover.jpg" (:bookmarks/thumbnail row)))
      (is (= "2015-10-15" (:bookmarks/published_date row))))))

(deftest the-description-is-deliberately-not-stored
  ;; ADR-0006 clause 4: a paragraph the card clamps to three lines, which the
  ;; bookmarks page has no use for, and the one Volume field that arrives
  ;; carrying HTML. A column added for it later would be a decision, not a
  ;; convenience — this asserts it is absent rather than merely unused.
  (testing "the table has no column for a Volume's description"
    (is (empty? (jdbc/execute! (ds)
                               ["select column_name from information_schema.columns
                                 where table_name = 'bookmarks' and column_name = 'description'"])))))

(deftest a-barely-described-volume-is-still-bookmarkable
  (testing "a Volume with nothing but an id stores as a row with empty fields"
    (bookmarks/save! (ds) reader-a nameless)
    (let [[row] (rows-for reader-a)]
      (is (= "kQ7fAAAAMAAJ" (:bookmarks/volume_id row)))
      (is (nil? (:bookmarks/title row)))
      (is (= [] (vec (.getArray ^java.sql.Array (:bookmarks/authors row))))
          "no authors is an empty list, never a null the reading code has to guard"))))

;; ---------------------------------------------------------------------------
;; Uniqueness, enforced by the database
;; ---------------------------------------------------------------------------

(deftest saving-the-same-volume-twice-leaves-one-row
  (testing "bookmarking a Volume again is not an error and not a second row"
    (bookmarks/save! (ds) reader-a brave-and-true)
    (bookmarks/save! (ds) reader-a brave-and-true)
    (is (= 1 (count (rows-for reader-a))))))

(deftest the-database-itself-refuses-the-duplicate-pair
  ;; The test above would still pass if uniqueness were an application check
  ;; racing itself across two instances. This one bypasses `books.bookmarks`
  ;; entirely and inserts the pair twice with raw SQL: it fails the moment the
  ;; constraint leaves the schema, which is the only place it can be trusted.
  (testing "a second row for the same (Reader, Volume) cannot be written at all"
    (let [insert ["insert into bookmarks (reader_id, volume_id) values (?, ?)"
                  reader-a (:id brave-and-true)]]
      (jdbc/execute-one! (ds) insert)
      (let [e (try (jdbc/execute-one! (ds) insert)
                   (catch org.postgresql.util.PSQLException e e))]
        (is (instance? org.postgresql.util.PSQLException e)
            "the second insert must be refused by Postgres, not silently accepted")
        (is (str/includes? (str/lower-case (ex-message e)) "duplicate key")))
      (is (= 1 (count (rows-for reader-a)))))))

(deftest two-readers-may-bookmark-the-same-volume
  (testing "uniqueness is on the PAIR — a popular Volume is not one Reader's"
    (bookmarks/save! (ds) reader-a brave-and-true)
    (bookmarks/save! (ds) reader-b brave-and-true)
    (is (= 1 (count (rows-for reader-a))))
    (is (= 1 (count (rows-for reader-b))))))

;; ---------------------------------------------------------------------------
;; Removing
;; ---------------------------------------------------------------------------

(deftest removing-a-bookmark-deletes-its-row
  (bookmarks/save! (ds) reader-a brave-and-true)
  (bookmarks/remove! (ds) reader-a (:id brave-and-true))
  (is (empty? (rows-for reader-a))))

(deftest removing-a-bookmark-nobody-has-is-not-an-error
  (testing "a double-click, or a stale page, must not fail"
    (bookmarks/remove! (ds) reader-a "never-bookmarked")
    (is (empty? (rows-for reader-a)))))

(deftest one-reader-cannot-delete-anothers-bookmark
  ;; The delete is scoped by reader_id in its WHERE clause, so this is a no-op
  ;; rather than a permission check that could be forgotten (ADR-0006 clause 3).
  (bookmarks/save! (ds) reader-b brave-and-true)
  (bookmarks/remove! (ds) reader-a (:id brave-and-true))
  (is (= 1 (count (rows-for reader-b))) "Reader B still has their Bookmark"))

;; ---------------------------------------------------------------------------
;; Which of these Volumes has this Reader already bookmarked?
;; ---------------------------------------------------------------------------

(deftest bookmarked-ids-answers-only-this-readers-bookmarks
  (bookmarks/save! (ds) reader-a brave-and-true)
  (bookmarks/save! (ds) reader-b nameless)
  (let [asked [(:id brave-and-true) (:id nameless)]]
    (is (= #{(:id brave-and-true)} (bookmarks/bookmarked-ids (ds) reader-a asked)))
    (is (= #{(:id nameless)} (bookmarks/bookmarked-ids (ds) reader-b asked)))))

(deftest bookmarked-ids-answers-nothing-when-nothing-matches
  (bookmarks/save! (ds) reader-a brave-and-true)
  (is (= #{} (bookmarks/bookmarked-ids (ds) reader-a ["some-other-volume"]))))

(deftest bookmarked-ids-asks-nothing-of-an-empty-page
  (testing "a prompt, an empty result or a failed search has no ids to ask about"
    (is (= #{} (bookmarks/bookmarked-ids (ds) reader-a [])))))

(deftest bookmarked-ids-without-a-database-answers-nothing
  ;; The same posture `books.db/check` takes for a nil datasource: a deployment
  ;; running database-less (DB_OPTIONAL, ADR-0003 clause 7) has no Bookmarks to
  ;; report, and the search page must still render.
  (is (= #{} (bookmarks/bookmarked-ids nil reader-a ["anything"]))))
