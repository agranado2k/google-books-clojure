(ns books.stub-book-search
  "The test doubles for the **Book search** port (see `books.catalog`) — the
  second implementation the port exists for. Handler tests drive every state of
  the search page through these: results, no matches, and each way a search
  fails.

  The port is a plain function of the query map, so a double is a closure
  rather than a `reify` — which is most of why the port is a function.

  Not a test namespace: the name deliberately does not end in `-test`, so the
  runner loads it only as a dependency of the namespaces that require it.")

(defn answering
  "A Book search that answers `result` for every query. When `seen` (an atom)
  is given, each query it is asked is conj'd onto it, so a test can assert what
  the handler actually sent to the port."
  ([result] (answering result nil))
  ([result seen]
   (fn [query]
     (when seen (swap! seen conj query))
     result)))

(defn found
  "A successful search answering `volumes`."
  ([volumes] (found volumes nil))
  ([volumes seen] (answering {:outcome :ok :volumes volumes} seen)))

(defn failing
  "A search that fails for `reason` (:quota, :unavailable, :not-configured)."
  [reason]
  (answering {:outcome :error :reason reason}))

(def brave-and-true
  "One fully-populated Volume — every field the card renders."
  {:id "3IGvBQAAQBAJ"
   :title "Clojure for the Brave and True"
   :authors ["Daniel Higginbotham"]
   :published-date "2015-10-15"
   :description "Learn to program with Clojure, one silly illustration at a time."
   :thumbnail "https://books.example.test/cover.jpg"})

(def sparse
  "A Volume the Catalog described sparsely — no cover, no date, no blurb."
  {:id "CVBhtQAACAAJ" :title "Programming Clojure" :authors ["Alex Miller"]})

(def long-blurb-description
  "A description the length a real catalog blurb actually runs to. Deliberately
  plain ASCII with no markup and **no apostrophes** — hiccup2 escapes those, and
  this fixture exists so a test can compare the rendered text to it directly,
  which only works while the two are identical. Escaping has its own tests."
  (str "A publisher blurb does not stop at a sentence. It introduces the "
       "premise, then the setting, then the protagonist, then the reversal "
       "that the marketing copy insists you will not see coming, and it keeps "
       "going for as long as the catalog entry allows it to. This one runs "
       "well past six hundred characters, which is ordinary for the Google "
       "Books catalog and far past anything a card can show, so it is exactly "
       "the input the clamp on that card exists for. Every character of it is "
       "in the document; how much of it a reader sees is the browser decision, "
       "taken from the rendered line count at the real width of the card."))

(def long-blurb
  "A Volume the Catalog described at length — the ordinary case, and the one no
  other fixture here covers."
  {:id "Nq9dEAAAQBAJ"
   :title "The Long Description"
   :authors ["A Verbose Publisher"]
   :published-date "2024"
   :description long-blurb-description})
