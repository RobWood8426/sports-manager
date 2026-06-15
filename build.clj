(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'sports-manager/sports-manager)
(def main-ns 'sports-manager.core)
(def class-dir "target/classes")
(def uber-file "target/sports-manager.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis :src-dirs ["src"] :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      main-ns})))
