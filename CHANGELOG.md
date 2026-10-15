# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- Project skeleton: deps.edn (ClojureScript only, no runtime dependency),
  package.json (no runtime npm dependency), standalone shadow-cljs.edn
  (`node-test`, `dev`, `examples` builds), the publicity gate wired first in
  `run-tests.sh`, `src/sql_cljs/core.cljs` with `api-version` `"0.1.0"`, dev
  and examples entry points, the cljs.test suite, and the PostgreSQL SQL
  grammar inventory for the `0.1.0` scope in `docs/sql-api-inventory.org`.
