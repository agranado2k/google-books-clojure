# `adapters/` — worked reference wirings, one per stack

Everything else in this kit is deliberately stack-free. The constitution names
no language, the guards read their patterns from a config file, and the docs
gate degrades to POSIX sh when there is no runtime. That is what makes the core
copyable at all — and it is also what leaves a real question unanswered on day
one: **what do those settings actually look like for my stack?**

This directory answers that question by example, and only by example.

## The rule

> **Copy from an adapter only if your stack matches it. Otherwise read it for
> the shape and write your own.**

An adapter is not a plugin, not a dependency, and not a layer the kit loads. It
is a directory of files someone already wired up in anger, with the reasoning
left in, so you can see how the kit's placeholders get filled by a real project
rather than inferring it from a regex example in a comment.

## What is here

| Adapter | For | Wires |
| --- | --- | --- |
| [`node-ts/`](node-ts/README.md) | A pnpm/TypeScript monorepo with Vitest | the TDD pairing guard's globs, `behavior-delta.sh`'s contract surfaces, a differential Stryker mutation diagnostic, and a promptfoo eval tier for agent-facing prompt surfaces |

## Dormant by design — and why this directory is still in your repo

Nothing here is shared layer. `adapters/` appears in no `files:` list in
`VERSION`, so no kit update will ever overwrite what you put here, and
`scripts/check.sh` will never fail because an adapter file went missing.

Nothing here is stamped. `bootstrap.sh` does not read this tree: it does not
copy out of it (only `templates/` is copied), it does not delete it (only the
kit-authoring files listed in `KIT_ONLY` are), and it does not personalize any
file in it. That is deliberate rather than an omission — see
[`node-ts/INSTALL.md`](node-ts/INSTALL.md) for the reasoning in full.

So this directory **arrives in your project intact and inert**, exactly as it
sits in the kit:

- no file here is on any execution path — no workflow lives in `adapters/`
  (GitHub only reads `.github/workflows/`), no guard resolves its config from
  here, no gate scans it for references;
- the one gate that does see these files is the docs gate's
  unstamped-placeholder scan, which every file in the repo is subject to and
  which these files pass;
- it costs you nothing until you copy something out of it.

Three legitimate things to do with it:

```sh
# 1. Use it: follow the INSTALL for the adapter that matches your stack.
$EDITOR adapters/node-ts/INSTALL.md

# 2. Keep it as reference and read it later. This is the default. It is inert.

# 3. Delete it. Nothing depends on it.
rm -rf adapters
```

Option 3 is a perfectly good answer if your stack is nowhere near any adapter
here — and it is a *better* answer than leaving a Node adapter sitting in a Go
repo where the next agent session may read it as a description of this project.
An agent reads what is in the repo; a wiring for a stack you do not use is
exactly the kind of stale standing instruction shared invariant §8 is about.

## Writing your own adapter

If you wire the kit into a stack no adapter covers, the useful artifact is not a
blog post — it is a sibling directory here, in your own repo, holding:

1. a **README** that walks the kit's configuration points in order and shows the
   values *you* chose, with the reason each one is drawn where it is;
2. the **copyable files** themselves (config, scripts, workflow), each carrying
   a header saying which project shape it assumes;
3. an **INSTALL** with the exact commands and, more importantly, the list of
   what must be edited afterwards and what happens if it is not.

Keep the reasoning in. The regexes are the cheap part — a reader can write those
themselves. What they cannot reconstruct is *why* the test glob is broader than
the source glob, or why the mutation run is a diagnostic and not a gate.
