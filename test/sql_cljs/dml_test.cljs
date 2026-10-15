(ns sql-cljs.dml-test
  "The golden battery of data modification: every INSERT, UPDATE, DELETE
   and TRUNCATE shape pinned to its exact SQL text, in parameter and inline
   mode."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sql-cljs.core :as sql]))

(defn- text [q] (:text (sql/format q)))

(defn- inline [q] (sql/format q {:inline true}))

;; ---------------------------------------------------------------------------
;; INSERT
;; ---------------------------------------------------------------------------

(deftest insert-values
  (testing "one row, no column list"
    (is (= {:text "INSERT INTO t VALUES ($1, $2)" :values [1 "x"]}
           (sql/format {:insert-into :t :values [[1 "x"]]}))))
  (testing "several rows with a column list, values left to right across rows"
    (is (= {:text "INSERT INTO t (a, b) VALUES ($1, $2), ($3, $4)" :values [1 "x" 2 "y"]}
           (sql/format {:insert-into :t :columns [:a :b] :values [[1 "x"] [2 "y"]]}))))
  (testing "the target alias always uses AS; a schema-qualified target; symbols"
    (is (= "INSERT INTO t AS d VALUES ($1)" (text {:insert-into [:t :d] :values [[1]]})))
    (is (= "INSERT INTO public.t VALUES ($1)" (text {:insert-into :public/t :values [[1]]})))
    (is (= "INSERT INTO t VALUES ($1)" (text {:insert-into [:t] :values [[1]]})))
    (is (= "INSERT INTO t (a) VALUES ($1)" (text '{:insert-into t :columns [a] :values [[1]]}))))
  (testing ":columns as a single keyword"
    (is (= "INSERT INTO t (a) VALUES ($1)" (text {:insert-into :t :columns :a :values [[1]]}))))
  (testing "rows may be any sequential, so lazy seqs work in the row slot"
    (is (= {:text "INSERT INTO t (a) VALUES ($1), ($2)" :values [1 2]}
           (sql/format {:insert-into :t :columns [:a] :values (map vector [1 2])}))))
  (testing "nil travels as a parameter"
    (is (= {:text "INSERT INTO t (a, b) VALUES ($1, $2)" :values [nil 1]}
           (sql/format {:insert-into :t :columns [:a :b] :values [[nil 1]]}))))
  (testing "expressions in a row: a function call, a column, a subquery, raw"
    (is (= {:text "INSERT INTO t (a, b) VALUES (NOW(), LOWER($1))" :values ["X"]}
           (sql/format {:insert-into :t :columns [:a :b] :values [[[:now] [:lower "X"]]]})))
    (is (= "INSERT INTO t (a) VALUES (other_col)" (text {:insert-into :t :columns [:a] :values [[:other_col]]})))
    (is (= {:text "INSERT INTO t (a) VALUES ((SELECT MAX(id) FROM u))" :values []}
           (sql/format {:insert-into :t :columns [:a] :values [[{:select [[[:max :id]]] :from [:u]}]]})))
    (is (= "INSERT INTO t (a) VALUES (gen_random_uuid())"
           (text {:insert-into :t :columns [:a] :values [[(sql/raw "gen_random_uuid()")]]})))
    (is (= {:text "INSERT INTO t (a) VALUES ($1 + $2)" :values [1 2]}
           (sql/format {:insert-into :t :columns [:a] :values [[[:+ 1 2]]]})))))

(deftest insert-values-as-maps
  (testing "the keys of the first map are the column list, in its order"
    (is (= {:text "INSERT INTO t (a, b) VALUES ($1, $2), ($3, $4)" :values [1 "x" 2 "y"]}
           (sql/format {:insert-into :t :values [{:a 1 :b "x"} {:a 2 :b "y"}]}))))
  (testing "later rows are read by key, whatever their own order"
    (is (= {:text "INSERT INTO t (a, b) VALUES ($1, $2), ($3, $4)" :values [1 "x" 2 "y"]}
           (sql/format {:insert-into :t :values [{:a 1 :b "x"} {:b "y" :a 2}]}))))
  (testing ":columns, when given, fixes the order and the rows are read by key"
    (is (= {:text "INSERT INTO t (b, a) VALUES ($1, $2)" :values ["x" 1]}
           (sql/format {:insert-into :t :columns [:b :a] :values [{:a 1 :b "x"}]}))))
  (testing "a single map row"
    (is (= {:text "INSERT INTO t (id) VALUES ($1)" :values [7]}
           (sql/format {:insert-into :t :values [{:id 7}]}))))
  (testing "a column left to its default is written :default in the map"
    (is (= {:text "INSERT INTO t (a, b) VALUES ($1, DEFAULT), ($2, $3)" :values [1 2 "y"]}
           (sql/format {:insert-into :t :values [{:a 1 :b :default} {:a 2 :b "y"}]}))))
  (testing "a map with more than eight keys renders every column once (hash order)"
    (let [row (into {} (map (fn [i] [(keyword (str "c" i)) i]) (range 1 10)))
          {:keys [text values]} (sql/format {:insert-into :t :values [row]})
          [_ cols] (re-find #"INSERT INTO t \((.*)\) VALUES" text)
          names (str/split cols #", ")]
      (is (= 9 (count names)))
      (is (= (set (keys row)) (set (map keyword names))))
      (is (= (mapv #(get row (keyword %)) names) values))
      (is (= (str "INSERT INTO t (" cols ") VALUES (" (str/join ", " (map #(str "$" %) (range 1 10))) ")")
             text)))))

(deftest insert-default
  (testing "DEFAULT is a key word in the value slot, never a parameter, in both modes"
    (is (= {:text "INSERT INTO t (a, b) VALUES (DEFAULT, $1)" :values ["x"]}
           (sql/format {:insert-into :t :columns [:a :b] :values [[:default "x"]]})))
    (is (= {:text "INSERT INTO t (a, b) VALUES (DEFAULT, 'x')" :values []}
           (inline {:insert-into :t :columns [:a :b] :values [[:default "x"]]})))
    (is (= {:text "INSERT INTO t (a, b) VALUES ($1, DEFAULT), (DEFAULT, $2)" :values [1 2]}
           (sql/format {:insert-into :t :columns [:a :b] :values [[1 :default] [:default 2]]}))))
  (testing "the symbol spelling"
    (is (= "INSERT INTO t (a) VALUES (DEFAULT)" (text '{:insert-into t :columns [a] :values [[default]]})))))

(deftest insert-select
  (testing "a statement map in place of the rows: INSERT ... SELECT, no parentheses"
    (is (= {:text "INSERT INTO t (a) SELECT a FROM u WHERE b = $1" :values [1]}
           (sql/format {:insert-into :t :columns [:a] :values {:select [:a] :from [:u] :where [:= :b 1]}}))))
  (testing "without a column list"
    (is (= "INSERT INTO t SELECT a, b FROM u" (text {:insert-into :t :values {:select [:a :b] :from [:u]}}))))
  (testing "a set operation as the source"
    (is (= "INSERT INTO t SELECT a FROM u UNION SELECT a FROM v"
           (str/replace (text {:insert-into :t :values {:union [{:select [:a] :from [:u]} {:select [:a] :from [:v]}]}})
                        #"[()]" ""))))
  (testing "a raw source"
    (is (= "INSERT INTO t (a) SELECT 1" (text {:insert-into :t :columns [:a] :values (sql/raw "SELECT 1")}))))
  (testing "WITH before INSERT"
    (is (= "WITH c AS (SELECT a FROM u) INSERT INTO t (a) SELECT a FROM c"
           (text {:with [[:c {:select [:a] :from [:u]}]] :insert-into :t :columns [:a] :values {:select [:a] :from [:c]}})))
    (is (= {:text "WITH c AS (SELECT a FROM u WHERE a > $1) INSERT INTO t (a) VALUES ($2)" :values [0 1]}
           (sql/format {:with [[:c {:select [:a] :from [:u] :where [:> :a 0]}]]
                        :insert-into :t :columns [:a] :values [[1]]})))))

(deftest insert-on-conflict
  (testing "DO NOTHING without a target"
    (is (= "INSERT INTO t VALUES ($1) ON CONFLICT DO NOTHING"
           (text {:insert-into :t :values [[1]] :on-conflict {:do-nothing true}}))))
  (testing "DO NOTHING with column targets"
    (is (= "INSERT INTO t VALUES ($1) ON CONFLICT (id) DO NOTHING"
           (text {:insert-into :t :values [[1]] :on-conflict {:on-columns [:id] :do-nothing true}})))
    (is (= "INSERT INTO t VALUES ($1) ON CONFLICT (id) DO NOTHING"
           (text {:insert-into :t :values [[1]] :on-conflict {:on-columns :id :do-nothing true}})))
    (is (= "INSERT INTO t VALUES ($1) ON CONFLICT (a, b) DO NOTHING"
           (text {:insert-into :t :values [[1]] :on-conflict {:on-columns [:a :b] :do-nothing true}}))))
  (testing "an index expression is parenthesized inside the target list"
    (is (= "INSERT INTO t VALUES ($1) ON CONFLICT ((LOWER(email))) DO NOTHING"
           (text {:insert-into :t :values [[1]] :on-conflict {:on-columns [[:lower :email]] :do-nothing true}})))
    (is (= "INSERT INTO t VALUES ($1) ON CONFLICT (a, (LOWER(email))) DO NOTHING"
           (text {:insert-into :t :values [[1]] :on-conflict {:on-columns [:a [:lower :email]] :do-nothing true}}))))
  (testing "the index predicate follows the target"
    (is (= {:text "INSERT INTO t VALUES ($1) ON CONFLICT (email) WHERE deleted_at IS NULL DO NOTHING" :values [1]}
           (sql/format {:insert-into :t :values [[1]]
                        :on-conflict {:on-columns [:email] :index-where [:is-null :deleted_at] :do-nothing true}}))))
  (testing "DO UPDATE SET from a map, the excluded row as a qualified entity"
    (is (= {:text "INSERT INTO t (id, name) VALUES ($1, $2) ON CONFLICT (id) DO UPDATE SET name = excluded.name"
            :values [1 "x"]}
           (sql/format {:insert-into :t :columns [:id :name] :values [[1 "x"]]
                        :on-conflict {:on-columns [:id] :do-update-set {:name :excluded/name}}}))))
  (testing "DO UPDATE SET from pairs, in order, with an expression and a parameter"
    (is (= {:text "INSERT INTO t (id, n) VALUES ($1, $2) ON CONFLICT (id) DO UPDATE SET n = t.n + $3, name = $4"
            :values [1 2 1 "x"]}
           (sql/format {:insert-into :t :columns [:id :n] :values [[1 2]]
                        :on-conflict {:on-columns [:id] :do-update-set [[:n [:+ :t.n 1]] [:name "x"]]}}))))
  (testing "DEFAULT in DO UPDATE SET"
    (is (= "INSERT INTO t VALUES ($1) ON CONFLICT (id) DO UPDATE SET a = DEFAULT"
           (text {:insert-into :t :values [[1]] :on-conflict {:on-columns [:id] :do-update-set {:a :default}}}))))
  (testing "the DO UPDATE condition, the page's example"
    (is (= {:text "INSERT INTO distributors AS d (did, dname) VALUES ($1, $2) ON CONFLICT (did) DO UPDATE SET dname = excluded.dname WHERE d.zipcode <> $3"
            :values [8 "Anvil Distribution" "21201"]}
           (sql/format {:insert-into [:distributors :d] :columns [:did :dname] :values [[8 "Anvil Distribution"]]
                        :on-conflict {:on-columns [:did]
                                      :do-update-set {:dname :excluded/dname}
                                      :where [:<> :d.zipcode "21201"]}}))))
  (testing "both conditions: the index predicate before DO, the update condition after SET"
    (is (= {:text "INSERT INTO t (a) VALUES ($1) ON CONFLICT (a) WHERE b = $2 DO UPDATE SET a = excluded.a WHERE t.c < $3"
            :values [1 true 5]}
           (sql/format {:insert-into :t :columns [:a] :values [[1]]
                        :on-conflict {:on-columns [:a] :index-where [:= :b true]
                                      :do-update-set {:a :excluded/a} :where [:< :t.c 5]}}))))
  (testing "ON CONSTRAINT"
    (is (= "INSERT INTO t VALUES ($1) ON CONFLICT ON CONSTRAINT t_pkey DO NOTHING"
           (text {:insert-into :t :values [[1]] :on-conflict {:on-constraint :t_pkey :do-nothing true}})))
    (is (= {:text "INSERT INTO t VALUES ($1) ON CONFLICT ON CONSTRAINT t_pkey DO UPDATE SET a = $2" :values [1 2]}
           (sql/format {:insert-into :t :values [[1]] :on-conflict {:on-constraint :t_pkey :do-update-set {:a 2}}}))))
  (testing ":do-nothing false is absent"
    (is (= "INSERT INTO t VALUES ($1) ON CONFLICT (id) DO UPDATE SET a = excluded.a"
           (text {:insert-into :t :values [[1]] :on-conflict {:on-columns [:id] :do-nothing false :do-update-set {:a :excluded/a}}}))))
  (testing "ON CONFLICT then RETURNING"
    (is (= "INSERT INTO t VALUES ($1) ON CONFLICT (id) DO NOTHING RETURNING id"
           (text {:insert-into :t :values [[1]] :on-conflict {:on-columns [:id] :do-nothing true} :returning [:id]})))))

(deftest insert-returning
  (testing "entities, the star, nested alias pairs, a function without alias"
    (is (= "INSERT INTO t VALUES ($1) RETURNING id" (text {:insert-into :t :values [[1]] :returning [:id]})))
    (is (= "INSERT INTO t VALUES ($1) RETURNING id, name" (text {:insert-into :t :values [[1]] :returning [:id :name]})))
    (is (= "INSERT INTO t VALUES ($1) RETURNING *" (text {:insert-into :t :values [[1]] :returning [:*]})))
    (is (= "INSERT INTO t VALUES ($1) RETURNING *" (text {:insert-into :t :values [[1]] :returning :*})))
    (is (= "INSERT INTO t VALUES ($1) RETURNING t.*" (text {:insert-into :t :values [[1]] :returning [:t/*]})))
    (is (= "INSERT INTO t VALUES ($1) RETURNING id, LOWER(name) AS n"
           (text {:insert-into :t :values [[1]] :returning [:id [[:lower :name] :n]]})))
    (is (= "INSERT INTO t VALUES ($1) RETURNING NOW()" (text {:insert-into :t :values [[1]] :returning [[[:now]]]})))
    (is (= "INSERT INTO t VALUES ($1) RETURNING id AS the_id" (text {:insert-into :t :values [[1]] :returning [[:id :the_id]]}))))
  (testing "a value in RETURNING is a parameter after the row's: [value alias] or [[value]]"
    (is (= {:text "INSERT INTO t VALUES ($1) RETURNING id, $2 AS tag" :values [1 "x"]}
           (sql/format {:insert-into :t :values [[1]] :returning [:id ["x" :tag]]})))
    (is (= {:text "INSERT INTO t VALUES ($1) RETURNING $2" :values [1 "x"]}
           (sql/format {:insert-into :t :values [[1]] :returning [["x"]]})))))

;; ---------------------------------------------------------------------------
;; UPDATE
;; ---------------------------------------------------------------------------

(deftest update-set
  (testing "a map of assignments"
    (is (= {:text "UPDATE t SET a = $1" :values [1]} (sql/format {:update :t :set {:a 1}})))
    (is (= {:text "UPDATE t SET a = $1, b = c" :values [1]} (sql/format {:update :t :set {:a 1 :b :c}}))))
  (testing "a vector of pairs, in the order given"
    (is (= {:text "UPDATE t SET b = $1, a = $2" :values ["y" 1]}
           (sql/format {:update :t :set [[:b "y"] [:a 1]]}))))
  (testing "the map and the pairs render identically"
    (is (= (sql/format {:update :t :set {:a 1 :b "x"}}) (sql/format {:update :t :set [[:a 1] [:b "x"]]}))))
  (testing "a map with more than eight assignments renders each once; the pairs keep the order"
    (let [cols (mapv #(keyword (str "c" %)) (range 1 10))
          pairs (mapv (fn [c i] [c i]) cols (range 1 10))
          from-map (sql/format {:update :t :set (into {} pairs)})
          assignments (-> (:text from-map) (str/replace "UPDATE t SET " "") (str/split #", "))]
      (is (= 9 (count assignments)))
      (is (= (set (map #(str (name %) " = $") cols)) (set (map #(str/replace % #"\d+$" "") assignments))))
      (is (= {:text "UPDATE t SET c1 = $1, c2 = $2, c3 = $3, c4 = $4, c5 = $5, c6 = $6, c7 = $7, c8 = $8, c9 = $9"
              :values [1 2 3 4 5 6 7 8 9]}
             (sql/format {:update :t :set pairs})))))
  (testing "DEFAULT, an expression, a subquery, raw, nil, a symbol column"
    (is (= "UPDATE t SET a = DEFAULT" (text {:update :t :set {:a :default}})))
    (is (= {:text "UPDATE t SET n = n + $1" :values [1]} (sql/format {:update :t :set {:n [:+ :n 1]}})))
    (is (= "UPDATE t SET a = (SELECT MAX(x) FROM u)" (text {:update :t :set {:a {:select [[[:max :x]]] :from [:u]}}})))
    (is (= "UPDATE t SET a = NOW()" (text {:update :t :set {:a (sql/raw "NOW()")}})))
    (is (= {:text "UPDATE t SET a = $1" :values [nil]} (sql/format {:update :t :set {:a nil}})))
    (is (= "UPDATE t SET a = b" (text '{:update t :set {a b}})))
    (is (= {:text "UPDATE t SET a = COALESCE(a, $1)" :values [0]} (sql/format {:update :t :set {:a [:coalesce :a 0]}}))))
  (testing "the target alias, a schema-qualified target"
    (is (= "UPDATE t AS x SET a = $1" (text {:update [:t :x] :set {:a 1}})))
    (is (= "UPDATE public.t SET a = $1" (text {:update :public/t :set {:a 1}})))))

(deftest update-clauses
  (testing "WHERE, values after the assignments'"
    (is (= {:text "UPDATE t SET a = $1 WHERE id = $2" :values ["x" 7]}
           (sql/format {:update :t :set {:a "x"} :where [:= :id 7]}))))
  (testing "FROM, the page's example"
    (is (= {:text "UPDATE employees SET sales_count = sales_count + $1 FROM accounts WHERE (accounts.name = $2) AND (employees.id = accounts.sales_person)"
            :values [1 "Acme Corporation"]}
           (sql/format {:update :employees
                        :set {:sales_count [:+ :sales_count 1]}
                        :from [:accounts]
                        :where [:and [:= :accounts.name "Acme Corporation"]
                                [:= :employees.id :accounts.sales_person]]}))))
  (testing "FROM with an alias and a subquery"
    (is (= "UPDATE t SET a = u.a FROM u AS x, (SELECT a FROM v) AS s WHERE t.id = x.id"
           (text {:update :t :set {:a :u/a} :from [[:u :x] [{:select [:a] :from [:v]} :s]] :where [:= :t.id :x.id]}))))
  (testing "RETURNING"
    (is (= {:text "UPDATE t SET a = $1 WHERE id = $2 RETURNING id, a" :values [1 2]}
           (sql/format {:update :t :set {:a 1} :where [:= :id 2] :returning [:id :a]})))
    (is (= "UPDATE t SET a = $1 RETURNING *" (text {:update :t :set {:a 1} :returning [:*]}))))
  (testing "the full synopsis order: WITH, UPDATE, SET, FROM, WHERE, RETURNING"
    (is (= "WITH c AS (SELECT id FROM u) UPDATE t SET a = $1 FROM c WHERE t.id = c.id RETURNING t.id"
           (text {:returning [:t/id] :where [:= :t.id :c.id] :from [:c] :set {:a 1} :update :t
                  :with [[:c {:select [:id] :from [:u]}]]})))))

;; ---------------------------------------------------------------------------
;; DELETE
;; ---------------------------------------------------------------------------

(deftest delete-clauses
  (testing "the bare form deletes every row"
    (is (= {:text "DELETE FROM t" :values []} (sql/format {:delete-from :t}))))
  (testing "WHERE"
    (is (= {:text "DELETE FROM t WHERE id = $1" :values [7]} (sql/format {:delete-from :t :where [:= :id 7]}))))
  (testing "USING, the page's example"
    (is (= {:text "DELETE FROM films USING producers WHERE (producer_id = producers.id) AND (producers.name = $1)"
            :values ["foo"]}
           (sql/format {:delete-from :films :using [:producers]
                        :where [:and [:= :producer_id :producers.id] [:= :producers.name "foo"]]}))))
  (testing "USING with several tables, an alias, a subquery"
    (is (= "DELETE FROM t USING u AS x, v WHERE t.id = x.id"
           (text {:delete-from :t :using [[:u :x] :v] :where [:= :t.id :x.id]})))
    (is (= "DELETE FROM t USING (SELECT id FROM u) AS s WHERE t.id = s.id"
           (text {:delete-from :t :using [[{:select [:id] :from [:u]} :s]] :where [:= :t.id :s.id]}))))
  (testing "the target alias, a schema-qualified target, symbols"
    (is (= "DELETE FROM t AS x WHERE x.a = $1" (text {:delete-from [:t :x] :where [:= :x.a 1]})))
    (is (= "DELETE FROM public.t" (text {:delete-from :public/t})))
    (is (= "DELETE FROM t" (text '{:delete-from t}))))
  (testing "RETURNING"
    (is (= {:text "DELETE FROM t WHERE id = $1 RETURNING *" :values [1]}
           (sql/format {:delete-from :t :where [:= :id 1] :returning [:*]})))
    (is (= "DELETE FROM t RETURNING id, LOWER(name) AS n" (text {:delete-from :t :returning [:id [[:lower :name] :n]]}))))
  (testing "the full synopsis order: WITH, DELETE FROM, USING, WHERE, RETURNING"
    (is (= "WITH c AS (SELECT id FROM u) DELETE FROM t USING c WHERE t.id = c.id RETURNING t.id"
           (text {:returning [:t/id] :where [:= :t.id :c.id] :using [:c] :delete-from :t
                  :with [[:c {:select [:id] :from [:u]}]]})))))

;; ---------------------------------------------------------------------------
;; TRUNCATE
;; ---------------------------------------------------------------------------

(deftest truncate-clauses
  (testing "one table, several, schema-qualified, symbols; never a value"
    (is (= {:text "TRUNCATE TABLE t" :values []} (sql/format {:truncate :t})))
    (is (= "TRUNCATE TABLE t" (text {:truncate [:t]})))
    (is (= "TRUNCATE TABLE t, u" (text {:truncate [:t :u]})))
    (is (= "TRUNCATE TABLE public.t, u" (text {:truncate [:public/t :u]})))
    (is (= "TRUNCATE TABLE t, u" (text '{:truncate [t u]}))))
  (testing "every option alone"
    (is (= "TRUNCATE TABLE t RESTART IDENTITY" (text {:truncate :t :restart-identity true})))
    (is (= "TRUNCATE TABLE t CONTINUE IDENTITY" (text {:truncate :t :continue-identity true})))
    (is (= "TRUNCATE TABLE t CASCADE" (text {:truncate :t :cascade true})))
    (is (= "TRUNCATE TABLE t RESTRICT" (text {:truncate :t :restrict true}))))
  (testing "every legal combination, identity before cascade whatever the key order"
    (is (= "TRUNCATE TABLE t, u RESTART IDENTITY CASCADE" (text {:truncate [:t :u] :restart-identity true :cascade true})))
    (is (= "TRUNCATE TABLE t RESTART IDENTITY CASCADE" (text {:cascade true :restart-identity true :truncate :t})))
    (is (= "TRUNCATE TABLE t RESTART IDENTITY RESTRICT" (text {:truncate :t :restart-identity true :restrict true})))
    (is (= "TRUNCATE TABLE t CONTINUE IDENTITY CASCADE" (text {:truncate :t :continue-identity true :cascade true})))
    (is (= "TRUNCATE TABLE t CONTINUE IDENTITY RESTRICT" (text {:truncate :t :continue-identity true :restrict true}))))
  (testing "a false flag is absent"
    (is (= "TRUNCATE TABLE t" (text {:truncate :t :restart-identity false :cascade false})))
    (is (= "TRUNCATE TABLE t CASCADE" (text {:truncate :t :restart-identity false :continue-identity false :cascade true :restrict false})))))

;; ---------------------------------------------------------------------------
;; Inline mode
;; ---------------------------------------------------------------------------

(deftest inline-dml
  (testing "INSERT: literals, a doubled quote, NULL, booleans, DEFAULT stays a key word"
    (is (= {:text "INSERT INTO t (a, b, c, d, e) VALUES (1, 'it''s', NULL, TRUE, DEFAULT)" :values []}
           (inline {:insert-into :t :columns [:a :b :c :d :e] :values [[1 "it's" nil true :default]]}))))
  (testing "INSERT from maps, several rows"
    (is (= {:text "INSERT INTO t (a, b) VALUES (1, 'x'), (2, DEFAULT)" :values []}
           (inline {:insert-into :t :values [{:a 1 :b "x"} {:a 2 :b :default}]}))))
  (testing "INSERT ... SELECT and ON CONFLICT"
    (is (= {:text "INSERT INTO t (a) SELECT a FROM u WHERE b = 'x' ON CONFLICT (id) DO UPDATE SET n = excluded.n WHERE t.v <> 2 RETURNING id"
            :values []}
           (inline {:insert-into :t :columns [:a] :values {:select [:a] :from [:u] :where [:= :b "x"]}
                    :on-conflict {:on-columns [:id] :do-update-set {:n :excluded/n} :where [:<> :t.v 2]}
                    :returning [:id]}))))
  (testing "UPDATE and DELETE"
    (is (= {:text "UPDATE t SET a = 'x', b = DEFAULT, c = FALSE WHERE id = 1" :values []}
           (inline {:update :t :set [[:a "x"] [:b :default] [:c false]] :where [:= :id 1]})))
    (is (= {:text "DELETE FROM t USING u WHERE (t.id = u.id) AND (u.name = 'foo') RETURNING *" :values []}
           (inline {:delete-from :t :using [:u] :where [:and [:= :t.id :u.id] [:= :u.name "foo"]] :returning [:*]}))))
  (testing "TRUNCATE has no values in either mode"
    (is (= {:text "TRUNCATE TABLE t CASCADE" :values []} (inline {:truncate :t :cascade true})))))
