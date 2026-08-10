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

(deftest blank-query-is-nothing-to-search-for
  (testing "a query with neither field is not a search"
    (is (true? (catalog/blank-query? {})))
    (is (false? (catalog/blank-query? {:title "Clojure"})))
    (is (false? (catalog/blank-query? {:author "Hickey"})))))

(deftest not-configured-book-search-answers-an-error-rather-than-throwing
  (testing "with no Book search wired the search surface degrades, it does not crash"
    (is (= {:outcome :error :reason :not-configured}
           (catalog/not-configured {:title "Clojure"})))))
