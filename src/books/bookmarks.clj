(ns books.bookmarks
  "The Bookmark store: the only namespace that writes SQL for the `bookmarks`
  table (ADR-0006).

  A **Bookmark** is one Reader's decision to keep one Volume — the pair is its
  identity, the database enforces that the pair is unique, and the row carries a
  snapshot of the Volume (title, authors, thumbnail, published date) copied at
  bookmark time so a page listing Bookmarks never calls the Catalog. See
  `docs/domain-glossary.md`.

  **Every statement here carries `reader_id = ?`.** That is what makes Reader
  isolation a property of the SQL rather than a check a later caller could
  forget: a delete scoped this way is a no-op against somebody else's row, and
  there is no query in this repo that can answer one. The Reader id comes from
  the verified session (`:reader` on the request, attached by the gate) and never
  from a request parameter.

  Every function takes the datasource `books.handler` already holds — the same
  shape `books.db/checker` is handed, and nil when this deployment runs without
  a database."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.sql Array Connection)))

(defn- text-array
  "`values` as a Postgres `text[]` parameter.

  Built through the connection because that is the only thing that knows the
  server's type for `text` — pgjdbc cannot infer an array type from a Clojure
  vector, and a joined string could not be un-joined into a Volume's authors."
  [^Connection connection values]
  (.createArrayOf connection "text" (into-array String values)))

(defn save!
  "Record `volume` as bookmarked by `reader-id`, or do nothing if it already is.

  `on conflict do nothing` rather than a read-then-write: the check and the
  insert would otherwise be two statements with a race between them, and the
  primary key is what closes it. Bookmarking twice is therefore the same request
  answered twice, not an error a caller has to distinguish."
  [datasource reader-id {:keys [id title authors published-date thumbnail]}]
  (with-open [connection (jdbc/get-connection datasource)]
    (jdbc/execute-one!
     connection
     ["insert into bookmarks
         (reader_id, volume_id, title, authors, thumbnail, published_date)
       values (?, ?, ?, ?, ?, ?)
       on conflict (reader_id, volume_id) do nothing"
      reader-id id title (text-array connection (or authors [])) thumbnail published-date])))

(defn remove!
  "Drop `reader-id`'s Bookmark of `volume-id`. Removing one that is not there is
  not an error: a double click and a stale page both produce it."
  [datasource reader-id volume-id]
  (jdbc/execute-one! datasource
                     ["delete from bookmarks where reader_id = ? and volume_id = ?"
                      reader-id volume-id]))

(defn- snapshot
  "One row as the Volume shape a card draws — the snapshot and nothing else, so
  a page built from these never reaches the Catalog (ADR-0006 clause 4).

  `authors` is a Postgres array, which arrives as a JDBC handle rather than a
  list; the column is `not null default '{}'`, so an authorless Volume reads as
  an empty vector rather than a nil a caller has to guard."
  [{:keys [volume_id title authors thumbnail published_date]}]
  {:id volume_id
   :title title
   :authors (vec (.getArray ^Array authors))
   :thumbnail thumbnail
   :published-date published_date})

(defn for-reader
  "Every Bookmark `reader-id` keeps, as Volumes, most recently bookmarked first.

  That order is the only one a reader can predict: the last thing they kept is
  the thing they came back for. `volume_id` breaks the tie, because two rows
  written by one statement share a `bookmarked_at` to the microsecond and an
  unordered tie would reshuffle the page between two identical requests.

  Unbounded on purpose — ADR-0006 caps nothing, and paging a list nobody has
  yet filled is a decision for the ticket that measures one.

  A deployment with no database has no Bookmarks to list, which is the answer
  `bookmarked-ids` and `books.db/check` both give a nil datasource."
  [datasource reader-id]
  (if (nil? datasource)
    []
    (mapv snapshot
          (jdbc/execute! datasource
                         ["select volume_id, title, authors, thumbnail, published_date
                           from bookmarks
                           where reader_id = ?
                           order by bookmarked_at desc, volume_id"
                          reader-id]
                         {:builder-fn rs/as-unqualified-lower-maps}))))

(defn bookmarked-ids
  "Which of `volume-ids` `reader-id` has already bookmarked, as a set.

  ONE statement for a whole page of Volumes, never one per card. A deployment
  with no database has no Bookmarks to report — the same answer `books.db/check`
  gives a nil datasource — and an empty page asks nothing at all."
  [datasource reader-id volume-ids]
  (if (or (nil? datasource) (empty? volume-ids))
    #{}
    (with-open [connection (jdbc/get-connection datasource)]
      (into #{}
            (map :volume_id)
            (jdbc/execute! connection
                           ["select volume_id from bookmarks
                             where reader_id = ? and volume_id = any(?)"
                            reader-id (text-array connection volume-ids)]
                           {:builder-fn rs/as-unqualified-lower-maps})))))
