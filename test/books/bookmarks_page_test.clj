(ns books.bookmarks-page-test
  "The bookmarks page at the handler seam: a Ring request in, a response map
  out, against a real Postgres and the REAL Clerk verifier.

  Two properties here can only be proved this way. **Reader isolation** is a
  `where` clause, so it needs two genuinely different signed-in Readers rather
  than a double that answers whoever it is asked about. And **the page owes the
  Catalog nothing** (ADR-0006 clause 4), which is only a fact if the Book search
  port can be made to fail while the page still renders in full.

  Where the database comes from — and the command that starts one — is in
  `books.test-db`."
  (:require [books.bookmarks :as bookmarks]
            [books.catalog :as catalog]
            [books.clerk :as clerk]
            [books.db :as db]
            [books.handler :as handler]
            [books.test-db :as test-db]
            [books.test-jwt :as test-jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(use-fixtures :once test-db/migrated-fixture)
(use-fixtures :each test-db/empty-bookmarks-fixture)

(def ^:private bookmarks-uri "/bookmarks")
(def ^:private sign-in-path "/sign-in")

(def ^:private reader-a "user_alice")
(def ^:private reader-b "user_bob")

(def ^:private brave-and-true
  {:id "3IGvBQAAQBAJ"
   :title "Clojure for the Brave and True"
   :authors ["Daniel Higginbotham" "A Second Author"]
   :published-date "2015-10-15"
   :description "Learn to program with Clojure, one silly illustration at a time."
   :thumbnail "https://books.example.test/cover.jpg"})

(def ^:private programming-clojure
  {:id "CVBhtQAACAAJ" :title "Programming Clojure" :authors ["Alex Miller"]})

(defn- ds [] (db/datasource test-db/test-database-url))

(def ^:private exploding-book-search
  "A Book search that cannot answer at all. The page must not notice — every
  string it draws came out of the database."
  (fn [_query] (throw (ex-info "the catalog is on fire" {}))))

(defn- app
  "The app under test: this suite's Postgres, the gate a deploy really runs, and
  a Book search that throws unless a test says otherwise."
  ([] (app exploding-book-search))
  ([book-search]
   (handler/make-app (ds)
                     {:book-search book-search
                      :publishable-key test-jwt/publishable-key
                      :session-check (clerk/session-check
                                      {:publishable-key test-jwt/publishable-key
                                       :authorized-party test-jwt/authorized-party
                                       :fetch (fn [_url] (test-jwt/jwks))})})))

(defn- token-for
  "A valid session token identifying `reader-id`. The Reader IS the `sub` claim,
  so this is the whole of what makes two Readers two."
  [reader-id]
  (test-jwt/token {:sub reader-id}))

(defn- GET
  "GET the bookmarks page as `reader-id`, or signed out when it is nil."
  ([reader-id] (GET reader-id (app)))
  ([reader-id app]
   (app (cond-> {:request-method :get :uri bookmarks-uri}
          reader-id (assoc :headers {"authorization" (str "Bearer " (token-for reader-id))})))))

(defn- encode [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn- remove-bookmark
  "DELETE /bookmarks the way the page's own control drives it: htmx puts a
  DELETE's parameters in the URL, and the control asks for the LIST back rather
  than for a re-rendered toggle."
  [reader-id volume-id]
  ((app) {:request-method :delete
          :uri bookmarks-uri
          :query-string (str "volume=" (encode volume-id) "&answer=list")
          :headers {"hx-request" "true"
                    "authorization" (str "Bearer " (token-for reader-id))}}))

(defn- body [response] (str (:body response)))

(defn- region-tag
  "The bookmarks region's own opening tag, or nil when the answer is not a
  region at all. Found by its id, because hiccup2 sorts attributes and the order
  they appear in is not the view's to promise."
  [response]
  (re-find #"<div\b[^>]*id=\"bookmarks\"[^>]*>" (body response)))

(defn- state-of
  "The `data-state` the bookmarks region rendered with — `bookmarks` or `empty`,
  the two states this page has, asserted on exactly as the search page's results
  region is."
  [response]
  (second (re-find #"data-state=\"([a-z-]+)\"" (str (region-tag response)))))

(defn- listed
  "The Volume ids the page listed, in the order it listed them. Read off each
  card's own control, so neither the markup around it nor the order of the
  attributes can make an assertion pass by accident."
  [response]
  (mapv #(second (re-find #"data-volume=\"([^\"]+)\"" %))
        (re-seq #"<form\b[^>]*data-volume[^>]*>" (body response))))

;; ---------------------------------------------------------------------------
;; What the page lists
;; ---------------------------------------------------------------------------

(deftest the-page-lists-this-readers-bookmarks
  (bookmarks/save! (ds) reader-a brave-and-true)
  (let [response (GET reader-a)]
    (is (= 200 (:status response)))
    (is (str/starts-with? (get-in response [:headers "Content-Type"]) "text/html"))
    (is (= "bookmarks" (state-of response)))
    (is (= [(:id brave-and-true)] (listed response)))))

(deftest the-page-draws-the-snapshot-and-nothing-else
  (bookmarks/save! (ds) reader-a brave-and-true)
  (let [rendered (body (GET reader-a))]
    (testing "the four stored fields are on the page"
      (is (str/includes? rendered "Clojure for the Brave and True"))
      (is (str/includes? rendered "Daniel Higginbotham, A Second Author"))
      (is (str/includes? rendered "2015-10-15"))
      (is (str/includes? rendered "https://books.example.test/cover.jpg")))
    (testing "and the description is not, because ADR-0006 never stored one"
      (is (not (str/includes? rendered "silly illustration"))))))

(deftest the-page-lists-most-recently-bookmarked-first
  (bookmarks/save! (ds) reader-a programming-clojure)
  (bookmarks/save! (ds) reader-a brave-and-true)
  (is (= [(:id brave-and-true) (:id programming-clojure)] (listed (GET reader-a)))))

(deftest the-page-is-framed-by-the-shared-layout
  (bookmarks/save! (ds) reader-a brave-and-true)
  (let [rendered (body (GET reader-a))]
    (is (str/includes? rendered "<header"))
    (is (str/includes? rendered "<footer"))
    (is (str/includes? rendered "/css/app.css"))))

(deftest the-page-is-reachable-from-the-navigation
  ;; The nav is in the shared layout, so a link that is only on this page is a
  ;; page nobody finds. Asserted on the landing page, which is where a reader
  ;; arrives before they have been anywhere else.
  (testing "every page's header offers the bookmarks page"
    (let [landing ((app) {:request-method :get :uri "/"})]
      (is (re-find #"(?s)<header.*?href=\"/bookmarks\".*?</header>" (body landing))))))

(deftest the-page-answers-head
  ;; Like every other page route. Without it this path answers 405 to a probe,
  ;; and reitit sends no `Allow` header with the refusal.
  (bookmarks/save! (ds) reader-a brave-and-true)
  (is (= 200 (:status ((app) {:request-method :head :uri bookmarks-uri
                              :headers {"authorization" (str "Bearer " (token-for reader-a))}})))))

;; ---------------------------------------------------------------------------
;; The empty collection
;; ---------------------------------------------------------------------------

(deftest a-reader-who-has-kept-nothing-gets-the-empty-state
  (let [response (GET reader-a)]
    (is (= 200 (:status response)))
    (is (= "empty" (state-of response)))
    (is (empty? (listed response)))))

(deftest the-empty-state-points-at-search
  (testing "the one thing a reader with no Bookmarks can do next is offered"
    (let [response (GET reader-a)
          region (subs (body response) (str/index-of (body response) (region-tag response)))]
      (is (str/includes? region "href=\"/search\"")))))

;; ---------------------------------------------------------------------------
;; The Catalog is not consulted. That is the whole point of the snapshot.
;; ---------------------------------------------------------------------------

(deftest the-page-renders-in-full-with-the-book-search-port-throwing
  ;; `app` wires a port that throws on any call, so reaching 200 with the
  ;; snapshot on the page is proof that nothing here asked it anything.
  (bookmarks/save! (ds) reader-a brave-and-true)
  (let [response (GET reader-a)]
    (is (= 200 (:status response)))
    (is (= "bookmarks" (state-of response)))
    (is (str/includes? (body response) "Clojure for the Brave and True"))))

(deftest the-page-renders-with-no-book-search-configured-at-all
  (testing "a deployment with no Books API key still lists what a Reader kept"
    (bookmarks/save! (ds) reader-a brave-and-true)
    (let [response (GET reader-a (app catalog/not-configured))]
      (is (= 200 (:status response)))
      (is (= [(:id brave-and-true)] (listed response))))))

;; ---------------------------------------------------------------------------
;; Reader isolation, proved with two signed-in Readers
;; ---------------------------------------------------------------------------

(deftest one-reader-never-sees-anothers-bookmarks
  (bookmarks/save! (ds) reader-b brave-and-true)
  (testing "Reader B sees what Reader B kept"
    (is (= [(:id brave-and-true)] (listed (GET reader-b)))))
  (testing "and Reader A sees an empty collection, not Reader B's"
    (let [response (GET reader-a)]
      (is (= "empty" (state-of response)))
      (is (not (str/includes? (body response) "Clojure for the Brave and True"))))))

(deftest the-page-lists-by-the-verified-session-never-by-a-parameter
  ;; The Reader id is taken from the token the gate verified. A request naming
  ;; somebody else must be answered as the Reader who sent it, whatever it asks
  ;; for — this is the one query in the app that could leak a collection.
  (bookmarks/save! (ds) reader-b brave-and-true)
  (let [response ((app) {:request-method :get :uri bookmarks-uri
                         :query-string (str "reader=" (encode reader-b)
                                            "&reader_id=" (encode reader-b))
                         :headers {"authorization" (str "Bearer " (token-for reader-a))}})]
    (is (= "empty" (state-of response)))
    (is (not (str/includes? (body response) "Clojure for the Brave and True")))))

;; ---------------------------------------------------------------------------
;; Removing a Bookmark, in place
;; ---------------------------------------------------------------------------

(deftest removing-a-bookmark-answers-the-list-without-it
  (bookmarks/save! (ds) reader-a brave-and-true)
  (bookmarks/save! (ds) reader-a programming-clojure)
  (let [response (remove-bookmark reader-a (:id brave-and-true))]
    (is (= 200 (:status response)))
    (is (= "bookmarks" (state-of response)))
    (is (= [(:id programming-clojure)] (listed response)))
    (testing "the answer is the region alone, for htmx to swap in place"
      (is (not (str/includes? (body response) "<html")))
      (is (not (str/includes? (body response) "<header"))))
    (testing "and the row is gone for good"
      (is (= [(:id programming-clojure)] (listed (GET reader-a)))))))

(deftest removing-the-last-bookmark-shows-the-empty-state
  ;; The interesting case, and the reason a removal swaps the whole list rather
  ;; than its own row: a row that swapped itself away cannot make its container
  ;; say "no bookmarks yet".
  (bookmarks/save! (ds) reader-a brave-and-true)
  (let [response (remove-bookmark reader-a (:id brave-and-true))]
    (is (= 200 (:status response)))
    (is (= "empty" (state-of response)))
    (is (empty? (listed response)))
    (is (str/includes? (body response) "href=\"/search\""))))

(deftest the-page-removal-control-asks-for-the-list-back
  (testing "the rendered control targets the whole region, and says which answer it wants"
    (bookmarks/save! (ds) reader-a brave-and-true)
    (let [control (re-find #"<form\b[^>]*data-volume[^>]*>" (body (GET reader-a)))]
      (is (str/includes? control "hx-delete=\"/bookmarks\""))
      (is (str/includes? control "hx-target=\"#bookmarks\""))
      (is (str/includes? control "hx-swap=\"outerHTML\""))
      (is (not (str/includes? control "hx-push-url"))
          "removing a Bookmark is not a place a reader navigated to"))
    (testing "and it names the Volume, and the answer it wants, as hidden fields"
      (let [rendered (body (GET reader-a))]
        (is (re-find #"<input[^>]*name=\"answer\"[^>]*value=\"list\"" rendered))
        (is (re-find (re-pattern (str "<input[^>]*name=\"volume\"[^>]*value=\""
                                      (:id brave-and-true) "\""))
                     rendered))))))

(deftest a-search-card-removal-still-answers-its-own-control
  ;; The other caller of the same route, unchanged: the search page's toggle
  ;; swaps itself, and a request that asks for no particular answer gets it.
  (bookmarks/save! (ds) reader-a brave-and-true)
  (let [response ((app) {:request-method :delete
                         :uri bookmarks-uri
                         :query-string (str "volume=" (encode (:id brave-and-true)))
                         :headers {"hx-request" "true"
                                   "authorization" (str "Bearer " (token-for reader-a))}})]
    (is (= 200 (:status response)))
    (is (str/includes? (body response) "data-bookmark=\"not-bookmarked\""))
    (is (nil? (state-of response)) "no list region — the control alone")))

(deftest one-reader-cannot-remove-anothers-bookmark-from-the-page
  (bookmarks/save! (ds) reader-b brave-and-true)
  (let [response (remove-bookmark reader-a (:id brave-and-true))]
    (is (= 200 (:status response)))
    (is (= "empty" (state-of response)) "Reader A's own list, which was always empty")
    (is (= [(:id brave-and-true)] (listed (GET reader-b))) "Reader B still has theirs")))

;; ---------------------------------------------------------------------------
;; Who may see the page at all
;; ---------------------------------------------------------------------------

(deftest a-signed-out-reader-is-sent-to-sign-in
  (bookmarks/save! (ds) reader-a brave-and-true)
  (let [response (GET nil)]
    (is (= 302 (:status response)))
    (is (str/starts-with? (get-in response [:headers "Location"]) sign-in-path))
    (testing "and nothing about anybody's collection is in the refusal"
      (is (not (str/includes? (body response) "Clojure for the Brave and True"))))))

(deftest signing-in-returns-the-reader-to-the-bookmarks-page
  ;; The return path is chosen from `gated-paths`, and only for a path that
  ;; answers GET — so this page is returnable only because its `:get` is there.
  (testing "the refusal remembers where the Reader was going"
    (is (= "%2Fbookmarks"
           (second (re-find #"redirect_url=([^&]*)"
                            (str (get-in (GET nil) [:headers "Location"]))))))))

(deftest a-cookie-alone-still-reads-the-page
  ;; ADR-0007 narrows the cookie out of MUTATIONS only. A document navigation
  ;; carries nothing else, and reading changes nothing.
  (bookmarks/save! (ds) reader-a brave-and-true)
  (let [response ((app) {:request-method :get :uri bookmarks-uri
                         :headers {"cookie" (str "__session=" (token-for reader-a))}})]
    (is (= 200 (:status response)))
    (is (= [(:id brave-and-true)] (listed response)))))

(deftest the-page-is-never-cached-for-the-next-visitor
  ;; One URL, one answer per Reader. A shared cache that stored it would hand
  ;; the next visitor somebody else's collection.
  (bookmarks/save! (ds) reader-a brave-and-true)
  (let [response (GET reader-a)
        vary (get-in response [:headers "Vary"])]
    (is (= "private, no-cache" (get-in response [:headers "Cache-Control"])))
    (is (str/includes? vary "Authorization"))
    (is (str/includes? vary "Cookie"))))

(deftest a-forged-token-sees-nothing
  (bookmarks/save! (ds) reader-a brave-and-true)
  (doseq [forgery [(test-jwt/alg-none-token)
                   (test-jwt/hs256-token)
                   (test-jwt/token-signed-by-a-stranger)
                   (test-jwt/expired-token)]]
    (let [response ((app) {:request-method :get :uri bookmarks-uri
                           :headers {"authorization" (str "Bearer " forgery)}})]
      (is (= 302 (:status response)))
      (is (not (str/includes? (body response) "Clojure for the Brave and True"))))))
