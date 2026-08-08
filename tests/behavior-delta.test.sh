#!/bin/sh
# tests/behavior-delta.test.sh — self-tests for scripts/behavior-delta.sh.
#
# The script's whole job is reading git history, so a fake would test nothing:
# every case here builds a REAL throwaway repository, commits a synthetic branch
# into it, and runs the script against it.
#
# Two tiers of claim are covered:
#   · the branch-level sections — one per configured contract surface;
#   · the per-COMMIT separation check — a `refactor:`/`style:` commit that also
#     touches a contract artifact, which no branch-level view can see.
#
# Usage: sh tests/behavior-delta.test.sh

set -u

KIT=$(cd "$(dirname "$0")/.." && pwd)
DELTA="$KIT/scripts/behavior-delta.sh"

# shellcheck source=./lib.sh
. "$KIT/tests/lib.sh"
t_init

# A representative contract-artifact configuration: four surfaces, standard test
# and feature patterns.
CONFIG_STD=$(
	cat <<'EOF'
GUARD_TEST_RE='(\.|_)(test|spec)\.(ts|tsx|mjs)$|\.feature$'
BEHAVIOR_DELTA_FEATURE_RE='\.feature$'
BEHAVIOR_DELTA_SURFACES='API surface (docs/api/openapi.yaml)|^docs/api/openapi\.yaml$
Persistence (schema and migrations)|^db/|^docs/db-design\.md$
Domain events|^docs/events\.md$
Security posture (headers, CSP)|^security/'
EOF
)

configure() { t_write "$1" "scripts/guards.config.sh" "$2
"; }

run_delta() { (cd "$1" && sh "$DELTA" main); }

# branch <repo> <name> — start a feature branch off main.
branch() { git -C "$1" checkout -q -b "$2"; }

# The lines of the commit-separation section, or empty when it was not printed.
separation_block() {
	printf '%s\n' "$LAST_OUT" | awk '
		/^## Commit separation/ { inside = 1; next }
		inside && /^## /        { inside = 0 }
		inside                  { print }
	'
}

assert_separation_has() {
	_block=$(separation_block)
	case "$_block" in
	*"$1"*) pass "commit-separation section names '$1'" ;;
	*)
		fail "commit-separation section does not name '$1'"
		printf '%s\n' "$LAST_OUT" | sed 's/^/        | /'
		;;
	esac
}

assert_separation_lacks() {
	_block=$(separation_block)
	case "$_block" in
	*"$1"*)
		fail "commit-separation section should NOT name '$1'"
		printf '%s\n' "$LAST_OUT" | sed 's/^/        | /'
		;;
	*) pass "commit-separation section does not name '$1'" ;;
	esac
}

# ---------------------------------------------------------------------------
banner "Unconfigured — silence is not the same as a clean branch"
# ---------------------------------------------------------------------------
t_repo
repo=$REPO
t_write "$repo" "README.md" "base
"
t_commit "$repo" "chore: base" >/dev/null
branch "$repo" "feat/x"
t_write "$repo" "docs/api/openapi.yaml" "openapi: 3.1.0
"
t_commit "$repo" "feat(api): add a path" >/dev/null
configure "$repo" "BEHAVIOR_DELTA_SURFACES=''"

assert_status 0 "an unconfigured script says so" -- run_delta "$repo"
assert_out_has "No contract artifacts configured"
assert_out_lacks "No contract-artifact deltas"

# ---------------------------------------------------------------------------
banner "Branch-level sections"
# ---------------------------------------------------------------------------
configure "$repo" "$CONFIG_STD"
assert_status 0 "lists an API-surface delta under its own section" -- run_delta "$repo"
assert_out_has "## API surface (docs/api/openapi.yaml)"
assert_out_has "docs/api/openapi.yaml"
assert_out_lacks "No contract-artifact deltas"

t_repo
repo=$REPO
t_write "$repo" "README.md" "base
"
t_commit "$repo" "chore: base" >/dev/null
branch "$repo" "refactor/inert"
t_write "$repo" "src/a.ts" "const b = 1;
"
t_commit "$repo" "refactor(domain): rename a local" >/dev/null
configure "$repo" "$CONFIG_STD"
assert_status 0 "reports no deltas when the branch touches nothing under contract" -- run_delta "$repo"
assert_out_has "No contract-artifact deltas on this branch."

t_repo
repo=$REPO
t_write "$repo" "src/old.test.ts" "// v1
"
t_commit "$repo" "test: existing coverage" >/dev/null
branch "$repo" "chore/tests"
t_write "$repo" "src/old.test.ts" "// v2
"
t_write "$repo" "src/new.test.ts" "// added
"
t_commit "$repo" "test: touch both" >/dev/null
configure "$repo" "$CONFIG_STD"
assert_status 0 "lists an edited existing test but not a newly added one" -- run_delta "$repo"
assert_out_has "## Edited existing tests"
assert_out_has "src/old.test.ts"
assert_out_lacks "src/new.test.ts"

t_repo
repo=$REPO
t_write "$repo" "db/old.sql" "select 1;
"
t_commit "$repo" "feat(db): a query" >/dev/null
branch "$repo" "refactor/move"
git -C "$repo" mv db/old.sql db/new.sql
t_commit "$repo" "refactor(db): move the query" >/dev/null
configure "$repo" "$CONFIG_STD"
assert_status 0 "reports the path that exists at HEAD for a renamed contract file" -- run_delta "$repo"
assert_out_has "db/new.sql"
assert_out_lacks "db/old.sql"

# ---------------------------------------------------------------------------
banner "Commit separation — a refactor commit that is not one"
# ---------------------------------------------------------------------------
t_repo
repo=$REPO
t_write "$repo" "docs/api/openapi.yaml" "openapi: 3.1.0
"
t_commit "$repo" "feat(api): a path" >/dev/null
branch "$repo" "refactor/mixed"
t_write "$repo" "src/a.ts" "const a = 1;
"
t_write "$repo" "docs/api/openapi.yaml" "openapi: 3.1.0
paths: {}
"
t_commit "$repo" "refactor(api): tidy the handler" >/dev/null
mixed_sha=$(t_short "$repo")
configure "$repo" "$CONFIG_STD"
assert_status 0 "flags a refactor commit that also edits a contract artifact" -- run_delta "$repo"
assert_separation_has "$mixed_sha"
assert_separation_has "refactor(api): tidy the handler"
assert_separation_has "docs/api/openapi.yaml"

t_repo
repo=$REPO
t_write "$repo" "security/csp.conf" "default-src
"
t_commit "$repo" "feat(security): a policy" >/dev/null
branch "$repo" "style/mixed"
t_write "$repo" "security/csp.conf" "default-src 'self'
"
t_commit "$repo" "style(security): reformat" >/dev/null
configure "$repo" "$CONFIG_STD"
assert_status 0 "flags a style commit just as it flags a refactor one" -- run_delta "$repo"
assert_separation_has "security/csp.conf"

t_repo
repo=$REPO
t_write "$repo" "README.md" "base
"
t_commit "$repo" "chore: base" >/dev/null
branch "$repo" "feat/honest"
t_write "$repo" "src/a.ts" "export const helper = () => 1;
"
t_commit "$repo" "refactor(domain): rename a local" >/dev/null
t_write "$repo" "docs/api/openapi.yaml" "openapi: 3.1.0
"
t_commit "$repo" "feat(api): add a path" >/dev/null
configure "$repo" "$CONFIG_STD"
assert_status 0 "prints no separation section when every commit is honest about its type" -- run_delta "$repo"
assert_out_lacks "## Commit separation"
# The behavior-changing commit is still on the branch-level list — the section
# narrows the claim, it does not swallow the candidate.
assert_out_has "## API surface"

# ---------------------------------------------------------------------------
banner "Commit separation — the false positives it refuses to raise"
# ---------------------------------------------------------------------------
t_repo
repo=$REPO
t_write "$repo" "db/old.sql" "select 1;
"
t_commit "$repo" "feat(db): a query" >/dev/null
branch "$repo" "refactor/pure-move"
git -C "$repo" mv db/old.sql db/new.sql
t_commit "$repo" "refactor(db): move the query" >/dev/null
configure "$repo" "$CONFIG_STD"
assert_status 0 "does not flag a refactor commit that only renames a contract file" -- run_delta "$repo"
assert_out_lacks "## Commit separation"

t_repo
repo=$REPO
t_write "$repo" "README.md" "base
"
t_commit "$repo" "chore: base" >/dev/null
branch "$repo" "refactor/characterize"
t_write "$repo" "db/schema.test.ts" "// characterization
"
t_commit "$repo" "refactor(db): pin the current shape first" >/dev/null
configure "$repo" "$CONFIG_STD"
assert_status 0 "does not flag a refactor commit that only ADDS a test under a contract path" -- run_delta "$repo"
assert_out_lacks "## Commit separation"

t_repo
repo=$REPO
t_write "$repo" "db/query.ts" "export const oldName = () => 1;
"
t_write "$repo" "db/query.test.ts" "// calls oldName
"
t_commit "$repo" "feat(db): a query and its test" >/dev/null
branch "$repo" "refactor/rename-symbol"
t_write "$repo" "db/query.ts" "export const newName = () => 1;
"
t_write "$repo" "db/query.test.ts" "// calls newName
"
t_commit "$repo" "refactor(db): rename oldName to newName" >/dev/null
configure "$repo" "$CONFIG_STD"
# The SOURCE edit under db/ is the finding; the co-moving unit test must not add
# a second, noisier one — call-site churn is what a rename IS.
assert_status 0 "does not flag the unit test that moved with a rename" -- run_delta "$repo"
assert_separation_has "db/query.ts"
assert_separation_lacks "db/query.test.ts"

t_repo
repo=$REPO
t_write "$repo" "README.md" "base
"
t_commit "$repo" "chore: base" >/dev/null
branch "$repo" "chore/side"
t_write "$repo" "db/a.sql" "select 1;
"
t_commit "$repo" "refactor(db): a query" >/dev/null
git -C "$repo" checkout -q main
branch "$repo" "chore/merge-host"
t_write "$repo" "security/csp.conf" "default-src
"
t_commit "$repo" "wip: not conventional at all" >/dev/null
git -C "$repo" merge -q --no-ff -m "Merge branch 'chore/side'" chore/side
configure "$repo" "$CONFIG_STD"
assert_status 0 "ignores merge commits and subjects that are not Conventional Commits" -- run_delta "$repo"
assert_out_lacks "Merge branch"
assert_out_lacks "wip: not conventional"

# ---------------------------------------------------------------------------
banner "Executable specs are the exception to skipping the test tier"
# ---------------------------------------------------------------------------
t_repo
repo=$REPO
t_write "$repo" "features/x.feature" "Feature: a
"
t_commit "$repo" "feat(e2e): a scenario" >/dev/null
branch "$repo" "refactor/scenario"
t_write "$repo" "features/x.feature" "Feature: a
  Scenario: b
"
t_commit "$repo" "refactor(e2e): tidy the scenario" >/dev/null
configure "$repo" "$CONFIG_STD"
assert_status 0 "does flag a refactor commit that edits an existing scenario" -- run_delta "$repo"
assert_separation_has "features/x.feature"

# ---------------------------------------------------------------------------
banner "The demo, as a test — only the mixed commit is flagged"
# ---------------------------------------------------------------------------
t_repo
repo=$REPO
t_write "$repo" "docs/events.md" "# Events
"
t_commit "$repo" "feat(events): the doc" >/dev/null
branch "$repo" "refactor/two-commits"
t_write "$repo" "src/a.ts" "export const helper = () => 1;
"
t_commit "$repo" "refactor(domain): extract a helper" >/dev/null
clean_sha=$(t_short "$repo")
t_write "$repo" "src/b.ts" "export const b = 1;
"
t_write "$repo" "docs/events.md" "# Events
- report.published
"
t_commit "$repo" "refactor(events): rename the emitter" >/dev/null
mixed_sha=$(t_short "$repo")
configure "$repo" "$CONFIG_STD"
assert_status 0 "flags only the mixed commit on a branch that also carries a clean refactor" -- run_delta "$repo"
assert_separation_has "$mixed_sha"
assert_separation_lacks "$clean_sha"
assert_separation_has "docs/events.md"
# src/b.ts is not a configured surface — the section lists contract artifacts,
# not everything the flagged commit happened to touch.
assert_separation_lacks "src/b.ts"

t_done "behavior-delta.sh"
