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
query side is in place: `format` renders `SELECT` statements (with `DISTINCT`, `FROM`,
joins, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT` / `OFFSET`, `UNION`, `WITH`
and locking clauses) and the expression operators, `raw` splices verbatim SQL, and
`{:inline true}` renders every value as a literal for consoles and debugging.

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

Every function of this library is a pure helper; the builder functions and `format`
will be listed here with `;; =>` results.

## Error handling

One taxonomy, `:sql-cljs/format-error`, raised as `ex-info` for malformed input; the
table of kinds and data keys will live here.

## Features

The covered PostgreSQL grammar (queries, expressions and operators, data
modification, data definition) will be listed here, one capability per bullet.

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
  helpers.cljs      builder functions producing plain composable data (to come)
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

The full index follows the surface.

## Development

```sh
npm install
./run-tests.sh                 # publicity gate, then the offline cljs.test suite
npx shadow-cljs compile examples && node target/examples.js
```

## License

[Unlicense](LICENSE).
