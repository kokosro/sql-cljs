(ns sql-cljs.examples
  "The examples of the README, runnable offline (the library is pure
   formatting, so every example is a data map in and a SQL text out):

     npx shadow-cljs compile examples && node target/examples.js

   Each README example will be a named function of this file, kept in
   lockstep with README.md by the examples test; for now the entry point
   only reports the library version."
  (:require [sql-cljs.core :as sql]))

(defn main [& _]
  (println "sql-cljs examples: api-version" sql/api-version))
