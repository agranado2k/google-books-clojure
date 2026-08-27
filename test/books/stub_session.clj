(ns books.stub-session
  "Session check doubles (see `books.reader`), for the tests that are about
  something else.

  The gate's own tests never use these — `books.auth-test` wires the real Clerk
  verifier, because a gate tested through a double is a gate nobody tested. What
  these are for is every test whose subject is the search page, the error page
  or the cache headers, and which would otherwise have to mint a token to reach
  the handler it is actually about."
  (:require [books.reader :as reader]))

(def reader-id
  "The Reader every `signed-in` check identifies. Named, so a test asserting on
  who the request belonged to has something to compare against."
  "user_stub_reader")

(defn signed-in
  "A Session check that signs every request in, whatever it carries — including
  nothing at all."
  []
  (constantly {:outcome :signed-in :reader {:id reader-id}}))

(defn signed-out
  "A Session check that refuses every request, as though the token had expired."
  []
  (constantly {:outcome :signed-out :reason :expired}))

;; Re-exported so a test naming the unconfigured case reaches for it here with
;; the others, rather than reaching past this namespace into the port.
(def not-configured reader/not-configured)
