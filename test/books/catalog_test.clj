(ns books.catalog-test
  "The Book search port itself: query normalization and the implementation used
  when no Book search is configured. The adapters are tested in their own
  namespaces."
  (:require [books.catalog :as catalog]
            [clojure.test :refer [deftest is testing]]))

(deftest query-trims-and-drops-blanks
  (testing "surrounding whitespace is not part of what the reader searched for"
    (is (= {:title "Clojure" :author "Hickey"}
           (catalog/query {:title "  Clojure " :author "Hickey  "}))))
  (testing "a blank field is the same as an absent one"
    (is (= {:title "Clojure"} (catalog/query {:title "Clojure" :author "   "})))
    (is (= {:author "Hickey"} (catalog/query {:title "" :author "Hickey"})))
    (is (= {} (catalog/query {:title "  " :author nil})))
    (is (= {} (catalog/query nil)))))

(deftest query-carries-the-page-to-start-at
  (testing "a non-negative integer is the offset of the page the reader asked for"
    (is (= {:title "Clojure" :start-index 20}
           (catalog/query {:title "Clojure" :start-index "20"}))))
  (testing "the first page is the ABSENCE of an offset, not a zero"
    ;; Same rule as a blank title: the query map holds what was asked for and
    ;; nothing else, so the adapter builds no `startIndex=0` and every existing
    ;; assertion about what reaches the port keeps its shape.
    (is (= {:title "Clojure"} (catalog/query {:title "Clojure" :start-index "0"}))))
  (testing "an offset the reader could type by hand is never allowed to throw"
    ;; The last one is in range for no integer type: `parse-long` answers nil
    ;; rather than throwing, which is the whole reason it is the parser here.
    (doseq [garbage [nil "" "   " "abc" "-1" "1.5" "20x" "0x14"
                     "99999999999999999999"]]
      (is (= {:title "Clojure"} (catalog/query {:title "Clojure" :start-index garbage}))
          (str "start=" (pr-str garbage)))))
  (testing "a repeated parameter is the same shape hazard the search terms have"
    (is (= {:title "Clojure" :start-index 40}
           (catalog/query {:title "Clojure" :start-index ["40" "80"]})))))

(deftest blank-query-is-nothing-to-search-for
  (testing "a query with neither field is not a search"
    (is (true? (catalog/blank-query? {})))
    (is (false? (catalog/blank-query? {:title "Clojure"})))
    (is (false? (catalog/blank-query? {:author "Hickey"})))))

(deftest a-page-offset-on-its-own-is-still-nothing-to-search-for
  (testing "paging says WHICH page of a search to show, never what to search for"
    ;; `/search?start=40` with nothing typed must prompt, not run an empty
    ;; search against the catalog.
    (is (true? (catalog/blank-query? (catalog/query {:start-index "40"}))))
    (is (true? (catalog/blank-query? {:start-index 40})))))

;; ---------------------------------------------------------------------------
;; Where a page sits in the run of pages
;; ---------------------------------------------------------------------------

(def ^:private a-volume {:id "3IGvBQAAQBAJ"})

(defn- page-of
  "`n` Volumes — enough of a page for the position derivation, which counts
  them and reads nothing else."
  [n]
  (vec (repeat n a-volume)))

(deftest a-results-page-stays-inside-the-catalogs-own-ceiling
  (testing "one number, and one the Catalog will accept as maxResults"
    (is (<= 1 catalog/page-size 40))))

(deftest page-position-names-which-way-a-reader-can-move
  (let [full (page-of catalog/page-size)
        short (page-of 3)
        second-page {:title "Clojure" :start-index catalog/page-size}]
    (testing "a first page the Catalog filled has a page after it and none before"
      (is (= :first-page (catalog/page-position {:title "Clojure"} full))))
    (testing "a first page the Catalog could not fill is the only page there is"
      (is (= :only-page (catalog/page-position {:title "Clojure"} short))))
    (testing "a filled page part way in has pages on both sides"
      (is (= :middle-page (catalog/page-position second-page full))))
    (testing "a short page part way in is the end of the run"
      (is (= :last-page (catalog/page-position second-page short))))
    (testing "…and so is an empty one, which is a last page rather than a first"
      (is (= :last-page (catalog/page-position second-page []))))))

(deftest a-filled-page-is-what-says-another-page-exists
  (testing "one Volume short of a full page is the end, however many that is"
    ;; The signal is the page the Catalog FILLED, never a total it estimated —
    ;; see `page-position`. So the boundary sits exactly at `page-size`.
    (is (= :first-page (catalog/page-position {} (page-of catalog/page-size))))
    (is (= :only-page (catalog/page-position {} (page-of (dec catalog/page-size)))))))

(deftest not-configured-book-search-answers-an-error-rather-than-throwing
  (testing "with no Book search wired the search surface degrades, it does not crash"
    (is (= {:outcome :error :reason :not-configured}
           (catalog/not-configured {:title "Clojure"})))))
