# agentic-sdlc

A language-agnostic, agent-first SDLC framework — extracted from a production
project where it was built, reviewed, and exercised by the very chain it defines.

Spec → tracer-bullet tickets → test-first implementation → two-axis review
(standards findings to agents, behavior findings to humans) → human merge gate.
Constitution-layered agent instructions, CI-verified process docs, guards that
block what prose used to merely request.

**Use it as a GitHub template**, run one bootstrap script, and your new repo has
an agent operating manual, the portable rulebook underneath it, and a gate that
fails when the two stop describing reality.

## Quickstart

```sh
# 1. Create your repo from this template ("Use this template" on GitHub, or:)
gh repo create my-project --template agranado2k/agentic-sdlc --private --clone
cd my-project

# 2. Bootstrap. Runs once, then deletes itself. Commits nothing.
sh bootstrap.sh "My Project" "One line about what it does."

# 3. Check the gate is green, then make the first commit yours.
sh scripts/check.sh
git add -A && git commit -m "chore: bootstrap from agentic-sdlc"

# 4. Turn the TDD pairing guard on. It ships INACTIVE — see "The guards".
$EDITOR scripts/guards.config.sh   # set GUARD_SOURCE_RE
```

`bootstrap.sh` stamps `constitution/AGENTS.md.template` into a root `AGENTS.md`,
writes the two one-line shims (`CLAUDE.md`, `GEMINI.md`) that point at it, stamps
the documentation set out of `templates/docs/`, wires the pre-push gate
with native `git config core.hooksPath .githooks` (no hook manager, no
dependency), prints your next steps, and removes itself. It refuses to run a
second time rather than overwriting a manual you have since edited.

## What you get

| Path | What it is |
| --- | --- |
| `constitution/shared-invariants.md` | The portable rulebook — eleven invariants that hold regardless of stack, domain, or vendor. **Shared layer:** copied verbatim, not edited locally. |
| `constitution/AGENTS.md.template` | The root agent manual: hard rules, agent trust boundary, article-layer pointers, quick-reference map. Carries double-brace marks that bootstrap stamps. Becomes `AGENTS.md`; then it is yours. |
| `LICENSE` | MIT. The skills adapted from mattpocock/skills carry their upstream notice separately, in `.claude/skills/LICENSE-mattpocock-skills.md`. |
| `constitution/local-engineering.md.template` | The stack article — style, architecture, test tiers, "what this repo is NOT". Marks and inline guidance; you fill it in and drop the suffix. |
| `constitution/local-workflow.md.template` | The process article — commits, merges, the docs-trigger matrix, review, decision records, the log. Same deal. |
| `.claude/skills/` | The twelve skills — the lifecycle made runnable. Copied as-is, never stamped: they must read correctly in any project. **Yours** on arrival. |
| `scripts/check.sh` | The docs gate. POSIX sh; delegates the reference checks to the harness when node is available (see below). |
| `scripts/docs-conformance/` | The real validator: layered manuals, slash-command resolution, article reachability, portability deny-list. Dependency-free ESM, with its own fixture tests. |
| `scripts/docs-conformance/config.mjs` | Everything the gate enforces, as data. **Yours** — the engine is shared, the rules are not. |
| `scripts/guards.config.sh` | **Yours.** The one place the guards learn your repo's shape — source globs, test globs, contract artifacts. |
| `scripts/tdd-pairing-guard.sh` | The TDD pairing rule: source changes must carry test changes. One implementation, called by the hook and by CI. |
| `scripts/tdd-pairing-guard-ci.sh` | The CI caller of that rule — merge-base range, `tdd-exempt` label hatch. |
| `scripts/behavior-delta.sh` | Inventories the branch's deltas in your contract artifacts, plus a per-commit `refactor:`-that-is-not check. |
| `scripts/worktree-cleanup.sh` | Prunes merged worktrees and fast-forwards the root checkout. Driven by the `/worktree-cleanup` skill. **Yours** — not shared layer. |
| `.githooks/pre-push` | Runs the docs gate and the pairing guard before every push, each with its own loud, logged bypass. |
| `templates/workflows/` | CI workflow templates, copied into `.github/workflows/` by bootstrap. |
| `templates/docs/` | The documentation skeletons. Stamped into `README.md`, `docs/diary.md`, `docs/domain-glossary.md`, `docs/adr/INDEX.md`, `docs/adr/NNNN-template.md` and `.github/PULL_REQUEST_TEMPLATE.md`, then removed. |
| `adapters/` | Worked reference wirings, one directory per stack — **copy only if your stack matches**. Not shared layer, not stamped, not installed: it arrives in your project intact and dormant. See below. |
| `UPDATING.md` | The shared-layer update recipe — how to diff your copy against a newer kit release and adopt it. **Shared layer.** |
| `tests/docs-demo.sh` | K4's acceptance test — the personalized docs set, and the update recipe run end to end (removed by bootstrap). |
| `VERSION` | The shared-layer manifest: which files are shared, at which version. |
| `tests/` | The kit's own acceptance tests and CI (removed from your project by bootstrap). |

### One manual, three entry points

The rules live in **`AGENTS.md`** — the filename the agent-tool ecosystem has
converged on, and the one this kit treats as canonical. Beside it bootstrap
writes two **shims**, `CLAUDE.md` and `GEMINI.md`, each holding one import line:

```markdown
<!-- Shim: the agent manual is AGENTS.md. Edit that file, not this one. -->
@AGENTS.md
```

That is the whole file, and the docs gate keeps it that way: `shim-invalid`
fires if a shim grows a second instruction, imports something else, or goes
missing. The rule exists because of the failure it prevents — the moment a
tool-specific file *can* hold a rule, somebody adds one there, and the repo has
two manuals whose difference nobody can see. A shim with no room for content
cannot become a rival manual.

The list is policy, not a constant: `claudeMdRefs.shims` in
`scripts/docs-conformance/config.mjs` names the entry points, and a project that
does not want one deletes it from that list rather than from the validator. The
check is evaluated only where the manual exists, so the kit's own tree — which
ships an `AGENTS.md.template` and no stamped files — stays silent.

**The honest limit.** This buys you the *rules* in every agent tool, not the
*commands*. The skills stay in `.claude/skills/` in one tool's slash-command
format; a tool that does not read that directory gets the practice as prose from
the manual and the articles, with no `/`-command to invoke it. Each `SKILL.md` is
plain markdown, so pointing another tool at one by path works today — but porting
the twelve to a second command format is explicitly out of scope here.

### The documentation set

Bootstrap leaves a project with the four documents an agent-run project needs on
day one, personalized with your project name and the bootstrap date:

- **`docs/diary.md`** — the development diary. A **Current state** block that is
  edited in place (the re-orientation summary an agent reads first), open
  questions, memory pointers, an explicit update protocol, and append-only dated
  entries below.
- **`docs/adr/INDEX.md`** + **`docs/adr/NNNN-template.md`** — MADR decision
  records. The index says what is currently binding; the template is copied per
  decision and stays in your repo.
- **`docs/domain-glossary.md`** — the ubiquitous language, plus the half people
  forget: the words the project deliberately does *not* use.
- **`.github/PULL_REQUEST_TEMPLATE.md`** — the PR checklist, with a separate
  section for the behavior findings a human must confirm (shared invariant §5).

All four are **yours** the moment they land. They are stamped from templates, not
copied verbatim, and nothing updates them afterwards.

### The skills

`.claude/skills/` holds twelve skills — the chain at the top of this README, made
runnable:

`/grill-me` → `/to-prd` → `/to-tickets` → `/implement` (driving `/tdd`) →
`/review-pr` → `/pr-iterate` → `/merge-train` → `/worktree-cleanup`, plus
`/grill-with-docs`, `/prototype` and `/diagnose` off to the side.

They are **copied as-is, never stamped**. That is a stronger constraint than the
templates are under: a template may carry a mark because something fills it in,
while a skill has to read correctly in a project nobody personalized. So where a
skill needs a project specific, it points at the artifact this kit already
establishes — `constitution/local-engineering.md` for the test tiers,
`scripts/guards.config.sh` for what counts as source and which artifacts are
contracts, `docs/adr/` for the binding decisions, `docs/domain-glossary.md` for
the names.

Two consequences worth stating plainly:

- **The root manual's quick-reference is the index, and the gate enforces it.**
  Every `/command` in `AGENTS.md` must resolve to `.claude/skills/<name>/SKILL.md`.
  Delete a skill you do not run and the gate makes you delete its row.
- **`/review-pr` keeps both axes** (shared invariant §5). Axis 1's six standards
  sub-agents feed a severity report an agent may act on; Axis 2's seventh
  sub-agent runs in a fresh context and emits a confirm-list only a human may
  resolve. The mutation-delta step it can cite is **conditional** — mutation
  testing is stack-specific, so the skill says to check `adapters/` and to skip
  the block, loudly, when nothing is wired.

Five of the twelve are adapted from [mattpocock/skills](https://github.com/mattpocock/skills)
under MIT; `.claude/skills/LICENSE-mattpocock-skills.md` records which, what
changed, and reproduces the licence, and each adapted skill carries the same note
at its own foot so provenance survives being read out of context.

## The shared layer, and why it has a version

Most of what bootstrap leaves behind is **yours** the moment it lands — your
`AGENTS.md`, your local rules, your docs. A small part is not: the files listed
under `files:` in `VERSION` are the **shared layer**, copied verbatim from the
kit and deliberately not edited downstream. They carry no product name, no
command, and no vendor, which is exactly what makes them copyable at all.

`VERSION` pins which release of that layer you took (`shared-layer: 0.3.0`). When
the kit moves, you diff the kit's shared layer against yours and apply what
changed — a manual, reviewable update rather than a dependency bump. That recipe
is `UPDATING.md`: read both manifests, read the upstream delta, measure your own
drift, apply, then **verify the verbatim claim byte-for-byte** before bumping the
marker. It is demonstrated end to end by `sh tests/docs-demo.sh`, whose transcript
is the worked example inside `UPDATING.md` itself.

The gate fails if a shared-layer file goes missing, so the manifest cannot
silently stop describing reality.

A local exception to a shared invariant does not get edited into the shared file.
It goes in a local article, and the shared copy stays byte-identical.

## The gate

Shared invariant §8: a process rule must be executable or CI-verified, because a
rule nothing checks decays into a lie — and a stale standing instruction is worse
than an absent one, since every agent session loads it.

`scripts/check.sh` is that gate. It runs on `git push` via `.githooks/pre-push`,
with `PUSH_WITHOUT_DOCS=1` as a documented, warning-printing escape hatch.

**Three checks always run, in POSIX sh** — they are cheap and exact in a shell:

- an unstamped placeholder survived bootstrap (the manual was never personalized);
- a shared-layer file named in `VERSION` is gone;
- the root `AGENTS.md` does not exist at all.

**The reference checks are delegated**, because they are real parsing work:

| | Engine | Covers |
| --- | --- | --- |
| node on `PATH` | `scripts/docs-conformance/` | every layer of the manual (root, articles, nested package manuals); slash commands must resolve to a skill; repo paths must exist; every article must be reachable from the root; the shared article must stay free of product, vendor, path and command names |
| no node | POSIX fallback inside `scripts/check.sh` | repo paths in code spans of the root manual and the articles — and it prints a NOTICE naming everything it is *not* checking |

That split is the whole language-agnostic claim, kept honest: a project that has
not chosen a toolchain still inherits a working gate on day one, and is told
plainly what it is missing rather than being allowed to believe in coverage it
does not have. `DOCS_CHECK_NO_NODE=1` forces the fallback, which is how the demo
proves both engines — including a portability leak the fallback provably misses.

The harness carries its own fixture tests (`scripts/docs-conformance/test/`),
because a gate whose failure path is untested is a claim, not a check.

Note that the gate scans its own source too, and that files named `*.template`
are exempt because carrying unstamped marks is their job. Everything else is
stamped output and is held to it.

## The guards

The docs gate answers "do the documents still describe reality". The guards
answer two different questions, and they follow the same shape: **a script owns
the rule, a caller resolves the range.** That is what lets one rule run in a
hook, in CI, and in a test without three copies of it drifting apart.

**The TDD pairing guard** (`scripts/tdd-pairing-guard.sh`) fails a range that
touches source files and no test file. `.githooks/pre-push` calls it per pushed
ref; `scripts/tdd-pairing-guard-ci.sh` calls it over a pull request's merge-base
range. The two escape hatches differ on purpose: locally it is
`PUSH_WITHOUT_TESTS=1`, an env var in one person's shell history; in CI it is
the `tdd-exempt` label, an override visible to whoever reviews the PR. So a
local bypass only *defers* the failure.

**`scripts/behavior-delta.sh`** lists — never judges — the branch's changes to
your contract artifacts: the places where behavior is externalized and therefore
machine-visible. It also checks something no branch-level view can see: a commit
whose Conventional Commit type claims structure-only work (`refactor:`,
`style:`) while its own diff edits a contract artifact.

### They start INACTIVE, and that is the design

`scripts/guards.config.sh` is the one place the guards read policy from — and it
ships with **no source globs set**. Until you set `GUARD_SOURCE_RE`, the pairing
guard prints one warning per push and blocks nothing.

That default is deliberate, not an oversight. Bootstrap runs on an empty
project; it cannot know where your source will live, and a guess stamped into a
script becomes a rule nobody chose. A guard that blocked every push in a repo
nobody had configured yet would be deleted on day one — and a deleted guard
checks nothing. So the kit ships the mechanism and asks you for the policy.

Mechanism is shared layer (copied verbatim, listed in `VERSION`); policy is
yours (`scripts/guards.config.sh` is deliberately *not* shared). That split is
what lets a kit update diff cleanly against your copy.

## The adapters, and why they are dormant

The core is stack-free on purpose — that is what makes it copyable — and that
leaves one question unanswered on day one: *what do those settings actually look
like for my stack?* `adapters/` answers it by example, and only by example.

`adapters/node-ts/` is the worked wiring for a pnpm/TypeScript workspace with
Vitest: a filled-in `guards.config.sh` (real source and test globs, six contract
surfaces beyond the kit's two), a differential Stryker mutation diagnostic with
its report formatter and label-triggered workflow, and a promptfoo eval tier for
agent-facing prompt surfaces. Every value in it is a real value from the project
this framework was extracted from, with the reasoning left in.

**`bootstrap.sh` does not touch this tree.** It copies nothing out of it, stamps
nothing in it, and deletes nothing from it — so it arrives in your project
byte-identical and inert. Neither alternative was better: installing an adapter
would be a stack guess stamped into a file the docs gate then enforces, and
deleting one would move the only worked example out of reach at exactly the
moment it becomes useful (the day you turn a guard on, weeks after bootstrap).
Nothing in `adapters/` is on an execution path: no workflow lives there, no
guard resolves its config from there, and no gate reads it. If no adapter
matches your stack, `rm -rf adapters` is the encouraged answer — a Node wiring
sitting in a Go repo is a stale standing instruction waiting to mislead the next
agent session.

`sh tests/adapters-demo.sh` states all of that as checks rather than prose: the
shell and module files parse, the config examples really set what the guards
read, and a bootstrapped consumer still holds the tree byte-for-byte with
nothing installed. What it *cannot* check — no Stryker run, no promptfoo run, no
workflow GitHub has ever parsed — is listed in `adapters/node-ts/INSTALL.md`.

## CI templates, and why they are not workflows here

`templates/workflows/` holds the CI half of each gate. `bootstrap.sh` copies
them into `.github/workflows/` of your project and removes the templates
directory. They are not live in this repo because **a template repository must
not run its consumers' CI against its own tree** — the kit has a
`AGENTS.md.template` rather than an `AGENTS.md`, and no configured source globs,
so both consumer gates are designed to be inert or red here.

Commit linting ships as `commitlint.yml.example` — inert, because GitHub Actions
reads `.yml`. It is the one gate whose reference implementation needs node, and
the kit does not decide that your project uses node. Rename it when you are
ready; the header explains what to do if you are not on node.

The kit's own CI is `.github/workflows/kit-guards.yml`, which runs the guard
test tiers and the end-to-end demo against this tree.

## Status — honest version

The **constitution layer (K1)**, the **skills (K2)**, the **guards (K3)**, the
**documentation set + update recipe (K4)**, the **Node/TS reference adapter
(K5)** and the **tool-agnostic manual (K7)** are in on top of the walking
skeleton (K0).

- `sh tests/kit-demo.sh` builds a throwaway project from this tree, bootstraps
  it, and proves the gate green — then red once per failure mode it claims: an
  unstamped placeholder, a deleted shared-layer file, a stale path, a dead
  slash command, the project's own name leaking into the shared article, and an
  article the root never points at. Includes a real `git push` the hook blocks,
  the POSIX fallback run, and a focused pass over the skills: every `/command`
  in the bootstrapped manual resolves to a `SKILL.md`, every shipped skill is
  reachable from the manual, no skill carries an unstamped mark — and then the
  same gate goes red when a skill is deleted out from under its row. It also
  proves the three entry points: `AGENTS.md` plus two shims that really are
  shims, then red when a shim grows content, red when a shim is deleted, and
  red when `AGENTS.md` itself is renamed away.
- `sh tests/guards-demo.sh` does the same for the guards: an unconfigured push
  that warns and passes, globs configured, a source-only push the hook really
  blocks (origin does not move), and the paired push that lands.
- `sh tests/docs-demo.sh` proves the bootstrapped docs set is personalized (and
  that the gate catches an unstamped mark inside `docs/`), then runs the whole
  `UPDATING.md` recipe on a fake 0.1.0 consumer updating to 0.3.0 — including a
  local edit to a shared file, moving it out, and the byte-for-byte verbatim
  check afterwards. Its transcript is the worked example inside `UPDATING.md`.
- `sh tests/adapters-demo.sh` covers K5: the adapter files parse, the config
  examples really configure the guards, and a bootstrapped consumer keeps
  `adapters/` byte-identical with nothing installed or activated from it.

- `sh tests/worktree-cleanup.test.sh` covers the one new script that deletes
  things: merged-and-clean is pruned, merged-but-dirty and unmerged are kept,
  `--dry-run` changes nothing, and a typo'd flag exits 2 rather than running.

The kit's own CI runs the harness fixture tests, the portability validator
against `constitution/shared-invariants.md`, the guard test suites, and the
demos on every PR (`kit-ci.yml` + `kit-guards.yml`).

Not here yet, each with its own ticket:

| | Ticket | Brings |
| --- | --- | --- |
| K6 | [#8](https://github.com/agranado2k/agentic-sdlc/issues/8) | Dogfood: a throwaway project end-to-end, and the verbatim claim proved by diff |

The PRD is [#1](https://github.com/agranado2k/agentic-sdlc/issues/1). The kit is
built ticket-by-ticket by its own `/to-tickets` → `/implement` chain.

## Licence

MIT (`LICENSE`) — and additionally, for the five skills adapted from
[mattpocock/skills](https://github.com/mattpocock/skills), the upstream MIT
notice reproduced in `.claude/skills/LICENSE-mattpocock-skills.md`.

## Working on the kit itself

`scripts/check.sh` is written for a *bootstrapped* project, so running it against
this repo fails on purpose: the kit has an `AGENTS.md.template`, not an `AGENTS.md`.
The kit's own gates run the consumer gates inside real throwaway consumers, and
CI runs all of them:

```sh
node --test scripts/docs-conformance/test/*.test.mjs   # the validators' fixture tests
node scripts/docs-conformance/index.mjs .              # portability of THIS repo's shared layer
sh tests/kit-demo.sh                                   # K0+K1: bootstrap + docs gate, end to end
sh tests/guards-demo.sh                                # K3: the guards, end to end
sh tests/docs-demo.sh                                  # K4: the docs set + the UPDATING.md recipe
sh tests/adapters-demo.sh                              # K5: the adapters tree, and that it stays dormant
sh tests/tdd-pairing-guard.test.sh                     # the pairing rule
sh tests/tdd-pairing-guard-ci.test.sh
sh tests/behavior-delta.test.sh
sh tests/worktree-cleanup.test.sh                      # the pruning rule
```

Do not wire `core.hooksPath` in this repo.

`bootstrap.sh` is edited by several kit tickets at once. Each one's changes live
between a `K<n> BEGIN` / `K<n> END` banner — keep yours inside one, and do not
interleave with another ticket's block.

`UPDATING.md` quotes a transcript produced by `tests/docs-demo.sh`. If you change
the recipe or the shared layer, re-run the demo and re-paste it: a worked example
nobody re-runs is exactly the stale standing instruction shared invariant §8 is
about.
