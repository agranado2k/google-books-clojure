(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
;; Copied into the Dockerfile runtime stage by path — keep the two in step.
(def uber-file "target/app.jar")

(defn uber [_]
  (b/delete {:path "target"})
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/compile-clj {:basis basis :ns-compile '[books.server] :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'books.server})))
