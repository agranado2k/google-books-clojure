# ADR-NNNN: <short title, stated as the decision — "Adopt X", not "X?">

<!--
MADR (https://adr.github.io/madr/). Copy this file to NNNN-short-kebab-title.md,
fill it in, and add a row to INDEX.md in the SAME commit.

This file deliberately uses <angle-bracket> marks rather than the kit's
double-brace placeholders: it is a working template that lives on in this repo,
and the docs gate rejects an unstamped double-brace mark in any non-template
file. Nothing stamps this one — you do.

Delete the comments as you fill each section in. An ADR with the guidance still
in it has not been written yet.
-->

- **Status**: Proposed | Accepted | Rejected | Deprecated | Superseded by NNNN
- **Date**: YYYY-MM-DD
- **Deciders**: <who actually decided — names, not "the team">
- **Supersedes / amends**: <ADR-NNNN, and in what respect — or "—">
- **Superseded by**: <ADR-NNNN — or "—">

## Context and problem statement

<!--
The forcing situation, in two or three paragraphs. What broke, what is about to,
or what cannot be built until this is settled. Include the concrete incident,
number, or constraint that made it urgent — a reader six months out must be able
to tell whether the pressure still exists.

State the problem, not the answer. If this section already implies exactly one
option, the decision was made somewhere else and this is a write-up.
-->

## Decision drivers

<!--
The criteria the options are judged against, ranked when they conflict. These
are what make the outcome checkable later: a driver that no option is scored on
is decoration.
-->

- <driver>
- <driver>

## Considered options

<!--
At least two, and at least one that was genuinely tenable. Each gets its verdict
inline with the REASON — a rejected option with no recorded reason gets
re-proposed every quarter. Mark the chosen one.
-->

1. **<option>** *(chosen)* — <one line on what it is>
2. **<option>** — rejected: <why, in terms of the drivers above>

## Decision outcome

<!--
THE CONTRACT. This is the section other documents, reviews, and agents cite, so
it must be readable on its own. Numbered clauses, each stating one thing that is
now true. Include the boundaries: what this decision explicitly does NOT cover,
and any non-goal worth pinning so it is not quietly assumed later.
-->

Chosen: **<option>**.

1. <clause>
2. <clause>
3. **Explicit non-goal**: <what this does not decide>

## Consequences

<!--
Honest, both directions. A consequences list with no cost in it is marketing.
Record known weak points here rather than discovering them in a post-mortem —
"this guard depends on X and would not catch Y" is the most valuable line an ADR
can contain.
-->

- **Good**: <what this buys>
- **Bad / trade-off**: <what it costs, who pays it, and when>
- **Neutral**: <what changes without being better or worse>
- **Honest limitation**: <what this does NOT protect against>

## More information

<!--
Short. Links, the PR/issue that implemented it, measurements, prior art, and any
later amendment (dated, labelled, and never rewriting the clause above it).
-->

- Implemented in: <PR / issue>
- Related: <ADR-NNNN, spec section, external reference>
