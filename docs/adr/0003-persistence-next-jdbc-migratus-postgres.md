# ADR-0003: Persist on Railway PostgreSQL via next.jdbc and Migratus, migrating at boot

- **Status**: Accepted
- **Date**: 2026-08-09
- **Deciders**: Arthur Granado (with agent research and a security review of the first implementation)
- **Supersedes / amends**: —
- **Superseded by**: —

## Context and problem statement

Bookmarking books needs durable storage, and the PRD fixes hosting on Railway, whose PostgreSQL addon injects a single `DATABASE_URL` in libpq form (`postgresql://user:pass@host:port/db`). Two questions had to be settled before any table existed: which access and migration libraries this repo commits to, and *when* schema changes are applied to a platform that gives us no separate release phase and no shell in production.

The first implementation answered both, and its security review then forced a third question. It converted `DATABASE_URL` into a credentialed JDBC URL string (`jdbc:postgresql://host/db?user=…&password=…`) and passed that string everywhere. That single representation produced three live findings: Migratus logs its own config on every migration and its `censor-password` can only redact a `:password` **key** — handed a `:jdbcUrl` string it returns it verbatim, so the production password went to the log on every deploy; `URISyntaxException` quotes its entire input, so a malformed `DATABASE_URL` echoed the password into a stack trace; and a password containing `:` was silently truncated, producing an auth failure whose cause is invisible. The representation of credentials is therefore an architectural decision here, not an implementation detail.

`/health` also had to be settled: it is unauthenticated (Railway's probe cannot present a credential) and it reports database state, so both its cost and its disclosure are decisions.

## Decision drivers

- Credentials must not be reachable by any accidental `println`, log line or exception message
- A deploy must not be able to look healthy while it is not
- Schema changes must apply without a release phase or a production shell
- Small surface: this is a walking skeleton, not a platform
- Failures must be loud and fast rather than silent or slow

## Considered options

**Access + migrations**

1. **next.jdbc + Migratus, SQL migration files on the classpath** *(chosen)* — thin, data-oriented, no ORM; Migratus is Clojure-native and its state lives in the database.
2. **HugSQL + Flyway** — rejected: Flyway's value is a Java-ecosystem migration story we do not need, and it adds a second config surface for the same `DATABASE_URL`.
3. **An ORM/datamapper (Toucan, Walkable)** — rejected: buys mapping we do not need at the cost of a large surface, against the "small surface" driver.

**When to migrate**

1. **Migrate at boot, in-process, before the server binds** *(chosen)* — the only mechanism Railway gives us that needs no release phase.
2. **A separate migration job/command run by hand** — rejected: no production shell; a hand-run step is a step that gets skipped.
3. **Migrate lazily on first request** — rejected: makes an ordinary request responsible for schema state and hides failures behind a 500.

**How credentials travel**

1. **A next.jdbc db-spec map, credentials as map values** *(chosen)* — the shape every downstream library knows how to redact.
2. **A credentialed `:jdbcUrl` string** — rejected: proven to leak (three findings above); nothing downstream can redact a secret it cannot locate.
3. **Split `DATABASE_URL` into separate env vars at deploy time** — rejected: fights the platform, which injects one variable and rotates it.

## Decision outcome

Chosen: **next.jdbc + Migratus against Railway PostgreSQL, migrating at boot, with credentials only ever in spec maps.**

1. **The stack.** `next.jdbc` for access, `Migratus` for migrations (SQL files in `resources/migrations`, on the classpath), `jsonista` for JSON. No connection pool, no ORM, no query DSL.
2. **Credentials live in spec maps, never in a URL string.** `books.db/db-spec` converts `DATABASE_URL` into a next.jdbc db-spec **map**; `datasource` and `migrate!` both take that map. **Binding rule: no code in this repo may construct a JDBC URL containing a user or password.** A reviewer seeing `:jdbcUrl` with credentials in it should treat it as a defect, not a style choice.
3. **Errors name values, never carry them.** The `java.net.URI` parse is wrapped: a malformed URL raises `ex-info "malformed DATABASE_URL"` with empty ex-data. An unsupported scheme names the dbtype alone. The connectivity probe logs one line — exception class and message — and never the datasource, the spec, or the URL.
4. **Migrate at boot, and crash on failure.** `books.server/run` migrates before the server binds. A failed migration or an unreachable database at boot terminates the process; Railway's restart loop then self-heals a transient outage, and a genuine fault stays down and visible instead of serving traffic against a schema that was never applied. This is deliberate, not an oversight.
5. **Every wait is bounded.** `connectTimeout`, `loginTimeout` and `socketTimeout` default to 5 seconds each (pgjdbc property names, seconds). They are *defaults*: a value in the `DATABASE_URL` query string wins, so an operator can tune a deploy without a code change. Measured against a black-holed address: 10.0s with our settings, 20.0s with the driver's defaults, 2.0s with `?connectTimeout=1` — because `next.jdbc.connection/get-driver-connection` retries a failed connection once, so the wall-clock ceiling is **twice** the configured timeout. Budget for 2× when tuning.
6. **The connectivity probe is cached for 5 seconds** (`books.db/check-ttl-ms`). `/health` is unauthenticated, so an uncached probe would let any caller open one database connection per request.
7. **An absent `DATABASE_URL` is a fault by default.** `/health` answers `503 {"status":"degraded","db":"not-configured"}`, so a deploy that silently lost its variable fails its health check. `DB_OPTIONAL=true` is the explicit opt-in that turns that state into `200 {"status":"ok","db":"not-configured"}` for local and pre-database deploys. The gate covers "no URL configured" only — a configured but unreachable database is 503 either way.
8. **sslmode is passed through, never forced.** Whatever the `DATABASE_URL` carries reaches the driver unchanged; this repo neither adds nor overrides it.
9. **Explicit non-goal**: this does not decide the domain schema, the bookmark data model, or a caching layer. It does not decide connection pooling either — see the limitation below.

## Consequences

- **Good**: a password now has exactly one representation, and every library that touches it knows how to redact it — verified empirically, not assumed (a real migration boot against a database whose password was a unique token produced no occurrence of that token in the captured output; Migratus logged `:password Z<censored>`, and `(str datasource)` / `(pr-str datasource)` print the bare `jdbc:postgresql://host:port/db`).
- **Good**: deploys need no release phase and no human step; the schema state is in the database, where a second instance can read it.
- **Bad / trade-off**: migrate-at-boot means every instance races to migrate on a rolling deploy. Migratus takes a lock in `schema_migrations`, so the losers wait rather than double-apply, but boot time now includes migration time and a long migration delays readiness for everyone. The bill comes due at the first big migration, not today.
- **Bad / trade-off**: crash-on-boot converts a transient database blip into a restart loop. That is the right trade for a service that cannot function without its database, but it does mean the platform's restart policy is load-bearing.
- **Neutral**: `DB_OPTIONAL` is one more environment variable, and the safe value is its default.
- **Honest limitation — no connection pool.** Every `datasource` call opens a connection on demand. That is fine for a health probe and a boot migration, and it will not be fine under real query load; revisit (HikariCP via `next.jdbc.connection/->pool`) when the first real query path lands, not before.
- **Honest limitation — sslmode is the platform's call.** Railway's internal network is not TLS-terminated, so forcing `sslmode=verify-full` (the reviewer's suggestion) would break production outright. Passing it through means that if the platform hands us a URL with no `sslmode`, traffic to the database is unencrypted on that internal network, and this repo will not notice. That risk is accepted and delegated to the platform's network isolation; the day the database moves off that network, this clause is what needs revisiting.
- **Honest limitation — `/health` discloses database state to anyone.** `db: unreachable` tells an unauthenticated caller something about our infrastructure. Accepted: it is the ticket's contract and the platform probe cannot authenticate. The mitigation is that the response carries a state word and nothing else — no host, no error text, no version.

## More information

- Implemented in: ticket [#4](https://github.com/agranado2k/google-books-clojure/issues/4), branch `feat/postgres-wiring`
- Related: ADR-0001 (web stack), ADR-0002 (packaging; `PORT`/`0.0.0.0` binding)
- `org.postgresql/postgresql` is held at ≥ 42.7.7: CVE-2025-49146 covers the 42.7.4–42.7.6 range.
