#!/bin/sh
# tests/tdd-pairing-guard.test.sh — the TDD pairing rule as a SEAM.
#
# `scripts/tdd-pairing-guard.sh <base> <head>` is the seam: the rule is reachable
# from a hook, from CI, and from here, which is the whole reason it is a script
# and not four lines inside a hook. What is asserted IS the diff classification,
# so every case drives the real script against a real throwaway repository.
#
# Ported from the vitest suite this rule was extracted from, and extended with
# the cases the kit adds: the configuration seam, and the unconfigured default.
#
# Usage: sh tests/tdd-pairing-guard.test.sh

set -u

KIT=$(cd "$(dirname "$0")/.." && pwd)
GUARD="$KIT/scripts/tdd-pairing-guard.sh"

# shellcheck source=./lib.sh
. "$KIT/tests/lib.sh"
t_init

# A representative project shape: TypeScript under src/ and packages/*/src,
# tests beside the code, plus one policy-data exclusion.
CONFIG_STD=$(
	cat <<'EOF'
GUARD_SOURCE_RE='^(src|packages/[^/]+/src)/.*\.(ts|tsx|mjs)$'
GUARD_SOURCE_EXCLUDE_RE='\.d\.ts$|^src/policy\.json$|^packages/[^/]+/src/config\.mjs$'
GUARD_TEST_RE='(\.|_)(test|spec)\.(ts|tsx|mjs)$|\.feature$'
EOF
)

configure() { t_write "$1" "scripts/guards.config.sh" "$2
"; }

run_guard() {
	_repo=$1
	shift
	(cd "$_repo" && sh "$GUARD" "$@")
}

# A repo with one base commit; the caller commits the head. Sets `repo` (its
# path) and `BASE` (the base sha).
new_repo_with_base() {
	t_repo
	repo=$REPO
	t_write "$repo" "README.md" "base
"
	BASE=$(t_commit "$repo" "chore: base")
}

# ---------------------------------------------------------------------------
banner "Usage and unrunnable ranges"
# ---------------------------------------------------------------------------
new_repo_with_base
configure "$repo" "$CONFIG_STD"

assert_status 2 "no refs at all is a usage error" -- run_guard "$repo"
assert_out_has "usage"
assert_status 2 "one ref is a usage error" -- run_guard "$repo" HEAD
assert_status 2 "an unknown flag is a usage error" -- run_guard "$repo" --nope
assert_status 2 "a range that cannot be diffed exits 2" -- run_guard "$repo" no-such-ref HEAD

# ---------------------------------------------------------------------------
banner "Unconfigured — the default a fresh project inherits"
# ---------------------------------------------------------------------------
# The load-bearing default. A guard that blocked every push in a repo nobody has
# configured yet would be deleted on day one, and a deleted guard checks nothing.
new_repo_with_base
t_write "$repo" "src/thing.ts" "export const a = 1;
"
head=$(t_commit "$repo" "feat: unpaired source change")

configure "$repo" "GUARD_SOURCE_RE=''"
assert_status 0 "an unconfigured guard passes an unpaired source change" -- run_guard "$repo" "$BASE" "$head"
assert_out_has "INACTIVE"
assert_out_has "GUARD_SOURCE_RE"

assert_status 0 "no config file at all behaves the same way" -- sh -c "rm -f '$repo/scripts/guards.config.sh'; cd '$repo' && sh '$GUARD' '$BASE' '$head'"
assert_out_has "INACTIVE"

configure "$repo" "GUARD_SOURCE_RE=''"
assert_status 0 "TDD_PAIRING_GUARD_QUIET=1 silences the warning (one push, one warning)" -- sh -c "cd '$repo' && TDD_PAIRING_GUARD_QUIET=1 sh '$GUARD' '$BASE' '$head'"
assert_out_lacks "INACTIVE"

# Configured source but no way to recognise a test would fail EVERY source
# change. That is a broken config, not a verdict — exit 2, not 1.
configure "$repo" "GUARD_SOURCE_RE='^src/'
GUARD_TEST_RE=''"
assert_status 2 "an empty GUARD_TEST_RE is a configuration error, not a verdict" -- run_guard "$repo" "$BASE" "$head"
assert_out_has "GUARD_TEST_RE"

# ---------------------------------------------------------------------------
banner "The rule itself"
# ---------------------------------------------------------------------------
new_repo_with_base
t_write "$repo" "src/report.ts" "export const a = 1;
"
head=$(t_commit "$repo" "feat: source only")
configure "$repo" "$CONFIG_STD"
assert_status 1 "blocks source changes that carry no test changes" -- run_guard "$repo" "$BASE" "$head"
assert_out_has "src/report.ts"
assert_out_has "source changes with no test changes"

new_repo_with_base
t_write "$repo" "packages/domain/src/report.ts" "export const a = 1;
"
t_write "$repo" "packages/domain/src/report.test.ts" "// test
"
head=$(t_commit "$repo" "feat: source with its test")
configure "$repo" "$CONFIG_STD"
assert_status 0 "passes source changes paired with a test change" -- run_guard "$repo" "$BASE" "$head"

new_repo_with_base
t_write "$repo" "src/report.ts" "export const a = 1;
"
t_write "$repo" "features/thing.feature" "Feature: thing
"
head=$(t_commit "$repo" "feat: source with an executable spec")
configure "$repo" "$CONFIG_STD"
assert_status 0 "accepts an executable spec as the paired test change" -- run_guard "$repo" "$BASE" "$head"

new_repo_with_base
t_write "$repo" "docs/diary.md" "log
"
t_write "$repo" "infra/main.tf" "resource {}
"
t_write "$repo" "app/routes/index.tsx" "export default () => null;
"
head=$(t_commit "$repo" "chore: everything outside the covered trees")
configure "$repo" "$CONFIG_STD"
assert_status 0 "ignores changes outside the configured source trees" -- run_guard "$repo" "$BASE" "$head"

# ---------------------------------------------------------------------------
banner "GUARD_SOURCE_EXCLUDE_RE — the carve-outs"
# ---------------------------------------------------------------------------
new_repo_with_base
t_write "$repo" "src/globals.d.ts" "declare const x: 1;
"
head=$(t_commit "$repo" "chore: a type declaration")
configure "$repo" "$CONFIG_STD"
assert_status 0 "ignores type declaration files" -- run_guard "$repo" "$BASE" "$head"

new_repo_with_base
t_write "$repo" "packages/docs/src/config.mjs" "export default {};
"
head=$(t_commit "$repo" "chore: edit reviewable policy data")
configure "$repo" "$CONFIG_STD"
assert_status 0 "exempts reviewable policy DATA named in the exclude pattern" -- run_guard "$repo" "$BASE" "$head"

# ---------------------------------------------------------------------------
banner "Ranges git itself describes oddly"
# ---------------------------------------------------------------------------
t_repo
repo=$REPO
t_write "$repo" "src/report.ts" "export const a = 1;
"
BASE=$(t_commit "$repo" "feat: seed")
git -C "$repo" mv src/report.ts src/moved.ts
head=$(t_commit "$repo" "refactor: move the module")
configure "$repo" "$CONFIG_STD"
assert_status 0 "ignores a pure rename — a move is not new behavior" -- run_guard "$repo" "$BASE" "$head"

new_repo_with_base
configure "$repo" "$CONFIG_STD"
assert_status 0 "passes an empty range" -- run_guard "$repo" "$BASE" "$BASE"

# ---------------------------------------------------------------------------
banner "The caller's voice — label and hint"
# ---------------------------------------------------------------------------
new_repo_with_base
t_write "$repo" "src/report.ts" "export const a = 1;
"
head=$(t_commit "$repo" "feat: source only")
configure "$repo" "$CONFIG_STD"
assert_status 1 "still fails when a caller names itself" -- run_guard "$repo" "$BASE" "$head" --label pre-push --hint "Bypass with FOO=1."
assert_out_has "x pre-push: source changes with no test changes"
assert_out_has "Bypass with FOO=1."

# ---------------------------------------------------------------------------
banner "Where the configuration comes from"
# ---------------------------------------------------------------------------
# Discovery order: $GUARDS_CONFIG, then the repo root's scripts/guards.config.sh.
# Every case above exercised the second; these two pin the first.
new_repo_with_base
t_write "$repo" "src/report.ts" "export const a = 1;
"
head=$(t_commit "$repo" "feat: source only")
configure "$repo" "GUARD_SOURCE_RE=''"
printf '%s\n' "$CONFIG_STD" >"$SCRATCH/elsewhere.config.sh"

assert_status 1 "GUARDS_CONFIG overrides the repo-root config" -- sh -c "cd '$repo' && GUARDS_CONFIG='$SCRATCH/elsewhere.config.sh' sh '$GUARD' '$BASE' '$head'"
assert_out_has "src/report.ts"

assert_status 2 "a GUARDS_CONFIG that does not exist is an error, not a silent fallback" -- sh -c "cd '$repo' && GUARDS_CONFIG='$SCRATCH/no-such.config.sh' sh '$GUARD' '$BASE' '$head'"
assert_out_has "does not exist"

t_done "tdd-pairing-guard.sh"
