#!/bin/sh
# mutation-delta.sh — the DIFFERENTIAL mutation diagnostic.
#
# The TDD pairing guard proves a test CHANGED. It cannot prove the test is
# load-bearing: a test that asserts nothing pairs just as well as one that
# asserts everything. This script answers that second question for one branch.
#
# A full mutation run over one small pure package is ~1000 mutants and the best
# part of a minute on a warm laptop — the right shape for an occasional
# calibration run and the wrong shape for a review, which cares about ONE
# branch. So this narrows Stryker's `--mutate` to the package source the branch
# actually changed, and prints the summary a reviewer reads instead of an HTML
# report they will not open:
#
#   - the mutation score over just those files;
#   - every surviving mutant with file:line and the mutator, so it can be
#     opened and read;
#   - or, when the branch changed nothing in scope, a one-line skip.
#
# IT IS A DIAGNOSTIC, NEVER A GATE. It exits 0 whatever the score — the same
# call `thresholds.break: null` makes in stryker.config.mjs. A surviving mutant
# is a question for a human, and there are two legitimate answers: strengthen
# the test, or state why the mutant is equivalent and cannot be killed. A gate
# admits only the first, which is how "make the test ask for less" becomes the
# path of least resistance.
#
# Usage: mutation-delta.sh [--list] [<base-ref>]        (default: origin/main)
#   --list   print the resolved mutate scope and stop — no Stryker run. What the
#            script WOULD measure, in the time it takes to run `git diff`.
#
# stdout is the summary and nothing else; Stryker's own chatter goes to stderr,
# so a caller can capture the review-consumable part with a plain `$(...)`.
#
# Policy lives in mutation.config.sh (see mutation.config.sh.example). This file
# is mechanism and holds no knowledge of your layout.

set -eu

list_only=0
base_ref=origin/main
for arg in "$@"; do
	case $arg in
	--list) list_only=1 ;;
	-*)
		echo "unknown option: $arg" >&2
		exit 2
		;;
	*) base_ref=$arg ;;
	esac
done

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

root=$(git rev-parse --show-toplevel 2>/dev/null) || {
	echo "mutation-delta: not inside a git repository." >&2
	exit 1
}

# --- config resolution ------------------------------------------------------
# Same order, and the same reasoning, as the kit's scripts/guards.lib.sh: an
# explicitly named file that does not exist is an ERROR, because the caller said
# which policy to run and silently running another one is worse than stopping.
#
# Unlike the guards, a MISSING config here IS an error: an unconfigured pairing
# guard can sensibly warn and pass, but there is no useful default answer to
# "which package should I mutate".
if [ -n "${MUTATION_CONFIG:-}" ]; then
	[ -f "$MUTATION_CONFIG" ] || {
		echo "mutation-delta: MUTATION_CONFIG=$MUTATION_CONFIG does not exist." >&2
		exit 2
	}
	# shellcheck source=/dev/null
	. "$MUTATION_CONFIG"
elif [ -f "$root/scripts/mutation.config.sh" ]; then
	# shellcheck source=/dev/null
	. "$root/scripts/mutation.config.sh"
elif [ -f "$here/mutation.config.sh" ]; then
	# shellcheck source=/dev/null
	. "$here/mutation.config.sh"
else
	echo "mutation-delta: no mutation.config.sh found." >&2
	echo "  Looked at: \$MUTATION_CONFIG, $root/scripts/mutation.config.sh, $here/mutation.config.sh" >&2
	echo "  Copy the example: cp adapters/node-ts/mutation/mutation.config.sh.example scripts/mutation.config.sh" >&2
	exit 2
fi

pkg_dir=${MUTATION_PKG_DIR:-}
pkg_name=${MUTATION_PKG_NAME:-}
src_re=${MUTATION_SRC_RE:-'\.ts$'}
src_exclude_re=${MUTATION_SRC_EXCLUDE_RE:-}
exec_tpl=${MUTATION_EXEC:-}
report_rel=${MUTATION_REPORT:-reports/mutation/mutation.json}

[ -n "$pkg_dir" ] || {
	echo "mutation-delta: MUTATION_PKG_DIR is empty — say which package to mutate." >&2
	exit 2
}

base=$(git merge-base "$base_ref" HEAD) || {
	echo "mutation-delta: cannot resolve merge-base($base_ref, HEAD)." >&2
	exit 1
}

# --- scope ------------------------------------------------------------------
# `--diff-filter=d` drops deletions: a file that no longer exists at HEAD cannot
# be mutated, and handing it to `--mutate` would make Stryker match nothing.
# The exclusions mirror stryker.config.mjs's own `mutate` list, so the
# differential run measures the same surface the full run does, just less of it.
changed=$(git diff --name-only --diff-filter=d "$base" HEAD -- "$pkg_dir/src" | grep -E "$src_re" || true)
if [ -n "$src_exclude_re" ]; then
	changed=$(printf '%s\n' "$changed" | grep -Ev "$src_exclude_re" || true)
fi
# Package-relative, because that is what Stryker's `--mutate` is resolved
# against when the run happens inside the package.
changed=$(printf '%s\n' "$changed" | sed "s|^$pkg_dir/||" | grep -v '^$' | sort -u || true)

echo "# Mutation delta — $(git rev-parse --abbrev-ref HEAD) vs $base_ref (merge-base $(git rev-parse --short "$base"))"

if [ -z "$changed" ]; then
	printf '\nNo mutable source changed on this branch — mutation run skipped.\n'
	exit 0
fi

file_count=$(printf '%s\n' "$changed" | wc -l | tr -d ' ')
printf '\n## Scope (%s changed source file(s) in %s)\n' "$file_count" "$pkg_dir"
printf '%s\n' "$changed" | sed 's/^/  /'

[ "$list_only" -eq 1 ] && exit 0

# Stryker's `--mutate` takes a comma-separated list and OVERRIDES the config's,
# which is exactly the narrowing wanted here.
mutate=$(printf '%s\n' "$changed" | paste -sd, -)

# Only the `json` reporter: `clear-text` and `progress` would interleave with
# the summary this script's stdout is supposed to be. Everything Stryker says
# goes to stderr; the parsed answer comes back on stdout below.
report="$root/$pkg_dir/$report_rel"
rm -f "$report"

# The package-manager prefix is a template from a config file this repo owns —
# `%s` is the package name — and it is deliberately word-split below, because it
# is a plain command prefix rather than a single argument.
# shellcheck disable=SC2059
runner=$(printf "$exec_tpl" "$pkg_name")

printf '\n' >&2
status=0
if [ -n "$runner" ]; then
	# Workspace repo: the prefix routes the run into the package from the root.
	# shellcheck disable=SC2086
	$runner stryker run \
		--mutate "$mutate" \
		--reporters json \
		--allowEmpty >&2 || status=$?
else
	# Single-package repo: nothing to filter, so run from inside the package.
	(cd "$root/$pkg_dir" && npx stryker run \
		--mutate "$mutate" \
		--reporters json \
		--allowEmpty) >&2 || status=$?
fi

if [ ! -f "$report" ]; then
	printf '\nStryker produced no report (exit %s) — see the run output above.\n' "$status"
	printf 'Expected it at %s.\n' "$report"
	printf 'If the run itself looked fine, check that stryker.config.mjs does NOT set\n'
	printf '`jsonReporter.fileName` to something other than MUTATION_REPORT — this script\n'
	printf 'reads the path the config file names and nothing else.\n'
	exit 0 # diagnostic, never a gate: a failed run is reported, not raised
fi

printf '\n'
node "$here/mutation-delta-report.mjs" "$report"
