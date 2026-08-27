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

(deftest not-configured-book-search-answers-an-error-rather-than-throwing
  (testing "with no Book search wired the search surface degrades, it does not crash"
    (is (= {:outcome :error :reason :not-configured}
           (catalog/not-configured {:title "Clojure"})))))
