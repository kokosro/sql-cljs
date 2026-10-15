# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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

[0.1.0]: https://github.com/kokosro/sql-cljs/releases/tag/v0.1.0
