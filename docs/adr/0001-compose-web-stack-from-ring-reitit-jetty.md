# ADR-0001: Compose the web service from Ring + Reitit + Jetty libraries

- **Status**: Accepted
- **Date**: 2026-08-08
- **Deciders**: Arthur Granado (stack confirmed during PRD session; research via agent)
- **Supersedes / amends**: —
- **Superseded by**: —

## Context and problem statement

The service (PRD [#1](https://github.com/agranado2k/google-books-clojure/issues/1)) needs an HTTP stack before the first ticket can be built. Clojure has no single dominant web framework — the State of Clojure surveys publish no framework ranking — so "use the standard" is not an answer by itself; the choice has to be made and recorded once, or every session re-litigates it.

## Decision drivers

- Match where the active Clojure community actually is (templates, maintenance activity), so examples and fixes exist
- Library composition over framework lock-in, per the ecosystem's own doctrine
- Small server-rendered app: no need for a batteries-included data layer we would fight later

## Considered options

1. **Ring + Reitit + Jetty adapter, composed as libraries** *(chosen)* — the composition every currently active community template (Kit, Biff, clojure-stack-lite) is built on; Reitit is under very active development (Metosin).
2. **Pedestal** — rejected: steeper learning curve and heavier machinery than a small server-rendered app needs.
3. **Biff (batteries included)** — rejected: brings XTDB and its own conventions; the PRD already decided PostgreSQL and Hiccup+HTMX, so the batteries would be replaced anyway.
4. **Compojure for routing** — rejected: maintenance mode; new templates and development activity are on Reitit.

## Decision outcome

Chosen: **Ring + Reitit + Jetty, composed as libraries**.

1. HTTP handling is a Ring handler; the router is `metosin/reitit-ring`; the server adapter is `ring/ring-jetty-adapter`.
2. The handler is the primary test seam: tests pass request maps into it and assert on response maps, without a running server (one boot smoke test exercises the Jetty wiring).
3. The server binds `0.0.0.0` on the `PORT` env var (local default 3000).
4. **Explicit non-goal**: this does not decide the UI templating, persistence, or auth stack — those are PRD decisions that get their own records when they land.

## Consequences

- **Good**: aligns with every active community template, so patterns are borrowable; no framework to fight when tickets add HTMX fragments and middleware.
- **Bad / trade-off**: composition means we own the glue (middleware ordering, server lifecycle) that a framework would decide for us.
- **Honest limitation**: "community-standard" was established by repo-activity research on 2026-08-08, not by a survey ranking — the ecosystem could shift and this record would not notice.

## More information

- Implemented in: PR [#11](https://github.com/agranado2k/google-books-clojure/pull/11) (ticket [#2](https://github.com/agranado2k/google-books-clojure/issues/2))
- Related: PRD [#1](https://github.com/agranado2k/google-books-clojure/issues/1) Implementation Decisions; ADR-0002 (packaging)
