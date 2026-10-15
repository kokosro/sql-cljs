# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- Data modification: `INSERT INTO` (alias, column list, multi-row `VALUES` as
  vectors or as maps sharing one key set, `DEFAULT` spelled `:default`,
  `INSERT ... SELECT` from a statement map, `ON CONFLICT` with column or
  expression targets, an index predicate or `ON CONSTRAINT`, `DO NOTHING` or
  `DO UPDATE SET ... WHERE`, `RETURNING`), `UPDATE` (`SET` from a map or a
  vector of pairs, `FROM`, `WHERE`, `RETURNING`), `DELETE FROM` (`USING`,
  `WHERE`, `RETURNING`), `TRUNCATE TABLE` with the identity and cascade
  flags, `WITH` before each; the statement validator reports a clause that
  does not apply next to the statement's head with the head as `:op`.
- `sql-cljs.helpers`: one builder function per clause, returning the plain
  statement map, threading on an optional leading map and merging repeated
  clauses the HoneySQL way.
- The format engine and the query surface: `sql-cljs.core/format` renders a
  statement map to `{:text :values}` with `$N` placeholders in order of
  appearance and values passed through untouched, or with every value inlined
  as a literal under `{:inline true}`; `SELECT` with `DISTINCT` / `DISTINCT ON`,
  `FROM` with aliases and subqueries, `INNER` / `LEFT` / `RIGHT` / `FULL` /
  `CROSS JOIN` with `ON` and `USING`, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`
  with directions and `NULLS FIRST` / `LAST`, `LIMIT` / `OFFSET`, `UNION` /
  `UNION ALL` with parenthesized operands, `WITH` / `WITH RECURSIVE`, and the
  locking clause; the comparison, logical, `IN`, `LIKE` / `ILIKE`, `BETWEEN`,
  `IS NULL`, `EXISTS` and arithmetic operators, function calls, nested
  statements anywhere a subquery is legal; `sql-cljs.core/raw` and `[:raw ...]`
  as the verbatim escape hatch; the `:sql-cljs/format-error` taxonomy with
  `:op` and `:arg` data, raised for every malformed input including any entity
  outside the bare lower-case identifier charset (`sql-cljs.util`).
- Project skeleton: deps.edn (ClojureScript only, no runtime dependency),
  package.json (no runtime npm dependency), standalone shadow-cljs.edn
  (`node-test`, `dev`, `examples` builds), the publicity gate wired first in
  `run-tests.sh`, `src/sql_cljs/core.cljs` with `api-version` `"0.1.0"`, dev
  and examples entry points, the cljs.test suite, and the PostgreSQL SQL
  grammar inventory for the `0.1.0` scope in `docs/sql-api-inventory.org`.
