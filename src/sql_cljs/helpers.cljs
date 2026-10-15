(ns sql-cljs.helpers
  "Builder functions, one per clause, each returning the plain statement
   map sql-cljs.core/format takes: (select :a :b) is {:select [:a :b]},
   (where [:= :a 1]) is {:where [:= :a 1]}, (insert-into :t) is
   {:insert-into :t}. The data is the API and the helpers are sugar over it:
   a statement built with helpers equals the literal map, formats
   identically, and composes with merge, assoc, dissoc and update like any
   map.

   Every helper also takes the statement being built as an optional first
   argument, so they thread: (-> (select :a) (from :t) (where [:= :a 1])) is
   {:select [:a] :from [:t] :where [:= :a 1]}. A repeated clause merges the
   HoneySQL way: a vector clause concatenates ((-> (select :a) (select :b))
   is {:select [:a :b]}), where and having combine with [:and ...], a map
   clause (:set, :on-conflict) merges its keys, and a scalar clause (:from
   target tables, :limit, :offset, flags) replaces. Since a leading map is
   the statement, a subquery given as a clause item is written nested,
   (select [[{:select [:a] :from [:u]} :n]]) or (from [[{...} :s]]); the
   set-operation helpers union and union-all are the one exception: their
   arguments are all operands and they never thread. The data definition
   helpers follow the same pattern: (-> (create-table :t) (columns [:id
   :serial :primary-key]) (if-not-exists)) is {:create-table :t :columns
   [[:id :serial :primary-key]] :if-not-exists true}, and the alter-table
   actions add-column, drop-column and rename-column concatenate into
   :actions.

   The helpers validate nothing: they shape data only, and malformed data
   is reported by format as a :sql-cljs/format-error. Clojure's own
   functions of the same name (for, set, update, group-by, distinct) are
   shadowed here; refer to this namespace with an alias, (:require
   [sql-cljs.helpers :as h])."
  (:refer-clojure :exclude [for set update group-by distinct]))

;; ---------------------------------------------------------------------------
;; Composition
;; ---------------------------------------------------------------------------

(defn- merge-clause
  "The statement m with the clause k set to v, merged with an existing v
   the HoneySQL way: vectors concatenate, maps merge, anything else replaces."
  [m k v]
  (let [old (get m k)]
    (cond
      (nil? old) (assoc m k v)
      (and (vector? old) (vector? v)) (assoc m k (into old v))
      (and (map? old) (map? v)) (assoc m k (merge old v))
      :else (assoc m k v))))

(defn- conjunction
  "The predicates combined into one condition: one predicate stands alone,
   several are [:and ...], and an existing [:and ...] takes the new ones."
  [old preds]
  (cond
    (nil? old) (if (= 1 (count preds)) (first preds) (into [:and] preds))
    (and (vector? old) (= :and (first old))) (into old preds)
    :else (into [:and old] preds)))

(defn- leading-map
  "[statement args]: args split from the optional leading statement map."
  [args]
  (if (map? (first args))
    [(first args) (rest args)]
    [{} args]))

(defn- items
  "The variadic clause k of the items in args, threaded on a leading map:
   {k [items...]}."
  [k args]
  (let [[m xs] (leading-map args)]
    (merge-clause m k (vec xs))))

(defn- scalar
  "The one-argument clause k: (k x) is {k x}, (k m x) is m with k x."
  [k args]
  (if (= 2 (count args))
    (merge-clause (first args) k (second args))
    {k (first args)}))

(defn- flag
  "The flag clause k: (k) is {k true}, (k m) is m with k true."
  [k args]
  (merge-clause (or (first args) {}) k true))

(defn- condition
  "The one-expression clause k of the predicates in args, combined with
   [:and ...] when several, threaded on a leading map."
  [k args]
  (let [[m preds] (leading-map args)]
    (if (seq preds)
      (assoc m k (conjunction (get m k) preds))
      m)))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn select
  "{:select [items...]}: (select :a [[:count :*] :n]) is
   {:select [:a [[:count :*] :n]]}. Repeated, the items concatenate."
  [& args]
  (items :select args))

(defn distinct
  "{:distinct true}: SELECT DISTINCT."
  [& args]
  (flag :distinct args))

(defn distinct-on
  "{:distinct-on [columns...]}: SELECT DISTINCT ON (a, b)."
  [& args]
  (items :distinct-on args))

(defn from
  "{:from [tables...]}: (from :t [:u :alias]) is {:from [:t [:u :alias]]}."
  [& args]
  (items :from args))

(defn join
  "{:join [table condition ...]}: (join :u [:= :t.id :u.t_id]) is
   {:join [:u [:= :t.id :u.t_id]]}; a USING condition is [:using :id]."
  [& args]
  (items :join args))

(defn inner-join
  "{:inner-join [table condition ...]}, the same pairs as join."
  [& args]
  (items :inner-join args))

(defn left-join
  "{:left-join [table condition ...]}, the same pairs as join."
  [& args]
  (items :left-join args))

(defn right-join
  "{:right-join [table condition ...]}, the same pairs as join."
  [& args]
  (items :right-join args))

(defn full-join
  "{:full-join [table condition ...]}, the same pairs as join."
  [& args]
  (items :full-join args))

(defn cross-join
  "{:cross-join [tables...]}: tables only, no condition."
  [& args]
  (items :cross-join args))

(defn where
  "{:where predicate}: (where [:= :a 1]) is {:where [:= :a 1]}; several
   predicates, or a repeated where, combine as [:and ...]. Next to
   create-index it is the partial-index predicate."
  [& args]
  (condition :where args))

(defn group-by
  "{:group-by [expressions...]}."
  [& args]
  (items :group-by args))

(defn having
  "{:having predicate}, combined with [:and ...] like where."
  [& args]
  (condition :having args))

(defn order-by
  "{:order-by [items...]}: (order-by :a [:b :desc]) is
   {:order-by [:a [:b :desc]]}."
  [& args]
  (items :order-by args))

(defn limit
  "{:limit n}: (limit 10) is {:limit 10}, (limit m 10) threads."
  [& args]
  (scalar :limit args))

(defn offset
  "{:offset n}: (offset 20) is {:offset 20}, (offset m 20) threads."
  [& args]
  (scalar :offset args))

(defn union
  "{:union [statements...]}: every argument is an operand, (union q1 q2)
   is {:union [q1 q2]}; this helper never threads."
  [& statements]
  {:union (vec statements)})

(defn union-all
  "{:union-all [statements...]}: every argument is an operand; never
   threads."
  [& statements]
  {:union-all (vec statements)})

(defn with
  "{:with [[name statement] ...]}: (with [:c {:select [:a] :from [:t]}]) is
   {:with [[:c {:select [:a] :from [:t]}]]}."
  [& args]
  (items :with args))

(defn with-recursive
  "{:with-recursive [[name statement] ...]}, the same items as with."
  [& args]
  (items :with-recursive args))

(defn for
  "{:for [strength tables... option]}: (for :update) is {:for [:update]},
   (for :update :t :nowait) is {:for [:update :t :nowait]}."
  [& args]
  (items :for args))

;; ---------------------------------------------------------------------------
;; INSERT
;; ---------------------------------------------------------------------------

(defn insert-into
  "{:insert-into table}: (insert-into :t) is {:insert-into :t},
   (insert-into [:t :alias]) keeps the alias pair."
  [& args]
  (scalar :insert-into args))

(defn columns
  "{:columns [columns...]}: (columns :a :b) is {:columns [:a :b]}, the
   column list of INSERT or of CREATE VIEW; for CREATE TABLE the items are
   column definitions, (columns [:id :serial :primary-key] [:name :text]),
   and for CREATE INDEX they are :order-by items, (columns :a [:b :desc])."
  [& args]
  (items :columns args))

(defn values
  "{:values rows}: takes the vector of rows as ONE argument,
   (values [[1 2] [3 4]]) is {:values [[1 2] [3 4]]} and (values [{:a 1}])
   is {:values [{:a 1}]}; a statement map, (values m {:select ...}), is
   INSERT ... SELECT. Repeated, the rows concatenate."
  [& args]
  (scalar :values args))

(defn on-conflict
  "{:on-conflict {:on-columns [columns...]}}: (on-conflict :a :b) is
   {:on-conflict {:on-columns [:a :b]}}, (on-conflict) is {:on-conflict {}}
   (no target, for do-nothing). The other on-conflict helpers merge their
   key into the same map."
  [& args]
  (let [[m cols] (leading-map args)]
    (merge-clause m :on-conflict (if (seq cols) {:on-columns (vec cols)} {}))))

(defn on-constraint
  "{:on-conflict {:on-constraint name}}: (on-constraint :t_pkey) is
   {:on-conflict {:on-constraint :t_pkey}}."
  [& args]
  (let [[m [c]] (leading-map args)]
    (merge-clause m :on-conflict {:on-constraint c})))

(defn index-where
  "{:on-conflict {:index-where predicate}}: the index predicate of the
   on-columns inference, combined with [:and ...] when several."
  [& args]
  (let [[m preds] (leading-map args)]
    (merge-clause m :on-conflict (condition :index-where (cons (get m :on-conflict {}) preds)))))

(defn do-nothing
  "{:on-conflict {:do-nothing true}}."
  [& args]
  (let [[m _] (leading-map args)]
    (merge-clause m :on-conflict {:do-nothing true})))

(defn do-update-set
  "{:on-conflict {:do-update-set assignments}}: (do-update-set {:a :excluded/a})
   is {:on-conflict {:do-update-set {:a :excluded/a}}}; the assignments are
   ONE argument, a map or a vector of [column expression] pairs, as in set."
  [& args]
  (let [[m a] (if (= 2 (count args)) args [{} (first args)])]
    (merge-clause m :on-conflict {:do-update-set a})))

(defn do-update-where
  "{:on-conflict {:where predicate}}: the condition of DO UPDATE, combined
   with [:and ...] when several."
  [& args]
  (let [[m preds] (leading-map args)]
    (merge-clause m :on-conflict (condition :where (cons (get m :on-conflict {}) preds)))))

(defn returning
  "{:returning [items...]}: (returning :id [[:lower :name] :n]) is
   {:returning [:id [[:lower :name] :n]]}, (returning :*) is {:returning [:*]}."
  [& args]
  (items :returning args))

;; ---------------------------------------------------------------------------
;; UPDATE, DELETE, TRUNCATE
;; ---------------------------------------------------------------------------

(defn update
  "{:update table}: (update :t) is {:update :t}, (update [:t :alias]) keeps
   the alias pair."
  [& args]
  (scalar :update args))

(defn set
  "{:set assignments}: (set {:a 1 :b :c}) is {:set {:a 1 :b :c}},
   (set [[:a 1] [:b :c]]) is {:set [[:a 1] [:b :c]]} (the pair vector keeps
   the order with more than eight assignments). Repeated with the same
   shape, maps merge and pair vectors concatenate."
  [& args]
  (scalar :set args))

(defn delete-from
  "{:delete-from table}: (delete-from :t) is {:delete-from :t}."
  [& args]
  (scalar :delete-from args))

(defn using
  "{:using [tables...]}: the USING tables of DELETE, as in from; next to
   create-index, (using :gin) names the index method (one item)."
  [& args]
  (items :using args))

(defn truncate
  "{:truncate [tables...]}: (truncate :t :u) is {:truncate [:t :u]}."
  [& args]
  (items :truncate args))

(defn restart-identity
  "{:restart-identity true}: TRUNCATE ... RESTART IDENTITY."
  [& args]
  (flag :restart-identity args))

(defn continue-identity
  "{:continue-identity true}: TRUNCATE ... CONTINUE IDENTITY."
  [& args]
  (flag :continue-identity args))

(defn cascade
  "{:cascade true}: TRUNCATE, DROP TABLE / INDEX / VIEW ... CASCADE."
  [& args]
  (flag :cascade args))

(defn restrict
  "{:restrict true}: TRUNCATE, DROP TABLE / INDEX / VIEW ... RESTRICT."
  [& args]
  (flag :restrict args))

;; ---------------------------------------------------------------------------
;; Data definition
;; ---------------------------------------------------------------------------

(defn create-table
  "{:create-table table}: (create-table :t) is {:create-table :t}. The
   column definitions come from columns, (columns [:id :serial :primary-key]
   [:name [:varchar 40] :not-null]), the table constraints from constraints."
  [& args]
  (scalar :create-table args))

(defn if-not-exists
  "{:if-not-exists true}: CREATE TABLE / INDEX ... IF NOT EXISTS."
  [& args]
  (flag :if-not-exists args))

(defn constraints
  "{:constraints [constraints...]}: the table constraints of CREATE TABLE,
   (constraints [:primary-key :a :b] [:unique :c]) is
   {:constraints [[:primary-key :a :b] [:unique :c]]}. Repeated, they
   concatenate."
  [& args]
  (items :constraints args))

(defn drop-table
  "{:drop-table [tables...]}: (drop-table :t :u) is {:drop-table [:t :u]}."
  [& args]
  (items :drop-table args))

(defn if-exists
  "{:if-exists true}: DROP TABLE / INDEX / VIEW, ALTER TABLE ... IF EXISTS."
  [& args]
  (flag :if-exists args))

(defn alter-table
  "{:alter-table table}: (alter-table :t) is {:alter-table :t}; the actions
   come from add-column, drop-column and rename-column, which concatenate
   into :actions."
  [& args]
  (scalar :alter-table args))

(defn add-column
  "{:actions [[:add-column definition & options]]}: (add-column [:x :int])
   is {:actions [[:add-column [:x :int]]]}, (add-column [:x :int]
   :if-not-exists) adds the option. The definition is ONE argument, the
   column vector of create-table."
  [& args]
  (let [[m xs] (leading-map args)]
    (merge-clause m :actions [(into [:add-column] xs)])))

(defn drop-column
  "{:actions [[:drop-column name & options]]}: (drop-column :x) is
   {:actions [[:drop-column :x]]}, (drop-column :x :if-exists :cascade) adds
   the options."
  [& args]
  (let [[m xs] (leading-map args)]
    (merge-clause m :actions [(into [:drop-column] xs)])))

(defn rename-column
  "{:actions [[:rename-column old new]]}: (rename-column :a :b) is
   {:actions [[:rename-column :a :b]]}."
  [& args]
  (let [[m xs] (leading-map args)]
    (merge-clause m :actions [(into [:rename-column] xs)])))

(defn create-index
  "{:create-index name}: (create-index :i) is {:create-index :i};
   (create-index) is {:create-index nil}, an unnamed index. A leading map
   threads: (create-index m :i), or (create-index m) for an unnamed index.
   The table comes from on, the columns from columns, the method from
   using, the predicate from where."
  [& args]
  (let [[m [nm]] (leading-map args)]
    (merge-clause m :create-index nm)))

(defn unique
  "{:unique true}: CREATE UNIQUE INDEX."
  [& args]
  (flag :unique args))

(defn concurrently
  "{:concurrently true}: CREATE INDEX / DROP INDEX ... CONCURRENTLY."
  [& args]
  (flag :concurrently args))

(defn on
  "{:on table}: the table of CREATE INDEX, (on :t) is {:on :t}."
  [& args]
  (scalar :on args))

(defn drop-index
  "{:drop-index [indexes...]}: (drop-index :i :j) is {:drop-index [:i :j]}."
  [& args]
  (items :drop-index args))

(defn create-view
  "{:create-view view}: (create-view :v) is {:create-view :v}; the body
   comes from as, the optional column names from columns."
  [& args]
  (scalar :create-view args))

(defn or-replace
  "{:or-replace true}: CREATE OR REPLACE VIEW."
  [& args]
  (flag :or-replace args))

(defn as
  "{:as body}: the body of CREATE VIEW, ONE argument, a statement map or a
   raw fragment: (as {:select [:a] :from [:t]}) is
   {:as {:select [:a] :from [:t]}}; (as m {...}) threads."
  [& args]
  (scalar :as args))

(defn drop-view
  "{:drop-view [views...]}: (drop-view :v :w) is {:drop-view [:v :w]}."
  [& args]
  (items :drop-view args))
