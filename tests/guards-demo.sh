#!/bin/sh
# The guards' acceptance test — and their demo.
#
# K3's claim is one sentence: "a project created from this template gets guards
# that are inert until configured, and that actually block once they are." A
# claim nothing runs is a claim nobody should believe, so this script drives the
# whole path end to end against a throwaway consumer built from this tree:
#
#   1.        bootstrap installs the workflow templates and removes templates/
#   2. GREEN  the first push establishes the branch on the remote
#   3. GREEN  an UNCONFIGURED guard warns and passes on an unpaired source push
#   4.        the consumer configures GUARD_SOURCE_RE in scripts/guards.config.sh
#   5. RED    a source-only push is BLOCKED by the real hook
#   6. GREEN  the same change, paired with a test, pushes
#   7.        PUSH_WITHOUT_TESTS=1 lets a red tree through, loudly
#   8.        PUSH_WITHOUT_DOCS=1 does NOT disable the pairing guard (narrow hatches)
#   9.        the CI twin re-judges the bypassed range and still fails it
#  10.        behavior-delta inventories the branch's contract-artifact deltas
#
# Usage: sh tests/guards-demo.sh

set -u

KIT=$(cd "$(dirname "$0")/.." && pwd)

# shellcheck source=./lib.sh
. "$KIT/tests/lib.sh"
t_init

PROJ="$SCRATCH/demo-project"
REMOTE="$SCRATCH/demo-remote.git"

assert_file() { [ -e "$1" ] && pass "$1 exists" || fail "$1 is missing"; }
assert_no_file() { [ -e "$1" ] && fail "$1 still exists" || pass "$1 is gone"; }

# ---------------------------------------------------------------------------
banner "Setup — simulate 'Use this template'"
# ---------------------------------------------------------------------------
mkdir -p "$PROJ"
cp -R "$KIT/." "$PROJ/"
rm -rf "$PROJ/.git"
cd "$PROJ" || exit 2

git init -q -b main
git config user.name "Guards Demo"
git config user.email "demo@example.invalid"
git config commit.gpgsign false
git add -A
git init -q --bare "$REMOTE"
git remote add origin "$REMOTE"
pass "fresh repo at \$SCRATCH/demo-project with a bare origin"

# ---------------------------------------------------------------------------
banner "1. Bootstrap installs the workflows and takes the templates away"
# ---------------------------------------------------------------------------
assert_status 0 "bootstrap.sh runs" -- sh bootstrap.sh "Guards Demo" "A throwaway project proving the guards guard."
printf '%s\n' "$LAST_OUT" | sed 's/^/      > /'

assert_file ".github/workflows/docs-gate.yml"
assert_file ".github/workflows/tdd-pairing.yml"
# Commit linting ships inert — GitHub Actions reads .yml, not .yml.example.
assert_file ".github/workflows/commitlint.yml.example"
assert_no_file ".github/workflows/commitlint.yml"
# A template repo must not carry its consumers' templates once it IS a consumer.
assert_no_file "templates/workflows"
assert_no_file "templates"
assert_file "scripts/guards.config.sh"
assert_file "scripts/tdd-pairing-guard.sh"
assert_no_file "tests/tdd-pairing-guard.test.sh"
assert_no_file "tests"

assert_status 0 "the docs gate passes on the bootstrapped project" -- sh scripts/check.sh

# ---------------------------------------------------------------------------
banner "2. GREEN — the first push establishes the branch on the remote"
# ---------------------------------------------------------------------------
# Nothing is judged here and that is correct: a brand-new remote branch has no
# base to diff against, and the hook does not block on a guess. The guard starts
# speaking from the second push, which is what the next step shows.
git add -A
git commit -q -m "chore: bootstrap from agentic-sdlc"
assert_status 0 "git push succeeds through the real hook" -- git push origin main

# ---------------------------------------------------------------------------
banner "3. UNCONFIGURED — the guard warns and passes, it does not block"
# ---------------------------------------------------------------------------
# The shipped default, and the load-bearing one. A guard that blocked here would
# be deleted before anyone read what it was for.
mkdir -p src
printf 'export const first = (xs) => xs[0];\n' >src/first.js
git add -A
git commit -q -m "feat(first): the first item, with no test at all"
assert_status 0 "an unpaired source push passes while the guard is unconfigured" -- git push origin main
assert_out_has "INACTIVE"
assert_out_has "GUARD_SOURCE_RE"

# ---------------------------------------------------------------------------
banner "4. The consumer configures its source globs"
# ---------------------------------------------------------------------------
cat >>scripts/guards.config.sh <<'EOF'

# --- configured by the demo --------------------------------------------------
GUARD_SOURCE_RE='^src/.*\.(ts|js)$'
EOF
git add -A
git commit -q -m "chore(guards): declare which trees count as source"
assert_status 0 "the configuring commit itself pushes fine" -- git push origin main
assert_out_lacks "INACTIVE"
pass "the guard stopped warning the moment it was configured"

# ---------------------------------------------------------------------------
banner "5. RED — a source-only push is BLOCKED"
# ---------------------------------------------------------------------------
printf 'export const total = (xs) => xs.length;\n' >src/total.js
git add -A
git commit -q -m "feat(total): count the items"
assert_status 1 "git push is BLOCKED by the pre-push pairing guard" -- git push origin main
assert_out_has "pre-push: source changes with no test changes"
assert_out_has "src/total.js"
assert_out_has "PUSH_WITHOUT_TESTS=1"
BLOCKED_SHA=$(git rev-parse HEAD)

# The remote is unchanged — "blocked" has to mean nothing landed.
remote_head=$(git ls-remote "$REMOTE" refs/heads/main | awk '{print $1}')
if [ "$remote_head" = "$BLOCKED_SHA" ]; then
	fail "the commit reached origin despite the block"
else
	pass "origin/main did not move — the push really was blocked"
fi

# ---------------------------------------------------------------------------
banner "6. GREEN — the same change, paired with a test"
# ---------------------------------------------------------------------------
printf 'import { total } from "./total.js";\n// asserts total([]) === 0\n' >src/total.test.js
git add -A
git commit -q -m "test(total): cover the empty case"
assert_status 0 "git push passes once a test change rides along" -- git push origin main
assert_out_lacks "source changes with no test changes"

remote_head=$(git ls-remote "$REMOTE" refs/heads/main | awk '{print $1}')
if [ "$remote_head" = "$(git rev-parse HEAD)" ]; then
	pass "origin/main advanced to the paired commit"
else
	fail "origin/main did not advance"
fi

# ---------------------------------------------------------------------------
banner "7. The bypass is real, and loud"
# ---------------------------------------------------------------------------
printf 'export const half = (n) => n / 2;\n' >src/half.js
git add -A
git commit -q -m "feat(half): halve a number"
assert_status 1 "the unpaired change is blocked first" -- git push origin main
assert_status 0 "PUSH_WITHOUT_TESTS=1 lets it through" -- sh -c "cd '$PROJ' && PUSH_WITHOUT_TESTS=1 git push origin main"
assert_out_has "BYPASSED"

# ---------------------------------------------------------------------------
banner "8. The two hatches are NARROW — neither one opens the other"
# ---------------------------------------------------------------------------
# A single "skip everything" switch is the one people reach for reflexively, so
# PUSH_WITHOUT_DOCS must not silence the pairing guard.
printf 'export const third = (n) => n / 3;\n' >src/third.js
git add -A
git commit -q -m "feat(third): a third of a number"
assert_status 1 "PUSH_WITHOUT_DOCS=1 still leaves the pairing guard on" -- sh -c "cd '$PROJ' && PUSH_WITHOUT_DOCS=1 git push origin main"
assert_out_has "docs gate BYPASSED"
assert_out_has "source changes with no test changes"

# ---------------------------------------------------------------------------
banner "9. A local bypass only DEFERS — the CI twin re-judges the same range"
# ---------------------------------------------------------------------------
base=$(git rev-parse HEAD~1)
assert_status 1 "the CI guard fails the range the local hatch let through" -- sh -c "
	cd '$PROJ' && BASE_SHA='$base' HEAD_SHA='$(git rev-parse HEAD)' sh scripts/tdd-pairing-guard-ci.sh
"
assert_out_has "x ci:"
assert_out_has "tdd-exempt"

printf '{"pull_request":{"labels":[{"name":"tdd-exempt"}]}}' >"$SCRATCH/event.json"
assert_status 0 "and goes green only with the label, on the record" -- sh -c "
	cd '$PROJ' && BASE_SHA='$base' HEAD_SHA='$(git rev-parse HEAD)' \
		GITHUB_EVENT_PATH='$SCRATCH/event.json' sh scripts/tdd-pairing-guard-ci.sh
"
assert_out_has "::notice"

# ---------------------------------------------------------------------------
banner "10. behavior-delta inventories the branch"
# ---------------------------------------------------------------------------
git checkout -q -b refactor/demo
printf 'export const total = (xs) => xs.length + 0;\n' >src/total.js
printf '\n# a local rule\n' >>AGENTS.md
git add -A
git commit -q -m "refactor(total): tidy the reducer"
assert_status 0 "behavior-delta runs against the branch" -- sh -c "cd '$PROJ' && sh scripts/behavior-delta.sh main"
printf '%s\n' "$LAST_OUT" | sed 's/^/      > /'
# AGENTS.md is a shipped default surface: a standing instruction edited inside a
# commit that claims structure-only work is exactly what the check is for.
assert_out_has "Commit separation"
assert_out_has "AGENTS.md"

t_done "guards demo"
