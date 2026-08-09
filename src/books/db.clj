(ns books.db
  "Database access: DATABASE_URL conversion.

  Railway (and Heroku-style platforms) hand the app a libpq URL:
  postgresql://user:pass@host:port/db. The PostgreSQL JDBC driver wants
  jdbc:postgresql://host:port/db?user=...&password=..., so the credentials
  move from the authority into query parameters."
  (:require [clojure.string :as str])
  (:import (java.net URI URLDecoder URLEncoder)))

(defn- percent-decode
  "Decode percent-escapes without treating '+' as a space (userinfo is
  RFC 3986 percent-encoding, where '+' is a literal plus)."
  [s]
  (URLDecoder/decode (str/replace s "+" "%2B") "UTF-8"))

(defn- query-param-encode
  "Encode a value for the JDBC URL query string. The pgjdbc driver decodes
  query values with URLDecoder semantics, so URLEncoder is the exact inverse."
  [s]
  (URLEncoder/encode s "UTF-8"))

(defn database-url->jdbc-url
  "Convert a postgresql:// (or postgres://) DATABASE_URL into JDBC form.
  Pure. Throws ex-info on any other scheme."
  [database-url]
  (let [uri (URI. database-url)
        scheme (.getScheme uri)]
    (when-not (contains? #{"postgres" "postgresql"} scheme)
      (throw (ex-info "DATABASE_URL must use the postgresql:// or postgres:// scheme"
                      {:scheme scheme})))
    (let [[raw-user raw-pass] (some-> (.getRawUserInfo uri) (str/split #":" 2))
          credentials (cond-> []
                        raw-user (conj (str "user=" (query-param-encode (percent-decode raw-user))))
                        raw-pass (conj (str "password=" (query-param-encode (percent-decode raw-pass)))))
          params (cond->> credentials
                   (.getRawQuery uri) (cons (.getRawQuery uri)))]
      (str "jdbc:postgresql://"
           (.getHost uri)
           (when (pos? (.getPort uri)) (str ":" (.getPort uri)))
           (.getRawPath uri)
           (when (seq params) (str "?" (str/join "&" params)))))))
