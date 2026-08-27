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
`scripts/vendor-htmx.sh` exists only to bump it, and reads both pins out of
`src/books/assets.clj` so there is one copy of each.

## Running it

```sh
# Run the service locally, database-less. Add GOOGLE_BOOKS_API_KEY to make the
# search page actually search; without it the page renders its error state.
DB_OPTIONAL=true clojure -M -m books.server

# Run it against a local Postgres (the same one the tests use).
docker run -d --rm --name books-test-pg -p 5544:5432 -e POSTGRES_PASSWORD=test postgres:16
DATABASE_URL=postgresql://postgres:test@localhost:5544/postgres clojure -M -m books.server

# The test suite. It needs that Postgres container running.
clojure -X:test

# Build the deployable uberjar (target/app.jar).
clojure -T:build uber
```

`GET /search` is the reader-facing search page: a title/author form that swaps
its results in via htmx, with the same URL answering the whole page for a plain
form GET (so it works without JavaScript, and a result URL can be shared). It
needs `GOOGLE_BOOKS_API_KEY`; without one it renders honestly and says search is
not configured.

`GET /sign-in` is the sign-in page: ClerkJS mounts Clerk's own form there and
runs the Google flow in the browser. Pages behind the gate require a signed-in
**Reader** — the `sub` claim of a Clerk session token, verified on the server
against the instance's published RS256 keys (ADR-0005). **A deployment with no
Clerk configuration does not open the gate**: it answers `503` and says sign-in
is not configured here, which is the opposite of `DB_OPTIONAL`'s posture and
deliberately so.

`GET /health` answers JSON: `200 {"status":"ok","db":"ok"}` when the database
is reachable, `503 {"status":"degraded","db":"unreachable"}` when it is not,
and `db: "not-configured"` when no `DATABASE_URL` is set — 503 by default, 200
under `DB_OPTIONAL=true`.

### Environment contract

| Variable | Required | Default | What it does |
| --- | --- | --- | --- |
| `DATABASE_URL` | yes, unless `DB_OPTIONAL=true` | — | The database, in libpq form: `postgresql://user:password@host:port/dbname` (`postgres://` is accepted too). Query parameters — `sslmode` included — are passed to the driver unchanged. Railway injects this. Migrations run at boot against it, and a failed migration or an unreachable database **crashes the boot** deliberately (ADR-0003). |
| `DB_OPTIONAL` | no | `false` | `true` makes running without a `DATABASE_URL` a healthy state. Anything else, including unset, makes a missing `DATABASE_URL` a 503 — so a deploy that silently loses the variable fails its health check. |
| `GOOGLE_BOOKS_API_KEY` | no, but search does nothing without it | — | The Google Books API key the search page uses. **A secret**: it travels in the `X-goog-api-key` request header of every catalog request, never in the URL (ADR-0003 clause 2, as amended), so the search URL is safe to log, render or put in an exception message; redirects are never followed, and any diagnostic built from text this repo did not construct is redacted. Absent or blank is not a boot failure — every search then answers "search is not configured here" and the page says so. |
| `CLERK_PUBLISHABLE_KEY` | no, but nobody can sign in without it | — | The Clerk instance, as `pk_test_…` / `pk_live_…`. **Not a secret**: it is rendered into every page, and it is what the browser gives ClerkJS. It also encodes the instance's Frontend API host, which is where both the ClerkJS script URL and the JWKS location are derived from — so one variable names the instance and a second one cannot contradict it. |
| `CLERK_AUTHORIZED_PARTY` | no, but nobody can sign in without it | — | This app's own public origin (`https://books.example.com`, or `http://localhost:3000` locally), compared against every token's `azp` claim. Without it there is nothing to compare against, and "compare it to nothing" is the CSRF hole — so a half-configured deploy closes the gate rather than opening it (ADR-0005). |
| `CLERK_JWKS_URL` | no | derived from the publishable key | An override for the key endpoint, for a deployment that reaches Clerk through a proxy. Leaving it unset is the safer choice: derived, it cannot name a different instance than the key does. |
| `PORT` | no | `3000` | The HTTP port; the server binds `0.0.0.0`. Railway injects this. |
| `TEST_DATABASE_URL` | no | `postgresql://postgres:test@localhost:5544/postgres` | Tests only — where the suite finds its Postgres. |

There is deliberately **no `CLERK_SECRET_KEY`**, and no `AUTH_OPTIONAL` to
mirror `DB_OPTIONAL`. Nothing here calls Clerk's Backend API — verification
reads the instance's *public* keys — and a switch that turns a gate off is a
switch that eventually gets left on.

### Setting up a Clerk instance

The server-side gate is covered by the test suite. **The browser half is not**:
there is no browser in the suite, so the sign-in form, the Google flow, the user
button and sign-out have never run against a live instance (ADR-0005 records
this). A smoke test needs a real Clerk app:

1. Create an application at [clerk.com](https://clerk.com). A **development**
   instance is enough, and its Frontend API is `https://<slug>.clerk.accounts.dev`.
2. **SSO connections → Add connection → For all users → Google.** On a
   development instance Clerk supplies shared OAuth credentials, so there is
   nothing else to configure. A *production* instance needs your own Google
   Cloud OAuth client (client id + secret, Clerk's redirect URI, and the OAuth
   app set to the "In production" publishing status).
3. Copy the **publishable key** (`pk_test_…`) from the dashboard's API keys page.
4. Run with it, setting the authorized party to the origin you browse from —
   they must match exactly, scheme and port included, or every token is refused:

```sh
DB_OPTIONAL=true \
CLERK_PUBLISHABLE_KEY=pk_test_… \
CLERK_AUTHORIZED_PARTY=http://localhost:3000 \
GOOGLE_BOOKS_API_KEY=… \
clojure -M -m books.server
```

Then visit `/search` signed out (it should send you to `/sign-in`), sign in with
Google, and confirm you land back on `/search` and that the header shows Clerk's
account menu rather than the "Sign in" link.

**No credential — database or API key — goes into a URL string.** Database
credentials reach the driver as db-spec map values; the Books API key is a
request header. That is a binding rule, not a preference (ADR-0003 clause 2,
amended 2026-08-10 to cover the API key as well as the database).

## Deploying

**CI deploys. A merge to `main` does not.** The `deploy` job in
`.github/workflows/ci.yml` runs on push to `main` *after* the `test` and
`docs-gate` jobs are green, and runs
`railway up --service google-books-clojure --detach`. A red `main` therefore
never reaches production, and a pull request — from a fork or otherwise — can
never deploy: the job is guarded on both the event name and the ref.

This replaces Railway's own GitHub push integration, which never fired for this
repo. The reasoning, the trade-offs, and the failure modes are in **ADR-0008**
(`docs/adr/0008-ci-triggers-the-railway-deploy.md`).

Two things are worth knowing before you touch it:

- **`--service` is load-bearing.** Without it an earlier deploy resolved to the
  project's Postgres service and took the database down. Never omit it.
- **A missing `RAILWAY_TOKEN` is a skip, not a failure.** The job prints a
  notice and passes, so forks and token-less clones stay green — which also
  means a revoked token looks like a healthy pipeline. After a merge, confirm in
  the Railway console that a deployment actually started.

To enable deploys, an operator creates one repository secret (no token value
lives in this repo):

1. **Railway → the project → Settings → Tokens** → create a **project token**
   scoped to the production environment. Prefer a project token over a personal
   account token: it reaches this project only, and it does not belong to a
   person who might leave.
2. **GitHub → the repo → Settings → Secrets and variables → Actions → New
   repository secret**, named `RAILWAY_TOKEN`, with that value.

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
