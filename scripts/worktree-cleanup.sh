#!/bin/sh
# worktree-cleanup.sh — prune merged feature worktrees and sync the root checkout.
#
# The root agent manual's first hard rule is "worktree, always": feature work
# happens in `worktree/<slug>` on a `<type>/<slug>` branch. That rule opens a
# lifecycle nothing was closing — after the pull request lands, the worktree,
# its local branch, and the diary's "Active worktrees" row are all still there,
# and the root checkout's default branch has gone stale, so the NEXT worktree
# branches from an old base and pays a merge-reconciliation tax later.
#
# This script closes it, conservatively. For each worktree under `worktree/`,
# the worktree and its local branch are removed ONLY when the branch is merged
# into the base ref AND the worktree has no uncommitted changes. Everything else
# is kept and reported with the reason. Nothing is ever force-removed: the whole
# value of the script is that a human can run it without reading it first.
#
# `/worktree-cleanup` is the skill that runs this and then updates the diary —
# the one step a script should not be doing on a human's behalf.
#
# Usage:
#   sh scripts/worktree-cleanup.sh [--dry-run]
#
# Configuration (environment, both optional):
#   WORKTREE_CLEANUP_BASE       the merge target. Default: origin's HEAD branch
#                               (falls back to origin/main if origin has no HEAD
#                               symref — e.g. a bare fixture remote).
#   WORKTREE_CLEANUP_POST_SYNC  a command to run when the base branch moved,
#                               typically a dependency install. EMPTY BY
#                               DEFAULT and deliberately so: the kit does not
#                               know your toolchain, and a guessed install
#                               command that runs after every sync is worse than
#                               a printed reminder. Set it once in your shell
#                               profile or your local workflow article.
#
# THIS FILE IS YOURS. It is not part of the shared layer (see VERSION): it
# encodes one worktree convention, and a project that arranges branches
# differently is expected to edit it rather than diff it against the kit.
#
# POSIX sh and git only. `gh` is used opportunistically for squash-merge
# detection and is not required.
#
# Exit: 0 on success (including "nothing to do"), non-zero if git itself fails.

set -eu

DRY_RUN=0
case "${1:-}" in
--dry-run) DRY_RUN=1 ;;
"") ;;
*)
	echo "worktree-cleanup: unknown argument '$1' (expected --dry-run)" >&2
	exit 2
	;;
esac

say() { printf '%s\n' "$*"; }
run() {
	if [ "$DRY_RUN" = 1 ]; then
		say "  [dry-run] $*"
	else
		"$@"
	fi
}

# Resolve the root checkout even when invoked from inside a linked worktree.
COMMON_DIR=$(git rev-parse --path-format=absolute --git-common-dir) || {
	echo "worktree-cleanup: not inside a git repository." >&2
	exit 2
}
ROOT=$(dirname "$COMMON_DIR")
cd "$ROOT"

# --- the base ref -------------------------------------------------------------
BASE=${WORKTREE_CLEANUP_BASE:-}
if [ -z "$BASE" ]; then
	if origin_head=$(git symbolic-ref --quiet refs/remotes/origin/HEAD 2>/dev/null); then
		BASE=${origin_head#refs/remotes/}
	else
		BASE="origin/main"
	fi
fi
BASE_BRANCH=${BASE##*/}

# is_merged <branch> — merged into BASE, or the head of a merged pull request.
#
# Two tests because they cover two merge methods. Ancestry is exact for merge
# commits and rebases; it is WRONG for squash merges, which rewrite the commits
# so the branch is never an ancestor of anything. The tracker lookup is the
# second opinion that covers those, and it is optional so the script still works
# in a repo with no forge CLI installed.
is_merged() {
	if git merge-base --is-ancestor "$1" "$BASE" 2>/dev/null; then
		return 0
	fi
	if command -v gh >/dev/null 2>&1; then
		pr=$(gh pr list --head "$1" --state merged --json number --jq '.[0].number' 2>/dev/null || true)
		[ -n "$pr" ] && return 0
	fi
	return 1
}

say "==> git fetch --prune origin"
git fetch --prune origin

# --- 1. Fast-forward the root checkout's base branch --------------------------
BASE_MOVED=0
current=$(git branch --show-current)
# "Dirty" here means TRACKED changes only, deliberately. The linked worktrees
# themselves live under `worktree/` in the root checkout, and a project that has
# not gitignored that directory would otherwise look permanently dirty and never
# fast-forward — a silent no-op that is very hard to notice. Untracked files are
# still safe: `git merge --ff-only` refuses rather than overwriting one.
if ! git diff --quiet || ! git diff --cached --quiet; then
	say "!! Root checkout has uncommitted changes — skipping fast-forward. Commit or stash first."
elif [ "$current" != "$BASE_BRANCH" ]; then
	say "!! Root checkout is on '$current', not $BASE_BRANCH — skipping fast-forward."
elif ! git rev-parse --verify --quiet "$BASE" >/dev/null; then
	say "!! $BASE does not exist — skipping fast-forward."
else
	before=$(git rev-parse HEAD)
	after=$(git rev-parse "$BASE")
	if [ "$before" = "$after" ]; then
		say "==> $BASE_BRANCH already up to date with $BASE ($(git rev-parse --short HEAD))"
	else
		say "==> Fast-forwarding $BASE_BRANCH: $(git rev-parse --short "$before") -> $(git rev-parse --short "$after")"
		run git merge --ff-only "$BASE"
		BASE_MOVED=1
	fi
fi

# --- 2. Prune merged, clean worktrees -----------------------------------------
# Newline-delimited accumulators rather than arrays: this is POSIX sh, like the
# rest of the kit's core, so it runs wherever `sh` does.
REMOVED=""
KEPT=""
n_removed=0
n_kept=0

keep() {
	KEPT="$KEPT$1
"
	n_kept=$((n_kept + 1))
}

# The list is materialised to a file first, for two reasons: `git worktree
# remove` mutates the very list being iterated, and in POSIX sh the right-hand
# side of a pipe may run in a SUBSHELL — which would silently discard every
# accumulator below and print an empty summary after doing real work.
listing=$(mktemp) || exit 2
trap 'rm -f "$listing"' EXIT INT TERM HUP
git worktree list --porcelain | awk '/^worktree /{print substr($0, 10)}' >"$listing"

while IFS= read -r wt; do
	[ -n "$wt" ] || continue
	[ "$wt" = "$ROOT" ] && continue
	case "$wt" in
	"$ROOT"/worktree/*) ;;
	*)
		keep "$wt — outside worktree/, skipped"
		continue
		;;
	esac

	branch=$(git -C "$wt" branch --show-current)
	if [ -z "$branch" ]; then
		keep "$wt — detached HEAD, skipped"
	elif [ -n "$(git -C "$wt" status --porcelain)" ]; then
		keep "$wt ($branch) — uncommitted changes"
	elif is_merged "$branch"; then
		say "==> Removing merged worktree $wt ($branch)"
		run git worktree remove "$wt"
		run git branch -D "$branch"
		# Most forges delete the head branch on merge; catch any that survived.
		if git ls-remote --exit-code --heads origin "$branch" >/dev/null 2>&1; then
			run git push origin --delete "$branch"
		fi
		REMOVED="$REMOVED$wt ($branch)
"
		n_removed=$((n_removed + 1))
	else
		keep "$wt ($branch) — not merged into $BASE"
	fi
done <"$listing"

# --- 3. Post-sync hook --------------------------------------------------------
if [ "$BASE_MOVED" = 1 ]; then
	if [ -n "${WORKTREE_CLEANUP_POST_SYNC:-}" ]; then
		say "==> $BASE_BRANCH moved — running WORKTREE_CLEANUP_POST_SYNC"
		run sh -c "$WORKTREE_CLEANUP_POST_SYNC"
	else
		say "==> $BASE_BRANCH moved — reinstall dependencies if your project needs it"
		say "    (set WORKTREE_CLEANUP_POST_SYNC to do it automatically)"
	fi
fi

# --- 4. Summary ---------------------------------------------------------------
say ""
if [ "$DRY_RUN" = 1 ]; then
	say "== worktree-cleanup summary (dry-run — nothing was changed)"
else
	say "== worktree-cleanup summary"
fi
say "$BASE_BRANCH: $(git rev-parse --short HEAD) ($(git log -1 --format=%s HEAD | cut -c1-80))"
say "removed ($n_removed):"
printf '%s' "$REMOVED" | while IFS= read -r r; do
	[ -n "$r" ] && say "  - $r"
done
say "kept ($n_kept):"
printf '%s' "$KEPT" | while IFS= read -r k; do
	[ -n "$k" ] && say "  - $k"
done
say ""
say "Reminder: refresh the 'Active worktrees' row in docs/diary.md — its update"
say "protocol says the Current state block is edited in place, history is not."
