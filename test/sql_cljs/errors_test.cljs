(ns sql-cljs.errors-test
  "The error battery: every malformed input is a :sql-cljs/format-error
   whose :op and :arg are pinned."
  (:require [cljs.test :refer [deftest is testing]]
            [sql-cljs.core :as sql]))

(defn- error-of
  "The ex-data of the format-error (sql/format q) raises, nil if it does not."
  ([q] (error-of q nil))
  ([q opts]
   (try (sql/format q opts) nil
        (catch :default e (ex-data e)))))

(defn- format-error? [d]
  (= :sql-cljs/format-error (:type d)))

;; ---------------------------------------------------------------------------
;; Identifiers
;; ---------------------------------------------------------------------------

(deftest identifier-errors
  (testing ":UserId in the select list: the part in :arg, the whole entity, the hatch in the message"
    (let [d (error-of {:select [:UserId] :from [:t]})]
      (is (format-error? d))
      (is (= :select (:op d)))
      (is (= "UserId" (:arg d)))
      (is (= :UserId (:entity d)))
      (is (re-find #"raw" (:message d)))
      (is (re-find #"UserId" (:message d)))))
  (testing "the same entity in every other position"
    (is (= [:from "Users"] ((juxt :op :arg) (error-of {:select [:a] :from [:Users]}))))
    (is (= [:= "UserId"] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:= :UserId 1]}))))
    (is (= [:group-by "A"] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :group-by [:A]}))))
    (is (= [:order-by "A"] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :order-by [[:A :desc]]}))))
    (is (= [:left-join "Bar"] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :left-join [:Bar [:= :a :b]]}))))
    (is (= [:join "Id"] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :join [:u [:using :Id]]}))))
    (is (= [:for "T"] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :for [:update :T]}))))
    (is (= [:with "C"] ((juxt :op :arg) (error-of {:with [[:C {:select [:a]}]] :select [:a]}))))
    (is (= [:with "Col"] ((juxt :op :arg) (error-of {:with [[[:c :Col] {:select [:a]}]] :select [:a]}))))
    (is (= [:select "Alias"] ((juxt :op :arg) (error-of {:select [[:a :Alias]] :from [:t]}))))
    (is (= [:from "S"] ((juxt :op :arg) (error-of {:select [:a] :from [[{:select [:a]} :S]]}))))
    (is (= [:count "A"] ((juxt :op :arg) (error-of {:select [[[:count :A]]] :from [:t]})))))
  (testing "exotic characters, a hyphen, a leading digit, an empty part, in a qualified part"
    (is (= ["a-b" :a-b] ((juxt :arg :entity) (error-of {:select [:a-b]}))))
    (is (= ["1a" :1a] ((juxt :arg :entity) (error-of {:select [:1a]}))))
    (is (= ["a b" (keyword "a b")] ((juxt :arg :entity) (error-of {:select [(keyword "a b")]}))))
    (is (= ["Bar" :foo/Bar] ((juxt :arg :entity) (error-of {:select [:foo/Bar]}))))
    (is (= ["Foo" :Foo/bar] ((juxt :arg :entity) (error-of {:select [:Foo/bar]}))))
    (is (= ["" :foo.] ((juxt :arg :entity) (error-of {:select [:foo.]}))))
    (is (= ["a;" (keyword "a;")] ((juxt :arg :entity) (error-of {:select [(keyword "a;")]}))))
    (is (= ["Foo" 'Foo] ((juxt :arg :entity) (error-of {:select ['Foo]})))))
  (testing "a hyphenated function name is an identifier too"
    (let [d (error-of {:select [[[:string-agg :a ","]]] :from [:t]})]
      (is (= :select (:op d)))
      (is (= "string-agg" (:arg d)))
      (is (= :string-agg (:entity d)))))
  (testing "the raw hatch makes the same statement format"
    (is (= "SELECT \"UserId\" FROM \"Users\""
           (:text (sql/format {:select [(sql/raw "\"UserId\"")] :from [(sql/raw "\"Users\"")]}))))))

(deftest strings-are-never-entities
  (is (= [:select "a"] ((juxt :op :arg) (error-of {:select ["a"] :from [:t]}))))
  (is (= [:select "a"] ((juxt :op :arg) (error-of {:select "a" :from [:t]}))))
  (is (= [:from "t"] ((juxt :op :arg) (error-of {:select [:a] :from ["t"]}))))
  (is (= [:from "t"] ((juxt :op :arg) (error-of {:select [:a] :from "t"}))))
  (is (= [:from "s"] ((juxt :op :arg) (error-of {:select [:a] :from [[:t "s"]]}))))
  (is (= [:select "s"] ((juxt :op :arg) (error-of {:select [[:a "s"]] :from [:t]}))))
  (is (= [:join "u"] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :join ["u" [:= :a :b]]}))))
  (is (= [:join "id"] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :join [:u [:using "id"]]}))))
  (is (= [:for "t"] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :for [:update "t"]}))))
  (is (= [:with "c"] ((juxt :op :arg) (error-of {:with [["c" {:select [:a]}]] :select [:a]}))))
  (testing "the message says so"
    (is (re-find #"strings are values" (:message (error-of {:select [:a] :from ["t"]}))))
    (is (re-find #"strings are values" (:message (error-of {:select ["a"]}))))))

;; ---------------------------------------------------------------------------
;; Select list shapes
;; ---------------------------------------------------------------------------

(deftest select-list-errors
  (testing "empty"
    (let [d (error-of {:select [] :from [:t]})]
      (is (format-error? d))
      (is (= :select (:op d)))
      (is (= [] (:arg d))))
    (is (= [:select nil] ((juxt :op :arg) (error-of {:select nil :from [:t]}))))
    (is (= [:select ()] ((juxt :op :arg) (error-of {:select () :from [:t]})))))
  (testing "[:count :*] bare: the star in alias position names the nested form"
    (let [d (error-of {:select [[:count :*]] :from [:t]})]
      (is (format-error? d))
      (is (= :select (:op d)))
      (is (= "*" (:arg d)))
      (is (= :* (:entity d)))
      (is (re-find #"\[\[:count :\*\]\]" (:message d)))
      (is (re-find #"\[\[:count :\*\] :total\]" (:message d)))))
  (testing "a non-entity in alias position: a vector, a string, a number, a map"
    (doseq [bad [[:a] "s" 1 {:select [:a]}]]
      (let [d (error-of {:select [[:a bad]] :from [:t]})]
        (is (= :select (:op d)) (pr-str bad))
        (is (= bad (:arg d)) (pr-str bad))
        (is (re-find #"\[\[:count :\*\] :total\]" (:message d)) (pr-str bad)))))
  (testing "a qualified star in alias position"
    (is (= [:select "*" :t/*] ((juxt :op :arg :entity) (error-of {:select [[:a :t/*]]})))))
  (testing "a vector of three"
    (is (= [:select [:a :b :c]] ((juxt :op :arg) (error-of {:select [[:a :b :c]]}))))
    (is (= [:select []] ((juxt :op :arg) (error-of {:select [[]]})))))
  (testing "a bare value"
    (let [d (error-of {:select [1] :from [:t]})]
      (is (= :select (:op d)))
      (is (= 1 (:arg d)))
      (is (re-find #"\[\[1\]\]" (:message d))))
    (is (= [:select nil] ((juxt :op :arg) (error-of {:select [nil]}))))
    (is (= [:select true] ((juxt :op :arg) (error-of {:select [true]})))))
  (testing "the star outside the select list and function arguments"
    (is (= [:from "*" :*] ((juxt :op :arg :entity) (error-of {:select [:a] :from [:*]}))))
    (is (= [:= "*" :*] ((juxt :op :arg :entity) (error-of {:select [:a] :from [:t] :where [:= :* 1]}))))
    (is (= [:group-by "*" :t/*] ((juxt :op :arg :entity) (error-of {:select [:a] :from [:t] :group-by [:t/*]}))))
    (is (= [:order-by "*" :*] ((juxt :op :arg :entity) (error-of {:select [:a] :from [:t] :order-by [:*]})))))
  (testing ":distinct and :distinct-on"
    (is (= [:distinct "yes"] ((juxt :op :arg) (error-of {:select [:a] :distinct "yes"}))))
    (is (= :select (:op (error-of {:select [:a] :distinct true :distinct-on [:a]}))))
    (is (= [:distinct-on []] ((juxt :op :arg) (error-of {:select [:a] :distinct-on []}))))))

;; ---------------------------------------------------------------------------
;; Conditions and operators
;; ---------------------------------------------------------------------------

(deftest condition-errors
  (testing ":where must be a vector: a bare keyword, a string, a map, a boolean"
    (let [d (error-of {:select [:a] :from [:t] :where :active})]
      (is (format-error? d))
      (is (= :where (:op d)))
      (is (= :active (:arg d)))
      (is (re-find #"\[:= :active true\]" (:message d)))
      (is (re-find #"raw" (:message d))))
    (is (= [:where "a = 1"] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where "a = 1"}))))
    (is (= [:where nil] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where nil}))))
    (is (= [:where true] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where true}))))
    (is (= [:where {:select [:a]}] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where {:select [:a]}})))))
  (testing ":having the same"
    (is (= [:having :x] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :having :x})))))
  (testing "a vector of predicates is not an expression"
    (let [d (error-of {:select [:a] :from [:t] :where [[:= :a 1] [:= :b 2]]})]
      (is (= :where (:op d)))
      (is (= [[:= :a 1] [:= :b 2]] (:arg d)))
      (is (re-find #"\[:and" (:message d)))))
  (testing "an empty vector, a value-headed vector"
    (is (= [:where []] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where []}))))
    (is (= [:where [1 2]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [1 2]}))))
    (is (= [:where ["=" :a 1]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where ["=" :a 1]})))))
  (testing "a list or a set in expression position is neither a value nor an expression"
    (is (= [:= '(1 2)] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:= :a '(1 2)]}))))
    (is (= [:= #{1}] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:= :a #{1}]}))))
    (is (= [:select '(:a)] ((juxt :op :arg) (error-of {:select ['(:a)] :from [:t]}))))))

(deftest operator-errors
  (testing "unknown symbol-only keywords are operators the formatter does not know"
    (let [d (error-of {:select [:a] :from [:t] :where [:!= :a 1]})]
      (is (format-error? d))
      (is (= :!= (:op d)))
      (is (= [:!= :a 1] (:arg d)))
      (is (vector? (:allowed d)))
      (is (contains? (set (:allowed d)) :not=))
      (is (= (count (:allowed d)) (count (set (:allowed d))))))
    (is (= [:<=> [:<=> :a 1]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:<=> :a 1]}))))
    (is (= [:|| [:|| :a :b]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:= [:|| :a :b] 1]}))))
    (let [caret (keyword "^")]
      (is (= [caret [caret :a 2]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:= [caret :a 2] 1]}))))))
  (testing "the empty IN list"
    (let [d (error-of {:select [:a] :from [:t] :where [:in :a []]})]
      (is (format-error? d))
      (is (= :in (:op d)))
      (is (= [:in :a []] (:arg d)))
      (is (re-find #"FALSE" (:message d))))
    (is (= [:not-in [:not-in :a ()]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:not-in :a ()]}))))
    (is (= [:in [:in :a 1]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:in :a 1]}))))
    (is (= [:in [:in :a "x"]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:in :a "x"]})))))
  (testing "arity"
    (is (= [:= [:= :a]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:= :a]}))))
    (is (= [:= [:= :a 1 2]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:= :a 1 2]}))))
    (is (= [:not [:not]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:not]}))))
    (is (= [:not [:not :a :b]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:not :a :b]}))))
    (is (= [:and [:and]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:and]}))))
    (is (= [:or [:or]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:or]}))))
    (is (= [:+ [:+ :a]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:= [:+ :a] 1]}))))
    (is (= [:* [:*]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:= [:*] 1]}))))
    (is (= [:- [:-]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:= [:-] 1]}))))
    (is (= [:between [:between :a 1]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:between :a 1]}))))
    (is (= [:is-null [:is-null :a :b]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:is-null :a :b]}))))
    (is (= [:like [:like :a]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:like :a]}))))
    (is (= [:in [:in :a]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:in :a]})))))
  (testing "EXISTS needs a statement map"
    (is (= [:exists [:exists :t]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:exists :t]}))))
    (is (= [:not-exists [:not-exists [:= :a 1]]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:not-exists [:= :a 1]]}))))))

;; ---------------------------------------------------------------------------
;; Clause shapes
;; ---------------------------------------------------------------------------

(deftest statement-errors
  (testing "an unknown clause"
    (let [d (error-of {:select [:a] :form [:t]})]
      (is (format-error? d))
      (is (= :statement (:op d)))
      (is (= :form (:arg d)))
      (is (contains? (set (:allowed d)) :from))))
  (testing "no statement head, two heads"
    (is (= [:statement {:from [:t]}] ((juxt :op :arg) (error-of {:from [:t]}))))
    (is (= [:statement {}] ((juxt :op :arg) (error-of {}))))
    (is (= :statement (:op (error-of {:select [:a] :union [{:select [:a]} {:select [:b]}]}))))
    (is (= :statement (:op (error-of {:union [{:select [:a]} {:select [:b]}] :union-all [{:select [:a]} {:select [:b]}]})))))
  (testing "a nested statement is validated the same way"
    (is (= [:statement :form] ((juxt :op :arg) (error-of {:select [:a] :from [[{:select [:a] :form [:t]} :s]]}))))
    (is (= [:union [:select :a]] ((juxt :op :arg) (error-of {:union [[:select :a] {:select [:a]}]}))))
    (is (= [:with [:c [:select :a]]] ((juxt :op :arg) (error-of {:with [[:c [:select :a]]] :select [:a]}))))
    (is (= [:union "x"] ((juxt :op :arg) (error-of {:union ["x" {:select [:a]}]})))))
  (testing ":with and :with-recursive together"
    (is (= :statement (:op (error-of {:with [[:a {:select [:x]}]] :with-recursive [[:b {:select [:y]}]] :select [:a]}))))))

(deftest from-and-join-errors
  (is (= [:from []] ((juxt :op :arg) (error-of {:select [:a] :from []}))))
  (is (= [:from [:t :a :b]] ((juxt :op :arg) (error-of {:select [:a] :from [[:t :a :b]]}))))
  (is (= [:from 1] ((juxt :op :arg) (error-of {:select [:a] :from [1]}))))
  (testing "a join needs table then condition pairs"
    (let [d (error-of {:select [:a] :from [:t] :join [:u]})]
      (is (= :join (:op d)))
      (is (= [:u] (:arg d)))
      (is (re-find #"pairs" (:message d))))
    (is (= [:left-join [:u [:= :a :b] :v]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :left-join [:u [:= :a :b] :v]}))))
    (is (= [:join []] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :join []})))))
  (testing "a join condition is an expression vector or [:using ...]"
    (is (= [:join :a] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :join [:u :a]}))))
    (is (= [:join "a = b"] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :join [:u "a = b"]}))))
    (is (= [:join nil] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :join [:u nil]}))))
    (is (= [:join [:using]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :join [:u [:using]]})))))
  (testing "CROSS JOIN takes no condition"
    (let [d (error-of {:select [:a] :from [:t] :cross-join [:u [:= :a :b]]})]
      (is (= :cross-join (:op d)))
      (is (= [:= :a :b] (:arg d))))
    (is (= [:cross-join [:using :a]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :cross-join [:u [:using :a]]}))))
    (is (= [:cross-join []] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :cross-join []}))))))

(deftest ordering-and-paging-errors
  (testing "a sort modifier as a bare item is the classic mistake"
    (let [d (error-of {:select [:a] :from [:t] :order-by [:a :desc]})]
      (is (format-error? d))
      (is (= :order-by (:op d)))
      (is (= :desc (:arg d)))
      (is (re-find #"\[\[:a :desc\]\]" (:message d))))
    (is (= [:order-by :nulls-last] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :order-by [:nulls-last]})))))
  (testing "an order-by vector is [expression & modifiers]"
    (let [d (error-of {:select [:a] :from [:t] :order-by [[:lower :name]]})]
      (is (= :order-by (:op d)))
      (is (= [:lower :name] (:arg d)))
      (is (= [:asc :desc :nulls-first :nulls-last] (:allowed d)))
      (is (re-find #"\[\[:lower :name\] :asc\]" (:message d))))
    (is (= [:order-by [:a :asc :desc]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :order-by [[:a :asc :desc]]}))))
    (is (= [:order-by [:a :nulls-first :nulls-last]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :order-by [[:a :nulls-first :nulls-last]]}))))
    (is (= [:order-by [:a "desc"]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :order-by [[:a "desc"]]}))))
    (is (= [:order-by []] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :order-by [[]]}))))
    (is (= [:order-by []] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :order-by []}))))
    (is (= [:group-by []] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :group-by []})))))
  (testing "LIMIT and OFFSET take a number"
    (is (= [:limit :ten] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :limit :ten}))))
    (is (= [:limit [:+ 1 2]] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :limit [:+ 1 2]}))))
    (is (= [:offset :all] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :offset :all}))))
    (is (= [:offset {:select [:a]}] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :offset {:select [:a]}}))))))

(deftest set-operation-errors
  (testing "fewer than two operands"
    (let [d (error-of {:union [{:select [:a]}]})]
      (is (= :union (:op d)))
      (is (= [{:select [:a]}] (:arg d))))
    (is (= [:union-all []] ((juxt :op :arg) (error-of {:union-all []})))))
  (testing "a SELECT-only clause next to a set operation"
    (let [d (error-of {:union [{:select [:a]} {:select [:b]}] :where [:= :a 1]})]
      (is (format-error? d))
      (is (= :union (:op d)))
      (is (= :where (:arg d)))
      (is (= [:limit :offset :order-by :union :union-all :with :with-recursive] (:allowed d))))
    (is (= [:union-all :from] ((juxt :op :arg) (error-of {:union-all [{:select [:a]} {:select [:b]}] :from [:t]}))))
    (is (= [:union :for] ((juxt :op :arg) (error-of {:union [{:select [:a]} {:select [:b]}] :for :update}))))))

(deftest cte-errors
  (testing "a CTE is [name statement]; the clause takes a vector of them"
    (let [d (error-of {:with [:c {:select [:a]}] :select [:a]})]
      (is (format-error? d))
      (is (= :with (:op d)))
      (is (= :c (:arg d)))
      (is (re-find #"\[\[:t \{:select" (:message d))))
    (is (= [:with-recursive [:c]] ((juxt :op :arg) (error-of {:with-recursive [[:c]] :select [:a]}))))
    (is (= [:with [:c {:select [:a]} :x]] ((juxt :op :arg) (error-of {:with [[:c {:select [:a]} :x]] :select [:a]}))))
    (is (= [:with []] ((juxt :op :arg) (error-of {:with [] :select [:a]})))))
  (testing "the body is a statement map"
    (is (= [:with [:c [:select :a]]] ((juxt :op :arg) (error-of {:with [[:c [:select :a]]] :select [:a]}))))
    (is (= [:with [:c "SELECT 1"]] ((juxt :op :arg) (error-of {:with [[:c "SELECT 1"]] :select [:a]}))))
    (is (= [:with nil] ((juxt :op :arg) (error-of {:with [[[] {:select [:a]}]] :select [:a]}))))))

(deftest locking-errors
  (testing "an unknown strength lists the accepted ones"
    (let [d (error-of {:select [:a] :from [:t] :for :exclusive})]
      (is (format-error? d))
      (is (= :for (:op d)))
      (is (= :exclusive (:arg d)))
      (is (= [:update :no-key-update :share :key-share] (:allowed d))))
    (is (= [:for :nowait] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :for [:nowait]}))))
    (is (= [:for "update"] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :for "update"}))))
    (is (= [:for nil] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :for []})))))
  (testing "NOWAIT and SKIP LOCKED exclude each other"
    (let [d (error-of {:select [:a] :from [:t] :for [:update :nowait :skip-locked]})]
      (is (= :for (:op d)))
      (is (= [:update :nowait :skip-locked] (:arg d)))
      (is (= [:nowait :skip-locked] (:allowed d))))))

(deftest errors-are-raised-before-any-text
  (testing "an error deep in the last clause leaves nothing behind: the next call is unaffected"
    (is (= [:for :bad] ((juxt :op :arg) (error-of {:select [:a] :from [:t] :where [:= :a 1] :for :bad}))))
    (is (= {:text "SELECT a FROM t WHERE a = $1" :values [1]}
           (sql/format {:select [:a] :from [:t] :where [:= :a 1]})))))
