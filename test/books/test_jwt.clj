(ns books.test-jwt
  "The test keypair, and the tokens minted with it.

  Nothing here is a Clerk key and nothing here is committed: the RSA pairs are
  generated in this process, at test time, and live only in memory. That is what
  lets a test drive the REAL verification path — `books.clerk`'s own JWKS
  resolution, RS256 signature check and claim validation — with no network, no
  Clerk instance, and no key material in the repository.

  The forgeries live here too (`alg-none-token`, `hs256-token`,
  `token-signed-by-a-stranger`), because a verifier is only as interesting as
  the attacks it is shown."
  (:require [buddy.core.keys :as keys]
            [buddy.sign.jwt :as jwt]
            [jsonista.core :as json])
  (:import (java.security KeyPair KeyPairGenerator)
           (java.util Base64)))

(def key-id
  "The `kid` the minted tokens carry and the test JWKS publishes. A token signed
  under any other `kid` is a key the adapter cannot resolve, which is its own
  test."
  "test-key-1")

(def rotated-key-id
  "The `kid` that replaces `key-id` when the test rotates the instance's keys."
  "test-key-2")

(def frontend-api
  "The Frontend API host the test publishable key encodes."
  "test-instance.clerk.accounts.dev")

(defn publishable-key-for
  "A Clerk publishable key for `host`: the prefix, then base64 of the host and
  Clerk's `$` terminator. A publishable key is not a credential — it is rendered
  into every page by design — and these name instances that do not exist."
  [prefix host]
  (str prefix (.encodeToString (Base64/getEncoder) (.getBytes (str host "$") "UTF-8"))))

(def publishable-key (publishable-key-for "pk_test_" frontend-api))

(def issuer (str "https://" frontend-api))

(def authorized-party
  "The origin this app is served from, as `azp` carries it."
  "https://books.example.test")

(defn- rsa-pair []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))))

(def ^:private pair (delay (rsa-pair)))
(def ^:private rotated-pair (delay (rsa-pair)))
(def ^:private stranger-pair (delay (rsa-pair)))

(defn- jwk-entry
  "One JWKS entry in the shape a JSON parse of Clerk's `.well-known/jwks.json`
  produces: string keys throughout."
  [^KeyPair kp kid]
  (let [{:keys [kty n e]} (keys/public-key->jwk (.getPublic kp))]
    {"kid" kid "kty" kty "alg" "RS256" "use" "sig" "n" n "e" e}))

(defn jwks
  "The instance's published keys."
  []
  {"keys" [(jwk-entry @pair key-id)]})

(defn rotated-jwks
  "What the same endpoint publishes after a rotation: a new key, and the old one
  gone."
  []
  {"keys" [(jwk-entry @rotated-pair rotated-key-id)]})

(defn- now-seconds [] (quot (System/currentTimeMillis) 1000))

(defn- valid-claims []
  (let [now (now-seconds)]
    {:sub "user_2readerid"
     :sid "sess_1"
     :iss issuer
     :azp authorized-party
     :iat now
     :nbf now
     ;; Clerk session tokens live sixty seconds. Verified against Clerk's
     ;; "How Clerk works" documentation, 2026-08-27.
     :exp (+ now 60)
     :v 2}))

(defn- sign-with
  [^KeyPair kp claims header]
  (jwt/sign claims (.getPrivate kp) {:alg :rs256 :header header}))

(defn token
  "An RS256 token signed with the test private key. `claims` is merged over a
  valid Clerk session token, so a test names only the claim it is about."
  ([] (token {}))
  ([claims] (token claims {:kid key-id}))
  ([claims header] (sign-with @pair (merge (valid-claims) claims) header)))

(defn token-without
  "A token missing `claim` entirely — which is a different thing from a token
  carrying it as null."
  [claim]
  (sign-with @pair (dissoc (valid-claims) claim) {:kid key-id}))

(defn expired-token
  "A token that was valid a minute ago — the ordinary case, since a Clerk
  session token lives for sixty seconds."
  []
  (let [now (now-seconds)]
    (token {:iat (- now 120) :nbf (- now 120) :exp (- now 60)})))

(defn not-yet-valid-token
  "A token whose `nbf` has not arrived."
  []
  (let [now (now-seconds)]
    (token {:nbf (+ now 300) :exp (+ now 600)})))

(defn rotated-token
  "A token signed by the rotated key, carrying the rotated `kid`."
  []
  (sign-with @rotated-pair (valid-claims) {:kid rotated-key-id}))

(defn token-signed-by-a-stranger
  "A well-formed token whose signature belongs to a key the instance never
  published, carrying the instance's `kid` so that only the signature check can
  catch it."
  []
  (sign-with @stranger-pair (valid-claims) {:kid key-id}))

(defn- b64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn alg-none-token
  "The classic forgery: real claims, no signature, and a header declaring there
  is no algorithm."
  []
  (str (b64url (.getBytes (json/write-value-as-string {:alg "none" :kid key-id}) "UTF-8"))
       "."
       (b64url (.getBytes (json/write-value-as-string (valid-claims)) "UTF-8"))
       "."))

(defn hs256-token
  "The other classic: an HMAC-signed token, betting the verifier will treat the
  RSA public key as a shared secret."
  []
  (jwt/sign (valid-claims) "the-public-key-as-a-secret"
            {:alg :hs256 :header {:kid key-id}}))
