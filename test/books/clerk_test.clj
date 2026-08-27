(ns books.clerk-test
  "The Clerk adapter's own tests: a token in, a Session check outcome out.

  Every token here is minted in-process with the throwaway RSA pair in
  `books.test-jwt` and verified through the REAL path — buddy's RS256 check
  against a JWKS this namespace serves from an injected fetch. No network, no
  Clerk instance, and no key material in the repository."
  (:require [books.clerk :as clerk]
            [books.test-jwt :as test-jwt]
            [clojure.test :refer [deftest is testing]]))

(defn- checking
  "A Session check wired to the test keypair's JWKS. `opts` overrides anything
  — the authorized party, the fetch, the clock — so each test names only the
  thing it is about."
  ([] (checking {}))
  ([opts]
   (clerk/session-check (merge {:publishable-key test-jwt/publishable-key
                                :authorized-party test-jwt/authorized-party
                                :fetch (fn [_url] (test-jwt/jwks))}
                               opts))))

(defn- outcome [check token] (check token))
(defn- reason [check token] (:reason (check token)))

;; ---------------------------------------------------------------------------
;; The publishable key is the one piece of configuration, and the JWKS location
;; is derived from it rather than configured beside it.
;; ---------------------------------------------------------------------------

(deftest frontend-api-is-decoded-from-the-publishable-key
  (testing "a publishable key carries its instance's Frontend API host, base64'd"
    (is (= test-jwt/frontend-api (clerk/frontend-api test-jwt/publishable-key))))
  (testing "both instance kinds carry it the same way"
    (is (= "clerk.example.com"
           (clerk/frontend-api (test-jwt/publishable-key-for "pk_live_" "clerk.example.com")))))
  (testing "anything that is not a publishable key decodes to nothing, never to a guess"
    (doseq [not-a-key [nil "" "   " "sk_test_secret" "pk_test_" "pk_test_!!!not-base64!!!"
                       "pk_test_bm8tdGVybWluYXRvcg=="]]
      (is (nil? (clerk/frontend-api not-a-key))
          (str (pr-str not-a-key) " must not decode to a host")))))

(deftest jwks-url-hangs-off-the-frontend-api
  (testing "the JWKS location is derived, so it cannot name a different instance than the key"
    (is (= (str "https://" test-jwt/frontend-api "/.well-known/jwks.json")
           (clerk/jwks-url test-jwt/publishable-key))))
  (testing "no key, no URL"
    (is (nil? (clerk/jwks-url "not-a-key")))))

;; ---------------------------------------------------------------------------
;; The happy path
;; ---------------------------------------------------------------------------

(deftest a-valid-token-identifies-the-reader
  (testing "the Reader's identity is the sub claim, and nothing else from the token"
    (let [result (outcome (checking) (test-jwt/token))]
      (is (= :signed-in (:outcome result)))
      (is (= {:id "user_2readerid"} (:reader result)))))
  (testing "the sub claim is what carries through, whatever it is"
    (is (= {:id "user_someone_else"}
           (:reader (outcome (checking) (test-jwt/token {:sub "user_someone_else"})))))))

;; ---------------------------------------------------------------------------
;; Refusals. Every one of these is a signed-out outcome, never a throw.
;; ---------------------------------------------------------------------------

(deftest no-token-is-not-an-error
  (testing "an anonymous request carried nothing to check"
    (doseq [nothing [nil "" "   "]]
      (is (= {:outcome :signed-out :reason :absent} (outcome (checking) nothing))))))

(deftest an-expired-token-is-refused-and-named-as-expired
  (testing "a Clerk session token lives 60 seconds, so this is the ordinary refusal"
    (is (= :expired (reason (checking) (test-jwt/expired-token))))))

(deftest a-token-not-yet-valid-is-refused
  (testing "nbf is validated as well as exp — a token from the future is not a token"
    (is (= :invalid (reason (checking) (test-jwt/not-yet-valid-token))))))

(deftest a-token-for-a-different-authorized-party-is-refused
  ;; Skipping azp is the documented CSRF hole: a token minted for another origin
  ;; is a perfectly valid Clerk signature, and without this check it signs its
  ;; bearer in here.
  (testing "azp must equal this app's own origin"
    (is (= :invalid (reason (checking) (test-jwt/token {:azp "https://attacker.example"})))))
  (testing "and a token with no azp at all is refused rather than waved through"
    (is (= :invalid (reason (checking) (test-jwt/token {:azp nil}))))
    (is (= :invalid (reason (checking) (test-jwt/token-without :azp))))))

(deftest a-token-with-no-subject-is-refused
  (testing "the Reader IS the sub claim, so a token without one identifies nobody"
    (is (= :invalid (reason (checking) (test-jwt/token-without :sub))))
    (is (= :invalid (reason (checking) (test-jwt/token {:sub "   "}))))))

(deftest a-token-with-no-expiry-is-refused
  ;; buddy accepts a signed token that simply omits `exp` — verified against
  ;; buddy-sign 3.6.1: `unsign` of {:sub "u"} returns the claims. Clerk always
  ;; sends one, so a token without it is not a Clerk session token, and a
  ;; never-expiring credential is the last thing to accept on trust.
  (testing "an absent exp is refused rather than read as 'no deadline'"
    (is (= :invalid (reason (checking) (test-jwt/token-without :exp))))))

(deftest a-token-signed-by-another-key-is-refused
  (testing "a well-formed token whose signature is not the instance's is not a session"
    (is (= :invalid (reason (checking) (test-jwt/token-signed-by-a-stranger))))))

(deftest an-unsigned-token-is-refused
  ;; The classic JWT forgery: strip the signature and declare `alg: none`.
  (testing "alg: none is a forgery, not an algorithm"
    (is (= :invalid (reason (checking) (test-jwt/alg-none-token))))))

(deftest an-algorithm-confused-token-is-refused
  ;; The other classic: sign with HMAC and hope the verifier treats the RSA
  ;; public key as a shared secret.
  (testing "an HS256 token is not accepted by an RS256 verifier"
    (is (= :invalid (reason (checking) (test-jwt/hs256-token))))))

(deftest a-malformed-token-is-refused-rather-than-thrown
  (testing "garbage in the header is a refusal like any other — the check never throws"
    (doseq [junk ["not-a-jwt" "a.b.c" "...." "eyJhbGciOiJSUzI1NiJ9" "%%%.%%%.%%%"]]
      (is (= {:outcome :signed-out :reason :invalid} (outcome (checking) junk))
          (str (pr-str junk) " must be refused, not thrown")))))

;; ---------------------------------------------------------------------------
;; The JWKS: fetched once, refetched on rotation, and never on demand from an
;; anonymous caller.
;; ---------------------------------------------------------------------------

(defn- counting-fetch
  "A JWKS fetch that records how many times it was called."
  [calls jwks]
  (fn [_url] (swap! calls inc) jwks))

(deftest the-jwks-is-fetched-once-and-cached
  (testing "verification must not become one HTTPS call per request"
    (let [calls (atom 0)
          check (checking {:fetch (counting-fetch calls (test-jwt/jwks))})]
      (dotimes [_ 20] (check (test-jwt/token)))
      (is (= 1 @calls)))))

(deftest a-rotated-key-is-picked-up-without-a-restart
  ;; The two halves of this test are the two halves of one trade-off, and it is
  ;; the trade-off `refetch-interval-ms` exists to make: an unresolvable `kid`
  ;; refetches, because that is how a rotation is noticed at all — but no more
  ;; often than the interval, because the `kid` is attacker-chosen. So a
  ;; rotation costs at most one interval of refusals, and that cost is the
  ;; price of not handing an anonymous caller a fetch amplifier.
  (let [published (atom (test-jwt/jwks))
        calls (atom 0)
        now (atom 0)
        check (clerk/session-check {:publishable-key test-jwt/publishable-key
                                    :authorized-party test-jwt/authorized-party
                                    :clock (fn [] @now)
                                    :fetch (fn [_url] (swap! calls inc) @published)})]
    (is (= :signed-in (:outcome (check (test-jwt/token)))))
    (is (= 1 @calls) "precondition: the first check warmed the cache")

    ;; Clerk rotates: a new kid signs from now on, and the old one leaves.
    (reset! published (test-jwt/rotated-jwks))

    (testing "inside the interval the new key is not yet visible, and is refused"
      (is (= :invalid (reason check (test-jwt/rotated-token))))
      (is (= 1 @calls) "…and the refusal cost no fetch at all"))

    (testing "once the interval has passed, one refetch picks the rotation up"
      (swap! now + (inc clerk/refetch-interval-ms))
      (is (= :signed-in (:outcome (check (test-jwt/rotated-token)))))
      (is (= 2 @calls) "exactly one refetch, not one per token checked"))

    (testing "and the refreshed key set is then cached in its turn"
      (dotimes [_ 10] (check (test-jwt/rotated-token)))
      (is (= 2 @calls)))))

(deftest an-unknown-key-id-does-not-become-a-fetch-per-request
  ;; /search is reachable by anyone, so an unauthenticated caller can name any
  ;; `kid` it likes. Without a floor between refetches that is an amplifier:
  ;; one cheap forged header per outbound HTTPS request to Clerk.
  (testing "a flood of unresolvable key ids refetches at most once per interval"
    (let [calls (atom 0)
          check (checking {:fetch (counting-fetch calls (test-jwt/jwks))})]
      (dotimes [_ 50] (check (test-jwt/token {} {:kid "no-such-key"})))
      (is (= 1 @calls))))
  (testing "…and the interval is what releases it, not the request count"
    (let [calls (atom 0)
          now (atom 0)
          check (checking {:fetch (counting-fetch calls (test-jwt/jwks))
                           :clock (fn [] @now)})]
      (check (test-jwt/token {} {:kid "no-such-key"}))
      (is (= 1 @calls))
      (swap! now + (inc clerk/refetch-interval-ms))
      (check (test-jwt/token {} {:kid "no-such-key"}))
      (is (= 2 @calls)))))

(deftest an-unreachable-jwks-is-a-refusal-not-a-crash
  (testing "Clerk being unreachable signs nobody in, and throws nothing at the handler"
    (let [check (checking {:fetch (fn [_url] (throw (java.io.IOException. "connection refused")))})]
      (is (= {:outcome :signed-out :reason :invalid} (outcome check (test-jwt/token))))))
  (testing "and neither does a JWKS that is not a JWKS"
    (doseq [nonsense [nil {} {"keys" []} {"keys" "not-a-list"} {"keys" [{"kid" "k" "kty" "oops"}]}]]
      (let [check (checking {:fetch (fn [_url] nonsense)})]
        (is (= :invalid (reason check (test-jwt/token)))
            (str (pr-str nonsense) " must be refused, not thrown"))))))

(deftest an-unconfigured-adapter-refuses-everything
  (testing "no publishable key means no JWKS to verify against, so nobody is signed in"
    (let [check (clerk/session-check {:authorized-party test-jwt/authorized-party})]
      (is (= {:outcome :signed-out :reason :not-configured} (outcome check (test-jwt/token))))))
  (testing "and neither does a key with no authorized party to check azp against"
    ;; Without a configured origin there is nothing to compare azp to, and
    ;; 'compare it to nothing' is the CSRF hole. Refuse instead.
    (let [check (clerk/session-check {:publishable-key test-jwt/publishable-key
                                      :fetch (fn [_url] (test-jwt/jwks))})]
      (is (= {:outcome :signed-out :reason :not-configured} (outcome check (test-jwt/token)))))))

(deftest an-explicit-jwks-url-overrides-the-derived-one
  (testing "the override is what the fetch is actually given"
    (let [asked (atom nil)
          check (checking {:jwks-url "https://jwks.internal.test/keys"
                           :fetch (fn [url] (reset! asked url) (test-jwt/jwks))})]
      (is (= :signed-in (:outcome (check (test-jwt/token)))))
      (is (= "https://jwks.internal.test/keys" @asked))))
  (testing "and without one, the derived URL is"
    (let [asked (atom nil)
          check (checking {:fetch (fn [url] (reset! asked url) (test-jwt/jwks))})]
      (check (test-jwt/token))
      (is (= (clerk/jwks-url test-jwt/publishable-key) @asked)))))
