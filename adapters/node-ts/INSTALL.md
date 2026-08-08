# Installing the `node-ts` adapter

Three independent pieces. **Install only the ones you want** — nothing here
depends on anything else here, and the guards piece is by far the most valuable
per minute spent.

| Piece | Install if | Cost to you |
| --- | --- | --- |
| 1. Guards config | your layout is a pnpm/TS workspace with Vitest | one file, five minutes |
| 2. Mutation delta | you have a pure package worth calibrating | three scripts, a config, a workflow, a label |
| 3. Prompt evals | you ship an agent-facing prompt surface | a suite you must finish yourself, and a provider bill |

Read [`README.md`](README.md) first. Every command below assumes you are at the
repo root.

---

## 1. The guards config

```sh
cp adapters/node-ts/guards.config.sh.example scripts/guards.config.sh
```

**Then edit, in this order:**

1. **`GUARD_SOURCE_RE`** — replace `packages/`, `apps/` and the routed-tree
   alternative with your actual trees. Verify by eye against
   `vitest.config.ts`'s `include` globs: anything the runner covers and this
   pattern misses is silently un-guarded.
2. **`GUARD_SOURCE_EXCLUDE_RE`** — drop the `docs-conformance/config.mjs` entry
   if you deleted that tree; add your own config-as-data files one at a time,
   each with the reason.
3. **`GUARD_TEST_RE`** — if your repo uses `__tests__/` directories or a
   different suffix, widen it. Err broad: a too-broad test pattern makes the
   guard more forgiving, never more aggressive.
4. **`BEHAVIOR_DELTA_SURFACES`** — delete every surface you do not have. Six of
   the eight lines are this adapter's example paths and will match nothing in
   your repo; a surface that never matches is a line a reviewer learns to skip.

**Verify it, in this order:**

```sh
# a) The guard is now ACTIVE — it should stop warning that it is inactive.
sh scripts/tdd-pairing-guard.sh HEAD~1 HEAD

# b) It sees your source. Touch a real source file and check it is listed.
sh scripts/tdd-pairing-guard.sh origin/main HEAD

# c) The contract surfaces resolve to real paths.
sh scripts/behavior-delta.sh origin/main
```

If (a) still prints `TDD pairing guard is INACTIVE`, `GUARD_SOURCE_RE` did not
get set — check you copied over `scripts/guards.config.sh` and not beside it.

**Add the reverse pointer.** Put a comment in `vitest.config.ts` next to the
`include` globs naming `scripts/guards.config.sh`, and one in
`guards.config.sh` naming `vitest.config.ts` (the example already carries the
second). Nothing ties the two together mechanically, so the only defence is that
both files tell you about the other.

---

## 2. The mutation delta

```sh
# The scripts (mechanism) and the config (policy).
cp adapters/node-ts/mutation/mutation-delta.sh          scripts/
cp adapters/node-ts/mutation/mutation-delta-report.mjs  scripts/
cp adapters/node-ts/mutation/mutation-delta-ci.sh       scripts/
cp adapters/node-ts/mutation/mutation.config.sh.example scripts/mutation.config.sh
chmod +x scripts/mutation-delta.sh scripts/mutation-delta-ci.sh

# The Stryker config goes INSIDE the package it mutates.
cp adapters/node-ts/mutation/stryker.config.mjs.example \
   packages/<your-pure-package>/stryker.config.mjs

# The workflow.
cp adapters/node-ts/workflows/mutation-delta.yml .github/workflows/
```

**Then, and none of these are optional:**

1. **Install the runner** in the target package, not the root:
   `pnpm --filter <pkg> add -D @stryker-mutator/core @stryker-mutator/vitest-runner`
2. **Edit `scripts/mutation.config.sh`** — `MUTATION_PKG_DIR`,
   `MUTATION_PKG_NAME` (the `package.json` name, which is not always the
   directory name), and `MUTATION_EXEC` if you are not on pnpm.
3. **Edit the workflow** — pin the pnpm version to your `packageManager` field,
   set the Node version, and confirm the label name matches the script's.
4. **Create the label**, or the escape hatch is only reachable by inventing it
   while applying it:
   ```sh
   gh label create mutation-check -d "Run the differential mutation diagnostic on this PR"
   ```
5. **Do NOT add it to required status checks.** It is a diagnostic. Adding it
   converts a signal people read into a threshold people defend, and the next
   person to meet a surviving mutant will weaken a test to clear it.

**Verify it:**

```sh
# Scope only — no run, no cost. This is the fast check that the config is right.
sh scripts/mutation-delta.sh --list

# The real thing, against your branch.
sh scripts/mutation-delta.sh origin/main
```

`--list` printing `No mutable source changed on this branch` on a branch that
*did* change that package means `MUTATION_PKG_DIR` or `MUTATION_SRC_RE` is
wrong — check them before concluding the diagnostic has nothing to say.

**Time-box the first real run.** If it takes more than a couple of minutes over
a handful of changed files, you picked the wrong package: find one with no I/O
and no environment, and calibrate there first.

---

## 3. The prompt evals

**Do not copy this one blind.** It is an example, and the cases in it are
assertions about a fictional surface.

```sh
cp -R adapters/node-ts/evals tests/evals
cp adapters/node-ts/workflows/prompt-evals.yml .github/workflows/
```

**Then — and the suite does not run until all of this is done:**

1. **Delete `tests/evals/golden-set/example.yaml`.** Both cases in it are
   illustrations. Keeping them means your first green run measured a fiction.
2. **Write the fixture generator**: a script that imports your instructions
   string and tool registrations and writes `tests/evals/fixtures/instructions.txt`
   and `tests/evals/fixtures/tools.json`. Check the output in.
3. **Write the keyless smoke tier** in your own runner — at minimum: the config
   parses, every `file://` it names exists, every case has a reference solution,
   and **the checked-in fixtures still match what the generator produces now**.
   That last one is the load-bearing assertion; without it a prompt-surface edit
   can ship while the eval measures the previous wording.
4. **Add the scripts** your workflow calls: `evals`, `evals:validate`,
   `evals:sync` in `package.json`.
5. **Edit the provider block** in `promptfooconfig.yaml` — your provider, your
   model, your key's env var name.
6. **Edit the workflow's `paths:` list** to the files your suite can actually
   observe, and write down why the excluded ones are excluded.
7. **Provision the key as a repository secret**, and expect outcome 2 (key
   present, unusable) until it is funded. The workflow is built to stay green
   through that; verify it does before you rely on it.
8. **Write the first case from a real failure**, not from imagination.

---

## What is verified, and what is not

This adapter was written inside the kit repository, which has **no Node project,
no package manager, no Stryker and no promptfoo**. Nothing here could be
executed at the point it was written, and this section says so plainly rather
than letting a green kit CI be read as more than it is.

**Verified** (and re-verified on every kit CI run by `tests/adapters-demo.sh`):

| Check | Over |
| --- | --- |
| `sh -n` — POSIX shell parses | `guards.config.sh.example`, `mutation.config.sh.example`, `mutation-delta.sh`, `mutation-delta-ci.sh` |
| `node --check` — the module parses | `mutation-delta-report.mjs`, `evals/prompts/client.js`, `evals/asserts/tool-selection.js` |
| the config files actually **set** the variables the guards read, with non-empty values | `guards.config.sh.example`, `mutation.config.sh.example` |
| the regexes are valid ERE that `grep -E` accepts | `GUARD_SOURCE_RE`, `GUARD_TEST_RE`, `GUARD_SOURCE_EXCLUDE_RE`, `MUTATION_SRC_RE` |
| `adapters/` survives `bootstrap.sh` intact, and nothing from it is installed as a workflow | the whole tree |
| the docs gate stays green with `adapters/` present | the whole tree |

**NOT verified — nobody has run these end to end from this repo:**

- **No Stryker run.** `mutation-delta.sh` has never invoked the real binary
  here; the `--mutate` narrowing, the `--allowEmpty` behaviour, and the JSON
  report path are ported from a working setup but not re-proved. The first real
  `--list` and then a real run are step one of installing it.
- **No promptfoo run, and no `promptfoo validate` either.** The eval config is
  deliberately incomplete (the generated fixtures it names are absent by
  design), so it cannot even be validated from here.
- **No workflow run.** Both YAML files are syntactically plausible and modelled
  on running ones, but GitHub has never parsed them: `adapters/` is not
  `.github/workflows/`, so nothing here is on any CI path. Expect to iterate on
  the first PR after you copy one in.
- **No report formatter output check.** `mutation-delta-report.mjs` parses, but
  no real Stryker JSON has been fed through it here. Write a fixture test for it
  when you install it — it is pure (file in, text out), which is exactly what
  makes that cheap.

---

## Why `bootstrap.sh` does not touch this directory

`adapters/` is neither copied out of nor deleted by `bootstrap.sh`, so it
arrives in your project exactly as it sits in the kit. That is a decision, not
an oversight, and the alternatives were both worse:

- **Copy it into place at bootstrap** — bootstrap runs on an empty project, so
  it cannot know your stack. Installing a Node adapter's guard config into a Go
  repo would be a guess stamped into a file the docs gate then enforces forever.
  The kit's whole position on the guards is that it ships mechanism and asks you
  for policy; an adapter installed automatically is policy nobody chose.
- **Delete it at bootstrap** (like `tests/` and the kit's own CI) — those are
  kit-*authoring* artifacts that mean nothing downstream. An adapter is the
  opposite: it is reference material a project wants **later**, on the day it
  turns a guard on, which is typically weeks after bootstrap and long after the
  kit repo has been forgotten. Deleting it would move the only worked example
  out of reach at the exact moment it becomes useful.

So it arrives **dormant**: no file in it is on an execution path, no workflow
lives in it (GitHub reads only `.github/workflows/`), no guard resolves its
config from it, and no gate scans it for references. It costs nothing until you
copy something out of it. And if your stack is nowhere near it, `rm -rf adapters`
is a correct and encouraged answer — a Node wiring sitting in a Go repo is a
stale standing instruction waiting to mislead the next agent session.
