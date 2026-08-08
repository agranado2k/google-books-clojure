# Glossary format

The project's ubiquitous language lives in `docs/domain-glossary.md` (the root
`AGENTS.md` names the real path — trust it over this file if they disagree).
That file carries its own writing rules in its header; this sidecar is the short
version, for use mid-grilling.

## Entry shape

```md
## <Context name>

- **<Term>** — <what it IS, in one or two sentences>. <Kind: Aggregate root /
  Entity / Value Object / read type / port / adapter>. Ref: <ADR-NNNN or spec section>.
  - _Avoid_: <the near-synonym people reach for> (<why it is wrong here>).
```

And the half that is usually missing:

```md
## Words this project does not use

- **<banned word>** — ambiguous here (<why>). Use **<term>** or **<term>**.
```

## Rules

- **Be opinionated.** When multiple words exist for the same concept, pick the
  best one and list the others under `_Avoid_`. One name per concept: an agent
  given two names for one thing will invent a distinction between them.
- **Keep definitions tight.** One or two sentences max. Define what it IS, not
  what it does. When an entry needs a page of explanation, that page is a
  decision record and the entry points at it.
- **Only include terms specific to this project's domain.** General programming
  concepts (timeouts, error types, utility patterns) don't belong even if the
  project uses them constantly. Before adding a term, ask: is this a concept
  unique to this domain, or a general programming concept? Only the former
  belongs.
- **Group terms under subheadings** when natural clusters emerge — usually one
  `##` per bounded context, module, or subsystem. If all terms belong to one
  cohesive area, a flat list is fine.
- **Retire, don't delete.** Mark a superseded term _(superseded by `NewName`)_
  and say what replaced it. A deleted entry loses the fact that the old name
  ever meant something — exactly what a reader of old code needs.
- **Rename in one change.** Changing a term means renaming it across the whole
  codebase and updating the glossary in the same commit. No aliases.

## Multiple contexts

When the repo has several bounded contexts, either give each one a `##` section
in the single glossary, or give each one its own file and make
`docs/domain-glossary.md` the index that lists them and how they relate:

```md
## Contexts

- [Ordering](../src/ordering/GLOSSARY.md) — receives and tracks customer orders
- [Billing](../src/billing/GLOSSARY.md) — generates invoices and processes payments

## Relationships

- **Ordering → Fulfillment**: Ordering emits `OrderPlaced`; Fulfillment consumes it to start picking
- **Ordering ↔ Billing**: shared types for `CustomerId` and `Money`
```

Prefer the single file until it stops being readable in one session-start scan.
Two glossaries that both define the same term are the drift this whole document
exists to prevent.

---

*Replaces the upstream `CONTEXT-FORMAT.md` from
[mattpocock/skills](https://github.com/mattpocock/skills) — MIT, see
`.claude/skills/LICENSE-mattpocock-skills.md`. Upstream puts the language in a
root `CONTEXT.md` with a `CONTEXT-MAP.md` for multi-context repos; this kit
already ships a glossary document, so the skill writes into that one instead of
creating a second home for the same rule.*
