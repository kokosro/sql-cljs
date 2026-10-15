# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.2.0] - 2026-10-16

### Added

- The `:dialect` option of `sql-cljs.core/format`: `{:dialect :pg}` (the
  default, identical to no option) renders PostgreSQL, `{:dialect :sqlite}`
  renders the same statement map for SQLite. Under `:sqlite` every placeholder is
  `?` (one per `:values` entry, in order of appearance; `:values` is the same
  vector); `:limit :all` renders `LIMIT -1` and an `:offset` without `:limit`
  renders `LIMIT -1 OFFSET ?`; `:union` / `:union-all` operands render bare;
  `:ilike` / `:not-ilike` render `LIKE` / `NOT LIKE`; `:serial` / `:bigserial` /
  `:smallserial` with `:primary-key` render `INTEGER ... PRIMARY KEY
  AUTOINCREMENT`; the zero-argument `[:current-timestamp]`, `[:current-date]` and
  `[:current-time]` render the bare key words `CURRENT_TIMESTAMP`, `CURRENT_DATE`
  and `CURRENT_TIME`; a function-call `DEFAULT` is parenthesized; `ON CONFLICT DO
  UPDATE SET` without a target renders. Any other `:dialect` value, `nil`
  included, is a format-error with `:allowed [:pg :sqlite]`; `:dialect` and
  `:inline` combine, and DDL is inline with `:values []` in both dialects.
- The `:sqlite` rejections, each a `:sql-cljs/format-error` with `:op` and `:arg`
  raised before any text is produced: `:for`, `:distinct-on`, `:on-constraint`,
  `:truncate`, `:using` on `:delete-from`, `:default` as a value in `VALUES`,
  `SET` or `DO UPDATE SET`, `INSERT ... SELECT` with `:on-conflict` and no
  `:where` in the select, an `:insert-into` / `:update` / `:delete-from` body in
  `:with` / `:with-recursive`, a compound-select operand with its own `:with` /
  `:with-recursive` / `:order-by` / `:limit` / `:offset` / `:for`, a serial column
  without `:primary-key`, `:alter-table` with several actions or with
  `:if-exists`, `:if-not-exists` on `:add-column`, `:if-exists` / `:cascade` /
  `:restrict` on `:drop-column`, `:create-index` with `:using` or
  `:concurrently`, without a name, or with `:nulls-first` / `:nulls-last` on a
  column, `:drop-table` / `:drop-index` / `:drop-view` with several names or with
  `:cascade` / `:restrict`, `:drop-index` with `:concurrently`, `:create-view`
  with `:or-replace`.
- The bridge to [sqlite-cljs](https://github.com/kokosro/sqlite-cljs): the
  `:sqlite` result goes to `sqlite-cljs.query/execute!` as `{:sql (:text s)
  :params (:values s)}` or `(into [(:text s)] (:values s))`, a DDL statement
  (`:values []`) also through `exec!`; one statement binds at most 32766
  parameters and sql-cljs does not chunk. The SQLite text is written against
  SQLite 3.53.4, the version better-sqlite3 13.0.3 (sqlite-cljs's driver)
  bundles; everything the dialect renders is older than that.
- Documentation: the README's Dialects section and dialect example (run from
  `dev/sql_cljs/examples.cljs`, held by `examples_test.cljs`), the manual's
  Dialects section and dual parameter spellings, the SQLite grammar and
  sqlite-cljs consumer inventory `docs/sqlite-inventory.org`, and
  `dialect_test.cljs` pinning every `:sqlite` string next to its `:pg` string.

### Changed

- `api-version` is `"0.2.0"`. Formatting without `:dialect` renders the `0.1.0`
  text and values unchanged.

## [0.1.0] - 2026-10-15

### Added

- The format engine, `sql-cljs.core/format`: a statement map in, `{:text
  :values}` out, with `$N` placeholders in order of appearance across nested
  statements and CTEs and the values passed through untouched; `{:inline true}`
  renders every value as a literal and returns `:values []`. One line, upper-case
  key words, single spaces, no trailing semicolon; every nested operator operand
  parenthesized. `sql-cljs.core/raw` and `[:raw "..."]` splice SQL verbatim;
  `api-version` is `"0.1.0"`.
- Queries: `SELECT` with `DISTINCT` / `DISTINCT ON`, `FROM` with aliases and
  subqueries, `INNER` / `LEFT` / `RIGHT` / `FULL` / `CROSS JOIN` with `ON` and
  `USING`, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY` with directions and `NULLS
  FIRST` / `LAST`, `LIMIT` / `OFFSET`, `UNION` / `UNION ALL` with parenthesized
  operands, `WITH` / `WITH RECURSIVE`, and the locking clause (`FOR UPDATE` /
  `NO KEY UPDATE` / `SHARE` / `KEY SHARE`, `OF`, `NOWAIT` / `SKIP LOCKED`).
- Expressions: the comparison, logical, arithmetic, `IN` / `NOT IN` (lists and
  subqueries), `LIKE` / `ILIKE`, `BETWEEN`, `IS NULL`, `EXISTS` operators, any
  function call, scalar subqueries, statement maps wherever a subquery is legal.
- Data modification: `INSERT INTO` (alias, column list, multi-row `VALUES` as
  vectors or as maps sharing one key set, `DEFAULT` spelled `:default`, `INSERT
  ... SELECT`, `ON CONFLICT` with column or expression targets, an index
  predicate or `ON CONSTRAINT`, `DO NOTHING` or `DO UPDATE SET ... WHERE`,
  `RETURNING`), `UPDATE` (`SET` from a map or a vector of pairs, `FROM`, `WHERE`,
  `RETURNING`), `DELETE FROM` (`USING`, `WHERE`, `RETURNING`), `TRUNCATE TABLE`
  with the identity and cascade flags, `WITH` before each.
- Data definition: `CREATE TABLE [IF NOT EXISTS]` from column definitions
  `[name type & constraints]` (keyword, vector and `raw` type spellings; `NOT
  NULL`, `NULL`, `UNIQUE`, `PRIMARY KEY`, `DEFAULT`, `CHECK`, `REFERENCES` with
  `ON DELETE` / `ON UPDATE` actions, `CONSTRAINT name`) and table constraints
  (`PRIMARY KEY`, `UNIQUE`, `CHECK`, `FOREIGN KEY`, `CONSTRAINT name`), `DROP
  TABLE`, `ALTER TABLE` (`ADD COLUMN [IF NOT EXISTS]`, `DROP COLUMN [IF EXISTS]`,
  `RENAME COLUMN`), `CREATE [UNIQUE] INDEX [CONCURRENTLY] [IF NOT EXISTS]` with
  `USING`, per-column order and a partial `WHERE`, `DROP INDEX [CONCURRENTLY]`,
  `CREATE [OR REPLACE] VIEW`, `DROP VIEW`, each with `IF EXISTS` and `CASCADE` /
  `RESTRICT` where the grammar has them. DDL literals are always inline and a
  DDL statement's `:values` is `[]`.
- `sql-cljs.helpers`: one builder function per clause, returning the plain
  statement map, threading on an optional leading map and merging repeated
  clauses the HoneySQL way (vectors concatenate, `where` / `having` AND
  together, maps merge, scalars replace); no build function, `merge` composes.
- The `:sql-cljs/format-error` taxonomy, one `ex-info` for every malformed
  input with `:op` (the innermost context) and `:arg` (the offending piece),
  plus `:entity` or `:allowed` where they exist; the identifier rule
  (`[a-z_][a-z0-9_$]*` per part, never quoted, the `raw` hatch named in the
  message) in `sql-cljs.util`, with the raw fragment type and the inline
  literal renderer.
- Documentation: the README with every example run from
  `dev/sql_cljs/examples.cljs` and held in lockstep by `examples_test.cljs`;
  the manual `docs/sql-cljs.org` whose API reference the `api_index_test.cljs`
  checks against the public vars in both directions; the PostgreSQL SQL grammar
  inventory `docs/sql-api-inventory.org`.
- Project skeleton: deps.edn (ClojureScript only, no runtime dependency),
  package.json (no runtime npm dependency), standalone shadow-cljs.edn
  (`node-test`, `dev`, `examples` builds), the publicity gate wired first in
  `run-tests.sh`, the cljs.test suite pinning exact SQL text in parameter and
  inline mode.

[0.2.0]: https://github.com/kokosro/sql-cljs/releases/tag/v0.2.0
[0.1.0]: https://github.com/kokosro/sql-cljs/releases/tag/v0.1.0
