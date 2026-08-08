# ADR format

This project records architectural decisions as **ADRs in
[MADR](https://adr.github.io/madr/) format**.

- **Registry**: `docs/adr/INDEX.md` — the table that alone answers "what is
  currently binding?", including supersessions.
- **Skeleton**: `docs/adr/NNNN-template.md` — copy it, do not retype it.
- **Numbering**: `NNNN-short-kebab-title.md`, zero-padded to four digits, next
  number in sequence per `INDEX.md`. Numbers are never reused, not even for a
  rejected record.

If the root `AGENTS.md` names different paths, it wins — it is the manual, this
is a sidecar.

## ADR, diary entry, or manual?

Three homes, one rule each (shared invariant §11: every rule has exactly one
home, and everywhere else points at it).

- **An ADR under `docs/adr/`** — an architectural decision that constrains future
  code: which patterns to use, which boundaries to draw, which tool to
  standardize on. MADR format, its own file, and the Status field is the
  contract.
- **A dated entry in `docs/diary.md`** — the chronological development log: what
  happened, debugging stories, progress. A diary entry may reference an ADR by
  number but is never the source of truth for a decision.
- **`AGENTS.md` and the `constitution/` articles** — standing operating
  instructions for anyone (human or agent) working in the repo: style rules,
  boundaries, the quick-reference map. They do not *contain* decisions; they
  point at the ADR that made them.

If a grilling session lands on a decision that constrains future code or
interfaces, write it as a proper ADR. Otherwise the outcome belongs in the diary
or directly in the code under development.

## The MADR sections

Open `docs/adr/NNNN-template.md` for the authoritative shape. The sections:

1. **Title** — `# ADR-NNNN: short kebab description`
2. **Status, Date, Deciders, Supersedes / Superseded by** — the front-matter list
3. **Context and problem statement**
4. **Decision drivers**
5. **Considered options** — at least two; "no change" is a valid option
6. **Decision outcome** — which option, with rationale and consequences split
   into Positive / Negative / Neutral
7. **Pros and cons of the options** — per option, brief
8. **More information** — sources, related ADRs, glossary cross-references

Two rules that outrank the shape:

- **Write the ADR when the decision is made**, not when the code lands. One
  written after the fact documents a rationalization, not a decision.
- **A reversal is a new record.** Never edit the old one; set its status to
  `Superseded by NNNN`. An amendment that only narrows or clarifies the same
  decision may be recorded in place, dated and labelled — a reversal never is.

Do NOT invent a different ADR format here. The project's MADR convention is the
contract; tools should produce records in that shape.

---

*Replaces the upstream `ADR-FORMAT.md` from
[mattpocock/skills](https://github.com/mattpocock/skills) — MIT, see
`.claude/skills/LICENSE-mattpocock-skills.md`. Upstream proposes its own ADR
template; this kit ships a MADR skeleton and an index, so the skill writes into
those.*
