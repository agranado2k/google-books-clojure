# Development diary

> Living history of the Google Books (Clojure) build. The **Current state** block at the
> top is the agent re-orientation summary — read it first when picking up the
> project. Below it: forward-chronological entries, newest at the bottom.

---

## Current state — 2026-08-09

<!--
Update this block IN PLACE. It is the only part of this file that is edited
rather than appended to: it answers "where is this project right now?" for an
agent (or a human) opening a fresh session, and a stale answer here poisons
every session that reads it. Entries below are append-only.

Keep it to facts an agent cannot cheaply derive: the phase, what is live, what
is in flight. Do not restate the README.
-->

| Field | Value |
| --- | --- |
| **Phase** | Tickets #4, #8, #12 implemented in parallel; PRs #13 (CI), #14 (Postgres), #15 (Tailwind) open, awaiting review + merge. |
| **Repo** | `~/PetProjects/google-books-clojure` (`main`). Feature work happens in `worktree/<slug>` on a `<type>/<slug>` branch. |
| **Remote** | `git@github.com:agranado2k/google-books-clojure.git` |
| **Last commit on `main`** | see `git log` — main moves with docs commits; last milestone: PR #11 merge |
| **Deployed / live** | https://google-books-clojure-production.up.railway.app (Railway project `google-books-clojure`, service built from the repo Dockerfile). |
| **Active worktrees** | `worktree/ci-pipeline` (#13), `worktree/postgres-wiring` (#14), `worktree/tailwind-layout` (#15). |
| **Spec status** | PRD in [issue #1](https://github.com/agranado2k/google-books-clojure/issues/1); tickets #2–#10 + #12/CI (DAG + labels in the checklist comment on #1). #2, #3 done. |

### Open questions / unresolved decisions

<!--
Things that are genuinely undecided, one bullet each, with enough context that
future-you can decide without re-deriving the problem. Strike a line through or
mark **RESOLVED <date>:** in place when it is settled — deleting it loses the
record that it was ever open.
-->

- _None yet._

### Memory pointers for future-me

<!--
The half-dozen facts you keep re-learning. Not documentation — pointers at it.
-->

- **The diary is the orientation document.** Read this `Current state` block at
  session start; everything below it is history.
- **The spec wins** in disputes. When the diary and the spec disagree, the spec
  is the contract and the diary is the log.
- **Decisions live in `docs/adr/`**, not here. A diary entry may *announce* a
  decision, but the ADR is the record.
- **Terms live in `docs/domain-glossary.md`.** One name per concept, everywhere.

### Update protocol

<!--
Shared invariant §8: a rule nothing checks decays into a lie. This protocol is
the cheapest honest form of "when does the diary get written?" — keep it short
enough that it is actually followed, and make the triggers observable events
rather than feelings.
-->

- **Phase milestone reached** → append a new dated entry below.
- **ADR added, decision reversed, or vendor changed** → append a new dated
  entry; do **not** edit old entries.
- **Worktree created for a non-trivial feature** → note it in the next entry;
  remove it from the active list when it merges.
- **Infrastructure changed** → append an entry naming the environment, the size
  of the change, and what moved.
- **Anything above happened** → also refresh the `Current state` block in place.

---

## Entries

<!--
Forward-chronological, newest at the BOTTOM (so reading top-to-bottom reads the
project's history in order). One `###` heading per entry:

    ### YYYY-MM-DD — <headline: what changed, not what you did>

Write what was decided and why, not a commit log — `git log` already exists.
Never edit a past entry; correct it with a new one that references it.
-->

### 2026-08-08 — Bootstrapped from the agentic-sdlc kit

Library that allows the user search and bookmark books via Gooogle books API"

The repo was created from the `agentic-sdlc` template and personalized by
`bootstrap.sh`: the root `AGENTS.md` agent manual, the portable
`constitution/shared-invariants.md` rulebook, the `scripts/check.sh` docs gate
wired to `.githooks/pre-push`, and this documentation set (diary, ADR index +
MADR template, domain glossary, PR template).

The shared layer taken is recorded in `VERSION`; `UPDATING.md` is the recipe for
moving it forward when the kit does.

First real decisions go in `docs/adr/`; first real progress goes below this
line.

### 2026-08-08 — PRD published; stack verified; GitHub Issues chosen as tracker

The first spec landed as [GitHub issue #1](https://github.com/agranado2k/google-books-clojure/issues/1):
a web app to search Google Books by title/author and bookmark results, with
Clerk (Google) sign-in, hosted on Railway. GitHub Issues was confirmed as the
project tracker and recorded in `AGENTS.md` → Local rules; the
`ready-for-agent` autonomy label was created on the repo.

Key verified decisions baked into the PRD (research from primary sources, not
assumption): Clojure has no dominant web framework, so the stack is the
community-standard composition — Ring + Reitit + Jetty, Hiccup + HTMX,
Tailwind standalone CLI; Clerk has no Clojure SDK, so auth is manual RS256 JWT
verification against Clerk's JWKS (`sikt-no/clj-jwt`), minding the 60-second
token lifetime; Railway's Railpack cannot detect Clojure, so deploys use a
multi-stage Dockerfile producing an uberjar; Google Books volume search needs
only an API key (`intitle:`/`inauthor:`). Persistence: Railway Postgres via
next.jdbc + HoneySQL, bookmarks denormalize a volume snapshot.

Test seams agreed with the owner: handler-level (Ring) integration tests with
a stubbed Books port and a test RSA keypair for JWT verification; real
Postgres through the same seam; no browser tests.

Next: `/to-tickets` on issue #1 — the PRD spans multiple sessions.

### 2026-08-08 — PRD decomposed into nine tracer-bullet tickets

`/to-tickets` turned issue #1 into issues #2–#10, confirmed by the owner before
publishing. Ordering doctrine: deploy the walking skeleton to Railway (#3)
right after it exists (#2), so the production pipeline is proven before any
feature. Parallel fronts after #2: Railway deploy (#3), Tailwind base layout
(#4), Postgres wiring (#8); search (#5) and Clerk auth (#7) fan out after the
layout; everything converges on bookmarking (#9), then the bookmarks page
(#10). Six tickets carry `ready-for-agent`; #3, #7, #8 stay human-in-the-loop
(owner accounts on Railway/Clerk, security-sensitive auth middleware, infra
provisioning). The full DAG lives in the checklist comment on #1.

### 2026-08-09 — Walking skeleton merged; first ADRs recorded; toolchain installed

PR #11 landed ticket #2: Ring + Reitit + Jetty service answering `/health`
(GET and HEAD) on `0.0.0.0:$PORT`, uberjar in a digest-pinned multi-stage
Dockerfile running as a non-root user, handler-seam tests plus an
ephemeral-port boot smoke test, TDD pairing guard out of warn-only mode.
The `/review-pr` pass (11 standards findings, 4 behavior confirms) was
applied in full before merge; ADR-0001 (stack composition) and ADR-0002
(Docker/Railway packaging) are the review's lasting output. One deliberate
deferral: registering the API surface in `BEHAVIOR_DELTA_SURFACES` waits
for the first real endpoint (ticket #5).

Infrastructure note: the dev machine had no Clojure toolchain — `openjdk@21`
and `clojure` were installed via Homebrew (Temurin cask needs sudo; the
formula does not). `JAVA_HOME=$(brew --prefix openjdk@21)` is required
until wired into the shell profile.

### 2026-08-09 — Skeleton live on Railway; CI decided as GitHub Actions + native deploys

Ticket #3 executed with the owner in the loop: Railway account upgraded from
expired trial to a paid plan, project `google-books-clojure` created, and the
walking skeleton deployed via `railway up` — live at
https://google-books-clojure-production.up.railway.app/health (200 on GET and
HEAD). Environment: Railway production, new service, Dockerfile build, injected
`PORT` honored. One click remains on #3: connecting the GitHub repo in the
dashboard so pushes to `main` auto-deploy.

CI/CD decision (owner, 2026-08-09): deploys stay on Railway's native GitHub
integration; GitHub Actions does CI only — tests, docs gate, and the pairing
guard's CI twin on every PR. Recorded as ticket #12 (`ready-for-agent`); not in
the original DAG, added at the owner's request.
