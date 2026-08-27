(ns books.search-test
  "The search slice at the handler seam: a Ring request in, a response map out,
  with the Book search port stubbed (`books.stub-book-search`). Nothing here touches
  Google, and nothing here needs a database.

  `/search` answers the same content two ways on purpose: the whole page for a
  plain request, and the results fragment alone when htmx asks (`HX-Request`).
  Both paths are covered, because the plain one is what a reader without
  JavaScript gets."
  (:require [books.assets :as assets]
            [books.handler :as handler]
            [books.stub-book-search :as stub]
            [books.stub-session :as session]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- app
  "A database-less app with `book-search` as its Book search port, and a Reader
  already signed in.

  /search is gated (ticket #7), and these tests are about what the page renders
  rather than about who may see it — so the Session check here is a double.
  `books.auth-test` is where the gate itself is exercised, against the real
  verifier."
  [book-search]
  (handler/make-app nil {:db-optional? true
                         :book-search book-search
                         :session-check (session/signed-in)}))

(defn- search
  "GET /search with the given query string. `:htmx? true` sends the header htmx
  sends, which is what asks for the fragment instead of the page."
  ([book-search query-string] (search book-search query-string {}))
  ([book-search query-string {:keys [htmx? headers method]}]
   ((app book-search)
    (cond-> {:request-method (or method :get) :uri "/search" :query-string query-string}
      htmx? (assoc :headers {"hx-request" "true"})
      headers (update :headers merge headers)))))

(defn- body [response] (str (:body response)))

(defn- state-of
  "The `data-state` the results region rendered with — the one marker every
  state test asserts on, so 'renders distinctly' is a fact and not a guess."
  [response]
  (second (re-find #"data-state=\"([a-z-]+)\"" (body response))))

;; ---------------------------------------------------------------------------
;; The page
;; ---------------------------------------------------------------------------

(deftest search-page-is-html-with-a-title-and-author-form
  (let [response (search (stub/found []) nil)]
    (is (= 200 (:status response)))
    (is (str/starts-with? (get-in response [:headers "Content-Type"]) "text/html"))
    (testing "one form, with the two fields the ticket names"
      (is (str/includes? (body response) "name=\"title\""))
      (is (str/includes? (body response) "name=\"author\"")))
    (testing "submitted by htmx, swapping the results region in place"
      (is (str/includes? (body response) "hx-get=\"/search\""))
      (is (str/includes? (body response) "hx-target=\"#results\"")))
    (testing "the vendored htmx is what makes that work, and it is loaded from our origin"
      (is (str/includes? (body response) (str "src=\"" assets/htmx-path "\"")))
      (is (not (str/includes? (body response) "//unpkg.com"))
          "no CDN — ADR-0004, amended 2026-08-10"))
    (testing "the page is framed by the shared layout"
      (is (str/includes? (body response) "<header"))
      (is (str/includes? (body response) "/css/app.css")))))

(deftest search-page-shows-a-loading-indicator-while-a-search-is-in-flight
  (let [rendered (body (search (stub/found []) nil))]
    (testing "an indicator element htmx toggles, hidden until a request is running"
      (is (str/includes? rendered "id=\"search-indicator\""))
      (is (str/includes? rendered "htmx-indicator"))
      (is (str/includes? rendered "hx-indicator=\"#search-indicator\""))
      (is (str/includes? rendered "Searching")))))

(deftest an-empty-form-is-not-an-empty-result
  (testing "arriving at /search with nothing typed prompts, and asks the catalog nothing"
    (let [seen (atom [])
          response (search (stub/found [stub/brave-and-true] seen) nil)]
      (is (= 200 (:status response)))
      (is (= "prompt" (state-of response)))
      (is (= [] @seen) "a blank query must never reach the catalog")))
  (testing "…and neither is a form submitted with only whitespace"
    (let [seen (atom [])]
      (is (= "prompt" (state-of (search (stub/found [] seen) "title=%20%20&author="))))
      (is (= [] @seen)))))

(deftest a-repeated-parameter-still-searches-instead-of-crashing
  ;; Regression: `wrap-params` answers a VECTOR when a name is repeated
  ;; (`?title=a&title=b`), and a query normalizer that assumed a string threw —
  ;; which, with no error middleware, served a stack trace to anyone who could
  ;; type a URL.
  (testing "a repeated title searches for the first value"
    (let [seen (atom [])
          response (search (stub/found [stub/sparse] seen) "title=a&title=b" {:htmx? true})]
      (is (= 200 (:status response)))
      (is (= "results" (state-of response)))
      (is (= [{:title "a"}] @seen))))
  (testing "…and so does a repeated author, alongside a repeated title"
    (let [seen (atom [])
          response (search (stub/found [] seen) "title=a&title=b&author=c&author=d" {:htmx? true})]
      (is (= 200 (:status response)))
      (is (= [{:title "a" :author "c"}] @seen))))
  (testing "a repeated blank parameter is still a blank query, not a crash"
    (let [response (search (stub/found []) "title=&title=%20" {:htmx? true})]
      (is (= 200 (:status response)))
      (is (= "prompt" (state-of response))))))

;; ---------------------------------------------------------------------------
;; Searching: by title, by author, by both
;; ---------------------------------------------------------------------------

(deftest a-search-by-title-renders-the-volumes-it-found
  (let [seen (atom [])
        response (search (stub/found [stub/brave-and-true stub/sparse] seen)
                         "title=clojure" {:htmx? true})]
    (is (= 200 (:status response)))
    (is (= "results" (state-of response)))
    (testing "the title reached the port, trimmed of everything it did not say"
      (is (= [{:title "clojure"}] @seen)))
    (testing "every field the ticket names is on the card"
      (let [rendered (body response)]
        (is (str/includes? rendered "Clojure for the Brave and True"))
        (is (str/includes? rendered "Daniel Higginbotham"))
        (is (str/includes? rendered "2015-10-15"))
        (is (str/includes? rendered "Learn to program with Clojure"))
        (is (str/includes? rendered "https://books.example.test/cover.jpg"))))
    (testing "a sparsely described Volume still renders, without empty furniture"
      (is (str/includes? (body response) "Programming Clojure")))))

;; The two fallbacks on the card — "Untitled" and "Author unknown" — read like
;; defensive dead code and are not: the adapter omits `:title` and `:authors`
;; whenever the catalog omits them (see `books.stub-book-search/nameless`), so
;; both are states a real search reaches. One deftest each: they are two
;; independent fallbacks, and a single test that lost one of them would still
;; report the other's name.

(defn- barely-described
  "The rendered fragment for a Volume the catalog described as thinly as it
  ever does."
  []
  (body (search (stub/found [stub/nameless]) "title=x" {:htmx? true})))

(deftest a-volume-with-no-title-renders-under-a-stand-in-name
  (is (str/includes? (barely-described) "Untitled")))

(deftest a-volume-the-catalog-credits-to-nobody-renders-under-a-stand-in-author
  (is (str/includes? (barely-described) "Author unknown")))

(deftest a-barely-described-volume-still-shows-the-fields-it-does-have
  (testing "the fallbacks stand in for what is missing; they do not replace the card"
    (is (str/includes? (barely-described) "1911"))))

(deftest the-form-replaces-the-results-region-rather-than-filling-it
  ;; hx-swap="outerHTML", and it is load-bearing: the fragment `/search` answers
  ;; is itself a `<div id="results">`. htmx's DEFAULT swap is innerHTML, which
  ;; would put that div INSIDE the existing one — a second #results nested in
  ;; the first. Every later swap then resolves `#results` to the outer element
  ;; and nests again, so the page degrades silently rather than breaking, which
  ;; is why losing this attribute needs an assertion of its own.
  (let [rendered (body (search (stub/found []) nil))]
    (is (str/includes? rendered "hx-swap=\"outerHTML\""))
    (is (str/includes? rendered "hx-target=\"#results\""))))

(deftest the-fragment-is-a-whole-results-element-to-swap-for
  ;; The other half of that coupling, and a separate behavior: the attribute
  ;; above says REPLACE #results, and this says the answer is a #results to
  ;; replace it WITH. Either one alone is half a contract, and a deftest holding
  ;; both would report the form's name when it was the fragment that changed.
  (let [fragment (body (search (stub/found [stub/sparse]) "title=x" {:htmx? true}))]
    (is (str/starts-with? fragment "<div"))
    (is (str/includes? fragment "id=\"results\""))))

(deftest a-paragraph-long-description-is-rendered-whole-and-clamped-in-css
  ;; The card used to cut the blurb in Clojure: count 240 characters, backtrack
  ;; to a space, append an ellipsis. That clamps by BYTES, which is not the
  ;; thing a card is short of — it is short of LINES, at a width that changes
  ;; with the viewport — and it cuts inside a grapheme cluster given the wrong
  ;; input. `line-clamp-3` moves the decision to the browser, which knows both.
  ;;
  ;; Until this test there was no fixture longer than about sixty characters,
  ;; so the whole truncation path was uncovered while it existed.
  (let [rendered (body (search (stub/found [stub/long-blurb]) "title=x" {:htmx? true}))]
    (is (< 600 (count stub/long-blurb-description))
        "precondition: the fixture is a real blurb's length, not a phrase")
    (testing "every character of the blurb is in the document"
      (is (str/includes? rendered stub/long-blurb-description)))
    (testing "and the element holding it is the one carrying the clamp"
      ;; Tied to the description paragraph specifically: a clamp somewhere else
      ;; on the card would leave the blurb unbounded and this green.
      (is (re-find (re-pattern (str "<p class=\"[^\"]*line-clamp-3[^\"]*\">"
                                    "A publisher blurb"))
                   rendered)))
    (testing "nothing is cut short with an ellipsis of our own"
      (is (not (str/includes? rendered "…"))))))

(deftest a-search-by-author-alone-reaches-the-port-intact
  (testing "an empty title is dropped rather than searched for"
    (let [seen (atom [])]
      (search (stub/found [] seen) "title=&author=hickey" {:htmx? true})
      (is (= [{:author "hickey"}] @seen)))))

(deftest a-search-by-title-and-author-reaches-the-port-intact
  (let [seen (atom [])]
    (search (stub/found [] seen) "title=clojure&author=hickey" {:htmx? true})
    (is (= [{:title "clojure" :author "hickey"}] @seen))))

(deftest a-percent-encoded-query-reaches-the-port-decoded
  (testing "the port is handed what the reader typed, not what the URL spelled"
    (let [seen (atom [])]
      (search (stub/found [] seen) "title=brave%20new%20world" {:htmx? true})
      (is (= [{:title "brave new world"}] @seen)))))

(deftest an-htmx-search-answers-the-fragment-alone
  (testing "the swapped-in response is the results region, not a whole document"
    (let [rendered (body (search (stub/found [stub/sparse]) "title=clojure" {:htmx? true}))]
      (is (str/includes? rendered "id=\"results\""))
      (is (not (str/includes? rendered "<html")))
      (is (not (str/includes? rendered "<header"))))))

;; Regression: htmx 2.0.10 restores a history entry by REPLAYING it as an
;; hx-request — it sends `HX-Request: true` AND `HX-History-Restore-Request:
;; true`, then swaps the answer into document.body with innerHTML. Answering the
;; fragment therefore replaced the whole page with the bare results region:
;; pressing Back after a search destroyed the page.
;;
;; Three deftests, because the rule and its two boundaries fail independently:
;; the rule can stop firing, and it can start firing where it should not.

(defn- restoring
  "GET /search as htmx replays a history entry, with the restore header set to
  `value`."
  [value]
  (body (search (stub/found [stub/sparse]) "title=clojure"
                {:htmx? true :headers {"hx-history-restore-request" value}})))

(deftest a-history-restore-answers-the-whole-page-even-though-htmx-asked
  (testing "both headers together: the full document, so Back restores a page"
    (let [rendered (restoring "true")]
      (is (str/includes? rendered "<html"))
      (is (str/includes? rendered "<header"))
      (is (str/includes? rendered "Programming Clojure")))))

(deftest hx-request-without-a-restore-header-is-still-the-fragment
  (testing "the ordinary search swap must not be widened by the restore rule"
    (is (not (str/includes? (body (search (stub/found [stub/sparse]) "title=clojure"
                                          {:htmx? true}))
                            "<html")))))

(deftest a-restore-header-htmx-did-not-set-to-true-is-still-the-fragment
  (testing "the rule keys on the value htmx sends, not on the header being present"
    (is (not (str/includes? (restoring "false") "<html")))))

(deftest a-plain-search-answers-the-whole-page-with-its-results
  (testing "without htmx the same URL still works — form GET, full page back"
    (let [rendered (body (search (stub/found [stub/sparse]) "title=clojure"))]
      (is (str/includes? rendered "<html"))
      (is (str/includes? rendered "Programming Clojure"))
      (testing "and the form is filled in with what was searched for"
        (is (str/includes? rendered "value=\"clojure\""))))))

;; ---------------------------------------------------------------------------
;; The three states that are not a list of Volumes
;; ---------------------------------------------------------------------------

(deftest no-matches-renders-the-empty-state
  (testing "a search that worked and found nothing says so — it is not an error"
    (let [response (search (stub/found []) "title=zzzz" {:htmx? true})]
      (is (= 200 (:status response)))
      (is (= "empty" (state-of response)))
      (is (str/includes? (body response) "No books matched")))))

(deftest a-quota-refusal-says-so-specifically
  (testing "HTTP 429 is 'try again shortly', not 'the catalog is broken'"
    (let [response (search (stub/failing :quota) "title=clojure" {:htmx? true})]
      ;; 200 deliberately: htmx does not swap a non-2xx response, and this IS
      ;; the content — a rendered error region. The page rendered fine; the
      ;; search did not.
      (is (= 200 (:status response)))
      (is (= "error" (state-of response)))
      (is (str/includes? (str/lower-case (body response)) "too many searches"))
      (is (not (str/includes? (body response) "No books matched"))))))

(deftest an-unreachable-catalog-renders-the-error-state
  (let [response (search (stub/failing :unavailable) "title=clojure" {:htmx? true})]
    (is (= 200 (:status response)))
    (is (= "error" (state-of response)))
    (is (str/includes? (body response) "could not be reached"))))

(deftest an-unconfigured-book-search-is-honest-about-why
  (testing "no API key: the page says search is not configured, and never why not in detail"
    (let [response (search (stub/failing :not-configured) "title=clojure" {:htmx? true})]
      (is (= 200 (:status response)))
      (is (= "error" (state-of response)))
      (is (str/includes? (body response) "not configured")))))

(deftest an-app-wired-with-no-book-search-at-all-still-serves-the-page
  (testing "the default port is the not-configured one — an absent key cannot crash boot"
    (let [app (handler/make-app nil {:db-optional? true
                                     :session-check (session/signed-in)})
          response (app {:request-method :get :uri "/search" :query-string "title=clojure"})]
      (is (= 200 (:status response)))
      (is (= "error" (state-of response)))
      (is (str/includes? (str (:body response)) "not configured")))))

;; ---------------------------------------------------------------------------
;; Escaping. The catalog's descriptions contain HTML; ours must not.
;; ---------------------------------------------------------------------------

(deftest catalog-supplied-html-is-escaped-everywhere-it-lands
  (let [hostile {:id "x"
                 :title "<script>alert('title')</script>"
                 :authors ["<script>alert('author')</script>"]
                 :published-date "<img src=x onerror=alert('date')>"
                 :description "<b>Bold</b> and <script>alert('description')</script>"
                 :thumbnail "https://books.example.test/\"onerror=\"alert('src')"}
        rendered (body (search (stub/found [hostile]) "title=x" {:htmx? true}))]
    (testing "no catalog string is ever emitted as markup"
      (is (not (str/includes? rendered "<script>")))
      (is (not (str/includes? rendered "<b>Bold</b>")))
      ;; The escaped text still CONTAINS the characters "onerror=…" — inside a
      ;; quoted attribute value, where they are inert. What must not exist is
      ;; an `onerror` that is its own ATTRIBUTE, which needs whitespace before
      ;; it and therefore a quote the escaping let through.
      (is (nil? (re-find #"<[a-z]+[^>]*\son[a-z]+=" rendered))))
    (testing "it is rendered — escaped — rather than silently dropped"
      (is (str/includes? rendered "&lt;script&gt;"))
      (is (str/includes? rendered "&lt;b&gt;Bold&lt;/b&gt;")))
    (testing "and an attribute cannot be broken out of"
      (is (not (re-find #"src=\"https://books\.example\.test/\"onerror" rendered))))))

(deftest reader-supplied-input-is-escaped-when-it-is-echoed-back
  (testing "the form re-renders what was typed, so it is an injection point too"
    (let [rendered (body (search (stub/found []) "title=%22%3E%3Cscript%3Ealert(1)%3C%2Fscript%3E"))]
      (is (not (str/includes? rendered "<script>alert(1)</script>")))
      (is (str/includes? rendered "&lt;script&gt;")))))

;; ---------------------------------------------------------------------------
;; Routing stays as it was
;; ---------------------------------------------------------------------------

(deftest search-is-routed-and-nothing-else-became-routed
  (testing "an unrouted path still 404s, with a Book search wired"
    (let [app (app (stub/found []))]
      (is (= 404 (:status (app {:request-method :get :uri "/searchx"}))))
      (is (= 404 (:status (app {:request-method :get :uri "/search/results"}))))))
  (testing "a search is a GET; it does not answer writes"
    (is (not= 200 (:status ((app (stub/found [])) {:request-method :post :uri "/search"}))))))

(deftest search-answers-head-like-every-other-page-route
  (testing "HEAD /search answers 200 — / and /health both do, and so do the static roots"
    ;; It was the one page route that did not, so a probe or a link checker got
    ;; a 405 that reitit does not even give an Allow header to.
    (let [response (search (stub/found []) "title=clojure" {:method :head})]
      (is (= 200 (:status response)))
      (is (str/starts-with? (get-in response [:headers "Content-Type"]) "text/html")))))

(def ^:private search-representations
  "The three answers /search gives at one URL, for the header tests to sweep."
  [["the page" {}]
   ["the fragment" {:htmx? true}]
   ["the prompt" {:htmx? true :query ""}]])

(deftest search-tells-caches-that-one-url-serves-two-representations
  ;; /search answers a whole page or a bare fragment at ONE URL, chosen by
  ;; request headers — BOTH of the headers `fragment-request?` reads, which is
  ;; why naming only the first was a bug rather than a shorthand. A history
  ;; restore carries `HX-Request: true` as well, so a cache keyed on that header
  ;; alone can answer a restore with a stored fragment — and htmx swaps a
  ;; restore into `document.body`, which is exactly the page-destroying swap the
  ;; restore handling exists to prevent.
  (doseq [[label opts] search-representations]
    (testing label
      (let [response (search (stub/found [stub/sparse]) (get opts :query "title=clojure") opts)]
        ;; The gate ADDS the credential headers to this list rather than
        ;; replacing it (ticket #7). Asserted as one exact string, because both
        ;; halves matter and each is a different bug: drop the htmx headers and a
        ;; cache replays a fragment into a navigation; drop the credential ones
        ;; and it serves one Reader's page to the next visitor.
        (is (= "HX-Request, HX-History-Restore-Request, Authorization, Cookie"
               (get-in response [:headers "Vary"])))))))

(deftest search-results-are-revalidated-rather-than-refused-storage
  ;; `no-store` was the first answer here and it was the wrong one: Chrome
  ;; blocklists any main-frame response carrying it from the back/forward cache,
  ;; and Firefox does the same. So pressing Back onto a /search entry forced a
  ;; full re-navigation and a fresh live catalog call — partially undoing the
  ;; Back-button fix on the very path (a no-JS full page GET) that fix exists to
  ;; protect.
  ;;
  ;; `private, no-cache` keeps what `no-store` was actually bought for: `private`
  ;; keeps a shared cache from holding it at all, and `no-cache` forces
  ;; revalidation on every reuse — and since this response sends no validator,
  ;; revalidation can only mean re-fetching. What it does not do is disqualify
  ;; the page from bfcache, which is a browser-internal store, not an HTTP one.
  (doseq [[label opts] search-representations]
    (testing label
      (let [response (search (stub/found [stub/sparse]) (get opts :query "title=clojure") opts)]
        (is (= "private, no-cache" (get-in response [:headers "Cache-Control"])))))))

(deftest the-landing-page-points-at-the-search-page
  (testing "the roadmap card that announces the slice is itself the link to it"
    ;; NOT a bare `href="/search"`: the shared header carries that link on every
    ;; page, this one included, so an assertion on the href alone stays green
    ;; with the roadmap card deleted — it cannot fail for the reason it states.
    ;; The card's link is the one inside the card's heading.
    (let [rendered (body ((app (stub/found [])) {:request-method :get :uri "/"}))]
      (is (re-find #"<h3[^>]*><a[^>]*href=\"/search\"[^>]*>Search</a></h3>" rendered)))))

;; `the-port-is-the-only-thing-the-handler-knows` stood here. It passed a bare
;; `fn` as the port and asserted the results rendered, on the stated grounds
;; that a bare function "is not an adapter" and so proved the handler reached
;; for no adapter of its own. That distinction died with the protocol: since the
;; port became a plain function, `books.stub-book-search/found` returns a
;; closure too, so every test above already hands `make-app` exactly the same
;; kind of thing — `a-search-by-title-renders-the-volumes-it-found` most
;; directly. The test could no longer fail for the reason it named, and nothing
;; else failed with it.
