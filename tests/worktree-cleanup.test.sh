#!/bin/sh
# tests/worktree-cleanup.test.sh — the pruning rule, against real repositories.
#
# The script decides, per worktree, between "remove this and its branch" and
# "keep it and say why". Both outcomes are destructive to get wrong in opposite
# directions, so every fixture here is a REAL repo with a REAL bare remote and
# REAL linked worktrees: faking the git state would fake the test.
#
# Usage: sh tests/worktree-cleanup.test.sh

set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SCRIPT="$ROOT/scripts/worktree-cleanup.sh"

. "$ROOT/tests/lib.sh"

t_init

# --- fixture builders --------------------------------------------------------

# wt_fixture — a repo on `main`, pushed to a bare origin with a HEAD symref, and
# `worktree/` gitignored (the layout the root manual's first hard rule asks for).
wt_fixture() {
	t_repo
	REMOTE="$SCRATCH/remote.$$.$(basename "$REPO").git"
	git init -q --bare -b main "$REMOTE"
	git -C "$REPO" remote add origin "$REMOTE"
	printf 'worktree/\n' >"$REPO/.gitignore"
	git -C "$REPO" add -A
	git -C "$REPO" commit -q -m "chore: ignore the worktree tree"
	git -C "$REPO" push -q -u origin main
	git -C "$REPO" remote set-head origin main >/dev/null
}

# wt_branch <slug> — a linked worktree at worktree/<slug> on feat/<slug>, with
# one commit on it. Branched from origin/main rather than the root checkout's
# HEAD, so that several fixtures can land in sequence without colliding — the
# root checkout is deliberately left stale, which is the situation the script
# exists to fix.
wt_branch() {
	git -C "$REPO" worktree add -q "worktree/$1" -b "feat/$1" origin/main
	printf '%s\n' "$1" >"$REPO/worktree/$1/$1.txt"
	git -C "$REPO/worktree/$1" add -A
	git -C "$REPO/worktree/$1" commit -q -m "feat: $1"
}

# wt_land <slug> — simulate the pull request landing: push the branch's tip onto
# origin/main, so it becomes an ancestor of the base ref.
wt_land() {
	git -C "$REPO" push -q origin "feat/$1:main"
	git -C "$REPO" fetch -q origin
}

# wt_run [args…] — run the script with the fixture repo as cwd.
wt_run() {
	t_run env WC_REPO="$REPO" WC_SCRIPT="$SCRIPT" \
		sh -c 'cd "$WC_REPO" && sh "$WC_SCRIPT" "$@"' -- "$@"
}

wt_has_worktree() { git -C "$REPO" worktree list | grep -q "worktree/$1"; }
wt_has_branch() { git -C "$REPO" branch --list "feat/$1" | grep -q .; }

# ---------------------------------------------------------------------------
banner "A merged, clean worktree is pruned; everything else is kept"
# ---------------------------------------------------------------------------
wt_fixture
wt_branch landed
wt_land landed
wt_branch wip # committed, never landed
wt_branch messy
wt_land messy                                          # landed…
printf 'scratch\n' >"$REPO/worktree/messy/scratch.txt" # …but has uncommitted work

wt_run
[ "$LAST_STATUS" = 0 ] && pass "the script exits 0 (exit 0)" ||
	fail "the script exited $LAST_STATUS"

wt_has_worktree landed && fail "the merged worktree was not removed" ||
	pass "the merged, clean worktree is gone"
wt_has_branch landed && fail "the merged branch survived" ||
	pass "its local branch is gone too"

wt_has_worktree wip && pass "the unmerged worktree is kept" ||
	fail "an unmerged worktree was removed — that is data loss"
assert_out_has "not merged into"

# `messy` IS merged — it is kept only because of the untracked file in it, which
# is the case worth pinning: "merged" alone must never be sufficient.
wt_has_worktree messy && pass "a merged worktree with uncommitted work is kept" ||
	fail "a worktree with uncommitted changes was removed — that is data loss"
assert_out_has "uncommitted changes"

# The remote branch goes too, for the case a forge did not delete it on merge.
git -C "$REPO" ls-remote --exit-code --heads origin "feat/landed" >/dev/null 2>&1 &&
	fail "the merged remote branch survived" ||
	pass "the merged remote branch is deleted"

# ---------------------------------------------------------------------------
banner "--dry-run changes nothing"
# ---------------------------------------------------------------------------
wt_fixture
wt_branch landed
wt_land landed

wt_run --dry-run
assert_out_has "[dry-run]"
assert_out_has "nothing was changed"
wt_has_worktree landed && pass "the worktree is still there after --dry-run" ||
	fail "--dry-run removed a worktree"
wt_has_branch landed && pass "the branch is still there after --dry-run" ||
	fail "--dry-run deleted a branch"
[ "$(git -C "$REPO" rev-parse HEAD)" = "$(git -C "$REPO" rev-parse main)" ] &&
	pass "the base branch did not move under --dry-run" ||
	fail "--dry-run fast-forwarded the root checkout"

# ---------------------------------------------------------------------------
banner "The root checkout fast-forwards, and only then runs the post-sync hook"
# ---------------------------------------------------------------------------
wt_fixture
before=$(git -C "$REPO" rev-parse HEAD)
wt_branch landed
wt_land landed

WORKTREE_CLEANUP_POST_SYNC='echo POST_SYNC_MARKER'
export WORKTREE_CLEANUP_POST_SYNC
wt_run
assert_out_has "Fast-forwarding main"
assert_out_has "POST_SYNC_MARKER"
[ "$(git -C "$REPO" rev-parse HEAD)" != "$before" ] &&
	pass "the root checkout moved to the landed commit" ||
	fail "the root checkout did not fast-forward"

# Second run: nothing landed since, so nothing moves and the hook must NOT fire.
# A post-sync command that runs on every invocation is how a reinstall ends up
# in someone's hot loop.
wt_run
assert_out_has "already up to date"
assert_out_lacks "POST_SYNC_MARKER"
unset WORKTREE_CLEANUP_POST_SYNC

# Without the hook set, the script says what it is NOT doing rather than
# guessing a package manager.
wt_fixture
wt_branch landed2
wt_land landed2
wt_run
assert_out_has "reinstall dependencies if your project needs it"

# ---------------------------------------------------------------------------
banner "Uncommitted work in the root checkout stops the fast-forward"
# ---------------------------------------------------------------------------
wt_fixture
wt_branch landed
wt_land landed
printf 'edited\n' >>"$REPO/.gitignore" # a TRACKED file, modified

wt_run
assert_out_has "uncommitted changes — skipping fast-forward"
[ "$(git -C "$REPO" rev-parse HEAD)" = "$(git -C "$REPO" rev-parse main)" ] &&
	pass "HEAD is untouched while the root is dirty" ||
	fail "the script fast-forwarded over uncommitted work"

# ---------------------------------------------------------------------------
banner "Untracked worktree/ does NOT read as a dirty root"
# ---------------------------------------------------------------------------
# The regression this guards: `git status --porcelain` reports the untracked
# `worktree/` tree, so a project that forgot to gitignore it would silently
# never fast-forward — a no-op that looks exactly like success.
wt_fixture
git -C "$REPO" rm -q --cached .gitignore
rm -f "$REPO/.gitignore"
git -C "$REPO" commit -q -m "chore: stop ignoring the worktree tree"
git -C "$REPO" push -q origin main
wt_branch landed
wt_land landed

wt_run
assert_out_has "Fast-forwarding main"

# ---------------------------------------------------------------------------
banner "Scope: only worktrees under worktree/ are candidates"
# ---------------------------------------------------------------------------
wt_fixture
git -C "$REPO" worktree add -q "$SCRATCH/elsewhere.$$" -b feat/elsewhere
git -C "$REPO" push -q origin "feat/elsewhere:main"
git -C "$REPO" fetch -q origin

wt_run
assert_out_has "outside worktree/, skipped"
git -C "$REPO" worktree list | grep -q "elsewhere" &&
	pass "a merged worktree outside worktree/ is left alone" ||
	fail "the script removed a worktree outside its scope"

# ---------------------------------------------------------------------------
banner "WORKTREE_CLEANUP_BASE overrides the merge target"
# ---------------------------------------------------------------------------
# The default is origin's HEAD branch. A repo whose integration branch is not
# that one must be able to say so without editing the script.
wt_fixture
git -C "$REPO" push -q origin main:release
git -C "$REPO" fetch -q origin
wt_branch landed
git -C "$REPO" push -q origin "feat/landed:release" # landed on release, NOT main
git -C "$REPO" fetch -q origin

wt_run # default base: origin/main — not merged there
assert_out_has "not merged into origin/main"
wt_has_worktree landed && pass "kept when measured against the default base" ||
	fail "removed a branch that never landed on the default base"

WORKTREE_CLEANUP_BASE=origin/release
export WORKTREE_CLEANUP_BASE
wt_run
wt_has_worktree landed && fail "the override did not take effect" ||
	pass "removed once the base is the branch it actually landed on"
unset WORKTREE_CLEANUP_BASE

# ---------------------------------------------------------------------------
banner "An unknown argument is an error, not a silent full run"
# ---------------------------------------------------------------------------
wt_fixture
wt_branch landed
wt_land landed

wt_run --dryrun # a plausible typo for --dry-run
[ "$LAST_STATUS" = 2 ] && pass "a bad argument exits 2 (exit 2)" ||
	fail "a bad argument exited $LAST_STATUS — a typo'd --dry-run must not delete anything"
assert_out_has "unknown argument"
wt_has_worktree landed && pass "nothing was removed on the bad invocation" ||
	fail "the script acted despite the bad argument"

t_done "worktree-cleanup.sh"
