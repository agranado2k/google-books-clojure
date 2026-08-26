#!/bin/sh
# agents.config.sh — the one place the kit learns which MODEL your provider
# gives each capability tier.
#
# This file is DATA, not mechanism. `scripts/agents.lib.sh` holds the resolver
# and the closed vocabulary; every provider-specific and price-specific fact
# lives here, in one reviewable file, so re-pointing a tier at a cheaper or
# newer model is a diff a human reads rather than an edit inside a script.
#
# It is read by:
#   scripts/agents.lib.sh   (`resolve_tier <tier>`), which /to-tickets and
#                            /implement call when deciding how to spawn.
#
# THIS FILE IS YOURS. It is not part of the shared layer (see VERSION), it is
# not overwritten by a kit update, and editing it is the intended workflow —
# the same arrangement as scripts/guards.config.sh, for the same reason: the
# kit owns mechanism, your repo owns policy.
#
# ---------------------------------------------------------------------------
# WHY THE KIT SHIPS THIS EMPTY, AND WILL KEEP SHIPPING IT EMPTY
# ---------------------------------------------------------------------------
# Model identifiers are the fastest-rotting constant a framework could carry.
# They are renamed, deprecated and repriced on a vendor's schedule, they differ
# per provider, and the cheap tier of one release is the expensive tier of the
# next. A kit that shipped one would be shipping a standing instruction with a
# timer on it — and shared invariant §8 is precisely about stale standing
# instructions being worse than absent ones.
#
# So: the kit names the FOUR TIERS and never a model. You name the models.
#
# UNSET IS A WORKING STATE. An unmapped tier resolves to nothing, the caller
# passes no model parameter, and the spawned agent inherits the session's own
# model — exactly what happens today without any of this. The resolver warns
# once per process so the gap is visible, and passes.
#
# ---------------------------------------------------------------------------
# THE VOCABULARY
# ---------------------------------------------------------------------------
# Defined in the manual layer (the root manual's "Capability tiers" section and
# the local workflow article), because choosing a tier is a human process rule.
# Repeated here only as the shape of the decision each variable encodes:
#
#   planner      Judgement over breadth. Decomposition, design, architecture,
#                triage of an ambiguous bug. Reads a lot, writes little, and a
#                wrong answer costs a whole wave of downstream work.
#   implementer  Judgement over depth. Building one ticket test-first through
#                seams it has to find. The default for real work.
#   mechanical   No judgement required, and a checkable definition of done. A
#                rename across call sites, a codemod, a dependency bump, the
#                contract half of an expand-migrate-contract. Cheap is correct
#                here: the test suite is the oracle, not the model.
#   reviewer     Judgement over a finished diff, in fresh context. Adversarial
#                reading rather than production. Undersizing this one is how a
#                review becomes a rubber stamp.
#
# Set each to whatever identifier YOUR agent harness expects in its spawn call.
# The adapter note for your harness says where that value goes — see
# `adapters/claude-code/README.md` for one worked example.
#
# Examples of the SHAPE (not real identifiers — deliberately):
#   AGENT_TIER_PLANNER='<your provider's strongest reasoning model>'
#   AGENT_TIER_MECHANICAL='<your provider's cheapest capable model>'

# ---------------------------------------------------------------------------
# THIS REPO'S MAPPING — Anthropic models, spawned by Claude Code
# ---------------------------------------------------------------------------
# The values below are FAMILY ALIASES (`opus` `sonnet` `haiku` `fable`), not
# dated API model identifiers such as `claude-opus-5`. That is not a shortcut,
# it is what the harness accepts: Claude Code's sub-agent spawn (`Task`/`Agent`)
# takes `model` as a closed enum of exactly those four words, and a full API id
# is rejected by the tool call before any request is made. See
# `adapters/claude-code/README.md` — "whatever identifiers your account can
# actually invoke", which here means the harness's enum, not the provider's.
#
# The alias resolving to a newer model on its own schedule is the POINT: it is
# the one form of this value that does not rot, which is exactly the failure the
# kit refuses to ship (see the note above). What a family means — planner-grade
# vs cheap-and-checkable — is stable in a way a dated id is not.
#
# A project driven by a different harness, or one that needs a pinned model for
# reproducibility, sets dated ids here instead. Nothing else changes: the
# resolver only ever prints this string.

# ---------------------------------------------------------------------------
# 1. PLANNER — decomposition, design, triage
# ---------------------------------------------------------------------------
# The strongest generally-available reasoning family. A wrong decomposition is
# paid for by every downstream ticket in the wave, and this repo's waves are
# DAGs of 8-10 tickets (PRD #1), so the multiplier is real.
AGENT_TIER_PLANNER='opus'

# ---------------------------------------------------------------------------
# 2. IMPLEMENTER — one ticket, test-first, through the seams
# ---------------------------------------------------------------------------
# A strong general model, and deliberately NOT the same family as the reviewer.
# `/implement`'s Deliver phase requires its review to run on a different tier
# than the code's author; with implementer and reviewer both on `opus` that
# rule would be satisfied on paper and violated in fact.
AGENT_TIER_IMPLEMENTER='sonnet'

# ---------------------------------------------------------------------------
# 3. MECHANICAL — checkable definition of done, no judgement required
# ---------------------------------------------------------------------------
# The cheapest family that can hold the task. The oracle here is `clojure -X:test`
# plus `scripts/check.sh`, not the model's judgement — capability past "can
# follow the pattern" buys nothing, and this is the tier that saves real money.
AGENT_TIER_MECHANICAL='haiku'

# ---------------------------------------------------------------------------
# 4. REVIEWER — adversarial reading of a finished diff, in fresh context
# ---------------------------------------------------------------------------
# Above the implementer, on purpose. A review is a verdict, and an under-sized
# verdict is a rubber stamp that looks like coverage. `fable` would be stronger
# still and costs more than opus-tier; opus is the sized-right default, and this
# is the line to raise if a review ever misses something a human then catches.
AGENT_TIER_REVIEWER='opus'
