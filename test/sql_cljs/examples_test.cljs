(ns ^:dev/always sql-cljs.examples-test
  "The examples of the README, run. Offline by nature: the library is pure
   formatting, so every example is a data map in and SQL text out.

   The README is held to the code:
   - what stands between a README:begin and a README:end mark of
     dev/sql_cljs/examples.cljs stands in README.md as written;
   - every one-liner of `shown` stands in README.md with the value it
     evaluates to;
   - every result README.md prints is the result the example returns.
   Spacing and commas are not compared. README.md and the examples source
   are read at compile time (sql-cljs.doctest/file-text); ^:dev/always
   keeps this namespace out of the compiler cache so a changed README is
   always re-read."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sql-cljs.core :as sql]
            [sql-cljs.examples :as ex]
            [sql-cljs.helpers :as h])
  (:require-macros [sql-cljs.doctest :refer [claims file-text]]))

;; ---------------------------------------------------------------------------
;; The one-liners
;; ---------------------------------------------------------------------------

(def shown
  "The one-liners of the README, each with its value."
  (claims
   (sql/format {:select [:a :b] :from [:t] :where [:= :t.a "x"]})
   (sql/format {:select [:a] :from [:t] :where [:in :a [1 2 3]]} {:inline true})
   (:text (sql/format {:select [:a] :from [:t] :where [:or [:= :a 1] [:and [:> :b 2] [:not [:is-null :c]]]]}))
   (sql/format {:select [[[:coalesce :nickname :name] :shown] [[:count :*] :n]] :from [:users] :group-by [:nickname :name]})
   (sql/format {:select [:a] :from [:t] :where [:>= :created_at (sql/raw "CURRENT_DATE - 7")]})
   (sql/format {:union [{:select [:id] :from [:customers]} {:select [:id] :from [:suppliers]}] :order-by [:id]})
   (sql/format {:insert-into :t :columns [:a :b] :values [[1 "x"] [2 :default]]})
   (sql/format {:delete-from :orders :using [:customers] :where [:and [:= :orders.customer_id :customers.id] [:= :customers.closed true]] :returning [:orders.id]})
   (sql/format {:truncate [:orders :order_lines] :restart-identity true :cascade true})
   (sql/format {:create-view :open_orders :or-replace true :as {:select [:*] :from [:orders] :where [:= :status "new"]}})
   (sql/format {:drop-index [:orders_open_idx] :concurrently true :if-exists true})
   (-> (h/select :a) (h/from :t) (h/where [:= :a 1]) (h/where [:= :b 2]))
   (merge (h/select :a) (h/from :t) (h/limit 1))
   (= (sql/format (-> (h/select :a) (h/from :t))) (sql/format {:select [:a] :from [:t]}))
   (sql/format {:insert-into :t :columns [:id :n] :values [[1 "x"]]} {:dialect :sqlite})
   sql/api-version))

;; ---------------------------------------------------------------------------
;; Reading the README
;; ---------------------------------------------------------------------------

(def ^:private readme (file-text "README.md"))

(def ^:private examples-source (file-text "dev/sql_cljs/examples.cljs"))

(defn- plain
  "`text` without what is not compared: commas, and any run of whitespace
   as one space."
  [text]
  (-> text
      (str/replace "," "")
      (str/replace #"\s+" " ")
      str/trim))

(defn- region
  "The text between the marks README:begin `label` and README:end `label`
   of the examples source, nil when either mark is missing."
  [label]
  (let [[_ found] (re-find (re-pattern (str "(?s);; README:begin " label "\\n(.*?)\\n\\s*;; README:end " label))
                           examples-source)]
    found))

(defn- shows?
  "Does README.md show `text`?"
  [text]
  (str/includes? (plain readme) (plain text)))

(defn- shown-in-readme
  "Asserts README.md prints `value`."
  [value]
  (is (shows? (pr-str value)) (str "README.md does not show:\n" (pr-str value))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest the-quick-start-formats
  (let [{:keys [query inline]} (ex/quick-start)]
    (testing "the value travels as $1; inline spells it out and returns no values"
      (is (= {:text "SELECT id, name, price_cents FROM products WHERE price_cents < $1 ORDER BY id"
              :values [500]}
             query))
      (is (= {:text "SELECT id, name, price_cents FROM products WHERE price_cents < 500 ORDER BY id"
              :values []}
             inline)))
    (shown-in-readme query)
    (shown-in-readme inline)))

(deftest the-dialects-render-one-map
  (let [{:keys [pg sqlite] :as r} (ex/dialects)]
    (testing "the same statement map, $N and ILIKE under :pg, ? and LIKE under :sqlite, one values vector"
      (is (= {:text "SELECT id, name FROM products WHERE name ILIKE $1 ORDER BY id LIMIT $2"
              :values ["%pen%" 20]}
             pg))
      (is (= {:text "SELECT id, name FROM products WHERE name LIKE ? ORDER BY id LIMIT ?"
              :values ["%pen%" 20]}
             sqlite))
      (is (= (:values pg) (:values sqlite))))
    (testing ":pg is the default"
      (is (= pg (sql/format {:select [:id :name] :from [:products] :where [:ilike :name "%pen%"] :order-by [:id] :limit 20}))))
    (shown-in-readme r)))

(deftest a-query-is-built-in-pieces
  (let [{:keys [statement query]} (ex/pieces)]
    (is (= {:select [:id :name :price_cents]
            :from [:products]
            :order-by [[:price_cents :desc] :id]
            :limit 20
            :where [:and [:ilike :name "%pen%"] [:<= :price_cents 500]]}
           statement))
    (is (= {:text "SELECT id, name, price_cents FROM products WHERE (name ILIKE $1) AND (price_cents <= $2) ORDER BY price_cents DESC, id LIMIT $3"
            :values ["%pen%" 500 20]}
           query))
    (testing "the helpers build the map a literal would be"
      (is (= (sql/format statement) query)))
    (shown-in-readme statement)
    (shown-in-readme query)))

(deftest a-report-joins-and-aggregates
  (let [r (ex/report)]
    (is (= {:text "SELECT c.id, c.name, COUNT(*) AS orders, SUM(o.total_cents) AS spent FROM customers AS c INNER JOIN orders AS o ON o.customer_id = c.id LEFT JOIN regions USING (region_id) WHERE (o.placed_at >= $1) AND (o.status = $2) GROUP BY c.id, c.name HAVING COUNT(*) > $3 ORDER BY spent DESC NULLS LAST, c.id LIMIT $4 OFFSET $5"
            :values ["2026-Jan-01" "paid" 1 10 10]}
           r))
    (shown-in-readme r)))

(deftest a-work-queue-locks-and-walks
  (let [{:keys [claim tree]} (ex/queue)]
    (is (= {:text "WITH next AS (SELECT id FROM jobs WHERE state = $1 ORDER BY id LIMIT $2 FOR UPDATE SKIP LOCKED) UPDATE jobs SET state = $3, worker = $4 FROM next WHERE jobs.id = next.id RETURNING jobs.id, jobs.payload"
            :values ["queued" 5 "running" "worker-7"]}
           claim))
    (is (= {:text "WITH RECURSIVE tree(id, parent_id, depth) AS ((SELECT id, parent_id, $1 AS depth FROM categories WHERE id = $2) UNION ALL (SELECT c.id, c.parent_id, tree.depth + $3 AS depth FROM categories AS c INNER JOIN tree ON c.parent_id = tree.id)) SELECT id, depth FROM tree ORDER BY depth, id"
            :values [0 1 1]}
           tree))
    (shown-in-readme claim)
    (shown-in-readme tree)))

(deftest an-upsert-names-either-arbiter
  (let [{:keys [inferred constraint]} (ex/upsert)]
    (is (= {:text "INSERT INTO products (id, name, price_cents) VALUES ($1, $2, $3), ($4, $5, $6) ON CONFLICT (id) DO UPDATE SET name = excluded.name, price_cents = excluded.price_cents WHERE products.price_cents <> excluded.price_cents RETURNING id, name, price_cents"
            :values [1 "pencil" 150 2 "notebook" 450]}
           inferred))
    (is (= {:text "INSERT INTO products (id, name, price_cents) VALUES ($1, $2, $3), ($4, $5, $6) ON CONFLICT ON CONSTRAINT products_pkey DO UPDATE SET name = excluded.name, price_cents = excluded.price_cents RETURNING id"
            :values [1 "pencil" 150 2 "notebook" 450]}
           constraint))
    (shown-in-readme inferred)
    (shown-in-readme constraint)))

(deftest a-migration-is-inline-ddl
  (let [r (ex/migrate)]
    (is (= ["CREATE TABLE IF NOT EXISTS orders (id BIGSERIAL PRIMARY KEY, customer_id BIGINT NOT NULL REFERENCES customers (id) ON DELETE CASCADE, status TEXT NOT NULL DEFAULT 'new', total_cents BIGINT NOT NULL DEFAULT 0 CHECK (total_cents >= 0), placed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE (customer_id, placed_at))"
            "CREATE INDEX orders_open_idx ON orders (customer_id, placed_at DESC) WHERE status IN ('new', 'paid')"
            "ALTER TABLE orders ADD COLUMN note TEXT, DROP COLUMN IF EXISTS legacy_ref"
            "DROP TABLE IF EXISTS orders_archive"]
           r))
    (testing "a DDL statement has no values in either mode"
      (is (every? #(= [] (:values (sql/format %))) ex/migration))
      (is (every? #(= [] (:values (sql/format % {:inline true}))) ex/migration)))
    (shown-in-readme r)))

(deftest failures-are-data
  (let [r (ex/errors)]
    (is (= {:mixed-case {:ok? false :why :format :error {:op :select :arg "UserId" :entity :UserId}}
            :raw-hatch {:ok? true :value {:text "SELECT \"UserId\" FROM users" :values []}}
            :a-string {:ok? false :why :format :error {:op :from :arg "users"}}
            :no-set {:ok? false :why :format :error {:op :update :arg {:update :users :where [:= :id 1]}}}
            :empty-in {:ok? false :why :format :error {:op :in :arg [:in :id []]}}}
           r))
    (shown-in-readme r)))

(deftest an-unknown-failure-is-rethrown
  (is (= :other/error
         (:type (ex-data (try (ex/outcome #(throw (ex-info "other" {:type :other/error})))
                              (catch :default e e)))))))

(deftest the-one-liners-hold
  (let [value (into {} shown)]
    (is (= (count shown) (count value)) "no one-liner is shown twice")
    (is (= {:text "SELECT a, b FROM t WHERE t.a = $1" :values ["x"]}
           (value "(sql/format {:select [:a :b], :from [:t], :where [:= :t.a \"x\"]})")))
    (is (= {:text "SELECT a FROM t WHERE a IN (1, 2, 3)" :values []}
           (value "(sql/format {:select [:a], :from [:t], :where [:in :a [1 2 3]]} {:inline true})")))
    (is (= "SELECT a FROM t WHERE (a = $1) OR ((b > $2) AND (NOT (c IS NULL)))"
           (value "(:text (sql/format {:select [:a], :from [:t], :where [:or [:= :a 1] [:and [:> :b 2] [:not [:is-null :c]]]]}))")))
    (is (= {:text "SELECT COALESCE(nickname, name) AS shown, COUNT(*) AS n FROM users GROUP BY nickname, name" :values []}
           (value "(sql/format {:select [[[:coalesce :nickname :name] :shown] [[:count :*] :n]], :from [:users], :group-by [:nickname :name]})")))
    (is (= {:text "SELECT a FROM t WHERE created_at >= CURRENT_DATE - 7" :values []}
           (value "(sql/format {:select [:a], :from [:t], :where [:>= :created_at (sql/raw \"CURRENT_DATE - 7\")]})")))
    (is (= {:text "(SELECT id FROM customers) UNION (SELECT id FROM suppliers) ORDER BY id" :values []}
           (value "(sql/format {:union [{:select [:id], :from [:customers]} {:select [:id], :from [:suppliers]}], :order-by [:id]})")))
    (is (= {:text "INSERT INTO t (a, b) VALUES ($1, $2), ($3, DEFAULT)" :values [1 "x" 2]}
           (value "(sql/format {:insert-into :t, :columns [:a :b], :values [[1 \"x\"] [2 :default]]})")))
    (is (= {:text "DELETE FROM orders USING customers WHERE (orders.customer_id = customers.id) AND (customers.closed = $1) RETURNING orders.id" :values [true]}
           (value "(sql/format {:delete-from :orders, :using [:customers], :where [:and [:= :orders.customer_id :customers.id] [:= :customers.closed true]], :returning [:orders.id]})")))
    (is (= {:text "TRUNCATE TABLE orders, order_lines RESTART IDENTITY CASCADE" :values []}
           (value "(sql/format {:truncate [:orders :order_lines], :restart-identity true, :cascade true})")))
    (is (= {:text "CREATE OR REPLACE VIEW open_orders AS SELECT * FROM orders WHERE status = 'new'" :values []}
           (value "(sql/format {:create-view :open_orders, :or-replace true, :as {:select [:*], :from [:orders], :where [:= :status \"new\"]}})")))
    (is (= {:text "DROP INDEX CONCURRENTLY IF EXISTS orders_open_idx" :values []}
           (value "(sql/format {:drop-index [:orders_open_idx], :concurrently true, :if-exists true})")))
    (is (= {:select [:a] :from [:t] :where [:and [:= :a 1] [:= :b 2]]}
           (value "(-> (h/select :a) (h/from :t) (h/where [:= :a 1]) (h/where [:= :b 2]))")))
    (is (= {:select [:a] :from [:t] :limit 1}
           (value "(merge (h/select :a) (h/from :t) (h/limit 1))")))
    (is (true? (value "(= (sql/format (-> (h/select :a) (h/from :t))) (sql/format {:select [:a], :from [:t]}))")))
    (is (= {:text "INSERT INTO t (id, n) VALUES (?, ?)" :values [1 "x"]}
           (value "(sql/format {:insert-into :t, :columns [:id :n], :values [[1 \"x\"]]} {:dialect :sqlite})")))
    (is (= "0.2.0" (value "sql/api-version")))))

(deftest the-readme-shows-what-runs
  (testing "the marked code of the examples"
    (doseq [label ["quick-start" "going-live" "dialect" "pieces" "report" "queue" "upsert" "migration" "errors"]
            :let [code (region label)]]
      (is (some? code) (str "no region " label))
      (is (shows? code) (str "README.md does not show " label ":\n" code))))
  (testing "every one-liner, with its value"
    (doseq [[source value] shown
            :let [line (str source "\n;; => " (pr-str value))]]
      (is (shows? line) (str "README.md does not show:\n" line))))
  (testing "how the examples are run, and where the manual is"
    (is (shows? "npx shadow-cljs compile examples && node target/examples.js"))
    (is (shows? "docs/sql-cljs.org"))
    (is (shows? "docs/sql-api-inventory.org"))))
