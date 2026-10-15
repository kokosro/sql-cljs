(ns sql-cljs.helpers-test
  "The helpers battery: every builder's map pinned exactly, composition by
   threading and by merge, and the data formatting identically to the
   literal map."
  (:require [cljs.test :refer [deftest is testing]]
            [sql-cljs.core :as sql]
            [sql-cljs.helpers :as h]))

;; ---------------------------------------------------------------------------
;; Each helper, the map it builds
;; ---------------------------------------------------------------------------

(deftest query-helpers
  (testing "select, distinct, distinct-on"
    (is (= {:select [:a]} (h/select :a)))
    (is (= {:select [:a :b]} (h/select :a :b)))
    (is (= {:select [[[:count :*] :n] :a]} (h/select [[:count :*] :n] :a)))
    (is (= {:select []} (h/select)))
    (is (= {:distinct true} (h/distinct)))
    (is (= {:distinct-on [:a :b]} (h/distinct-on :a :b))))
  (testing "from and the joins"
    (is (= {:from [:t]} (h/from :t)))
    (is (= {:from [:t [:u :x]]} (h/from :t [:u :x])))
    (is (= {:join [:u [:= :t.id :u.t_id]]} (h/join :u [:= :t.id :u.t_id])))
    (is (= {:join [:u [:using :id] :v [:= :a :b]]} (h/join :u [:using :id] :v [:= :a :b])))
    (is (= {:inner-join [:u [:using :id]]} (h/inner-join :u [:using :id])))
    (is (= {:left-join [:u [:using :id]]} (h/left-join :u [:using :id])))
    (is (= {:right-join [:u [:using :id]]} (h/right-join :u [:using :id])))
    (is (= {:full-join [:u [:using :id]]} (h/full-join :u [:using :id])))
    (is (= {:cross-join [:u :v]} (h/cross-join :u :v))))
  (testing "where and having: one predicate stands alone, several combine with :and"
    (is (= {:where [:= :a 1]} (h/where [:= :a 1])))
    (is (= {:where [:and [:= :a 1] [:= :b 2]]} (h/where [:= :a 1] [:= :b 2])))
    (is (= {:having [:> [:count :*] 1]} (h/having [:> [:count :*] 1])))
    (is (= {:having [:and [:> :a 1] [:< :a 9]]} (h/having [:> :a 1] [:< :a 9])))
    (is (= {} (h/where))))
  (testing "group-by, order-by, limit, offset"
    (is (= {:group-by [:a :b]} (h/group-by :a :b)))
    (is (= {:order-by [:a [:b :desc]]} (h/order-by :a [:b :desc])))
    (is (= {:limit 10} (h/limit 10)))
    (is (= {:offset 20} (h/offset 20))))
  (testing "union and union-all take operands only"
    (is (= {:union [{:select [:a]} {:select [:b]}]} (h/union {:select [:a]} {:select [:b]})))
    (is (= {:union-all [{:select [:a]} {:select [:b]}]} (h/union-all {:select [:a]} {:select [:b]}))))
  (testing "with, with-recursive, for"
    (is (= {:with [[:c {:select [:a]}]]} (h/with [:c {:select [:a]}])))
    (is (= {:with [[:c {:select [:a]}] [[:d :x] {:select [:b]}]]}
           (h/with [:c {:select [:a]}] [[:d :x] {:select [:b]}])))
    (is (= {:with-recursive [[:c {:select [:a]}]]} (h/with-recursive [:c {:select [:a]}])))
    (is (= {:for [:update]} (h/for :update)))
    (is (= {:for [:update :t :nowait]} (h/for :update :t :nowait)))))

(deftest insert-helpers
  (is (= {:insert-into :t} (h/insert-into :t)))
  (is (= {:insert-into [:t :d]} (h/insert-into [:t :d])))
  (is (= {:columns [:a :b]} (h/columns :a :b)))
  (is (= {:values [[1 2] [3 4]]} (h/values [[1 2] [3 4]])))
  (is (= {:values [{:a 1} {:a 2}]} (h/values [{:a 1} {:a 2}])))
  (is (= {:values {:select [:a] :from [:u]}} (h/values {:select [:a] :from [:u]})))
  (is (= {:on-conflict {:on-columns [:id]}} (h/on-conflict :id)))
  (is (= {:on-conflict {:on-columns [:a [:lower :email]]}} (h/on-conflict :a [:lower :email])))
  (is (= {:on-conflict {}} (h/on-conflict)))
  (is (= {:on-conflict {:on-constraint :t_pkey}} (h/on-constraint :t_pkey)))
  (is (= {:on-conflict {:index-where [:is-null :deleted_at]}} (h/index-where [:is-null :deleted_at])))
  (is (= {:on-conflict {:index-where [:and [:is-null :a] [:= :b 1]]}} (h/index-where [:is-null :a] [:= :b 1])))
  (is (= {:on-conflict {:do-nothing true}} (h/do-nothing)))
  (is (= {:on-conflict {:do-update-set {:a :excluded/a}}} (h/do-update-set {:a :excluded/a})))
  (is (= {:on-conflict {:do-update-set [[:a :excluded/a] [:b 1]]}} (h/do-update-set [[:a :excluded/a] [:b 1]])))
  (is (= {:on-conflict {:where [:<> :t.a :excluded/a]}} (h/do-update-where [:<> :t.a :excluded/a])))
  (is (= {:returning [:id]} (h/returning :id)))
  (is (= {:returning [:*]} (h/returning :*)))
  (is (= {:returning [:id [[:lower :name] :n]]} (h/returning :id [[:lower :name] :n]))))

(deftest update-delete-truncate-helpers
  (is (= {:update :t} (h/update :t)))
  (is (= {:update [:t :x]} (h/update [:t :x])))
  (is (= {:set {:a 1 :b :c}} (h/set {:a 1 :b :c})))
  (is (= {:set [[:a 1] [:b :c]]} (h/set [[:a 1] [:b :c]])))
  (is (= {:delete-from :t} (h/delete-from :t)))
  (is (= {:using [:u [:v :x]]} (h/using :u [:v :x])))
  (is (= {:truncate [:t]} (h/truncate :t)))
  (is (= {:truncate [:t :u]} (h/truncate :t :u)))
  (is (= {:restart-identity true} (h/restart-identity)))
  (is (= {:continue-identity true} (h/continue-identity)))
  (is (= {:cascade true} (h/cascade)))
  (is (= {:restrict true} (h/restrict))))

;; ---------------------------------------------------------------------------
;; Composition
;; ---------------------------------------------------------------------------

(deftest threading-and-merging
  (testing "a leading map is the statement being built"
    (is (= {:select [:a] :from [:t] :where [:= :a 1]}
           (-> (h/select :a) (h/from :t) (h/where [:= :a 1]))))
    (is (= {:select [:a] :from [:t] :where [:= :a 1]}
           (merge (h/select :a) (h/from :t) (h/where [:= :a 1]))))
    (is (= (-> (h/from :t) (h/select :a)) (-> (h/select :a) (h/from :t)))))
  (testing "a repeated vector clause concatenates"
    (is (= {:select [:a :b :c]} (-> (h/select :a) (h/select :b :c))))
    (is (= {:from [:t :u]} (-> (h/from :t) (h/from :u))))
    (is (= {:values [[1] [2]]} (-> (h/values [[1]]) (h/values [[2]]))))
    (is (= {:returning [:id :a]} (-> (h/returning :id) (h/returning :a))))
    (is (= {:truncate [:t :u]} (-> (h/truncate :t) (h/truncate :u))))
    (is (= {:join [:u [:using :id] :v [:using :id]]} (-> (h/join :u [:using :id]) (h/join :v [:using :id])))))
  (testing "a repeated where or having combines with :and, flattening an existing :and"
    (is (= {:where [:and [:= :a 1] [:= :b 2]]} (-> (h/where [:= :a 1]) (h/where [:= :b 2]))))
    (is (= {:where [:and [:= :a 1] [:= :b 2] [:= :c 3]]}
           (-> (h/where [:= :a 1]) (h/where [:= :b 2]) (h/where [:= :c 3]))))
    (is (= {:where [:and [:or [:= :a 1] [:= :a 2]] [:= :b 2]]}
           (-> (h/where [:or [:= :a 1] [:= :a 2]]) (h/where [:= :b 2]))))
    (is (= {:having [:and [:> :a 1] [:< :a 9]]} (-> (h/having [:> :a 1]) (h/having [:< :a 9]))))
    (is (= {:select [:a]} (-> (h/select :a) (h/where)))))
  (testing "a repeated map clause merges, so the on-conflict helpers build one map"
    (is (= {:insert-into :t :values [[1 "x"]]
            :on-conflict {:on-columns [:id] :do-update-set {:name :excluded/name} :where [:<> :t.name :excluded/name]}}
           (-> (h/insert-into :t) (h/values [[1 "x"]])
               (h/on-conflict :id) (h/do-update-set {:name :excluded/name})
               (h/do-update-where [:<> :t.name :excluded/name]))))
    (is (= {:on-conflict {:on-columns [:email] :index-where [:is-null :deleted_at] :do-nothing true}}
           (-> (h/on-conflict :email) (h/index-where [:is-null :deleted_at]) (h/do-nothing))))
    (is (= {:on-conflict {:on-constraint :t_pkey :do-nothing true}} (-> (h/on-constraint :t_pkey) (h/do-nothing))))
    (is (= {:on-conflict {:do-nothing true}} (-> (h/on-conflict) (h/do-nothing))))
    (is (= {:set {:a 1 :b 2}} (-> (h/set {:a 1}) (h/set {:b 2}))))
    (is (= {:set [[:a 1] [:b 2]]} (-> (h/set [[:a 1]]) (h/set [[:b 2]])))))
  (testing "a scalar clause replaces"
    (is (= {:limit 5} (-> (h/limit 10) (h/limit 5))))
    (is (= {:update :u} (-> (h/update :t) (h/update :u))))
    (is (= {:set [[:b 2]]} (-> (h/set {:a 1}) (h/set [[:b 2]]))))
    (is (= {:truncate [:t] :cascade true} (-> (h/truncate :t) (h/cascade) (h/cascade)))))
  (testing "the result is plain data: dissoc and assoc work"
    (is (= {:select [:b] :from [:t]} (-> (h/select :a) (h/from :t) (dissoc :select) (h/select :b))))
    (is (= {:select [:a] :limit 1} (-> (h/select :a) (assoc :limit 1))))))

;; ---------------------------------------------------------------------------
;; The helpers' data formats exactly like the literal map
;; ---------------------------------------------------------------------------

(deftest helpers-format-like-literal-maps
  (testing "a query"
    (let [built (-> (h/select :a [[:count :*] :n]) (h/from :t) (h/left-join :u [:using :id])
                    (h/where [:= :a 1] [:> :b 2]) (h/group-by :a) (h/having [:> [:count :*] 1])
                    (h/order-by [:a :desc]) (h/limit 10) (h/offset 20))
          literal {:select [:a [[:count :*] :n]] :from [:t] :left-join [:u [:using :id]]
                   :where [:and [:= :a 1] [:> :b 2]] :group-by [:a] :having [:> [:count :*] 1]
                   :order-by [[:a :desc]] :limit 10 :offset 20}]
      (is (= literal built))
      (is (= {:text "SELECT a, COUNT(*) AS n FROM t LEFT JOIN u USING (id) WHERE (a = $1) AND (b > $2) GROUP BY a HAVING COUNT(*) > $3 ORDER BY a DESC LIMIT $4 OFFSET $5"
              :values [1 2 1 10 20]}
             (sql/format built)
             (sql/format literal)))))
  (testing "an upsert"
    (let [built (-> (h/insert-into [:t :d]) (h/columns :id :name) (h/values [[1 "x"]])
                    (h/on-conflict :id) (h/do-update-set {:name :excluded/name})
                    (h/do-update-where [:<> :d.name :excluded/name]) (h/returning :id))]
      (is (= {:text "INSERT INTO t AS d (id, name) VALUES ($1, $2) ON CONFLICT (id) DO UPDATE SET name = excluded.name WHERE d.name <> excluded.name RETURNING id"
              :values [1 "x"]}
             (sql/format built)))))
  (testing "an insert from maps, an INSERT ... SELECT, an insert that does nothing"
    (is (= "INSERT INTO t (a, b) VALUES ($1, $2), ($3, $4)"
           (:text (sql/format (-> (h/insert-into :t) (h/values [{:a 1 :b "x"} {:a 2 :b "y"}]))))))
    (is (= "INSERT INTO t (a) SELECT a FROM u"
           (:text (sql/format (-> (h/insert-into :t) (h/columns :a) (h/values (-> (h/select :a) (h/from :u))))))))
    (is (= "INSERT INTO t VALUES ($1) ON CONFLICT DO NOTHING"
           (:text (sql/format (-> (h/insert-into :t) (h/values [[1]]) (h/on-conflict) (h/do-nothing)))))))
  (testing "an update, a delete, a truncate"
    (is (= {:text "UPDATE t SET a = $1, b = c FROM u WHERE t.id = u.id RETURNING *" :values [1]}
           (sql/format (-> (h/update :t) (h/set [[:a 1] [:b :c]]) (h/from :u) (h/where [:= :t.id :u.id]) (h/returning :*)))))
    (is (= {:text "DELETE FROM t USING u WHERE (t.id = u.id) AND (u.x = $1) RETURNING id" :values [1]}
           (sql/format (-> (h/delete-from :t) (h/using :u) (h/where [:= :t.id :u.id]) (h/where [:= :u.x 1]) (h/returning :id)))))
    (is (= {:text "TRUNCATE TABLE t, u RESTART IDENTITY CASCADE" :values []}
           (sql/format (-> (h/truncate :t :u) (h/restart-identity) (h/cascade))))))
  (testing "a set operation and a CTE"
    (is (= "(SELECT a FROM t) UNION ALL (SELECT a FROM u) ORDER BY a"
           (:text (sql/format (-> (h/union-all (-> (h/select :a) (h/from :t)) (-> (h/select :a) (h/from :u)))
                                  (h/order-by :a))))))
    (is (= "WITH c AS (SELECT a FROM t) SELECT a FROM c"
           (:text (sql/format (-> (h/with [:c (-> (h/select :a) (h/from :t))]) (h/select :a) (h/from :c)))))))
  (testing "malformed helper data fails at format time, not in the helper"
    (is (= {:select ["a"]} (h/select "a")))
    (is (= [:select "a"] ((juxt :op :arg) (try (sql/format (h/select "a")) nil (catch :default e (ex-data e))))))
    (is (= [:update {:update :t}] ((juxt :op :arg) (try (sql/format (h/update :t)) nil (catch :default e (ex-data e))))))))

;; ---------------------------------------------------------------------------
;; Data definition helpers
;; ---------------------------------------------------------------------------

(deftest ddl-helpers
  (testing "create-table, if-not-exists, columns, constraints"
    (is (= {:create-table :t} (h/create-table :t)))
    (is (= {:if-not-exists true} (h/if-not-exists)))
    (is (= {:columns [[:id :serial :primary-key] [:name [:varchar 40] :not-null]]}
           (h/columns [:id :serial :primary-key] [:name [:varchar 40] :not-null])))
    (is (= {:constraints [[:primary-key :a :b]]} (h/constraints [:primary-key :a :b])))
    (is (= {:constraints [[:unique :a] [:check [:> :a 0]]]} (h/constraints [:unique :a] [:check [:> :a 0]]))))
  (testing "drop-table, if-exists"
    (is (= {:drop-table [:t]} (h/drop-table :t)))
    (is (= {:drop-table [:t :u]} (h/drop-table :t :u)))
    (is (= {:if-exists true} (h/if-exists))))
  (testing "alter-table and its actions"
    (is (= {:alter-table :t} (h/alter-table :t)))
    (is (= {:actions [[:add-column [:x :int]]]} (h/add-column [:x :int])))
    (is (= {:actions [[:add-column [:x :int :not-null] :if-not-exists]]} (h/add-column [:x :int :not-null] :if-not-exists)))
    (is (= {:actions [[:drop-column :x]]} (h/drop-column :x)))
    (is (= {:actions [[:drop-column :x :if-exists :cascade]]} (h/drop-column :x :if-exists :cascade)))
    (is (= {:actions [[:rename-column :a :b]]} (h/rename-column :a :b))))
  (testing "create-index, unique, concurrently, on, using, columns, where"
    (is (= {:create-index :i} (h/create-index :i)))
    (is (= {:create-index nil} (h/create-index)))
    (is (= {:unique true} (h/unique)))
    (is (= {:concurrently true} (h/concurrently)))
    (is (= {:on :t} (h/on :t)))
    (is (= {:using [:gin]} (h/using :gin)))
    (is (= {:columns [:a [:b :desc]]} (h/columns :a [:b :desc])))
    (is (= {:where [:= :kind "x"]} (h/where [:= :kind "x"]))))
  (testing "drop-index, create-view, or-replace, as, drop-view"
    (is (= {:drop-index [:i]} (h/drop-index :i)))
    (is (= {:drop-index [:i :j]} (h/drop-index :i :j)))
    (is (= {:create-view :v} (h/create-view :v)))
    (is (= {:or-replace true} (h/or-replace)))
    (is (= {:as {:select [:a] :from [:t]}} (h/as {:select [:a] :from [:t]})))
    (is (= {:as (sql/raw "SELECT 1")} (h/as (sql/raw "SELECT 1"))))
    (is (= {:drop-view [:v]} (h/drop-view :v)))
    (is (= {:drop-view [:v :w]} (h/drop-view :v :w)))))

(deftest ddl-helpers-thread-and-merge
  (testing "a leading map threads; columns, constraints and actions concatenate; flags and scalars replace"
    (is (= {:create-table :t :if-not-exists true :columns [[:id :serial :primary-key] [:name :text]] :constraints [[:unique :name]]}
           (-> (h/create-table :t) (h/if-not-exists) (h/columns [:id :serial :primary-key]) (h/columns [:name :text]) (h/constraints [:unique :name]))))
    (is (= {:alter-table :t :if-exists true :actions [[:add-column [:a :int]] [:drop-column :b :if-exists] [:add-column [:c :text] :if-not-exists]]}
           (-> (h/alter-table :t) (h/if-exists) (h/add-column [:a :int]) (h/drop-column :b :if-exists) (h/add-column [:c :text] :if-not-exists))))
    (is (= {:alter-table :t :actions [[:rename-column :a :b]]} (-> (h/alter-table :t) (h/rename-column :a :b))))
    (is (= {:create-index :i :unique true :concurrently true :on :t :using [:gin] :columns [:a [:b :desc]] :where [:and [:= :kind "x"] [:> :n 1]]}
           (-> (h/create-index :i) (h/unique) (h/concurrently) (h/on :t) (h/using :gin) (h/columns :a [:b :desc]) (h/where [:= :kind "x"]) (h/where [:> :n 1]))))
    (is (= {:on :t :columns [:a] :create-index nil} (-> (h/on :t) (h/columns :a) (h/create-index))))
    (is (= {:create-index :j} (-> (h/create-index :i) (h/create-index :j))))
    (is (= {:drop-index [:i :j] :concurrently true} (-> (h/drop-index :i) (h/drop-index :j) (h/concurrently))))
    (is (= {:create-view :v :or-replace true :columns [:x :y] :as {:select [:a :b] :from [:t]}}
           (-> (h/create-view :v) (h/or-replace) (h/columns :x :y) (h/as (-> (h/select :a :b) (h/from :t))))))
    (is (= {:drop-view [:v :w] :if-exists true :cascade true} (-> (h/drop-view :v :w) (h/if-exists) (h/cascade))))
    (is (= {:drop-table [:t] :restrict true} (-> (h/drop-table :t) (h/restrict) (h/restrict))))
    (is (= {:create-table :t :columns [[:a :int]]} (merge (h/create-table :t) (h/columns [:a :int]))))))

(deftest ddl-helpers-format-like-literal-maps
  (testing "a table"
    (let [built (-> (h/create-table :films) (h/if-not-exists)
                    (h/columns [:code [:char 5] [:constraint :firstkey :primary-key]] [:title [:varchar 40] :not-null]
                               [:did :integer [:references :distributors :did] [:on-delete :cascade]] [:kind :text [:default "drama"]])
                    (h/constraints [:constraint :production [:unique :title :did]] [:check [:<> :title ""]]))
          literal {:create-table :films :if-not-exists true
                   :columns [[:code [:char 5] [:constraint :firstkey :primary-key]] [:title [:varchar 40] :not-null]
                             [:did :integer [:references :distributors :did] [:on-delete :cascade]] [:kind :text [:default "drama"]]]
                   :constraints [[:constraint :production [:unique :title :did]] [:check [:<> :title ""]]]}]
      (is (= literal built))
      (is (= {:text "CREATE TABLE IF NOT EXISTS films (code CHAR(5) CONSTRAINT firstkey PRIMARY KEY, title VARCHAR(40) NOT NULL, did INTEGER REFERENCES distributors (did) ON DELETE CASCADE, kind TEXT DEFAULT 'drama', CONSTRAINT production UNIQUE (title, did), CHECK (title <> ''))"
              :values []}
             (sql/format built)
             (sql/format literal)))))
  (testing "an alter, a drop"
    (is (= {:text "ALTER TABLE IF EXISTS t ADD COLUMN IF NOT EXISTS a INT, DROP COLUMN IF EXISTS b CASCADE" :values []}
           (sql/format (-> (h/alter-table :t) (h/if-exists) (h/add-column [:a :int] :if-not-exists) (h/drop-column :b :if-exists :cascade)))))
    (is (= "ALTER TABLE t RENAME COLUMN a TO b" (:text (sql/format (-> (h/alter-table :t) (h/rename-column :a :b))))))
    (is (= {:text "DROP TABLE IF EXISTS t, u CASCADE" :values []}
           (sql/format (-> (h/drop-table :t :u) (h/if-exists) (h/cascade))))))
  (testing "an index, its drop"
    (let [built (-> (h/create-index :i) (h/unique) (h/on :t) (h/using :gin) (h/columns :a [:b :desc :nulls-last]) (h/where [:is-null :deleted_at]))]
      (is (= {:text "CREATE UNIQUE INDEX i ON t USING gin (a, b DESC NULLS LAST) WHERE deleted_at IS NULL" :values []}
             (sql/format built)
             (sql/format {:create-index :i :unique true :on :t :using :gin :columns [:a [:b :desc :nulls-last]] :where [:is-null :deleted_at]}))))
    (is (= "CREATE INDEX ON t (a)" (:text (sql/format (-> (h/on :t) (h/columns :a) (h/create-index))))))
    (is (= "DROP INDEX CONCURRENTLY IF EXISTS i" (:text (sql/format (-> (h/drop-index :i) (h/concurrently) (h/if-exists)))))))
  (testing "a view, its drop"
    (is (= {:text "CREATE OR REPLACE VIEW v (x) AS SELECT a FROM t WHERE kind = 'x'" :values []}
           (sql/format (-> (h/create-view :v) (h/or-replace) (h/columns :x) (h/as (-> (h/select :a) (h/from :t) (h/where [:= :kind "x"])))))))
    (is (= "DROP VIEW IF EXISTS v RESTRICT" (:text (sql/format (-> (h/drop-view :v) (h/if-exists) (h/restrict)))))))
  (testing "malformed helper data fails at format time, not in the helper"
    (is (= {:columns [[:a "text"]]} (h/columns [:a "text"])))
    (is (= [:columns "text"] ((juxt :op :arg) (try (sql/format (-> (h/create-table :t) (h/columns [:a "text"]))) nil (catch :default e (ex-data e))))))
    (is (= [:alter-table {:alter-table :t}] ((juxt :op :arg) (try (sql/format (h/alter-table :t)) nil (catch :default e (ex-data e))))))))
