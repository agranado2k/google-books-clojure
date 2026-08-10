(ns books.stub-catalog
  "The test double for the Books port (`books.catalog/BookSearch`) — the second
  implementation the port exists for. Handler tests drive every state of the
  search page through this: results, no matches, and each way a search fails.

  Not a test namespace: the name deliberately does not end in `-test`, so the
  runner loads it only as a dependency of the namespaces that require it."
  (:require [books.catalog :as catalog]))

(defn answering
  "A `BookSearch` that answers `result` for every query. When `seen` (an atom)
  is given, each query it is asked is conj'd onto it, so a test can assert what
  the handler actually sent to the port."
  ([result] (answering result nil))
  ([result seen]
   (reify catalog/BookSearch
     (search-volumes [_ query]
       (when seen (swap! seen conj query))
       result))))

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
  "A Volume the catalog described sparsely — no cover, no date, no blurb."
  {:id "CVBhtQAACAAJ" :title "Programming Clojure" :authors ["Alex Miller"]})
