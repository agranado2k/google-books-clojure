(ns books.google-books
  "The real **Book search** adapter (see `books.catalog`): the Google Books
  **volumes** endpoint, reached over plain `java.net.http` (no new dependency).

  Two rules shape this namespace.

  1. **The API key is a secret, and it never travels in a URL.** ADR-0003
     clause 2 binds credentials out of URL strings, and it binds this call too:
     the key goes in the `X-goog-api-key` request header, which the Books API
     honours identically to the `?key=` parameter (verified against the live
     API — a bogus key answers 400 \"API key not valid\" through both, the
     key-validation path, while sending no key at all answers 429, the
     anonymous-quota path). The search URL therefore holds no credential and is
     safe to log, render, or put in an exception message. `redact` remains as a
     second line for a diagnostic built from something other than the URL.
  2. **A search never throws.** Every fault — no key, a refusal, a timeout, a
     body that is not the JSON we asked for — becomes one of the outcomes the
     Book search contract documents, because they are all states the search
     page has to render anyway."
  (:require [books.catalog :as catalog]
            [clojure.string :as str]
            [jsonista.core :as json])
  (:import (java.io IOException)
           (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpResponse$BodyHandler HttpResponse$BodySubscriber
                          HttpResponse$BodySubscribers)
           (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent CompletableFuture ExecutionException Flow$Subscription
                                 TimeUnit TimeoutException)
           (java.util.concurrent.atomic AtomicBoolean AtomicLong)))

(def ^:private endpoint "https://www.googleapis.com/books/v1/volumes")

(def ^:private max-results
  "How many Volumes one search asks for. The API's own ceiling is 40; a page
  of 20 is what the results list shows. Paging is ticket #6."
  20)

(def ^:private fields
  "The partial-response projection: exactly the fields the card renders, so the
  catalog does not ship (and we do not parse) a payload of everything else."
  "items(id,volumeInfo(title,authors,publishedDate,description,imageLinks/thumbnail))")

(def max-body-bytes
  "The ceiling on a response body, in bytes.

  A response is buffered into memory before it is parsed, so its SIZE is a
  resource bound exactly as the timeouts above are — and it was the one this
  adapter did not have: an upstream that answered without end (hostile, or
  merely broken, or a captive-portal proxy) could grow the heap on a request
  thread until the process died.

  2 MiB is a ceiling with room, not a budget. One search asks for 20 Volumes
  through the `fields` projection above — id, title, authors, date, description
  and a thumbnail URL — and the description is the only field that is not a
  line or two; even at several kilobytes each, a full page lands two orders of
  magnitude under this. Nothing the catalog legitimately answers is refused;
  what is refused is a body that stopped being a search result.

  Public so a test can drive a body to exactly this size rather than guessing
  it."
  (* 2 1024 1024))

(def timeouts
  "Every wait is bounded, so an unresponsive catalog fails fast instead of
  pinning a request thread — the same posture `books.db` takes for Postgres.

  Three bounds, because the JDK's two do not cover the whole exchange:

  * `:connect` — `HttpClient.connectTimeout`, the TCP/TLS handshake.
  * `:request` — `HttpRequest.timeout`. This one is narrower than its name
    suggests: it bounds the wait for the response **headers**, and nothing
    after them.
  * `:total` — the deadline this namespace imposes itself, covering the whole
    exchange including body reception. It is the only bound that a body which
    arrives and then STOPS ever reaches. Measured without it, against a server
    that answered 200, its headers and one 8 KiB chunk and then held the
    connection: `http-fetch` blocked for the entire length of the stall —
    45.2 s against a 45 s stall, 120.2 s against a 120 s one — while
    `:request` sat at ten seconds. The byte ceiling below does not help there
    either: a trickle never reaches 2 MiB. With ring's default 50-thread pool,
    fifty such requests consume it permanently.

  20 seconds is a ceiling with room, not a budget: a page of 20 Volumes under
  the `fields` projection is tens of kilobytes, which a working catalog
  delivers in well under a second.

  Public so a test can read the same numbers the adapter runs on."
  {:connect (Duration/ofSeconds 5)
   :request (Duration/ofSeconds 10)
   :total (Duration/ofSeconds 20)})

;; ---------------------------------------------------------------------------
;; The request
;; ---------------------------------------------------------------------------

(defn- encode
  "Percent-encode one query-string value. `URLEncoder` is form encoding, which
  spells a space `+`; here `+` is the separator BETWEEN search operators, so a
  space inside a phrase has to stay `%20`."
  [value]
  (-> (URLEncoder/encode (str value) StandardCharsets/UTF_8)
      (str/replace "+" "%20")))

(defn- term
  "One field-scoped search term, e.g. `intitle:%22brave%20new%20world%22`. The
  value is quoted so a multi-word title stays one phrase rather than becoming
  several loose words."
  [operator value]
  (str operator ":" (encode (str "\"" value "\""))))

(defn q-param
  "The `q` value for a normalized query: the title and author operators the
  reader actually filled in, ANDed with `+`."
  [{:keys [title author]}]
  (str/join "+" (cond-> []
                  title (conj (term "intitle" title))
                  author (conj (term "inauthor" author)))))

(def ^:private api-key-header
  "The header the API key travels in. The Books API accepts the key either here
  or as a `key=` query parameter and treats the two identically; this repo uses
  the header, because ADR-0003 clause 2 keeps credentials out of URL strings —
  URLs are the thing that ends up in logs, proxies, `Referer`, and exception
  messages."
  "X-goog-api-key")

(defn search-url
  "The full volumes URL for `query`. Carries **no credential**: the API key is a
  request header (see `http-fetch`), so this string is safe to log, render, or
  put in an exception message."
  [query]
  (str endpoint
       "?q=" (q-param query)
       "&maxResults=" max-results
       "&fields=" (encode fields)))

;; ---------------------------------------------------------------------------
;; The response
;; ---------------------------------------------------------------------------

(defn- cover
  "The thumbnail to render, or nil. The catalog answers `http://` URLs, which
  would make the page mixed-content, so they are upgraded; anything that is not
  an http(s) URL at all is not a cover image and is dropped."
  [thumbnail]
  (cond
    (nil? thumbnail) nil
    (str/starts-with? thumbnail "https://") thumbnail
    (str/starts-with? thumbnail "http://") (str "https://" (subs thumbnail (count "http://")))
    :else nil))

(defn- volume
  "One catalog item as a Volume (see `books.catalog`). Optional fields the
  catalog omitted stay omitted rather than becoming nil-valued keys."
  [item]
  (let [info (get item "volumeInfo")
        thumbnail (cover (get-in info ["imageLinks" "thumbnail"]))]
    (cond-> {:id (get item "id")}
      (get info "title") (assoc :title (get info "title"))
      (seq (get info "authors")) (assoc :authors (vec (get info "authors")))
      (get info "publishedDate") (assoc :published-date (get info "publishedDate"))
      (get info "description") (assoc :description (get info "description"))
      thumbnail (assoc :thumbnail thumbnail))))

(defn parse-body
  "Parse a 200 response body into `{:outcome :ok :volumes [...]}`. An absent
  `items` is a search that ran and matched nothing, which is a success."
  [body]
  {:outcome :ok
   :volumes (mapv volume (get (json/read-value body) "items"))})

;; ---------------------------------------------------------------------------
;; Faults
;; ---------------------------------------------------------------------------

(defn redact
  "`text` with every occurrence of `secret` replaced. Now that the key is a
  header rather than a URL parameter, this is a second line rather than the
  control: a diagnostic built from the search URL cannot leak a key the URL
  does not hold. It earns its keep on the one path where a message is built
  from something we did not construct — `report!` below, which prints an
  exception message whose text belongs to whatever threw it."
  [text secret]
  (if (seq secret)
    (str/replace text secret "[redacted]")
    text))

(defn- report!
  "Report a fault on stderr: exception class and message only, redacted, and
  never the request. The URL no longer holds the key, but the message text
  belongs to whatever threw, so it is redacted before it is printed."
  [^Exception e api-key]
  (binding [*out* *err*]
    (println (redact (str "book search failed: " (.getName (class e)) ": " (ex-message e))
                     api-key))))

(defn- failure-reason
  "The failure a non-200 status stands for, or nil when the search succeeded.
  429 is the catalog refusing us for quota/rate reasons and is named
  separately, because 'try again shortly' and 'the catalog is down' are
  different things to tell a reader."
  [status]
  (case status
    200 nil
    429 :quota
    :unavailable))

;; ---------------------------------------------------------------------------
;; The adapter
;; ---------------------------------------------------------------------------

(def ^:private shared-client
  "ONE `HttpClient` for the life of the process.

  A client is not a request object: it owns a connection pool and starts a
  selector-manager thread, and on this JDK it has no `close`, so a client built
  per request is a leak with a thread attached. Measured before this was
  hoisted: 36 -> 131 JVM threads (59 of them `HttpClient-*`) after 40
  concurrent searches, still there after 30 seconds of idle, and released only
  by a forced GC.

  A `delay` rather than a bare `def` so that requiring this namespace — which
  the handler does at boot, and which a test does merely to read `q-param` —
  does not start a thread nothing is going to use."
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (:connect timeouts))
             ;; Stated, not inherited. NEVER happens to be the builder's
             ;; default today, but this is a security property — the request
             ;; carries `X-goog-api-key`, and the JDK's redirect filter replays
             ;; custom headers onto the redirected request, so one followed 302
             ;; hands the credential to whatever origin the Location names. A
             ;; property that matters that much does not get to live in
             ;; somebody else's default.
             (.followRedirects HttpClient$Redirect/NEVER)
             (.build))))

(defn ^HttpClient http-client
  "The one client every fetch uses. Public so a test can prove that it IS one."
  []
  @shared-client)

(defn- limiting
  "`downstream`, but refusing more than `capacity` bytes.

  The JDK ships no public equivalent — `HttpResponse.BodySubscribers` offers
  `buffering` and `mapping` but nothing that bounds a body; the limiting
  subscriber the JDK uses for its own `ofFile` lives in `jdk.internal.net.http`
  and is not exported. So it is written here, and it is small: count what
  arrives, and the first chunk that crosses the line cancels the subscription
  and fails the downstream with an `IOException`. `failed` makes that a
  one-shot — after a cancel the producer may still deliver a queued chunk or an
  `onComplete`, and a downstream must see exactly one terminal signal.

  This bounds SIZE and nothing else, and it is worth being exact about that:
  this docstring used to claim that decorating the subscriber also kept the
  time bound intact, because `send` returns only once the subscriber has
  terminated. The first half is true and the conclusion does not follow —
  `HttpRequest.timeout` stops at the response headers, so `send` blocking until
  the subscriber terminates is the PROBLEM rather than the protection. A body
  that trickles is bounded by neither this ceiling nor that timeout, and is
  bounded instead by `:total` in `await-within`."
  [^HttpResponse$BodySubscriber downstream ^long capacity]
  (let [seen (AtomicLong.)
        failed (AtomicBoolean. false)
        subscription (volatile! nil)]
    (reify HttpResponse$BodySubscriber
      (getBody [_] (.getBody downstream))
      (onSubscribe [_ s]
        (vreset! subscription s)
        (.onSubscribe downstream s))
      (onNext [_ buffers]
        (let [arrived (transduce (map (fn [^ByteBuffer b] (.remaining b))) + 0 buffers)]
          (cond
            (<= (.addAndGet seen arrived) capacity)
            (.onNext downstream buffers)

            (.compareAndSet failed false true)
            (do (some-> ^Flow$Subscription @subscription (.cancel))
                (.onError downstream
                          (IOException. (str "response body exceeds " capacity " bytes")))))))
      (onError [_ t]
        (when (.compareAndSet failed false true)
          (.onError downstream t)))
      (onComplete [_]
        (when-not (.get failed)
          (.onComplete downstream))))))

(def ^:private bounded-body
  "The body handler every fetch uses: the response as a UTF-8 string, and never
  more than `max-body-bytes` of it.

  `BodyHandlers/ofString` — what this used to be — reads whatever arrives, so
  the only bound on the body was the upstream's good manners. The overrun
  surfaces as a THROWN fault, which `book-search` already turns into
  `{:outcome :error :reason :unavailable}`; a truncated body that went on to
  parse into a plausible-looking short list of Volumes would be the quiet
  failure worth avoiding."
  (reify HttpResponse$BodyHandler
    (apply [_ _response-info]
      (limiting (HttpResponse$BodySubscribers/ofString StandardCharsets/UTF_8)
                max-body-bytes))))

(defn- await-within
  "The value of `pending`, waited for no longer than `budget`.

  This is the wall-clock bound on the WHOLE exchange, and the reason the fetch
  below is `sendAsync` rather than `send`: `send` blocks the calling thread
  until the body subscriber terminates, and no JDK timeout covers that stretch,
  so a body that arrives part-way and then stops holds the thread for as long
  as the upstream cares to hold the connection.

  Running out of budget is a failed search like any other, so it leaves as an
  `IOException` — the same shape a refused connection has — for `book-search`
  to turn into `{:outcome :error :reason :unavailable}`. The exchange is
  cancelled on the way out so the connection is released rather than left
  reading into a buffer nobody will read."
  [^CompletableFuture pending ^Duration budget]
  (try
    (.get pending (.toMillis budget) TimeUnit/MILLISECONDS)
    (catch TimeoutException _
      (.cancel pending true)
      (throw (IOException. (str "the catalog did not finish a response within "
                                (.toMillis budget) " ms"))))
    (catch InterruptedException e
      (.cancel pending true)
      (.interrupt (Thread/currentThread))
      (throw (IOException. "interrupted while waiting for the catalog" e)))
    ;; The real fault — a refused connection, an unresolvable host, the body
    ;; ceiling above — arrives wrapped. Unwrap it, so the fault a caller sees
    ;; and a log line reports is the one that actually happened.
    (catch ExecutionException e
      (throw (or (ex-cause e) e)))))

(defn http-fetch
  "GET `url` with `api-key` as the credential header, answering
  `{:status … :body …}`. Bounded on both axes: in bytes by `max-body-bytes`,
  and in wall-clock time by `budget` (default `(:total timeouts)`) over the
  whole exchange, headers and body alike. This is the default `:fetch` —
  public so the one piece of this namespace that the canned-body tests cannot
  reach is still reachable by a test of its own; the `budget` arity is there so
  those tests can spend milliseconds proving the deadline rather than seconds."
  ([url api-key] (http-fetch url api-key (:total timeouts)))
  ([url api-key budget]
   ;; Redirects are not followed — see `shared-client`. A 3xx therefore arrives
   ;; here as a status, and `failure-reason` calls it :unavailable.
   (let [client (http-client)
         request (-> (HttpRequest/newBuilder (URI/create url))
                     (.timeout (:request timeouts))
                     (.header "Accept" "application/json")
                     (.header api-key-header api-key)
                     (.GET)
                     (.build))
         response (await-within (.sendAsync client request bounded-body) budget)]
     {:status (.statusCode response) :body (.body response)})))

(defn book-search
  "A Book search (see `books.catalog`) over the Google Books volumes endpoint:
  a function of the query map.

  * `:api-key` — `GOOGLE_BOOKS_API_KEY`. Absent or blank is not a boot failure:
    every search then answers `:not-configured`, which the page renders.
  * `:fetch` — the HTTP call, a function of the URL and the key, injectable so
    the URL and parse logic can be tested against canned bodies without calling
    Google."
  [{:keys [api-key fetch] :or {fetch http-fetch}}]
  (let [api-key (when (seq (some-> api-key str/trim)) (str/trim api-key))]
    (fn [query]
      (if-not api-key
        ;; The port owns this value; rebuilding it here would give the contract
        ;; two owners that could drift apart.
        (catalog/not-configured query)
        (try
          (let [{:keys [status body]} (fetch (search-url query) api-key)]
            (if-let [reason (failure-reason status)]
              {:outcome :error :reason reason}
              (parse-body body)))
          (catch Exception e
            (report! e api-key)
            {:outcome :error :reason :unavailable}))))))
