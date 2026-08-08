---
name: to-prd
description: Turn the current conversation context into a PRD and publish it to the project issue tracker. Use when user wants to create a PRD from the current context.
---

This skill takes the current conversation context and codebase understanding and produces a PRD. Do NOT interview the user — just synthesize what you already know. (If the requirements are not settled yet, that is `/grill-me` or `/grill-with-docs`, not this.)

## Before you start

- **The tracker.** Publish to whatever issue tracker the project uses; the root `AGENTS.md` or `constitution/local-workflow.md` names it. If neither does, ask once and then record the answer there rather than in this file.
- **The autonomy label.** Shared invariant §6: every ticket carries an explicit autonomy label, and ambiguity resolves to human-in-the-loop. This kit's mechanism is a single `ready-for-agent` label — its presence means an agent may take the work solo, its absence means a human stays in the loop. There is no literal `HITL` label; absence *is* the signal.

## Process

1. Explore the repo to understand the current state of the codebase, if you haven't already. Use `docs/domain-glossary.md` vocabulary throughout the PRD, and respect the decision records in `docs/adr/` that cover the area you're touching.

2. Sketch out the seams at which you're going to test the feature. Existing seams should be preferred to new ones. Use the highest seam possible. If new seams are needed, propose them at the highest point you can.

Check with the user that these seams match their expectations.

3. Write the PRD using the template below, then publish it to the project issue tracker with the `ready-for-agent` label if the work is mechanical with a checkable definition of done — no additional triage needed.

<prd-template>

## Problem Statement

The problem that the user is facing, from the user's perspective.

## Solution

The solution to the problem, from the user's perspective.

## User Stories

A LONG, numbered list of user stories. Each user story should be in the format of:

1. As an <actor>, I want a <feature>, so that <benefit>

<user-story-example>
1. As a mobile bank customer, I want to see balance on my accounts, so that I can make better informed decisions about my spending
</user-story-example>

This list of user stories should be extremely extensive and cover all aspects of the feature.

## Implementation Decisions

A list of implementation decisions that were made. This can include:

- The modules that will be built/modified
- The interfaces of those modules that will be modified
- Technical clarifications from the developer
- Architectural decisions
- Schema changes
- API contracts
- Specific interactions

Do NOT include specific file paths or code snippets. They may end up being outdated very quickly.

Exception: if a prototype produced a snippet that encodes a decision more precisely than prose can (state machine, reducer, schema, type shape), inline it within the relevant decision and note briefly that it came from a prototype. Trim to the decision-rich parts — not a working demo, just the important bits.

## Testing Decisions

A list of testing decisions that were made. Include:

- A description of what makes a good test (only test external behavior, not implementation details)
- Which modules will be tested
- Prior art for the tests (i.e. similar types of tests in the codebase)

## Out of Scope

A description of the things that are out of scope for this PRD.

## Further Notes

Any further notes about the feature.

</prd-template>

## What happens next

A PRD that spans more than one context window goes through `/to-tickets` before any code is written (shared invariant §1). One that fits a single window can go straight to `/implement`.

---

*Adapted from `engineering/to-prd` in [mattpocock/skills](https://github.com/mattpocock/skills) — MIT, see `.claude/skills/LICENSE-mattpocock-skills.md`. Upstream expects a separate setup skill to have supplied the tracker and label vocabulary; here that vocabulary is the kit's own autonomy-label mechanism.*
