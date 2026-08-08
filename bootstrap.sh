#!/bin/sh
# agentic-sdlc — one-shot bootstrap.
#
# Run this ONCE, immediately after creating your repo from the template. It
# personalizes the kit, wires the git hook, tells you what to do next, and then
# deletes itself. It commits nothing: the first commit of your project is yours
# to read and to sign.
#
# Usage:
#   sh bootstrap.sh                          # prompts for the values
#   sh bootstrap.sh "My Project"             # name as an argument
#   sh bootstrap.sh "My Project" "One line." # name + description
#
# POSIX sh, git only. No node, no package manager — the kit's core is
# language-agnostic, and bootstrap runs before your project has a toolchain.

set -eu

die() {
	echo "bootstrap: $*" >&2
	exit 1
}

TEMPLATE="constitution/AGENTS.md.template"
MANUAL="AGENTS.md"
# The manual is ONE file. AGENTS.md is the filename the agent-tool ecosystem has
# converged on, and the tools that read a differently-named file get a SHIM: one
# import line pointing at the manual, no rules of its own. Two manuals is the
# failure mode this prevents — the moment a tool-specific file can hold a rule,
# it does, and the rules diverge silently per tool.
SHIMS="CLAUDE.md GEMINI.md"
# The docs-gate policy file: seeded with your project name so the portability
# guard protects the shared layer from your own vocabulary from the first run.
VOCAB_TEMPLATE="scripts/docs-conformance/local-vocabulary.mjs.template"
VOCAB="scripts/docs-conformance/local-vocabulary.mjs"
# Kit-authoring artifacts: they test and ship the kit itself, and mean nothing
# inside a consumer project. Removed at the end together with this script.
#
# The kit's CI goes too: it runs the kit's OWN acceptance test against the kit's
# OWN shared layer, and inheriting it would give a fresh project a workflow that
# fails for reasons that are none of its business. Consumer CI workflow
# templates are installed separately below (K3).
# Space-separated; each kit ticket that adds a demo adds its script here.
KIT_ONLY="tests/kit-demo.sh tests/docs-demo.sh tests/lib.sh tests/guards-demo.sh tests/adapters-demo.sh tests/tdd-pairing-guard.test.sh tests/tdd-pairing-guard-ci.test.sh tests/behavior-delta.test.sh tests/worktree-cleanup.test.sh .github/workflows/kit-ci.yml .github/workflows/kit-guards.yml"

# NOT in KIT_ONLY, and deliberately: adapters/. It is reference material a
# project wants LATER — on the day it turns a guard on, typically weeks after
# bootstrap — so it arrives intact and dormant rather than being deleted here or
# installed automatically. Nothing in it is copied, stamped or activated; see
# adapters/node-ts/INSTALL.md, "Why bootstrap.sh does not touch this directory".
# tests/adapters-demo.sh asserts exactly that, byte for byte.

# --- ground checks ----------------------------------------------------------
# Run from the repo root regardless of where the caller invoked it.
root=$(git rev-parse --show-toplevel 2>/dev/null) ||
	die "not inside a git repository. Run \`git init\` (or clone from the template) first — bootstrap wires a git hook and has nothing to wire otherwise."
cd "$root"

# Idempotency: refuse the second run rather than re-stamping over a manual you
# have since edited. Refusing is cheap; silently overwriting a week of local
# rules is not.
[ -f "$MANUAL" ] &&
	die "$MANUAL already exists — this repo looks bootstrapped already. Delete $MANUAL first if you really mean to re-stamp it."
# Same reasoning one level down: a shim is written unconditionally, so a
# pre-existing file at that path would be destroyed. If you arrived here with a
# hand-written CLAUDE.md, its content is the thing worth keeping — move it into
# the manual yourself, then delete it and re-run.
for shim in $SHIMS; do
	if [ -e "$shim" ]; then
		die "$shim already exists, and bootstrap would overwrite it with a one-line shim. Move its content into $MANUAL, delete $shim, then re-run."
	fi
done
[ -f "$TEMPLATE" ] ||
	die "$TEMPLATE not found. Either this is not an agentic-sdlc repo, or bootstrap already ran."

# --- gather values ----------------------------------------------------------
name=${1:-}
description=${2:-}

if [ -z "$name" ]; then
	if [ -t 0 ]; then
		printf 'Project name: '
		read -r name
	else
		die "no project name given and no terminal to ask on. Pass it: sh bootstrap.sh \"My Project\""
	fi
fi
[ -n "$name" ] || die "project name cannot be empty."

if [ -z "$description" ]; then
	if [ -t 0 ]; then
		printf 'One-line description (Enter to skip): '
		read -r description || description=""
	fi
fi
[ -n "$description" ] || description="An agent-assisted project built on the agentic-sdlc framework."

# A stamped value containing the placeholder syntax would defeat the gate, and a
# `/` or `&` would corrupt the sed replacement below.
case "$name$description" in
*'{{'* | *'}}'*) die "project name/description must not contain '{{' or '}}'." ;;
esac

# --- stamp ------------------------------------------------------------------
# `|` as the sed delimiter, and the values are escaped for it plus `&` and `\`.
esc() {
	printf '%s' "$1" | sed -e 's/[\\&|]/\\&/g'
}
# The vocabulary file is JavaScript, and the name lands inside a double-quoted
# string literal there. Escape for JS first, then for sed — in that order, or
# the sed pass would escape the backslashes the JS pass just added.
esc_js() {
	printf '%s' "$1" | sed -e 's/["\\]/\\&/g'
}
name_esc=$(esc "$name")
description_esc=$(esc "$description")
name_js_esc=$(esc "$(esc_js "$name")")

sed \
	-e "s|{{PROJECT_NAME}}|$name_esc|g" \
	-e "s|{{PROJECT_DESCRIPTION}}|$description_esc|g" \
	"$TEMPLATE" >"$MANUAL"
rm -f "$TEMPLATE"
echo "  stamped $MANUAL"

# ============================================================================
# K7 BEGIN — the tool shims (#16)
# ----------------------------------------------------------------------------
# One manual, several tool-specific filenames. Each shim is written from
# scratch — never stamped, never appended to — and holds exactly two lines: a
# comment saying what it is, and the import line the tool resolves.
#
# The comment is an HTML comment on purpose: it renders as nothing and reads as
# metadata rather than as a rule, which is the whole point. A shim that can hold
# a rule stops being a shim.
#
# The docs gate enforces the shape (`shim-invalid` in the docs-conformance
# harness), so a shim that grows a second instruction fails the push rather than
# quietly becoming a rival manual.
for shim in $SHIMS; do
	cat >"$shim" <<EOF
<!-- Shim: the agent manual is $MANUAL. Edit that file, not this one. -->
@$MANUAL
EOF
	echo "  wrote   $shim (shim -> $MANUAL)"
done
# K7 END
# ============================================================================

if [ -f "$VOCAB_TEMPLATE" ]; then
	sed -e "s|{{PROJECT_NAME}}|$name_js_esc|g" "$VOCAB_TEMPLATE" >"$VOCAB"
	rm -f "$VOCAB_TEMPLATE"
	echo "  stamped $VOCAB"
fi

# --- wire the hook ----------------------------------------------------------
# Native git hooks path. This is per-clone config, not a tracked setting, so
# every collaborator runs it once — which is why the next-steps text says so.
git config core.hooksPath .githooks
chmod +x .githooks/* scripts/*.sh 2>/dev/null || true
echo "  wired core.hooksPath -> .githooks"

# ============================================================================
# K4 BEGIN — the documentation set (#6)
# ----------------------------------------------------------------------------
# Everything between this banner and `K4 END` belongs to K4. bootstrap.sh is
# touched by several kit tickets (K1 constitution articles, K3 guards); keeping
# each one's edits inside a named block is the difference between a three-way
# merge and a rewrite. Do not interleave.
#
# The skeletons live under templates/docs/ and are put in place here:
#   *.template      -> stamped through sed (they carry double-brace marks)
#   everything else -> copied verbatim (it must carry NO marks: once it lands
#                      it is ordinary repo content and the gate scans it)
#
# The templates/docs/ source tree is removed afterwards. A project holding both
# a stamped docs/diary.md and an unstamped templates/docs/diary.md.template has
# two answers to the same question, and bootstrap does not run a second time to
# reconcile them — same reasoning as constitution/AGENTS.md.template above.
DOCS_TEMPLATES="templates/docs"
today=$(date +%Y-%m-%d)
today_esc=$(esc "$today")

# stamp <source> <destination>
stamp() {
	mkdir -p "$(dirname "$2")"
	sed \
		-e "s|{{PROJECT_NAME}}|$name_esc|g" \
		-e "s|{{PROJECT_DESCRIPTION}}|$description_esc|g" \
		-e "s|{{BOOTSTRAP_DATE}}|$today_esc|g" \
		"$1" >"$2"
	echo "  stamped $2"
}

# copy <source> <destination>
copy() {
	mkdir -p "$(dirname "$2")"
	cp "$1" "$2"
	echo "  copied  $2"
}

if [ -d "$DOCS_TEMPLATES" ]; then
	# The kit's own README describes the kit. Overwriting it is the point:
	# a project whose README advertises the template it came from tells every
	# reader the wrong thing on day one.
	stamp "$DOCS_TEMPLATES/README.md.template" "README.md"
	stamp "$DOCS_TEMPLATES/diary.md.template" "docs/diary.md"
	stamp "$DOCS_TEMPLATES/domain-glossary.md.template" "docs/domain-glossary.md"
	stamp "$DOCS_TEMPLATES/adr/INDEX.md.template" "docs/adr/INDEX.md"

	# Verbatim: the MADR skeleton stays a working template inside the project
	# (it is copied per new decision), and GitHub reads the PR template from
	# .github/ by name.
	copy "$DOCS_TEMPLATES/adr/NNNN-template.md" "docs/adr/NNNN-template.md"
	copy "$DOCS_TEMPLATES/PULL_REQUEST_TEMPLATE.md" ".github/PULL_REQUEST_TEMPLATE.md"

	rm -rf "$DOCS_TEMPLATES"
	rmdir templates 2>/dev/null || true
	echo "  removed $DOCS_TEMPLATES (stamped into place)"
else
	echo "  skipped the documentation set — $DOCS_TEMPLATES not found"
fi
# K4 END
# ============================================================================
# --- install the CI workflow templates --------------------------------------
# The kit ships these under templates/ rather than .github/workflows/ because a
# TEMPLATE repository must not run its consumers' CI against its own tree — the
# docs gate expects a bootstrapped project, and the kit is deliberately not one.
# They become real workflows here, where there IS a project to gate.
#
# Never overwrites: a file already at the destination is somebody's decision.
if [ -d templates/workflows ]; then
	mkdir -p .github/workflows
	for wf in templates/workflows/*; do
		[ -e "$wf" ] || continue
		dest=".github/workflows/$(basename "$wf")"
		if [ -e "$dest" ]; then
			echo "  kept $dest (already present — not overwritten)"
		else
			cp "$wf" "$dest"
			echo "  installed $dest"
		fi
	done
	rm -rf templates/workflows
	rmdir templates 2>/dev/null || true
fi

# --- clean up the kit's own scaffolding -------------------------------------
for f in $KIT_ONLY; do
	if [ -e "$f" ]; then
		rm -f "$f"
		echo "  removed $f (kit-authoring only)"
	fi
done
# Only if now empty — a project that already has its own workflows keeps them.
rmdir tests .github/workflows .github 2>/dev/null || true

# --- next steps -------------------------------------------------------------
cat <<EOF

Bootstrapped: $name

Next:
  1. sh scripts/check.sh          run the docs gate — it should pass now
  2. read AGENTS.md               it is loaded into every agent session; every
                                  line is yours to keep, cut, or replace.
                                  CLAUDE.md and GEMINI.md are one-line shims
                                  importing it — never edit those
  3. fill in the two articles     constitution/local-engineering.md.template
                                  constitution/local-workflow.md.template
                                  replace the marks, drop the .template suffix,
                                  then point AGENTS.md's article layer at them
  4. edit scripts/guards.config.sh
                                  the TDD pairing guard is INACTIVE until you
                                  set GUARD_SOURCE_RE to match your source
                                  trees. Until then it warns on every push and
                                  blocks nothing — on purpose, because it cannot
                                  know your layout and will not guess it.
  5. fill in docs/diary.md        the "Current state" block at the top is what
                                  an agent reads first; README.md is stamped
                                  but thin — make it say what $name is
  6. git add -A && git commit     bootstrap committed nothing on purpose

Both gates run automatically before every push (.githooks/pre-push), each with
its own loud bypass: PUSH_WITHOUT_DOCS=1 and PUSH_WITHOUT_TESTS=1. The matching
CI workflows were installed into .github/workflows/, so a local bypass only
defers the failure. Commit linting ships DISABLED as
.github/workflows/commitlint.yml.example — rename it once you have a runner.
Collaborators cloning this repo run \`git config core.hooksPath .githooks\`
once — hooks path is per-clone config and cannot be committed.

With node on PATH the gate runs the full docs-conformance harness
(scripts/docs-conformance); without it, a reduced POSIX fallback runs and says
so. The policy it enforces is data you own: scripts/docs-conformance/config.mjs
and scripts/docs-conformance/local-vocabulary.mjs.

The shared layer (see VERSION) is copied verbatim from the kit and is not
edited here. Everything else is yours.
EOF

# ============================================================================
# K5 BEGIN — the adapters pointer (#7)
# ----------------------------------------------------------------------------
# adapters/ is NOT copied, stamped or removed by this script (see KIT_ONLY
# above). It arrives dormant. All this block does is TELL you it is there —
# without a mention, a fresh project contains a directory nobody introduced,
# which is how reference material gets mistaken for a description of the
# project. A pointer, not an install.
if [ -d adapters ]; then
	cat <<'EOF'
adapters/ holds worked reference wirings — one directory per stack, copied from
a real project with the reasoning left in. Nothing in it runs, nothing in it was
stamped, and no gate reads it: it is documentation you can copy from on the day
you configure a guard. Read adapters/README.md. If no adapter matches your
stack, `rm -rf adapters` is the right answer.
EOF
fi
# K5 END
# ============================================================================

# --- self-delete ------------------------------------------------------------
# Last, so a failure above leaves bootstrap runnable.
rm -f "$root/bootstrap.sh"
