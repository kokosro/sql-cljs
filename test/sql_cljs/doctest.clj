(ns sql-cljs.doctest
  "Macros of the README examples test and of the API index test."
  (:require [cljs.analyzer.api :as ana]))

(defmacro claims
  "A vector of [source value] pairs, one per form: the form as it is
   written and what it evaluates to."
  [& forms]
  (mapv (fn [form] [(pr-str form) form]) forms))

(defmacro public-vars
  "The names of the public vars of the ClojureScript namespace `ns-sym`
   (a quoted symbol), as a sorted vector of strings, read from the
   compiler's analysis of that namespace at compile time. The namespace
   must be required by the namespace that uses this macro, so that it has
   been analyzed first."
  [ns-sym]
  (let [ns-sym (if (and (seq? ns-sym) (= 'quote (first ns-sym))) (second ns-sym) ns-sym)
        publics (ana/ns-publics ns-sym)]
    (when (empty? publics)
      (throw (ex-info (str "public-vars: no analysis for " ns-sym) {:ns ns-sym})))
    (vec (sort (map str (keys publics))))))

(defmacro file-text
  "The text of the file at `path`, relative to the project root (where
   shadow-cljs runs), read at compile time: the test namespaces stay pure
   ClojureScript, with no file system access at run time. A namespace
   using it carries ^:dev/always so that it is compiled on every build
   and never served from the cache with stale text."
  [path]
  (slurp path))
