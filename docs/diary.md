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
| **Phase** | Tickets #2, #4, #8, #12 merged. Frontier: #5 (search — needs the Google Books API key), #7 (Clerk auth — needs a Clerk app). |
| **Repo** | `~/PetProjects/google-books-clojure` (`main`). Feature work happens in `worktree/<slug>` on a `<type>/<slug>` branch. |
| **Remote** | `git@github.com:agranado2k/google-books-clojure.git` |
| **Last commit on `main`** | see `git log` — main moves with docs commits; last milestone: PR #11 merge |
| **Deployed / live** | https://google-books-clojure-production.up.railway.app — **stale: still the walking skeleton**. Railway is not connected to the repo, so nothing auto-deploys; `main` is three features ahead of production. |
| **Active worktrees** | `worktree/kit-0-4-0` on `chore/kit-0-4-0` — the shared-layer update, now carried on to 0.10.0, open as a PR. |
| **Kit / shared layer** | 0.10.0 (see `VERSION`). Capability tiers are live: `scripts/agents.config.sh` maps all four onto Anthropic model families, plus one domain override (`AGENT_TIER_IMPLEMENTER_CONTENT`). `constitution/shared-code-craft.md` is a second shared article. `/dogfood` is adopted; its surfaces and personas are in `constitution/local-product.md`. |
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

### 2026-08-09 — Three tickets merged; production is now behind main

PRs #13 (CI), #14 (persistence) and #15 (UI) all landed, closing tickets #12,
#8 and #4. `main` now carries: a GitHub Actions pipeline that runs the suite
against a live Postgres service container, compiles the stylesheet and builds
the uberjar; `books.db` with credentials confined to db-spec maps (never a URL
string), migrate-on-boot, and a JSON `/health` with three states gated by
`DB_OPTIONAL`; and a Hiccup landing page whose Tailwind CSS is built by the
checksum-pinned standalone CLI and served from a `/css/`-scoped static surface.
ADR-0003 (persistence) and ADR-0004 (server-rendered UI) record the decisions;
ADR-0002 gained a dated amendment extending pinning to fetched build tools.

Two things worth carrying forward. The `/review-pr` pass on #14 found — and
proved live — that Migratus logged the database password whenever the JDBC URL
carried credentials; the fix (spec maps, so the library's redactor engages) is
now a binding rule in ADR-0003 rather than a one-off patch. And merging #15
after #14 was a genuine code merge, not a formality: both branches had
restructured the handler, so `make-app` was recombined by hand and the merged
image re-verified against a real Postgres before the branch was pushed.

**Production is stale.** The Railway service still runs the walking-skeleton
image from 2026-08-09 (`/health` answers plain `ok`; `/` 404s). Ticket #3's
last step — connecting the GitHub repo in the Railway dashboard so pushes to
`main` deploy — was never done, and no deploy has run since. The database and
its `DATABASE_URL` reference variable are provisioned and Online, so the merged
code should come up healthy once a deploy happens.

### 2026-08-26 — Shared layer moved 0.3.0 → 0.4.0, and the half that is not shared layer

Two commits on `chore/kit-0-4-0`, deliberately separate.

**Part 1 — the shared layer.** `scripts/agents.lib.sh` (the capability-tier
resolver) joined the manifest and `UPDATING.md` itself changed; the shared
invariants did not move between the two releases. Step 3's drift check printed
`clean` for all eleven 0.3.0 files, so step 5 was a plain overwrite with nothing
to merge — the cheap case, and it was cheap because nobody had edited a file
that was not theirs to edit.

**Part 2 — everything the manifest does not list, which is where this release's
value actually sits.** `/implement` now ends at an open PR carrying an
independent review rather than at a local commit; `/to-tickets` stamps a
capability tier on every ticket and surfaces the tier mix at its quiz;
`/improve-codebase-architecture` and `/dogfood` are new. The manual gained a
Capability tiers section, and `constitution/local-product.md` now declares the
one surface and two personas `/dogfood` is allowed to drive.

Three things worth carrying forward.

**The tier → model mapping is ours and is written down.** `planner` and
`reviewer` run on `opus`, `implementer` on `sonnet`, `mechanical` on `haiku`.
Reviewer above implementer is the load-bearing part: `/implement` requires its
review to run on a different tier than the code's author, and a cheap verdict
fails silently rather than loudly. The values are Claude Code's family aliases
rather than dated API identifiers because that is what the harness's spawn enum
accepts — and because an alias is the one form of this value that does not rot.

**Two kit workflow templates were deliberately not taken.** `UPDATING.md` §9c
classifies `docs-gate.yml` and `tdd-pairing.yml` as NEW, because it can only ask
whether we have a file at that path — not whether we ever did. We did, at
v0.3.0, and ticket #12 folded both into `ci.yml` on purpose. Following the
recipe literally would have silently re-added two workflows duplicating jobs CI
already runs. Recorded here so the next update does not re-derive it.

**The 0.3.0 recipe cannot tell you it has been superseded.** Part 1 ends at "run
the gate, commit, note it in the diary" — and by then step 5 has already
replaced `UPDATING.md` with a version that has a whole second half. A consumer
who follows the file they started reading lands on 0.4.0 with the resolver on
disk, no config, no callers, a green gate, and no signal that anything is
missing. Re-read `UPDATING.md` after step 5 of any future update, before
believing you are done.

### 2026-08-27 — Shared layer moved 0.4.0 → 0.9.0; the resolver was a security fix

Two more commits on the same branch, same Part 1 / Part 2 split.

**Part 1 — the shared layer.** Step 3 printed `clean` for all twelve 0.4.0
files again. `constitution/shared-code-craft.md` joined the manifest — ten
portable rules for the diff an agent produces — and three shared scripts changed
behaviour. The step-6 gate came back **red** with `article-unreferenced`, which
is the recipe working as designed: the article is shared layer, the pointer to
it lives in our `AGENTS.md`, and the red is what forces both halves to land
together. One pointer line, and it went green.

**Part 2.** `/explain-diff` is new (interactive HTML explainer for a diff,
branch or PR — it teaches, it never reviews). `/review-pr` gained an OWASP
Agentic Skills Top 10 audit for diffs that touch agent-facing surfaces, and the
`ai-review` prompt file gained the same. `/to-tickets` and `/implement` learned
the optional `Domain:` line. `/tdd` and `/review-pr` now both point at the new
craft article.

Four things worth carrying forward.

**0.6.0 was a security fix and we were carrying the vulnerable copies.** Both
`scripts/agents.lib.sh` and `scripts/guards.lib.sh` resolved their config from
the *caller's* current directory, so running either while standing inside a
cloned third-party repo sourced — that is, executed — that clone's config. Our
own root manual names cloned third-party repos as untrusted content. Verified
before and after against a throwaway hostile clone: the 0.4.0 copies ran the
attacker's config and took its value; the 0.9.0 copies keep ours, and the guards
loader prints an explicit refusal. The same 0.4.0 resolver also rejected every
real tier name under `zsh` (word-splitting), killed an interactive zsh that
sourced it, and aborted silently under `set -e`. All four now behave.

**We mapped one task domain, and deliberately only one.**
`AGENT_TIER_IMPLEMENTER_CONTENT='opus'`. Prose at implementer tier — ADRs, this
diary, PRD and ticket bodies, the glossary, `/explain-diff`'s reports — has no
oracle: `clojure -X:test` and `scripts/check.sh` fail loudly on bad code and say
nothing about a badly argued decision record that every later session then
re-reads. `code` is left unmapped because it is what `AGENT_TIER_IMPLEMENTER`
already means here, and a domain key that restates its own fallback is noise.

**§9c now answers the question we recorded last time.** The 0.4.0 recipe
classified `docs-gate.yml` and `tdd-pairing.yml` as `NEW` and would have had us
re-add two workflows `ci.yml` already runs. 0.9.0's loop asks a second question —
did we have the file at the release we are on? — and prints `DECLINED` for both.
The note we left for our future selves was consumed by the tool instead.

**§9d has a live bug: do not run its ADD branch on
`scripts/docs-conformance/local-vocabulary.mjs`.** In the kit that path exists
only as `local-vocabulary.mjs.template`; bootstrap stamps it. So the recipe's
`kit cat-file -e` test says "not present at either ref", prints `ADD`, and runs
`kit show "$TO_REF:$C" >"$C"` — the redirect truncates our real vocabulary file
to zero bytes *before* `kit show` fails. Reproduced in a sandbox, not on the
real file. Relatedly, §9d's `keys()` extractor only matches shell `NAME=`, so it
finds nothing at all in the two `.mjs` configs it names and its key-set diff is
vacuously empty for them — which is how it missed the change that actually
mattered this release: `portability.files` in `scripts/docs-conformance/config.mjs`
had to gain the new shared article by hand, or the gate would never have checked
it for product-vocabulary leaks. Both reported upstream.

## 2026-08-27 — shared layer 0.9.0 → 0.10.0, and both upstream bugs are fixed

The two defects this project's own update found last entry are closed upstream,
and this is the release that carries the fix. Only one shared-layer file changed
— `UPDATING.md` — so nothing this project *runs* is affected. What changed is the
recipe our next update follows, which is the point: the previous one could have
zeroed `scripts/docs-conformance/local-vocabulary.mjs`, our product vocabulary,
and could have eaten a local constitution article under zsh, which is this
machine's default shell.

Upstream fixed both as shapes rather than incidents, and the audit found more
than we reported: eight take-sites had the truncate-before-failure pattern, not
one, and four expansions were zsh-reachable, not three — the extra one being
prose that tells the reader to run a command, which no test would have executed.
Every take now writes only on success.

Also taken, both byte-clean against v0.9.0 and so straight takes: `/implement`
now appends an `/explain-diff` narrative to the PR body below an explicit marker,
and `/review-pr`'s Axis-2 reviewer stops reading there — the implementer's
narrative is exactly what that agent's context isolation exists to exclude.

Verified: all 13 manifest files verbatim on bytes **and** mode; docs gate green at
0.10.0; docs-conformance harness 52/52; `clojure -X:test` 45 tests / 85 assertions
/ 0 failures.
