(ns sql-cljs.ddl-errors-test
  "The error battery of data definition: every malformed CREATE TABLE, DROP
   TABLE, ALTER TABLE, CREATE INDEX, DROP INDEX, CREATE VIEW and DROP VIEW
   is a :sql-cljs/format-error whose :op and :arg are pinned."
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

(def ^:private column-constraint-words [:not-null :null :unique :primary-key])
(def ^:private column-constraint-heads [:check :constraint :default :on-delete :on-update :references])
(def ^:private table-constraint-heads [:check :constraint :foreign-key :primary-key :unique])
(def ^:private referential-actions [:no-action :restrict :cascade :set-null :set-default])

;; ---------------------------------------------------------------------------
;; CREATE TABLE
;; ---------------------------------------------------------------------------

(deftest create-table-statement-errors
  (testing "the target is an entity or raw, never a value or a subquery"
    (let [d (error-of {:create-table "t" :columns [[:a :text]]})]
      (is (format-error? d))
      (is (= :create-table (:op d)))
      (is (= "t" (:arg d)))
      (is (re-find #"strings are values" (:message d))))
    (is (= [:create-table {:select [:a]}] (op-arg {:create-table {:select [:a]} :columns [[:a :text]]})))
    (is (= [:create-table "T"] (op-arg {:create-table :T :columns [[:a :text]]})))
    (is (= [:create-table nil] (op-arg {:create-table nil :columns [[:a :text]]}))))
  (testing "CREATE TABLE needs :columns"
    (let [d (error-of {:create-table :t})]
      (is (format-error? d))
      (is (= :create-table (:op d)))
      (is (= {:create-table :t} (:arg d)))
      (is (re-find #":columns" (:message d))))
    (is (= [:create-table {:create-table :t :constraints [[:primary-key :a]]}]
           (op-arg {:create-table :t :constraints [[:primary-key :a]]}))))
  (testing ":columns empty or not a list of definitions"
    (is (= [:columns []] (op-arg {:create-table :t :columns []})))
    (is (= [:columns "a"] (op-arg {:create-table :t :columns "a"})))
    (is (= [:columns :a] (op-arg {:create-table :t :columns [:a]})))
    (is (= [:columns :a] (op-arg {:create-table :t :columns :a})))
    (is (= [:columns [:a]] (op-arg {:create-table :t :columns [[:a]]})))
    (is (= [:columns {:a :text}] (op-arg {:create-table :t :columns [{:a :text}]}))))
  (testing "the flag"
    (is (= [:if-not-exists "yes"] (op-arg {:create-table :t :if-not-exists "yes" :columns [[:a :text]]}))))
  (testing "clauses that do not apply next to :create-table, with :allowed"
    (let [d (error-of {:create-table :t :columns [[:a :text]] :where [:= :a 1]})]
      (is (format-error? d))
      (is (= :create-table (:op d)))
      (is (= :where (:arg d)))
      (is (= [:columns :constraints :create-table :if-not-exists] (:allowed d))))
    (is (= [:create-table :from] (op-arg {:create-table :t :columns [[:a :text]] :from [:u]})))
    (is (= [:create-table :with] (op-arg {:create-table :t :columns [[:a :text]] :with [[:c {:select [:a]}]]})))
    (is (= [:create-table :if-exists] (op-arg {:create-table :t :columns [[:a :text]] :if-exists true})))
    (is (= [:create-table :actions] (op-arg {:create-table :t :columns [[:a :text]] :actions [[:drop-column :a]]}))))
  (testing "two heads, an unknown clause"
    (is (= :statement (:op (error-of {:create-table :t :columns [[:a :text]] :drop-table :u}))))
    (is (= :statement (:op (error-of {:create-table :t :columns [[:a :text]] :select [:a]}))))
    (let [d (error-of {:create-table :t :column [[:a :text]]})]
      (is (= :statement (:op d)))
      (is (= :column (:arg d)))
      (is (contains? (set (:allowed d)) :create-table))
      (is (contains? (set (:allowed d)) :constraints)))))

(deftest create-table-column-errors
  (testing "a string column type is a value, never a type"
    (let [d (error-of {:create-table :t :columns [[:a "text"]]})]
      (is (format-error? d))
      (is (= :columns (:op d)))
      (is (= "text" (:arg d)))
      (is (re-find #"strings are values" (:message d)))
      (is (re-find #"raw" (:message d)))))
  (testing "other bad types: a number, a mixed-case keyword, a vector without arguments or with a string"
    (is (= [:columns 5] (op-arg {:create-table :t :columns [[:a 5]]})))
    (let [d (error-of {:create-table :t :columns [[:a :Text]]})]
      (is (= :columns (:op d)))
      (is (= :Text (:arg d)))
      (is (= :Text (:entity d))))
    (is (= [:columns :text-] (op-arg {:create-table :t :columns [[:a :text-]]})))
    (is (= [:columns [:varchar]] (op-arg {:create-table :t :columns [[:a [:varchar]]]})))
    (is (= [:columns [:varchar "255"]] (op-arg {:create-table :t :columns [[:a [:varchar "255"]]]})))
    (is (= [:columns [:varchar :n]] (op-arg {:create-table :t :columns [[:a [:varchar :n]]]})))
    (is (= [:columns ["varchar" 255]] (op-arg {:create-table :t :columns [[:a ["varchar" 255]]]})))
    (is (= [:columns [:Varchar 255]] (op-arg {:create-table :t :columns [[:a [:Varchar 255]]]})))
    (is (= [:columns nil] (op-arg {:create-table :t :columns [[:a nil]]}))))
  (testing "a column name is a plain entity or raw: not a value, not qualified, not mixed-case"
    (let [d (error-of {:create-table :t :columns [["a" :text]]})]
      (is (= :columns (:op d)))
      (is (= "a" (:arg d))))
    (is (= [:columns :t.a] (op-arg {:create-table :t :columns [[:t.a :text]]})))
    (is (= [:columns :t/a] (op-arg {:create-table :t :columns [[:t/a :text]]})))
    (let [d (error-of {:create-table :t :columns [[:A :text]]})]
      (is (= :columns (:op d)))
      (is (= "A" (:arg d)))
      (is (= :A (:entity d))))
    (is (= [:columns 1] (op-arg {:create-table :t :columns [[1 :text]]}))))
  (testing "unknown constraints, keyword and vector, with :allowed"
    (let [d (error-of {:create-table :t :columns [[:a :int :nope]]})]
      (is (= :columns (:op d)))
      (is (= :nope (:arg d)))
      (is (= column-constraint-words (:allowed d))))
    (is (= [:columns :not_null] (op-arg {:create-table :t :columns [[:a :int :not_null]]})))
    (let [d (error-of {:create-table :t :columns [[:a :int [:nope 1]]]})]
      (is (= :columns (:op d)))
      (is (= [:nope 1] (:arg d)))
      (is (= column-constraint-heads (:allowed d))))
    (is (= [:columns [:primary-key :a]] (op-arg {:create-table :t :columns [[:a :int [:primary-key :a]]]})))
    (is (= [:columns [:foreign-key :a [:references :t]]] (op-arg {:create-table :t :columns [[:a :int [:foreign-key :a [:references :t]]]]})))
    (is (= [:columns []] (op-arg {:create-table :t :columns [[:a :int []]]})))
    (is (= [:columns 5] (op-arg {:create-table :t :columns [[:a :int 5]]})))
    (is (= [:columns "NOT NULL"] (op-arg {:create-table :t :columns [[:a :int "NOT NULL"]]}))))
  (testing "DEFAULT takes exactly one expression; a value with no inline spelling is an inline error in parameter mode"
    (is (= [:columns [:default]] (op-arg {:create-table :t :columns [[:a :int [:default]]]})))
    (is (= [:columns [:default 1 2]] (op-arg {:create-table :t :columns [[:a :int [:default 1 2]]]})))
    (let [obj (Opaque.)
          d (error-of {:create-table :t :columns [[:a :int [:default obj]]]})]
      (is (format-error? d))
      (is (= :inline (:op d)))
      (is (identical? obj (:arg d))))
    (let [d (error-of {:create-table :t :columns [[:a :int [:default ##NaN]]]})]
      (is (= :inline (:op d)))
      (is (NaN? (:arg d))))
    (is (= [:columns #{1}] (op-arg {:create-table :t :columns [[:a :int [:default #{1}]]]}))))
  (testing "CHECK takes one expression vector or raw"
    (is (= [:columns [:check]] (op-arg {:create-table :t :columns [[:a :int [:check]]]})))
    (is (= [:columns [:check "a > 0"]] (op-arg {:create-table :t :columns [[:a :int [:check "a > 0"]]]})))
    (is (= [:columns [:check :a]] (op-arg {:create-table :t :columns [[:a :int [:check :a]]]})))
    (is (= [:columns [:check [:> :a 0] [:< :a 9]]] (op-arg {:create-table :t :columns [[:a :int [:check [:> :a 0] [:< :a 9]]]]})))
    (testing "an expression error inside CHECK keeps the operator as :op"
      (is (= [:> [:> :a]] (op-arg {:create-table :t :columns [[:a :int [:check [:> :a]]]]})))
      (is (= [:in [:in :a []]] (op-arg {:create-table :t :columns [[:a :int [:check [:in :a []]]]]})))))
  (testing "REFERENCES needs the table; the referenced names are plain"
    (is (= [:columns [:references]] (op-arg {:create-table :t :columns [[:a :int [:references]]]})))
    (is (= [:columns "d"] (op-arg {:create-table :t :columns [[:a :int [:references "d"]]]})))
    (is (= [:columns {:select [:a]}] (op-arg {:create-table :t :columns [[:a :int [:references {:select [:a]}]]]})))
    (is (= [:columns :d.id] (op-arg {:create-table :t :columns [[:a :int [:references :d :d.id]]]})))
    (is (= [:columns "id"] (op-arg {:create-table :t :columns [[:a :int [:references :d "id"]]]}))))
  (testing "the referential actions: unknown, wrong arity, and not after REFERENCES"
    (let [d (error-of {:create-table :t :columns [[:a :int [:references :d] [:on-delete :nope]]]})]
      (is (= :columns (:op d)))
      (is (= [:on-delete :nope] (:arg d)))
      (is (= referential-actions (:allowed d))))
    (is (= [:columns [:on-update "cascade"]] (op-arg {:create-table :t :columns [[:a :int [:references :d] [:on-update "cascade"]]]})))
    (is (= [:columns [:on-delete]] (op-arg {:create-table :t :columns [[:a :int [:references :d] [:on-delete]]]})))
    (is (= [:columns [:on-delete :cascade :restrict]] (op-arg {:create-table :t :columns [[:a :int [:references :d] [:on-delete :cascade :restrict]]]})))
    (let [d (error-of {:create-table :t :columns [[:a :int [:on-delete :cascade]]]})]
      (is (= :columns (:op d)))
      (is (= [:on-delete :cascade] (:arg d)))
      (is (re-find #":references" (:message d))))
    (is (= [:columns [:on-update :cascade]] (op-arg {:create-table :t :columns [[:a :int [:on-update :cascade] [:references :d]]]})))
    (is (= [:columns [:on-delete :cascade]] (op-arg {:create-table :t :columns [[:a :int [:references :d] :not-null [:on-delete :cascade]]]}))))
  (testing "a named constraint is [:constraint name constraint], named once"
    (is (= [:columns [:constraint :nm]] (op-arg {:create-table :t :columns [[:a :int [:constraint :nm]]]})))
    (is (= [:columns [:constraint :nm :not-null :unique]] (op-arg {:create-table :t :columns [[:a :int [:constraint :nm :not-null :unique]]]})))
    (is (= [:columns "nm"] (op-arg {:create-table :t :columns [[:a :int [:constraint "nm" :not-null]]]})))
    (is (= [:columns :s.nm] (op-arg {:create-table :t :columns [[:a :int [:constraint :s.nm :not-null]]]})))
    (is (= [:columns :nope] (op-arg {:create-table :t :columns [[:a :int [:constraint :nm :nope]]]})))
    (is (= [:columns [:constraint :a [:constraint :b :not-null]]]
           (op-arg {:create-table :t :columns [[:a :int [:constraint :a [:constraint :b :not-null]]]]})))))

(deftest create-table-constraint-errors
  (testing "an unknown table constraint, with :allowed; a keyword or value in place of a vector"
    (let [d (error-of {:create-table :t :columns [[:a :int]] :constraints [[:nope :a]]})]
      (is (format-error? d))
      (is (= :constraints (:op d)))
      (is (= [:nope :a] (:arg d)))
      (is (= table-constraint-heads (:allowed d))))
    (is (= [:constraints [:not-null :a]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:not-null :a]]})))
    (is (= [:constraints [:references :u]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:references :u]]})))
    (is (= [:constraints :primary-key] (op-arg {:create-table :t :columns [[:a :int]] :constraints [:primary-key]})))
    (is (= [:constraints "PRIMARY KEY (a)"] (op-arg {:create-table :t :columns [[:a :int]] :constraints ["PRIMARY KEY (a)"]})))
    (is (= [:constraints []] (op-arg {:create-table :t :columns [[:a :int]] :constraints []})))
    (is (= [:constraints []] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[]]}))))
  (testing "PRIMARY KEY and UNIQUE need columns, plain ones"
    (is (= [:constraints [:primary-key]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:primary-key]]})))
    (is (= [:constraints [:unique]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:unique]]})))
    (is (= [:constraints "a"] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:primary-key "a"]]})))
    (is (= [:constraints :t.a] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:unique :t.a]]}))))
  (testing "CHECK as a table constraint"
    (is (= [:constraints [:check]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:check]]})))
    (is (= [:constraints [:check "x"]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:check "x"]]}))))
  (testing "FOREIGN KEY: the columns, the references, the actions"
    (is (= [:constraints [:foreign-key]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key]]})))
    (is (= [:constraints [:foreign-key :a]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key :a]]})))
    (is (= [:constraints [:foreign-key :a :u]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key :a :u]]})))
    (is (= [:constraints [:foreign-key "a" [:references :u]]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key "a" [:references :u]]]})))
    (is (= [:constraints [:foreign-key [] [:references :u]]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key [] [:references :u]]]})))
    (is (= [:constraints [:references]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key :a [:references]]]})))
    (is (= [:constraints "u"] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key :a [:references "u"]]]})))
    (is (= [:constraints [:check [:> :a 0]]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key :a [:references :u] [:check [:> :a 0]]]]})))
    (is (= [:constraints :not-null] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key :a [:references :u] :not-null]]})))
    (let [d (error-of {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key :a [:references :u] [:on-delete :nope]]]})]
      (is (= :constraints (:op d)))
      (is (= [:on-delete :nope] (:arg d)))
      (is (= referential-actions (:allowed d))))
    (is (= [:constraints [:foreign-key :a [:constraint :c [:references :u]]]]
           (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key :a [:constraint :c [:references :u]]]]}))))
  (testing "a named table constraint"
    (is (= [:constraints [:constraint :nm]] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:constraint :nm]]})))
    (is (= [:constraints :primary-key] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:constraint :nm :primary-key]]})))
    (is (= [:constraints "nm"] (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:constraint "nm" [:unique :a]]]})))
    (is (= [:constraints [:constraint :a [:constraint :b [:unique :a]]]]
           (op-arg {:create-table :t :columns [[:a :int]] :constraints [[:constraint :a [:constraint :b [:unique :a]]]]})))))

;; ---------------------------------------------------------------------------
;; DROP TABLE, DROP INDEX, DROP VIEW
;; ---------------------------------------------------------------------------

(deftest drop-errors
  (testing "the names"
    (let [d (error-of {:drop-table "t"})]
      (is (format-error? d))
      (is (= :drop-table (:op d)))
      (is (= "t" (:arg d))))
    (is (= [:drop-table []] (op-arg {:drop-table []})))
    (is (= [:drop-table {:select [:a]}] (op-arg {:drop-table {:select [:a]}})))
    (is (= [:drop-table "u"] (op-arg {:drop-table [:t "u"]})))
    (is (= [:drop-index nil] (op-arg {:drop-index nil})))
    (is (= [:drop-view "V"] (op-arg {:drop-view :V}))))
  (testing "the flags, and contradictory flags"
    (is (= [:if-exists 1] (op-arg {:drop-table :t :if-exists 1})))
    (is (= [:cascade "yes"] (op-arg {:drop-view :v :cascade "yes"})))
    (is (= [:concurrently "yes"] (op-arg {:drop-index :i :concurrently "yes"})))
    (let [d (error-of {:drop-table :t :cascade true :restrict true})]
      (is (= :drop-table (:op d)))
      (is (= {:cascade true :restrict true} (:arg d))))
    (is (= [:drop-index {:cascade true :restrict true}] (op-arg {:drop-index :i :cascade true :restrict true})))
    (is (= [:drop-view {:cascade true :restrict true}] (op-arg {:drop-view :v :cascade true :restrict true}))))
  (testing "DROP INDEX CONCURRENTLY: no CASCADE, exactly one name"
    (let [d (error-of {:drop-index :i :concurrently true :cascade true})]
      (is (format-error? d))
      (is (= :drop-index (:op d)))
      (is (= {:concurrently true :cascade true} (:arg d)))
      (is (re-find #"CONCURRENTLY" (:message d))))
    (let [d (error-of {:drop-index [:i :j] :concurrently true})]
      (is (= :drop-index (:op d)))
      (is (= [:i :j] (:arg d)))
      (is (re-find #"exactly one" (:message d))))
    (is (= [:drop-index {:concurrently true :cascade true}] (op-arg {:drop-index [:i :j] :concurrently true :cascade true}))))
  (testing "clauses that do not apply, with :allowed"
    (let [d (error-of {:drop-table :t :from [:u]})]
      (is (= :drop-table (:op d)))
      (is (= :from (:arg d)))
      (is (= [:cascade :drop-table :if-exists :restrict] (:allowed d))))
    (is (= [:drop-table :concurrently] (op-arg {:drop-table :t :concurrently true})))
    (is (= [:drop-table :if-not-exists] (op-arg {:drop-table :t :if-not-exists true})))
    (let [d (error-of {:drop-index :i :where [:= :a 1]})]
      (is (= :drop-index (:op d)))
      (is (= :where (:arg d)))
      (is (= [:cascade :concurrently :drop-index :if-exists :restrict] (:allowed d))))
    (let [d (error-of {:drop-view :v :returning [:*]})]
      (is (= :drop-view (:op d)))
      (is (= :returning (:arg d)))
      (is (= [:cascade :drop-view :if-exists :restrict] (:allowed d))))
    (is (= [:drop-view :concurrently] (op-arg {:drop-view :v :concurrently true})))
    (is (= [:drop-view :restart-identity] (op-arg {:drop-view :v :restart-identity true})))))

;; ---------------------------------------------------------------------------
;; ALTER TABLE
;; ---------------------------------------------------------------------------

(deftest alter-table-errors
  (testing "ALTER TABLE needs :actions, a non-empty vector of action vectors"
    (let [d (error-of {:alter-table :t})]
      (is (format-error? d))
      (is (= :alter-table (:op d)))
      (is (= {:alter-table :t} (:arg d)))
      (is (re-find #":actions" (:message d))))
    (is (= [:actions []] (op-arg {:alter-table :t :actions []})))
    (is (= [:actions :add-column] (op-arg {:alter-table :t :actions :add-column})))
    (is (= [:actions :add-column] (op-arg {:alter-table :t :actions [:add-column]})))
    (is (= [:actions []] (op-arg {:alter-table :t :actions [[]]})))
    (is (= [:actions {:add-column [:a :int]}] (op-arg {:alter-table :t :actions [{:add-column [:a :int]}]})))
    (is (= [:actions "DROP COLUMN a"] (op-arg {:alter-table :t :actions ["DROP COLUMN a"]}))))
  (testing "the target and the flag"
    (is (= [:alter-table "t"] (op-arg {:alter-table "t" :actions [[:drop-column :a]]})))
    (is (= [:alter-table {:select [:a]}] (op-arg {:alter-table {:select [:a]} :actions [[:drop-column :a]]})))
    (is (= [:if-exists "yes"] (op-arg {:alter-table :t :if-exists "yes" :actions [[:drop-column :a]]}))))
  (testing "an unknown action, with :allowed"
    (let [d (error-of {:alter-table :t :actions [[:nope :a]]})]
      (is (= :actions (:op d)))
      (is (= [:nope :a] (:arg d)))
      (is (= [:add-column :drop-column :rename-column] (:allowed d))))
    (is (= [:actions [:alter-column :a]] (op-arg {:alter-table :t :actions [[:alter-column :a]]})))
    (is (= [:actions [:rename-to :u]] (op-arg {:alter-table :t :actions [[:rename-to :u]]}))))
  (testing "ADD COLUMN: the definition, its options, and the column errors with the action as :op"
    (is (= [:add-column [:add-column]] (op-arg {:alter-table :t :actions [[:add-column]]})))
    (is (= [:add-column :a] (op-arg {:alter-table :t :actions [[:add-column :a]]})))
    (is (= [:add-column [:a]] (op-arg {:alter-table :t :actions [[:add-column [:a]]]})))
    (is (= [:add-column "text"] (op-arg {:alter-table :t :actions [[:add-column [:a "text"]]]})))
    (is (= [:add-column "a"] (op-arg {:alter-table :t :actions [[:add-column ["a" :text]]]})))
    (is (= [:add-column :t.a] (op-arg {:alter-table :t :actions [[:add-column [:t.a :text]]]})))
    (let [d (error-of {:alter-table :t :actions [[:add-column [:a :int :nope]]]})]
      (is (= :add-column (:op d)))
      (is (= :nope (:arg d)))
      (is (= column-constraint-words (:allowed d))))
    (is (= [:add-column [:on-delete :cascade]] (op-arg {:alter-table :t :actions [[:add-column [:a :int [:on-delete :cascade]]]]})))
    (let [d (error-of {:alter-table :t :actions [[:add-column [:a :int] :if-exists]]})]
      (is (= :add-column (:op d)))
      (is (= :if-exists (:arg d)))
      (is (= [:if-not-exists] (:allowed d))))
    (is (= [:add-column :cascade] (op-arg {:alter-table :t :actions [[:add-column [:a :int] :cascade]]})))
    (is (= [:add-column [:add-column [:a :int] :if-not-exists :if-not-exists]]
           (op-arg {:alter-table :t :actions [[:add-column [:a :int] :if-not-exists :if-not-exists]]}))))
  (testing "DROP COLUMN: the name, its options"
    (is (= [:drop-column [:drop-column]] (op-arg {:alter-table :t :actions [[:drop-column]]})))
    (is (= [:drop-column "a"] (op-arg {:alter-table :t :actions [[:drop-column "a"]]})))
    (is (= [:drop-column :t.a] (op-arg {:alter-table :t :actions [[:drop-column :t.a]]})))
    (is (= [:drop-column [:a :b]] (op-arg {:alter-table :t :actions [[:drop-column [:a :b]]]})))
    (let [d (error-of {:alter-table :t :actions [[:drop-column :a :if-not-exists]]})]
      (is (= :drop-column (:op d)))
      (is (= :if-not-exists (:arg d)))
      (is (= [:if-exists :cascade :restrict] (:allowed d))))
    (is (= [:drop-column [:drop-column :a :cascade :restrict]] (op-arg {:alter-table :t :actions [[:drop-column :a :cascade :restrict]]})))
    (is (= [:drop-column [:drop-column :a :cascade :cascade]] (op-arg {:alter-table :t :actions [[:drop-column :a :cascade :cascade]]}))))
  (testing "RENAME COLUMN: exactly two plain names, and never combined"
    (is (= [:rename-column [:rename-column :a]] (op-arg {:alter-table :t :actions [[:rename-column :a]]})))
    (is (= [:rename-column [:rename-column :a :b :c]] (op-arg {:alter-table :t :actions [[:rename-column :a :b :c]]})))
    (is (= [:rename-column "b"] (op-arg {:alter-table :t :actions [[:rename-column :a "b"]]})))
    (is (= [:rename-column :t.a] (op-arg {:alter-table :t :actions [[:rename-column :t.a :b]]})))
    (let [d (error-of {:alter-table :t :actions [[:rename-column :a :b] [:drop-column :c]]})]
      (is (= :actions (:op d)))
      (is (= [[:rename-column :a :b] [:drop-column :c]] (:arg d)))
      (is (re-find #"RENAME COLUMN" (:message d))))
    (is (= [:actions [[:rename-column :a :b] [:rename-column :c :d]]]
           (op-arg {:alter-table :t :actions [[:rename-column :a :b] [:rename-column :c :d]]}))))
  (testing "clauses that do not apply, with :allowed"
    (let [d (error-of {:alter-table :t :actions [[:drop-column :a]] :set {:a 1}})]
      (is (= :alter-table (:op d)))
      (is (= :set (:arg d)))
      (is (= [:actions :alter-table :if-exists] (:allowed d))))
    (is (= [:alter-table :columns] (op-arg {:alter-table :t :actions [[:drop-column :a]] :columns [[:a :int]]})))
    (is (= [:alter-table :cascade] (op-arg {:alter-table :t :actions [[:drop-column :a]] :cascade true})))))

;; ---------------------------------------------------------------------------
;; CREATE INDEX
;; ---------------------------------------------------------------------------

(deftest create-index-errors
  (testing "CREATE INDEX needs :on and :columns"
    (let [d (error-of {:create-index :i :columns [:a]})]
      (is (format-error? d))
      (is (= :create-index (:op d)))
      (is (= {:create-index :i :columns [:a]} (:arg d)))
      (is (re-find #":on" (:message d))))
    (let [d (error-of {:create-index :i :on :t})]
      (is (= :create-index (:op d)))
      (is (re-find #":columns" (:message d))))
    (is (= [:create-index {:create-index nil}] (op-arg {:create-index nil}))))
  (testing "the index name is plain: unqualified, never a value; IF NOT EXISTS needs a name"
    (is (= [:create-index "i"] (op-arg {:create-index "i" :on :t :columns [:a]})))
    (is (= [:create-index :public/i] (op-arg {:create-index :public/i :on :t :columns [:a]})))
    (is (= [:create-index "I"] (op-arg {:create-index :I :on :t :columns [:a]})))
    (is (= [:create-index [:i]] (op-arg {:create-index [:i] :on :t :columns [:a]})))
    (let [d (error-of {:create-index nil :if-not-exists true :on :t :columns [:a]})]
      (is (= :create-index (:op d)))
      (is (= {:create-index nil :if-not-exists true :on :t :columns [:a]} (:arg d)))
      (is (re-find #"IF NOT EXISTS" (:message d)))))
  (testing "the table"
    (is (= [:on "t"] (op-arg {:create-index :i :on "t" :columns [:a]})))
    (is (= [:on {:select [:a]}] (op-arg {:create-index :i :on {:select [:a]} :columns [:a]})))
    (is (= [:on [:t :x]] (op-arg {:create-index :i :on [:t :x] :columns [:a]})))
    (is (= [:on nil] (op-arg {:create-index :i :on nil :columns [:a]}))))
  (testing "the method: one entity or raw"
    (let [d (error-of {:create-index :i :on :t :using "gin" :columns [:a]})]
      (is (= :using (:op d)))
      (is (= "gin" (:arg d))))
    (is (= [:using [:a :b]] (op-arg {:create-index :i :on :t :using [:a :b] :columns [:a]})))
    (is (= [:using nil] (op-arg {:create-index :i :on :t :using nil :columns [:a]})))
    (is (= [:using "Gin"] (op-arg {:create-index :i :on :t :using :Gin :columns [:a]}))))
  (testing "the columns: order-by items with :columns as :op"
    (is (= [:columns []] (op-arg {:create-index :i :on :t :columns []})))
    (is (= [:columns "a"] (op-arg {:create-index :i :on :t :columns ["a"]})))
    (is (= [:columns "a"] (op-arg {:create-index :i :on :t :columns "a"})))
    (let [d (error-of {:create-index :i :on :t :columns [[:a :nope]]})]
      (is (= :columns (:op d)))
      (is (= [:a :nope] (:arg d)))
      (is (= [:asc :desc :nulls-first :nulls-last] (:allowed d))))
    (is (= [:columns [:a :asc :desc]] (op-arg {:create-index :i :on :t :columns [[:a :asc :desc]]})))
    (is (= [:columns [:a :nulls-first :nulls-last]] (op-arg {:create-index :i :on :t :columns [[:a :nulls-first :nulls-last]]})))
    (is (= [:columns :desc] (op-arg {:create-index :i :on :t :columns [:desc]})))
    (is (= [:columns []] (op-arg {:create-index :i :on :t :columns [[]]})))
    (is (= [:columns 1] (op-arg {:create-index :i :on :t :columns [[1 :desc]]})))
    (is (= [:columns 1] (op-arg {:create-index :i :on :t :columns [1]})))
    (is (= [:columns nil] (op-arg {:create-index :i :on :t :columns [nil]})))
    (testing "an expression error inside a column keeps its own :op"
      (is (= [:raw [:raw 5]] (op-arg {:create-index :i :on :t :columns [[[:raw 5]]]})))
      (is (= [:> [:> :a]] (op-arg {:create-index :i :on :t :columns [[[:> :a] :desc]]})))))
  (testing "the predicate and the flags"
    (is (= [:where :active] (op-arg {:create-index :i :on :t :columns [:a] :where :active})))
    (is (= [:where "a > 0"] (op-arg {:create-index :i :on :t :columns [:a] :where "a > 0"})))
    (is (= [:unique 1] (op-arg {:create-index :i :unique 1 :on :t :columns [:a]})))
    (is (= [:concurrently "yes"] (op-arg {:create-index :i :concurrently "yes" :on :t :columns [:a]})))
    (is (= [:if-not-exists :yes] (op-arg {:create-index :i :if-not-exists :yes :on :t :columns [:a]}))))
  (testing "an inline error in the predicate, in parameter mode"
    (let [obj (Opaque.)
          d (error-of {:create-index :i :on :t :columns [:a] :where [:= :a obj]})]
      (is (= :inline (:op d)))
      (is (identical? obj (:arg d)))))
  (testing "clauses that do not apply, with :allowed"
    (let [d (error-of {:create-index :i :on :t :columns [:a] :from [:t]})]
      (is (= :create-index (:op d)))
      (is (= :from (:arg d)))
      (is (= [:columns :concurrently :create-index :if-not-exists :on :unique :using :where] (:allowed d))))
    (is (= [:create-index :order-by] (op-arg {:create-index :i :on :t :columns [:a] :order-by [:a]})))
    (is (= [:create-index :if-exists] (op-arg {:create-index :i :on :t :columns [:a] :if-exists true})))
    (is (= [:create-index :cascade] (op-arg {:create-index :i :on :t :columns [:a] :cascade true})))))

;; ---------------------------------------------------------------------------
;; CREATE VIEW
;; ---------------------------------------------------------------------------

(deftest create-view-errors
  (testing "CREATE VIEW needs :as, a statement map or raw"
    (let [d (error-of {:create-view :v})]
      (is (format-error? d))
      (is (= :create-view (:op d)))
      (is (= {:create-view :v} (:arg d)))
      (is (re-find #":as" (:message d))))
    (let [d (error-of {:create-view :v :as "SELECT 1"})]
      (is (= :as (:op d)))
      (is (= "SELECT 1" (:arg d))))
    (is (= [:as [:select :a]] (op-arg {:create-view :v :as [:select :a]})))
    (is (= [:as nil] (op-arg {:create-view :v :as nil})))
    (is (= [:as :q] (op-arg {:create-view :v :as :q}))))
  (testing "the body is validated as a statement"
    (is (= [:select []] (op-arg {:create-view :v :as {:select []}})))
    (is (= [:statement {}] (op-arg {:create-view :v :as {}})))
    (is (= [:from "t"] (op-arg {:create-view :v :as {:select [:a] :from ["t"]}})))
    (is (= [:update {:update :t}] (op-arg {:create-view :v :as {:update :t}}))))
  (testing "the name, the columns, the flag"
    (is (= [:create-view "v"] (op-arg {:create-view "v" :as {:select [:a]}})))
    (is (= [:create-view {:select [:a]}] (op-arg {:create-view {:select [:a]} :as {:select [:a]}})))
    (is (= [:columns "a"] (op-arg {:create-view :v :columns ["a"] :as {:select [:a]}})))
    (is (= [:columns :t.a] (op-arg {:create-view :v :columns [:t.a] :as {:select [:a]}})))
    (is (= [:columns []] (op-arg {:create-view :v :columns [] :as {:select [:a]}})))
    (is (= [:or-replace 1] (op-arg {:create-view :v :or-replace 1 :as {:select [:a]}}))))
  (testing "clauses that do not apply, with :allowed"
    (let [d (error-of {:create-view :v :as {:select [:a]} :from [:t]})]
      (is (= :create-view (:op d)))
      (is (= :from (:arg d)))
      (is (= [:as :columns :create-view :or-replace] (:allowed d))))
    (is (= [:create-view :where] (op-arg {:create-view :v :as {:select [:a]} :where [:= :a 1]})))
    (is (= [:create-view :if-not-exists] (op-arg {:create-view :v :as {:select [:a]} :if-not-exists true})))
    (is (= [:create-view :with] (op-arg {:create-view :v :as {:select [:a]} :with [[:c {:select [:a]}]]})))))
