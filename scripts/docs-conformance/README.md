# docs-conformance

A dependency-free harness that confirms the **agent manual layer** still
describes reality: every command it names resolves, every path it names exists,
every article is reachable from the root, and the shared article stays free of
local vocabulary.

It is the enforcement of shared invariant §8 — *a rule written in a document
that nothing checks decays into a lie, and stale standing instructions are worse
than absent ones, because every agent session loads them.*

## Run

```sh
node scripts/docs-conformance/index.mjs .          # check this repo; exit 1 on any violation
node --test scripts/docs-conformance/test/*.test.mjs   # the harness's own fixture tests
```

You normally do not run it directly: `scripts/check.sh` is the gate, and it
delegates here whenever `node` is on PATH. See "Two engines" below.

## How it's built

- **Plain ESM** (`.mjs`), Node built-ins only — no test runner, no lint deps, no
  `package.json`. The kit adds no dependency to a project that has not chosen a
  toolchain.
- Each **validator** in `validators/` exports `{ id, run(ctx) }` and returns a
  list of `{ validator, file, rule, message, hint }` violations. It owns no
  policy — all rules live in `config.mjs`, so the rules are reviewable in a PR.
- `context.mjs` builds the read-only `ctx` (rooted at a repo path, so tests point
  it at fixture trees); `runner.mjs` aggregates; `index.mjs` is the CLI.
- Tests in `test/` are fixture-driven: a clean fixture passes, targeted dirty
  fixtures each fail with the expected rule.

**Shared layer vs. yours.** `index.mjs`, `context.mjs`, `runner.mjs` and
`validators/` are shared layer (listed in `VERSION`) — copied verbatim, updated
by diffing against a new kit release. `config.mjs`, `local-vocabulary.mjs` and
`test/` are **yours**: the engine is common, the rules are not.

## Two engines, one gate

`scripts/check.sh` always runs two checks in POSIX sh (no unstamped placeholder
survived bootstrap; every file `VERSION` lists still exists) and then delegates
the *reference* checks:

| | Engine | Covers |
| --- | --- | --- |
| node on PATH | this harness | layered manuals, slash-command resolution, article reachability, package-relative paths, portability deny-list |
| no node | POSIX fallback in `check.sh` | repo paths in code spans of the root manual and the articles — and it prints a NOTICE listing what it is *not* checking |

Set `DOCS_CHECK_NO_NODE=1` to force the fallback. The two share one policy in
two places (`config.mjs`'s `pathRoots` and `check.sh`'s `path_roots`); that
duplication is the price of a gate that works before a project has a runtime,
and it is called out in both files.

## Validators

| id | checks |
| --- | --- |
| `claude-md-refs` | every layer of the agent manual (root `AGENTS.md`, the articles under `constitutionDir`, and any nested package manuals) references only commands and paths that exist; each article is reachable from the root; the tool shims beside the manual import it and hold nothing else; the shared article stays free of product / vendor / path / command names |

Rules it reports: `skill-missing`, `path-missing`, `article-unreferenced`,
`shim-invalid`, `portability-leak` (plus `validator-crash` from the runner).

### The shims

`claudeMdRefs.rootManual` is `AGENTS.md`, and `claudeMdRefs.shims` lists the
entry points other agent tools look for (`CLAUDE.md`, `GEMINI.md`). Each must
contain exactly one import line — `@AGENTS.md` — plus at most one HTML-comment
line saying that is all it is; blank lines are ignored and everything else is
`shim-invalid`. The grammar is deliberately unforgiving: "nothing but an import"
is only checkable if there is no room to argue about what else counts as
nothing, and a tool-specific file that can hold a rule becomes a second manual
nobody diffs.

Like every other manual check, it is evaluated **only where the root manual
exists**. The kit's own tree ships a `*.template` and no stamped files, so it
has no shims to be wrong about, and neither does a fixture that models the
article layer alone.

## `claude-md-refs` path resolution

A backticked token whose **first segment** is one of `config.claudeMdRefs.pathRoots`
resolves **repo-relative**, from whichever manual names it — so `tests/` in
`apps/api/AGENTS.md` is the repo's test tree. Any other path-shaped token inside
a **nested** manual resolves against that manual's own directory (`src/tools.ts`
→ `apps/api/src/tools.ts`). Repo-level manuals (root + articles) never resolve
package-relative. To keep prose out of the check, a package-relative token must
contain a `/` and either end in `/` or have a dotted final segment, so bare
filenames (`server.test.ts`), globs (`*.test.ts`) and identifiers are left alone.

The validator matches **whole code spans**. Write a path in a span of its own —
`` `scripts/check.sh` `` — not embedded in a longer one, or it goes unchecked.

The portability deny-list (`config.claudeMdRefs.portability`) carries one entry
per category of local vocabulary with the reason it may not appear in the shared
article; `prose`-scoped entries are matched against the whole file,
`spans`-scoped entries only against markdown code spans. Your project's own
names come from `local-vocabulary.mjs`, which `bootstrap.sh` stamps with your
project name.

## Deferred (noted for a follow-up)

Full markdown lint and link-integrity checking are out of scope. The docs
skeleton validators — decision-record index sync, MADR shape, glossary aliases —
arrive with K4 (#6) and plug into `runner.mjs`'s list without touching the
engine.

## Provenance

Extracted from the repo where this framework was built and exercised; the
product-specific validators (spec/BDD/OpenAPI/domain-event conformance) stayed
behind, and every rule that survived was reduced to configuration.
