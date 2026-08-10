# Google Books (Clojure)

Library that allows the user search and bookmark books via Gooogle books API"

<!--
This README was stamped by the agentic-sdlc bootstrap on 2026-08-08. It
describes the process scaffolding you inherited, because that is all that exists
on day one. Replace the top half with what this project actually is as soon as
there is something to say; keep the bottom half, which documents machinery that
stays true.
-->

## Getting started

```sh
# Clone, then wire the git hooks. Hook path is per-clone config and cannot be
# committed, so every collaborator runs this once.
git config core.hooksPath .githooks

# The docs gate. Runs automatically before every push.
sh scripts/check.sh
```

## Developing

```sh
# Build the stylesheet (standalone Tailwind CLI, no Node: `brew install tailwindcss`).
# Pass --watch while working on pages. Run it before the server, or the page
# renders unstyled; `clojure -T:build uber` refuses to package without it.
scripts/build-css.sh
```

The committed Tailwind input is `styles/app.css`; the generated
`resources/public/css/app.css` is gitignored and served by the app at
`/css/app.css`. The Dockerfile build stage runs the same CSS build with a
version-pinned, checksum-verified Tailwind binary.

Client-side interactivity is [htmx](https://htmx.org), **vendored** rather than
loaded from a CDN: the release is committed at
`resources/public/js/htmx-<version>.min.js`, its version and SHA-256 are pinned
in `src/books/assets.clj`, and the test suite re-hashes the committed bytes on
every run. Nothing needs to be built or fetched for it —
`scripts/vendor-htmx.sh` exists only to bump or re-verify it.

## Running it

```sh
# Run the service locally, database-less.
DB_OPTIONAL=true clojure -M -m books.server

# Run it against a local Postgres (the same one the tests use).
docker run -d --rm --name books-test-pg -p 5544:5432 -e POSTGRES_PASSWORD=test postgres:16
DATABASE_URL=postgresql://postgres:test@localhost:5544/postgres clojure -M -m books.server

# The test suite. It needs that Postgres container running.
clojure -X:test

# Build the deployable uberjar (target/app.jar).
clojure -T:build uber
```

`GET /health` answers JSON: `200 {"status":"ok","db":"ok"}` when the database
is reachable, `503 {"status":"degraded","db":"unreachable"}` when it is not,
and `db: "not-configured"` when no `DATABASE_URL` is set — 503 by default, 200
under `DB_OPTIONAL=true`.

### Environment contract

| Variable | Required | Default | What it does |
| --- | --- | --- | --- |
| `DATABASE_URL` | yes, unless `DB_OPTIONAL=true` | — | The database, in libpq form: `postgresql://user:password@host:port/dbname` (`postgres://` is accepted too). Query parameters — `sslmode` included — are passed to the driver unchanged. Railway injects this. Migrations run at boot against it, and a failed migration or an unreachable database **crashes the boot** deliberately (ADR-0003). |
| `DB_OPTIONAL` | no | `false` | `true` makes running without a `DATABASE_URL` a healthy state. Anything else, including unset, makes a missing `DATABASE_URL` a 503 — so a deploy that silently loses the variable fails its health check. |
| `PORT` | no | `3000` | The HTTP port; the server binds `0.0.0.0`. Railway injects this. |
| `TEST_DATABASE_URL` | no | `postgresql://postgres:test@localhost:5544/postgres` | Tests only — where the suite finds its Postgres. |

Credentials reach the driver as db-spec map values and are never assembled into
a JDBC URL string; that is a binding rule, not a preference (ADR-0003 clause 2).

## How this repo is run

This project is built with an **agent-first SDLC**: a written spec before code,
vertical slices, tests as the specification, a fresh context per phase, and a
human merge gate. The rules are in the repo, not in anyone's head.

| Path | What it is |
| --- | --- |
| `AGENTS.md` | The agent operating manual. Loaded into **every** agent session, so it is deliberately short. Yours to edit. |
| `CLAUDE.md`, `GEMINI.md` | Shims — one import line each, pointing at `AGENTS.md` so a tool that looks for its own filename finds the same manual. Never edit them. |
| `constitution/shared-invariants.md` | The portable rulebook — the invariants that hold regardless of stack, domain, or vendor. **Shared layer:** copied verbatim, not edited here. |
| `docs/diary.md` | The development diary. The **Current state** block at the top is the orientation document — read it first when picking the project up. |
| `docs/adr/` | Architecture Decision Records (MADR). `INDEX.md` lists what is currently binding; `NNNN-template.md` starts a new one. |
| `docs/domain-glossary.md` | The ubiquitous language. One name per concept, used in code and in conversation. |
| `.github/PULL_REQUEST_TEMPLATE.md` | The PR checklist, including the human confirm-list for behavior findings. |
| `scripts/check.sh` | The docs gate — fails when the docs and the repo stop describing the same thing. |
| `.githooks/pre-push` | Runs the gate before every push, with a loud, logged bypass. |
| `VERSION` | Which release of the shared layer this repo took. |
| `UPDATING.md` | How to move the shared layer forward when the kit does. |

## The shared layer

Almost everything here is **yours** — this README, `AGENTS.md`, the docs, the
code. A small part is not: the files listed under `files:` in `VERSION` are the
**shared layer**, copied verbatim from the
[agentic-sdlc](https://github.com/agranado2k/agentic-sdlc) kit and deliberately
not edited downstream. They name no product, no command, and no vendor, which is
exactly what makes them copyable at all.

A local exception to a shared rule does **not** get edited into the shared file.
It goes in a local article, and the shared copy stays byte-identical — otherwise
the next update becomes an archaeology exercise instead of a diff.

See `UPDATING.md` for the update recipe.

## The gate

A process rule that nothing checks decays into a lie, and a stale standing
instruction is worse than an absent one — every agent session loads it. So the
rules here are executable or CI-verified rather than merely written down.

`sh scripts/check.sh` is this repo's docs gate. It runs on `git push` via
`.githooks/pre-push`; `PUSH_WITHOUT_DOCS=1 git push` is the documented,
warning-printing escape hatch.
