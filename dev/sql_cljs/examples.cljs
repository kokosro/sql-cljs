(ns sql-cljs.examples
  "The examples of the README, runnable OFFLINE:

     npx shadow-cljs compile examples && node target/examples.js

   The library is pure formatting: every example is a data map in and the
   {:text :values} map a PostgreSQL client (or, under {:dialect :sqlite}, a
   SQLite client) sends out, so nothing here needs a server, a network or a
   credential. Each example is a named function
   returning its data; `main` prints each name and its result.

   What stands between a README:begin and a README:end mark stands in
   README.md as written (test/sql_cljs/examples_test.cljs holds the README
   to it and runs every function of this file)."
  (:require [cljs.pprint :as pprint]
            [sql-cljs.core :as sql]
            [sql-cljs.helpers :as h]))

;; ---------------------------------------------------------------------------
;; Quick start
;; ---------------------------------------------------------------------------

;; README:begin quick-start
(defn products-under
  "The products cheaper than `max-cents`, as the parameterized query a
   PostgreSQL client sends: the price travels as $1, untouched."
  [max-cents]
  (sql/format {:select   [:id :name :price_cents]
               :from     [:products]
               :where    [:< :price_cents max-cents]
               :order-by [:id]}))
;; README:end quick-start

(defn quick-start
  "`products-under` for 500 cents, and the same statement inlined for a
   console."
  []
  {:query  (products-under 500)
   :inline (sql/format {:select   [:id :name :price_cents]
                        :from     [:products]
                        :where    [:< :price_cents 500]
                        :order-by [:id]}
                       {:inline true})})

;; ---------------------------------------------------------------------------
;; Going live: the composition with pg-cljs. Not run here (pg-cljs is not a
;; dependency of this library); the form is what an application writes.
;; ---------------------------------------------------------------------------

(comment
  ;; (:require [promesa.core :as p] [pg-cljs.core :as pg] [sql-cljs.core :as sql])
  ;; README:begin going-live
  (p/let [client (pg/connect! {:host "db.example.test" :user "shop" :database "shop"})
          result (pg/query! client (sql/format {:select [:id :name :price_cents]
                                                :from   [:products]
                                                :where  [:< :price_cents 500]}))
          _      (pg/end! client)]
    (:rows result))
  ;; README:end going-live
  )

;; ---------------------------------------------------------------------------
;; Dialects: one statement map, the PostgreSQL text and the SQLite text
;; ---------------------------------------------------------------------------

;; README:begin dialect
(defn products-named-like
  "The products whose name matches `pattern`, rendered for both dialects
   from one statement map: {:dialect :pg} (the default) spells $N
   placeholders and ILIKE, {:dialect :sqlite} spells ? placeholders and
   LIKE; the values are the same vector either way."
  [pattern]
  (let [statement {:select   [:id :name]
                   :from     [:products]
                   :where    [:ilike :name pattern]
                   :order-by [:id]
                   :limit    20}]
    {:pg     (sql/format statement {:dialect :pg})
     :sqlite (sql/format statement {:dialect :sqlite})}))
;; README:end dialect

(defn dialects
  "`products-named-like` for the pattern \"%pen%\"."
  []
  (products-named-like "%pen%"))

;; ---------------------------------------------------------------------------
;; Scenario: a query built in pieces
;; ---------------------------------------------------------------------------

;; README:begin pieces
(defn search-products
  "The products matching `filters`, built clause by clause with the
   helpers: each filter present adds a predicate and `where` combines them
   with AND. The result is the same plain map a literal would be."
  [{:keys [name-like max-cents in-stock?]}]
  (cond-> (-> (h/select :id :name :price_cents)
              (h/from :products)
              (h/order-by [:price_cents :desc] :id)
              (h/limit 20))
    name-like (h/where [:ilike :name name-like])
    max-cents (h/where [:<= :price_cents max-cents])
    in-stock? (h/where [:> :stock 0])))
;; README:end pieces

(defn pieces
  "`search-products` with two filters: the map it builds and its SQL."
  []
  (let [q (search-products {:name-like "%pen%" :max-cents 500})]
    {:statement q
     :query     (sql/format q)}))

;; ---------------------------------------------------------------------------
;; Scenario: a report
;; ---------------------------------------------------------------------------

;; README:begin report
(defn top-customers
  "Customers by amount spent on paid orders since `since`: a join with a
   condition, a join with USING, two aggregates with aliases, GROUP BY,
   HAVING, ORDER BY with modifiers and a page of `page-size` rows."
  [since page-size page]
  (sql/format {:select    [:c.id :c.name [[:count :*] :orders] [[:sum :o.total_cents] :spent]]
               :from      [[:customers :c]]
               :join      [[:orders :o] [:= :o.customer_id :c.id]]
               :left-join [:regions [:using :region_id]]
               :where     [:and [:>= :o.placed_at since] [:= :o.status "paid"]]
               :group-by  [:c.id :c.name]
               :having    [:> [:count :*] 1]
               :order-by  [[:spent :desc :nulls-last] :c.id]
               :limit     page-size
               :offset    (* page page-size)}))
;; README:end report

(defn report
  "`top-customers` for the second page of ten since the start of the year."
  []
  (top-customers "2026-Jan-01" 10 1))

;; ---------------------------------------------------------------------------
;; Scenario: a work queue
;; ---------------------------------------------------------------------------

;; README:begin queue
(defn claim-jobs
  "Claims up to `n` queued jobs for `worker`: a CTE locks the next rows with
   FOR UPDATE SKIP LOCKED, the UPDATE reaches them through FROM, and
   RETURNING hands back what was claimed."
  [worker n]
  (sql/format {:with      [[:next {:select   [:id]
                                   :from     [:jobs]
                                   :where    [:= :state "queued"]
                                   :order-by [:id]
                                   :limit    n
                                   :for      [:update :skip-locked]}]]
               :update    :jobs
               :set       {:state "running" :worker worker}
               :from      [:next]
               :where     [:= :jobs.id :next.id]
               :returning [:jobs.id :jobs.payload]}))

(defn category-tree
  "Every category under `root-id` with its depth: WITH RECURSIVE over a
   UNION ALL of the root and the children of what was found."
  [root-id]
  (sql/format {:with-recursive [[[:tree :id :parent_id :depth]
                                 {:union-all [{:select [:id :parent_id [0 :depth]]
                                               :from   [:categories]
                                               :where  [:= :id root-id]}
                                              {:select [:c.id :c.parent_id [[:+ :tree.depth 1] :depth]]
                                               :from   [[:categories :c]]
                                               :join   [:tree [:= :c.parent_id :tree.id]]}]}]]
               :select         [:id :depth]
               :from           [:tree]
               :order-by       [:depth :id]}))
;; README:end queue

(defn queue
  "`claim-jobs` for one worker and `category-tree` from the root 1."
  []
  {:claim (claim-jobs "worker-7" 5)
   :tree  (category-tree 1)})

;; ---------------------------------------------------------------------------
;; Scenario: an upsert
;; ---------------------------------------------------------------------------

;; README:begin upsert
(defn upsert-products
  "Inserts `products` (maps sharing one key set, one row each) or, where an
   id exists, updates the row from the proposed one (`excluded`) when the
   price changed, and returns the rows as stored. The arbiter is the unique
   index inferred from the id column."
  [products]
  (sql/format {:insert-into :products
               :values      products
               :on-conflict {:on-columns    [:id]
                             :do-update-set {:name        :excluded/name
                                             :price_cents :excluded/price_cents}
                             :where         [:<> :products.price_cents :excluded/price_cents]}
               :returning   [:id :name :price_cents]}))

(defn upsert-by-constraint
  "The same upsert naming the constraint as the arbiter instead of
   inferring the index, built with the helpers."
  [products]
  (-> (h/insert-into :products)
      (h/values products)
      (h/on-constraint :products_pkey)
      (h/do-update-set {:name :excluded/name :price_cents :excluded/price_cents})
      (h/returning :id)
      sql/format))
;; README:end upsert

(def ^:private two-products
  [{:id 1 :name "pencil" :price_cents 150}
   {:id 2 :name "notebook" :price_cents 450}])

(defn upsert
  "Both upserts for two products."
  []
  {:inferred   (upsert-products two-products)
   :constraint (upsert-by-constraint two-products)})

;; ---------------------------------------------------------------------------
;; Scenario: a migration
;; ---------------------------------------------------------------------------

;; README:begin migration
(def migration
  "A migration as data: the statements in order. DDL takes no parameters,
   so every literal is spelled inline in both modes and each :values is []."
  [{:create-table  :orders
    :if-not-exists true
    :columns       [[:id :bigserial :primary-key]
                    [:customer_id :bigint :not-null [:references :customers :id] [:on-delete :cascade]]
                    [:status :text :not-null [:default "new"]]
                    [:total_cents :bigint :not-null [:default 0] [:check [:>= :total_cents 0]]]
                    [:placed_at :timestamptz :not-null [:default [:now]]]]
    :constraints   [[:unique :customer_id :placed_at]]}
   {:create-index :orders_open_idx
    :on           :orders
    :columns      [:customer_id [:placed_at :desc]]
    :where        [:in :status ["new" "paid"]]}
   {:alter-table :orders
    :actions     [[:add-column [:note :text]]
                  [:drop-column :legacy_ref :if-exists]]}
   {:drop-table :orders_archive :if-exists true}])

(defn migrate
  "The SQL of `migration`, one text per statement."
  []
  (mapv (comp :text sql/format) migration))
;; README:end migration

;; ---------------------------------------------------------------------------
;; Error handling
;; ---------------------------------------------------------------------------

;; README:begin errors
(defn outcome
  "The value of `(f)`, or its failure as data: the :op and :arg of a
   format-error, and the :entity a rejected name belongs to when there is
   one. Any other exception is rethrown."
  [f]
  (try {:ok? true :value (f)}
       (catch :default e
         (let [data (ex-data e)]
           (if (= :sql-cljs/format-error (:type data))
             {:ok? false :why :format :error (select-keys data [:op :arg :entity])}
             (throw e))))))
;; README:end errors

(defn errors
  "`outcome` of a mixed-case column name, of the same statement through the
   raw hatch, of a string where a table name belongs, of an UPDATE without
   SET and of an empty IN list."
  []
  {:mixed-case (outcome #(sql/format {:select [:UserId] :from [:users]}))
   :raw-hatch  (outcome #(sql/format {:select [(sql/raw "\"UserId\"")] :from [:users]}))
   :a-string   (outcome #(sql/format {:select [:id] :from ["users"]}))
   :no-set     (outcome #(sql/format {:update :users :where [:= :id 1]}))
   :empty-in   (outcome #(sql/format {:select [:id] :from [:users] :where [:in :id []]}))})

;; ---------------------------------------------------------------------------
;; Runner
;; ---------------------------------------------------------------------------

(defn- show [title value]
  (println (str ";; " title))
  (pprint/pprint value))

(defn main [& _]
  (show "quick start" (quick-start))
  (show "dialects" (dialects))
  (show "a query built in pieces" (pieces))
  (show "a report" (report))
  (show "a work queue" (queue))
  (show "an upsert" (upsert))
  (show "a migration" (migrate))
  (show "failures are data" (errors))
  (println ";; pure helpers")
  (println "sql/api-version ;; =>" (pr-str sql/api-version)))
