#!/bin/sh
# tests/tdd-pairing-guard-ci.test.sh — the CI CALLER of the shared pairing rule.
#
# `scripts/tdd-pairing-guard-ci.sh` with BASE_SHA / HEAD_SHA / GITHUB_EVENT_PATH
# in the environment — exactly what templates/workflows/tdd-pairing.yml hands it.
#
# The caller exists so the two things CI adds on top of the rule — resolving the
# pull request's MERGE-BASE range, and the `tdd-exempt` label escape hatch — are
# runnable and assertable OFF a CI runner. Inline in YAML, the only way to learn
# that the label path works would be to open a pull request and push at it. Here
# the label path is driven against a SIMULATED event payload: the same JSON
# shape GitHub writes to $GITHUB_EVENT_PATH.
#
# Usage: sh tests/tdd-pairing-guard-ci.test.sh

set -u

KIT=$(cd "$(dirname "$0")/.." && pwd)
GUARD_CI="$KIT/scripts/tdd-pairing-guard-ci.sh"

# shellcheck source=./lib.sh
. "$KIT/tests/lib.sh"
t_init

CONFIG_STD=$(
	cat <<'EOF'
GUARD_SOURCE_RE='^(src|packages/[^/]+/src)/.*\.(ts|tsx|mjs)$'
GUARD_SOURCE_EXCLUDE_RE='\.d\.ts$'
GUARD_TEST_RE='(\.|_)(test|spec)\.(ts|tsx|mjs)$|\.feature$'
EOF
)

configure() { t_write "$1" "scripts/guards.config.sh" "$2
"; }

# run_ci <repo> [VAR=VALUE ...] — the guard as CI invokes it. The three env vars
# it reads are cleared first, so a case that omits one is really testing the
# absence rather than inheriting the developer's shell.
run_ci() {
	_repo=$1
	shift
	(cd "$_repo" && env BASE_SHA= HEAD_SHA= GITHUB_EVENT_PATH= "$@" sh "$GUARD_CI")
}

# event_payload <repo> <file> <label>... — the `pull_request` payload GitHub
# writes to $GITHUB_EVENT_PATH, reduced to the one field the caller reads.
event_payload() {
	_repo=$1
	_file=$2
	shift 2
	_labels=""
	for _l in "$@"; do
		[ -n "$_labels" ] && _labels="$_labels,"
		_labels="$_labels{\"name\":\"$_l\"}"
	done
	printf '{"pull_request":{"number":7,"labels":[%s]}}' "$_labels" >"$_repo/$_file"
	printf '%s' "$_repo/$_file"
}

# pr_repo <pr-file> — a pull-request-shaped history: `main` forks and the PR
# branch commits <pr-file>. Sets `repo`, `BASE` (the base branch tip) and `HEAD`.
pr_repo() {
	t_repo
	repo=$REPO
	t_write "$repo" "README.md" "fork
"
	BASE=$(t_commit "$repo" "chore: fork point")
	git -C "$repo" checkout -q -b pr
	t_write "$repo" "$1" "// pr work
"
	HEAD=$(t_commit "$repo" "feat: pr work")
	git -C "$repo" checkout -q main
	configure "$repo" "$CONFIG_STD"
}

# ---------------------------------------------------------------------------
banner "A gate that cannot run must not report green"
# ---------------------------------------------------------------------------
pr_repo "docs/x.md"
assert_status 2 "no BASE_SHA/HEAD_SHA in the environment is a usage error" -- run_ci "$repo"
assert_out_has "BASE_SHA"

assert_status 2 "an unresolvable merge base exits 2, it does not guess" -- run_ci "$repo" \
	BASE_SHA=0000000000000000000000000000000000000000 HEAD_SHA="$HEAD"

# ---------------------------------------------------------------------------
banner "The rule, over the pull request's range"
# ---------------------------------------------------------------------------
pr_repo "src/report.ts"
assert_status 1 "fails the PR whose source changes carry no test changes" -- run_ci "$repo" \
	BASE_SHA="$BASE" HEAD_SHA="$HEAD"
assert_out_has "src/report.ts"
assert_out_has "source changes with no test changes"
assert_out_has "x ci:"
assert_out_has "tdd-exempt"

t_write "$repo" "src/report.test.ts" "// test
"
git -C "$repo" checkout -q pr
t_write "$repo" "src/report.test.ts" "// test
"
HEAD=$(t_commit "$repo" "test: pair it")
git -C "$repo" checkout -q main
assert_status 0 "passes the PR whose source changes are paired with a test change" -- run_ci "$repo" \
	BASE_SHA="$BASE" HEAD_SHA="$HEAD"

# The merge base, not BASE_SHA itself. `main` advances after the fork with an
# unpaired EDIT to a source file that already existed — so a plain
# `git diff <main-head> <pr-head>` reports it as MODIFIED (the PR's tree still
# holds the fork version) and would fail a docs-only pull request that never
# went near it.
#
# The pre-existing file matters: had `main` ADDED the file instead, the same
# plain diff would report a deletion, which --diff-filter=ACM drops — and the
# test would pass against a guard that ignored the merge base entirely.
t_repo
repo=$REPO
t_write "$repo" "src/other.ts" "// v1, present before the PR forked
"
t_commit "$repo" "feat: a source file that predates the PR" >/dev/null
git -C "$repo" checkout -q -b pr
t_write "$repo" "docs/diary.md" "log
"
HEAD=$(t_commit "$repo" "docs: a diary entry")
git -C "$repo" checkout -q main
t_write "$repo" "src/other.ts" "// v2, edited on main with no test
"
BASE=$(t_commit "$repo" "feat: main moves on without a test")
configure "$repo" "$CONFIG_STD"

assert_status 0 "judges the MERGE-BASE range — an unpaired commit on main is not the PR's fault" -- run_ci "$repo" \
	BASE_SHA="$BASE" HEAD_SHA="$HEAD"
assert_out_lacks "other.ts"

# ---------------------------------------------------------------------------
banner "The tdd-exempt label — an override on the record"
# ---------------------------------------------------------------------------
pr_repo "src/report.ts"
payload=$(event_payload "$repo" "event.json" "ready-for-agent" "tdd-exempt")
assert_status 0 "an exempt PR goes green with a visible notice" -- run_ci "$repo" \
	BASE_SHA="$BASE" HEAD_SHA="$HEAD" GITHUB_EVENT_PATH="$payload"
assert_out_has "::notice"
assert_out_has "tdd-exempt"
assert_out_has "was NOT enforced"

payload=$(event_payload "$repo" "other.json" "ready-for-agent" "tdd-exempt-ish")
assert_status 1 "a PR carrying only other labels is not exempt" -- run_ci "$repo" \
	BASE_SHA="$BASE" HEAD_SHA="$HEAD" GITHUB_EVENT_PATH="$payload"

payload=$(event_payload "$repo" "none.json")
assert_status 1 "a PR with no labels at all is not exempt" -- run_ci "$repo" \
	BASE_SHA="$BASE" HEAD_SHA="$HEAD" GITHUB_EVENT_PATH="$payload"

# ---------------------------------------------------------------------------
banner "The hatch fails CLOSED"
# ---------------------------------------------------------------------------
# Every way of not being able to read the payload must mean "not exempt". A
# parser that errors into the green branch is a gate anyone can switch off by
# corrupting a file.
assert_status 1 "an absent payload file is not exempt" -- run_ci "$repo" \
	BASE_SHA="$BASE" HEAD_SHA="$HEAD" GITHUB_EVENT_PATH="$repo/no-such-event.json"

printf '{ not json' >"$repo/malformed.json"
assert_status 1 "a malformed payload is not exempt" -- run_ci "$repo" \
	BASE_SHA="$BASE" HEAD_SHA="$HEAD" GITHUB_EVENT_PATH="$repo/malformed.json"

printf '{"pull_request":{"labels":"tdd-exempt"}}' >"$repo/wrong-shape.json"
assert_status 1 "a payload of the wrong shape is not exempt" -- run_ci "$repo" \
	BASE_SHA="$BASE" HEAD_SHA="$HEAD" GITHUB_EVENT_PATH="$repo/wrong-shape.json"

# The one node dependency in the kit's core lives in this hatch. Without a JSON
# parser on PATH the lookup must fail closed too — never "exempt by accident".
# A minimal PATH built by hand, rather than a stripped one: "no node" has to be
# a fact about this fixture, not a bet on where the machine keeps its binaries.
mkdir -p "$SCRATCH/minbin"
for tool in sh git dirname sed grep; do
	p=$(command -v "$tool") && ln -sf "$p" "$SCRATCH/minbin/$tool"
done
if PATH="$SCRATCH/minbin" command -v node >/dev/null 2>&1; then
	fail "fixture is wrong: node is still reachable on the minimal PATH"
else
	payload=$(event_payload "$repo" "exempt.json" "tdd-exempt")
	assert_status 1 "no JSON parser on PATH means not exempt, not exempt-by-default" -- sh -c "
		cd '$repo' && PATH='$SCRATCH/minbin' BASE_SHA='$BASE' HEAD_SHA='$HEAD' \
			GITHUB_EVENT_PATH='$payload' sh '$GUARD_CI'
	"
fi

# ---------------------------------------------------------------------------
banner "Exemption short-circuits before the range is judged"
# ---------------------------------------------------------------------------
pr_repo "docs/diary.md"
payload=$(event_payload "$repo" "event.json" "tdd-exempt")
assert_status 0 "an exempt PR stays green even when the range was fine anyway" -- run_ci "$repo" \
	BASE_SHA="$BASE" HEAD_SHA="$HEAD" GITHUB_EVENT_PATH="$payload"

# ---------------------------------------------------------------------------
banner "The unconfigured default reaches CI too"
# ---------------------------------------------------------------------------
pr_repo "src/report.ts"
configure "$repo" "GUARD_SOURCE_RE=''"
assert_status 0 "an unconfigured guard does not fail anybody's CI either" -- run_ci "$repo" \
	BASE_SHA="$BASE" HEAD_SHA="$HEAD"
assert_out_has "INACTIVE"

t_done "tdd-pairing-guard-ci.sh"
