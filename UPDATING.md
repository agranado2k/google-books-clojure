# Updating the shared layer

Your project took a copy of the **shared layer** when it was bootstrapped. This
is how you move that copy forward when the kit releases a newer one.

It is a **manual, reviewable update**, not a dependency bump — deliberately. The
shared layer is prose that every agent session loads; a silent upgrade of the
rules an agent works under is exactly the kind of change that should require a
human to read the diff.

> This file is itself shared layer. Do not edit it locally — an edited recipe
> drifts from the kit's actual layout and then tells you to do the wrong thing.
> Local notes go in a local article.

---

## What the shared layer is

The files listed under `files:` in `VERSION`, and nothing else.

They are copied **verbatim** from the kit. They name no product, no command, and
no vendor, which is exactly what makes them copyable at all. Everything else in
your repo — `AGENTS.md` and its shims, `README.md`, `docs/`, your adapters — was
stamped from
a template and became **yours** the moment bootstrap wrote it. Those never
update; you own them.

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

## Worked example

A real run, captured from `tests/docs-demo.sh` in the kit. The setup: a consumer
that bootstrapped at shared-layer **0.1.0** (whose layer was
`constitution/shared-invariants.md` alone), updating to **0.3.0** (by which point
the guards, the gate, the harness engine and this file have all joined the
layer). The consumer has one local edit to a shared file — the drift case,
because the clean case teaches nothing.

Refs are local paths here rather than tags, per the pre-1.0 note in step 0.

```console
$ kit tag --list
v0.1.0
v0.3.0
$ echo "$FROM_REF -> $TO_REF"
v0.1.0 -> v0.3.0

$ comm -13 "$WORK/from.list" "$WORK/to.list"   # JOINING
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
 UPDATING.md                       | 359 ++++++++++++++++++++++++++++++++++++++
 constitution/shared-invariants.md |   8 +-
 2 files changed, 366 insertions(+), 1 deletion(-)

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
OK  docs gate: all checks passed (shared-layer 0.3.0, engine: harness)
$ sed -n 's/^shared-layer:[[:space:]]*//p' VERSION
0.3.0
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
