#!/bin/sh
# The kit's acceptance test — and its demo.
#
# The claim is one sentence: "a project created from this template, bootstrapped,
# passes its own gate — and the gate is not vacuous." A claim that nothing runs
# is a claim nobody should believe, so this script runs the whole path end to
# end against a throwaway copy of the kit, red and green, once per failure mode
# the gate says it catches:
#
#   1.  RED    before bootstrap — no root manual, so the gate refuses
#   2.  GREEN  bootstrap stamps AGENTS.md and the two shims, wires,
#              self-deletes; the gate passes
#   2b. GREEN  the three entry points exist and the shims are PURE — then RED
#              when a shim grows content, and RED when AGENTS.md is deleted
#   3.         a second bootstrap is refused (idempotency)
#   4.  GREEN  the harness's own fixture tests pass inside the project
#   4b. GREEN  the skills came through un-stamped and intact, every command in
#              the manual resolves to one, and no skill is orphaned — then RED
#              when a skill is deleted out from under its row
#   5.  GREEN  a real `git push` to a real remote passes through the real hook
#   6.  RED    an unstamped placeholder fails the gate AND blocks the push
#   7.  RED    a missing shared-layer file fails the gate
#   8.  RED    a stale PATH reference fails the REAL validator
#   9.  RED    a stale SLASH COMMAND fails the real validator
#   10. RED    a leak of the project's own name into the shared article
#   11. RED→GREEN  an unreachable article, then the documented fix
#   12.        the POSIX fallback: green without node, and honest about the
#              coverage it does not have
#   13.        the documented bypass lets a red tree through, loudly
#
# Usage: sh tests/kit-demo.sh

set -u

KIT=$(cd "$(dirname "$0")/.." && pwd)
SCRATCH=$(mktemp -d) || exit 2
PROJ="$SCRATCH/demo-project"
REMOTE="$SCRATCH/demo-remote.git"
PROJECT_NAME="Demo Project"

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

assert_out_has() {
	case "$LAST_OUT" in
	*"$1"*) pass "output mentions '$1'" ;;
	*)
		fail "output does not mention '$1'"
		printf '%s\n' "$LAST_OUT" | sed 's/^/        | /'
		;;
	esac
}

assert_out_lacks() {
	case "$LAST_OUT" in
	*"$1"*)
		fail "output unexpectedly mentions '$1'"
		printf '%s\n' "$LAST_OUT" | sed 's/^/        | /'
		;;
	*) pass "output does not mention '$1' (as documented)" ;;
	esac
}

assert_file() { [ -e "$1" ] && pass "$1 exists" || fail "$1 is missing"; }
assert_no_file() { [ -e "$1" ] && fail "$1 still exists" || pass "$1 is gone"; }

# Snapshot / restore the manual layer, so each red step starts from green.
save_good() {
	cp AGENTS.md "$SCRATCH/AGENTS.md.good"
	cp CLAUDE.md "$SCRATCH/CLAUDE.md.good"
	cp GEMINI.md "$SCRATCH/GEMINI.md.good"
	cp constitution/shared-invariants.md "$SCRATCH/shared-invariants.good"
}
restore_good() {
	cp "$SCRATCH/AGENTS.md.good" AGENTS.md
	cp "$SCRATCH/CLAUDE.md.good" CLAUDE.md
	cp "$SCRATCH/GEMINI.md.good" GEMINI.md
	cp "$SCRATCH/shared-invariants.good" constitution/shared-invariants.md
	rm -f constitution/local-notes.md
}

# ---------------------------------------------------------------------------
banner "Setup — simulate 'Use this template'"
# ---------------------------------------------------------------------------
mkdir -p "$PROJ"
cp -R "$KIT/." "$PROJ/"
rm -rf "$PROJ/.git"
cd "$PROJ" || exit 2

git init -q -b main
git config user.name "Kit Demo"
git config user.email "demo@example.invalid"
git config commit.gpgsign false
git add -A
git init -q --bare "$REMOTE"
git remote add origin "$REMOTE"
pass "fresh repo at \$SCRATCH/demo-project with a bare origin"
[ "$HAVE_NODE" = 1 ] && pass "node is available — the full harness will run" ||
	skip "node is NOT available — harness steps will be skipped, fallback still proven"

# ---------------------------------------------------------------------------
banner "1. RED — the gate fails BEFORE bootstrap"
# ---------------------------------------------------------------------------
# If it passed here, it would be passing on a tree with no agent manual at all,
# and the green in step 2 would mean nothing.
assert_status 1 "check.sh rejects an unbootstrapped tree" -- sh scripts/check.sh
assert_out_has "root-manual-missing"

# ---------------------------------------------------------------------------
banner "2. GREEN — bootstrap, then the gate"
# ---------------------------------------------------------------------------
assert_status 0 "bootstrap.sh runs" -- sh bootstrap.sh "$PROJECT_NAME" "A throwaway project proving the kit walks."
printf '%s\n' "$LAST_OUT" | sed 's/^/      > /'

assert_file "AGENTS.md"
assert_no_file "constitution/AGENTS.md.template"
assert_no_file "bootstrap.sh"
assert_no_file "tests/kit-demo.sh"
assert_no_file ".github/workflows/kit-ci.yml"
assert_file "constitution/shared-invariants.md"
assert_file "constitution/local-engineering.md.template"
assert_file "constitution/local-workflow.md.template"
assert_file "scripts/docs-conformance/local-vocabulary.mjs"
assert_no_file "scripts/docs-conformance/local-vocabulary.mjs.template"

grep -q "$PROJECT_NAME" AGENTS.md && pass "AGENTS.md carries the project name" ||
	fail "AGENTS.md was not stamped with the project name"
grep -q "$PROJECT_NAME" scripts/docs-conformance/local-vocabulary.mjs &&
	pass "local-vocabulary.mjs carries the project name" ||
	fail "local-vocabulary.mjs was not stamped"
[ "$(git config core.hooksPath)" = ".githooks" ] && pass "core.hooksPath is .githooks" ||
	fail "core.hooksPath is '$(git config core.hooksPath)', expected .githooks"

# Deliberately NOT `git add`-ed first: a just-bootstrapped project has committed
# nothing, and the gate must see the new AGENTS.md anyway.
assert_status 0 "check.sh passes on the bootstrapped project" -- sh scripts/check.sh
assert_out_has "shared-layer 0.3.0"
if [ "$HAVE_NODE" = 1 ]; then
	assert_out_has "engine: harness"
else
	assert_out_has "engine: fallback"
fi

save_good

# ---------------------------------------------------------------------------
banner "2b. The three entry points — one manual, two shims that stay shims"
# ---------------------------------------------------------------------------
# AGENTS.md is the manual. CLAUDE.md and GEMINI.md exist so a tool that looks for
# its own filename finds the same rules — which is only true while they contain
# nothing but the import. The claim has two halves and both are checked here:
# the files are there, and they are PURE.
assert_file "AGENTS.md"
assert_file "CLAUDE.md"
assert_file "GEMINI.md"

# Purity, stated as the gate states it: dropping blanks, a shim holds exactly one
# `@AGENTS.md` line and at most one comment line. Anything else is a rule living
# in a tool-specific file, which is the whole failure mode.
for shim in CLAUDE.md GEMINI.md; do
	imports=$(grep -c '^@AGENTS\.md$' "$shim")
	other=$(grep -v '^[[:space:]]*$' "$shim" | grep -vc -e '^@AGENTS\.md$' -e '^<!--.*-->$')
	if [ "$imports" = 1 ] && [ "$other" = 0 ]; then
		pass "$shim is a pure shim (one @AGENTS.md import, no rules of its own)"
	else
		fail "$shim is not a pure shim — $imports import line(s), $other other line(s)"
		sed 's/^/        | /' "$shim"
	fi
done

# RED — a shim that grows a rule. Only the harness sees this: it is a content
# rule, and the POSIX fallback says in its own NOTICE that it does not check it.
if [ "$HAVE_NODE" = 1 ]; then
	printf '\n## Extra rule\n\nAlways squash before merging.\n' >>CLAUDE.md
	assert_status 1 "check.sh rejects a shim that has grown content" -- sh scripts/check.sh
	assert_out_has "shim-invalid"
	assert_out_has "CLAUDE.md"
	restore_good
	assert_status 0 "check.sh is green again once the shim is pure" -- sh scripts/check.sh

	# And a deleted shim, which is the other half of the same claim: that tool
	# now loads no manual at all.
	mv GEMINI.md "$SCRATCH/GEMINI.md.moved"
	assert_status 1 "check.sh rejects a missing shim" -- sh scripts/check.sh
	assert_out_has "shim-invalid"
	assert_out_has "GEMINI.md"
	mv "$SCRATCH/GEMINI.md.moved" GEMINI.md
else
	skip "shim integrity (no node — the fallback's NOTICE says it cannot see this)"
fi

# RED — the manual itself renamed away. This one is POSIX, so it holds with or
# without node: a project whose shims point at nothing is broken whichever
# engine is available.
mv AGENTS.md "$SCRATCH/AGENTS.md.moved"
assert_status 1 "check.sh rejects a deleted AGENTS.md" -- sh scripts/check.sh
assert_out_has "root-manual-missing"
mv "$SCRATCH/AGENTS.md.moved" AGENTS.md
assert_status 0 "check.sh is green again once the manual is back" -- sh scripts/check.sh

# ---------------------------------------------------------------------------
banner "3. Idempotency — bootstrap refuses a second run"
# ---------------------------------------------------------------------------
cp "$KIT/bootstrap.sh" bootstrap.sh
assert_status 1 "second bootstrap is refused" -- sh bootstrap.sh "Other Name"
assert_out_has "already exists"
rm -f bootstrap.sh

# ---------------------------------------------------------------------------
banner "4. GREEN — the gate's own tests pass inside the project"
# ---------------------------------------------------------------------------
# The gate is code, and code with no test that can fail is a claim, not a check
# (shared invariant §3). These fixture tests ship with the project precisely so
# a local rule change can be tested the same way.
if [ "$HAVE_NODE" = 1 ]; then
	assert_status 0 "harness fixture tests pass" -- \
		node --test scripts/docs-conformance/test/claude-md-refs.test.mjs
	assert_out_has "# fail 0"
else
	skip "harness fixture tests (no node)"
fi

# ---------------------------------------------------------------------------
banner "4b. The skills — the manual's command map, checked both ways"
# ---------------------------------------------------------------------------
# The skills are the one part of the kit that is COPIED, not stamped: nothing
# personalizes them, so they have to read correctly in a project nobody
# personalized. Three claims, and each is checkable:
#
#   (a) they survived bootstrap and carry no unstamped mark;
#   (b) every /command in the manual resolves to a skill on disk;
#   (c) every skill shipped is named by the manual — no orphans.
#
# (b) is the direction the docs gate already enforces, so the positive form here
# would be redundant on its own — that is why the step ends by deleting a skill
# and watching the gate go red. A check that has never failed is a claim.
assert_file ".claude/skills/LICENSE-mattpocock-skills.md"
assert_file ".claude/skills/tdd/SKILL.md"
assert_file "scripts/worktree-cleanup.sh"

# (a) The gate's placeholder rule covers the whole tree; this narrows the report
# to the skills, because "a mark leaked into a skill" and "a mark leaked into
# the manual" have completely different causes.
ob='{'
cb='}'
if grep -rq "${ob}${ob}[A-Z][A-Z0-9_]*${cb}${cb}" .claude/skills 2>/dev/null; then
	fail "a skill carries an unstamped placeholder — skills are copied, never stamped"
	grep -rn "${ob}${ob}[A-Z][A-Z0-9_]*${cb}${cb}" .claude/skills | sed 's/^/        | /'
else
	pass "no skill carries an unstamped placeholder"
fi

# Commands as the harness reads them: code spans outside fences, split into
# words, keeping the tokens that OPEN with a slash command.
manual_commands() {
	awk '/^[ \t]*(```|~~~)/ { fence = !fence; next } !fence { print }' AGENTS.md |
		grep -o '`[^`]*`' | tr -d '`' | tr ' \t' '\n\n' |
		grep '^[([{"]*/[a-z]' | grep -o '/[a-z][a-z0-9-]*' | sort -u
}

# Harness built-ins, mirroring claudeMdRefs.ignoreCommands in config.mjs. They
# are real commands that are deliberately not repo skills.
is_ignored() {
	case "$1" in
	/loop | /security-review | /review | /init) return 0 ;;
	esac
	return 1
}

resolved=0
for cmd in $(manual_commands); do
	is_ignored "$cmd" && continue
	if [ -f ".claude/skills/${cmd#/}/SKILL.md" ]; then
		resolved=$((resolved + 1))
	else
		fail "AGENTS.md references $cmd but .claude/skills/${cmd#/}/SKILL.md does not exist"
	fi
done
[ "$resolved" -ge 12 ] &&
	pass "all $resolved slash commands in AGENTS.md resolve to a skill" ||
	fail "only $resolved commands resolved — the manual should map the whole chain"

# (c) The other direction. An unreferenced skill is the article-unreferenced
# problem one layer down: nothing points at it, so nobody loads it, so it rots.
orphans=0
for d in .claude/skills/*/; do
	[ -f "$d/SKILL.md" ] || continue
	name=$(basename "$d")
	manual_commands | grep -qx "/$name" || {
		fail ".claude/skills/$name ships but AGENTS.md never names /$name"
		orphans=$((orphans + 1))
	}
done
[ "$orphans" = 0 ] && pass "no orphaned skills — every shipped skill is on the map"

# RED — pull one skill out and watch the gate report it. Only the harness can
# see this: resolving a command needs command-span parsing, which the POSIX
# fallback deliberately does not do (it says so in its NOTICE).
if [ "$HAVE_NODE" = 1 ]; then
	mv .claude/skills/tdd "$SCRATCH/tdd.skill"
	assert_status 1 "check.sh rejects the manual's now-dead /tdd row" -- sh scripts/check.sh
	assert_out_has "skill-missing"
	assert_out_has "/tdd"
	mv "$SCRATCH/tdd.skill" .claude/skills/tdd
	assert_status 0 "check.sh is green again once the skill is back" -- sh scripts/check.sh
else
	skip "deleted-skill detection (no node)"
fi

# ---------------------------------------------------------------------------
banner "5. GREEN — a real push through the real pre-push hook"
# ---------------------------------------------------------------------------
git add -A
git commit -q -m "chore: bootstrap from agentic-sdlc"
assert_status 0 "git push succeeds (hook ran the gate and it passed)" -- git push -q origin main
assert_status 0 "the pre-push hook is executable" -- test -x .githooks/pre-push

# ---------------------------------------------------------------------------
banner "6. RED — an unstamped placeholder fails the gate and blocks the push"
# ---------------------------------------------------------------------------
printf '\nOwner: {{PROJECT_OWNER}}\n' >>AGENTS.md
assert_status 1 "check.sh rejects the surviving placeholder" -- sh scripts/check.sh
assert_out_has "placeholder-unstamped"

git add -A
git commit -q -m "docs: add an owner line (with an unstamped placeholder)"
assert_status 1 "git push is BLOCKED by the hook" -- git push origin main
assert_out_has "pre-push: docs gate failed"

restore_good
git add -A
git commit -q -m "revert: drop the unstamped owner line"

# ---------------------------------------------------------------------------
banner "7. RED — a deleted shared-layer file fails the gate"
# ---------------------------------------------------------------------------
rm -f constitution/shared-invariants.md
assert_status 1 "check.sh rejects the missing shared-layer file" -- sh scripts/check.sh
assert_out_has "shared-layer-missing"
# The root manual points at it, so the reference check catches the same deletion
# from the other side — whichever engine is running.
assert_out_has "path-missing"
restore_good

# ---------------------------------------------------------------------------
banner "8. RED — a planted STALE PATH fails the real validator"
# ---------------------------------------------------------------------------
# This is the failure the K0 stopgap could only approximate. The reference now
# goes through claude-md-refs: whole-code-span matching, configured path roots,
# and a violation that names the validator and the rule.
printf '\nThe onboarding guide is `docs/ghost-guide.md`.\n' >>AGENTS.md
assert_status 1 "check.sh rejects the stale path reference" -- sh scripts/check.sh
assert_out_has "ghost-guide"
if [ "$HAVE_NODE" = 1 ]; then
	assert_out_has "claude-md-refs"
	assert_out_has "path-missing"
fi
restore_good

# ---------------------------------------------------------------------------
banner "9. RED — a planted STALE SLASH COMMAND fails the real validator"
# ---------------------------------------------------------------------------
# Not reachable by the POSIX fallback at all: it needs command-span parsing and
# skill-directory resolution. This is the coverage the harness buys.
if [ "$HAVE_NODE" = 1 ]; then
	printf '\nWhen finished, run `/ghost-command` to wrap up.\n' >>AGENTS.md
	assert_status 1 "check.sh rejects the dead slash command" -- sh scripts/check.sh
	assert_out_has "skill-missing"
	assert_out_has "ghost-command"
	restore_good
else
	skip "stale slash command (no node)"
fi

# ---------------------------------------------------------------------------
banner "10. RED — the project's own name leaks into the shared article"
# ---------------------------------------------------------------------------
# The shared layer's only job is to be copyable verbatim. bootstrap seeded
# local-vocabulary.mjs with this project's name, so the guard is armed on day 1.
if [ "$HAVE_NODE" = 1 ]; then
	printf '\nThis rule exists because %s needed it.\n' "$PROJECT_NAME" >>constitution/shared-invariants.md
	assert_status 1 "check.sh rejects the portability leak" -- sh scripts/check.sh
	assert_out_has "portability-leak"
	assert_out_has "product-name"
	restore_good
else
	skip "portability leak (no node)"
fi

# ---------------------------------------------------------------------------
banner "11. RED then GREEN — an unreachable article, and the documented fix"
# ---------------------------------------------------------------------------
# An article the root never points at is dead text: it binds nobody and drifts
# unnoticed. The fix is the one the hint names — add the pointer.
if [ "$HAVE_NODE" = 1 ]; then
	printf '# Local notes\n\nSomething binding.\n' >constitution/local-notes.md
	assert_status 1 "check.sh rejects the unreachable article" -- sh scripts/check.sh
	assert_out_has "article-unreferenced"

	printf '\nLocal notes live in `constitution/local-notes.md`.\n' >>AGENTS.md
	assert_status 0 "check.sh passes once the root points at it" -- sh scripts/check.sh
	restore_good
else
	skip "unreachable article (no node)"
fi

# ---------------------------------------------------------------------------
banner "12. The POSIX fallback — green without node, and honest about it"
# ---------------------------------------------------------------------------
# A project that has not chosen a toolchain still inherits a working gate. What
# it must NOT inherit is a false sense of coverage, so the fallback says what it
# cannot see — and this step proves that claim in both directions.
assert_status 0 "fallback passes on the clean tree" -- env DOCS_CHECK_NO_NODE=1 sh scripts/check.sh
assert_out_has "reduced coverage"
assert_out_has "engine: fallback"

printf '\nThe onboarding guide is `docs/ghost-guide.md`.\n' >>AGENTS.md
assert_status 1 "fallback still catches a stale repo path" -- env DOCS_CHECK_NO_NODE=1 sh scripts/check.sh
assert_out_has "ghost-guide"
restore_good

printf '\nThis rule exists because %s needed it.\n' "$PROJECT_NAME" >>constitution/shared-invariants.md
assert_status 0 "fallback does NOT catch the portability leak" -- env DOCS_CHECK_NO_NODE=1 sh scripts/check.sh
assert_out_lacks "portability-leak"
restore_good

# ---------------------------------------------------------------------------
banner "13. The bypass is real, and loud"
# ---------------------------------------------------------------------------
printf '\nOwner: {{PROJECT_OWNER}}\n' >>AGENTS.md
git add -A
git commit -q --allow-empty -m "docs: reintroduce the bad line to test the bypass"
out=$(PUSH_WITHOUT_DOCS=1 git push origin main 2>&1)
status=$?
if [ "$status" = 0 ]; then
	pass "PUSH_WITHOUT_DOCS=1 lets the push through (exit 0)"
else
	fail "bypass did not work — exit $status"
	printf '%s\n' "$out" | sed 's/^/        | /'
fi
LAST_OUT=$out
assert_out_has "BYPASSED"

# ---------------------------------------------------------------------------
banner "Result"
# ---------------------------------------------------------------------------
if [ "$failures" = 0 ]; then
	echo "  ALL GREEN — the kit walks."
	exit 0
fi
echo "  $failures assertion(s) failed."
exit 1
