# Attribution — Matt Pocock skills

Six of the skills in this directory were **copied and adapted** from
https://github.com/mattpocock/skills. The adaptation is the same in every case:
the upstream advice is universal, and the framework replaced the parts that
named one project's stack with pointers at the artifacts this kit establishes
(`constitution/`, `docs/adr/`, `docs/domain-glossary.md`, `scripts/`).

| Skill here | Upstream | What changed |
| --- | --- | --- |
| `grill-me/` | `productivity/grill-me` | verbatim |
| `grill-with-docs/` | `engineering/grill-with-docs` | glossary sidecar retargeted at `docs/domain-glossary.md`; ADR sidecar rewritten for MADR |
| `tdd/` | `engineering/tdd` | project-context prelude added to `SKILL.md`; the five sidecars are verbatim |
| `diagnose/` | `engineering/diagnose` | tool names in the feedback-loop list generalised; the architectural hand-off at the end names `/improve-codebase-architecture` and the decision record it produces |
| `to-prd/` | `engineering/to-prd` | tracker/label setup replaced by the kit's autonomy-label mechanism |
| `improve-codebase-architecture/` | `engineering/improve-codebase-architecture` **+** `engineering/codebase-design` | upstream splits the vocabulary into a separate `/codebase-design` skill; this kit ships no such skill, so that skill's glossary and its `DEEPENING.md` / `DESIGN-IT-TWICE.md` are folded in here as `LANGUAGE.md`, `DEEPENING.md` and `INTERFACE-DESIGN.md`. `CONTEXT.md` → `docs/domain-glossary.md`; the illustrative `ADR-0007` → citing a record by its own id; named stand-ins and third-party services → the role they play; upstream's `HTML-REPORT.md` → `PRESENTING.md`, a rendering-agnostic contract keeping the HTML scaffold as one worked example. Added: the capability-tier resolution for the sub-agents it spawns, and the `/to-tickets` hand-off that keeps a deepening out of a feature diff |

Each of those six carries a one-line attribution note at the bottom of its own
`SKILL.md`, so the provenance survives being read out of context.

Upstream is a moving repository. The table above was re-verified against
`mattpocock/skills` on 2026-08-09, at which point the skills live under a
`skills/<category>/` prefix (`skills/engineering/…`); the category paths in this
table are kept in the shorter form the rest of the kit cites.

The upstream repository is licensed under the MIT License (verified 2026-06-03),
reproduced below.

## The skills that are NOT Matt Pocock's

`to-tickets/`, `implement/`, `prototype/`, `pr-iterate/`, `worktree-cleanup/` and
`merge-train/` were written for the project this framework was extracted from and
carry no upstream. `review-pr/` began as an in-house reviewer command in a
private repository and was substantially rewritten here — the second axis (the
spec & behavior confirm-list, shared invariant §5) has no upstream at all.

`dogfood/` (the optional skill — see the README) has **no upstream either**, and
the check was made rather than assumed: `mattpocock/skills` was re-read on
2026-08-09 and ships no dogfooding, QA, end-to-end or browser skill of any kind.
It was generalized from an end-to-end QA command in that same extraction-source
project, whose own commit history credits the dogfooding pattern it implements
to Kieran Klaassen's publicly described verification harness. The pattern is
prior art and is credited as such; the wording here is this kit's, and the port
deliberately dropped two things the source had — the browser-only assumption,
and the repair loop.

---

```
MIT License

Copyright (c) 2026 Matt Pocock

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
