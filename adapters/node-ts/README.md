# `node-ts` — the worked reference wiring

**Stack this assumes:** a pnpm workspace of TypeScript packages and apps,
Vitest as the unit runner, GitHub Actions for CI, Node 22+.

> **Copy only if your stack matches.** Every value below is a real value from
> the repo this framework was extracted from, not a plausible-looking sample.
> That is what makes it useful — and exactly why pasting it into a different
> shape of repo produces a guard that silently measures the wrong tree. If your
> layout differs, read this for the *shape of the decision* and write your own.

Install commands and the post-copy edit list: [`INSTALL.md`](INSTALL.md).

---

## What this adapter fills in

The kit ships three configuration points with nothing in them, on purpose. This
adapter shows all three filled:

| Kit configuration point | Ships as | This adapter's answer |
| --- | --- | --- |
| `scripts/guards.config.sh` → `GUARD_SOURCE_RE` | empty (guard inactive) | the Vitest-covered trees of a pnpm monorepo |
| `scripts/guards.config.sh` → `BEHAVIOR_DELTA_SURFACES` | two kit defaults | six more surfaces: API, persistence, env contract, HTTP headers, error model, agent-facing prompts |
| *(nothing — the kit has no opinion)* | — | two optional tiers a Node repo can afford: a **differential mutation diagnostic** and a **prompt-eval suite** |

---

## 1. The guards config

Copyable file: [`guards.config.sh.example`](guards.config.sh.example). It is a
drop-in replacement for `scripts/guards.config.sh` with every value filled in.
This section explains the values; the file is the thing you copy.

### `GUARD_SOURCE_RE` — what counts as source

```sh
GUARD_SOURCE_RE='^(packages/[^/]+/src/|apps/[^/]+/src/|apps/[^/]+/app/(server|edit)/|scripts/docs-conformance/).*\.(ts|tsx|mjs)$'
```

Read it left to right, because each alternative was added for a reason:

- **`packages/<name>/src/`** — the workspace libraries. One anchored `[^/]+`
  rather than `packages/.*/src/` so a nested `packages/x/node_modules/y/src/`
  cannot match.
- **`apps/<name>/src/`** — the same for deployables.
- **`apps/<name>/app/(server|edit)/`** — the framework-routed tree, narrowed to
  the two directories that actually hold behaviour. Route files, layouts and
  page components live in the same tree and are covered by the browser tier,
  not the unit tier; a guard that demanded a Vitest change for a JSX tweak
  would be demanding the wrong test.
- **`scripts/docs-conformance/`** — the docs gate's own validators. They are
  `.mjs`, they run under `node --test`, and they are production code for the
  gate. A repo that guards its product but not its guards has a hole exactly
  where it matters most.

**The rule this regex must obey:** keep it in step with the `include` globs in
`vitest.config.ts`. Nothing ties them together mechanically — a tree added to
the runner's coverage but not added here is silently un-guarded, which is the
one failure mode that produces a green push over untested code. Put a comment
in `vitest.config.ts` pointing back at `scripts/guards.config.sh`, in both
directions.

**What is deliberately NOT in it:**

- `*.sh` — the guard scripts themselves are shell. Widening the regex to
  `scripts/*.sh` would also capture every other shell script in the repo, most
  of which have no test tier. That is a rule expansion with its own blast
  radius, not a gap to patch in passing. What covers the guards is their own
  test suite (`tests/tdd-pairing-guard*.test.sh`), which is the honest answer.
- `*.tsx` under page/route trees — see above.
- Renames. The guard already passes `--diff-filter=ACM`, so a pure move never
  demands a test change.

### `GUARD_SOURCE_EXCLUDE_RE` — the holes you punch on purpose

```sh
GUARD_SOURCE_EXCLUDE_RE='\.d\.ts$|\.min\.js$|(^|/)index\.ts$|^scripts/docs-conformance/config\.mjs$'
```

Note what is *absent*: `*.test.ts`. The guard mechanism already subtracts
`GUARD_TEST_RE` from the source list before it asks the pairing question, so
listing test files here would be a second, drifting copy of that rule. Three
categories remain, and each one is a policy claim worth reviewing:

| Exclusion | Why it is not source |
| --- | --- |
| `*.d.ts`, `*.min.js` | declarations and build output — no behaviour to test |
| `index.ts` barrels | pure re-exports. A barrel that gains a line has moved no behaviour — and mutation testing agrees, which is why the Stryker config below excludes them as well |
| `docs-conformance/config.mjs` | reviewable policy **data**, not mechanism. Editing a deny-list entry is a human decision a reviewer reads, not new behaviour a test can pin |

That last one is the interesting one. Config-as-data files are the standard
exception: if a file is *read* rather than *executed*, its change is a decision
and not a behaviour. Add yours here, one at a time, with the reason — an
exclusion list that grows without reasons is how a guard is switched off in
instalments.

### `GUARD_TEST_RE` — what counts as the paired change

```sh
GUARD_TEST_RE='\.(test|spec)\.(ts|tsx|mjs)$|\.feature$'
```

Narrower than the kit default, which spans every ecosystem's convention. Here
the repo has exactly two conventions — the Vitest suffix and Gherkin — so the
pattern says exactly that.

**Direction of error matters.** A *too broad* test pattern makes the guard more
forgiving and never more aggressive; a too narrow one blocks honest pushes and
gets the guard deleted. So when you are unsure, err broad, and narrow it later
once you know what your repo actually names things.

### `BEHAVIOR_DELTA_SURFACES` — where behaviour is externalized

`scripts/behavior-delta.sh` lists (never judges) the branch's changes to your
*contract artifacts*: the places where behaviour becomes machine-visible, and
therefore the places a reviewer must be handed rather than expected to notice.
The same set drives the per-commit `refactor:`-that-is-not check.

The kit ships two surfaces that hold for any project built on it (the
constitution/skills/hooks/guards, and the ADRs). This adapter adds six, and each
is a pattern plus the question it exists to force:

```sh
BEHAVIOR_DELTA_SURFACES='Agent & process surfaces (the constitution, skills, hooks, guards)|^AGENTS\.md$|/AGENTS\.md$|^CLAUDE\.md$|^GEMINI\.md$|^constitution/|^\.claude/|^\.githooks/|^scripts/
Architecture decisions|^docs/adr/
API surface (OpenAPI / GraphQL / protobuf)|^docs/api/openapi\.yaml$|\.graphql$|\.proto$
Persistence (schema + migrations)|^packages/db/|^prisma/schema\.prisma$|^migrations/|^docs/db-design\.md$
Configuration (the env contract)|^packages/env/|\.env\.example$
HTTP posture (headers, CSP, CORS)|^packages/headers/|^apps/[^/]+/middleware\.ts$
Error semantics (the shared error model)|^packages/http/
Agent-facing prompt surfaces|^apps/mcp/(src/(instructions|prompts|tools)|skill/)'
```

Surface by surface:

- **API surface** — `docs/api/openapi.yaml`, `*.graphql`, `*.proto`. The
  canonical case: the document a consumer codes against. A change here is a
  change to someone else's build, whatever the diff looks like internally. Note
  it is the *spec* file, not the handler — the handler is ordinary source that
  the pairing guard already covers.
- **Persistence** — the schema and the migration directory. A migration is
  behaviour that runs exactly once, in production, and cannot be reverted by
  reverting the commit. It belongs on a human's list even when the PR is
  "small". Both the ORM schema and the raw `migrations/` tree are listed
  because projects acquire the second one the moment the first cannot express
  something.
- **Configuration (the env contract)** — the env-var schema package, plus
  `.env.example`. A new *required* variable is a deployment change disguised as
  a one-line diff: the code compiles, the tests pass with the value present in
  CI, and the deploy fails. Listing the schema surfaces that at review time.
- **HTTP posture** — the headers/CSP package and any framework middleware. A
  CSP directive or a `SameSite` flag is security behaviour with no test that
  naturally fails when it is wrong, which is precisely the profile of a change
  that needs a human confirming it (shared invariant §5).
- **Error semantics** — the shared error/problem-details module. What status
  code and what body shape a failure produces is API contract, even though it
  lives in a library. Clients branch on it.
- **Agent-facing prompt surfaces** — tool descriptions, server instructions,
  packaged skills. If your product's surface *is* prompt text, then editing that
  text is editing behaviour, and the compiler will never say so. This is also
  the surface the eval tier in section 3 exists to measure.

**How to choose your own.** Ask one question per candidate path: *if this file
changes and nobody looks, who finds out and how?* If the answer is "a consumer,
in production" — it is a contract artifact. If it is "the type-checker, in
twelve seconds" — it is not.

Keep the list *short*. `behavior-delta.sh` is read by a human on every review;
a surface list that matches half the repo produces a report nobody reads, which
is worse than no report because it looks like coverage.

---

## 2. Mutation testing — a differential diagnostic, never a gate

Files: [`mutation/`](mutation/) · workflow:
[`workflows/mutation-delta.yml`](workflows/mutation-delta.yml)

The TDD pairing guard proves a test *changed*. It cannot prove the test is
load-bearing — a test that asserts nothing pairs just as well as one that
asserts everything. Mutation testing is the answer to that second question, and
this adapter wires it in the only shape that survives contact with a real
project.

### Three design calls, and the reasoning for each

**It is differential.** A full mutation run over one small pure package is
~1050 mutants and ~50 s on a warm laptop; a shared CI runner is slower, and the
number grows with the package. That is the right shape for an occasional
calibration run and the wrong shape for a review, which cares about one branch.
`mutation-delta.sh` narrows Stryker's `--mutate` to the files the branch
actually changed, so the cost tracks the diff rather than the repo.

**It is on demand.** The workflow triggers on the `mutation-check` **label**,
not on push. A per-push mutation run buys a slowly-moving signal at a
constantly-paid cost — and, worse, turns the score into a number people defend
instead of a diagnostic they read.

**It is never a gate.** `thresholds.break: null` in the Stryker config, `exit 0`
whatever the score in the script, and absent from your required status checks.
A surviving mutant is a question for a human ("is this behaviour unenforced, or
is the mutant equivalent?"), and there are two legitimate answers: strengthen
the test, or state why the mutant cannot be killed. A gate admits only the
first, which is how "make the test ask for less" becomes the path of least
resistance.

### Scope: start with one pure package

`mutation.config.sh.example` points at a single package, and this adapter says
so loudly. Pick the one with **no I/O and no environment** — a domain or pure
logic package — because there a mutant costs a function call rather than a
container. Widening the scope is a follow-up with its own cost calibration, not
a default.

### What the report says

`mutation-delta-report.mjs` turns Stryker's JSON into the two things a reviewer
actually asks:

```
## Score
  87.50% total · 91.30% covered — 21 killed, 0 timeout, 2 survived,
  1 no-coverage (24 scored across 3 file(s))

## Survivors (3)
  Survived    src/policy.ts:44:11   ConditionalExpression → false
  Survived    src/policy.ts:61:5    EqualityOperator → >=
  NoCoverage  src/window.ts:12:3    ArithmeticOperator → -
```

Killed mutants are a number and never a list: they are the part that needs no
attention. Survivors carry `file:line:column` so they can be opened and read,
and `Survived` and `NoCoverage` share the list because for a reviewer they are
the same finding — *this behaviour is unenforced*.

The formatter is a separate file from the runner on purpose: it is pure (a JSON
file in, text out — no git, no Stryker), which is what lets it be tested
without a mutation run.

---

## 3. The eval tier — tests for prompts

Files: [`evals/`](evals/README.md) · workflow:
[`workflows/prompt-evals.yml`](workflows/prompt-evals.yml)

**This one is an EXAMPLE, not a drop-in, and more so than anything else here.**
The layout, the grading design and the CI classification transfer. The cases do
not: they are assertions about one product's tool descriptions, and they are
meaningless against yours.

Wire this tier only if your repo has an **agent-facing prompt surface** — an MCP
server's tool descriptions, a system-instructions string, a packaged skill.
Those are shipped product text where a one-word edit moves agent behaviour, and
no compiler, linter or unit test will ever notice. If you have no such surface,
skip this section entirely; you are not missing a gate.

What is worth stealing:

- **The two-tier split.** A keyless *smoke* tier that runs in your ordinary test
  suite and proves the harness is well-formed (the config parses, every
  `file://` resolves, every case carries a reference solution, the generated
  fixtures still match the live source), and a *paid* tier that measures model
  behaviour. Faking behavioural coverage in the keyless tier would be exactly
  the failure mode mutation testing exists to detect.
- **Code-graded, not LLM-judged.** "Which tool fired, with what argument shape"
  is a decidable question; deciding it with a second model buys noise and a
  bill. Reserve a rubric judge for the one dimension that genuinely cannot be
  string-matched, and then constrain it: one dimension, an explicit threshold,
  an "Unknown" escape hatch, and a *different model* from the generator.
- **Partial credit with a per-case bar.** 0.9 ("right tool, missing one
  argument") and 0.4 ("never called it") are different failures. A per-case
  `min_score` — defaulting to a strict 1.0, lowered only in the case data where
  a reviewer sees it in the diff — is what makes partial credit change outcomes
  rather than merely decorate reasons.
- **Negative cases, at a proportional floor.** A suite of positives alone
  measures only eagerness, which is the failure mode a sharpened tool
  description most easily creates. Hold each polarity to a share of the suite
  rather than an absolute count.
- **Cases harvested from real failures, never invented.** A case written at a
  desk measures your imagination; a case harvested from a failure measures the
  product.
- **The three-outcome CI job.** See below — it is the part most people get
  wrong.

### The three outcomes, and why two of them are green

A job that spends money per run has three states, not two, and a workflow that
cannot tell them apart trains everyone to ignore it:

| Situation | The job does |
| --- | --- |
| no provider key readable | runs the keyless half, emits a notice, **exits 0** |
| key present but unusable — every call errored, **zero** assertions executed | emits a notice naming the funding issue, **exits 0** |
| key present and usable | assertion failures are real and the job goes **red** |

The middle row is the one that matters. An unfunded or rate-limited key turns
every run permanently red for a reason that has nothing to do with the diff, and
a permanently red advisory job is one people learn to scroll past — taking the
real failures with it. It is classified by *reading the result file* (zero
successes, zero failures, non-zero errors) rather than guessing from the exit
code, so it cannot swallow a genuine failure; a *partial* outage stays red,
because the suite measured something and its verdict counts.

### Cost control

Path-scope the trigger to the files the suite can actually observe — and be
strict about that. Paying for a run on a file no case loads is pure cost with no
signal. Cache provider responses (they are pinned by prompt + model, so a PR
that does not touch the surface re-runs mostly from cache, and a PR that *does*
re-bills, which is exactly right). Run `k` repeats and fail if any trial fails —
`pass^k` — because a prompt that works two times in three is a prompt that does
not work.

---

## Verification status of the files in this directory

This adapter ships inside a kit that has **no Node project**, so nothing here
can be executed at the point it is written. What has been checked is stated
plainly, per file, in [`INSTALL.md`](INSTALL.md#what-is-verified-and-what-is-not).
Read that before assuming a green kit CI means these scripts run.
