(ns books.security-headers-test
  "The security response headers ADR-0004 clause 7 deferred to this ticket.

  Asserted per directive rather than as one exact policy string: a test that
  compares the whole header teaches nothing when it fails and has to be rewritten
  whenever a directive is added, which is how such a test ends up being updated
  to whatever the code now emits."
  (:require [books.catalog :as catalog]
            [books.clerk :as clerk]
            [books.handler :as handler]
            [books.stub-book-search :as stub]
            [books.test-jwt :as test-jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- app
  ([] (app test-jwt/publishable-key))
  ([publishable-key]
   (handler/make-app nil {:db-optional? true
                          :book-search (stub/found [stub/brave-and-true])
                          :publishable-key publishable-key})))

(defn- headers-for
  ([uri] (headers-for uri (app)))
  ([uri app] (:headers (app {:request-method :get :uri uri}))))

(defn- policy
  ([] (policy (app)))
  ([app] (get (headers-for "/" app) "Content-Security-Policy")))

(defn- directive
  "The sources named by `name` in `policy`, as a set."
  [policy name]
  (some->> (str/split policy #";\s*")
           (some #(when (str/starts-with? % (str name " ")) %))
           (#(str/split % #"\s+"))
           (drop 1)
           (set)))

;; ---------------------------------------------------------------------------
;; Every response, not merely every page.
;; ---------------------------------------------------------------------------

(deftest the-headers-are-on-every-response
  ;; A policy with a hole in it is not a policy, and the response nobody
  ;; remembers to cover — the 404, the stylesheet — is the one worth checking.
  (testing "pages, static assets, refusals and the default handler alike"
    (doseq [uri ["/" "/health" "/sign-in" "/search" "/css/fixture.css" "/nope"]]
      (let [headers (headers-for uri)]
        (is (seq (get headers "Content-Security-Policy")) (str uri " has no CSP"))
        (is (= "nosniff" (get headers "X-Content-Type-Options")) uri)
        (is (= "strict-origin-when-cross-origin" (get headers "Referrer-Policy")) uri)))))

(deftest nothing-may-frame-this-app
  (testing "frame-ancestors is 'none' — there is no embeddable surface here"
    (is (= #{"'none'"} (directive (policy) "frame-ancestors")))))

;; ---------------------------------------------------------------------------
;; The policy has to admit what the app actually loads. A CSP that breaks the
;; page it protects gets switched off by the next person to debug it.
;; ---------------------------------------------------------------------------

(deftest the-policy-is-same-origin-by-default
  (testing "everything not named below falls back to this origin"
    (is (= #{"'self'"} (directive (policy) "default-src")))
    (is (= #{"'self'"} (directive (policy) "form-action")))))

(deftest the-policy-admits-the-catalogs-cover-images
  ;; The one self-inflicted break worth a test of its own: `img-src 'self'`
  ;; alone renders every Volume with a broken cover, and it looks like a
  ;; catalog fault rather than a header fault.
  (testing "a Volume's thumbnail origin is named"
    (let [img-src (directive (policy) "img-src")]
      (is (contains? img-src "'self'"))
      (doseq [origin catalog/cover-origins]
        (is (contains? img-src origin) (str origin " must be allowed to load covers"))))))

(deftest the-policy-admits-the-vendored-script-and-stylesheet
  (testing "htmx and the Tailwind stylesheet are ours, so 'self' covers them"
    (is (contains? (directive (policy) "script-src") "'self'"))
    (is (contains? (directive (policy) "style-src") "'self'"))))

(deftest the-policy-admits-clerk
  ;; Every origin here comes from Clerk's own documented CSP guidance rather
  ;; than from guesswork; `books.clerk/csp-sources` is where they are named.
  (let [instance (str "https://" test-jwt/frontend-api)
        current (policy)]
    (testing "the instance's own Frontend API host serves ClerkJS and answers its calls"
      (is (contains? (directive current "script-src") instance))
      (is (contains? (directive current "connect-src") instance)))
    (testing "and its bot-protection origins, without which sign-in fails a challenge it cannot show"
      (is (contains? (directive current "script-src") "https://challenges.cloudflare.com"))
      (is (contains? (directive current "frame-src") "https://challenges.cloudflare.com"))
      ;; Clerk's own note: these answer on ports other than 443, and a bare host
      ;; in connect-src matches only 443.
      (is (contains? (directive current "connect-src") "https://*.protect.clerk.com:*")))
    (testing "Clerk styles its components at runtime, which needs inline styles"
      (is (contains? (directive current "style-src") "'unsafe-inline'")))
    (testing "and mounts a worker from a blob URL"
      (is (contains? (directive current "worker-src") "blob:")))))

(deftest the-policy-does-not-allow-eval
  ;; Clerk documents 'unsafe-eval' as a Next.js DEVELOPMENT need. This is
  ;; neither, so it is not sent — the one directive from Clerk's example policy
  ;; deliberately left out.
  (testing "'unsafe-eval' appears nowhere"
    (is (not (str/includes? (policy) "unsafe-eval")))))

;; ---------------------------------------------------------------------------
;; An unconfigured deployment names no vendor at all.
;; ---------------------------------------------------------------------------

(deftest without-clerk-the-policy-mentions-no-third-party
  (let [bare (policy (app nil))]
    (testing "no instance means no Clerk origins anywhere in the policy"
      (is (not (str/includes? bare "clerk")))
      (is (not (str/includes? bare "cloudflare"))))
    (testing "…and the app's own needs are still admitted"
      (is (= #{"'self'"} (directive bare "default-src")))
      (is (contains? (directive bare "img-src") (first catalog/cover-origins))))
    (testing "an unusable publishable key is no key, not a host named half-way"
      (let [garbage (policy (app "pk_test_garbage"))]
        (is (not (str/includes? garbage "clerk")))
        (is (not (str/includes? garbage "cloudflare")))
        (is (= (directive bare "script-src") (directive garbage "script-src"))))))
  (testing "a directive with nothing left to name is omitted rather than sent empty"
    (is (not (re-find #"(?m)frame-src\s*(;|$)" (policy (app nil))))))

  (testing "and the page then loads no third-party script at all"
    (let [body (:body ((app nil) {:request-method :get :uri "/"}))]
      (is (not (str/includes? body "clerk"))))))

;; ---------------------------------------------------------------------------
;; What the page actually asks the browser to load.
;; ---------------------------------------------------------------------------

(deftest the-page-loads-clerkjs-from-the-instance-not-a-public-cdn
  (let [body (:body ((app) {:request-method :get :uri "/sign-in"}))]
    (testing "the script comes off the configured instance's own host"
      (is (str/includes? body (clerk/script-url test-jwt/publishable-key)))
      (is (str/includes? body test-jwt/frontend-api)))
    (testing "the publishable key travels as an attribute, so no inline script is needed"
      (is (str/includes? body (str "data-clerk-publishable-key=\"" test-jwt/publishable-key "\""))))
    (testing "and this repo's own browser code is served from this origin"
      (is (str/includes? body "/app/session.js")))
    (testing "no page carries an inline <script> block"
      ;; Every script on the page is a `src`, which is what would let
      ;; `script-src 'unsafe-inline'` be dropped the day Clerk stops needing it.
      (is (nil? (re-find #"<script(?![^>]*\ssrc=)" body))))))

(deftest the-script-url-is-nothing-without-an-instance
  (testing "an absent or unusable key yields no script URL to render"
    (doseq [not-a-key [nil "" "pk_test_garbage" "sk_test_secret"]]
      (is (nil? (clerk/script-url not-a-key))))))

;; ---------------------------------------------------------------------------
;; The third static root, for the code this repo wrote.
;; ---------------------------------------------------------------------------

(deftest this-repos-own-script-is-served
  (let [response ((app) {:request-method :get :uri "/app/session.js"})]
    (testing "GET /app/session.js answers 200 with a JavaScript content type"
      (is (= 200 (:status response)))
      (is (re-find #"javascript" (str (get-in response [:headers "Content-Type"])))))
    (testing "its URL carries no version, so it revalidates rather than caching forever"
      (is (= "public, max-age=0, must-revalidate"
             (get-in response [:headers "Cache-Control"]))))))

(deftest the-app-root-is-scoped-like-the-other-two
  (testing "a third root publishes exactly its own tree and nothing beside it"
    (doseq [escape ["/app/../not-served.txt"
                    "/app/../migrations/20260809120000-schema-baseline.up.sql"]]
      (is (= 404 (:status ((app) {:request-method :get :uri escape}))) escape)))
  (testing "and only GET and HEAD reach it"
    (doseq [method [:post :put :delete :patch]]
      (is (not= 200 (:status ((app) {:request-method method :uri "/app/session.js"})))))))
