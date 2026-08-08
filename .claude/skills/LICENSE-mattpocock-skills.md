# Attribution — Matt Pocock skills

Five of the skills in this directory were **copied and adapted** from
https://github.com/mattpocock/skills. The adaptation is the same in every case:
the upstream advice is universal, and the framework replaced the parts that
named one project's stack with pointers at the artifacts this kit establishes
(`constitution/`, `docs/adr/`, `docs/domain-glossary.md`, `scripts/`).

| Skill here | Upstream | What changed |
| --- | --- | --- |
| `grill-me/` | `productivity/grill-me` | verbatim |
| `grill-with-docs/` | `engineering/grill-with-docs` | glossary sidecar retargeted at `docs/domain-glossary.md`; ADR sidecar rewritten for MADR |
| `tdd/` | `engineering/tdd` | project-context prelude added to `SKILL.md`; the five sidecars are verbatim |
| `diagnose/` | `engineering/diagnose` | the architectural hand-off at the end points at a decision record instead of a skill this kit does not ship |
| `to-prd/` | `engineering/to-prd` | tracker/label setup replaced by the kit's autonomy-label mechanism |

Each of those five carries a one-line attribution note at the bottom of its own
`SKILL.md`, so the provenance survives being read out of context.

The upstream repository is licensed under the MIT License (verified 2026-06-03),
reproduced below.

## The skills that are NOT Matt Pocock's

`to-tickets/`, `implement/`, `prototype/`, `pr-iterate/`, `worktree-cleanup/` and
`merge-train/` were written for the project this framework was extracted from and
carry no upstream. `review-pr/` began as an in-house reviewer command in a
private repository and was substantially rewritten here — the second axis (the
spec & behavior confirm-list, shared invariant §5) has no upstream at all.

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
