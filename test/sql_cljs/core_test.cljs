(ns sql-cljs.core-test
  "The public surface of sql-cljs.core: api-version, the format entry point
   and its options, and the raw escape hatch in both spellings."
  (:require [cljs.test :refer [deftest is testing]]
            [sql-cljs.core :as sql]
            [sql-cljs.util :as util]))

(defn- thrown-data [f]
  (try (f) nil (catch :default e (ex-data e))))

(deftest api-version-is-exact
  (is (= "0.1.0" sql/api-version)))

;; ---------------------------------------------------------------------------
;; format: the entry point
;; ---------------------------------------------------------------------------

(deftest format-returns-the-query-map-pg-cljs-takes
  (testing "exactly {:text :values}, the README example"
    (is (= {:text "SELECT a, b FROM foo WHERE foo.a = $1" :values ["baz"]}
           (sql/format {:select [:a :b] :from [:foo] :where [:= :foo.a "baz"]}))))
  (testing "the one-argument arity equals the two-argument arity with nil or {} options"
    (let [q {:select [:a] :from [:t] :where [:= :a 1]}]
      (is (= (sql/format q) (sql/format q nil)))
      (is (= (sql/format q) (sql/format q {})))))
  (testing "map key order never matters: the text follows the synopsis order"
    (is (= "SELECT a FROM t WHERE a = $1 ORDER BY a LIMIT $2"
           (:text (sql/format {:limit 5 :order-by [:a] :where [:= :a 1] :from [:t] :select [:a]})))))
  (testing ":values is a vector, empty when there is no value"
    (is (= [] (:values (sql/format {:select [:a] :from [:t]}))))
    (is (vector? (:values (sql/format {:select [:a] :from [:t] :where [:= :a 1]}))))))

(deftest format-validates-its-arguments
  (testing "a non-map statement"
    (let [d (thrown-data #(sql/format [:select :a]))]
      (is (= :sql-cljs/format-error (:type d)))
      (is (= :format (:op d)))
      (is (= [:select :a] (:arg d))))
    (is (= :format (:op (thrown-data #(sql/format nil)))))
    (is (= :format (:op (thrown-data #(sql/format "SELECT 1"))))))
  (testing "options must be a map or nil"
    (let [d (thrown-data #(sql/format {:select [:a]} :inline))]
      (is (= :format (:op d)))
      (is (= :inline (:arg d)))))
  (testing "an unknown option is an error before anything is rendered"
    (let [d (thrown-data #(sql/format {:select [:a]} {:pretty true}))]
      (is (= :sql-cljs/format-error (:type d)))
      (is (= :format (:op d)))
      (is (= :pretty (:arg d)))
      (is (= [:inline] (:allowed d)))))
  (testing ":inline must be a boolean"
    (let [d (thrown-data #(sql/format {:select [:a]} {:inline "yes"}))]
      (is (= :format (:op d)))
      (is (= "yes" (:arg d)))))
  (testing ":inline false is the default mode"
    (is (= {:text "SELECT a FROM t WHERE a = $1" :values [1]}
           (sql/format {:select [:a] :from [:t] :where [:= :a 1]} {:inline false})))))

;; ---------------------------------------------------------------------------
;; raw: the escape hatch
;; ---------------------------------------------------------------------------

(deftest raw-splices-verbatim
  (testing "as a function"
    (is (= {:text "SELECT NOW() FROM t" :values []}
           (sql/format {:select [(sql/raw "NOW()")] :from [:t]}))))
  (testing "the data spelling renders identically; at the top level of :select it nests like a function call"
    (is (= (sql/format {:select [(sql/raw "NOW()")] :from [:t]})
           (sql/format {:select [[[:raw "NOW()"]]] :from [:t]})))
    (is (= (sql/format {:select [:a] :from [:t] :where [:> :a (sql/raw "NOW()")]})
           (sql/format {:select [:a] :from [:t] :where [:> :a [:raw "NOW()"]]})))
    (testing "bare at the top level it is an alias pair, and the error says how to nest it"
      (let [d (thrown-data #(sql/format {:select [[:raw "NOW()"]] :from [:t]}))]
        (is (= :select (:op d)))
        (is (= "NOW()" (:arg d)))
        (is (re-find #"\[\[:raw \"NOW\(\)\"\]\]" (:message d))))))
  (testing "a quoted identifier passes through untouched"
    (is (= "SELECT \"UserId\" FROM \"Users\""
           (:text (sql/format {:select [(sql/raw "\"UserId\"")] :from [(sql/raw "\"Users\"")]})))))
  (testing "raw text is never parameterized: a quote inside stays, no value is collected"
    (is (= {:text "SELECT a FROM t WHERE created < NOW() - '5 seconds'" :values []}
           (sql/format {:select [:a] :from [:t] :where [:< :created (sql/raw "NOW() - '5 seconds'")]}))))
  (testing "raw is a value that prints and compares"
    (is (= "#sql-cljs/raw \"NOW()\"" (pr-str (sql/raw "NOW()"))))
    (is (= (sql/raw "x") (sql/raw "x")))
    (is (not= (sql/raw "x") (sql/raw "y")))
    (is (util/raw? (sql/raw "x")))
    (is (not (util/raw? "x"))))
  (testing "anything but a string is a format-error at construction"
    (let [d (thrown-data #(sql/raw 5))]
      (is (= :sql-cljs/format-error (:type d)))
      (is (= :raw (:op d)))
      (is (= 5 (:arg d))))
    (is (= :raw (:op (thrown-data #(sql/raw nil))))))
  (testing "[:raw ...] needs exactly one string"
    (let [d (thrown-data #(sql/format {:select [:a] :where [:raw 5]}))]
      (is (= :raw (:op d)))
      (is (= [:raw 5] (:arg d))))
    (is (= [:raw "a" "b"] (:arg (thrown-data #(sql/format {:select [[[:raw "a" "b"]]]})))))
    (is (= [:raw] (:arg (thrown-data #(sql/format {:select [[[:raw]]]})))))))
