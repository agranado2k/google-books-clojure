#!/bin/sh
# K5's acceptance test — the adapters tree.
#
# An adapter is reference material, not mechanism: nothing in `adapters/` runs
# in this repo, and the kit has no Node project to run it against. That is
# exactly why it needs a test. Two claims are made about this tree, both of them
# the kind that rot silently:
#
#   A. THE FILES ARE WELL-FORMED. Every shell file parses under `sh -n`, every
#      module parses under `node --check`, every config file really sets the
#      variables the guards read, and every regex is an ERE `grep -E` accepts.
#      A worked example with a syntax error is worse than no example: it is
#      copied, it fails, and the reader blames their own repo.
#
#   B. IT ARRIVES IN A CONSUMER DORMANT. bootstrap.sh does not copy out of
#      `adapters/`, does not delete it, and installs no workflow from it — and
#      the docs gate stays green with it present. That is the K5 decision
#      (adapters/node-ts/INSTALL.md, "Why bootstrap.sh does not touch this
#      directory") stated as a check rather than as prose, per shared
#      invariant §8.
#
# What this CANNOT prove is stated where it belongs, in INSTALL.md's
# "What is verified, and what is not": no Stryker run, no promptfoo run, no
# workflow parsed by GitHub. Nothing here pretends otherwise.
#
# Usage: sh tests/adapters-demo.sh

set -u

KIT=$(cd "$(dirname "$0")/.." && pwd)
SCRATCH=$(mktemp -d) || exit 2
PROJ="$SCRATCH/demo-project"

trap 'rm -rf "$SCRATCH"' EXIT INT TERM HUP

failures=0
HAVE_NODE=0
command -v node >/dev/null 2>&1 && HAVE_NODE=1

banner() { printf '\n=== %s ===\n' "$*"; }
pass() { printf '  ok    %s\n' "$*"; }
skip() { printf '  skip  %s\n' "$*"; }
fail() {
	printf '  FAIL  %s\n' "$*"
	failures=$((failures + 1))
}

check() {
	# check <label> -- <command...>
	_label=$1
	shift 2
	if out=$("$@" 2>&1); then
		pass "$_label"
	else
		fail "$_label"
		printf '%s\n' "$out" | sed 's/^/        | /'
	fi
}

cd "$KIT" || exit 2

# ---------------------------------------------------------------------------
banner "A1. Every adapter shell file parses (sh -n)"
# ---------------------------------------------------------------------------
# `.example` files included: they are copied verbatim into scripts/ and sourced
# from there, so a syntax error in one is a syntax error in the consumer.
found=0
for f in $(find adapters -name '*.sh' -o -name '*.sh.example' | sort); do
	found=$((found + 1))
	check "sh -n $f" -- sh -n "$f"
done
[ "$found" -gt 0 ] && pass "$found shell file(s) checked" ||
	fail "no shell files found under adapters/ — did the tree move?"

# ---------------------------------------------------------------------------
banner "A2. Every adapter module parses (node --check)"
# ---------------------------------------------------------------------------
if [ "$HAVE_NODE" = 1 ]; then
	found=0
	for f in $(find adapters \( -name '*.mjs' -o -name '*.js' -o -name '*.mjs.example' \) | sort); do
		found=$((found + 1))
		# node --check reads the module goal from the extension, and `.example`
		# has none it knows. Copy to a real extension first.
		tmp="$SCRATCH/$(basename "${f%.example}")"
		cp "$f" "$tmp"
		check "node --check $f" -- node --check "$tmp"
	done
	[ "$found" -gt 0 ] && pass "$found module(s) checked" ||
		fail "no modules found under adapters/ — did the tree move?"
else
	skip "node is not available — module syntax checks skipped"
fi

# ---------------------------------------------------------------------------
banner "A3. The guards config example really configures the guards"
# ---------------------------------------------------------------------------
# Sourcing it and reading the variables back is the only honest check: a config
# file that parses but sets nothing would leave the pairing guard INACTIVE while
# looking installed, which is the failure mode the whole file exists to prevent.
GUARDS_EXAMPLE="adapters/node-ts/guards.config.sh.example"
(
	# shellcheck source=/dev/null
	. "./$GUARDS_EXAMPLE"
	[ -n "${GUARD_SOURCE_RE:-}" ] || exit 1
	[ -n "${GUARD_TEST_RE:-}" ] || exit 2
	[ -n "${GUARD_SOURCE_EXCLUDE_RE:-}" ] || exit 3
	[ -n "${BEHAVIOR_DELTA_SURFACES:-}" ] || exit 4
	# Valid EREs, judged by the same grep the guards use.
	printf 'x\n' | grep -E "$GUARD_SOURCE_RE" >/dev/null 2>&1
	[ $? -gt 1 ] && exit 5
	printf 'x\n' | grep -E "$GUARD_TEST_RE" >/dev/null 2>&1
	[ $? -gt 1 ] && exit 6
	printf 'x\n' | grep -E "$GUARD_SOURCE_EXCLUDE_RE" >/dev/null 2>&1
	[ $? -gt 1 ] && exit 7
	exit 0
)
case $? in
0) pass "$GUARDS_EXAMPLE sets all four settings, and its regexes are valid ERE" ;;
1) fail "$GUARDS_EXAMPLE leaves GUARD_SOURCE_RE empty — the guard would stay INACTIVE" ;;
2) fail "$GUARDS_EXAMPLE leaves GUARD_TEST_RE empty — the guard would exit 2" ;;
3) fail "$GUARDS_EXAMPLE leaves GUARD_SOURCE_EXCLUDE_RE empty" ;;
4) fail "$GUARDS_EXAMPLE leaves BEHAVIOR_DELTA_SURFACES empty" ;;
*) fail "$GUARDS_EXAMPLE carries a regex grep -E rejects" ;;
esac

# The example must match the trees it documents, and must NOT match a test file
# (the guard subtracts tests, but a SOURCE pattern that swallows them is still
# a smell worth catching here).
(
	# shellcheck source=/dev/null
	. "./$GUARDS_EXAMPLE"
	printf 'packages/domain/src/policy.ts\n' | grep -qE "$GUARD_SOURCE_RE" || exit 1
	printf 'apps/web/src/handler.ts\n' | grep -qE "$GUARD_SOURCE_RE" || exit 2
	printf 'docs/diary.md\n' | grep -qE "$GUARD_SOURCE_RE" && exit 3
	printf 'packages/domain/src/policy.test.ts\n' | grep -qE "$GUARD_TEST_RE" || exit 4
	printf 'packages/domain/src/index.ts\n' | grep -qE "$GUARD_SOURCE_EXCLUDE_RE" || exit 5
	exit 0
)
case $? in
0) pass "its patterns classify a source file, an app file, a doc, a test and a barrel correctly" ;;
*) fail "its patterns misclassify at least one of: source / app / doc / test / barrel" ;;
esac

# ---------------------------------------------------------------------------
banner "A4. The mutation config example really configures the diagnostic"
# ---------------------------------------------------------------------------
MUTATION_EXAMPLE="adapters/node-ts/mutation/mutation.config.sh.example"
(
	# shellcheck source=/dev/null
	. "./$MUTATION_EXAMPLE"
	[ -n "${MUTATION_PKG_DIR:-}" ] || exit 1
	[ -n "${MUTATION_PKG_NAME:-}" ] || exit 2
	[ -n "${MUTATION_REPORT:-}" ] || exit 3
	printf 'x\n' | grep -E "${MUTATION_SRC_RE:-}" >/dev/null 2>&1
	[ $? -gt 1 ] && exit 4
	printf 'src/policy.ts\n' | grep -qE "$MUTATION_SRC_RE" || exit 5
	printf 'src/index.ts\n' | grep -qE "$MUTATION_SRC_EXCLUDE_RE" || exit 6
	exit 0
)
case $? in
0) pass "$MUTATION_EXAMPLE sets a scope, a report path, and patterns that classify correctly" ;;
*) fail "$MUTATION_EXAMPLE is incomplete or its patterns misclassify" ;;
esac

# mutation-delta.sh must REFUSE to run with no config rather than guessing a
# package — a silent default here would measure the wrong tree and report a
# score for it.
out=$(MUTATION_CONFIG=/nonexistent/mutation.config.sh sh adapters/node-ts/mutation/mutation-delta.sh --list 2>&1)
if [ $? = 2 ] && printf '%s' "$out" | grep -q 'does not exist'; then
	pass "mutation-delta.sh errors (exit 2) on an explicit config that does not exist"
else
	fail "mutation-delta.sh did not reject a missing explicit MUTATION_CONFIG"
	printf '%s\n' "$out" | sed 's/^/        | /'
fi

# ---------------------------------------------------------------------------
banner "B. Setup — simulate 'Use this template'"
# ---------------------------------------------------------------------------
mkdir -p "$PROJ"
cp -R "$KIT/." "$PROJ/"
rm -rf "$PROJ/.git"
cd "$PROJ" || exit 2

git init -q -b main
git config user.name "Adapters Demo"
git config user.email "demo@example.invalid"
git config commit.gpgsign false
git add -A
pass "fresh repo at \$SCRATCH/demo-project"

# ---------------------------------------------------------------------------
banner "B1. bootstrap leaves adapters/ INTACT"
# ---------------------------------------------------------------------------
if out=$(sh bootstrap.sh "Adapters Demo" "A throwaway project proving adapters arrive dormant." 2>&1); then
	pass "bootstrap.sh runs"
else
	fail "bootstrap.sh failed"
	printf '%s\n' "$out" | sed 's/^/        | /'
fi

for f in \
	adapters/README.md \
	adapters/node-ts/README.md \
	adapters/node-ts/INSTALL.md \
	adapters/node-ts/guards.config.sh.example \
	adapters/node-ts/mutation/mutation-delta.sh \
	adapters/node-ts/mutation/mutation-delta-report.mjs \
	adapters/node-ts/mutation/stryker.config.mjs.example \
	adapters/node-ts/evals/promptfooconfig.yaml \
	adapters/node-ts/workflows/mutation-delta.yml \
	adapters/node-ts/workflows/prompt-evals.yml; do
	[ -f "$f" ] && pass "$f survived bootstrap" || fail "$f is missing after bootstrap"
done

# The whole tree, byte for byte: an adapter that arrived STAMPED would mean
# bootstrap had quietly claimed it as its own.
if diff -r "$KIT/adapters" adapters >/dev/null 2>&1; then
	pass "adapters/ is byte-identical to the kit's — nothing stamped, nothing removed"
else
	fail "adapters/ differs from the kit's copy after bootstrap"
	diff -r "$KIT/adapters" adapters 2>&1 | sed 's/^/        | /'
fi

# ---------------------------------------------------------------------------
banner "B2. Nothing from adapters/ was installed or activated"
# ---------------------------------------------------------------------------
for wf in mutation-delta.yml prompt-evals.yml; do
	[ -e ".github/workflows/$wf" ] &&
		fail ".github/workflows/$wf was installed — adapters must not auto-activate" ||
		pass ".github/workflows/$wf was NOT installed (dormant, as designed)"
done

# The guards must still be INACTIVE: the adapter ships a filled-in config, and
# if bootstrap ever copied it over scripts/guards.config.sh it would be
# enforcing a layout this project does not have.
if grep -q "^GUARD_SOURCE_RE=''" scripts/guards.config.sh; then
	pass "scripts/guards.config.sh is still the kit's unconfigured copy"
else
	fail "scripts/guards.config.sh was replaced — the adapter's config leaked into the project"
fi

# ---------------------------------------------------------------------------
banner "B3. The docs gate stays green with adapters/ present"
# ---------------------------------------------------------------------------
# The gate scans EVERY file in the repo for unstamped placeholders, adapters
# included. A double-brace mark in an adapter file would fail a consumer's gate
# on day one, for a directory they never touched.
if out=$(sh scripts/check.sh 2>&1); then
	pass "scripts/check.sh passes with adapters/ in the tree"
else
	fail "scripts/check.sh fails with adapters/ in the tree"
	printf '%s\n' "$out" | sed 's/^/        | /'
fi

if [ "$HAVE_NODE" = 1 ]; then
	if out=$(DOCS_CHECK_NO_NODE=1 sh scripts/check.sh 2>&1); then
		pass "the POSIX fallback passes too"
	else
		fail "the POSIX fallback fails with adapters/ in the tree"
		printf '%s\n' "$out" | sed 's/^/        | /'
	fi
fi

# ---------------------------------------------------------------------------
printf '\n'
if [ "$failures" = 0 ]; then
	printf '  ALL GREEN — adapters demo\n'
	exit 0
fi
printf '  %s check(s) failed — adapters demo\n' "$failures"
exit 1
