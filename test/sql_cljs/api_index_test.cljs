(ns ^:dev/always sql-cljs.api-index-test
  "The API reference of the manual (docs/sql-cljs.org) and the public vars
   of the source agree in both directions: every public var of
   sql-cljs.core, sql-cljs.helpers and sql-cljs.util has a reference
   entry, and every reference entry names a var that exists. An entry is
   a level-four org headline `**** sql-cljs.<ns>/<name>` under the
   reference section; the public vars are read from the compiler's
   analysis at compile time (sql-cljs.doctest/public-vars), and the
   manual is read at compile time too (sql-cljs.doctest/file-text), with
   ^:dev/always keeping this namespace out of the compiler cache."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as str]
            [sql-cljs.core]
            [sql-cljs.helpers]
            [sql-cljs.util])
  (:require-macros [sql-cljs.doctest :refer [public-vars file-text]]))

(def manual
  "The text of docs/sql-cljs.org."
  (file-text "docs/sql-cljs.org"))

(def in-source
  "Namespace name -> the set of its public var names, from the source."
  {"sql-cljs.core" (set (public-vars 'sql-cljs.core))
   "sql-cljs.helpers" (set (public-vars 'sql-cljs.helpers))
   "sql-cljs.util" (set (public-vars 'sql-cljs.util))})

(defn- documented
  "Namespace name -> the set of var names the manual's reference entries
   name (`**** sql-cljs.<ns>/<name>` headlines)."
  [text]
  (reduce (fn [m [_ ns-name var-name]]
            (update m ns-name (fnil conj #{}) var-name))
          {}
          (re-seq #"(?m)^\*\*\*\* (sql-cljs\.[a-z]+)/(\S+)" text)))

(deftest the-manual-indexes-every-public-var-and-nothing-else
  (let [in-manual (documented manual)]
    (testing "the three namespaces have reference entries"
      (is (= (set (keys in-source)) (set (keys in-manual)))))
    (doseq [[ns-name source-vars] in-source
            :let [manual-vars (get in-manual ns-name #{})]]
      (testing (str ns-name ": every public var has an entry")
        (is (empty? (set/difference source-vars manual-vars))
            (str ns-name " public vars without a reference entry: "
                 (pr-str (sort (set/difference source-vars manual-vars))))))
      (testing (str ns-name ": every entry names an existing public var")
        (is (empty? (set/difference manual-vars source-vars))
            (str ns-name " reference entries without a public var: "
                 (pr-str (sort (set/difference manual-vars source-vars)))))))
    (testing "the source has the public surface the README documents"
      (is (contains? (in-source "sql-cljs.core") "format"))
      (is (contains? (in-source "sql-cljs.core") "raw"))
      (is (contains? (in-source "sql-cljs.helpers") "on-constraint"))
      (is (contains? (in-source "sql-cljs.util") "format-error"))
      (is (not (contains? (in-source "sql-cljs.core") "statement")) "private vars are not public"))
    (testing "the manual points at the run commands"
      (is (str/includes? manual "npx shadow-cljs compile examples && node target/examples.js"))
      (is (str/includes? manual "./run-tests.sh")))))
