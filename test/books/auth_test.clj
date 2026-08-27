(ns books.auth-test
  "The gate, at the handler seam: a Ring request in, a response map out.

  These tests wire the REAL verifier — `books.clerk/session-check` — and give it
  only its JWKS fetch, which answers the key set for the throwaway pair in
  `books.test-jwt`. So a request carrying a token here runs the same signature
  check, the same `azp` comparison and the same expiry arithmetic a request
  carrying a Clerk token runs in production. Nothing is stubbed between the
  request map and the refusal."
  (:require [books.clerk :as clerk]
            [books.handler :as handler]
            [books.stub-book-search :as stub]
            [books.test-jwt :as test-jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private sign-in-path "/sign-in")

(defn- app
  "A database-less app whose gate is the real Clerk verifier, pointed at the
  test key set. `opts` overrides the Clerk configuration for the tests that are
  about configuration."
  ([] (app {}))
  ([opts]
   (handler/make-app nil
                     {:db-optional? true
                      :book-search (stub/found [stub/brave-and-true])
                      :publishable-key test-jwt/publishable-key
                      :session-check (clerk/session-check
                                      (merge {:publishable-key test-jwt/publishable-key
                                              :authorized-party test-jwt/authorized-party
                                              :fetch (fn [_url] (test-jwt/jwks))}
                                             opts))})))

(defn- GET
  ([uri] (GET uri {}))
  ([uri {:keys [token cookie htmx? app] :as opts}]
   ((or app (books.auth-test/app))
    (cond-> {:request-method :get :uri uri}
      (:query-string opts) (assoc :query-string (:query-string opts))
      token (assoc-in [:headers "authorization"] (str "Bearer " token))
      cookie (assoc-in [:headers "cookie"] (str "__session=" cookie))
      htmx? (assoc-in [:headers "hx-request"] "true")))))

(defn- header [response name] (get-in response [:headers name]))

;; ---------------------------------------------------------------------------
;; What stays public. The gate is a named set of routes, not a default.
;; ---------------------------------------------------------------------------

(deftest the-landing-page-and-health-stay-public
  (testing "a signed-out visitor still gets the landing page and the health probe"
    (is (= 200 (:status (GET "/"))))
    (is (= 200 (:status (GET "/health"))))))

(deftest the-sign-in-page-is-public
  ;; It has to be: gating it would redirect a signed-out reader to a page that
  ;; redirects them to itself.
  (testing "the sign-in page is reachable by exactly the people who need it"
    (let [response (GET sign-in-path)]
      (is (= 200 (:status response)))
      (is (str/starts-with? (header response "Content-Type") "text/html")))))

(deftest the-sign-in-form-is-centred-on-its-own-column
  ;; Regression: the mount slot carried `justify-center` but not `w-full`, so
  ;; the card Clerk mounts into it sized itself and settled against the left
  ;; edge of a wide content column. Both classes are load-bearing — a flex
  ;; parent narrower than its row centres nothing.
  (testing "the slot ClerkJS mounts into is a full-width centring row"
    (let [body (:body (GET sign-in-path))
          slot (re-find #"<div[^>]*id=\"sign-in\"[^>]*>" body)]
      (is (some? slot) "the sign-in page still renders a mount slot")
      (is (str/includes? slot "justify-center"))
      (is (str/includes? slot "w-full")))))

(deftest static-assets-stay-public
  (testing "the stylesheet and the vendored script are not behind the gate"
    ;; They are what the sign-in page itself is rendered and driven by, so
    ;; gating them would break the page that ungates everything else.
    (is (= 200 (:status (GET "/css/fixture.css"))))))

;; ---------------------------------------------------------------------------
;; What is gated.
;; ---------------------------------------------------------------------------

(deftest a-signed-out-document-request-is-redirected-to-sign-in
  (testing "GET /search with no credential at all"
    (let [response (GET "/search" {:query-string "title=clojure"})]
      (is (= 302 (:status response)))
      (is (str/starts-with? (header response "Location") sign-in-path))
      (testing "and the response says nothing about the catalog"
        (is (not (str/includes? (str (:body response)) "Brave")))))))

(deftest a-signed-out-request-is-never-cached
  ;; One URL, two answers, chosen by a credential: a shared cache that stored
  ;; the redirect would sign everybody out, and one that stored the page would
  ;; serve one Reader's results to the next visitor.
  (testing "the refusal carries a private, no-store policy"
    (let [response (GET "/search")]
      (is (= "no-store" (header response "Cache-Control")))))
  (testing "and the answer varies on the credential headers, both of them"
    (let [vary (header (GET "/search" {:token (test-jwt/token)}) "Vary")]
      (is (str/includes? vary "Authorization"))
      (is (str/includes? vary "Cookie")))))

(deftest a-valid-bearer-token-reaches-the-gated-page
  (testing "the token htmx attaches is what signs the request in"
    (let [response (GET "/search" {:query-string "title=clojure"
                                   :token (test-jwt/token)})]
      (is (= 200 (:status response)))
      (is (str/includes? (str (:body response)) "Brave")))))

(deftest a-valid-session-cookie-reaches-the-gated-page
  (testing "a full-page request carries the __session cookie instead"
    (let [response (GET "/search" {:query-string "title=clojure"
                                   :cookie (test-jwt/token)})]
      (is (= 200 (:status response)))
      (is (str/includes? (str (:body response)) "Brave")))))

(deftest an-expired-token-is-refused
  (testing "a document request with an expired token is sent to sign in again"
    (let [response (GET "/search" {:cookie (test-jwt/expired-token)})]
      (is (= 302 (:status response)))
      (is (str/starts-with? (header response "Location") sign-in-path))))
  (testing "and so is a bearer one"
    (is (= 302 (:status (GET "/search" {:token (test-jwt/expired-token)}))))))

(deftest a-token-for-another-authorized-party-is-refused
  ;; The CSRF case: a real Clerk signature, minted for a different origin.
  (testing "a wrong azp does not reach the page, by either transport"
    (doseq [carrier [:token :cookie]]
      (let [response (GET "/search" {carrier (test-jwt/token {:azp "https://attacker.example"})})]
        (is (= 302 (:status response)) (str "carried as " (name carrier)))
        (is (not (str/includes? (str (:body response)) "Brave")))))))

(deftest a-forged-token-is-refused
  (testing "the classic forgeries do not reach the page either"
    (doseq [forgery [(test-jwt/alg-none-token)
                     (test-jwt/hs256-token)
                     (test-jwt/token-signed-by-a-stranger)
                     "not-a-jwt-at-all"]]
      (is (= 302 (:status (GET "/search" {:token forgery})))))))

(deftest a-bearer-token-beats-the-cookie
  ;; htmx sends a token it just minted; the cookie may be up to a minute older.
  ;; The fresher credential is the one to read, and the test pins which is which.
  (testing "when both arrive, the Authorization header is what is checked"
    (is (= 200 (:status (GET "/search" {:token (test-jwt/token)
                                        :cookie (test-jwt/expired-token)}))))
    (is (= 302 (:status (GET "/search" {:token (test-jwt/expired-token)
                                        :cookie (test-jwt/token)}))))))

;; ---------------------------------------------------------------------------
;; htmx asks differently, so it is refused differently. A 302 answered to an
;; XHR is followed by the browser, and htmx would swap a whole sign-in page
;; into the results region.
;; ---------------------------------------------------------------------------

(deftest a-signed-out-htmx-request-is-told-to-navigate
  (testing "the fragment request gets a refusal htmx acts on, not a page to swap"
    (let [response (GET "/search" {:htmx? true :query-string "title=clojure"})]
      (is (= 401 (:status response)))
      (is (str/starts-with? (header response "HX-Redirect") sign-in-path))
      (is (not (str/includes? (str (:body response)) "Brave"))))))

(deftest a-signed-in-htmx-request-still-gets-its-fragment
  (testing "the gate does not change what a signed-in fragment request answers"
    (let [response (GET "/search" {:htmx? true :query-string "title=clojure"
                                   :token (test-jwt/token)})]
      (is (= 200 (:status response)))
      (is (not (str/includes? (str (:body response)) "<html")) "a fragment, not a page")
      (is (str/includes? (str (:body response)) "Brave")))))

;; ---------------------------------------------------------------------------
;; Coming back to where you were — without becoming an open redirect.
;; ---------------------------------------------------------------------------

(defn- redirect-target
  "The `redirect_url` the refusal asks the sign-in page to return to."
  [response]
  (second (re-find #"redirect_url=([^&]*)" (str (header response "Location")))))

(deftest the-redirect-remembers-the-gated-page
  (testing "signing in lands back on what was asked for, query string included"
    (is (= "%2Fsearch%3Ftitle%3Dclojure"
           (redirect-target (GET "/search" {:query-string "title=clojure"}))))))

(deftest the-redirect-is-never-somebody-elses-origin
  ;; An attacker who can choose where sign-in returns to has a phishing page
  ;; hosted behind this app's own sign-in flow.
  (testing "only a path on this origin is ever echoed back"
    (doseq [hostile ["//evil.example/pwn"
                     "https://evil.example/pwn"
                     "/\\evil.example"
                     "\\\\evil.example"]]
      (let [response ((app) {:request-method :get :uri "/search"
                             :headers {"x-forwarded-uri" hostile}})]
        (is (= 302 (:status response)))
        (is (= "%2Fsearch" (redirect-target response))
            (str "a crafted " (pr-str hostile) " must not steer the return"))))))

;; ---------------------------------------------------------------------------
;; No configuration is a closed gate, never an open one.
;; ---------------------------------------------------------------------------

(deftest a-deployment-with-no-clerk-configuration-keeps-the-gate-shut
  (let [unconfigured (handler/make-app nil {:db-optional? true
                                            :book-search (stub/found [stub/brave-and-true])})]
    (testing "the gated page is refused, and the catalog is never reached"
      (let [response (unconfigured {:request-method :get :uri "/search"
                                    :query-string "title=clojure"})]
        (is (= 503 (:status response)))
        (is (not (str/includes? (str (:body response)) "Brave")))
        (testing "…and it says so, rather than bouncing to a sign-in that cannot sign anyone in"
          (is (str/includes? (str (:body response)) "not configured"))
          (is (nil? (header response "Location"))))))
    (testing "even carrying a token that would otherwise verify"
      (is (= 503 (:status (unconfigured {:request-method :get :uri "/search"
                                         :headers {"authorization" (str "Bearer " (test-jwt/token))}})))))
    (testing "and the public pages are untouched by it"
      (is (= 200 (:status (unconfigured {:request-method :get :uri "/"}))))
      (is (= 200 (:status (unconfigured {:request-method :get :uri "/health"})))))))

(deftest an-authorized-party-that-was-lost-closes-the-gate-too
  ;; Half-configured is the dangerous shape: with no origin to compare `azp` to,
  ;; a verifier that carried on would accept a token minted for anywhere.
  (testing "a publishable key with no authorized party signs nobody in"
    (let [half (handler/make-app nil
                                 {:db-optional? true
                                  :book-search (stub/found [stub/brave-and-true])
                                  :session-check (clerk/session-check
                                                  {:publishable-key test-jwt/publishable-key
                                                   :fetch (fn [_url] (test-jwt/jwks))})})]
      (is (= 503 (:status (half {:request-method :get :uri "/search"
                                 :headers {"authorization" (str "Bearer " (test-jwt/token))}})))))))

;; ---------------------------------------------------------------------------
;; The gated set is a list, so ticket #9 and #10 join it with a line.
;; ---------------------------------------------------------------------------

(deftest the-gated-routes-are-named-in-one-place
  (testing "the seam the bookmarks pages use is data, not a scattered wrapper"
    (is (= {"/search" #{:get :head}
            "/bookmarks" #{:post :delete}}
           handler/gated-paths))))

(deftest every-gated-path-actually-refuses
  ;; The list above is only a seam if the router honours it. This asserts on the
  ;; list rather than on "/search", so a path added there without being wired
  ;; through the gate fails here instead of shipping open — and it probes each
  ;; path by the METHODS it answers, so a mutation route is not waved through by
  ;; a GET that 404s and looks refused.
  (testing "each named path refuses a signed-out request, by every method it answers"
    (doseq [[path methods] handler/gated-paths
            method methods]
      (is (= 302 (:status ((app) {:request-method method :uri path})))
          (str (name method) " " path " must be gated")))))
