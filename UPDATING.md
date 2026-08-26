# Updating from a kit release

Your project took a copy of the kit when it was bootstrapped. **Two different
things came with it, and they update by two different rules** — because they
are two different kinds of thing:

| | What it covers | The right question at update time |
| --- | --- | --- |
| **Part 1** — steps 0–7 below | the files listed under `files:` in `VERSION` — the **shared layer** | *Is my copy byte-identical to the release?* |
| **Part 2** — steps 8–10 | skills, the manual and its articles, templates, config files, adapters | *What did the kit change, and did I change the same thing?* |

Part 1's files are a **copy**, so a byte comparison answers the question
completely. Part 2's files are **not** a copy: bootstrap stamped or installed
them and they became yours, and editing them is the intended workflow. A byte
comparison there answers the wrong question — it flags every local edit you were
invited to make, and following it would tell you to overwrite your own work.

**Both halves are one update.** Part 1 on its own is an *inert half-update*, and
0.4.0 is the worked example: `scripts/agents.lib.sh` (the capability-tier
resolver) joined the shared layer, so Part 1 delivers it — while the config it
reads, the skills that call it, and the manual section that defines its
vocabulary are all Part 2. Take Part 1 only and you land a resolver with no
mapping and no callers.

It is a **manual, reviewable update**, not a dependency bump — deliberately. The
shared layer is prose that every agent session loads; a silent upgrade of the
rules an agent works under is exactly the kind of change that should require a
human to read the diff.

> This file is itself shared layer. Do not edit it locally — an edited recipe
> drifts from the kit's actual layout and then tells you to do the wrong thing.
> Local notes go in a local article.

---

## Part 1 — what the shared layer is

The files listed under `files:` in `VERSION`, and nothing else.

They are copied **verbatim** from the kit. They name no product, no command, and
no vendor, which is exactly what makes them copyable at all. Everything else in
your repo — `AGENTS.md` and its shims, `README.md`, `docs/`, your skills, your
adapters — was stamped from a template and became **yours** the moment bootstrap
wrote it. Those are never *overwritten* for you; carrying a release's changes
into them is a decision you make, file by file, and that is Part 2.

`VERSION` records which release of the layer you are on:

```sh
sed -n 's/^shared-layer:[[:space:]]*//p' VERSION
```

`VERSION` is itself copied wholesale during an update (step 5). It carries no
project-specific content — only which release you are on and what that release
covers — so there is nothing in it to merge.

**A local exception to a shared rule never gets edited into the shared file.**
It goes in a local article, and the shared copy stays byte-identical. That rule
exists precisely so that this update stays a copy instead of an archaeology
exercise.

---

## Before you start

- A clean working tree (`git status` empty). Step 5 overwrites files in place.
- You are on a branch, not `main` — this lands as a reviewed PR like anything
  else. Shared invariant §7: an agent may take it to one click away and stops.

## Step 0 — point at the kit

```sh
KIT_URL=https://github.com/agranado2k/agentic-sdlc.git
WORK=$(mktemp -d)

git clone --bare --quiet "$KIT_URL" "$WORK/kit.git"
kit() { git --git-dir="$WORK/kit.git" "$@"; }
```

A bare clone: you are only ever *reading* out of it, and a second working tree
on disk is one more thing to get out of sync.

Now pick the two points you are comparing.

```sh
FROM_REF="v$(sed -n 's/^shared-layer:[[:space:]]*//p' VERSION | head -1)"   # what you have
TO_REF=v0.3.0                                                              # what you want

kit tag --list        # the releases on offer
```

> **Pre-1.0 note.** Until the kit cuts tagged releases, `FROM_REF`/`TO_REF` can
> be any git ref the clone can resolve — `main`, a branch, a SHA. Everything
> below works unchanged; only the `v`-prefixed defaults above assume tags.

## Step 1 — read both manifests

The file **list** can change between releases, so read it at both ends rather
than assuming your local one is current.

```sh
manifest() {
	kit show "$1:VERSION" | awk '
		/^files:/       { inlist = 1; next }
		!inlist         { next }
		/^[ \t]*#/      { next }
		/^[ \t]*$/      { next }
		/^[ \t]+[^ \t]/ { sub(/^[ \t]+/, ""); sub(/[ \t]+$/, ""); print; next }
		                { inlist = 0 }
	'
}

manifest "$FROM_REF" | sort >"$WORK/from.list"
manifest "$TO_REF" | sort >"$WORK/to.list"

comm -13 "$WORK/from.list" "$WORK/to.list"   # files JOINING the shared layer
comm -23 "$WORK/from.list" "$WORK/to.list"   # files LEAVING it
```

That awk is the same parser `scripts/check.sh` uses. Two parsers for one file
format is two chances to disagree about what your own manifest says.

## Step 2 — read the upstream delta

What the **kit** changed between the two releases. This is what you are being
asked to adopt.

```sh
kit diff --stat "$FROM_REF" "$TO_REF" -- $(sort -u "$WORK/from.list" "$WORK/to.list")

# then read the ones that moved, in full — this is the part a human must read
kit diff "$FROM_REF" "$TO_REF" -- constitution/shared-invariants.md
```

Read it as rules, not as text. "§9 now requires X" is a change to how every
future session in this repo behaves.

## Step 3 — measure your own drift

What **you** changed. This should print `clean` for every file. Anything else is
a local edit to a file that was not yours to edit, and it is the only thing that
can make this update hard.

```sh
while IFS= read -r f; do
	if [ ! -e "$f" ]; then
		echo "MISSING $f"
		continue
	fi
	if kit show "$FROM_REF:$f" | cmp -s - "$f"; then
		echo "clean   $f"
	else
		echo "DRIFT   $f"
		kit show "$FROM_REF:$f" | diff -u - "$f" | sed 's/^/        /'
	fi
done <"$WORK/from.list"
```

**If you have drift**, stop and resolve it *before* step 5, not during:

1. Read what you changed and why. It is almost always a local exception someone
   needed and wrote in the nearest available place.
2. Move it to a local article — `AGENTS.md`'s local-rules section, or a
   `constitution/local-*.md` — where it belongs and where it survives updates.
3. Restore the shared file to its `FROM_REF` content
   (`kit show "$FROM_REF:$f" >"$f"`), confirm step 3 is clean, and commit that
   as its **own** change. Untangling drift and adopting a new release in one
   commit makes both unreviewable (shared invariant §10).
4. If the exception is genuinely universal rather than local, it is a kit issue,
   not a local edit. Open one.

## Step 4 — decide

You now have three facts: what upstream changed, that your copy is unmodified,
and which files join or leave the layer. Decide, per file, whether you are taking
it. The default is **all of it** — a partial take is possible (step 7) but leaves
you on no release at all.

## Step 5 — apply

```sh
# every file in the TARGET manifest, taken verbatim
while IFS= read -r f; do
	mkdir -p "$(dirname "$f")"
	kit show "$TO_REF:$f" >"$f"
	echo "  updated $f"
done <"$WORK/to.list"

# anything that LEFT the shared layer is no longer kit-owned. Deleting is the
# usual answer; keeping it means it is now an ordinary file of yours.
comm -23 "$WORK/from.list" "$WORK/to.list" | while IFS= read -r f; do
	git rm -q --ignore-unmatch -- "$f" 2>/dev/null || rm -f "$f"
	echo "  removed $f (left the shared layer at $TO_REF)"
done

# the manifest itself, wholesale — version marker and file list together
kit show "$TO_REF:VERSION" >VERSION
```

## Step 6 — verify the verbatim claim, then the gate

The version marker is only worth something if it is checkable. This is the check:

```sh
while IFS= read -r f; do
	if kit show "$TO_REF:$f" | cmp -s - "$f"; then
		echo "verbatim  $f"
	else
		echo "DRIFT     $f"
	fi
done <"$WORK/to.list"

sh scripts/check.sh
```

Every line `verbatim`, and the gate green. Then commit:

```sh
git add -A
git commit -m "chore: update shared layer ${FROM_REF#v} -> ${TO_REF#v}"
rm -rf "$WORK"
```

Note it in `docs/diary.md` — a change to the rules every session loads is a
diary entry by the update protocol ("decision reversed or vendor changed").

---

## Step 7 — taking only part of a release

Sometimes one file's change needs a discussion you are not having today. Take
the rest:

```sh
kit show "$TO_REF:constitution/shared-invariants.md" >constitution/shared-invariants.md
```

…and then **do not bump `shared-layer:`**. A partial take is not the release.
Leave the marker at `FROM_REF`, and record what you deferred and why — in the
diary, or as an issue. The next update then starts from a version you are
genuinely on.

The check in step 6 is what makes this honest: it is the difference between "we
are on 0.3.0" and "we believe we are on 0.3.0". Run it any time, not only during
an update.

## When a file joins the shared layer

Step 5 writes it for you. Two things to check afterwards:

- **You may already have a file at that path.** `kit show >` overwrote it. If it
  had local content, recover it from git and move that content to a local
  article — the path is kit-owned from this release on.
- **The gate now requires it.** `scripts/check.sh` fails if a file named in
  `VERSION` is missing, so deleting it later fails your push rather than silently
  degrading.

## When a shared file's path changes

Treat it as one leaving and one joining: it falls out of `from.list` and into
`to.list`, and step 5 handles both halves. Check the upstream diff for the
rename note so you know it is the same file, not a deletion plus an unrelated
addition.

---

## Worked example — Part 1

A real run, captured from `tests/docs-demo.sh` in the kit. The setup: a consumer
that bootstrapped at shared-layer **0.1.0** (whose layer was
`constitution/shared-invariants.md` alone), updating to **0.4.0** (by which point
the guards, the gate, the harness engine, the tier resolver and this file have
all joined the layer). The consumer has one local edit to a shared file — the
drift case, because the clean case teaches nothing.

Refs are local paths here rather than tags, per the pre-1.0 note in step 0.

```console
$ kit tag --list
v0.1.0
v0.4.0
$ echo "$FROM_REF -> $TO_REF"
v0.1.0 -> v0.4.0

$ comm -13 "$WORK/from.list" "$WORK/to.list"   # JOINING
scripts/agents.lib.sh
scripts/behavior-delta.sh
scripts/check.sh
scripts/docs-conformance/context.mjs
scripts/docs-conformance/index.mjs
scripts/docs-conformance/runner.mjs
scripts/docs-conformance/validators/claude-md-refs.mjs
scripts/guards.lib.sh
scripts/tdd-pairing-guard-ci.sh
scripts/tdd-pairing-guard.sh
UPDATING.md
$ comm -23 "$WORK/from.list" "$WORK/to.list"   # LEAVING
(none)

$ kit diff --stat "$FROM_REF" "$TO_REF" -- $(sort -u "$WORK/from.list" "$WORK/to.list")
 UPDATING.md                       | 811 ++++++++++++++++++++++++++++++++++++++
 constitution/shared-invariants.md |   8 +-
 2 files changed, 818 insertions(+), 1 deletion(-)

$ kit diff "$FROM_REF" "$TO_REF" -- constitution/shared-invariants.md
diff --git a/constitution/shared-invariants.md b/constitution/shared-invariants.md
index 7661602..5c18e6a 100644
--- a/constitution/shared-invariants.md
+++ b/constitution/shared-invariants.md
@@ -96,3 +96,3 @@ A rule that is neither is a suggestion. Label it as one or delete it.
 
-## 9. Measure the ceiling
+## 9. Measure the ceiling, don't assume it
 
@@ -117,2 +117,8 @@ refactor first, on its own, with the suite green before and after.
 
+Per §8 this rule is checkable rather than merely asserted, because the claim is machine-
+visible: a commit whose declared type says "structure only" while its own diff touches a
+contract artifact has contradicted itself. Review tooling should surface those commits as
+a confirm item — the author either splits the commit or relabels it, and both outcomes are
+better than a reviewer discovering the mix by reading.
+
 ## 11. The context budget is a real budget

$ # step 3 — drift check
DRIFT   constitution/shared-invariants.md
        @@ -122,3 +122,5 @@
         push elaboration into articles read on demand; scope package-specific rules to the
         package. Duplicated guidance is not redundancy, it is drift waiting to happen — every
         rule has exactly one home, and everywhere else points at it.
        +
        +NOTE (local): §4 is waived for the QA phase in this repo.

$ # the exception moves to a local article; the shared file is restored
clean   constitution/shared-invariants.md

$ # step 5 — apply
  updated constitution/shared-invariants.md
  updated scripts/agents.lib.sh
  updated scripts/behavior-delta.sh
  updated scripts/check.sh
  updated scripts/docs-conformance/context.mjs
  updated scripts/docs-conformance/index.mjs
  updated scripts/docs-conformance/runner.mjs
  updated scripts/docs-conformance/validators/claude-md-refs.mjs
  updated scripts/guards.lib.sh
  updated scripts/tdd-pairing-guard-ci.sh
  updated scripts/tdd-pairing-guard.sh
  updated UPDATING.md

$ # step 6 — verbatim check, then the gate
verbatim  constitution/shared-invariants.md
verbatim  scripts/agents.lib.sh
verbatim  scripts/behavior-delta.sh
verbatim  scripts/check.sh
verbatim  scripts/docs-conformance/context.mjs
verbatim  scripts/docs-conformance/index.mjs
verbatim  scripts/docs-conformance/runner.mjs
verbatim  scripts/docs-conformance/validators/claude-md-refs.mjs
verbatim  scripts/guards.lib.sh
verbatim  scripts/tdd-pairing-guard-ci.sh
verbatim  scripts/tdd-pairing-guard.sh
verbatim  UPDATING.md
$ sh scripts/check.sh
OK  docs gate: all checks passed (shared-layer 0.4.0, engine: harness)
$ sed -n 's/^shared-layer:[[:space:]]*//p' VERSION
0.4.0
```

Read the drift block again. The consumer had written a local exception **into**
the shared rulebook. Step 3 found it in one command; the fix was to move those
two lines to `AGENTS.md` and restore the shared file to its 0.1.0 bytes, as its
own commit. Only then did step 5 run — and it is a plain overwrite, because
there was nothing left to merge.

Had the exception stayed where it was, step 5 would have silently destroyed it
and nobody would have known which paragraph used to be there.

The lesson is step 3. The update itself is a `git show` redirect per shared
file; what makes it cheap or expensive is entirely whether anyone edited a file
that was not theirs to edit.

---

# Part 2 — the parts that are yours

Everything bootstrap stamped, installed or left behind is **yours**: the skills
under `.claude/skills/`, `AGENTS.md` and the `constitution/local-*.md` articles,
the workflows under `.github/workflows/`, the config files, `README.md`, `docs/`,
and `adapters/`.

"Yours" does not mean frozen. The kit keeps improving them, and a release's
actual *features* usually live here rather than in the shared layer — 0.4.0's
value is a Deliver phase in `/implement`, tier-aware planning in `/to-tickets`,
two new skills and a cross-provider review workflow, none of which is
manifest-listed. What "yours" means is that **nothing here is ever overwritten
without you looking at it**, and that there is no verbatim check at the end: the
docs gate is the check.

Do Part 2 *after* Part 1 and commit it separately (shared invariant §10). Part 1
is a mechanical overwrite anybody can re-derive; Part 2 is a series of
judgements, and a reviewer reading the two mixed together can check neither.

## Step 8 — list what changed outside the shared layer

Reuse the bare clone, the two refs, and the two manifests from steps 0 and 1.

```sh
kit diff --name-only "$FROM_REF" "$TO_REF" | sort >"$WORK/changed.all"
sort -u "$WORK/from.list" "$WORK/to.list" >"$WORK/shared.all"
comm -23 "$WORK/changed.all" "$WORK/shared.all" >"$WORK/changed.yours"

cat "$WORK/changed.yours"
```

Do **not** re-derive `FROM_REF` from `VERSION` here: step 5 already moved it to
the release you are adopting. Part 2 runs in the same session as Part 1, on the
same two refs.

Some of what prints is not in your repo and never was. Bootstrap deletes the
kit's own scaffolding (`tests/`, `.github/workflows/kit-*.yml`, `EXCLUSIONS.md`,
and `bootstrap.sh` itself) and consumes `templates/docs/` into `docs/`. A path
you do not have is not an update — skip those lines. `VERSION` prints too,
because it is not an entry in its own manifest; step 5 already copied it.

## Step 9 — take each category by its own rule

One rule per category, because the categories differ in what a local edit
*means*:

| Category | Paths | The rule |
| --- | --- | --- |
| **Skills** (9a) | `.claude/skills/*/` | three-way: kit's old → kit's new → yours. Take the delta unless you deliberately forked |
| **Manual & articles** (9b) | `AGENTS.md`, `constitution/local-*.md` | three-way against the `.template` they were stamped from; you are hunting for **sections** you do not have |
| **Templates** (9c) | `templates/workflows/*` → `.github/workflows/` | copy only what you have not customized; new files are plain adds |
| **Config** (9d) | `scripts/*.config.sh`, `scripts/docs-conformance/config.mjs`, `.../local-vocabulary.mjs` | **never overwrite.** Diff the KEY SETS — the new shared code may read a key you do not set |
| **Adapters** (9e) | `adapters/` | opt-in, whole-directory. Take a tree or leave it; never half of one |

### 9a. Skills — a three-way, not a copy

A skill is prose an agent loads, and adapting it to your repo is the intended
way to make the chain fit. So "is it byte-identical to the release?" is the
wrong question here; the right one is **"what did the kit change, and did I
change the same lines?"**

```sh
S=.claude/skills/implement/SKILL.md

kit diff "$FROM_REF" "$TO_REF" -- "$S"       # what the KIT changed
kit show "$FROM_REF:$S" | diff -u - "$S"     # what YOU changed since bootstrap
```

Four outcomes, and only one of them needs a human:

- **kit clean, you clean** — nothing to do.
- **kit changed, you clean** — take it: `kit show "$TO_REF:$S" >"$S"`.
- **kit clean, you changed** — nothing to do. Your version stands.
- **both changed** — merge; do not pick a side:

  ```sh
  kit show "$FROM_REF:$S" >"$WORK/base"
  kit show "$TO_REF:$S" >"$WORK/theirs"
  git merge-file "$S" "$WORK/base" "$WORK/theirs"
  ```

  `git merge-file` merges in place and exits non-zero after writing conflict
  markers where the two edits overlap. Read those; there is no verbatim check to
  fall back on, which is exactly why this category is not automatable.

**If you deliberately forked a skill, write the fork down** — one line in a
local article ("`/review-pr`'s Axis-2 section is ours; we replaced the
confirm-list format"). That single line is the whole difference between a fork
and drift, because the next update is run by somebody who was not there. It is
the same rule as Part 1's step 3, moved one category over: the exception lives
in a local article, not in the file the kit owns.

**A new skill is a directory copy, and it is not installed until the manual
points at it.**

```sh
kit diff --name-only --diff-filter=A "$FROM_REF" "$TO_REF" -- .claude/skills

kit archive "$TO_REF" .claude/skills/improve-codebase-architecture | tar -x
```

Then add its row to `AGENTS.md`'s quick reference **by hand**. That is not
bookkeeping. The docs gate resolves every `/command` in the manual layer to a
skill directory, so a row whose skill you did not copy fails your next push
(`skill-missing`) — and a skill with no row is a command nobody in this repo
will ever find.

**A removed skill** (`--diff-filter=D`) is the reverse: delete the directory and
the row in the same commit, and let the gate catch the half you forgot.

### 9b. The manual and the local articles — hunt for missing SECTIONS

`AGENTS.md` was stamped from `constitution/AGENTS.md.template`; each
`constitution/local-*.md` was stamped from its `.template` sibling. All of them
are yours, and bootstrap refuses to run twice, so a release's changes to those
templates reach you only if you carry them.

```sh
kit diff "$FROM_REF" "$TO_REF" -- constitution/
```

Read that diff for **new sections**, not new lines. When a release introduces a
*concept*, it introduces it here, and your stamped copy simply has no paragraph
about it. 0.3.0 → 0.4.0 adds a "Capability tiers" section to the manual template
and a matching block to the workflow article: skip them and your repo has skills
that speak four tier names and no file that says what they mean.

Copy the new sections across by hand, adapting the wording to your repo. Never
re-stamp a template over a manual you have been editing for six months.

### 9c. Templates — copy only what you have not customized

`templates/workflows/` is installed into `.github/workflows/` **once**, at
bootstrap, and bootstrap never overwrites a file that is already there (it prints
`kept …`). So a release's changes here reach you only by hand.

```sh
kit ls-tree --name-only "$TO_REF" templates/workflows/ | while IFS= read -r wf; do
	dest=".github/workflows/$(basename "$wf")"
	if [ ! -e "$dest" ]; then
		echo "NEW       $dest"
	elif kit show "$FROM_REF:$wf" 2>/dev/null | cmp -s - "$dest"; then
		echo "UNTOUCHED $dest"
	else
		echo "YOURS     $dest"
	fi
done
```

`NEW` and `UNTOUCHED` are both `kit show "$TO_REF:$wf" >"$dest"`. `YOURS` is a
three-way merge, exactly as in 9a.

**A workflow can have a file it needs beside it.** 0.4.0's
`ai-review.example.yml` reads `.github/workflows/ai-review-prompt.md` at run
time; take one without the other and you have a workflow that fails on its first
run. Take a template together with its neighbours, and keep the `.example`
suffix until you have added a provider secret — it ships inert on purpose.

`templates/docs/` is a different case: bootstrap consumed it and deleted it. Its
descendants — `README.md`, `docs/diary.md`, `docs/adr/`, the PR template — are
ordinary files of yours now. A kit change there is something you may read and
borrow from; it is never something to copy over the top.

### 9d. Config files — never overwrite, always diff the KEY SET

**This is the category that breaks silently**, because both failure modes are
quiet. Overwrite the file and your provider and model choices vanish with no
error. Skip it and the release's new shared code reads a key you never set,
resolves it to empty, and carries on.

The config files are the ones `VERSION` names in its "everything NOT shared"
comment: `scripts/guards.config.sh`, `scripts/agents.config.sh`,
`scripts/docs-conformance/config.mjs`,
`scripts/docs-conformance/local-vocabulary.mjs`.

**First ask whether the file existed at the release you are on.** If it did not,
this is an ADD and there is nothing of yours to preserve:

```sh
C=scripts/agents.config.sh

if kit cat-file -e "$FROM_REF:$C" 2>/dev/null; then
	echo "MERGE  $C existed at $FROM_REF — diff the keys, below"
else
	echo "ADD    $C is new at $TO_REF — copy it whole"
	kit show "$TO_REF:$C" >"$C"
fi
```

That is the 0.3.0 → 0.4.0 case: `scripts/agents.config.sh` did **not** exist at
0.3.0 — it arrived with the tier resolver — so a 0.3.0 consumer copies the whole
file and then edits it. Nothing is at risk, which is precisely why it is worth
checking rather than assuming: the same path is a destructive overwrite for a
consumer who *did* have it.

**For the MERGE case, never `kit show >` the file.** Diff the key sets instead:

```sh
keys() { sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' "$1" | sort -u; }

kit show "$TO_REF:$C" >"$WORK/config.new"
keys "$WORK/config.new" >"$WORK/keys.new"
keys "$C" >"$WORK/keys.mine"

comm -13 "$WORK/keys.mine" "$WORK/keys.new"   # keys the RELEASE expects, you lack
comm -23 "$WORK/keys.mine" "$WORK/keys.new"   # keys only you have — yours, or removed upstream
```

Add each missing key to your file **with your value**, and bring the kit's
comment block for it across so the next reader knows what it is for. An unset key
is not automatically a bug — `agents.config.sh` ships all four tiers empty and
unset is a documented working state — but it has to be a key you decided to leave
unset, not one you never saw.

Then re-read `scripts/agents.lib.sh` (or whatever shared code reads the config).
It is shared layer, so Part 1 already replaced it: what it reads *now* is the
authority on what your config has to provide.

### 9e. Adapters — opt-in, whole-directory

`adapters/` is reference material. Nothing in it runs, nothing was stamped from
it, and no gate reads it. If you deleted the tree at bootstrap — a documented,
supported answer — a release's changes there are none of your business.

If you kept it, take whole directories:

```sh
kit archive "$TO_REF" adapters | tar -x
```

Never merge a single adapter file. Each directory is one worked wiring that has
to stay internally consistent; half of the release's on top of half of yours is a
configuration nobody has ever run.

## Step 10 — verify with the gate, then commit

Part 2 has no verbatim claim to check, so the gate is the check — and it is not a
formality here. It is what catches the quick-reference row whose skill you did not
copy, the article the manual points at that you never created, and the path
reference that moved.

```sh
sh scripts/check.sh
```

```sh
git add -A
git commit -m "chore: adopt kit ${TO_REF#v} outside the shared layer"
```

Note it in `docs/diary.md` alongside the Part 1 entry. Part 2 is where the
release's behaviour actually changed, so it is the half a future reader will want
explained.

## Optional skills — adopting or declining one after bootstrap

The kit ships exactly one **optional** skill, `/dogfood`, and bootstrap asked
about it **once, at bootstrap**. There is no second question: bootstrap deleted
itself, and no update step will ever ask again. So both directions are manual,
and both are more than a directory.

**Adopting `/dogfood` later** — you now have a runnable user-facing surface:

```sh
kit archive "$TO_REF" .claude/skills/dogfood | tar -x
kit show "$TO_REF:constitution/local-product.md.template" \
	>constitution/local-product.md.template
```

Then, by hand, the part no command can do for you:

1. add `/dogfood`'s row to `AGENTS.md`'s quick reference (the kit's template
   carries it between `<!-- DOGFOOD:BEGIN -->` / `<!-- DOGFOOD:END -->` markers —
   `kit show "$TO_REF:constitution/AGENTS.md.template"` shows you exactly which
   lines bootstrap would have kept);
2. fill in the DOGFOOD DECLARATION in `constitution/local-product.md.template`,
   drop the `.template` suffix, and point `AGENTS.md`'s article layer at the
   result — the same three steps as the other local articles. Until you do, the
   skill stops and says so, which is correct: a guessed persona produces a report
   about a user who does not exist.

```sh
sh scripts/check.sh
```

**Declining it later** is the exact reverse, and the order matters — remove the
references first, then the files, so the gate is red in between rather than
green over a half-removal:

```sh
rm -rf .claude/skills/dogfood
rm -f constitution/local-product.md constitution/local-product.md.template
```

…and remove *every* mention from the manual layer: the quick-reference row, the
paragraph that introduces it, and the article-layer pointer.

```sh
sh scripts/check.sh
```

**The gate is the proof that nothing dangles.** A `/dogfood` mention left
anywhere in the manual layer with no skill directory behind it is `skill-missing`
and fails the push — which is the point. A half-removed skill is worse than
either whole state: the manual promises a command the repo does not have, and
every session loads that promise.

---

## Worked example — Part 2

The same test, a different consumer. This one bootstrapped at shared-layer
**0.3.0** with `/dogfood` declined, adapted `/to-tickets` with a local note (a
legitimate edit — skills are yours), and has just finished Part 1: its `VERSION`
says 0.4.0, `scripts/agents.lib.sh` is on disk, and the gate is green.

**And nothing the release is for has arrived.** `tests/docs-demo.sh` asserts
exactly that before running a single Part 2 command: no `scripts/agents.config.sh`,
no `/improve-codebase-architecture`, no review workflow, no Deliver phase in
`/implement`, and a resolver that runs, prints nothing, and exits 0 — because an
unmapped tier is a working state, which is precisely why the half-update is
silent. Part 2 is what fixes it:

```console
$ comm -23 "$WORK/changed.all" "$WORK/shared.all" >"$WORK/changed.yours"
$ cat "$WORK/changed.yours"
.claude/skills/dogfood/SKILL.md
.claude/skills/implement/SKILL.md
.claude/skills/improve-codebase-architecture/DEEPENING.md
.claude/skills/improve-codebase-architecture/INTERFACE-DESIGN.md
.claude/skills/improve-codebase-architecture/LANGUAGE.md
.claude/skills/improve-codebase-architecture/PRESENTING.md
.claude/skills/improve-codebase-architecture/SKILL.md
.claude/skills/to-tickets/SKILL.md
adapters/claude-code/README.md
constitution/AGENTS.md.template
constitution/local-product.md.template
constitution/local-workflow.md.template
scripts/agents.config.sh
templates/workflows/ai-review-prompt.md
templates/workflows/ai-review.example.yml
VERSION

$ # 9a — /implement: the kit changed it, we did not
$ kit diff --stat "$FROM_REF" "$TO_REF" -- "$S"
 .claude/skills/implement/SKILL.md | 19 +++++++++++++++++++
 1 file changed, 19 insertions(+)
$ kit show "$FROM_REF:$S" | diff -u - "$S" | head -1
(no local edit — take it)
  took    .claude/skills/implement/SKILL.md

$ # 9a — /to-tickets: BOTH changed. Three-way, not a copy.
$ git merge-file "$T" "$WORK/base" "$WORK/theirs"
  merged clean — the kit's delta and our local note both survive

$ kit diff --name-only --diff-filter=A "$FROM_REF" "$TO_REF" -- .claude/skills
.claude/skills/dogfood/SKILL.md
.claude/skills/improve-codebase-architecture/DEEPENING.md
.claude/skills/improve-codebase-architecture/INTERFACE-DESIGN.md
.claude/skills/improve-codebase-architecture/LANGUAGE.md
.claude/skills/improve-codebase-architecture/PRESENTING.md
.claude/skills/improve-codebase-architecture/SKILL.md
$ kit archive "$TO_REF" .claude/skills/improve-codebase-architecture | tar -x
$ sh scripts/check.sh   # the skill is here; the manual does not know
OK  docs gate: all checks passed (shared-layer 0.4.0, engine: harness)

$ # 9b — new SECTIONS in the manual template we were stamped from
$ kit diff --stat "$FROM_REF" "$TO_REF" -- constitution/
 constitution/AGENTS.md.template         |  41 ++++++++++++-
 constitution/local-product.md.template  | 103 ++++++++++++++++++++++++++++++++
 constitution/local-workflow.md.template |  43 +++++++++++++
 3 files changed, 186 insertions(+), 1 deletion(-)
$ # copied across by hand: the Capability tiers section, and two rows
  edited  AGENTS.md (new section + three quick-reference rows)

$ # 9c — workflow templates: installed once at bootstrap, never after
NEW       .github/workflows/ai-review-prompt.md
NEW       .github/workflows/ai-review.example.yml
UNTOUCHED .github/workflows/commitlint.yml.example
UNTOUCHED .github/workflows/docs-gate.yml
UNTOUCHED .github/workflows/tdd-pairing.yml
  took    .github/workflows/ai-review.example.yml + its prompt file

$ # 9d — config: ADD or MERGE? Ask before you write.
$ # kit cat-file -e "$FROM_REF:$C" — did it exist at the release we are on?
ADD    scripts/agents.config.sh is new at v0.4.0 — nothing of ours to preserve
$ sed -n 's/^\(AGENT_TIER_[A-Z]*\)=.*/\1/p' "$C"
AGENT_TIER_PLANNER
AGENT_TIER_IMPLEMENTER
AGENT_TIER_MECHANICAL
AGENT_TIER_REVIEWER

$ # 9e — adapters: whole directories, or none
$ kit archive "$TO_REF" adapters | tar -x
claude-code
node-ts
README.md

$ sh scripts/check.sh
OK  docs gate: all checks passed (shared-layer 0.4.0, engine: harness)
```

Four things in that transcript are worth reading twice.

**`ADD    scripts/agents.config.sh is new at v0.4.0`.** The tier→model map did
not exist at 0.3.0; it arrived with the resolver. So this consumer copies the
whole file — nothing of theirs is at risk — and then edits it. That is *this*
pair of releases, not a rule: the same path is a destructive overwrite for a
consumer who already had the file, which is why 9d asks before it writes.

**`merged clean — the kit's delta and our local note both survive`.** The kit
added a tier rubric to `/to-tickets`; the consumer had added a line of their own.
A copy would have destroyed one of them, and a byte comparison would have called
a legitimate local adaptation "drift". Neither is the right question for a skill.

**The gate ran twice, and the first run was green.** After the new skill's
directory landed but before its quick-reference row was written by hand, nothing
was broken — a skill with no row is merely invisible. The gate's teeth are on the
other side: a row with no skill is `skill-missing` and fails the push, which is
what makes "add the row by hand" a step rather than a suggestion. The test proves
both directions, and proves them again for `/dogfood` adopted and then declined
after bootstrap.

**`NEW       .github/workflows/ai-review-prompt.md`.** The workflow next to it
reads that file at run time. Taking one and not the other produces a review
workflow that fails on its first PR — which is why 9c says take a template with
its neighbours.
