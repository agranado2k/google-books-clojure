(ns books.catalog
  "The **Book search** port: the seam between the app and whatever answers a
  search of the Catalog for Volumes. The handler depends on this contract and
  never on an adapter, so a test can inject a double and the production wiring
  can inject the Google Books adapter (`books.google-books`) without either
  knowing about the other.

  A **Volume** is one edition of a book as the Catalog describes it — the term
  is the Catalog's own, and it is what a Book search returns. See
  `docs/domain-glossary.md`.

  ## The contract

  A **Book search is a plain function of one argument**: the query map, already
  normalized by `query` below — optional `:title` and `:author`, trimmed, with
  blank fields dropped, plus the optional `:start-index` naming which page of
  that search to answer.

  It returns a map, and **never throws**: an unreachable or refusing Catalog is
  an outcome the page renders, not an exception the handler catches.

      {:outcome :ok    :volumes [volume …]}   — the search ran; the vector may
                                                be empty (no matches)
      {:outcome :error :reason  reason}       — the search did not run

  `reason` is one of:
    :not-configured  no API key, so no search was attempted
    :quota           the Catalog refused us for rate/quota reasons (HTTP 429)
    :unavailable     unreachable, timed out, or answered anything else

  A volume is a map:
    {:id             \"…\"        the Catalog's own identifier
     :title          \"…\"        may be absent
     :authors        [\"…\"]      possibly empty
     :published-date \"…\"        as the Catalog states it (\"2011\", \"2011-05\")
     :description    \"…\"        may be absent, and may contain HTML —
                                  it is rendered escaped, never as raw markup
     :thumbnail      \"https://…\" may be absent}

  Paging is the `:start-index` key: which Volume of the whole run of matches
  the answered page starts at, absent for the first page. It arrived as another
  key on the query map, which is why the query is a map rather than two
  positional arguments — and why the port is a function of ONE argument rather
  than a protocol whose only method would have to grow a parameter. A search
  answers at most `page-size` Volumes, which is what lets `page-position` tell
  a full page from a final one."
  (:require [clojure.string :as str]))

(def cover-origins
  "Where a Volume's `:thumbnail` is served from.

  Part of the port's contract rather than the adapter's business: the field is
  documented above as a URL a page renders, and a page that renders remote
  images has to admit their origin in its Content-Security-Policy. A policy
  missing these shows every Volume with a broken cover — so the list lives
  beside the field it describes, where a second Catalog would have to update it.

  `books.google-books` upgrades every `http://` thumbnail to `https://` before it
  becomes a Volume, which is why only the secure origins are named."
  ["https://books.google.com" "https://books.googleusercontent.com"])

(def page-size
  "How many Volumes one results page holds — the ONE owner of that number, read
  by the adapter (as the Catalog's `maxResults`) and by `page-position`, which
  can only tell a full page from a final one while the two agree.

  The Catalog's own ceiling is 40; 20 is what the results list shows."
  20)

(def ^:private search-fields
  "The fields that say WHAT to search for. `:start-index` is deliberately not
  one of them: it says which page of a search to show, so it cannot make a
  search out of nothing (see `blank-query?`)."
  [:title :author])

(defn- single
  "One value for a request parameter, whatever shape the parameter middleware
  handed us. Ring's `wrap-params` answers a **vector** when the same name is
  repeated (`/search?title=a&title=b`), so a normalizer that assumed a string
  turned a crafted URL into a 500 — which is why this is total over the shape
  rather than trusting it.

  The FIRST value wins. The form submits each field once, so a repeat is a
  crawler, a stale bookmark, or someone probing; answering the first one keeps
  the search working, which is the only outcome a reader can act on. Anything
  non-nil is coerced to a string for the same reason: normalization must never
  be the thing that throws."
  [v]
  (if (sequential? v) (first v) v))

(defn- offset
  "A raw `start` parameter as the Volume the page begins at, or nil.

  Total, like `single`, and for the same reason: `/search?start=abc` is a URL
  anyone can type, so anything that is not a whole non-negative number is not
  an error — it is simply no offset, and the reader gets the first page. Zero
  is that first page too, so it is dropped rather than carried."
  [v]
  (when-let [n (some-> (single v) str str/trim parse-long)]
    (when (pos? n) n)))

(defn query
  "Normalize raw request params into a search query: trimmed, and with blank
  fields dropped so `blank-query?` can answer honestly and so an adapter never
  builds `intitle:\"\"`. `:start-index` comes through as a number or not at all.

  Total by contract: **no request may make this throw** (see `single`)."
  [params]
  (let [terms (into {}
                    (keep (fn [k]
                            (let [v (some-> (single (get params k)) str str/trim)]
                              (when (seq v) [k v]))))
                    search-fields)
        start (offset (:start-index params))]
    (cond-> terms
      start (assoc :start-index start))))

(defn blank-query?
  "Whether there is nothing to search for. A blank query is not an empty result
  — the page says 'type something', not 'no matches'. Only the search fields
  count: `/search?start=40` with nothing typed is still nothing to search for."
  [query]
  (not-any? query search-fields))

(def ^:private page-positions
  "The four places a page can sit in a run of pages, keyed by whether there are
  pages before it and pages after it. The table is the one spot those two facts
  are read as a pair; everywhere else the position travels under its name."
  {[false false] :only-page
   [false true] :first-page
   [true true] :middle-page
   [true false] :last-page})

(defn page-position
  "Where the page of `volumes` that answered `query` sits: `:only-page`,
  `:first-page`, `:middle-page` or `:last-page`. A named state rather than a
  pair of booleans, because these four ARE the sets of paging controls a
  results region can offer.

  **There is a page after this one exactly when the Catalog filled this one.**
  The Catalog also answers a `totalItems` count and it is deliberately unused:
  Google documents it as an estimate, it fluctuates between identical requests,
  and a control derived from it points at pages that do not exist. A filled
  page is a fact about the response we are holding.

  The price of that honesty is one case: when the matches run out on an exact
  multiple of `page-size`, the final full page still offers a next page, and it
  answers the empty state."
  [{:keys [start-index]} volumes]
  (page-positions [(pos? (or start-index 0))
                   (<= page-size (count volumes))]))

(def not-configured
  "The Book search used when nothing has been wired in: every search is an
  honest `:not-configured` error. It is the default rather than a nil check so
  that an unconfigured deploy renders a message instead of a 500.

  It is also the ONE owner of that result value — the Google Books adapter's
  keyless branch calls this rather than rebuilding the map, so the contract
  cannot drift between the port and its adapter."
  (constantly {:outcome :error :reason :not-configured}))
