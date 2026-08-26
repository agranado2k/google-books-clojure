#!/bin/sh
# agents.lib.sh — THE capability-tier resolver. One implementation, many callers.
#
# Answers exactly one question: "which execution model does this tier run on?"
#
#   sh scripts/agents.lib.sh implementer     -> prints the mapped model id, or
#                                               nothing if the tier is unmapped
#   . scripts/agents.lib.sh; resolve_tier …  -> the same, as a shell function
#
# WHY THE KIT NEVER NAMES A MODEL
# ---------------------------------------------------------------------------
# Model identifiers rot faster than any other constant a framework could carry:
# they are renamed, deprecated and repriced on someone else's schedule, and they
# differ per provider and per agent harness. A kit that shipped one would be
# shipping a lie with a timer on it, and the lie would be re-read by every
# session that loads the manual.
#
# So the kit ships the VOCABULARY and the MECHANISM, and your project supplies
# the mapping:
#
#   VOCABULARY   four tier names — planner, implementer, mechanical, reviewer —
#                defined in the manual layer (the root manual and the local
#                workflow article), because deciding which tier a piece of work
#                deserves is a human process rule, not a script's business.
#   MECHANISM    this file. Shared layer (see VERSION): copied verbatim, so a
#                fix to the resolution order reaches every project.
#   MAPPING      scripts/agents.config.sh. Yours, local, never overwritten by a
#                kit update — the same split, and the same reasoning, as
#                guards.lib.sh / guards.config.sh.
#
# THE UNCONFIGURED DEFAULT IS LOAD-BEARING
# ---------------------------------------------------------------------------
# An unmapped tier prints NOTHING and exits 0, after warning once. "Nothing" is
# a real answer: the caller passes no model parameter and the spawned agent
# inherits the session's own model, which is precisely the behaviour every
# project has today. A resolver that hard-failed on an unmapped tier would make
# a freshly bootstrapped project unable to spawn anything, and would be deleted
# on day one — and a deleted resolver resolves nothing.
#
# An UNKNOWN tier is the opposite case and does fail (exit 2). A typo is not a
# policy choice: silently running `implementor` on whatever the session happens
# to be is the exact cost blindness this seam exists to remove.
#
# Config resolution order, first hit wins:
#   1. $AGENTS_CONFIG        — explicit. If it is set and does not exist, that
#                              is an ERROR (exit 2): the caller named a file, so
#                              falling back silently would run a mapping nobody
#                              asked for. Tests rely on this.
#   2. <repo root>/scripts/agents.config.sh
#   3. <this file's directory>/agents.config.sh
#
# Exit codes:  0 resolved (a value, or deliberately nothing) · 2 usage error,
#              unknown tier, or an explicit config that does not exist.

# The closed vocabulary. Deliberately NOT configurable: the tier names are the
# shared word between a ticket, a skill and this resolver, and a project that
# renamed them would break every skill that says "implementer" while the docs
# gate stayed green. Add a MAPPING in the config; do not add a tier here.
AGENT_TIERS='planner implementer mechanical reviewer'

# MODULE GLOBALS, and why they diverge from guards.lib.sh's convention.
#
# guards.lib.sh takes its "directory of the calling script" as a PARAMETER,
# because it is only ever sourced by a script that knows where it lives. This
# file is also EXECUTED directly (`sh scripts/agents.lib.sh implementer` — the
# seam an agent following a SKILL.md actually uses), and in that case the only
# thing that knows the directory is `$0`, which is read at the bottom of the
# file, long after resolve_tier's signature is fixed at one argument: the tier.
# Threading the directory through as a second parameter would put a value the
# CALLER cannot supply into the caller's hands. So it is a global, set once by
# the direct-execution branch, and overridable by a sourcing caller that wants
# order 3.
#
# The other two globals are per-process memos: config loading and the unmapped
# warning both have to happen at most once no matter how many tiers a single
# process resolves.
_agents_here=${_agents_here:-.}
_agents_config_loaded=0
_agents_config_tried=0
_agents_warned=0

agents_usage() {
	echo "usage: agents.lib.sh <tier>" >&2
	echo "  tier is one of: $AGENT_TIERS" >&2
}

# agents_load_config — source the mapping, once per process.
#
# Returns 0 when a config was loaded, 1 when none exists anywhere (not an
# error — see the unconfigured default above), 2 when an explicitly named one
# is missing.
#
# The MISS is memoized too, not only the hit. An unconfigured project is the
# common case, and every resolve_tier call in it would otherwise re-run
# `git rev-parse` and two stat calls to reach the same "no" — a caller that
# resolves four tiers pays for that four times, for nothing.
#
# Only the genuine "no config anywhere" miss is remembered. An explicitly named
# AGENTS_CONFIG that does not exist keeps failing on every call, loudly: that is
# a caller error, and a caller error that reports itself once and then goes
# quiet is worse than one that keeps saying so.
agents_load_config() {
	[ "$_agents_config_loaded" = 1 ] && return 0
	[ "$_agents_config_tried" = 1 ] && return 1

	if [ -n "${AGENTS_CONFIG:-}" ]; then
		if [ ! -f "$AGENTS_CONFIG" ]; then
			echo "x agents: AGENTS_CONFIG=$AGENTS_CONFIG does not exist." >&2
			return 2
		fi
		. "$AGENTS_CONFIG"
		_agents_config_loaded=1
		return 0
	fi

	_al_root=$(git rev-parse --show-toplevel 2>/dev/null) || _al_root=
	if [ -n "$_al_root" ] && [ -f "$_al_root/scripts/agents.config.sh" ]; then
		. "$_al_root/scripts/agents.config.sh"
		_agents_config_loaded=1
		return 0
	fi

	if [ -f "$_agents_here/agents.config.sh" ]; then
		. "$_agents_here/agents.config.sh"
		_agents_config_loaded=1
		return 0
	fi

	_agents_config_tried=1
	return 1
}

# resolve_tier <tier> — print the mapped model id on stdout, diagnostics on
# stderr. Stdout carries the ANSWER and nothing else, so a caller can use it
# directly: `model=$(sh scripts/agents.lib.sh implementer)`.
resolve_tier() {
	if [ $# -ne 1 ] || [ -z "${1:-}" ]; then
		agents_usage
		return 2
	fi

	_rt_tier=$1
	_rt_known=0
	for _rt_t in $AGENT_TIERS; do
		[ "$_rt_t" = "$_rt_tier" ] && _rt_known=1 && break
	done
	if [ "$_rt_known" = 0 ]; then
		echo "x agents: unknown capability tier '$_rt_tier'." >&2
		echo "  The vocabulary is closed: $AGENT_TIERS." >&2
		echo "  It is defined in the manual layer; a ticket that needs another word needs a manual change first." >&2
		return 2
	fi

	agents_load_config
	_rt_load=$?
	[ "$_rt_load" = 2 ] && return 2

	# Tier name -> variable name. The tier is already whitelisted above, so the
	# eval can only ever expand one of four literal variable names.
	_rt_upper=$(printf '%s' "$_rt_tier" | tr 'a-z' 'A-Z')
	eval "_rt_value=\${AGENT_TIER_${_rt_upper}:-}"

	if [ -z "$_rt_value" ]; then
		if [ "$_agents_warned" = 0 ] && [ "${AGENTS_TIER_QUIET:-}" != "1" ]; then
			_agents_warned=1
			echo "!  agents: capability tier '$_rt_tier' is UNMAPPED — no model configured." >&2
			echo "   Set AGENT_TIER_${_rt_upper} in scripts/agents.config.sh to map it." >&2
			echo "   Until then every tier runs on the session's own model, and the planner's" >&2
			echo "   cost/benefit decision has no effect on what anything actually costs." >&2
		fi
		return 0
	fi

	printf '%s\n' "$_rt_value"
	return 0
}

# Direct execution: the seam an agent following a SKILL.md actually uses, since
# an agent runs commands rather than sourcing shell libraries. Sourcing callers
# fall through with only the functions defined.
case "$0" in
*/agents.lib.sh | agents.lib.sh)
	_agents_here=$(dirname "$0")
	resolve_tier "$@"
	exit $?
	;;
esac
