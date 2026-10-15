# sql-cljs

A pure ClojureScript (Node.js) SQL query builder for PostgreSQL: a statement is a
map of clauses, an expression is a vector, and the result is the parameterized query
a PostgreSQL client sends. Plain ClojureScript data goes in and a `{:text :values}`
map comes out, the exact shape [pg-cljs](https://github.com/kokosro/pg-cljs)'s
`query!` takes; keywords and symbols name tables and columns, strings are always
values, and values are passed through untouched for the client to encode. The
library is pure formatting: it has no runtime npm dependency at all, nothing in it
is asynchronous, and nothing touches the network.

This README grows into the full shape shared by the sibling libraries
(requirements, an offline quick start, going live, scenarios, pure helpers, the error
taxonomy, features, settings, documentation and layout) as the surface is built. The
query, data-modification and data-definition sides are in place: `format` renders
`SELECT` statements (with `DISTINCT`, `FROM`, joins, `WHERE`, `GROUP BY`, `HAVING`,
`ORDER BY`, `LIMIT` / `OFFSET`, `UNION`, `WITH` and locking clauses) and the
expression operators, `INSERT` (multi-row `VALUES`, rows as maps, `DEFAULT`, `INSERT
... SELECT`, `ON CONFLICT`, `RETURNING`), `UPDATE` (`SET`, `FROM`), `DELETE`
(`USING`) and `TRUNCATE`, and the DDL statements `CREATE TABLE` (column and table
constraints), `DROP TABLE`, `ALTER TABLE` (add, drop and rename column), `CREATE
INDEX` / `DROP INDEX` and `CREATE VIEW` / `DROP VIEW`; `raw` splices verbatim SQL,
`{:inline true}` renders every value as a literal for consoles and debugging (DDL is
always rendered that way, since it takes no parameters), and `sql-cljs.helpers`
offers one builder function per clause that returns the same plain maps.

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
vector of values without a server. The examples will live in
`dev/sql_cljs/examples.cljs` and run with
`npx shadow-cljs compile examples && node target/examples.js`.

### Going live

The `{:text :values}` map `format` returns is handed to a PostgreSQL client such as
pg-cljs's `query!` as is. **Running it executes SQL against your own database:
whether and when to do that is your decision.**

## Scenarios

Realistic multi-step flows (a query built in pieces, an upsert, a migration) will be
named functions of `examples.cljs` with the text they produce.

## Pure helpers

Every function of this library is a pure helper. The data is the API; the builders of
`sql-cljs.helpers` are sugar that returns the same maps and threads on an optional
leading statement:

```clojure
(require '[sql-cljs.core :as sql] '[sql-cljs.helpers :as h])

(-> (h/select :a [[:count :*] :n]) (h/from :t) (h/where [:= :a 1]) (h/group-by :a))
;; => {:select [:a [[:count :*] :n]] :from [:t] :where [:= :a 1] :group-by [:a]}

(def upsert
  (-> (h/insert-into :t) (h/values [{:id 1 :name "x"}])
      (h/on-conflict :id) (h/do-update-set {:name :excluded/name}) (h/returning :id)))
upsert
;; => {:insert-into :t :values [{:id 1 :name "x"}]
;;     :on-conflict {:on-columns [:id] :do-update-set {:name :excluded/name}}
;;     :returning [:id]}
(sql/format upsert)
;; => {:text "INSERT INTO t (id, name) VALUES ($1, $2) ON CONFLICT (id) DO UPDATE SET name = excluded.name RETURNING id"
;;     :values [1 "x"]}
```

A repeated clause merges the HoneySQL way: vector clauses concatenate (`(-> (select
:a) (select :b))` is `{:select [:a :b]}`), `where` / `having` combine with `[:and
...]`, map clauses (`set`, the `on-conflict` family) merge, scalar clauses replace.
Since a leading map is the statement being built, a subquery given as a clause item is
written nested, `(h/from [[{:select [:a] :from [:u]} :s]])`; `union` / `union-all`
take operands only and never thread. The helpers validate nothing: malformed data
fails in `format`. There is no `build` function; `merge` on the maps is the same
thing.

## Error handling

One taxonomy, `:sql-cljs/format-error`, raised as `ex-info` for malformed input; the
table of kinds and data keys will live here.

## Features

The covered PostgreSQL grammar, one capability per bullet (data definition to come):

- `SELECT` with `DISTINCT` / `DISTINCT ON`, `FROM` with aliases and subqueries,
  `INNER` / `LEFT` / `RIGHT` / `FULL` / `CROSS JOIN` with `ON` and `USING`, `WHERE`,
  `GROUP BY`, `HAVING`, `ORDER BY` with directions and `NULLS FIRST` / `LAST`,
  `LIMIT` / `OFFSET`, `UNION` / `UNION ALL`, `WITH` / `WITH RECURSIVE`, `FOR UPDATE`
  / `SHARE` / `NO KEY UPDATE` / `KEY SHARE` with `NOWAIT` / `SKIP LOCKED`.
- Expressions: comparison, logical, `IN` / `NOT IN` (lists and subqueries), `LIKE` /
  `ILIKE`, `BETWEEN`, `IS NULL`, `EXISTS`, arithmetic, function calls, scalar
  subqueries, `raw` fragments.
- `INSERT INTO t [AS alias] [(columns)]` with multi-row `VALUES` (rows as vectors or
  as maps sharing one key set), `DEFAULT` in a value slot (`:default`), `INSERT ...
  SELECT` (a statement map as `:values`), `ON CONFLICT` with column or expression
  targets and an index predicate, `ON CONSTRAINT`, `DO NOTHING`, `DO UPDATE SET ...
  [WHERE ...]` (`:excluded/col` names the proposed row), `RETURNING`.
- `UPDATE t [AS alias] SET` from a map or a vector of `[column expression]` pairs,
  `DEFAULT`, `FROM`, `WHERE`, `RETURNING`.
- `DELETE FROM t [AS alias]` with `USING`, `WHERE`, `RETURNING`.
- `TRUNCATE TABLE t, u` with `RESTART IDENTITY` / `CONTINUE IDENTITY` and `CASCADE` /
  `RESTRICT`.
- `WITH` before `INSERT`, `UPDATE` and `DELETE`.
- `CREATE TABLE [IF NOT EXISTS] t (...)` from column definitions `[name type &
  constraints]` (types as upper-cased keywords, `[:varchar 40]` for arguments,
  `:double-precision` for multi-word types, `raw` for the rest; `NOT NULL`, `NULL`,
  `UNIQUE`, `PRIMARY KEY`, `DEFAULT`, `CHECK`, `REFERENCES` with `ON DELETE` / `ON
  UPDATE` actions, `CONSTRAINT name`) and table constraints (`PRIMARY KEY`,
  `UNIQUE`, `CHECK`, `FOREIGN KEY ... REFERENCES` with actions, `CONSTRAINT name`).
- `DROP TABLE [IF EXISTS] t, u [CASCADE | RESTRICT]`.
- `ALTER TABLE [IF EXISTS] t` with `ADD COLUMN [IF NOT EXISTS]`, `DROP COLUMN [IF
  EXISTS] [CASCADE | RESTRICT]` (several, comma-separated) or `RENAME COLUMN ... TO`.
- `CREATE [UNIQUE] INDEX [CONCURRENTLY] [IF NOT EXISTS] [name] ON t [USING method]
  (columns with ASC / DESC / NULLS, expressions) [WHERE predicate]`, `DROP INDEX
  [CONCURRENTLY] [IF EXISTS] i [CASCADE | RESTRICT]`.
- `CREATE [OR REPLACE] VIEW v [(columns)] AS query`, `DROP VIEW [IF EXISTS] v
  [CASCADE | RESTRICT]`.
- DDL literals are always inline and a DDL statement's `:values` is always `[]`.

Not covered: `INTERSECT` / `EXCEPT`, `DEFAULT VALUES`, `OVERRIDING ... VALUE`,
`ONLY`, the multi-column `SET (a, b) = (...)` forms, `WHERE CURRENT OF`, type casts,
identifier quoting (names outside `[a-z_][a-z0-9_$]*` go through `raw`), and in DDL
`TEMPORARY` / `UNLOGGED`, `GENERATED` and identity columns, `DEFERRABLE`, `EXCLUDE`,
the other `ALTER TABLE` actions, `INCLUDE` / `COLLATE` / `opclass` on indexes and
the view options (a `raw` fragment stands in for a constraint or a type the shapes
do not spell).

## Limits and settings

The `format` options (`:inline`) with their defaults and meanings, and what the
library does not enforce, will be tabled here.

## Documentation

- `docs/sql-cljs.org`: the manual (to come).
- `docs/sql-api-inventory.org`: the inventory of the PostgreSQL SQL grammar the
  library covers, with the documentation page each fact was taken from.

## Layout

```
src/sql_cljs/
  core.cljs         format engine, `format`, `raw`, `api-version`
  helpers.cljs      builder functions, one per clause, producing plain composable data
  util.cljs         engine internals: the error constructor, the entity validator,
                    the raw fragment type, the inline literal renderer
dev/sql_cljs/
  dev.cljs          node-script entry point for a REPL
  examples.cljs     every example of the README
docs/
  sql-api-inventory.org
test/sql_cljs/      mirrors src; run-tests.sh + test/publicity_gate.sh
```

## Public API index

- `sql-cljs.core/format`: `(format query)` or `(format query {:inline true})`, a
  statement map in, `{:text "SELECT ..." :values [...]}` out.
- `sql-cljs.core/raw`: `(raw "NOW()")`, a SQL fragment spliced verbatim.
- `sql-cljs.core/api-version`: the version marker, `"0.1.0"`.
- `sql-cljs.helpers`: one builder per clause, each returning `{clause value}` and
  taking the statement being built as an optional first argument. Queries: `select`,
  `distinct`, `distinct-on`, `from`, `join`, `inner-join`, `left-join`, `right-join`,
  `full-join`, `cross-join`, `where`, `group-by`, `having`, `order-by`, `limit`,
  `offset`, `union`, `union-all`, `with`, `with-recursive`, `for`. Data
  modification: `insert-into`, `columns`, `values` (one argument, the vector of rows
  or the source statement), `on-conflict` (the target columns), `on-constraint`,
  `index-where`, `do-nothing`, `do-update-set` (one argument, the assignments),
  `do-update-where`, `returning`, `update`, `set` (one argument, a map or a vector of
  pairs), `delete-from`, `using`, `truncate`, `restart-identity`,
  `continue-identity`, `cascade`, `restrict`. Data definition: `create-table`,
  `if-not-exists`, `columns` (column definitions here), `constraints`, `drop-table`,
  `if-exists`, `alter-table`, `add-column` (the definition, then options),
  `drop-column` (the name, then options), `rename-column`, `create-index` (the name,
  or none), `unique`, `concurrently`, `on`, `using` (the index method), `where` (the
  partial-index predicate), `drop-index`, `create-view`, `or-replace`, `as` (one
  argument, the body), `drop-view`. Refer to the namespace with an alias: `for`,
  `set`, `update`, `group-by` and `distinct` shadow `cljs.core`.

The full index follows the surface.

## Development

```sh
npm install
./run-tests.sh                 # publicity gate, then the offline cljs.test suite
npx shadow-cljs compile examples && node target/examples.js
```

## License

[Unlicense](LICENSE).
