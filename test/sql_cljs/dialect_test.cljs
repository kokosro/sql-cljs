(ns sql-cljs.dialect-test
  "The :dialect option: the same statement maps rendered under {:dialect :pg}
   (the PostgreSQL text, identical to formatting without the option) and
   under {:dialect :sqlite} (the SQLite text, or the format-error SQLite's
   grammar calls for). Every pair pins both exact strings."
  (:require [cljs.test :refer [deftest is testing]]
            [sql-cljs.core :as sql]))

(defn- thrown-data [f]
  (try (f) nil (catch :default e (ex-data e))))

(defn- pg [q] (sql/format q {:dialect :pg}))
(defn- lite [q] (sql/format q {:dialect :sqlite}))
(defn- pg-text [q] (:text (pg q)))
(defn- lite-text [q] (:text (lite q)))
(defn- lite-error [q] (thrown-data #(lite q)))

;; ---------------------------------------------------------------------------
;; The option surface
;; ---------------------------------------------------------------------------

(deftest dialect-option
  (let [q {:select [:a] :from [:t] :where [:= :a 1]}]
    (testing ":pg is the default: absent, {:dialect :pg} and {} render the same"
      (is (= {:text "SELECT a FROM t WHERE a = $1" :values [1]} (sql/format q)))
      (is (= (sql/format q) (sql/format q {:dialect :pg})))
      (is (= (sql/format q) (sql/format q {}))))
    (testing ":sqlite is accepted"
      (is (= {:text "SELECT a FROM t WHERE a = ?" :values [1]} (sql/format q {:dialect :sqlite}))))
    (testing "any other dialect is a format-error with the allowed list"
      (let [d (thrown-data #(sql/format q {:dialect :mysql}))]
        (is (= :sql-cljs/format-error (:type d)))
        (is (= :format (:op d)))
        (is (= :mysql (:arg d)))
        (is (= [:pg :sqlite] (:allowed d))))
      (is (= :format (:op (thrown-data #(sql/format q {:dialect "sqlite"})))))
      (is (= :format (:op (thrown-data #(sql/format q {:dialect :postgres}))))))
    (testing "a nil :dialect is an error, not the default"
      (let [d (thrown-data #(sql/format q {:dialect nil}))]
        (is (= :format (:op d)))
        (is (nil? (:arg d)))
        (is (= [:pg :sqlite] (:allowed d)))))
    (testing ":dialect and :inline combine"
      (is (= {:text "SELECT a FROM t WHERE a = 1" :values []} (sql/format q {:dialect :sqlite :inline true})))
      (is (= {:text "SELECT a FROM t WHERE a = 1" :values []} (sql/format q {:dialect :pg :inline true})))
      (is (= {:text "SELECT a FROM t WHERE a = ?" :values [1]} (sql/format q {:dialect :sqlite :inline false})))
      (is (= "yes" (:arg (thrown-data #(sql/format q {:dialect :sqlite :inline "yes"}))))))
    (testing "an unknown option names both options as allowed"
      (let [d (thrown-data #(sql/format q {:dialect :sqlite :pretty true}))]
        (is (= :format (:op d)))
        (is (= :pretty (:arg d)))
        (is (= [:dialect :inline] (:allowed d)))))))

;; ---------------------------------------------------------------------------
;; Placeholders
;; ---------------------------------------------------------------------------

(deftest placeholders
  (testing "the README query"
    (let [q {:select [:a :b] :from [:foo] :where [:= :foo.a "baz"]}]
      (is (= {:text "SELECT a, b FROM foo WHERE foo.a = $1" :values ["baz"]} (pg q)))
      (is (= {:text "SELECT a, b FROM foo WHERE foo.a = ?" :values ["baz"]} (lite q)))))
  (testing "one ? per value, in order of appearance, a repeated value sent twice"
    (let [q {:select [:a] :from [:t] :where [:and [:= :a 1] [:in :b [2 3]] [:= :c 1]]}]
      (is (= {:text "SELECT a FROM t WHERE (a = $1) AND (b IN ($2, $3)) AND (c = $4)" :values [1 2 3 1]} (pg q)))
      (is (= {:text "SELECT a FROM t WHERE (a = ?) AND (b IN (?, ?)) AND (c = ?)" :values [1 2 3 1]} (lite q)))))
  (testing "INSERT rows"
    (let [q {:insert-into :t :columns [:a :b] :values [[1 "x"] [2 "y"]]}]
      (is (= {:text "INSERT INTO t (a, b) VALUES ($1, $2), ($3, $4)" :values [1 "x" 2 "y"]} (pg q)))
      (is (= {:text "INSERT INTO t (a, b) VALUES (?, ?), (?, ?)" :values [1 "x" 2 "y"]} (lite q)))))
  (testing "UPDATE and DELETE"
    (let [u {:update :t :set {:a 1} :where [:= :id 2]}
          d {:delete-from :t :where [:= :id 2] :returning [:id]}]
      (is (= "UPDATE t SET a = $1 WHERE id = $2" (pg-text u)))
      (is (= "UPDATE t SET a = ? WHERE id = ?" (lite-text u)))
      (is (= "DELETE FROM t WHERE id = $1 RETURNING id" (pg-text d)))
      (is (= {:text "DELETE FROM t WHERE id = ? RETURNING id" :values [2]} (lite d)))))
  (testing "across a CTE and a nested subquery the order is the order of the text"
    (let [q {:with [[:x {:select [:id] :from [:u] :where [:= :k 1]}]]
             :select [:a] :from [:t]
             :where [:in :id {:select [:id] :from [:x] :where [:> :n 2]}]}]
      (is (= {:text "WITH x AS (SELECT id FROM u WHERE k = $1) SELECT a FROM t WHERE id IN (SELECT id FROM x WHERE n > $2)"
              :values [1 2]}
             (pg q)))
      (is (= {:text "WITH x AS (SELECT id FROM u WHERE k = ?) SELECT a FROM t WHERE id IN (SELECT id FROM x WHERE n > ?)"
              :values [1 2]}
             (lite q)))))
  (testing "inline mode is the same text in both dialects, booleans as TRUE / FALSE"
    (let [q {:select [:a] :from [:t] :where [:and [:= :a "it's"] [:= :b true] [:is-null :c]]}]
      (is (= {:text "SELECT a FROM t WHERE (a = 'it''s') AND (b = TRUE) AND (c IS NULL)" :values []}
             (sql/format q {:dialect :pg :inline true})))
      (is (= (sql/format q {:dialect :pg :inline true}) (sql/format q {:dialect :sqlite :inline true}))))))

;; ---------------------------------------------------------------------------
;; What renders identically: entities, the empty IN, UPDATE ... FROM, RETURNING
;; ---------------------------------------------------------------------------

(deftest unchanged-rules
  (testing "the bare-identifier rule and its error are the same under both dialects"
    (is (= "SELECT a_1$ FROM s.t" (pg-text {:select [:a_1$] :from [:s/t]})))
    (is (= "SELECT a_1$ FROM s.t" (lite-text {:select [:a_1$] :from [:s/t]})))
    (let [q {:select [:UserId] :from [:t]}]
      (is (= :select (:op (lite-error q))))
      (is (= "UserId" (:arg (lite-error q))))
      (is (= (thrown-data #(pg q)) (lite-error q))))
    (is (= "SELECT \"UserId\" FROM \"Users\""
           (lite-text {:select [(sql/raw "\"UserId\"")] :from [(sql/raw "\"Users\"")]}))))
  (testing "[:in x []] is the same format-error under both dialects"
    (let [q {:select [:a] :from [:t] :where [:in :a []]}]
      (is (= :in (:op (lite-error q))))
      (is (= (thrown-data #(pg q)) (lite-error q)))))
  (testing "UPDATE ... FROM, INSERT ... RETURNING and the target alias render identically"
    (let [u {:update :t :set {:a :u.a} :from [:u] :where [:= :t.id :u.id]}
          i {:insert-into [:t :x] :columns [:a] :values [[1]] :returning [:*]}]
      (is (= "UPDATE t SET a = u.a FROM u WHERE t.id = u.id" (pg-text u)))
      (is (= "UPDATE t SET a = u.a FROM u WHERE t.id = u.id" (lite-text u)))
      (is (= "INSERT INTO t AS x (a) VALUES ($1) RETURNING *" (pg-text i)))
      (is (= "INSERT INTO t AS x (a) VALUES (?) RETURNING *" (lite-text i))))))

;; ---------------------------------------------------------------------------
;; LIMIT and OFFSET
;; ---------------------------------------------------------------------------

(deftest limit-and-offset
  (testing "a numeric limit is a parameter in both"
    (let [q {:select [:a] :from [:t] :limit 5}]
      (is (= {:text "SELECT a FROM t LIMIT $1" :values [5]} (pg q)))
      (is (= {:text "SELECT a FROM t LIMIT ?" :values [5]} (lite q)))))
  (testing ":limit :all is LIMIT ALL under :pg, LIMIT -1 under :sqlite"
    (let [q {:select [:a] :from [:t] :limit :all}]
      (is (= {:text "SELECT a FROM t LIMIT ALL" :values []} (pg q)))
      (is (= {:text "SELECT a FROM t LIMIT -1" :values []} (lite q)))))
  (testing "an :offset without :limit gets LIMIT -1 under :sqlite, the offset still a parameter"
    (let [q {:select [:a] :from [:t] :offset 10}]
      (is (= {:text "SELECT a FROM t OFFSET $1" :values [10]} (pg q)))
      (is (= {:text "SELECT a FROM t LIMIT -1 OFFSET ?" :values [10]} (lite q)))
      (is (= {:text "SELECT a FROM t LIMIT -1 OFFSET 10" :values []} (sql/format q {:dialect :sqlite :inline true})))))
  (testing ":limit and :offset together"
    (let [q {:select [:a] :from [:t] :order-by [:a] :limit 5 :offset 10}]
      (is (= {:text "SELECT a FROM t ORDER BY a LIMIT $1 OFFSET $2" :values [5 10]} (pg q)))
      (is (= {:text "SELECT a FROM t ORDER BY a LIMIT ? OFFSET ?" :values [5 10]} (lite q)))))
  (testing ":limit :all with :offset"
    (let [q {:select [:a] :from [:t] :limit :all :offset 10}]
      (is (= {:text "SELECT a FROM t LIMIT ALL OFFSET $1" :values [10]} (pg q)))
      (is (= {:text "SELECT a FROM t LIMIT -1 OFFSET ?" :values [10]} (lite q)))))
  (testing "a raw offset"
    (let [q {:select [:a] :from [:t] :offset (sql/raw "10")}]
      (is (= "SELECT a FROM t OFFSET 10" (pg-text q)))
      (is (= "SELECT a FROM t LIMIT -1 OFFSET 10" (lite-text q)))))
  (testing "the same spellings at the compound level"
    (let [q {:union [{:select [:a] :from [:t]} {:select [:a] :from [:u]}] :limit :all}
          o {:union [{:select [:a] :from [:t]} {:select [:a] :from [:u]}] :offset 3}]
      (is (= "(SELECT a FROM t) UNION (SELECT a FROM u) LIMIT ALL" (pg-text q)))
      (is (= "SELECT a FROM t UNION SELECT a FROM u LIMIT -1" (lite-text q)))
      (is (= "(SELECT a FROM t) UNION (SELECT a FROM u) OFFSET $1" (pg-text o)))
      (is (= "SELECT a FROM t UNION SELECT a FROM u LIMIT -1 OFFSET ?" (lite-text o))))))

;; ---------------------------------------------------------------------------
;; Compound selects
;; ---------------------------------------------------------------------------

(def ^:private s-t {:select [:a] :from [:t]})
(def ^:private s-u {:select [:a] :from [:u]})
(def ^:private s-v {:select [:a] :from [:v]})

(deftest compound-selects
  (testing "operands are parenthesized under :pg, bare under :sqlite"
    (let [q {:union [s-t s-u]}]
      (is (= "(SELECT a FROM t) UNION (SELECT a FROM u)" (pg-text q)))
      (is (= "SELECT a FROM t UNION SELECT a FROM u" (lite-text q)))))
  (testing "UNION ALL, chained, with ORDER BY and LIMIT on the whole"
    (let [q {:union-all [s-t s-u s-v] :order-by [:a] :limit 3}]
      (is (= {:text "(SELECT a FROM t) UNION ALL (SELECT a FROM u) UNION ALL (SELECT a FROM v) ORDER BY a LIMIT $1" :values [3]}
             (pg q)))
      (is (= {:text "SELECT a FROM t UNION ALL SELECT a FROM u UNION ALL SELECT a FROM v ORDER BY a LIMIT ?" :values [3]}
             (lite q)))))
  (testing "values inside the operands"
    (let [q {:union [{:select [:a] :from [:t] :where [:= :a 1]} {:select [:a] :from [:u] :where [:= :a 2]}]}]
      (is (= {:text "(SELECT a FROM t WHERE a = $1) UNION (SELECT a FROM u WHERE a = $2)" :values [1 2]} (pg q)))
      (is (= {:text "SELECT a FROM t WHERE a = ? UNION SELECT a FROM u WHERE a = ?" :values [1 2]} (lite q)))))
  (testing "a raw operand"
    (let [q {:union [s-t (sql/raw "SELECT 1")]}]
      (is (= "(SELECT a FROM t) UNION (SELECT 1)" (pg-text q)))
      (is (= "SELECT a FROM t UNION SELECT 1" (lite-text q)))))
  (testing "a nested compound flattens left to right"
    (let [q {:union [{:union [s-t s-u]} s-v]}]
      (is (= "((SELECT a FROM t) UNION (SELECT a FROM u)) UNION (SELECT a FROM v)" (pg-text q)))
      (is (= "SELECT a FROM t UNION SELECT a FROM u UNION SELECT a FROM v" (lite-text q)))))
  (testing "an operand with its own :order-by / :limit / :offset / :for / :with is legal under :pg, a format-error under :sqlite"
    (let [ordered (assoc s-t :order-by [:a])
          q {:union [ordered s-u]}
          d (lite-error q)]
      (is (= "(SELECT a FROM t ORDER BY a) UNION (SELECT a FROM u)" (pg-text q)))
      (is (= :sql-cljs/format-error (:type d)))
      (is (= :union (:op d)))
      (is (= ordered (:arg d)))
      (is (= [:with :with-recursive :order-by :limit :offset :for] (:allowed d)))
      (is (re-find #"select cores" (:message d))))
    (is (= "(SELECT a FROM t) UNION ALL (SELECT a FROM u LIMIT $1)" (pg-text {:union-all [s-t (assoc s-u :limit 1)]})))
    (is (= :union-all (:op (lite-error {:union-all [s-t (assoc s-u :limit 1)]}))))
    (is (= :union (:op (lite-error {:union [s-t (assoc s-u :offset 1)]}))))
    (is (= :union (:op (lite-error {:union [s-t (assoc s-u :for :update)]}))))
    (is (= :union (:op (lite-error {:union [(assoc s-t :with [[:w s-v]]) s-u]}))))
    (testing "a :with on the compound itself is fine"
      (is (= "WITH w AS (SELECT a FROM v) SELECT a FROM t UNION SELECT a FROM u"
             (lite-text {:with [[:w s-v]] :union [s-t s-u]}))))))

;; ---------------------------------------------------------------------------
;; Expressions: LIKE, the CURRENT_* key words
;; ---------------------------------------------------------------------------

(deftest like-operators
  (testing ":ilike is ILIKE under :pg and LIKE under :sqlite"
    (let [q {:select [:a] :from [:t] :where [:ilike :name "a%"]}]
      (is (= {:text "SELECT a FROM t WHERE name ILIKE $1" :values ["a%"]} (pg q)))
      (is (= {:text "SELECT a FROM t WHERE name LIKE ?" :values ["a%"]} (lite q)))))
  (testing ":not-ilike"
    (let [q {:select [:a] :from [:t] :where [:not-ilike :name "a%"]}]
      (is (= "SELECT a FROM t WHERE name NOT ILIKE $1" (pg-text q)))
      (is (= "SELECT a FROM t WHERE name NOT LIKE ?" (lite-text q)))))
  (testing ":like and :not-like are unchanged"
    (is (= "SELECT a FROM t WHERE name LIKE ?" (lite-text {:select [:a] :from [:t] :where [:like :name "a%"]})))
    (is (= "SELECT a FROM t WHERE name NOT LIKE ?" (lite-text {:select [:a] :from [:t] :where [:not-like :name "a%"]})))
    (is (= "SELECT a FROM t WHERE name LIKE $1" (pg-text {:select [:a] :from [:t] :where [:like :name "a%"]}))))
  (testing "the arity rule is the same"
    (is (= :ilike (:op (lite-error {:select [:a] :where [:ilike :name]}))))))

(defn- not-an-identifier?
  "True when d is the entity rule's rejection of the hyphenated name nm."
  [d nm]
  (and (= :sql-cljs/format-error (:type d)) (= (name nm) (:arg d)) (= nm (:entity d))))

(deftest current-key-words
  (testing "[:current-timestamp] is the bare key word under :sqlite; under :pg a hyphenated function name is not an identifier, as always"
    (let [q {:select [[[:current-timestamp]]]}
          d (thrown-data #(pg q))]
      (is (= {:text "SELECT CURRENT_TIMESTAMP" :values []} (lite q)))
      (is (not-an-identifier? d :current-timestamp))
      (is (= :select (:op d)))))
  (testing "CURRENT_DATE and CURRENT_TIME, aliased, in WHERE"
    (is (= "SELECT CURRENT_DATE AS today" (lite-text {:select [[[:current-date] :today]]})))
    (is (not-an-identifier? (thrown-data #(pg {:select [[[:current-date] :today]]})) :current-date))
    (is (= "SELECT a FROM t WHERE created < CURRENT_TIME" (lite-text {:select [:a] :from [:t] :where [:< :created [:current-time]]})))
    (is (not-an-identifier? (thrown-data #(pg {:select [:a] :from [:t] :where [:< :created [:current-time]]})) :current-time)))
  (testing "in a DEFAULT, unparenthesized under :sqlite (a key word, not a call)"
    (let [q {:create-table :t :columns [[:created :text [:default [:current-timestamp]]]]}]
      (is (= {:text "CREATE TABLE t (created TEXT DEFAULT CURRENT_TIMESTAMP)" :values []} (lite q)))
      (is (not-an-identifier? (thrown-data #(pg q)) :current-timestamp))))
  (testing "with any argument it is an ordinary function call, whose hyphenated name fails the entity rule in both"
    (let [q {:select [[[:current-timestamp 3]]]}]
      (is (not-an-identifier? (lite-error q) :current-timestamp))
      (is (= (thrown-data #(pg q)) (lite-error q)))))
  (testing "the symbol spelling"
    (is (= "SELECT CURRENT_DATE" (lite-text '{:select [[[current-date]]]}))))
  (testing "other zero-argument calls keep their parentheses, and the underscore spelling is a plain call in both"
    (is (= "SELECT NOW()" (lite-text {:select [[[:now]]]})))
    (is (= "SELECT NOW()" (pg-text {:select [[[:now]]]})))
    (is (= "SELECT CURRENT_TIMESTAMP()" (pg-text {:select [[[:current_timestamp]]]})))
    (is (= "SELECT CURRENT_TIMESTAMP()" (lite-text {:select [[[:current_timestamp]]]})))))

;; ---------------------------------------------------------------------------
;; SELECT: DISTINCT ON and locking
;; ---------------------------------------------------------------------------

(deftest distinct-on-and-locking
  (testing ":distinct-on"
    (let [q {:select [:a :b] :distinct-on [:a] :from [:t] :order-by [:a]}
          d (lite-error q)]
      (is (= "SELECT DISTINCT ON (a) a, b FROM t ORDER BY a" (pg-text q)))
      (is (= :sql-cljs/format-error (:type d)))
      (is (= :distinct-on (:op d)))
      (is (= [:a] (:arg d)))
      (is (re-find #"SQLite has no DISTINCT ON" (:message d)))))
  (testing "plain :distinct renders identically"
    (is (= "SELECT DISTINCT a FROM t" (pg-text {:select [:a] :distinct true :from [:t]})))
    (is (= "SELECT DISTINCT a FROM t" (lite-text {:select [:a] :distinct true :from [:t]}))))
  (testing ":for"
    (let [q {:select [:a] :from [:t] :where [:= :id 1] :for :update}
          d (lite-error q)]
      (is (= "SELECT a FROM t WHERE id = $1 FOR UPDATE" (pg-text q)))
      (is (= :for (:op d)))
      (is (= :update (:arg d)))
      (is (re-find #"row-locking" (:message d))))
    (is (= "SELECT a FROM t FOR SHARE OF t NOWAIT" (pg-text {:select [:a] :from [:t] :for [:share :t :nowait]})))
    (is (= [:share :t :nowait] (:arg (lite-error {:select [:a] :from [:t] :for [:share :t :nowait]}))))))

;; ---------------------------------------------------------------------------
;; INSERT: upsert, DEFAULT as a value, INSERT ... SELECT
;; ---------------------------------------------------------------------------

(def ^:private ins {:insert-into :t :columns [:id :n] :values [[1 "x"]]})

(deftest upsert
  (testing "the column target with DO UPDATE SET renders identically"
    (let [q (assoc ins :on-conflict {:on-columns [:id] :do-update-set {:n :excluded/n}})]
      (is (= {:text "INSERT INTO t (id, n) VALUES ($1, $2) ON CONFLICT (id) DO UPDATE SET n = excluded.n" :values [1 "x"]} (pg q)))
      (is (= {:text "INSERT INTO t (id, n) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET n = excluded.n" :values [1 "x"]} (lite q)))))
  (testing ":index-where, :do-nothing and the DO UPDATE :where render identically"
    (let [q (assoc ins :on-conflict {:on-columns [:id] :index-where [:> :id 0] :do-nothing true})
          w (assoc ins :on-conflict {:on-columns [[:lower :n]] :do-update-set {:n :excluded/n} :where [:< :t.n :excluded/n]})]
      (is (= "INSERT INTO t (id, n) VALUES ($1, $2) ON CONFLICT (id) WHERE id > $3 DO NOTHING" (pg-text q)))
      (is (= {:text "INSERT INTO t (id, n) VALUES (?, ?) ON CONFLICT (id) WHERE id > ? DO NOTHING" :values [1 "x" 0]} (lite q)))
      (is (= "INSERT INTO t (id, n) VALUES ($1, $2) ON CONFLICT ((LOWER(n))) DO UPDATE SET n = excluded.n WHERE t.n < excluded.n" (pg-text w)))
      (is (= "INSERT INTO t (id, n) VALUES (?, ?) ON CONFLICT ((LOWER(n))) DO UPDATE SET n = excluded.n WHERE t.n < excluded.n" (lite-text w)))))
  (testing ":on-constraint is a format-error under :sqlite"
    (let [q (assoc ins :on-conflict {:on-constraint :t_pkey :do-nothing true})
          d (lite-error q)]
      (is (= "INSERT INTO t (id, n) VALUES ($1, $2) ON CONFLICT ON CONSTRAINT t_pkey DO NOTHING" (pg-text q)))
      (is (= :on-conflict (:op d)))
      (is (= :t_pkey (:arg d)))
      (is (re-find #"ON CONSTRAINT" (:message d)))))
  (testing "DO UPDATE SET without a target renders in both"
    (let [q (assoc ins :on-conflict {:do-update-set {:n :excluded/n}})]
      (is (= "INSERT INTO t (id, n) VALUES ($1, $2) ON CONFLICT DO UPDATE SET n = excluded.n" (pg-text q)))
      (is (= "INSERT INTO t (id, n) VALUES (?, ?) ON CONFLICT DO UPDATE SET n = excluded.n" (lite-text q)))))
  (testing "DO NOTHING without a target renders in both"
    (is (= "INSERT INTO t (id, n) VALUES (?, ?) ON CONFLICT DO NOTHING" (lite-text (assoc ins :on-conflict {:do-nothing true})))))
  (testing "INSERT ... SELECT with :on-conflict needs a :where in the select under :sqlite"
    (let [sel {:select [:id :n] :from [:s]}
          q (assoc ins :values sel :on-conflict {:on-columns [:id] :do-nothing true})
          d (lite-error q)
          w (assoc-in q [:values :where] [:> :id 0])]
      (is (= "INSERT INTO t (id, n) SELECT id, n FROM s ON CONFLICT (id) DO NOTHING" (pg-text q)))
      (is (= :on-conflict (:op d)))
      (is (= sel (:arg d)))
      (is (re-find #"WHERE" (:message d)))
      (is (= {:text "INSERT INTO t (id, n) SELECT id, n FROM s WHERE id > $1 ON CONFLICT (id) DO NOTHING" :values [0]} (pg w)))
      (is (= {:text "INSERT INTO t (id, n) SELECT id, n FROM s WHERE id > ? ON CONFLICT (id) DO NOTHING" :values [0]} (lite w)))))
  (testing "INSERT ... SELECT without :on-conflict needs no :where"
    (is (= "INSERT INTO t (id, n) SELECT id, n FROM s" (lite-text (assoc ins :values {:select [:id :n] :from [:s]}))))))

(deftest default-as-a-value
  (testing "in a VALUES row"
    (let [q {:insert-into :t :columns [:a :b] :values [[1 :default]]}
          d (lite-error q)]
      (is (= {:text "INSERT INTO t (a, b) VALUES ($1, DEFAULT)" :values [1]} (pg q)))
      (is (= :sql-cljs/format-error (:type d)))
      (is (= :values (:op d)))
      (is (= :default (:arg d)))
      (is (re-find #"SQLite has no DEFAULT key word" (:message d)))))
  (testing "in a map row"
    (let [q {:insert-into :t :values [{:a 1 :b :default}]}]
      (is (= "INSERT INTO t (a, b) VALUES ($1, DEFAULT)" (pg-text q)))
      (is (= :values (:op (lite-error q))))))
  (testing "a map row that leaves a column out is the same error in both dialects"
    (let [q {:insert-into :t :columns [:a :b] :values [{:a 1}]}]
      (is (= :values (:op (lite-error q))))
      (is (= (thrown-data #(pg q)) (lite-error q)))))
  (testing "in SET"
    (let [q {:update :t :set {:a :default} :where [:= :id 1]}]
      (is (= "UPDATE t SET a = DEFAULT WHERE id = $1" (pg-text q)))
      (is (= :set (:op (lite-error q))))))
  (testing "in DO UPDATE SET"
    (let [q {:insert-into :t :columns [:id] :values [[1]] :on-conflict {:on-columns [:id] :do-update-set {:n :default}}}]
      (is (= "INSERT INTO t (id) VALUES ($1) ON CONFLICT (id) DO UPDATE SET n = DEFAULT" (pg-text q)))
      (is (= :do-update-set (:op (lite-error q))))))
  (testing "the symbol spelling"
    (is (= :values (:op (lite-error '{:insert-into t :columns [a] :values [[default]]}))))))

;; ---------------------------------------------------------------------------
;; DELETE USING, TRUNCATE, CTE bodies
;; ---------------------------------------------------------------------------

(deftest delete-using
  (let [q {:delete-from :t :using [:u] :where [:= :t.id :u.id]}
        d (lite-error q)]
    (is (= "DELETE FROM t USING u WHERE t.id = u.id" (pg-text q)))
    (is (= :using (:op d)))
    (is (= [:u] (:arg d)))
    (is (re-find #"no USING" (:message d))))
  (testing "the subquery form renders identically"
    (let [q {:delete-from :t :where [:in :id {:select [:id] :from [:u]}]}]
      (is (= "DELETE FROM t WHERE id IN (SELECT id FROM u)" (pg-text q)))
      (is (= "DELETE FROM t WHERE id IN (SELECT id FROM u)" (lite-text q))))))

(deftest truncate
  (let [q {:truncate :t}
        d (lite-error q)]
    (is (= {:text "TRUNCATE TABLE t" :values []} (pg q)))
    (is (= :sql-cljs/format-error (:type d)))
    (is (= :truncate (:op d)))
    (is (= :t (:arg d)))
    (is (re-find #"SQLite has no TRUNCATE" (:message d))))
  (testing "with any flags"
    (let [q {:truncate [:t :u] :restart-identity true :cascade true}]
      (is (= "TRUNCATE TABLE t, u RESTART IDENTITY CASCADE" (pg-text q)))
      (is (= [:t :u] (:arg (lite-error q))))))
  (testing "the DELETE FROM idiom renders identically"
    (is (= "DELETE FROM t" (lite-text {:delete-from :t})))
    (is (= "DELETE FROM t" (pg-text {:delete-from :t})))))

(deftest cte-bodies
  (testing "a data-modifying CTE"
    (let [body {:update :t :set {:a 1} :returning [:id]}
          q {:with [[:m body]] :select [:id] :from [:m]}
          d (lite-error q)]
      (is (= {:text "WITH m AS (UPDATE t SET a = $1 RETURNING id) SELECT id FROM m" :values [1]} (pg q)))
      (is (= :sql-cljs/format-error (:type d)))
      (is (= :with (:op d)))
      (is (= body (:arg d)))
      (is (re-find #"select statements" (:message d)))))
  (testing "INSERT and DELETE bodies, :with-recursive"
    (is (= :with (:op (lite-error {:with [[:m {:insert-into :t :columns [:a] :values [[1]] :returning [:id]}]] :select [:id] :from [:m]}))))
    (is (= :with (:op (lite-error {:with [[:m {:delete-from :t :returning [:id]}]] :select [:id] :from [:m]}))))
    (is (= :with-recursive (:op (lite-error {:with-recursive [[:m {:delete-from :t :returning [:id]}]] :select [:id] :from [:m]}))))
    (is (= "WITH m AS (DELETE FROM t RETURNING id) SELECT id FROM m"
           (pg-text {:with [[:m {:delete-from :t :returning [:id]}]] :select [:id] :from [:m]}))))
  (testing "select bodies render identically, with a CTE over a modification too"
    (let [q {:with-recursive [[[:n :x] {:select [:a :b] :from [:t]}]] :select [:x] :from [:n]}
          m {:with [[:w {:select [:id] :from [:u]}]] :delete-from :t :where [:in :id {:select [:id] :from [:w]}]}]
      (is (= "WITH RECURSIVE n(x) AS (SELECT a, b FROM t) SELECT x FROM n" (pg-text q)))
      (is (= "WITH RECURSIVE n(x) AS (SELECT a, b FROM t) SELECT x FROM n" (lite-text q)))
      (is (= "WITH w AS (SELECT id FROM u) DELETE FROM t WHERE id IN (SELECT id FROM w)" (pg-text m)))
      (is (= "WITH w AS (SELECT id FROM u) DELETE FROM t WHERE id IN (SELECT id FROM w)" (lite-text m))))))

;; ---------------------------------------------------------------------------
;; CREATE TABLE: serial columns, defaults
;; ---------------------------------------------------------------------------

(deftest serial-columns
  (testing ":serial :primary-key is SERIAL PRIMARY KEY under :pg, INTEGER PRIMARY KEY AUTOINCREMENT under :sqlite"
    (let [q {:create-table :t :columns [[:id :serial :primary-key]]}]
      (is (= {:text "CREATE TABLE t (id SERIAL PRIMARY KEY)" :values []} (pg q)))
      (is (= {:text "CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT)" :values []} (lite q)))))
  (testing ":bigserial and :smallserial render INTEGER the same way"
    (is (= "CREATE TABLE t (id BIGSERIAL PRIMARY KEY)" (pg-text {:create-table :t :columns [[:id :bigserial :primary-key]]})))
    (is (= "CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT)" (lite-text {:create-table :t :columns [[:id :bigserial :primary-key]]})))
    (is (= "CREATE TABLE t (id SMALLSERIAL PRIMARY KEY)" (pg-text {:create-table :t :columns [[:id :smallserial :primary-key]]})))
    (is (= "CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT)" (lite-text {:create-table :t :columns [[:id :smallserial :primary-key]]}))))
  (testing "AUTOINCREMENT follows the PRIMARY KEY constraint wherever it stands, named or not"
    (let [q {:create-table :t :columns [[:id :serial :not-null :primary-key [:check [:> :id 0]]] [:n :text]]}
          named {:create-table :t :columns [[:id :serial [:constraint :pk :primary-key]]]}]
      (is (= "CREATE TABLE t (id SERIAL NOT NULL PRIMARY KEY CHECK (id > 0), n TEXT)" (pg-text q)))
      (is (= "CREATE TABLE t (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT CHECK (id > 0), n TEXT)" (lite-text q)))
      (is (= "CREATE TABLE t (id SERIAL CONSTRAINT pk PRIMARY KEY)" (pg-text named)))
      (is (= "CREATE TABLE t (id INTEGER CONSTRAINT pk PRIMARY KEY AUTOINCREMENT)" (lite-text named)))))
  (testing "the symbol spelling"
    (is (= "CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT)" (lite-text '{:create-table t :columns [[id serial primary-key]]}))))
  (testing "a serial column without :primary-key is a format-error naming the idiom"
    (let [q {:create-table :t :columns [[:id :serial]]}
          d (lite-error q)]
      (is (= "CREATE TABLE t (id SERIAL)" (pg-text q)))
      (is (= :sql-cljs/format-error (:type d)))
      (is (= :columns (:op d)))
      (is (= [:id :serial] (:arg d)))
      (is (re-find #"INTEGER PRIMARY KEY \[AUTOINCREMENT\]" (:message d)))
      (is (re-find #"\[:id :integer :primary-key\]" (:message d))))
    (is (= [:n :bigserial :unique] (:arg (lite-error {:create-table :t :columns [[:n :bigserial :unique]]}))))
    (testing "a table-level primary key does not count: the constraint must be on the column"
      (is (= :columns (:op (lite-error {:create-table :t :columns [[:id :serial]] :constraints [[:primary-key :id]]}))))))
  (testing ":integer :primary-key is the plain rowid alias, no AUTOINCREMENT, in both"
    (is (= "CREATE TABLE t (id INTEGER PRIMARY KEY)" (pg-text {:create-table :t :columns [[:id :integer :primary-key]]})))
    (is (= "CREATE TABLE t (id INTEGER PRIMARY KEY)" (lite-text {:create-table :t :columns [[:id :integer :primary-key]]})))))

(deftest default-constraints
  (testing "a function-call default is parenthesized under :sqlite"
    (let [q {:create-table :t :columns [[:slug :text [:default [:lower :name]]]]}]
      (is (= "CREATE TABLE t (slug TEXT DEFAULT LOWER(name))" (pg-text q)))
      (is (= "CREATE TABLE t (slug TEXT DEFAULT (LOWER(name)))" (lite-text q)))))
  (testing "an operator default is parenthesized in both"
    (let [q {:create-table :t :columns [[:n :integer [:default [:+ 1 2]]]]}]
      (is (= "CREATE TABLE t (n INTEGER DEFAULT (1 + 2))" (pg-text q)))
      (is (= "CREATE TABLE t (n INTEGER DEFAULT (1 + 2))" (lite-text q)))))
  (testing "literals, nil and booleans are bare in both"
    (let [q {:create-table :t :columns [[:a :integer [:default 0]] [:b :text [:default "x"]] [:c :boolean [:default true]] [:d :text [:default nil]]]}]
      (is (= "CREATE TABLE t (a INTEGER DEFAULT 0, b TEXT DEFAULT 'x', c BOOLEAN DEFAULT TRUE, d TEXT DEFAULT NULL)" (pg-text q)))
      (is (= {:text "CREATE TABLE t (a INTEGER DEFAULT 0, b TEXT DEFAULT 'x', c BOOLEAN DEFAULT TRUE, d TEXT DEFAULT NULL)" :values []} (lite q)))))
  (testing "raw defaults splice verbatim in both spellings"
    (is (= "CREATE TABLE t (a TEXT DEFAULT now())" (lite-text {:create-table :t :columns [[:a :text [:default (sql/raw "now()")]]]})))
    (is (= "CREATE TABLE t (a TEXT DEFAULT now())" (lite-text {:create-table :t :columns [[:a :text [:default [:raw "now()"]]]]})))
    (is (= "CREATE TABLE t (a TEXT DEFAULT now())" (pg-text {:create-table :t :columns [[:a :text [:default [:raw "now()"]]]]}))))
  (testing "a function call with arguments, nested"
    (let [q {:create-table :t :columns [[:a :text [:default [:coalesce [:lower :b] "z"]]]]}]
      (is (= "CREATE TABLE t (a TEXT DEFAULT COALESCE(LOWER(b), 'z'))" (pg-text q)))
      (is (= "CREATE TABLE t (a TEXT DEFAULT (COALESCE(LOWER(b), 'z')))" (lite-text q))))))

;; ---------------------------------------------------------------------------
;; ALTER TABLE
;; ---------------------------------------------------------------------------

(deftest alter-table
  (testing "one ADD COLUMN, DROP COLUMN or RENAME COLUMN renders identically"
    (let [add {:alter-table :t :actions [[:add-column [:c :text :not-null]]]}
          drop {:alter-table :t :actions [[:drop-column :c]]}
          ren {:alter-table :t :actions [[:rename-column :a :b]]}]
      (is (= {:text "ALTER TABLE t ADD COLUMN c TEXT NOT NULL" :values []} (pg add)))
      (is (= {:text "ALTER TABLE t ADD COLUMN c TEXT NOT NULL" :values []} (lite add)))
      (is (= "ALTER TABLE t DROP COLUMN c" (pg-text drop)))
      (is (= "ALTER TABLE t DROP COLUMN c" (lite-text drop)))
      (is (= "ALTER TABLE t RENAME COLUMN a TO b" (pg-text ren)))
      (is (= "ALTER TABLE t RENAME COLUMN a TO b" (lite-text ren)))))
  (testing "several actions are a format-error under :sqlite"
    (let [q {:alter-table :t :actions [[:add-column [:c :text]] [:drop-column :d]]}
          d (lite-error q)]
      (is (= "ALTER TABLE t ADD COLUMN c TEXT, DROP COLUMN d" (pg-text q)))
      (is (= :sql-cljs/format-error (:type d)))
      (is (= :actions (:op d)))
      (is (= [[:add-column [:c :text]] [:drop-column :d]] (:arg d)))
      (is (re-find #"exactly one action" (:message d)))))
  (testing "the statement's :if-exists"
    (let [q {:alter-table :t :if-exists true :actions [[:add-column [:c :text]]]}
          d (lite-error q)]
      (is (= "ALTER TABLE IF EXISTS t ADD COLUMN c TEXT" (pg-text q)))
      (is (= :if-exists (:op d)))
      (is (= true (:arg d))))
    (is (= "ALTER TABLE t ADD COLUMN c TEXT" (lite-text {:alter-table :t :if-exists false :actions [[:add-column [:c :text]]]}))))
  (testing ":if-not-exists on :add-column"
    (let [q {:alter-table :t :actions [[:add-column [:c :text] :if-not-exists]]}
          d (lite-error q)]
      (is (= "ALTER TABLE t ADD COLUMN IF NOT EXISTS c TEXT" (pg-text q)))
      (is (= :add-column (:op d)))
      (is (= [:add-column [:c :text] :if-not-exists] (:arg d)))))
  (testing ":if-exists, :cascade and :restrict on :drop-column"
    (doseq [[opt text] [[:if-exists "ALTER TABLE t DROP COLUMN IF EXISTS c"]
                        [:cascade "ALTER TABLE t DROP COLUMN c CASCADE"]
                        [:restrict "ALTER TABLE t DROP COLUMN c RESTRICT"]]]
      (let [q {:alter-table :t :actions [[:drop-column :c opt]]}
            d (lite-error q)]
        (is (= text (pg-text q)))
        (is (= :drop-column (:op d)))
        (is (= [:drop-column :c opt] (:arg d)))
        (is (re-find (re-pattern (str "drop " opt)) (:message d))))))
  (testing "a serial column added under :sqlite follows the serial rule"
    (is (= :add-column (:op (lite-error {:alter-table :t :actions [[:add-column [:n :serial]]]}))))))

;; ---------------------------------------------------------------------------
;; CREATE INDEX, DROP INDEX / TABLE / VIEW, CREATE VIEW
;; ---------------------------------------------------------------------------

(deftest create-index
  (testing "names, UNIQUE, IF NOT EXISTS, ASC / DESC, expressions and the partial-index predicate render identically"
    (let [q {:create-index :i :on :t :columns [:a]}
          full {:create-index :i :unique true :if-not-exists true :on :t
                :columns [[:a :desc] [:b :asc] [[:lower :email]] [[:+ :a :b]]] :where [:> :a 0]}
          text "CREATE UNIQUE INDEX IF NOT EXISTS i ON t (a DESC, b ASC, LOWER(email), (a + b)) WHERE a > 0"]
      (is (= {:text "CREATE INDEX i ON t (a)" :values []} (pg q)))
      (is (= {:text "CREATE INDEX i ON t (a)" :values []} (lite q)))
      (is (= {:text text :values []} (pg full)))
      (is (= {:text text :values []} (lite full)))))
  (testing ":using (the index method)"
    (let [q {:create-index :i :on :t :using :gin :columns [:a]}
          d (lite-error q)]
      (is (= "CREATE INDEX i ON t USING gin (a)" (pg-text q)))
      (is (= :sql-cljs/format-error (:type d)))
      (is (= :using (:op d)))
      (is (= :gin (:arg d)))
      (is (re-find #"no index method" (:message d)))))
  (testing ":concurrently"
    (let [q {:create-index :i :concurrently true :on :t :columns [:a]}
          d (lite-error q)]
      (is (= "CREATE INDEX CONCURRENTLY i ON t (a)" (pg-text q)))
      (is (= :concurrently (:op d)))
      (is (= true (:arg d))))
    (is (= "CREATE INDEX i ON t (a)" (lite-text {:create-index :i :concurrently false :on :t :columns [:a]}))))
  (testing "an unnamed index"
    (let [q {:create-index nil :on :t :columns [:a]}
          d (lite-error q)]
      (is (= "CREATE INDEX ON t (a)" (pg-text q)))
      (is (= :create-index (:op d)))
      (is (= q (:arg d)))
      (is (re-find #"needs an index name" (:message d)))))
  (testing ":nulls-first / :nulls-last on a column"
    (let [q {:create-index :i :on :t :columns [[:a :desc :nulls-last]]}
          d (lite-error q)]
      (is (= "CREATE INDEX i ON t (a DESC NULLS LAST)" (pg-text q)))
      (is (= :columns (:op d)))
      (is (= [:a :desc :nulls-last] (:arg d)))
      (is (re-find #"NULLS FIRST / NULLS LAST" (:message d))))
    (is (= [:b :nulls-first] (:arg (lite-error {:create-index :i :on :t :columns [:a [:b :nulls-first]]}))))))

(deftest drop-statements
  (testing "one name with IF EXISTS renders identically for TABLE, INDEX and VIEW"
    (doseq [[q text] [[{:drop-table :t :if-exists true} "DROP TABLE IF EXISTS t"]
                      [{:drop-table [:t]} "DROP TABLE t"]
                      [{:drop-index :i :if-exists true} "DROP INDEX IF EXISTS i"]
                      [{:drop-view :v :if-exists true} "DROP VIEW IF EXISTS v"]
                      [{:drop-view :v :cascade false :restrict nil} "DROP VIEW v"]]]
      (is (= {:text text :values []} (pg q)))
      (is (= {:text text :values []} (lite q)))))
  (testing "several names"
    (doseq [[q text] [[{:drop-table [:t :u]} "DROP TABLE t, u"]
                      [{:drop-index [:i :j] :if-exists true} "DROP INDEX IF EXISTS i, j"]
                      [{:drop-view [:v :w]} "DROP VIEW v, w"]]]
      (let [head (first (keys q))
            d (lite-error q)]
        (is (= text (pg-text q)))
        (is (= :sql-cljs/format-error (:type d)))
        (is (= head (:op d)))
        (is (= (get q head) (:arg d)))
        (is (re-find #"exactly one" (:message d))))))
  (testing ":cascade and :restrict"
    (doseq [[q text] [[{:drop-table :t :cascade true} "DROP TABLE t CASCADE"]
                      [{:drop-table :t :restrict true} "DROP TABLE t RESTRICT"]
                      [{:drop-index :i :cascade true} "DROP INDEX i CASCADE"]
                      [{:drop-view :v :if-exists true :cascade true} "DROP VIEW IF EXISTS v CASCADE"]
                      [{:drop-view :v :restrict true} "DROP VIEW v RESTRICT"]]]
      (let [flag (if (contains? q :cascade) :cascade :restrict)
            d (lite-error q)]
        (is (= text (pg-text q)))
        (is (= flag (:op d)))
        (is (= true (:arg d)))
        (is (re-find #"no CASCADE / RESTRICT" (:message d))))))
  (testing "DROP INDEX CONCURRENTLY"
    (let [q {:drop-index :i :concurrently true :if-exists true}
          d (lite-error q)]
      (is (= "DROP INDEX CONCURRENTLY IF EXISTS i" (pg-text q)))
      (is (= :concurrently (:op d)))
      (is (= true (:arg d))))))

(deftest create-view
  (testing "explicit column names and a statement body render identically"
    (let [q {:create-view :v :columns [:a :b] :as {:select [:x :y] :from [:t] :where [:= :x 1]}}]
      (is (= {:text "CREATE VIEW v (a, b) AS SELECT x, y FROM t WHERE x = 1" :values []} (pg q)))
      (is (= {:text "CREATE VIEW v (a, b) AS SELECT x, y FROM t WHERE x = 1" :values []} (lite q)))))
  (testing ":or-replace"
    (let [q {:create-view :v :or-replace true :as {:select [:a] :from [:t]}}
          d (lite-error q)]
      (is (= "CREATE OR REPLACE VIEW v AS SELECT a FROM t" (pg-text q)))
      (is (= :sql-cljs/format-error (:type d)))
      (is (= :or-replace (:op d)))
      (is (= true (:arg d)))
      (is (re-find #"DROP VIEW IF EXISTS first" (:message d))))
    (is (= "CREATE VIEW v AS SELECT a FROM t" (lite-text {:create-view :v :or-replace false :as {:select [:a] :from [:t]}})))))

;; ---------------------------------------------------------------------------
;; DDL is inline in both dialects, :values []
;; ---------------------------------------------------------------------------

(deftest ddl-inlines-under-sqlite
  (let [t {:create-table :t :columns [[:a :text [:default "it's"]] [:b :integer [:check [:> :b 0]]] [:c :boolean [:default false]]]}
        i {:create-index :i :on :t :columns [:a] :where [:= :a "y"]}
        v {:create-view :v :as {:select [:a] :from [:t] :where [:and [:= :a "x"] [:= :b true] [:is-null :c]]}}]
    (is (= {:text "CREATE TABLE t (a TEXT DEFAULT 'it''s', b INTEGER CHECK (b > 0), c BOOLEAN DEFAULT FALSE)" :values []} (lite t)))
    (is (= (pg t) (lite t)))
    (is (= {:text "CREATE INDEX i ON t (a) WHERE a = 'y'" :values []} (lite i)))
    (is (= (pg i) (lite i)))
    (is (= {:text "CREATE VIEW v AS SELECT a FROM t WHERE (a = 'x') AND (b = TRUE) AND (c IS NULL)" :values []} (lite v)))
    (is (= (pg v) (lite v)))
    (testing "the same whatever :inline says"
      (is (= (lite t) (sql/format t {:dialect :sqlite :inline true})))
      (is (= (lite v) (sql/format v {:dialect :sqlite :inline false}))))
    (testing "a value with no literal spelling is the same error in both"
      (let [q {:create-table :t :columns [[:a :text [:default identity]]]}]
        (is (= :inline (:op (lite-error q))))
        (is (= :inline (:op (thrown-data #(pg q)))))))))

;; ---------------------------------------------------------------------------
;; PostgreSQL does not regress: no :dialect equals {:dialect :pg}, text and values
;; ---------------------------------------------------------------------------

(def ^:private pg-rendered-statements
  "Every statement map above that PostgreSQL renders (including those the
   :sqlite dialect rejects)."
  [{:select [:a :b] :from [:foo] :where [:= :foo.a "baz"]}
   {:select [:a] :from [:t] :where [:and [:= :a 1] [:in :b [2 3]] [:= :c 1]]}
   {:insert-into :t :columns [:a :b] :values [[1 "x"] [2 "y"]]}
   {:update :t :set {:a 1} :where [:= :id 2]}
   {:delete-from :t :where [:= :id 2] :returning [:id]}
   {:with [[:x {:select [:id] :from [:u] :where [:= :k 1]}]] :select [:a] :from [:t]
    :where [:in :id {:select [:id] :from [:x] :where [:> :n 2]}]}
   {:select [:a_1$] :from [:s/t]}
   {:update :t :set {:a :u.a} :from [:u] :where [:= :t.id :u.id]}
   {:insert-into [:t :x] :columns [:a] :values [[1]] :returning [:*]}
   {:select [:a] :from [:t] :limit 5}
   {:select [:a] :from [:t] :limit :all}
   {:select [:a] :from [:t] :offset 10}
   {:select [:a] :from [:t] :order-by [:a] :limit 5 :offset 10}
   {:select [:a] :from [:t] :limit :all :offset 10}
   {:union [s-t s-u] :limit :all}
   {:union [s-t s-u] :offset 3}
   {:union [s-t s-u]}
   {:union-all [s-t s-u s-v] :order-by [:a] :limit 3}
   {:union [{:select [:a] :from [:t] :where [:= :a 1]} {:select [:a] :from [:u] :where [:= :a 2]}]}
   {:union [{:union [s-t s-u]} s-v]}
   {:union [(assoc s-t :order-by [:a]) s-u]}
   {:union-all [s-t (assoc s-u :limit 1)]}
   {:union [s-t (assoc s-u :for :update)]}
   {:union [(assoc s-t :with [[:w s-v]]) s-u]}
   {:with [[:w s-v]] :union [s-t s-u]}
   {:select [:a] :from [:t] :where [:ilike :name "a%"]}
   {:select [:a] :from [:t] :where [:not-ilike :name "a%"]}
   {:select [:a] :from [:t] :where [:like :name "a%"]}
   {:select [[[:now]]]}
   {:select [[[:current_timestamp]]]}
   {:select [:a :b] :distinct-on [:a] :from [:t] :order-by [:a]}
   {:select [:a] :distinct true :from [:t]}
   {:select [:a] :from [:t] :where [:= :id 1] :for :update}
   {:select [:a] :from [:t] :for [:share :t :nowait]}
   (assoc ins :on-conflict {:on-columns [:id] :do-update-set {:n :excluded/n}})
   (assoc ins :on-conflict {:on-columns [:id] :index-where [:> :id 0] :do-nothing true})
   (assoc ins :on-conflict {:on-columns [[:lower :n]] :do-update-set {:n :excluded/n} :where [:< :t.n :excluded/n]})
   (assoc ins :on-conflict {:on-constraint :t_pkey :do-nothing true})
   (assoc ins :on-conflict {:do-update-set {:n :excluded/n}})
   (assoc ins :on-conflict {:do-nothing true})
   (assoc ins :values {:select [:id :n] :from [:s]} :on-conflict {:on-columns [:id] :do-nothing true})
   (assoc ins :values {:select [:id :n] :from [:s] :where [:> :id 0]} :on-conflict {:on-columns [:id] :do-nothing true})
   (assoc ins :values {:select [:id :n] :from [:s]})
   {:insert-into :t :columns [:a :b] :values [[1 :default]]}
   {:insert-into :t :values [{:a 1 :b :default}]}
   {:update :t :set {:a :default} :where [:= :id 1]}
   {:insert-into :t :columns [:id] :values [[1]] :on-conflict {:on-columns [:id] :do-update-set {:n :default}}}
   {:delete-from :t :using [:u] :where [:= :t.id :u.id]}
   {:delete-from :t :where [:in :id {:select [:id] :from [:u]}]}
   {:truncate :t}
   {:truncate [:t :u] :restart-identity true :cascade true}
   {:delete-from :t}
   {:with [[:m {:update :t :set {:a 1} :returning [:id]}]] :select [:id] :from [:m]}
   {:with [[:m {:insert-into :t :columns [:a] :values [[1]] :returning [:id]}]] :select [:id] :from [:m]}
   {:with-recursive [[:m {:delete-from :t :returning [:id]}]] :select [:id] :from [:m]}
   {:with-recursive [[[:n :x] {:select [:a :b] :from [:t]}]] :select [:x] :from [:n]}
   {:with [[:w {:select [:id] :from [:u]}]] :delete-from :t :where [:in :id {:select [:id] :from [:w]}]}
   {:create-table :t :columns [[:id :serial :primary-key]]}
   {:create-table :t :columns [[:id :bigserial :primary-key]]}
   {:create-table :t :columns [[:id :smallserial :primary-key]]}
   {:create-table :t :columns [[:id :serial :not-null :primary-key [:check [:> :id 0]]] [:n :text]]}
   {:create-table :t :columns [[:id :serial [:constraint :pk :primary-key]]]}
   {:create-table :t :columns [[:id :serial]]}
   {:create-table :t :columns [[:n :bigserial :unique]]}
   {:create-table :t :columns [[:id :serial]] :constraints [[:primary-key :id]]}
   {:create-table :t :columns [[:id :integer :primary-key]]}
   {:create-table :t :columns [[:slug :text [:default [:lower :name]]]]}
   {:create-table :t :columns [[:n :integer [:default [:+ 1 2]]]]}
   {:create-table :t :columns [[:a :integer [:default 0]] [:b :text [:default "x"]] [:c :boolean [:default true]] [:d :text [:default nil]]]}
   {:create-table :t :columns [[:a :text [:default [:raw "now()"]]]]}
   {:create-table :t :columns [[:a :text [:default [:coalesce [:lower :b] "z"]]]]}
   {:alter-table :t :actions [[:add-column [:c :text :not-null]]]}
   {:alter-table :t :actions [[:drop-column :c]]}
   {:alter-table :t :actions [[:rename-column :a :b]]}
   {:alter-table :t :actions [[:add-column [:c :text]] [:drop-column :d]]}
   {:alter-table :t :if-exists true :actions [[:add-column [:c :text]]]}
   {:alter-table :t :actions [[:add-column [:c :text] :if-not-exists]]}
   {:alter-table :t :actions [[:drop-column :c :if-exists]]}
   {:alter-table :t :actions [[:drop-column :c :cascade]]}
   {:alter-table :t :actions [[:add-column [:n :serial]]]}
   {:create-index :i :on :t :columns [:a]}
   {:create-index :i :unique true :if-not-exists true :on :t
    :columns [[:a :desc] [:b :asc] [[:lower :email]] [[:+ :a :b]]] :where [:> :a 0]}
   {:create-index :i :on :t :using :gin :columns [:a]}
   {:create-index :i :concurrently true :on :t :columns [:a]}
   {:create-index nil :on :t :columns [:a]}
   {:create-index :i :on :t :columns [[:a :desc :nulls-last]]}
   {:drop-table :t :if-exists true}
   {:drop-table [:t :u]}
   {:drop-index [:i :j] :if-exists true}
   {:drop-view [:v :w]}
   {:drop-table :t :cascade true}
   {:drop-view :v :if-exists true :cascade true}
   {:drop-index :i :concurrently true :if-exists true}
   {:create-view :v :columns [:a :b] :as {:select [:x :y] :from [:t] :where [:= :x 1]}}
   {:create-view :v :or-replace true :as {:select [:a] :from [:t]}}])

(deftest pg-does-not-regress
  (testing "formatting without :dialect equals {:dialect :pg}, parameterized and inline"
    (doseq [q pg-rendered-statements]
      (is (= (sql/format q) (sql/format q {:dialect :pg})))
      (is (= (sql/format q nil) (sql/format q {:dialect :pg :inline false})))
      (is (= (sql/format q {:inline true}) (sql/format q {:dialect :pg :inline true})))))
  (testing "every statement above renders under :pg (the rejections are :sqlite's alone)"
    (doseq [q pg-rendered-statements]
      (is (string? (:text (sql/format q))))
      (is (vector? (:values (sql/format q)))))))
