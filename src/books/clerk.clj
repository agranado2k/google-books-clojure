(ns books.clerk
  "The real **Session check** adapter (see `books.reader`): a Clerk session
  token, verified against the instance's published keys.

  Clerk ships no Clojure SDK, so the supported path for a backend like this one
  is Clerk's documented **manual JWT verification**: fetch the instance's JWKS,
  check the RS256 signature, and validate the claims. That document names three
  claims to validate — `exp`, `nbf` and `azp` — and this namespace validates
  those three plus two of its own (see `verify` below). Verified against
  Clerk's \"Manual JWT verification\" guide, 2026-08-27.

  Four rules shape it.

  1. **Nothing here is trusted until the signature is.** A token is attacker-
     controlled bytes until buddy says otherwise; the only thing read before
     verification is the header's `kid`, and it is used solely to look a key up.
     A `kid` that resolves to nothing is a refusal, never a fallback to some
     other key.
  2. **The crypto is not ours.** buddy-sign performs the RS256 check and refuses
     both classic forgeries — `alg: none` and an HS256 token offered to an RS256
     verifier. What buddy does *not* do is check `azp`, so that one is here, and
     it is the one whose absence is a CSRF hole rather than a signature bug.
  3. **A check never throws.** Every fault — an unreachable JWKS, a malformed
     token, a body that is not a key set — becomes a signed-out outcome, because
     they are all states the gate has to answer anyway. `books.reader` documents
     that contract.
  4. **Every unverifiable state is closed.** There is no branch in this file
     that answers `:signed-in` without a verified signature, a matching
     authorized party, and a subject.

  The JWKS location is **derived from the publishable key** rather than
  configured beside it. The key encodes its own instance's Frontend API host
  (Clerk's own browser code decodes it the same way), so deriving removes a
  configuration pair that could disagree — and two Clerk variables that disagree
  means verifying tokens against a different instance than the one issuing them,
  which is a hole rather than a typo."
  (:require [books.reader :as reader]
            [buddy.core.keys :as keys]
            [buddy.sign.jws :as jws]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [jsonista.core :as json])
  (:import (java.io InputStream)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util Base64)
           (java.util.concurrent CompletableFuture ExecutionException TimeUnit TimeoutException)
           (java.util.function Function)))

;; ---------------------------------------------------------------------------
;; The publishable key, and the instance it names
;; ---------------------------------------------------------------------------

(def ^:private publishable-key-prefixes
  "The two prefixes a Clerk publishable key carries — a development instance and
  a live one. Anything else is not a publishable key."
  ["pk_test_" "pk_live_"])

(def ^:private host-terminator
  "Clerk appends this to the host before base64-encoding it, as a stop character
  that makes a truncated key detectable. A decoded value without it is not a
  publishable key payload, however well it base64-decoded."
  "$")

(defn- decode-base64
  "The bytes `encoded` stands for as text, or nil when it is not base64 at all."
  [^String encoded]
  (try
    (String. (.decode (Base64/getDecoder) encoded) StandardCharsets/UTF_8)
    (catch IllegalArgumentException _ nil)))

(defn frontend-api
  "The Frontend API host `publishable-key` encodes, or nil when the argument is
  not a publishable key.

  A publishable key is `pk_test_`/`pk_live_` followed by the base64 of the
  instance's Frontend API host and a `$` terminator. Verified against Clerk's
  documented decode (`atob(key.split('_')[2]).slice(0, -1)`), 2026-08-27.

  Total by contract: an operator's typo, a secret key pasted into the wrong
  variable, or a truncated value all answer nil — which the caller reads as
  'not configured', which closes the gate."
  [publishable-key]
  (when-let [prefix (and (string? publishable-key)
                         (some #(when (str/starts-with? publishable-key %) %)
                               publishable-key-prefixes))]
    (let [decoded (decode-base64 (subs publishable-key (count prefix)))]
      (when (and decoded (str/ends-with? decoded host-terminator))
        (let [host (subs decoded 0 (- (count decoded) (count host-terminator)))]
          (when (seq host) host))))))

(defn jwks-url
  "Where `publishable-key`'s instance publishes its keys, or nil when there is
  no instance to ask. Clerk's documented location is the Frontend API URL with
  `/.well-known/jwks.json` appended."
  [publishable-key]
  (some-> (frontend-api publishable-key) (->> (format "https://%s/.well-known/jwks.json"))))

;; ---------------------------------------------------------------------------
;; What the browser loads, and what a Content-Security-Policy has to admit for
;; it to run. Both derive from the same publishable key as the JWKS location.
;; ---------------------------------------------------------------------------

(def ^:private clerk-js-major
  "The major version of ClerkJS a page loads. Clerk serves the script from the
  instance's own Frontend API host and patches it there, which is the reason
  ADR-0005 accepts a third-party script rather than vendoring one the way
  ADR-0004 vendors htmx: a pinned copy of a security-critical script is a stale
  copy the day after it is pinned."
  "6")

(def ^:private clerk-ui-major
  "The major version of Clerk's UI bundle. Since clerk-js 6, the components are
  NOT in clerk.browser.js: `mountSignIn` throws \"Clerk was not loaded with Ui
  components\" unless this second script is loaded and its constructor handed to
  `Clerk.load`. Same Frontend API host, so it admits no new origin."
  "1")

(defn script-url
  "Where the browser loads ClerkJS from, or nil when no instance is configured.
  The instance's own Frontend API host serves it — not a public CDN."
  [publishable-key]
  (when-let [host (frontend-api publishable-key)]
    (format "https://%s/npm/@clerk/clerk-js@%s/dist/clerk.browser.js" host clerk-js-major)))

(defn ui-script-url
  "Where the browser loads Clerk's UI components from, or nil when no instance
  is configured. Separate from `script-url` since clerk-js 6 split them apart."
  [publishable-key]
  (when-let [host (frontend-api publishable-key)]
    (format "https://%s/npm/@clerk/ui@%s/dist/ui.browser.js" host clerk-ui-major)))

(def ^:private bot-protection-origins
  "Clerk runs its bot and fraud checks from these. A sign-in that cannot reach
  them fails a challenge it never manages to show, so a CSP that omits them
  breaks sign-in rather than hardening it. Per Clerk's own CSP guidance,
  verified 2026-08-27."
  ["https://challenges.cloudflare.com" "https://*.protect.clerk.com"])

(defn csp-sources
  "The origins ClerkJS needs, per CSP directive — and **empty everywhere when no
  instance is configured**, so an unconfigured deployment sends the strict
  same-origin policy rather than a policy full of a vendor's hostnames it never
  contacts.

  `books.handler` composes these into the header. Which hosts Clerk needs is
  Clerk knowledge, so it lives here rather than in a header string over there."
  [publishable-key]
  (let [instance (some->> (frontend-api publishable-key) (format "https://%s"))]
    {:script-src (if instance (into [instance] bot-protection-origins) [])
     ;; The `:*` is Clerk's own: its protect hosts answer on ports other than
     ;; 443, and a bare host in `connect-src` matches only 443.
     ;; Telemetry is on by default and ClerkJS really does POST to it on load;
     ;; a policy that omits it reports a violation on every page view.
     :connect-src (if instance
                    [instance "https://*.protect.clerk.com:*"
                     "https://clerk-telemetry.com" "https://*.clerk-telemetry.com"]
                    [])
     :img-src (if instance ["https://img.clerk.com"] [])
     :frame-src (if instance bot-protection-origins [])}))

;; ---------------------------------------------------------------------------
;; Fetching the key set. Bounded on both axes, like every other outbound call
;; this repo makes.
;; ---------------------------------------------------------------------------

(def timeouts
  "Every wait is bounded, so an unresponsive Clerk fails a sign-in rather than
  pinning a request thread. The same three bounds `books.google-books` takes,
  for the same reason: `HttpRequest.timeout` stops at the response headers, so
  only `:total` — imposed here, over the whole exchange — bounds a body that
  arrives and then stops."
  {:connect (Duration/ofSeconds 5)
   :request (Duration/ofSeconds 10)
   :total (Duration/ofSeconds 15)})

(def max-body-bytes
  "The ceiling on a JWKS response, in bytes. A key set is a handful of RSA
  moduli — a few kilobytes at most — so this is a ceiling with two orders of
  magnitude of room, not a budget. It exists because a response is buffered
  before it is parsed, and an endpoint that answers without end would otherwise
  grow the heap on a request thread."
  (* 256 1024))

(def ^:private shared-client
  "ONE `HttpClient` for the life of the process — a client owns a connection
  pool and a selector thread and has no `close` on this JDK, so one per fetch is
  a leak with a thread attached. A `delay`, so that requiring this namespace
  does not start a thread an unconfigured deployment will never use."
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (:connect timeouts))
             ;; Stated rather than inherited: a redirect would send this request
             ;; to an origin the publishable key does not name, and the answer
             ;; would then be the key set we verify every session against.
             (.followRedirects HttpClient$Redirect/NEVER)
             (.build))))

(defn- read-bounded
  "The response body as text, refusing more than `max-body-bytes`.

  `readNBytes` is asked for one byte more than the ceiling: a full buffer means
  the body had not ended, which is the overrun. Reading inside the async chain
  is what keeps the whole exchange — headers and body — under one deadline."
  [^HttpResponse response]
  (with-open [^InputStream body (.body response)]
    (let [bytes (.readNBytes body (inc max-body-bytes))]
      (when (> (alength bytes) max-body-bytes)
        (throw (ex-info "the key set response exceeds the ceiling" {:limit max-body-bytes})))
      {:status (.statusCode response)
       :body (String. bytes StandardCharsets/UTF_8)})))

(defn- await-within
  "The value of `pending`, waited for no longer than `budget`. The exchange is
  cancelled on the way out so a stalled connection is released rather than left
  reading into a buffer nobody will read."
  [^CompletableFuture pending ^Duration budget]
  (try
    (.get pending (.toMillis budget) TimeUnit/MILLISECONDS)
    (catch TimeoutException _
      (.cancel pending true)
      (throw (ex-info "the key set did not arrive within the budget"
                      {:budget-ms (.toMillis budget)})))
    (catch InterruptedException e
      (.cancel pending true)
      (.interrupt (Thread/currentThread))
      (throw (ex-info "interrupted while fetching the key set" {} e)))
    (catch ExecutionException e
      (throw (or (ex-cause e) e)))))

(defn http-fetch
  "GET `url` and parse it as JSON. The default `:fetch` — public so the one
  piece of this namespace the injected-fetch tests cannot reach has a seam of
  its own.

  Throws on anything that is not a 200 carrying JSON; every caller is inside the
  catch that turns a fault into a signed-out outcome."
  [url]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (:request timeouts))
                    (.header "Accept" "application/json")
                    (.GET)
                    (.build))
        pending (.sendAsync ^HttpClient @shared-client request
                            (HttpResponse$BodyHandlers/ofInputStream))
        bounded (.thenApply pending (reify Function (apply [_ response] (read-bounded response))))
        {:keys [status body]} (await-within bounded (:total timeouts))]
    (when-not (= 200 status)
      (throw (ex-info "the key set endpoint refused" {:status status})))
    (json/read-value body)))

;; ---------------------------------------------------------------------------
;; The key set, cached
;; ---------------------------------------------------------------------------

(def refetch-interval-ms
  "The floor between two fetches of the key set.

  A gated route is reachable by anyone, so an anonymous caller chooses the `kid`
  in the header it sends. Refetching whenever a `kid` misses would make that an
  amplifier — one forged header, one outbound HTTPS request to Clerk — so a miss
  refetches at most this often. Clerk rotates keys rarely and publishes the new
  one before signing with it, which is what makes a bounded delay the right
  trade: a rotation costs at most one interval of refusals, and only for
  sessions minted inside it.

  Public so a test can drive the boundary rather than sleep through it."
  60000)

(defn- public-key
  "One JWKS entry as a `PublicKey`, or nil when it is not one this app can use.

  buddy's `jwk->public-key` dispatches on a KEYWORD `:kty`, and a parsed JWKS
  has string keys throughout — so the conversion is where a plain JSON map
  becomes a key, and where anything that is not an RSA signing key is dropped
  rather than guessed at."
  [entry]
  (when (map? entry)
    (try
      (let [{:strs [kty n e]} entry]
        (when (and (= "RSA" kty) (string? n) (string? e))
          (keys/jwk->public-key {:kty kty :n n :e e})))
      (catch Exception _ nil))))

(defn- key-set
  "The `kid -> PublicKey` map a fetched JWKS stands for. Entries without a usable
  `kid` and a usable key are dropped, so a key set with one broken entry still
  serves the rest."
  [jwks]
  (into {}
        (keep (fn [entry]
                (let [kid (get entry "kid")
                      key (public-key entry)]
                  (when (and (string? kid) key) [kid key]))))
        (when (map? jwks) (let [ks (get jwks "keys")] (when (sequential? ks) ks)))))

(defn- resolver
  "A function of a `kid`, answering the `PublicKey` the instance publishes under
  it, or nil.

  The cache is one atom holding the current key set and the time it was last
  fetched. A `kid` that is not in it refetches — that is how a rotation is
  picked up without a restart — but no more often than `refetch-interval-ms`."
  [{:keys [url fetch clock]}]
  (let [cache (atom {:keys {} :fetched-at nil})]
    (letfn [(refresh! []
              (let [fetched (key-set (fetch url))]
                (swap! cache assoc :keys fetched :fetched-at (clock))
                fetched))
            (stale? [fetched-at]
              (or (nil? fetched-at) (>= (- (clock) fetched-at) refetch-interval-ms)))]
      (fn [kid]
        (let [{:keys [keys fetched-at]} @cache]
          (cond
            (contains? keys kid) (get keys kid)
            (stale? fetched-at) (get (refresh!) kid)
            ;; Known-stale-but-too-recent: refuse now rather than fetch on
            ;; demand for a caller who may have invented this `kid`.
            :else nil))))))

;; ---------------------------------------------------------------------------
;; Verification
;; ---------------------------------------------------------------------------

(def ^:private signed-out
  {:absent {:outcome :signed-out :reason :absent}
   :expired {:outcome :signed-out :reason :expired}
   :invalid {:outcome :signed-out :reason :invalid}})

(defn- refusal
  "The outcome a buddy validation failure stands for. `exp` is named separately
  because an expired token is the ordinary case — a Clerk session token lives
  sixty seconds — and it is the one refusal that means 'you were signed in a
  moment ago', which is a different thing to tell a Reader."
  [cause]
  (if (= :exp cause) (:expired signed-out) (:invalid signed-out)))

(defn- claimed-key-id
  "The `kid` from the token's header. This is the ONE thing read out of an
  unverified token, and it is used only to look a key up — never as a claim
  about the token's contents."
  [token]
  (try
    (:kid (jws/decode-header token))
    (catch Exception _ nil)))

(defn- reader-of
  "The Reader a verified claim set identifies, or nil when it identifies nobody.

  Three claims are required beyond the signature, and each is required for its
  own reason:

  * `sub` — the Reader IS this claim. A token without one names no Reader.
  * `exp` — buddy accepts a signed token that simply omits it (verified against
    buddy-sign 3.6.1), and a credential with no deadline is the last thing to
    accept on trust. Clerk always sends one.
  * `azp` — the authorized party, checked against this app's own origin. Clerk's
    guide says a token with no `azp` may skip the check; this refuses instead,
    because 'no authorized party' and 'the right authorized party' are the same
    answer only for an attacker."
  [{:keys [sub exp azp]} authorized-party]
  (when (and (integer? exp)
             (string? azp) (= azp authorized-party)
             (string? sub) (seq (str/trim sub)))
    {:id sub}))

(defn- verify
  "One token against one key resolver. Returns a Session check outcome."
  [token {:keys [resolve-key authorized-party]}]
  (if-let [key (some-> (claimed-key-id token) (resolve-key))]
    (try
      (let [claims (jwt/unsign token key {:alg :rs256})]
        (if-let [reader (reader-of claims authorized-party)]
          {:outcome :signed-in :reader reader}
          (:invalid signed-out)))
      (catch clojure.lang.ExceptionInfo e
        (refusal (:cause (ex-data e))))
      (catch Exception _ (:invalid signed-out)))
    (:invalid signed-out)))

(defn session-check
  "A Session check (see `books.reader`) over a Clerk instance's published keys:
  a function of the session token.

  * `:publishable-key` — `CLERK_PUBLISHABLE_KEY`. Not a secret; it is rendered
    into every page. It is also where the JWKS location comes from.
  * `:authorized-party` — `CLERK_AUTHORIZED_PARTY`: this app's own public
    origin, which every token's `azp` must equal.
  * `:jwks-url` — an override for the derived location, for a deployment that
    reaches Clerk through a proxy. Optional, and leaving it unset is the safer
    choice: derived, it cannot name a different instance than the key does.
  * `:fetch` — the HTTP call, a function of the URL answering parsed JSON.
    Injectable, so the whole verification path can be tested against a key set
    this process minted.
  * `:clock` — current time in milliseconds; injectable so a test can drive the
    refetch interval rather than sleep through it.

  **Missing configuration is not a boot failure and not an open gate.** Without
  a usable publishable key, or without an authorized party to check `azp`
  against, this returns `books.reader/not-configured` — which refuses every
  request. There is deliberately no option that turns the gate off."
  [{:keys [publishable-key authorized-party fetch clock]
    :or {fetch http-fetch clock #(System/currentTimeMillis)}
    url :jwks-url}]
  (let [url (or url (jwks-url publishable-key))
        authorized-party (some-> authorized-party str/trim not-empty)]
    (if-not (and url authorized-party)
      ;; The port owns this value; rebuilding it here would give the contract
      ;; two owners that could drift apart.
      reader/not-configured
      (let [resolve-key (resolver {:url url :fetch fetch :clock clock})]
        (fn [token]
          (if-not (seq (some-> token str/trim))
            (:absent signed-out)
            (try
              (verify token {:resolve-key resolve-key :authorized-party authorized-party})
              (catch Exception _ (:invalid signed-out)))))))))
