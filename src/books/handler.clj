(ns books.handler
  (:require [books.db :as db]
            [jsonista.core :as json]
            [reitit.ring :as ring]))

(def ^:private health-states
  {:ok             {:http-status 200 :body {:status "ok" :db "ok"}}
   :not-configured {:http-status 200 :body {:status "ok" :db "not-configured"}}
   :unreachable    {:http-status 503 :body {:status "degraded" :db "unreachable"}}})

(defn- health [datasource]
  (fn [_request]
    (let [{:keys [http-status body]} (health-states (db/check datasource))]
      {:status http-status
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string body)})))

(defn make-app
  "The Ring handler with its database dependency injected. `datasource` is
  nil when no DATABASE_URL is configured — the app serves regardless."
  [datasource]
  (let [health-handler (health datasource)]
    (ring/ring-handler
     (ring/router
      [["/health" {:get health-handler :head health-handler}]])
     (ring/create-default-handler))))

(def app
  "Default app wired from the process environment (DATABASE_URL)."
  (make-app (db/datasource (System/getenv "DATABASE_URL"))))
