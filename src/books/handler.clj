(ns books.handler
  (:require [books.db :as db]
            [books.views :as views]
            [jsonista.core :as json]
            [reitit.ring :as ring]
            [ring.middleware.not-modified :refer [wrap-not-modified]]))

(def ^:private health-states
  {:ok          {:http-status 200 :body {:status "ok" :db "ok"}}
   :unreachable {:http-status 503 :body {:status "degraded" :db "unreachable"}}})

(defn- not-configured-state
  "No DATABASE_URL is a fault by default: a deploy that silently lost its
  database variable must fail its health check rather than report itself
  healthy. DB_OPTIONAL is the explicit opt-out for running database-less."
  [db-optional?]
  (if db-optional?
    {:http-status 200 :body {:status "ok" :db "not-configured"}}
    {:http-status 503 :body {:status "degraded" :db "not-configured"}}))

(defn- health [check db-optional?]
  (fn [_request]
    (let [state (check)
          {:keys [http-status body]} (or (health-states state)
                                         (not-configured-state db-optional?))]
      {:status http-status
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string body)})))

(defn- landing [_request]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (views/landing-page)})

(def ^:private stylesheet-cache-control
  ;; The stylesheet URL is unversioned (/css/app.css), so a cached copy can
  ;; outlive the deploy that changed it. Revalidate every time — correctness
  ;; over bytes — until a cache-busting scheme exists (ADR-0004).
  "public, max-age=0, must-revalidate")

(defn- wrap-request-methods
  "Only requests whose method is in `methods` reach `handler`; the rest get a
  nil response, so `ring/routes` falls through to the next handler."
  [methods handler]
  (fn
    ([request]
     (when (methods (:request-method request)) (handler request)))
    ([request respond raise]
     (if (methods (:request-method request))
       (handler request respond raise)
       (respond nil)))))

(defn- wrap-cache-control
  "Stamps `value` as Cache-Control on any response `handler` actually produces."
  [value handler]
  (letfn [(stamp [response]
            (some-> response (assoc-in [:headers "Cache-Control"] value)))]
    (fn
      ([request] (stamp (handler request)))
      ([request respond raise] (handler request (comp respond stamp) raise)))))

(def ^:private stylesheets
  "The whole static surface: the Tailwind-built stylesheet under /css/.
  Rooted at public/css rather than public so nothing else on the classpath —
  a keeper file, a dependency jar's own public/ assets — is web-reachable."
  (->> (ring/create-resource-handler {:path "/css/" :root "public/css"})
       (wrap-not-modified)
       (wrap-cache-control stylesheet-cache-control)
       (wrap-request-methods #{:get :head})))

(defn make-app
  "The Ring handler with its database dependency injected. `datasource` is nil
  when no DATABASE_URL is configured.

  Options:
  * `:db-optional?` — treat an absent DATABASE_URL as healthy (default false);
  * `:probe` — the connectivity probe, for tests.

  The connectivity result is cached for `db/check-ttl-ms`: /health is
  unauthenticated, so it must not open a database connection per request."
  [datasource {:keys [db-optional? probe] :or {db-optional? false}}]
  (let [check (db/checker datasource (cond-> {} probe (assoc :probe probe)))
        health-handler (health check db-optional?)]
    (ring/ring-handler
     (ring/router
      [["/" {:get landing :head landing}]
       ["/health" {:get health-handler :head health-handler}]])
     (ring/routes
      stylesheets
      (ring/create-default-handler)))))
