(ns books.google-books-test
  "The real Google Books adapter, exercised WITHOUT calling Google: the URL it
  builds is asserted directly, and the whole search path runs against canned
  response bodies through the injected `:fetch`. Nothing here needs a key, a
  network, or a quota."
  (:require [books.catalog :as catalog]
            [books.google-books :as google]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.adapter.jetty :as jetty]))

(def ^:private api-key "test-key-not-a-real-one")

(defn- adapter
  "The adapter with its HTTP call replaced. `fetch` receives the URL and
  returns `{:status … :body …}` — or throws, to stand in for a network fault."
  [fetch]
  (google/book-search {:api-key api-key :fetch fetch}))

;; ---------------------------------------------------------------------------
;; The query the catalog is asked
;; ---------------------------------------------------------------------------

(deftest q-uses-intitle-and-inauthor-operators
  (testing "a title-only search constrains the title field"
    (is (= "intitle:%22Clojure%22" (google/q-param {:title "Clojure"}))))
  (testing "an author-only search constrains the author field"
    (is (= "inauthor:%22Hickey%22" (google/q-param {:author "Hickey"}))))
  (testing "both fields are ANDed, title first"
    (is (= "intitle:%22Clojure%22+inauthor:%22Hickey%22"
           (google/q-param {:title "Clojure" :author "Hickey"})))))

(deftest q-quotes-and-encodes-the-terms
  (testing "a multi-word term stays one phrase, and reserved characters are encoded"
    ;; The quotes are the phrase operator; the spaces inside them must not turn
    ;; into the `+` that separates the two operators.
    (is (= "intitle:%22brave%20new%20world%22" (google/q-param {:title "brave new world"})))
    (is (= "intitle:%22C%2B%2B%22" (google/q-param {:title "C++"})))
    (is (= "inauthor:%22Gabriel%20Garc%C3%ADa%20M%C3%A1rquez%22"
           (google/q-param {:author "Gabriel García Márquez"}))))
  (testing "a term cannot smuggle in another query parameter"
    (is (not (str/includes? (google/q-param {:title "x&key=stolen"}) "&")))))

(deftest search-url-carries-the-key-the-cap-and-only-the-rendered-fields
  (let [url (google/search-url {:title "Clojure"} api-key)]
    (testing "the volumes endpoint, over TLS"
      (is (str/starts-with? url "https://www.googleapis.com/books/v1/volumes?")))
    (testing "the API key travels as the key parameter"
      (is (str/includes? url (str "key=" api-key))))
    (testing "the result count is capped well under the API's own limit of 40"
      (is (str/includes? url "maxResults=20")))
    (testing "fields asks for exactly what the page renders, and no more"
      (let [fields (second (re-find #"fields=([^&]*)" url))]
        (doseq [rendered ["id" "title" "authors" "publishedDate" "description" "thumbnail"]]
          (is (str/includes? fields rendered)
              (str "fields must request " rendered)))))))

;; ---------------------------------------------------------------------------
;; The response the catalog gives back
;; ---------------------------------------------------------------------------

(def ^:private two-volumes
  "A canned body in the shape the volumes endpoint answers, trimmed to the
  `fields` the adapter asks for."
  (str "{\"totalItems\":2,\"items\":["
       "{\"id\":\"3IGvBQAAQBAJ\",\"volumeInfo\":{"
       "\"title\":\"Clojure for the Brave and True\","
       "\"authors\":[\"Daniel Higginbotham\"],"
       "\"publishedDate\":\"2015-10-15\","
       "\"description\":\"For weeks, months—nay!—from the very moment you were born.\","
       "\"imageLinks\":{\"thumbnail\":\"http://books.google.com/books/content?id=3IGvBQAAQBAJ&zoom=1\"}}},"
       "{\"id\":\"CVBhtQAACAAJ\",\"volumeInfo\":{"
       "\"title\":\"Programming Clojure\","
       "\"authors\":[\"Alex Miller\",\"Stuart Halloway\",\"Aaron Bedra\"],"
       "\"publishedDate\":\"2018\"}}]}"))

(deftest a-successful-search-maps-every-rendered-field
  (let [result ((adapter (constantly {:status 200 :body two-volumes}))
                                       {:title "Clojure"})]
    (is (= :ok (:outcome result)))
    (is (= 2 (count (:volumes result))))
    (testing "each field the card shows comes across under a domain name"
      (is (= {:id "3IGvBQAAQBAJ"
              :title "Clojure for the Brave and True"
              :authors ["Daniel Higginbotham"]
              :published-date "2015-10-15"
              :description "For weeks, months—nay!—from the very moment you were born."
              :thumbnail "https://books.google.com/books/content?id=3IGvBQAAQBAJ&zoom=1"}
             (first (:volumes result)))))
    (testing "absent optional fields are absent, not nil-valued placeholders"
      (is (= {:id "CVBhtQAACAAJ"
              :title "Programming Clojure"
              :authors ["Alex Miller" "Stuart Halloway" "Aaron Bedra"]
              :published-date "2018"}
             (second (:volumes result)))))))

(deftest a-thumbnail-is-upgraded-to-tls-or-dropped
  (testing "the catalog answers http:// thumbnails; serving them would make the page mixed-content"
    (is (= ["https://example.test/cover.jpg"]
           (map :thumbnail (:volumes (google/parse-body
                                      "{\"items\":[{\"id\":\"a\",\"volumeInfo\":{\"imageLinks\":{\"thumbnail\":\"http://example.test/cover.jpg\"}}}]}"))))))
  (testing "anything that is not an http(s) URL is not a cover image"
    (is (= [nil]
           (map :thumbnail (:volumes (google/parse-body
                                      "{\"items\":[{\"id\":\"a\",\"volumeInfo\":{\"imageLinks\":{\"thumbnail\":\"javascript:alert(1)\"}}}]}")))))))

(deftest no-matches-is-a-successful-search
  (testing "an empty catalog answer is :ok with no volumes — not an error"
    (doseq [body ["{\"kind\":\"books#volumes\",\"totalItems\":0}" "{\"items\":[]}"]]
      (is (= {:outcome :ok :volumes []}
             ((adapter (constantly {:status 200 :body body}))
                                     {:title "zzzzz"}))))))

;; ---------------------------------------------------------------------------
;; The ways a search fails
;; ---------------------------------------------------------------------------

(deftest quota-refusal-is-reported-as-quota
  (testing "HTTP 429 is the catalog rate-limiting us, and the page says so"
    (is (= {:outcome :error :reason :quota}
           ((adapter (constantly {:status 429 :body "{\"error\":{\"code\":429}}"}))
                                   {:title "Clojure"})))))

(deftest any-other-non-200-is-unavailable
  (doseq [status [400 401 403 500 503]]
    (is (= {:outcome :error :reason :unavailable}
           ((adapter (constantly {:status status :body "{}"}))
                                   {:title "Clojure"}))
        (str "HTTP " status " must degrade, not throw"))))

(deftest an-unreachable-catalog-is-unavailable
  (testing "a network fault is an outcome the page renders, never an exception"
    (is (= {:outcome :error :reason :unavailable}
           ((adapter (fn [_] (throw (java.io.IOException. "connect timed out"))))
                                   {:title "Clojure"})))))

(deftest an-unparseable-body-is-unavailable
  (testing "a 200 that is not the JSON we asked for is still a failed search"
    (is (= {:outcome :error :reason :unavailable}
           ((adapter (constantly {:status 200 :body "<html>proxy error</html>"}))
                                   {:title "Clojure"})))))

(deftest without-a-key-nothing-is-fetched
  (testing "an absent GOOGLE_BOOKS_API_KEY degrades the page; it must not crash boot"
    (let [called (atom false)
          adapter (google/book-search {:api-key nil :fetch (fn [_] (reset! called true) nil)})]
      (is (= {:outcome :error :reason :not-configured}
             (adapter {:title "Clojure"})))
      (is (false? @called) "no key means no call")))
  (testing "a blank key counts as absent"
    (is (= {:outcome :error :reason :not-configured}
           ((google/book-search {:api-key "   "}) {:title "Clojure"})))))

;; ---------------------------------------------------------------------------
;; The default fetch — the one piece the canned bodies cannot exercise.
;; Against a local server, never against Google.
;; ---------------------------------------------------------------------------

(deftest the-default-fetch-really-speaks-http
  (let [jetty (jetty/run-jetty
               (fn [request]
                 {:status (if (= "/refused" (:uri request)) 429 200)
                  :headers {"Content-Type" "application/json"}
                  :body "{\"items\":[{\"id\":\"a\",\"volumeInfo\":{\"title\":\"Over the wire\"}}]}"})
               {:host "127.0.0.1" :port 0 :join? false})
        base (str "http://127.0.0.1:" (.getLocalPort (aget (.getConnectors jetty) 0)))]
    (try
      (testing "status and body come back as the adapter expects them"
        (let [{:keys [status body]} (google/http-fetch (str base "/books/v1/volumes?q=x"))]
          (is (= 200 status))
          (is (= [{:id "a" :title "Over the wire"}] (:volumes (google/parse-body body))))))
      (testing "a refusal is reported by status, not by throwing"
        (is (= 429 (:status (google/http-fetch (str base "/refused"))))))
      (finally (.stop jetty)))))

;; ---------------------------------------------------------------------------
;; The key is a secret, including on the way out
;; ---------------------------------------------------------------------------

(deftest failures-never-carry-the-key
  (testing "the URL holds the key, so a diagnostic built from it must be redacted"
    (let [message (str "GET " (google/search-url {:title "Clojure"} api-key) " failed")]
      (is (str/includes? message api-key) "precondition: the URL embeds the key")
      (is (not (str/includes? (google/redact message api-key) api-key)))
      (is (str/includes? (google/redact message api-key) "[redacted]"))
      (testing "redaction with no secret to redact is a no-op, never an NPE"
        (is (= message (google/redact message nil)))
        (is (= message (google/redact message "")))))
    (testing "a thrown fault is reported without the URL that caused it"
      (let [err (java.io.StringWriter.)
            boom (fn [_] (throw (ex-info (str "connect failed: " (google/search-url {:title "x"} api-key)) {})))]
        (binding [*err* err]
          ((adapter boom) {:title "x"}))
        (is (not (str/includes? (str err) api-key))
            "the key must never reach a log line")
        (is (pos? (count (str err))) "…but the fault is still reported")))))
