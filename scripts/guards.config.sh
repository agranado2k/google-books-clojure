#!/bin/sh
# guards.config.sh — the one place the guards learn the shape of YOUR repo.
#
# This file is DATA, not mechanism. The guards in this directory hold no
# knowledge of your layout; every project-specific pattern lives here, in one
# reviewable file, so that a change of policy is a diff a human reads rather
# than an edit inside a script nobody re-reads.
#
# It is sourced by:
#   scripts/tdd-pairing-guard.sh   (and its CI twin)
#   scripts/behavior-delta.sh
#
# THIS FILE IS YOURS. It is not part of the shared layer (see VERSION), it is
# not overwritten by a kit update, and editing it is the intended workflow.
#
# ---------------------------------------------------------------------------
# WHY A CONFIG FILE AND NOT A BOOTSTRAP-STAMPED PLACEHOLDER
# ---------------------------------------------------------------------------
# The obvious alternative was a doubled-curly mark inside the guard scripts that
# `bootstrap.sh` fills in. Rejected, for four reasons:
#
#   1. Bootstrap runs on an EMPTY project. It cannot know where your source will
#      live, so anything it stamped would be a guess — and the docs gate would
#      then enforce that guess forever.
#   2. The docs gate fails on any surviving unstamped mark outside a `*.template`
#      file. Marks inside `scripts/*.sh` would therefore force bootstrap to stamp
#      SOMETHING, which is exactly the opposite of the required default of
#      "unconfigured guards warn and pass".
#   3. A stamp happens once. Your source layout changes for the life of the
#      project; the guard has to be re-configurable long after bootstrap is gone.
#   4. Config-as-data is testable. The guard tests point GUARDS_CONFIG at
#      throwaway files and assert the behaviour of each setting.
#
# The guards locate this file as: $GUARDS_CONFIG (if set) -> the repo root's
# scripts/guards.config.sh -> the file next to the guard script. A missing or
# unconfigured file is NOT an error: the pairing guard warns once and passes.
# A guard that blocks every push in a repo nobody has configured yet gets
# deleted on day one, and a deleted guard checks nothing.

# ---------------------------------------------------------------------------
# 1. SOURCE — the production files whose change demands a test change
# ---------------------------------------------------------------------------
# An extended regular expression (grep -E) matched against repo-relative paths.
#
# EMPTY BY DEFAULT, and that default is load-bearing: with no SOURCE patterns
# the TDD pairing guard prints one warning and exits 0. Nothing is blocked until
# you say what "source" means here, because only you know.
#
# Fill this in with the trees your test runner actually covers. Keep it in step
# with your runner's coverage/include globs: a tree covered there but absent
# here is silently un-guarded.
#
# Examples (delete the ones that do not apply, uncomment the ones that do):
#   GUARD_SOURCE_RE='^(src|lib)/.*\.(ts|tsx|js|mjs)$'
#   GUARD_SOURCE_RE='^(packages/[^/]+/src/|apps/[^/]+/src/).*\.(ts|tsx|mjs)$'
#   GUARD_SOURCE_RE='^(app|domain)/.*\.py$'
#   GUARD_SOURCE_RE='^internal/.*\.go$'
GUARD_SOURCE_RE=''

# Paths that match SOURCE but must NOT count as source. The default carries the
# two exclusions every project needs: the test files themselves (they match many
# source globs) and type declarations (no behaviour to test).
#
# Add reviewable POLICY DATA here too — a config or fixture file that is read,
# not executed, is changed by a human decision rather than by new behaviour, so
# editing it should not demand a test edit.
GUARD_SOURCE_EXCLUDE_RE='(\.d\.ts|\.min\.js)$'

# ---------------------------------------------------------------------------
# 2. TEST — what counts as the paired test change
# ---------------------------------------------------------------------------
# Unlike SOURCE this ships a working default, deliberately BROAD: it spans the
# common conventions across ecosystems (xUnit suffixes, pytest prefixes, Go's
# _test, a top-level tests/ tree, Gherkin features). A too-broad test pattern
# makes the guard more forgiving, never more aggressive — the safe direction for
# a default. Narrow it when you want the guard to be stricter.
GUARD_TEST_RE='(\.|_|/)(test|tests|spec|specs)(\.|_|/)|(^|/)(test|tests|spec|specs)/|_test\.|\.feature$'

# ---------------------------------------------------------------------------
# 3. CONTRACT ARTIFACTS — the surfaces behavior-delta.sh inventories
# ---------------------------------------------------------------------------
# One record per line: a human-readable label, a pipe, an extended regex.
# behavior-delta.sh prints a section per surface that has deltas on the branch,
# and the SAME set is what its per-commit separation check treats as contract.
# Add a surface here and every part of the script picks it up.
#
# A "contract artifact" is a place where behavior is EXTERNALIZED, and therefore
# machine-visible: an API document, a schema, an event catalogue, a security
# policy, a standing instruction that shapes every future agent session.
#
# The two defaults below hold for any project built from this kit. Everything
# else is yours to add.
#
# -------- TEMPLATE BLOCK: copy a line down, edit it, uncomment it -------------
#   API surface (OpenAPI / GraphQL / protobuf)|^docs/api/openapi\.yaml$|\.proto$
#   Persistence (schema + migrations)|^db/|^migrations/|^prisma/schema\.prisma$
#   Domain events|^docs/events\.md$
#   Configuration (env contract)|^config/|\.env\.example$
#   Security posture (headers, CSP, auth policy)|^security/|^.*csp.*$
#   Error semantics (the shared error model)|^lib/errors/
#   Agent-facing prompt surfaces|^prompts/|^skills/
# -----------------------------------------------------------------------------
BEHAVIOR_DELTA_SURFACES='Agent & process surfaces (the constitution, skills, hooks, guards)|^AGENTS\.md$|/AGENTS\.md$|^CLAUDE\.md$|^GEMINI\.md$|^constitution/|^\.claude/|^\.githooks/|^scripts/
Architecture decisions|^docs/adr/'

# Executable specification files. behavior-delta flags these when EDITED inside a
# structure-only commit: an edited scenario is an unambiguous behavior change,
# while a newly added one is just added coverage.
BEHAVIOR_DELTA_FEATURE_RE='\.feature$'
