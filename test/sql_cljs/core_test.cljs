(ns sql-cljs.core-test
  (:require [cljs.test :refer [deftest is]]
            [sql-cljs.core :as core]))

(deftest api-version-is-exact
  (is (= "0.1.0" core/api-version)))
