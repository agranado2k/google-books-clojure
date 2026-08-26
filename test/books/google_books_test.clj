(ns books.google-books-test
  "The real Google Books adapter, exercised WITHOUT calling Google: the URL it
  builds is asserted directly, and the whole search path runs against canned
  response bodies through the injected `:fetch`. Nothing here needs a key, a
  network, or a quota."
  (:require [books.catalog :as catalog]
            [books.google-books :as google]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.adapter.jetty :as jetty])
  (:import (java.net InetAddress ServerSocket Socket URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

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

(defn- param
  "The decoded value of one query parameter of the search URL."
  [name query]
  (-> (re-find (re-pattern (str name "=([^&]*)")) (google/search-url query))
      second
      (URLDecoder/decode StandardCharsets/UTF_8)))

(deftest the-search-url-is-the-volumes-endpoint-over-tls
  (is (str/starts-with? (google/search-url {:title "Clojure"})
                        "https://www.googleapis.com/books/v1/volumes?")))

(deftest the-search-url-carries-no-credential
  (testing "ADR-0003 clause 2: the key travels in a request header, not a URL"
    ;; Which is what makes this string safe to log, render, and put in an
    ;; exception message.
    (let [url (google/search-url {:title "Clojure"})]
      (is (not (str/includes? url api-key)))
      (is (not (str/includes? url "key="))))))

(deftest the-search-url-caps-the-result-count
  (testing "one page of Volumes, well under the API's own limit of 40"
    (is (= "20" (param "maxResults" {:title "Clojure"})))))

(deftest the-search-url-asks-for-exactly-the-rendered-fields-and-no-more
  ;; "and no more" was always the claim; substring-checking the six names could
  ;; not support it — a projection that ALSO asked for every other volumeInfo
  ;; field would have satisfied all six checks and shipped a payload nothing
  ;; renders. The whole decoded value is the assertion, so adding a field now
  ;; fails here and has to be justified by something on the card.
  (is (= "items(id,volumeInfo(title,authors,publishedDate,description,imageLinks/thumbnail))"
         (param "fields" {:title "Clojure"}))))

(deftest the-adapter-fetches-exactly-the-url-it-builds
  ;; Every other double here discards its argument, so nothing pinned the URL
  ;; the adapter ACTUALLY requests: dropping maxResults, or handing the fetch a
  ;; nil key, would have kept the whole suite green. This is the one test that
  ;; closes that gap, and it is what makes the `search-url` assertions above
  ;; assertions about the real request rather than about a private helper.
  (let [requested (atom nil)
        query {:title "Clojure" :author "Hickey"}
        result ((adapter (fn [url key]
                           (reset! requested [url key])
                           {:status 200 :body "{\"items\":[]}"}))
                query)]
    (is (= {:outcome :ok :volumes []} result) "precondition: the fetch was reached")
    (testing "the URL fetched is the URL the builder produces, character for character"
      (is (= (google/search-url query) (first @requested))))
    (testing "and the credential is handed to the fetch separately, to become a header"
      (is (= api-key (second @requested))))
    (testing "…and it is still the whole request, not a URL that lost a parameter"
      (let [url (first @requested)]
        (is (str/includes? url "maxResults=20"))
        (is (str/includes? url "intitle:%22Clojure%22+inauthor:%22Hickey%22"))
        (is (str/includes? url "fields="))))))

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
  (doseq [status [301 302 303 307 308 400 401 403 500 503]]
    (is (= {:outcome :error :reason :unavailable}
           ((adapter (constantly {:status status :body "{}"}))
                                   {:title "Clojure"}))
        (str "HTTP " status " must degrade, not throw"))))

(deftest an-unreachable-catalog-is-unavailable
  (testing "a network fault is an outcome the page renders, never an exception"
    (is (= {:outcome :error :reason :unavailable}
           ((adapter (fn [_ _] (throw (java.io.IOException. "connect timed out"))))
                                   {:title "Clojure"})))))

(deftest an-unparseable-body-is-unavailable
  (testing "a 200 that is not the JSON we asked for is still a failed search"
    (is (= {:outcome :error :reason :unavailable}
           ((adapter (constantly {:status 200 :body "<html>proxy error</html>"}))
                                   {:title "Clojure"})))))

(deftest without-a-key-nothing-is-fetched
  (testing "an absent GOOGLE_BOOKS_API_KEY degrades the page; it must not crash boot"
    (let [called (atom false)
          adapter (google/book-search {:api-key nil :fetch (fn [_ _] (reset! called true) nil)})]
      (is (= {:outcome :error :reason :not-configured}
             (adapter {:title "Clojure"})))
      (is (false? @called) "no key means no call"))))

(deftest a-blank-key-counts-as-absent
  (testing "a variable set to whitespace is an unconfigured deploy, not a credential"
    (is (= {:outcome :error :reason :not-configured}
           ((google/book-search {:api-key "   "}) {:title "Clojure"})))))

;; ---------------------------------------------------------------------------
;; The default fetch — the one piece the canned bodies cannot exercise.
;; Against a local server, never against Google.
;; ---------------------------------------------------------------------------

(deftest the-default-fetch-really-speaks-http
  (let [seen (atom nil)
        jetty (jetty/run-jetty
               (fn [request]
                 (reset! seen request)
                 {:status (if (= "/refused" (:uri request)) 429 200)
                  :headers {"Content-Type" "application/json"}
                  :body "{\"items\":[{\"id\":\"a\",\"volumeInfo\":{\"title\":\"Over the wire\"}}]}"})
               {:host "127.0.0.1" :port 0 :join? false})
        base (str "http://127.0.0.1:" (.getLocalPort (aget (.getConnectors jetty) 0)))]
    (try
      (testing "status and body come back as the adapter expects them"
        (let [{:keys [status body]} (google/http-fetch (str base "/books/v1/volumes?q=x") api-key)]
          (is (= 200 status))
          (is (= [{:id "a" :title "Over the wire"}] (:volumes (google/parse-body body))))))
      (testing "the credential is sent as a request header, never in the URL"
        ;; ADR-0003 clause 2. Verified against the live Books API before this
        ;; moved: `X-goog-api-key: <key>` is honoured identically to `?key=` —
        ;; a bogus key answers 400 "API key not valid" through both (the
        ;; key-validation path), while no key at all answers 429 (the
        ;; anonymous-quota path), so the header genuinely authenticates.
        (is (= api-key (get-in @seen [:headers "x-goog-api-key"])))
        (is (not (str/includes? (str (:query-string @seen)) api-key)))
        (is (not (str/includes? (str (:query-string @seen)) "key="))))
      (testing "a refusal is reported by status, not by throwing"
        (is (= 429 (:status (google/http-fetch (str base "/refused") api-key)))))
      (finally (.stop jetty)))))

(defn- filler
  "`n` bytes of harmless ASCII, as a string — a body of a given SIZE, when the
  size is the only thing under test."
  [n]
  (String. (byte-array n (byte (int \x))) StandardCharsets/UTF_8))

(defn- json-of-size
  "A well-formed volumes body of exactly `n` bytes, padded out in the one field
  a real oversized response would grow in: the description."
  [n]
  (let [prefix "{\"items\":[{\"id\":\"a\",\"volumeInfo\":{\"description\":\""
        suffix "\"}}]}"]
    (str prefix (filler (- n (count prefix) (count suffix))) suffix)))

(defn- serving
  "Run a local Jetty answering `body-for` (a function of the Ring request), and
  call `f` with its base URL. Never Google."
  [body-for f]
  (let [jetty (jetty/run-jetty
               (fn [request]
                 {:status 200
                  :headers {"Content-Type" "application/json"}
                  :body (body-for request)})
               {:host "127.0.0.1" :port 0 :join? false})]
    (try
      (f (str "http://127.0.0.1:" (.getLocalPort (aget (.getConnectors jetty) 0)) "/v"))
      (finally (.stop jetty)))))

(deftest the-default-fetch-reads-a-body-up-to-the-ceiling
  ;; The ceiling is a real limit, not a small one: a full page of Volumes with
  ;; the fields projection is orders of magnitude under it, so nothing the
  ;; catalog legitimately answers is truncated by it.
  (serving (fn [_] (json-of-size google/max-body-bytes))
           (fn [url]
             (let [{:keys [status body]} (google/http-fetch url api-key)]
               (is (= 200 status))
               (is (= google/max-body-bytes (count body))
                   "a body AT the ceiling arrives whole, not truncated")
               (is (= 1 (count (:volumes (google/parse-body body)))))))))

(deftest the-default-fetch-refuses-a-body-past-the-ceiling
  ;; The body is buffered into memory, so its SIZE is a resource bound exactly
  ;; as the timeouts are — and it was the one that was missing: a hostile or
  ;; misbehaving upstream could answer a gigabyte and drive heap growth on a
  ;; request thread. `BodySubscribers.limiting` bounds it.
  (serving (fn [_] (filler (inc google/max-body-bytes)))
           (fn [url]
             (testing "the overrun is refused, not buffered"
               (is (thrown? java.io.IOException (google/http-fetch url api-key))))
             (testing "and it reaches the reader as an outcome, never an escaped exception"
               ;; The fetch is the real one; only the URL is local. A truncated
               ;; body that PARSED would be the quiet failure this guards
               ;; against, so :unavailable is the only acceptable answer.
               (let [errors (java.io.StringWriter.)
                     result (binding [*err* errors]
                              ((google/book-search
                                {:api-key api-key
                                 :fetch (fn [_built-url key] (google/http-fetch url key))})
                               {:title "Clojure"}))]
                 (is (= {:outcome :error :reason :unavailable} result))
                 (is (not (str/includes? (str errors) api-key))))))))

;; ---------------------------------------------------------------------------
;; The OTHER axis of the same bound: wall-clock time over the whole exchange.
;; ---------------------------------------------------------------------------

(defn- stalling
  "Run a local server that answers `200`, its headers, and one 8 KiB chunk of a
  body it promised but never finishes — then holds the connection open, sending
  nothing more, for `stall-ms` — and call `f` with its URL. Never Google.

  A raw socket rather than the Jetty `serving` uses, because the fault under
  test is precisely the thing no server API will do for you on purpose: stop
  mid-body and keep the connection. Jetty would end the exchange on its own
  idle timeout, which is the right answer for the wrong reason and would hide
  the bug."
  [stall-ms f]
  (let [socket (ServerSocket. 0 4 (InetAddress/getByName "127.0.0.1"))
        workers (atom [])
        serve! (fn [^Socket conn]
                 (try
                   (.read (.getInputStream conn) (byte-array 8192)) ; the request
                   (let [out (.getOutputStream conn)]
                     (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                 "Content-Type: application/json\r\n"
                                                 "Content-Length: 10000000\r\n\r\n")
                                            StandardCharsets/UTF_8))
                     (.write out (byte-array 8192 (byte (int \x))))
                     (.flush out)
                     (Thread/sleep (long stall-ms)))
                   (catch Exception _ nil)
                   (finally (try (.close conn) (catch Exception _ nil)))))
        spawn! (fn [conn]
                 (let [t (doto (Thread. #(serve! conn)) (.setDaemon true) (.start))]
                   (swap! workers conj t)))
        accepting (doto (Thread. #(try (while true (spawn! (.accept socket)))
                                       (catch Exception _ nil)))
                    (.setDaemon true)
                    (.start))]
    (try
      (f (str "http://127.0.0.1:" (.getLocalPort socket) "/v"))
      (finally
        (.close socket)
        (.interrupt accepting)
        (run! #(.interrupt ^Thread %) @workers)))))

(defn- elapsed-ms [started-nanos]
  (quot (- (System/nanoTime) started-nanos) 1000000))

(def ^:private test-budget
  "A deliberately tiny total budget. Production runs on 20 seconds
  (`google/timeouts`) and the three tests below assert the SHAPE of the
  deadline rather than its length, so they buy the same evidence for half a
  second each."
  (Duration/ofMillis 500))

(def ^:private stall-ms
  "How long the stalling server holds its connection: comfortably past the test
  budget, and past the ceiling each test allows, so 'it finished in time' can
  only mean OUR deadline fired."
  20000)

(deftest the-default-fetch-bounds-the-whole-exchange-in-wall-clock-time
  ;; Measured before this bound existed, against a server that answered 200,
  ;; its headers and one 8 KiB chunk and then simply stopped sending:
  ;; `http-fetch` blocked for the ENTIRE stall — 45.2 s against a 45 s stall,
  ;; 120.2 s against a 120 s one — while `:request` sat at ten seconds.
  ;; `HttpRequest.timeout` bounds the wait for the response HEADERS and nothing
  ;; after them, and the byte ceiling cannot help either: a trickle never
  ;; reaches 2 MiB. With ring's default 50-thread pool, fifty such requests
  ;; consume it permanently and only a restart gets it back. With the deadline,
  ;; the same 120 s stall is refused in 20.1 s.
  (stalling
   stall-ms
   (fn [url]
     (let [started (System/nanoTime)]
       (is (thrown? java.io.IOException
                    (google/http-fetch url api-key test-budget)))
       (let [took (elapsed-ms started)]
         (is (< took 10000)
             (str "a stalled body must fail on OUR deadline, not on the upstream's "
                  "goodwill; the fetch blocked for " took " ms")))))))

(deftest a-catalog-that-stalls-mid-body-reaches-the-reader-as-unavailable
  ;; The deadline is only worth having if it degrades the way every other fault
  ;; does. The fetch here is the real one; only the URL is local.
  (stalling
   stall-ms
   (fn [url]
     (let [errors (java.io.StringWriter.)
           result (binding [*err* errors]
                    ((google/book-search
                      {:api-key api-key
                       :fetch (fn [_built-url key]
                                (google/http-fetch url key test-budget))})
                     {:title "Clojure"}))]
       (is (= {:outcome :error :reason :unavailable} result))
       (is (not (str/includes? (str errors) api-key)))))))

(deftest a-timed-out-exchange-leaves-the-shared-client-usable
  ;; The client is one instance for the life of the process, so a deadline that
  ;; poisoned it would turn one stalled upstream into every later search
  ;; failing. Cancelling the exchange rather than abandoning it is what keeps
  ;; this true.
  (stalling
   stall-ms
   (fn [stalled-url]
     (is (thrown? java.io.IOException
                  (google/http-fetch stalled-url api-key test-budget))
         "precondition: the deadline fired")
     (serving (fn [_] "{\"items\":[{\"id\":\"a\",\"volumeInfo\":{\"title\":\"Still working\"}}]}")
              (fn [good-url]
                (let [{:keys [status body]} (google/http-fetch good-url api-key)]
                  (is (= 200 status))
                  (is (= [{:id "a" :title "Still working"}]
                         (:volumes (google/parse-body body))))))))))

(defn- selector-threads
  "How many java.net.http selector-manager threads are alive. The JDK starts
  exactly one per HttpClient and keeps it alive for the life of that client, so
  this number IS the number of live clients — which is what makes the leak
  below measurable rather than merely arguable."
  []
  (count (filter #(str/includes? (.getName ^Thread %) "HttpClient-")
                 (keys (Thread/getAllStackTraces)))))

(deftest the-default-fetch-reuses-one-http-client
  ;; Regression: `http-fetch` built a fresh HttpClient per request and never
  ;; closed it. Measured before the fix: 36 -> 131 JVM threads (59 of them
  ;; HttpClient-*) after 40 concurrent searches, surviving 30s of idle and
  ;; freed only by a forced GC.
  (let [jetty (jetty/run-jetty
               (constantly {:status 200
                            :headers {"Content-Type" "application/json"}
                            :body "{\"items\":[]}"})
               {:host "127.0.0.1" :port 0 :join? false})
        url (str "http://127.0.0.1:" (.getLocalPort (aget (.getConnectors jetty) 0)) "/v")]
    (try
      (testing "the client is one instance, handed out rather than rebuilt"
        (is (identical? (google/http-client) (google/http-client))))
      (testing "fetching many times does not accumulate clients"
        (let [before (selector-threads)]
          (dotimes [_ 15] (google/http-fetch url api-key))
          (let [grew (- (selector-threads) before)]
            (is (<= grew 1)
                (str "15 fetches must not leave 15 live HttpClients behind; "
                     "selector threads grew by " grew)))))
      (finally (.stop jetty)))))

(deftest the-default-fetch-never-follows-a-redirect
  ;; The property that protects the key: the request carries `X-goog-api-key`,
  ;; and the JDK's redirect filter replays custom headers onto the redirected
  ;; request — so a followed 302 would hand the credential to whatever origin
  ;; the Location names. It used to rest on an UNDECLARED builder default and
  ;; on nothing at all in the suite.
  (let [followed (atom [])
        elsewhere (jetty/run-jetty
                   (fn [request]
                     (swap! followed conj (get-in request [:headers "x-goog-api-key"]))
                     {:status 200 :headers {"Content-Type" "application/json"}
                      :body "{\"items\":[]}"})
                   {:host "127.0.0.1" :port 0 :join? false})
        elsewhere-url (str "http://127.0.0.1:"
                           (.getLocalPort (aget (.getConnectors elsewhere) 0)) "/stolen")
        redirector (jetty/run-jetty
                    (constantly {:status 302 :headers {"Location" elsewhere-url} :body ""})
                    {:host "127.0.0.1" :port 0 :join? false})
        redirect-url (str "http://127.0.0.1:"
                          (.getLocalPort (aget (.getConnectors redirector) 0)) "/v")]
    (try
      (testing "the policy is declared, not inherited from a default that could change"
        (is (= java.net.http.HttpClient$Redirect/NEVER (.followRedirects (google/http-client)))))
      (let [{:keys [status]} (google/http-fetch redirect-url api-key)]
        (testing "the 3xx surfaces as the status, for `failure-reason` to call a fault"
          (is (= 302 status)))
        (testing "and the redirect target is never asked for anything"
          ;; The vector holds what the target saw in `X-goog-api-key`, so a
          ;; regression reports not just that the redirect was followed but
          ;; that the credential travelled with it.
          (is (= [] @followed) "a request reached the redirect's origin")))
      (finally (.stop redirector) (.stop elsewhere)))))

;; ---------------------------------------------------------------------------
;; The key is a secret, including on the way out
;; ---------------------------------------------------------------------------

(deftest a-diagnostic-built-from-the-search-url-cannot-carry-the-key
  (testing "the primary control: the search URL simply has no credential in it"
    (let [message (str "GET " (google/search-url {:title "Clojure"}) " failed")]
      (is (not (str/includes? message api-key))))))

(deftest redact-removes-the-key-from-a-message-built-from-something-else
  (testing "the second line, for text this repo did not construct"
    (let [message (str "connect failed for " api-key)]
      (is (not (str/includes? (google/redact message api-key) api-key)))
      (is (str/includes? (google/redact message api-key) "[redacted]")))))

(deftest redact-with-no-secret-to-redact-is-a-no-op
  (testing "an unconfigured deploy still reports its faults, rather than NPEing"
    (let [message "connect failed"]
      (is (= message (google/redact message nil)))
      (is (= message (google/redact message ""))))))

(deftest a-thrown-fault-is-reported-with-the-key-stripped-out-of-it
  (let [err (java.io.StringWriter.)
        boom (fn [_ key] (throw (ex-info (str "connect failed, credential was " key) {})))]
    (binding [*err* err]
      ((adapter boom) {:title "x"}))
    (is (not (str/includes? (str err) api-key))
        "the key must never reach a log line")
    (is (pos? (count (str err))) "…but the fault is still reported")))
