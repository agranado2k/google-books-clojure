(ns books.bookmarks-test
  "The Bookmark store, against a real Postgres.

  Two things are proved here that no double could prove: that the **database**
  refuses a duplicate pair, and that a statement scoped to one Reader cannot
  reach another's row. Both are properties of the schema and the SQL, so a stub
  standing in for either would be a test of the stub.

  Where the database comes from — and the command that starts one — is in
  `books.test-db`."
  (:require [books.bookmarks :as bookmarks]
            [books.clerk :as clerk]
            [books.db :as db]
            [books.handler :as handler]
            [books.stub-book-search :as stub]
            [books.test-db :as test-db]
            [books.test-jwt :as test-jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc])
  (:import (java.io ByteArrayInputStream)
           (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

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

;; ---------------------------------------------------------------------------
;; Everything one Reader keeps — what the bookmarks page is drawn from
;; ---------------------------------------------------------------------------

(deftest for-reader-answers-the-stored-snapshot
  (testing "the four snapshot fields come back as the Volume shape a card draws"
    (bookmarks/save! (ds) reader-a brave-and-true)
    (is (= [{:id "3IGvBQAAQBAJ"
             :title "Clojure for the Brave and True"
             :authors ["Daniel Higginbotham" "A Second Author"]
             :thumbnail "https://books.example.test/cover.jpg"
             :published-date "2015-10-15"}]
           (bookmarks/for-reader (ds) reader-a)))))

(deftest for-reader-answers-a-barely-described-volume-too
  (testing "the absences a thin catalog entry stored come back as absences"
    (bookmarks/save! (ds) reader-a nameless)
    (is (= [{:id "kQ7fAAAAMAAJ"
             :title nil
             :authors []
             :thumbnail nil
             :published-date nil}]
           (bookmarks/for-reader (ds) reader-a)))))

(deftest for-reader-answers-most-recently-bookmarked-first
  ;; The order is `bookmarked_at desc`, which is the only order a reader can
  ;; predict: the last thing they kept is the thing they are looking for.
  (let [saved-at (fn [volume-id at]
                   (jdbc/execute-one! (ds)
                                      ["insert into bookmarks (reader_id, volume_id, bookmarked_at)
                                        values (?, ?, ?::timestamptz)"
                                       reader-a volume-id at]))]
    (saved-at "oldest" "2026-01-01T00:00:00Z")
    (saved-at "newest" "2026-03-01T00:00:00Z")
    (saved-at "middle" "2026-02-01T00:00:00Z")
    (is (= ["newest" "middle" "oldest"] (mapv :id (bookmarks/for-reader (ds) reader-a))))))

(deftest for-reader-orders-a-tie-by-volume-id
  ;; Two rows written in the same statement share a `bookmarked_at` to the
  ;; microsecond. Without the second sort key the planner picks, and the page
  ;; would reshuffle itself between two identical requests.
  (jdbc/execute-one! (ds)
                     ["insert into bookmarks (reader_id, volume_id, bookmarked_at)
                       values (?, 'b', now()), (?, 'a', now()), (?, 'c', now())"
                      reader-a reader-a reader-a])
  (is (= ["a" "b" "c"] (mapv :id (bookmarks/for-reader (ds) reader-a)))))

(deftest for-reader-answers-only-this-readers-bookmarks
  (bookmarks/save! (ds) reader-a brave-and-true)
  (bookmarks/save! (ds) reader-b nameless)
  (is (= [(:id brave-and-true)] (mapv :id (bookmarks/for-reader (ds) reader-a))))
  (is (= [(:id nameless)] (mapv :id (bookmarks/for-reader (ds) reader-b)))))

(deftest for-reader-answers-nothing-for-a-reader-who-has-kept-nothing
  (bookmarks/save! (ds) reader-b brave-and-true)
  (is (= [] (bookmarks/for-reader (ds) reader-a))))

(deftest for-reader-without-a-database-answers-nothing
  ;; The posture `bookmarked-ids` and `books.db/check` both take for a nil
  ;; datasource: a deployment running database-less has no Bookmarks to list.
  (is (= [] (bookmarks/for-reader nil reader-a))))

;; ---------------------------------------------------------------------------
;; The toggle, at the handler seam.
;;
;; A Ring request in, a response map out, against the real Postgres above and
;; the REAL Clerk verifier — the same signature check, `azp` comparison and
;; expiry arithmetic production runs, given only the throwaway key set in
;; `books.test-jwt`. A Reader is a signed token here, never a parameter, which
;; is the only way the isolation tests below mean anything.
;; ---------------------------------------------------------------------------

(def ^:private bookmarks-uri "/bookmarks")

(defn- app
  "The app under test: this suite's Postgres, and the gate a deploy really runs."
  []
  (handler/make-app (ds)
                    {:book-search (stub/found [brave-and-true nameless])
                     :publishable-key test-jwt/publishable-key
                     :session-check (clerk/session-check
                                     {:publishable-key test-jwt/publishable-key
                                      :authorized-party test-jwt/authorized-party
                                      :fetch (fn [_url] (test-jwt/jwks))})}))

(defn- token-for
  "A valid session token identifying `reader-id`. The Reader IS the `sub`
  claim, so this is the whole of what makes two Readers two."
  [reader-id]
  (test-jwt/token {:sub reader-id}))

(defn- encode [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn- volume-form
  "The form the results card carries, urlencoded. The authors repeat the same
  name, which is how a list travels through a form and what the handler has to
  read back."
  [{:keys [id title authors published-date thumbnail]}]
  (->> (concat [["volume" id]]
               (when title [["title" title]])
               (map (fn [author] ["author" author]) authors)
               (when published-date [["published-date" published-date]])
               (when thumbnail [["thumbnail" thumbnail]]))
       (map (fn [[name value]] (str name "=" (encode value))))
       (str/join "&")))

(defn- toggle
  "POST or DELETE /bookmarks, driven the way htmx really drives it: a POST puts
  its parameters in the body, a DELETE in the URL (htmx 2's
  `methodsThatUseUrlParams` is `[\"get\" \"delete\"]`)."
  [method volume {:keys [token cookie]}]
  (let [encoded (volume-form volume)
        headers (cond-> {"hx-request" "true"}
                  (= :post method) (assoc "content-type" "application/x-www-form-urlencoded")
                  token (assoc "authorization" (str "Bearer " token))
                  cookie (assoc "cookie" (str "__session=" cookie)))]
    ((app) (cond-> {:request-method method :uri bookmarks-uri :headers headers}
             (= :post method) (assoc :body (ByteArrayInputStream.
                                            (.getBytes encoded "UTF-8")))
             (= :delete method) (assoc :query-string encoded)))))

(defn- control-state
  "The state the returned bookmark control rendered in — the one marker the
  toggle tests assert on, exactly as the results region carries `data-state`."
  [response]
  (second (re-find #"data-bookmark=\"([a-z-]+)\"" (str (:body response)))))

(defn- search-page
  "GET /search as `reader-id`, as a whole page."
  [reader-id]
  ((app) {:request-method :get :uri "/search" :query-string "title=clojure"
          :headers {"authorization" (str "Bearer " (token-for reader-id))}}))

(defn- card-states
  "Volume id -> the state its bookmark control rendered in, across the page.
  Read off the control's own two markers, so neither the order of the cards nor
  the order of the attributes can make this pass by accident."
  [response]
  (into {}
        (map (fn [control]
               [(second (re-find #"data-volume=\"([^\"]+)\"" control))
                (second (re-find #"data-bookmark=\"([a-z-]+)\"" control))]))
        (re-seq #"<form\b[^>]*data-bookmark[^>]*>" (str (:body response)))))

;; ---------------------------------------------------------------------------
;; Bookmarking and unbookmarking, in place
;; ---------------------------------------------------------------------------

(deftest bookmarking-a-result-flips-its-control-and-stores-the-volume
  (let [response (toggle :post brave-and-true {:token (token-for reader-a)})]
    (is (= 200 (:status response)))
    (is (= "bookmarked" (control-state response)))
    (testing "the answer is the control alone, for htmx to swap in place"
      (is (not (str/includes? (str (:body response)) "<html")))
      (is (not (str/includes? (str (:body response)) "id=\"results\""))))
    (testing "and the Volume is now kept, snapshot and all"
      (let [[row] (rows-for reader-a)]
        (is (= "3IGvBQAAQBAJ" (:bookmarks/volume_id row)))
        (is (= "Clojure for the Brave and True" (:bookmarks/title row)))
        (is (= ["Daniel Higginbotham" "A Second Author"]
               (vec (.getArray ^java.sql.Array (:bookmarks/authors row))))
            "the repeated form field arrives as a list, not as one joined string")))))

(deftest unbookmarking-flips-the-control-back-and-drops-the-row
  (bookmarks/save! (ds) reader-a brave-and-true)
  (let [response (toggle :delete brave-and-true {:token (token-for reader-a)})]
    (is (= 200 (:status response)))
    (is (= "not-bookmarked" (control-state response)))
    (is (empty? (rows-for reader-a)))))

(deftest bookmarking-the-same-volume-twice-still-leaves-one-row
  (testing "a double click, or a second tab, answers the same thing both times"
    (let [reader (token-for reader-a)
          first-response (toggle :post brave-and-true {:token reader})
          second-response (toggle :post brave-and-true {:token reader})]
      (is (= 200 (:status first-response)))
      (is (= 200 (:status second-response)))
      (is (= "bookmarked" (control-state second-response)))
      (is (= 1 (count (rows-for reader-a)))))))

(deftest a-toggle-that-names-no-volume-changes-nothing
  (testing "there is no such request a card could make, and it stores nothing"
    (let [response (toggle :post {} {:token (token-for reader-a)})]
      (is (= 400 (:status response)))
      (is (empty? (rows-for reader-a))))))

;; ---------------------------------------------------------------------------
;; Reader isolation, proved with two signed-in Readers
;; ---------------------------------------------------------------------------

(deftest one-reader-cannot-remove-anothers-bookmark-over-http
  (bookmarks/save! (ds) reader-b brave-and-true)
  (let [response (toggle :delete brave-and-true {:token (token-for reader-a)})]
    (testing "Reader A's delete touches only Reader A"
      (is (= 200 (:status response)))
      (is (= 1 (count (rows-for reader-b))) "Reader B still has their Bookmark")
      (is (empty? (rows-for reader-a))))))

(deftest one-reader-never-sees-anothers-bookmarks-on-the-results
  (bookmarks/save! (ds) reader-b brave-and-true)
  (testing "Reader B's own results show it kept"
    (is (= "bookmarked" (get (card-states (search-page reader-b)) (:id brave-and-true)))))
  (testing "and Reader A's show the very same Volume unkept"
    (is (= "not-bookmarked" (get (card-states (search-page reader-a)) (:id brave-and-true))))))

(deftest the-results-mark-exactly-what-this-reader-has-kept
  (bookmarks/save! (ds) reader-a brave-and-true)
  (is (= {(:id brave-and-true) "bookmarked"
          (:id nameless) "not-bookmarked"}
         (card-states (search-page reader-a)))))

;; ---------------------------------------------------------------------------
;; Who may toggle at all
;; ---------------------------------------------------------------------------

(deftest a-signed-out-toggle-is-refused-and-stores-nothing
  (let [response (toggle :post brave-and-true {})]
    (is (= 401 (:status response)) "an htmx request is told to navigate, not redirected")
    (is (str/starts-with? (get-in response [:headers "HX-Redirect"]) "/sign-in"))
    (is (empty? (rows-for reader-a)))))

(deftest a-cookie-alone-cannot-change-anything
  ;; The CSRF case, and the reason ADR-0007 exists: a cross-site form carries
  ;; our own __session cookie, so its token verifies and its `azp` is ours.
  ;; Refusing the cookie as a credential for a WRITE is what makes the forgery
  ;; impossible, and it holds without depending on a cookie attribute Clerk
  ;; sets and this repo cannot see.
  (testing "a valid session cookie with no bearer header writes nothing"
    (let [response (toggle :post brave-and-true {:cookie (token-for reader-a)})]
      (is (= 401 (:status response)))
      (is (empty? (rows-for reader-a)))))
  (testing "and neither does it delete anything"
    (bookmarks/save! (ds) reader-a brave-and-true)
    (is (= 401 (:status (toggle :delete brave-and-true {:cookie (token-for reader-a)}))))
    (is (= 1 (count (rows-for reader-a))))))

(deftest a-cookie-still-reads-a-gated-page
  ;; The narrowing is on mutations alone: a document navigation carries only the
  ;; cookie, and reading changes nothing.
  (let [response ((app) {:request-method :get :uri "/search"
                         :query-string "title=clojure"
                         :headers {"cookie" (str "__session=" (token-for reader-a))}})]
    (is (= 200 (:status response)))))

(deftest a-forged-token-cannot-toggle
  (testing "the classic forgeries are refused on the write path too"
    (doseq [forgery [(test-jwt/alg-none-token)
                     (test-jwt/hs256-token)
                     (test-jwt/token-signed-by-a-stranger)
                     (test-jwt/expired-token)]]
      (is (= 401 (:status (toggle :post brave-and-true {:token forgery}))))
      (is (empty? (rows-for "user_2readerid"))))))
