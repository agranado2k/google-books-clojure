#!/bin/sh
# K4's acceptance test — and its demo.
#
# Two claims, both run end to end against throwaway copies of the kit:
#
#   A. Bootstrap produces a PERSONALIZED documentation set — diary with a dated
#      current-state block, ADR index + MADR template, domain glossary, PR
#      template, and a consumer README that describes the project instead of the
#      kit — and the docs gate is green on it.
#   B. The UPDATING.md recipe WORKS. A fake older consumer (shared-layer 0.1.0)
#      is diffed against a newer kit (0.3.0) and updated with the exact commands
#      in UPDATING.md, including the drift case, a file joining the layer, and
#      the verbatim check.
#
# Part B's output is the transcript quoted in UPDATING.md's worked example. If
# you change the recipe, re-run this and re-paste it — a worked example nobody
# re-runs is the "stale standing instruction" shared invariant §8 is about.
#
# Usage: sh tests/docs-demo.sh

set -u

KIT=$(cd "$(dirname "$0")/.." && pwd)
SCRATCH=$(mktemp -d) || exit 2

trap 'rm -rf "$SCRATCH"' EXIT INT TERM HUP

failures=0

banner() { printf '\n=== %s ===\n' "$*"; }
pass() { printf '  ok    %s\n' "$*"; }
fail() {
	printf '  FAIL  %s\n' "$*"
	failures=$((failures + 1))
}

assert_file() { [ -e "$1" ] && pass "$1 exists" || fail "$1 is missing"; }
assert_no_file() { [ -e "$1" ] && fail "$1 still exists" || pass "$1 is gone"; }

# assert_has <file> <string>
assert_has() {
	if grep -qF "$2" "$1" 2>/dev/null; then
		pass "$1 mentions '$2'"
	else
		fail "$1 does not mention '$2'"
	fi
}

# assert_same <a> <b> <label>
assert_same() {
	if cmp -s "$1" "$2"; then
		pass "$3"
	else
		fail "$3 — files differ"
	fi
}

# assert_status <expected> <label> -- <command...>
assert_status() {
	expected=$1
	label=$2
	shift 3
	out=$("$@" 2>&1)
	actual=$?
	if [ "$actual" = "$expected" ]; then
		pass "$label (exit $actual)"
	else
		fail "$label — expected exit $expected, got $actual"
		printf '%s\n' "$out" | sed 's/^/        | /'
	fi
	LAST_OUT=$out
}

TODAY=$(date +%Y-%m-%d)

# ###########################################################################
# PART A — bootstrap produces the personalized documentation set
# ###########################################################################

PROJ="$SCRATCH/demo-project"

banner "A0. Setup — simulate 'Use this template'"
mkdir -p "$PROJ"
cp -R "$KIT/." "$PROJ/"
rm -rf "$PROJ/.git"
cd "$PROJ" || exit 2
git init -q -b main
git config user.name "Docs Demo"
git config user.email "demo@example.invalid"
git config commit.gpgsign false
pass "fresh repo at \$SCRATCH/demo-project"

banner "A1. Bootstrap"
assert_status 0 "bootstrap.sh runs" -- \
	sh bootstrap.sh "Aurora Ledger" "A double-entry ledger for small cooperatives."
printf '%s\n' "$LAST_OUT" | sed 's/^/      > /'

banner "A2. The documentation set is in place"
assert_file "README.md"
assert_file "docs/diary.md"
assert_file "docs/domain-glossary.md"
assert_file "docs/adr/INDEX.md"
assert_file "docs/adr/NNNN-template.md"
assert_file ".github/PULL_REQUEST_TEMPLATE.md"
assert_file "UPDATING.md"
assert_no_file "templates/docs"
assert_no_file "templates"

banner "A3. It is PERSONALIZED, not merely present"
assert_has "README.md" "# Aurora Ledger"
assert_has "README.md" "A double-entry ledger for small cooperatives."
assert_has "docs/diary.md" "Current state — $TODAY"
assert_has "docs/diary.md" "Living history of the Aurora Ledger build"
assert_has "docs/diary.md" "### $TODAY — Bootstrapped from the agentic-sdlc kit"
assert_has "docs/domain-glossary.md" "canonical terms for Aurora Ledger"
assert_has "docs/adr/INDEX.md" "architectural decision for Aurora Ledger"

# The K0 README advertised the kit itself. A project whose README tells readers
# to "use it as a GitHub template" is describing the wrong repository.
if grep -qF "Use it as a GitHub template" README.md; then
	fail "README.md is still the KIT's README, not the project's"
else
	pass "README.md no longer describes the kit"
fi

banner "A4. Verbatim files are byte-identical to the kit's"
assert_same "$KIT/templates/docs/adr/NNNN-template.md" "docs/adr/NNNN-template.md" \
	"docs/adr/NNNN-template.md copied verbatim"
assert_same "$KIT/templates/docs/PULL_REQUEST_TEMPLATE.md" ".github/PULL_REQUEST_TEMPLATE.md" \
	".github/PULL_REQUEST_TEMPLATE.md copied verbatim"

banner "A5. The gate is green on the whole set"
# Not `git add`-ed first, deliberately: a just-bootstrapped project has committed
# nothing, and the gate must still see (and scan) every new doc.
assert_status 0 "check.sh passes on the bootstrapped project" -- sh scripts/check.sh
assert_status 0 "shared layer reported" -- sh scripts/check.sh
case "$LAST_OUT" in
*"shared-layer 0.3.0"*) pass "gate reports shared-layer 0.3.0" ;;
*) fail "gate did not report shared-layer 0.3.0: $LAST_OUT" ;;
esac

banner "A6. The gate is NOT vacuous over the new docs"
# If an unstamped mark could survive in docs/, "personalized" would be unchecked.
printf '\n- **Owner** — {{PROJECT_OWNER}}\n' >>docs/domain-glossary.md
assert_status 1 "check.sh rejects an unstamped mark in docs/" -- sh scripts/check.sh
case "$LAST_OUT" in
*"domain-glossary"*) pass "the violation names the glossary" ;;
*) fail "the violation does not name the glossary" ;;
esac
sed '/PROJECT_OWNER/d' docs/domain-glossary.md >"$SCRATCH/glossary.clean"
cp "$SCRATCH/glossary.clean" docs/domain-glossary.md
assert_status 0 "check.sh is green again once the mark is removed" -- sh scripts/check.sh

# And the manual's new references have to resolve.
rm -f docs/diary.md
assert_status 1 "check.sh rejects a deleted docs/diary.md (AGENTS.md points at it)" -- sh scripts/check.sh
case "$LAST_OUT" in
*"path-missing"*) pass "reported as path-missing" ;;
*) fail "not reported as path-missing" ;;
esac

# ###########################################################################
# PART B — the UPDATING.md recipe, executed once
# ###########################################################################
#
# Scenario, constructed in a scratch dir:
#   kit v0.1.0 — shared layer is constitution/shared-invariants.md alone, and
#                its §9/§10 are one heading and one paragraph shorter
#   kit v0.3.0 — the kit as it stands here: §9/§10 tightened, and UPDATING.md
#                JOINS the shared layer
#   consumer   — bootstrapped from v0.1.0, and someone edited the shared file
#                locally (the drift case — the clean case teaches nothing)

banner "B0. Build the fake older kit (v0.1.0) and a consumer on it"

OLDKIT="$SCRATCH/kit-0.1.0"
mkdir -p "$OLDKIT"
cp -R "$KIT/." "$OLDKIT/"
rm -rf "$OLDKIT/.git"
rm -f "$OLDKIT/UPDATING.md" # UPDATING.md did not exist at 0.1.0

cat >"$OLDKIT/VERSION" <<'EOF'
# agentic-sdlc — shared-layer manifest

shared-layer: 0.1.0

files:
  constitution/shared-invariants.md
EOF

# Roll the shared rulebook back to its (simulated) 0.1.0 wording.
sed -e "s/^## 9\. Measure the ceiling, don't assume it$/## 9. Measure the ceiling/" \
	-e '/^Per §8 this rule is checkable/,/^$/d' \
	"$KIT/constitution/shared-invariants.md" >"$OLDKIT/constitution/shared-invariants.md"

if cmp -s "$OLDKIT/constitution/shared-invariants.md" "$KIT/constitution/shared-invariants.md"; then
	fail "the fake 0.1.0 rulebook is identical to 0.3.0 — the scenario has no delta"
else
	pass "fake 0.1.0 rulebook differs from 0.3.0"
fi

# A .git-free copy of the kit as it stands: the v0.3.0 release tree.
NEWKIT="$SCRATCH/kit-0.3.0"
mkdir -p "$NEWKIT"
cp -R "$KIT/." "$NEWKIT/"
rm -rf "$NEWKIT/.git"

# The two ends, as real git refs in one repo.
HIST="$SCRATCH/kit-history"
mkdir -p "$HIST"
cp -R "$OLDKIT/." "$HIST/"
cd "$HIST" || exit 2
git init -q -b main
git config user.name "Kit Release"
git config user.email "kit@example.invalid"
git config commit.gpgsign false
git config tag.gpgSign false
git config tag.forceSignAnnotated false
git add -A >/dev/null
git commit -q -m "release 0.1.0"
git tag v0.1.0
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp -R "$NEWKIT/." "$HIST/"
git add -A >/dev/null
git commit -q -m "release 0.3.0"
git tag v0.3.0
if git rev-parse -q --verify v0.1.0 >/dev/null && git rev-parse -q --verify v0.3.0 >/dev/null; then
	pass "kit history built with tags v0.1.0 and v0.3.0"
else
	fail "kit history tags were not created"
fi

# A consumer bootstrapped at 0.1.0.
CONSUMER="$SCRATCH/older-consumer"
mkdir -p "$CONSUMER"
cp -R "$OLDKIT/." "$CONSUMER/"
rm -rf "$CONSUMER/.git"
cd "$CONSUMER" || exit 2
git init -q -b main
git config user.name "Older Consumer"
git config user.email "older@example.invalid"
git config commit.gpgsign false
sh bootstrap.sh "Older Consumer" "A project that bootstrapped at shared-layer 0.1.0." >/dev/null 2>&1
git add -A >/dev/null
git commit -q -m "chore: bootstrap from agentic-sdlc"
assert_status 0 "the 0.1.0 consumer's gate is green before the update" -- sh scripts/check.sh

# The local edit that should never have been made to a verbatim file.
printf '\nNOTE (local): §4 is waived for the QA phase in this repo.\n' \
	>>constitution/shared-invariants.md
git commit -qam "docs: note our local exception to invariant 4"
pass "consumer carries one local edit to a shared-layer file (drift)"

# ---------------------------------------------------------------------------
banner "B1. RUN THE RECIPE — the transcript below is UPDATING.md's worked example"
# ---------------------------------------------------------------------------
printf '\n'

recipe() {
	# --- Step 0: point at the kit -------------------------------------------
	KIT_URL="$HIST"
	WORK=$(mktemp -d)
	git clone --bare --quiet "$KIT_URL" "$WORK/kit.git"
	kit() { git --git-dir="$WORK/kit.git" "$@"; }

	FROM_REF="v$(sed -n 's/^shared-layer:[[:space:]]*//p' VERSION | head -1)"
	TO_REF=v0.3.0

	echo "\$ kit tag --list"
	kit tag --list
	echo "\$ echo \"\$FROM_REF -> \$TO_REF\""
	echo "$FROM_REF -> $TO_REF"

	# --- Step 1: read both manifests ----------------------------------------
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

	echo ""
	echo "\$ comm -13 \"\$WORK/from.list\" \"\$WORK/to.list\"   # JOINING"
	comm -13 "$WORK/from.list" "$WORK/to.list"
	echo "\$ comm -23 \"\$WORK/from.list\" \"\$WORK/to.list\"   # LEAVING"
	comm -23 "$WORK/from.list" "$WORK/to.list" | grep . || echo "(none)"

	# --- Step 2: the upstream delta -----------------------------------------
	echo ""
	echo "\$ kit diff --stat \"\$FROM_REF\" \"\$TO_REF\" -- \$(sort -u \"\$WORK/from.list\" \"\$WORK/to.list\")"
	# shellcheck disable=SC2046
	kit diff --stat "$FROM_REF" "$TO_REF" -- $(sort -u "$WORK/from.list" "$WORK/to.list")
	echo ""
	echo "\$ kit diff \"\$FROM_REF\" \"\$TO_REF\" -- constitution/shared-invariants.md"
	kit diff --unified=1 "$FROM_REF" "$TO_REF" -- constitution/shared-invariants.md |
		sed -n '1,40p'

	# --- Step 3: measure your own drift -------------------------------------
	echo ""
	echo "\$ # step 3 — drift check"
	while IFS= read -r f; do
		if [ ! -e "$f" ]; then
			echo "MISSING $f"
			continue
		fi
		if kit show "$FROM_REF:$f" | cmp -s - "$f"; then
			echo "clean   $f"
		else
			echo "DRIFT   $f"
			kit show "$FROM_REF:$f" | diff -u - "$f" | sed -n '3,$p' | sed 's/^/        /'
		fi
	done <"$WORK/from.list"

	# --- Step 3 remediation: move the exception out, restore the shared file --
	echo ""
	echo "\$ # the exception moves to a local article; the shared file is restored"
	{
		echo ""
		echo "## Local exception to shared invariant 4"
		echo ""
		echo "§4 is waived for the QA phase in this repo."
	} >>AGENTS.md
	while IFS= read -r f; do
		kit show "$FROM_REF:$f" >"$f"
	done <"$WORK/from.list"
	git commit -qam "refactor: move the local exception out of the shared layer"
	while IFS= read -r f; do
		kit show "$FROM_REF:$f" | cmp -s - "$f" && echo "clean   $f" || echo "DRIFT   $f"
	done <"$WORK/from.list"

	# --- Step 5: apply ------------------------------------------------------
	echo ""
	echo "\$ # step 5 — apply"
	while IFS= read -r f; do
		mkdir -p "$(dirname "$f")"
		kit show "$TO_REF:$f" >"$f"
		echo "  updated $f"
	done <"$WORK/to.list"
	comm -23 "$WORK/from.list" "$WORK/to.list" | while IFS= read -r f; do
		git rm -q --ignore-unmatch -- "$f" 2>/dev/null || rm -f "$f"
		echo "  removed $f (left the shared layer at $TO_REF)"
	done
	kit show "$TO_REF:VERSION" >VERSION

	# --- Step 6: verify -----------------------------------------------------
	echo ""
	echo "\$ # step 6 — verbatim check, then the gate"
	while IFS= read -r f; do
		if kit show "$TO_REF:$f" | cmp -s - "$f"; then
			echo "verbatim  $f"
		else
			echo "DRIFT     $f"
		fi
	done <"$WORK/to.list"
	echo "\$ sh scripts/check.sh"
	sh scripts/check.sh
	gate=$?
	echo "\$ sed -n 's/^shared-layer:[[:space:]]*//p' VERSION"
	sed -n 's/^shared-layer:[[:space:]]*//p' VERSION
	rm -rf "$WORK"
	return $gate
}

recipe
recipe_status=$?
printf '\n'

banner "B2. Assertions on the updated consumer"
if [ "$recipe_status" = 0 ]; then
	pass "the recipe finished with a green gate"
else
	fail "the recipe finished with gate exit $recipe_status"
fi

cd "$CONSUMER" || exit 2
assert_file "UPDATING.md"
assert_same "$KIT/UPDATING.md" "UPDATING.md" \
	"UPDATING.md joined the layer and is byte-identical to the kit's"
assert_same "$KIT/constitution/shared-invariants.md" "constitution/shared-invariants.md" \
	"constitution/shared-invariants.md is byte-identical to the kit's"
assert_has "VERSION" "shared-layer: 0.3.0"
assert_has "AGENTS.md" "Local exception to shared invariant 4"

# The whole point: the local exception survived the update, in a file that is
# the consumer's own.
if grep -qF "NOTE (local)" constitution/shared-invariants.md; then
	fail "the local edit is still inside the shared file"
else
	pass "the shared file carries no local edit"
fi

banner "B3. The verbatim check is not vacuous"
printf '\nlocal tweak\n' >>constitution/shared-invariants.md
if cmp -s "$KIT/constitution/shared-invariants.md" constitution/shared-invariants.md; then
	fail "an edited shared file still compares equal — the check proves nothing"
else
	pass "an edited shared file is detected as DRIFT"
fi

# ---------------------------------------------------------------------------
banner "Result"
# ---------------------------------------------------------------------------
if [ "$failures" = 0 ]; then
	echo "  ALL GREEN — the docs set is personalized and the update recipe works."
	exit 0
fi
echo "  $failures assertion(s) failed."
exit 1
