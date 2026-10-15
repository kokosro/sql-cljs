(ns sql-cljs.dev
  "Node-script entry point for interactive development."
  (:require [sql-cljs.core :as sql]))

(defn main [& _]
  (println "sql-cljs dev runtime loaded. Attach with: npx shadow-cljs cljs-repl dev"
           "api-version:" sql/api-version)
  (js/setInterval #(println "…dev heartbeat") 60000))
