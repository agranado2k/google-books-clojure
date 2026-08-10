(ns books.google-books
  "The real `books.catalog/BookSearch` adapter: the Google Books **volumes**
  endpoint, reached over plain `java.net.http` (no new dependency).

  Two rules shape this namespace.

  1. **The API key is a secret**, and the key travels in the URL — so the URL
     is never logged, never rendered, and never allowed into an exception
     message. Anything derived from it goes through `redact` first. This is
     ADR-0003's credential rule applied to the second credential the app holds.
  2. **A search never throws.** Every fault — no key, a refusal, a timeout, a
     body that is not the JSON we asked for — becomes one of the outcomes
     `books.catalog/BookSearch` documents, because they are all states the
     search page has to render anyway."
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

(defn search-url
  "The full volumes URL for `query`. **Carries the API key** — do not log it,
  render it, or put it in an exception message."
  [query api-key]
  (str endpoint
       "?q=" (q-param query)
       "&maxResults=" max-results
       "&fields=" (encode fields)
       "&key=" (encode api-key)))

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
  "`text` with every occurrence of `secret` replaced. The only sanctioned way
  to turn anything derived from a search URL into something loggable."
  [text secret]
  (if (seq secret)
    (str/replace text secret "[redacted]")
    text))

(defn- report!
  "Report a fault on stderr: exception class and message only, redacted, and
  never the URL — which holds the key."
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
  "GET `url`, answering `{:status … :body …}`. Bounded by `timeouts`. This is
  the default `:fetch` — public so the one piece of this namespace that the
  canned-body tests cannot reach is still reachable by a test of its own."
  [url]
  ;; Redirects are deliberately NOT followed (the builder's default): the URL
  ;; carries the API key, and a followed redirect would hand it to whatever
  ;; origin the redirect names. A 3xx therefore reads as :unavailable.
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (:connect timeouts))
                   (.build))
        request (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (:request timeouts))
                    (.header "Accept" "application/json")
                    (.GET)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)}))

(defn book-search
  "A `books.catalog/BookSearch` over the Google Books volumes endpoint.

  * `:api-key` — `GOOGLE_BOOKS_API_KEY`. Absent or blank is not a boot failure:
    every search then answers `:not-configured`, which the page renders.
  * `:fetch` — the HTTP call, injectable so the URL and parse logic can be
    tested against canned bodies without calling Google."
  [{:keys [api-key fetch] :or {fetch http-fetch}}]
  (let [api-key (when (seq (some-> api-key str/trim)) (str/trim api-key))]
    (reify catalog/BookSearch
      (search-volumes [_ query]
        (if-not api-key
          {:outcome :error :reason :not-configured}
          (try
            (let [{:keys [status body]} (fetch (search-url query api-key))]
              (if-let [reason (failure-reason status)]
                {:outcome :error :reason reason}
                (parse-body body)))
            (catch Exception e
              (report! e api-key)
              {:outcome :error :reason :unavailable})))))))
