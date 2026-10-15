(ns sql-cljs.dml-errors-test
  "The error battery of data modification: every malformed INSERT, UPDATE,
   DELETE and TRUNCATE is a :sql-cljs/format-error whose :op and :arg are
   pinned."
  (:require [cljs.test :refer [deftest is testing]]
            [sql-cljs.core :as sql]))

(defn- error-of
  "The ex-data of the format-error (sql/format q) raises, nil if it does not."
  ([q] (error-of q nil))
  ([q opts]
   (try (sql/format q opts) nil
        (catch :default e (ex-data e)))))

(defn- op-arg [q] ((juxt :op :arg) (error-of q)))

(defn- format-error? [d]
  (= :sql-cljs/format-error (:type d)))

(deftype Opaque [])

;; ---------------------------------------------------------------------------
;; INSERT
;; ---------------------------------------------------------------------------

(deftest insert-target-errors
  (testing "the target is an entity or [entity alias], never a value or a subquery"
    (let [d (error-of {:insert-into "t" :values [[1]]})]
      (is (format-error? d))
      (is (= :insert-into (:op d)))
      (is (= "t" (:arg d)))
      (is (re-find #"strings are values" (:message d))))
    (is (= [:insert-into {:select [:a]}] (op-arg {:insert-into {:select [:a]} :values [[1]]})))
    (is (= [:insert-into [:t :x :y]] (op-arg {:insert-into [:t :x :y] :values [[1]]})))
    (is (= [:insert-into "x"] (op-arg {:insert-into [:t "x"] :values [[1]]})))
    (is (= [:insert-into "T"] (op-arg {:insert-into :T :values [[1]]})))
    (is (= [:insert-into nil] (op-arg {:insert-into nil :values [[1]]})))))

(deftest insert-values-errors
  (testing "INSERT needs :values"
    (let [d (error-of {:insert-into :t :columns [:a]})]
      (is (format-error? d))
      (is (= :insert-into (:op d)))
      (is (= {:insert-into :t :columns [:a]} (:arg d)))
      (is (re-find #":values" (:message d)))))
  (testing "empty, a flat vector, a value, a set"
    (is (= [:values []] (op-arg {:insert-into :t :values []})))
    (is (= [:values 1] (op-arg {:insert-into :t :values [1 2]})))
    (is (= [:values "x"] (op-arg {:insert-into :t :values "x"})))
    (is (= [:values #{1}] (op-arg {:insert-into :t :values #{1}})))
    (is (= [:values 2] (op-arg {:insert-into :t :values [[1] 2]}))))
  (testing "an empty row, rows of different widths, a width differing from :columns"
    (is (= [:values []] (op-arg {:insert-into :t :values [[]]})))
    (is (= [:values [3]] (op-arg {:insert-into :t :values [[1 2] [3]]})))
    (let [d (error-of {:insert-into :t :columns [:a] :values [[1 2]]})]
      (is (= :values (:op d)))
      (is (= [1 2] (:arg d)))
      (is (= [:a] (:columns d)))))
  (testing "mixed vector and map rows, either way round"
    (is (= [:values {:a 1}] (op-arg {:insert-into :t :values [[1] {:a 1}]})))
    (is (= [:values [1]] (op-arg {:insert-into :t :values [{:a 1} [1]]}))))
  (testing "map rows with different key sets: the odd row, the columns"
    (let [d (error-of {:insert-into :t :values [{:a 1} {:a 2 :b 3}]})]
      (is (format-error? d))
      (is (= :values (:op d)))
      (is (= {:a 2 :b 3} (:arg d)))
      (is (= [:a] (:columns d)))
      (is (re-find #":default" (:message d))))
    (is (= [:values {:b 2}] (op-arg {:insert-into :t :values [{:a 1} {:b 2}]})))
    (is (= [:values {:a 1}] (op-arg {:insert-into :t :values [{:a 1 :b 2} {:a 1}]})))
    (is (= [:values {:a 1 :b 2}] (op-arg {:insert-into :t :columns [:a] :values [{:a 1 :b 2}]})))
    (is (= [:values {}] (op-arg {:insert-into :t :values [{}]}))))
  (testing "a bad column key in a map row is reported under :values, in :columns under :columns"
    (is (= [:values "A"] (op-arg {:insert-into :t :values [{:A 1}]})))
    (is (= [:values "a-b"] (op-arg {:insert-into :t :values [{:a-b 1}]})))
    (is (= [:columns "a"] (op-arg {:insert-into :t :columns ["a"] :values [[1]]})))
    (is (= [:columns "A"] (op-arg {:insert-into :t :columns [:A] :values [[1]]})))
    (is (= [:columns []] (op-arg {:insert-into :t :columns [] :values [[1]]})))
    (is (= [:columns 1] (op-arg {:insert-into :t :columns 1 :values [[1]]}))))
  (testing "a value element that is a list or a set, an unknown operator in a row"
    (is (= [:values '(1 2)] (op-arg {:insert-into :t :values [['(1 2)]]})))
    (is (= [:<=> [:<=> 1 2]] (op-arg {:insert-into :t :values [[[:<=> 1 2]]]}))))
  (testing "the source statement is validated like any other"
    (is (= [:statement :form] (op-arg {:insert-into :t :values {:select [:a] :form [:t]}})))
    (is (= [:statement {}] (op-arg {:insert-into :t :values {}})))))

(deftest on-conflict-errors
  (let [base {:insert-into :t :values [[1]]}]
    (testing "a map, not a vector or a keyword"
      (let [d (error-of (assoc base :on-conflict [:id]))]
        (is (format-error? d))
        (is (= :on-conflict (:op d)))
        (is (= [:id] (:arg d))))
      (is (= [:on-conflict :do-nothing] (op-arg (assoc base :on-conflict :do-nothing)))))
    (testing "an unknown key"
      (let [d (error-of (assoc base :on-conflict {:do-nothing true :foo 1}))]
        (is (= :on-conflict (:op d)))
        (is (= :foo (:arg d)))
        (is (= [:on-columns :on-constraint :index-where :do-nothing :do-update-set :where] (:allowed d)))))
    (testing "no action, both actions"
      (is (= [:on-conflict {:on-columns [:id]}] (op-arg (assoc base :on-conflict {:on-columns [:id]}))))
      (is (= [:on-conflict {}] (op-arg (assoc base :on-conflict {}))))
      (is (= [:on-conflict {:on-columns [:id] :do-nothing false}]
             (op-arg (assoc base :on-conflict {:on-columns [:id] :do-nothing false}))))
      (is (= [:on-conflict {:do-nothing true :do-update-set {:a 1}}]
             (op-arg (assoc base :on-conflict {:on-columns [:id] :do-nothing true :do-update-set {:a 1}})))))
    (testing ":do-nothing is true or false"
      (is (= [:do-nothing 1] (op-arg (assoc base :on-conflict {:do-nothing 1}))))
      (is (= [:do-nothing "yes"] (op-arg (assoc base :on-conflict {:do-nothing "yes"})))))
    (testing "both targets"
      (is (= [:on-conflict {:on-columns [:a] :on-constraint :c}]
             (op-arg (assoc base :on-conflict {:on-columns [:a] :on-constraint :c :do-nothing true})))))
    (testing "the index predicate without :on-columns, the update condition without :do-update-set"
      (is (= [:on-conflict [:is-null :x]] (op-arg (assoc base :on-conflict {:index-where [:is-null :x] :do-nothing true}))))
      (is (= [:on-conflict [:is-null :x]]
             (op-arg (assoc base :on-conflict {:on-constraint :c :index-where [:is-null :x] :do-nothing true}))))
      (is (= [:on-conflict [:= :a 1]] (op-arg (assoc base :on-conflict {:on-columns [:a] :do-nothing true :where [:= :a 1]})))))
    (testing "the target items"
      (is (= [:on-conflict []] (op-arg (assoc base :on-conflict {:on-columns [] :do-nothing true}))))
      (is (= [:on-conflict "id"] (op-arg (assoc base :on-conflict {:on-columns ["id"] :do-nothing true}))))
      (is (= [:on-conflict "Id"] (op-arg (assoc base :on-conflict {:on-columns [:Id] :do-nothing true}))))
      (is (= [:on-conflict 1] (op-arg (assoc base :on-conflict {:on-columns [1] :do-nothing true}))))
      (is (= [:on-conflict "c"] (op-arg (assoc base :on-conflict {:on-constraint "c" :do-nothing true}))))
      (is (= [:on-conflict "C"] (op-arg (assoc base :on-conflict {:on-constraint :C :do-nothing true})))))
    (testing "the assignments of DO UPDATE SET"
      (is (= [:do-update-set {}] (op-arg (assoc base :on-conflict {:on-columns [:a] :do-update-set {}}))))
      (is (= [:do-update-set []] (op-arg (assoc base :on-conflict {:on-columns [:a] :do-update-set []}))))
      (is (= [:do-update-set "a"] (op-arg (assoc base :on-conflict {:on-columns [:a] :do-update-set {"a" 1}}))))
      (is (= [:do-update-set :t/a] (op-arg (assoc base :on-conflict {:on-columns [:a] :do-update-set {:t/a 1}}))))
      (is (= [:do-update-set "x"] (op-arg (assoc base :on-conflict {:on-columns [:a] :do-update-set "x"}))))
      (is (= [:do-update-set [:a 1 2]] (op-arg (assoc base :on-conflict {:on-columns [:a] :do-update-set [[:a 1 2]]}))))
      (is (= [:do-update-set "Name"] (op-arg (assoc base :on-conflict {:on-columns [:a] :do-update-set {:a :excluded/Name}})))))
    (testing "the conditions are expression vectors"
      (is (= [:where :x] (op-arg (assoc base :on-conflict {:on-columns [:a] :do-update-set {:a 1} :where :x}))))
      (is (= [:index-where "x"] (op-arg (assoc base :on-conflict {:on-columns [:a] :index-where "x" :do-nothing true})))))))

(deftest returning-errors
  (testing "a value, a malformed pair, an empty list, a star alias"
    (let [d (error-of {:insert-into :t :values [[1]] :returning ["id"]})]
      (is (format-error? d))
      (is (= :returning (:op d)))
      (is (= "id" (:arg d))))
    (is (= [:returning [:a :b :c]] (op-arg {:insert-into :t :values [[1]] :returning [[:a :b :c]]})))
    (is (= [:returning []] (op-arg {:insert-into :t :values [[1]] :returning []})))
    (is (= [:returning "*"] (op-arg {:insert-into :t :values [[1]] :returning [[:count :*]]})))
    (is (= [:returning "Id"] (op-arg {:update :t :set {:a 1} :returning [:Id]})))
    (is (= [:returning 1] (op-arg {:delete-from :t :returning [1]})))))

;; ---------------------------------------------------------------------------
;; UPDATE and DELETE
;; ---------------------------------------------------------------------------

(deftest update-errors
  (testing "UPDATE needs :set"
    (let [d (error-of {:update :t :where [:= :id 1]})]
      (is (format-error? d))
      (is (= :update (:op d)))
      (is (= {:update :t :where [:= :id 1]} (:arg d)))
      (is (re-find #":set" (:message d)))))
  (testing "the assignments: empty, a string column, a qualified column, a bad pair, a flat pair, a value"
    (is (= [:set {}] (op-arg {:update :t :set {}})))
    (is (= [:set []] (op-arg {:update :t :set []})))
    (let [d (error-of {:update :t :set {"a" 1}})]
      (is (= :set (:op d)))
      (is (= "a" (:arg d)))
      (is (re-find #"strings are values" (:message d))))
    (let [d (error-of {:update :t :set {:t/a 1}})]
      (is (= :set (:op d)))
      (is (= :t/a (:arg d)))
      (is (re-find #"never table-qualified" (:message d))))
    (is (= [:set :t.a] (op-arg {:update :t :set [[:t.a 1]]})))
    (is (= [:set [:a 1 2]] (op-arg {:update :t :set [[:a 1 2]]})))
    (is (= [:set :a] (op-arg {:update :t :set [:a 1]})))
    (is (= [:set 5] (op-arg {:update :t :set 5})))
    (is (= [:set "A"] (op-arg {:update :t :set {:A 1}})))
    (is (= [:set '(1)] (op-arg {:update :t :set {:a '(1)}}))))
  (testing "the target"
    (is (= [:update "t"] (op-arg {:update "t" :set {:a 1}})))
    (is (= [:update {:select [:a]}] (op-arg {:update {:select [:a]} :set {:a 1}})))
    (is (= [:update "x"] (op-arg {:update [:t "x"] :set {:a 1}}))))
  (testing "clauses that do not apply next to :update"
    (let [d (error-of {:update :t :set {:a 1} :group-by [:a]})]
      (is (format-error? d))
      (is (= :update (:op d)))
      (is (= :group-by (:arg d)))
      (is (= [:from :returning :set :update :where :with :with-recursive] (:allowed d))))
    (is (= [:update :values] (op-arg {:update :t :set {:a 1} :values [[1]]})))
    (is (= [:update :using] (op-arg {:update :t :set {:a 1} :using [:u]})))
    (is (= [:update :on-conflict] (op-arg {:update :t :set {:a 1} :on-conflict {:do-nothing true}})))
    (is (= [:update :limit] (op-arg {:update :t :set {:a 1} :limit 1}))))
  (testing "WHERE and FROM are validated as in SELECT"
    (is (= [:where :active] (op-arg {:update :t :set {:a 1} :where :active})))
    (is (= [:from "u"] (op-arg {:update :t :set {:a 1} :from ["u"]})))))

(deftest delete-errors
  (testing "the target"
    (is (= [:delete-from "t"] (op-arg {:delete-from "t"})))
    (is (= [:delete-from {:select [:a]}] (op-arg {:delete-from {:select [:a]}})))
    (is (= [:delete-from "T"] (op-arg {:delete-from :T}))))
  (testing "USING is a FROM list"
    (is (= [:using []] (op-arg {:delete-from :t :using []})))
    (is (= [:using "u"] (op-arg {:delete-from :t :using ["u"]})))
    (is (= [:using "U"] (op-arg {:delete-from :t :using [[:U :x]]}))))
  (testing "clauses that do not apply next to :delete-from"
    (let [d (error-of {:delete-from :t :set {:a 1}})]
      (is (= :delete-from (:op d)))
      (is (= :set (:arg d)))
      (is (= [:delete-from :returning :using :where :with :with-recursive] (:allowed d))))
    (is (= [:delete-from :from] (op-arg {:delete-from :t :from [:u]})))
    (is (= [:delete-from :order-by] (op-arg {:delete-from :t :order-by [:a]})))))

;; ---------------------------------------------------------------------------
;; TRUNCATE
;; ---------------------------------------------------------------------------

(deftest truncate-errors
  (testing "the table list: empty, a value, an alias pair, a subquery, an exotic name"
    (is (= [:truncate []] (op-arg {:truncate []})))
    (is (= [:truncate "t"] (op-arg {:truncate ["t"]})))
    (is (= [:truncate "t"] (op-arg {:truncate "t"})))
    (is (= [:truncate [:t :x]] (op-arg {:truncate [[:t :x]]})))
    (is (= [:truncate {:select [:a]}] (op-arg {:truncate [{:select [:a]}]})))
    (is (= [:truncate "T"] (op-arg {:truncate :T}))))
  (testing "the flags are true or false"
    (is (= [:restart-identity 1] (op-arg {:truncate :t :restart-identity 1})))
    (is (= [:continue-identity "yes"] (op-arg {:truncate :t :continue-identity "yes"})))
    (is (= [:cascade :cascade] (op-arg {:truncate :t :cascade :cascade})))
    (is (= [:cascade 0] (op-arg {:truncate :t :cascade 0}))))
  (testing "a nil flag is absent, like false"
    (is (= "TRUNCATE TABLE t" (:text (sql/format {:truncate :t :restrict nil :cascade false})))))
  (testing "contradictory flags"
    (let [d (error-of {:truncate :t :restart-identity true :continue-identity true})]
      (is (format-error? d))
      (is (= :truncate (:op d)))
      (is (= {:restart-identity true :continue-identity true} (:arg d))))
    (is (= [:truncate {:cascade true :restrict true}] (op-arg {:truncate :t :cascade true :restrict true}))))
  (testing "clauses that do not apply next to :truncate"
    (let [d (error-of {:truncate :t :where [:= :a 1]})]
      (is (= :truncate (:op d)))
      (is (= :where (:arg d)))
      (is (= [:cascade :continue-identity :restart-identity :restrict :truncate] (:allowed d))))
    (is (= [:truncate :returning] (op-arg {:truncate :t :returning [:*]})))
    (is (= [:truncate :with] (op-arg {:truncate :t :with [[:c {:select [:a]}]]})))))

;; ---------------------------------------------------------------------------
;; Statement level
;; ---------------------------------------------------------------------------

(deftest dml-statement-errors
  (testing "two heads"
    (is (= [:statement {:select [:a] :insert-into :t :values [[1]]}]
           (op-arg {:select [:a] :insert-into :t :values [[1]]})))
    (is (= :statement (:op (error-of {:update :t :set {:a 1} :delete-from :u}))))
    (is (= :statement (:op (error-of {:truncate :t :delete-from :u})))))
  (testing "an unknown clause next to a DML head is reported at the statement level with every clause"
    (let [d (error-of {:insert-into :t :values [[1]] :valeus [[1]]})]
      (is (= :statement (:op d)))
      (is (= :valeus (:arg d)))
      (is (contains? (set (:allowed d)) :values))
      (is (contains? (set (:allowed d)) :truncate))))
  (testing "a DML clause next to SELECT or a set operation"
    (let [d (error-of {:select [:a] :from [:t] :returning [:a]})]
      (is (= :select (:op d)))
      (is (= :returning (:arg d)))
      (is (= [:cross-join :distinct :distinct-on :for :from :full-join :group-by :having :inner-join :join
              :left-join :limit :offset :order-by :right-join :select :where :with :with-recursive]
             (:allowed d))))
    (is (= [:select :set] (op-arg {:select [:a] :set {:a 1}})))
    (is (= [:select :values] (op-arg {:select [:a] :values [[1]]})))
    (is (= [:union :values] (op-arg {:union [{:select [:a]} {:select [:b]}] :values [[1]]}))))
  (testing "INSERT next to the SELECT-only clauses"
    (let [d (error-of {:insert-into :t :values [[1]] :where [:= :a 1]})]
      (is (= :insert-into (:op d)))
      (is (= :where (:arg d)))
      (is (= [:columns :insert-into :on-conflict :returning :values :with :with-recursive] (:allowed d))))
    (is (= [:insert-into :from] (op-arg {:insert-into :t :values [[1]] :from [:u]})))
    (is (= [:insert-into :set] (op-arg {:insert-into :t :values [[1]] :set {:a 1}}))))
  (testing "a nested DML statement is validated the same way"
    (is (= [:update {:update :t}] (op-arg {:with [[:c {:update :t}]] :select [:a]})))))

;; ---------------------------------------------------------------------------
;; Inline mode
;; ---------------------------------------------------------------------------

(deftest inline-dml-errors
  (testing "a value with no literal spelling in a row, an assignment, a condition"
    (let [obj (Opaque.)
          d (error-of {:insert-into :t :values [[obj]]} {:inline true})]
      (is (format-error? d))
      (is (= :inline (:op d)))
      (is (identical? obj (:arg d))))
    (let [obj (Opaque.)]
      (is (= :inline (:op (error-of {:update :t :set {:a obj}} {:inline true}))))
      (is (= :inline (:op (error-of {:delete-from :t :where [:= :a obj]} {:inline true}))))
      (is (= :inline (:op (error-of {:insert-into :t :values [[1]] :on-conflict {:on-columns [:a] :do-update-set {:a obj}}} {:inline true})))))
    (let [d (error-of {:update :t :set {:a ##NaN}} {:inline true})]
      (is (= :inline (:op d)))
      (is (NaN? (:arg d))))
    (is (= [:inline ##Inf] (let [d (error-of {:insert-into :t :values [[##Inf]]} {:inline true})] [(:op d) (:arg d)]))))
  (testing "the same statements format in parameter mode, the value passed through"
    (let [obj (Opaque.)]
      (is (= {:text "INSERT INTO t VALUES ($1)" :values [obj]} (sql/format {:insert-into :t :values [[obj]]})))
      (is (= {:text "UPDATE t SET a = $1" :values [obj]} (sql/format {:update :t :set {:a obj}}))))))
