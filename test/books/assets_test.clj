(ns books.assets-test
  "The vendored front-end assets. htmx is a third-party script we serve from
  our own origin, so the bytes in the repo are the supply chain: this suite is
  what verifies them, and it runs everywhere the suite runs — locally, in CI,
  and against whatever a build stage packaged."
  (:require [books.assets :as assets]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import (java.security MessageDigest)))

(defn- sha256
  "Lowercase hex SHA-256 of a classpath resource."
  [resource]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream (io/resource resource))]
      (let [buffer (byte-array 8192)]
        (loop []
          (let [read (.read in buffer)]
            (when (pos? read)
              (.update digest buffer 0 read)
              (recur))))))
    (apply str (map #(format "%02x" %) (.digest digest)))))

(deftest vendored-htmx-matches-its-pin
  (testing "the committed htmx is byte-for-byte the release we pinned"
    (is (some? (io/resource assets/htmx-resource))
        (str assets/htmx-resource " is missing — run scripts/vendor-htmx.sh"))
    (is (= assets/htmx-sha256 (sha256 assets/htmx-resource))
        "the vendored htmx does not match its pinned digest")))

(deftest the-served-url-names-the-version-it-serves
  (testing "the URL is version-stamped, which is what lets it be cached forever"
    (is (= "/js/htmx-2.0.10.min.js" assets/htmx-path))
    (is (= (str "public" assets/htmx-path) assets/htmx-resource))))
