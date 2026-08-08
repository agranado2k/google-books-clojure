#!/bin/sh
# guards.lib.sh — the one loader the guards share.
#
# Sourced, never executed. Its only job is finding and reading
# scripts/guards.config.sh so that three scripts do not carry three copies of
# the same resolution order (and drift).
#
# Resolution order, first hit wins:
#   1. $GUARDS_CONFIG        — explicit. If it is set and does not exist, that
#                              is an ERROR (exit 2): the caller named a file, so
#                              silently falling back would run a policy nobody
#                              asked for. Tests rely on this.
#   2. <repo root>/scripts/guards.config.sh
#   3. <this file's directory>/guards.config.sh
#
# A missing config is NOT an error. Each guard decides what an unconfigured repo
# means for it; the pairing guard warns once and passes.
#
# Shared layer (see VERSION): copied verbatim, not edited downstream. Your
# policy goes in guards.config.sh.

# guards_load_config [<dir of the calling script>]
guards_load_config() {
	_gl_here=${1:-.}

	if [ -n "${GUARDS_CONFIG:-}" ]; then
		if [ ! -f "$GUARDS_CONFIG" ]; then
			echo "guards: GUARDS_CONFIG=$GUARDS_CONFIG does not exist." >&2
			exit 2
		fi
		. "$GUARDS_CONFIG"
		return 0
	fi

	_gl_root=$(git rev-parse --show-toplevel 2>/dev/null) || _gl_root=
	if [ -n "$_gl_root" ] && [ -f "$_gl_root/scripts/guards.config.sh" ]; then
		. "$_gl_root/scripts/guards.config.sh"
		return 0
	fi

	if [ -f "$_gl_here/guards.config.sh" ]; then
		. "$_gl_here/guards.config.sh"
		return 0
	fi

	return 1
}
