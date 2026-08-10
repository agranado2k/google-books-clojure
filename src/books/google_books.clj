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
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(def ^:private endpoint "https://www.googleapis.com/books/v1/volumes")

(def ^:private max-results
  "How many Volumes one search asks for. The API's own ceiling is 40; a page
  of 20 is what the results list shows. Paging is ticket #6."
  20)

(def ^:private fields
  "The partial-response projection: exactly the fields the card renders, so the
  catalog does not ship (and we do not parse) a payload of everything else."
  "items(id,volumeInfo(title,authors,publishedDate,description,imageLinks/thumbnail))")

(def ^:private timeouts
  "Every wait is bounded, so an unresponsive catalog fails fast instead of
  pinning a request thread — the same posture `books.db` takes for Postgres."
  {:connect (Duration/ofSeconds 5)
   :request (Duration/ofSeconds 10)})

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

(defn http-fetch
  "GET `url` with `api-key` as the credential header, answering
  `{:status … :body …}`. Bounded by `timeouts`. This is the default `:fetch` —
  public so the one piece of this namespace that the canned-body tests cannot
  reach is still reachable by a test of its own."
  [url api-key]
  ;; Redirects are deliberately NOT followed: the request carries the API key,
  ;; and the JDK's redirect filter replays custom headers onto the new request,
  ;; so a followed redirect would hand the key to whatever origin the redirect
  ;; names. A 3xx therefore reads as :unavailable.
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (:connect timeouts))
                   (.build))
        request (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (:request timeouts))
                    (.header "Accept" "application/json")
                    (.header api-key-header api-key)
                    (.GET)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)}))

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
