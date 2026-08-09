(ns books.handler
  (:require [books.views :as views]
            [reitit.ring :as ring]
            [ring.middleware.not-modified :refer [wrap-not-modified]]))

(defn- health [_request]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "ok"})

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

(def app
  (ring/ring-handler
   (ring/router
    [["/" {:get landing :head landing}]
     ["/health" {:get health :head health}]])
   (ring/routes
    stylesheets
    (ring/create-default-handler))))
