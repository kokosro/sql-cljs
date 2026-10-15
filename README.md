# sql-cljs

A pure ClojureScript (Node.js) SQL query builder for PostgreSQL: a statement is a
map of clauses, an expression is a vector, and the result is the parameterized query
a PostgreSQL client sends. Plain ClojureScript data goes in and a `{:text :values}`
map comes out, the exact shape [pg-cljs](https://github.com/kokosro/pg-cljs)'s
`query!` takes; keywords and symbols name tables and columns, strings are always
values, and values are passed through untouched for the client to encode. The same
map renders for SQLite with `{:dialect :sqlite}`, as the `?`-parameterized statement
[sqlite-cljs](https://github.com/kokosro/sqlite-cljs) takes. The library is pure
formatting: it has no runtime npm dependency at all, nothing in it is asynchronous,
and nothing touches the network.

## Requirements

Node.js 22+, Java 11+ for shadow-cljs. The library is consumed as a source dependency:

```clojure
;; deps.edn of the consuming app, next to a checkout:
{:deps {io.github.kokosro/sql-cljs {:local/root "../sql-cljs"}}}
```

There is no npm side: `deps.edn` declares ClojureScript only, and the consuming
app's `package.json` needs nothing for sql-cljs.

## Quick start (offline)

Every use of sql-cljs is offline: `format` turns a data map into SQL text and a
vector of values without a server.

```bash
npm install
npx shadow-cljs compile examples && node target/examples.js
```

That runs [`dev/sql_cljs/examples.cljs`](dev/sql_cljs/examples.cljs), which holds
every example of this page and prints the results shown below. Its namespace form:

```clojure
(ns sql-cljs.examples
  (:require [cljs.pprint :as pprint]
            [sql-cljs.core :as sql]
            [sql-cljs.helpers :as h]))
```

A statement is a map; `format` renders it:

```clojure
(defn products-under
  "The products cheaper than `max-cents`, as the parameterized query a
   PostgreSQL client sends: the price travels as $1, untouched."
  [max-cents]
  (sql/format {:select   [:id :name :price_cents]
               :from     [:products]
               :where    [:< :price_cents max-cents]
               :order-by [:id]}))
```

`(products-under 500)` gives:

```clojure
{:text   "SELECT id, name, price_cents FROM products WHERE price_cents < $1 ORDER BY id"
 :values [500]}
```

Keywords (`:id`, `:products`) are names and are rendered bare; a qualified name,
`:products/id` or `:products.id`, renders as `products.id`. Every value (`500` here)
becomes the next `$N` in order of appearance and is placed in `:values` exactly as
written, so the client encodes it for the wire. With `{:inline true}` the same
statement spells its values out, for a console or a log, and `:values` is empty:

```clojure
{:text   "SELECT id, name, price_cents FROM products WHERE price_cents < 500 ORDER BY id"
 :values []}
```

### Going live

The map `format` returns is the command pg-cljs's `query!` takes; the two libraries
compose without an adapter. In an application that requires `promesa.core` as `p`,
`pg-cljs.core` as `pg` and `sql-cljs.core` as `sql`:

```clojure
(p/let [client (pg/connect! {:host "db.example.test" :user "shop" :database "shop"})
        result (pg/query! client (sql/format {:select [:id :name :price_cents]
                                              :from   [:products]
                                              :where  [:< :price_cents 500]}))
        _      (pg/end! client)]
  (:rows result))
```

**Running it executes SQL against your own database: whether and when to do that is
your decision.** Two rules keep the composition safe. Send the parameterized form
(`format` without options): the values travel outside the text and the server never
parses them as SQL. Inline mode and `raw` build SQL text from your data with no
protection on the server side, so inlining or splicing untrusted input is SQL
injection: keep `{:inline true}` for consoles and debugging, and build `raw`
fragments from your own constants only.

## Dialects

`format` takes `{:dialect :pg}`, the default and the same as no option, or
`{:dialect :sqlite}`. The statement data is the same for both: the map does not
change, only the rendered text does.

```clojure
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
```

`(products-named-like "%pen%")` gives:

```clojure
{:pg     {:text   "SELECT id, name FROM products WHERE name ILIKE $1 ORDER BY id LIMIT $2"
          :values ["%pen%" 20]}
 :sqlite {:text   "SELECT id, name FROM products WHERE name LIKE ? ORDER BY id LIMIT ?"
          :values ["%pen%" 20]}}
```

The `:sqlite` result is what [sqlite-cljs](https://github.com/kokosro/sqlite-cljs)'s
`sqlite-cljs.query` executes. With that namespace required as `q` and a statement
`s` from `format`, either designator fits, with no adapter:

```clojure
(q/execute! db {:sql (:text s) :params (:values s)})
(q/execute! db (into [(:text s)] (:values s)))
```

A DDL statement has `:values []`, so its `:text` can also go through `q/exec!`, the
parameterless path that clears sqlite-cljs's statement cache after a schema change.
sqlite-cljs binds each value by its own rules (a boolean travels as `1` / `0`, an
integral number and a BigInt as an `INTEGER`, `nil` as `NULL`); the binding table is
in its README. One statement binds at most 32766 parameters, sqlite-cljs's ceiling:
rows × columns of a multi-row `INSERT` (plus any other value) must stay within it,
and sql-cljs does not chunk, so splitting the rows is the caller's job.

What `:sqlite` changes, and what it refuses with a `:sql-cljs/format-error` because
SQLite has no spelling for it (the dialect never degrades silently):

| construct                                                                                   | under `:sqlite`                                                                                                                                       |
|---------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| placeholders                                                                                | `?` instead of `$N`, one per value in order of appearance; `:values` is the same vector                                                                |
| `:limit :all`                                                                               | `LIMIT -1`                                                                                                                                            |
| `:offset` without `:limit`                                                                  | `LIMIT -1 OFFSET ?`                                                                                                                                   |
| `:union` / `:union-all` operands                                                            | rendered bare, without parentheses; an operand with its own `:with`, `:with-recursive`, `:order-by`, `:limit`, `:offset` or `:for` is a format-error    |
| `:ilike` / `:not-ilike`                                                                     | `LIKE` / `NOT LIKE`; SQLite's `LIKE` is case-insensitive for ASCII by default, so `:like` is too                                                       |
| `:serial`, `:bigserial`, `:smallserial` with `:primary-key`                                 | `INTEGER ... PRIMARY KEY AUTOINCREMENT`; such a column without `:primary-key` is a format-error naming the idiom                                       |
| `[:current-timestamp]`, `[:current-date]`, `[:current-time]`                                | the bare key words `CURRENT_TIMESTAMP`, `CURRENT_DATE`, `CURRENT_TIME`, in a `DEFAULT` too; under `:pg` these hyphenated names fail the identifier rule |
| a function-call `DEFAULT`                                                                   | parenthesized, `DEFAULT (LOWER(name))`                                                                                                                |
| `ON CONFLICT DO UPDATE SET` without a target                                                | rendered, as under `:pg`; SQLite accepts it, PostgreSQL rejects it at execution                                                                       |
| `:for` (row locking)                                                                        | format-error                                                                                                                                          |
| `:distinct-on`                                                                              | format-error                                                                                                                                          |
| `:on-constraint`                                                                            | format-error                                                                                                                                          |
| `:truncate`                                                                                 | format-error                                                                                                                                          |
| `:delete-from` with `:using`                                                                | format-error                                                                                                                                          |
| `:default` as a value in `VALUES`, `SET` or `DO UPDATE SET`                                 | format-error                                                                                                                                          |
| `INSERT ... SELECT` with `:on-conflict` and no `:where` in the select                       | format-error                                                                                                                                          |
| an `:insert-into`, `:update` or `:delete-from` body in `:with` / `:with-recursive`          | format-error                                                                                                                                          |
| `:alter-table` with several actions, with `:if-exists`, with `:if-not-exists` on `:add-column`, or with `:if-exists` / `:cascade` / `:restrict` on `:drop-column` | format-error                                                                                                |
| `:create-index` with `:using`, with `:concurrently`, without a name, or with `:nulls-first` / `:nulls-last` on a column | format-error                                                                                                                  |
| `:drop-table`, `:drop-index`, `:drop-view` with several names or with `:cascade` / `:restrict`; `:drop-index` with `:concurrently` | format-error                                                                                                       |
| `:create-view` with `:or-replace`                                                           | format-error                                                                                                                                          |

Everything else renders identically: the identifier rule and its errors, the
empty-`IN` error, every nested operator operand parenthesized, `UPDATE ... FROM`,
`RETURNING`, `ON CONFLICT` with column or expression targets, `WITH` over select
bodies, the rest of the DDL. DDL stays inline with `:values []` in both dialects,
and `{:inline true}` renders the same text in both.

## Scenarios

Each scenario is a function of `dev/sql_cljs/examples.cljs`, shown with the map it
returns. Nothing in them needs a server.

### A query built in pieces

The builders of `sql-cljs.helpers` return the same plain maps and thread on an
optional leading statement, so a query grows clause by clause:

```clojure
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
```

`(search-products {:name-like "%pen%" :max-cents 500})` is the map

```clojure
{:select   [:id :name :price_cents]
 :from     [:products]
 :order-by [[:price_cents :desc] :id]
 :limit    20
 :where    [:and [:ilike :name "%pen%"] [:<= :price_cents 500]]}
```

and `format` of it gives:

```clojure
{:text   "SELECT id, name, price_cents FROM products WHERE (name ILIKE $1) AND (price_cents <= $2) ORDER BY price_cents DESC, id LIMIT $3"
 :values ["%pen%" 500 20]}
```

Two `where` calls combined into one `[:and ...]`; the key order of the map never
matters, the text follows the PostgreSQL synopsis order. Every nested operator
operand is parenthesized, so precedence is never relied on. `LIMIT` travels as a
parameter like any value.

### A report

```clojure
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
```

`(top-customers "2026-Jan-01" 10 1)` gives:

```clojure
{:text   "SELECT c.id, c.name, COUNT(*) AS orders, SUM(o.total_cents) AS spent FROM customers AS c INNER JOIN orders AS o ON o.customer_id = c.id LEFT JOIN regions USING (region_id) WHERE (o.placed_at >= $1) AND (o.status = $2) GROUP BY c.id, c.name HAVING COUNT(*) > $3 ORDER BY spent DESC NULLS LAST, c.id LIMIT $4 OFFSET $5"
 :values ["2026-Jan-01" "paid" 1 10 10]}
```

A two-element vector at the top of `:select` is `[expression alias]`, so a function
call nests one level deeper: `[[:count :*] :orders]` is `COUNT(*) AS orders`, and
`[[:count :*]]` would be the same call without an alias. A join clause is a flat
vector of table-then-condition pairs; the condition is an expression (`ON`) or
`[:using :col ...]`. An alias is always rendered with `AS`.

### A work queue

```clojure
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
```

`(claim-jobs "worker-7" 5)` and `(category-tree 1)` give:

```clojure
{:text   "WITH next AS (SELECT id FROM jobs WHERE state = $1 ORDER BY id LIMIT $2 FOR UPDATE SKIP LOCKED) UPDATE jobs SET state = $3, worker = $4 FROM next WHERE jobs.id = next.id RETURNING jobs.id, jobs.payload"
 :values ["queued" 5 "running" "worker-7"]}

{:text   "WITH RECURSIVE tree(id, parent_id, depth) AS ((SELECT id, parent_id, $1 AS depth FROM categories WHERE id = $2) UNION ALL (SELECT c.id, c.parent_id, tree.depth + $3 AS depth FROM categories AS c INNER JOIN tree ON c.parent_id = tree.id)) SELECT id, depth FROM tree ORDER BY depth, id"
 :values [0 1 1]}
```

The `$N` numbering is textual: the CTE's values come first, then the main
statement's, across any nesting. A `:with` on an `UPDATE`, `INSERT` or `DELETE`
works as on a `SELECT`. The operands of a set operation are parenthesized, and the
literal `0` in a select item is a value like any other, so it travels as `$1`.

### An upsert

```clojure
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
```

For two products, `[{:id 1 :name "pencil" :price_cents 150} {:id 2 :name "notebook"
:price_cents 450}]`, the two functions give:

```clojure
{:text   "INSERT INTO products (id, name, price_cents) VALUES ($1, $2, $3), ($4, $5, $6) ON CONFLICT (id) DO UPDATE SET name = excluded.name, price_cents = excluded.price_cents WHERE products.price_cents <> excluded.price_cents RETURNING id, name, price_cents"
 :values [1 "pencil" 150 2 "notebook" 450]}

{:text   "INSERT INTO products (id, name, price_cents) VALUES ($1, $2, $3), ($4, $5, $6) ON CONFLICT ON CONSTRAINT products_pkey DO UPDATE SET name = excluded.name, price_cents = excluded.price_cents RETURNING id"
 :values [1 "pencil" 150 2 "notebook" 450]}
```

Rows given as maps take their column list from the first map and must share one key
set; rows given as vectors follow `:columns`. `:excluded/name` names the proposed
row, as the SQL does. Both arbiter forms are offered and the caller picks: the
PostgreSQL documentation prefers unique index inference (`:on-columns`, with an
optional `:index-where` predicate for a partial index) over naming a constraint
(`:on-constraint`), and only `NOT DEFERRABLE` constraints and unique indexes serve
as arbiters, which the server enforces, not this library.

### A migration

```clojure
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
```

`(migrate)` gives:

```clojure
["CREATE TABLE IF NOT EXISTS orders (id BIGSERIAL PRIMARY KEY, customer_id BIGINT NOT NULL REFERENCES customers (id) ON DELETE CASCADE, status TEXT NOT NULL DEFAULT 'new', total_cents BIGINT NOT NULL DEFAULT 0 CHECK (total_cents >= 0), placed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE (customer_id, placed_at))"
 "CREATE INDEX orders_open_idx ON orders (customer_id, placed_at DESC) WHERE status IN ('new', 'paid')"
 "ALTER TABLE orders ADD COLUMN note TEXT, DROP COLUMN IF EXISTS legacy_ref"
 "DROP TABLE IF EXISTS orders_archive"]
```

A column definition is `[name type & constraints]`: the type is a keyword rendered
upper-case (`:bigserial`, `:double-precision` for a two-word type), a vector for
arguments (`[:varchar 40]`, `[:numeric 10 2]`) or `raw` for anything else; a
constraint is a keyword (`:not-null`, `:primary-key`) or a vector (`[:default
expr]`, `[:check expr]`, `[:references :t :col]`, `[:on-delete :cascade]`,
`[:constraint :name ...]`). A `DEFAULT` that is a function call renders as the call
(`NOW()`); a string renders as a literal. Each statement of a migration runs
separately: `CREATE INDEX CONCURRENTLY` cannot run inside a transaction block.

## Pure helpers

Every function of this library is a pure helper. No server, no network:

```clojure
(sql/format {:select [:a :b] :from [:t] :where [:= :t.a "x"]})
;; => {:text "SELECT a, b FROM t WHERE t.a = $1" :values ["x"]}

(sql/format {:select [:a] :from [:t] :where [:in :a [1 2 3]]} {:inline true})
;; => {:text "SELECT a FROM t WHERE a IN (1, 2, 3)" :values []}

(:text (sql/format {:select [:a] :from [:t] :where [:or [:= :a 1] [:and [:> :b 2] [:not [:is-null :c]]]]}))
;; => "SELECT a FROM t WHERE (a = $1) OR ((b > $2) AND (NOT (c IS NULL)))"

(sql/format {:select [[[:coalesce :nickname :name] :shown] [[:count :*] :n]] :from [:users] :group-by [:nickname :name]})
;; => {:text "SELECT COALESCE(nickname, name) AS shown, COUNT(*) AS n FROM users GROUP BY nickname, name" :values []}

(sql/format {:select [:a] :from [:t] :where [:>= :created_at (sql/raw "CURRENT_DATE - 7")]})
;; => {:text "SELECT a FROM t WHERE created_at >= CURRENT_DATE - 7" :values []}

(sql/format {:union [{:select [:id] :from [:customers]} {:select [:id] :from [:suppliers]}] :order-by [:id]})
;; => {:text "(SELECT id FROM customers) UNION (SELECT id FROM suppliers) ORDER BY id" :values []}

(sql/format {:insert-into :t :columns [:a :b] :values [[1 "x"] [2 :default]]})
;; => {:text "INSERT INTO t (a, b) VALUES ($1, $2), ($3, DEFAULT)" :values [1 "x" 2]}

(sql/format {:delete-from :orders :using [:customers] :where [:and [:= :orders.customer_id :customers.id] [:= :customers.closed true]] :returning [:orders.id]})
;; => {:text "DELETE FROM orders USING customers WHERE (orders.customer_id = customers.id) AND (customers.closed = $1) RETURNING orders.id" :values [true]}

(sql/format {:truncate [:orders :order_lines] :restart-identity true :cascade true})
;; => {:text "TRUNCATE TABLE orders, order_lines RESTART IDENTITY CASCADE" :values []}

(sql/format {:create-view :open_orders :or-replace true :as {:select [:*] :from [:orders] :where [:= :status "new"]}})
;; => {:text "CREATE OR REPLACE VIEW open_orders AS SELECT * FROM orders WHERE status = 'new'" :values []}

(sql/format {:drop-index [:orders_open_idx] :concurrently true :if-exists true})
;; => {:text "DROP INDEX CONCURRENTLY IF EXISTS orders_open_idx" :values []}

(-> (h/select :a) (h/from :t) (h/where [:= :a 1]) (h/where [:= :b 2]))
;; => {:select [:a] :from [:t] :where [:and [:= :a 1] [:= :b 2]]}

(merge (h/select :a) (h/from :t) (h/limit 1))
;; => {:select [:a] :from [:t] :limit 1}

(= (sql/format (-> (h/select :a) (h/from :t))) (sql/format {:select [:a] :from [:t]}))
;; => true

(sql/format {:insert-into :t :columns [:id :n] :values [[1 "x"]]} {:dialect :sqlite})
;; => {:text "INSERT INTO t (id, n) VALUES (?, ?)" :values [1 "x"]}

sql/api-version
;; => "0.2.0"
```

Any lettered keyword at the head of a vector is a function call (`[:coalesce
:nickname :name]`, `[:now]`), an operator keyword is one of the operators the
library knows, and `raw` (or `[:raw "..."]` in expression position) is copied
verbatim. The builders of `sql-cljs.helpers` are one per clause, each taking the
statement being built as an optional first argument; a repeated clause merges the
HoneySQL way: vector clauses concatenate, `where` / `having` combine with `[:and
...]`, map clauses (`set`, the `on-conflict` family) merge, scalar clauses replace.
`union` / `union-all` take operands only and never thread. The helpers validate
nothing; malformed data fails in `format`. There is no build function: the helpers
return the finished map, and `merge` on such maps is the same thing. Refer to the
namespace with an alias, since `for`, `set`, `update`, `group-by` and `distinct`
shadow `cljs.core`.

## Error handling

One taxonomy, raised as an `ex-info` before any text is produced:

| `:type`                   | when                                                                                                                 | data                                                                                                             |
|---------------------------|----------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `:sql-cljs/format-error`  | the statement, a clause or an expression is malformed: a value where a name is required, a name outside the identifier rule, an unknown clause, operator or option, a clause that does not apply to the statement's head, a missing required clause, a wrong arity, rows of different shapes, an empty `IN` list, a value with no literal spelling in inline mode | `:message`; `:op`, the innermost clause or operator the offending piece belongs to (`:select`, `:from`, `:in`, `:update`, `:inline`, `:format`, ...); `:arg`, the offending piece itself; `:entity` (the whole name whose part failed) or `:allowed` (the closed set of accepted values) where one exists |

The message starts with `sql-cljs: ` and says what was expected. Nothing else is
thrown: a statement that formats is one the library vouches for syntactically, and
whatever the server then rejects (a reserved word used as a name, a missing table, a
type mismatch) is the server's error, reported by the client.

```clojure
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
```

For a mixed-case column name, the same statement through the raw hatch, a string
where a table name belongs, an `UPDATE` without `SET` and an empty `IN` list,
`outcome` gives:

```clojure
{:mixed-case {:ok? false :why :format :error {:op :select :arg "UserId" :entity :UserId}}
 :raw-hatch  {:ok? true :value {:text "SELECT \"UserId\" FROM users" :values []}}
 :a-string   {:ok? false :why :format :error {:op :from :arg "users"}}
 :no-set     {:ok? false :why :format :error {:op :update :arg {:update :users :where [:= :id 1]}}}
 :empty-in   {:ok? false :why :format :error {:op :in :arg [:in :id []]}}}
```

The identifier rule: every dot-separated part of a name must match
`[a-z_][a-z0-9_$]*`, the unquoted-identifier charset of PostgreSQL in lower case.
PostgreSQL folds an unquoted name to lower case, so a bare `UserId` would name
`userid`; rather than quote silently, the library refuses the name and points at the
hatch, `(sql/raw "\"UserId\"")`, which splices the quoted identifier verbatim. The
same hatch stands for a reserved word used as a name (`(sql/raw "\"user\"")`), which
the library does not detect and the server would reject. A string is always a value:
`:from ["users"]` is refused for that reason, with the clause as `:op`. An empty
`IN` list is refused rather than rendered, since `IN ()` is not SQL; write `FALSE`
or drop the predicate.

Inline mode renders `'...'` literals with quotes doubled and nothing else: a
backslash in a string is a literal backslash on a server with the default
`standard_conforming_strings = on`. The text is built from caller data with no
server-side protection, so **never inline or splice (`raw`) untrusted input**: send
the parameterized form.

## Features

* **Statements as maps, expressions as vectors** — `{:select [...] :from [...]
  :where [...]}` in, `{:text :values}` out; the map's key order never matters, the
  text follows the PostgreSQL synopsis order, one line, upper-case key words, single
  spaces, no trailing semicolon.
* **Values untouched** — every value becomes the next `$N` in order of appearance
  and is placed in `:values` exactly as written: nil, strings, numbers, booleans,
  BigInts and anything else pass through for the client to encode; a value used
  twice is sent twice.
* **Inline mode** — `{:inline true}` spells every value as a literal and returns
  `:values []`, for consoles and debugging.
* **Two dialects** — `{:dialect :pg}` (the default) renders PostgreSQL and
  `{:dialect :sqlite}` renders the same statement map for SQLite: `?` placeholders,
  SQLite's `LIMIT` / `OFFSET`, compound-select, `LIKE`, serial-column, `DEFAULT` and
  `CURRENT_*` spellings, and a format-error for every clause SQLite has no spelling
  for, so nothing degrades silently.
* **Queries** — `SELECT` with `DISTINCT` / `DISTINCT ON`, `FROM` with aliases and
  subqueries, `INNER` / `LEFT` / `RIGHT` / `FULL` / `CROSS JOIN` with `ON` and
  `USING`, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY` with directions and `NULLS
  FIRST` / `LAST`, `LIMIT` / `OFFSET`, `UNION` / `UNION ALL`, `WITH` / `WITH
  RECURSIVE`, `FOR UPDATE` / `SHARE` / `NO KEY UPDATE` / `KEY SHARE` with `OF`,
  `NOWAIT` / `SKIP LOCKED`.
* **Expressions** — comparison, logical, `IN` / `NOT IN` (lists and subqueries),
  `LIKE` / `ILIKE`, `BETWEEN`, `IS NULL`, `EXISTS`, arithmetic, any function call,
  scalar subqueries, `raw` fragments; every nested operator operand parenthesized.
* **Data modification** — `INSERT` with multi-row `VALUES` (rows as vectors or maps),
  `DEFAULT`, `INSERT ... SELECT`, `ON CONFLICT` with column or expression targets, an
  index predicate or `ON CONSTRAINT`, `DO NOTHING` or `DO UPDATE SET ... WHERE`,
  `RETURNING`; `UPDATE` with `SET` from a map or pairs, `FROM`, `WHERE`,
  `RETURNING`; `DELETE` with `USING`; `TRUNCATE` with the identity and cascade
  flags; `WITH` before each.
* **Data definition** — `CREATE TABLE` with column and table constraints, `DROP
  TABLE`, `ALTER TABLE` (add, drop, rename column), `CREATE INDEX` (unique,
  concurrently, method, order, partial), `DROP INDEX`, `CREATE VIEW`, `DROP VIEW`,
  each with its `IF [NOT] EXISTS` and `CASCADE` / `RESTRICT` where the grammar has
  them; DDL literals always inline, `:values` always `[]`.
* **Helpers that stay data** — one builder per clause in `sql-cljs.helpers`, threading
  on an optional statement, composing with `merge`; the result equals the literal
  map.
* **Fail loud, one taxonomy** — every malformed input is a `:sql-cljs/format-error`
  with `:op` and `:arg`, raised before any text is produced; names outside the
  identifier rule are refused with the `raw` hatch named in the message.
* **Pure ClojureScript** — no JavaScript interop, no runtime npm dependency, nothing
  asynchronous, no network, no file access.

## Limits and settings

The two options of `format`:

| option     | default | meaning                                                                                  |
|------------|---------|------------------------------------------------------------------------------------------|
| `:dialect` | `:pg`   | `:pg` renders PostgreSQL with `$N` placeholders; `:sqlite` renders SQLite with `?` placeholders and the translations and rejections of the Dialects table; any other value, `nil` included, is a format-error with `:allowed [:pg :sqlite]` |
| `:inline`  | `false` | `true` renders every value as a SQL literal in `:text` and returns `:values []`; DDL statements render this way in both modes and both dialects |

What the library does not do:

* **It never quotes an identifier.** Names are rendered bare, and a name outside
  `[a-z_][a-z0-9_$]*` per dot-separated part is a format-error. Mixed-case,
  reserved-word or otherwise exotic names go through `(sql/raw "\"Name\"")`, written
  by you; the library neither detects reserved words (the server rejects `user`
  or `order` as a bare name) nor folds case.
* **No type casts.** `::type` is not spelled by any shape; a cast goes through
  `raw`, or the server infers the type of the parameter.
* **Not covered:** `INTERSECT` / `EXCEPT`, `DEFAULT VALUES`, `OVERRIDING ... VALUE`,
  `ONLY`, the multi-column `SET (a, b) = (...)` forms, `WHERE CURRENT OF`, window
  functions, `CASE`; in DDL `TEMPORARY` / `UNLOGGED`, `GENERATED` and identity
  columns, `DEFERRABLE`, `EXCLUDE`, the other `ALTER TABLE` actions, `INCLUDE` /
  `COLLATE` / operator classes on indexes and the view options. A `raw` fragment
  stands wherever an expression, a table, a condition, a type, a constraint, a
  statement operand or a CTE body is expected.
* **Map order past eight keys.** A `:set` map, a `:do-update-set` map or a row map
  with more than eight keys is a hash map, whose iteration order is not the written
  order: the SQL is still correct (every column once), but the column order is not
  yours. Use the vector of pairs for `:set` / `:do-update-set`, and `:columns` with
  vector rows for `INSERT`, when the order matters to you.
* **Inline mode is for consoles.** Literals are `'...'` with quotes doubled; on a
  server with `standard_conforming_strings = off` (the default has been `on` since
  PostgreSQL 9.1) backslashes in them would be read as escapes. `NaN`, `Infinity`
  and `-Infinity` are not SQL numeric constants and are a format-error inline (pass
  them as parameters, or as strings for the server to cast); so is any value with no
  literal spelling (a date, an object).
* **An empty `IN` list** is a format-error, not `IN ()`, in both dialects.
* **Under `:sqlite`:** row locking, `DISTINCT ON`, `ON CONSTRAINT`, `TRUNCATE`,
  `DELETE ... USING`, `DEFAULT` as a value, `INSERT ... SELECT ... ON CONFLICT`
  without a `WHERE`, data-modifying CTE bodies, compound operands with their own
  ordering or limit, multi-action or conditional `ALTER TABLE`, index methods,
  `CONCURRENTLY`, unnamed indexes, `NULLS FIRST` / `LAST` in index columns,
  multi-name or `CASCADE` / `RESTRICT` drops and `OR REPLACE` are format-errors (the
  table under Dialects), and a `:serial` column needs `:primary-key`. These are
  grammar facts of SQLite, not choices of the library. One statement binds at most
  32766 parameters, sqlite-cljs's ceiling, counting rows × columns of a multi-row
  `INSERT`, and sql-cljs does not chunk. The SQLite text is written against SQLite
  3.53.4, the version sqlite-cljs's driver (better-sqlite3 13.0.3) bundles;
  everything the dialect renders is older than that.
* **`ON CONFLICT`:** both arbiter forms are exposed and you pick; the PostgreSQL
  documentation prefers unique index inference (`:on-columns`) to `:on-constraint`.
  `DROP INDEX CONCURRENTLY` takes one name and no `CASCADE`, which the library
  enforces; that `CREATE` / `DROP INDEX CONCURRENTLY` cannot run inside a
  transaction block, which arbiters a server accepts and which names exist, the
  server enforces, not this library.
* **Nothing is sent anywhere.** The library holds no connection, reads no
  environment and no file, and decides nothing about execution.

## Documentation

* [`docs/sql-cljs.org`](docs/sql-cljs.org) — the manual: the data model (statements,
  expressions, names and values), the engine's parenthesization and `$N` rules, the
  identifier rule, inline mode, the guides per statement family, the gotchas,
  testing, and the reference of every public var.
* [`docs/sql-api-inventory.org`](docs/sql-api-inventory.org) — the inventory of the
  PostgreSQL SQL grammar the library covers, with the documentation page each fact
  was taken from.
* [`docs/sqlite-inventory.org`](docs/sqlite-inventory.org) — the same for the SQLite
  grammar behind `{:dialect :sqlite}`, with the sqlite-cljs facts the bridge rests
  on (designators, value binding, the parameter ceiling, the bundled version).
* The docstrings of `src/sql_cljs/` are the reference of each function; the
  namespace docstring of `sql-cljs.core` lists every clause shape.

## Layout

```
src/sql_cljs/
  core.cljs         the format engine: format, raw, api-version, every statement head
  helpers.cljs      one builder function per clause, producing plain composable data
  util.cljs         engine internals: the error constructor, the entity validator,
                    the raw fragment type, the inline literal renderer
dev/sql_cljs/
  examples.cljs     every example of this page, offline
  dev.cljs          the Node runtime a REPL attaches to
test/sql_cljs/      the cljs.test suite: one battery per surface, the README
                    lockstep (examples_test) and the API index check (api_index_test)
docs/
  sql-cljs.org              the manual
  sql-api-inventory.org     the PostgreSQL grammar inventory
  sqlite-inventory.org      the SQLite grammar and sqlite-cljs consumer inventory
```

## Public API index

* `sql-cljs.core`:
  * `(format query)`, `(format query {:dialect :sqlite})`, `(format query {:inline
    true})` — a statement map in, `{:text "SELECT ..." :values [...]}` out.
  * `(raw sql)` — a SQL fragment spliced verbatim; `[:raw "..."]` in expression
    position is the same.
  * `api-version` — the version marker, `"0.2.0"`.
* `sql-cljs.helpers`, one builder per clause, each returning `{clause value}` and
  taking the statement being built as an optional first argument:
  * queries — `select`, `distinct`, `distinct-on`, `from`, `join`, `inner-join`,
    `left-join`, `right-join`, `full-join`, `cross-join`, `where`, `group-by`,
    `having`, `order-by`, `limit`, `offset`, `union`, `union-all`, `with`,
    `with-recursive`, `for`;
  * data modification — `insert-into`, `columns`, `values` (one argument, the rows
    or the source statement), `on-conflict` (the target columns), `on-constraint`,
    `index-where`, `do-nothing`, `do-update-set` (one argument, the assignments),
    `do-update-where`, `returning`, `update`, `set` (one argument, a map or pairs),
    `delete-from`, `using`, `truncate`, `restart-identity`, `continue-identity`,
    `cascade`, `restrict`;
  * data definition — `create-table`, `if-not-exists`, `columns` (column
    definitions here), `constraints`, `drop-table`, `if-exists`, `alter-table`,
    `add-column`, `drop-column`, `rename-column`, `create-index`, `unique`,
    `concurrently`, `on`, `using` (the index method), `where` (the partial-index
    predicate), `drop-index`, `create-view`, `or-replace`, `as` (one argument, the
    body), `drop-view`.
* `sql-cljs.util`, the engine internals, public for the engine's own use and for
  anyone extending it: `(format-error op arg message)`, `(format-error op arg
  message data)`; the raw fragment type `Raw`, its protocol `IRaw` with `(raw-sql
  x)`, `(->Raw sql)`, `(raw? x)`, `(make-raw op sql)`; the entity rule `(entity?
  x)`, `identifier-pattern`, `(entity-parts x)`, `(star? x)`, `(entity-name op x
  star?)`, `(operator-shaped? k)`; the inline renderer `(literal x)`.

## Development

```bash
npm install
./run-tests.sh                                                # the publicity gate, then the suite; no network
npx shadow-cljs compile examples && node target/examples.js   # the examples of this page; no network
npx shadow-cljs watch dev                                     # a Node runtime for the REPL
```

The suite pins the exact SQL text of every shape, in parameter and inline mode, and
the `:op` / `:arg` of every malformed input; it runs the examples of this page and
fails when this page shows code or a result that the examples do not produce, and
when the manual's API reference and the public vars of the source disagree. There is
no live check: the library produces text and sends nothing, so there is nothing to
run against a server that the pg-cljs composition above does not already cover.
`./run-tests.sh` ends by replacing its shell with node, so nothing can follow it on
the same command line.

## License

[Unlicense](LICENSE).
