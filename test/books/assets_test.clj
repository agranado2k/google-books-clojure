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

;; That the SERVED url and the classpath resource name the same file is proved
;; where it matters and where it can actually be wrong: `books.handler-test`
;; does a real GET of `assets/htmx-path` and asserts a 200 with a JavaScript
;; content type. A test here that restated the two defs to each other could only
;; fail when someone edited a def, and it would go green again by editing the
;; test — which is not a test, it is a copy.
