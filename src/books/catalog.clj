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
  blank fields dropped.

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

  Paging is not part of this signature yet (ticket #6): it arrives as another
  optional key on the query map, which is why the query is a map rather than
  two positional arguments — and why the port is a function of ONE argument
  rather than a protocol whose only method would have to grow a parameter."
  (:require [clojure.string :as str]))

(defn query
  "Normalize raw request params into a search query: trimmed, and with blank
  fields dropped so `blank-query?` can answer honestly and so an adapter never
  builds `intitle:\"\"`."
  [params]
  (into {}
        (keep (fn [k]
                (let [v (some-> (get params k) str/trim)]
                  (when (seq v) [k v]))))
        [:title :author]))

(defn blank-query?
  "Whether there is nothing to search for. A blank query is not an empty result
  — the page says 'type something', not 'no matches'."
  [query]
  (empty? query))

(def not-configured
  "The Book search used when nothing has been wired in: every search is an
  honest `:not-configured` error. It is the default rather than a nil check so
  that an unconfigured deploy renders a message instead of a 500.

  It is also the ONE owner of that result value — the Google Books adapter's
  keyless branch calls this rather than rebuilding the map, so the contract
  cannot drift between the port and its adapter."
  (constantly {:outcome :error :reason :not-configured}))
