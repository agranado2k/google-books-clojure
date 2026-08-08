# `evals/` — the prompt-eval tier, as an EXAMPLE

> **This is the least copyable thing in this adapter, and the framing matters
> more than the files.** The layout, the grading design and the CI
> classification transfer to any project. The *cases* do not: they are
> assertions about one product's tool descriptions, and against your surfaces
> they are noise. Nothing here runs until you generate fixtures from your own
> source — see [Before this runs](#before-this-runs).

## When this tier is worth having

Wire it only if your repo ships an **agent-facing prompt surface**: an MCP
server's tool descriptions, a system-instructions string, a packaged skill, a
prompt template that goes out with the product. Those are shipped product text
where a one-word edit moves agent behaviour, and no compiler, linter or unit
test will ever notice.

If you have no such surface, skip this directory entirely. You are not missing
a gate; you are declining to pay for one you cannot read.

**Evals are to prompts what tests are to code.** That analogy is the whole
design rationale, and it is worth taking literally: red first, one case per
observed failure, and a fast keyless tier that runs on every push.

## Layout

```
promptfooconfig.yaml     the suite: provider, prompt, default assertion, case files
prompts/client.js        system = your shipped instructions, user = the scenario
asserts/tool-selection.js  the CODE grader: outcomes, partial credit, no LLM
golden-set/
  example.yaml           ONE illustrative case file — positive and negative
fixtures/                GENERATED, never hand-written (see below). Not shipped.
  instructions.txt         from your instructions source
  tools.json               from your tool registrations
```

## Two tiers, one of which costs money

| Tier | What it proves | Needs a key? | Runs in |
| --- | --- | --- | --- |
| **Smoke** | the harness is well-formed: the config parses, every `file://` it names exists, every case carries a reference solution, the generated fixtures still match the live source, and the grader itself behaves | **No** | your ordinary `pnpm test` |
| **Eval** | the shipped prompt surface produces the right agent behaviour | **Yes** | `workflows/prompt-evals.yml`, path-scoped |

The smoke tier deliberately asserts **nothing** about model behaviour. Faking
behavioural coverage in the keyless tier would be exactly the "make the test ask
for less" failure mode the mutation tier exists to detect.

Write the smoke tier yourself, in your own runner — it is a handful of
assertions over YAML and it is where most of this tier's day-to-day value is.
It is not shipped here because it is the part most coupled to your test
framework, and a broken import is a worse starting point than a blank file.

## Before this runs

`fixtures/` is **not** in this directory, and that absence is deliberate.

Both fixtures are **generated from your own source** — the instructions string
and the tool definitions your server actually registers — and then checked in,
so the CI eval job needs no build step. Shipping a fake pair here would give you
a suite that runs immediately and measures nothing, which is worse than one that
refuses to start.

So the wiring you owe this tier is:

1. a small script that imports your instructions/tool registrations and writes
   `fixtures/instructions.txt` and `fixtures/tools.json`;
2. a smoke-tier assertion that the checked-in fixtures still match what that
   script produces **right now**. This is the load-bearing one: it turns "the
   author forgot to regenerate" into a red fast gate, and it makes every
   prompt-surface change show up as a reviewable fixture diff in the PR.

Nothing in `fixtures/` is ever hand-edited. The moment it is, the suite is
measuring text that is not shipped.

## Anatomy of a case

Everything the grader needs lives under `metadata`. See
[`golden-set/example.yaml`](golden-set/example.yaml) for two fully commented
cases (one positive, one negative).

| Key | Meaning |
| --- | --- |
| `polarity` | `positive` \| `negative`. Balanced across the suite — see below |
| `expected_tools` | the reference solution. `[]` means "nothing should fire" |
| `expected_any_of` | `true` ⇒ any one of `expected_tools` is correct |
| `acceptable_tools` | legitimate lookups on the way; never penalised |
| `forbidden_tools` | calling one is a HARD fail, at any score |
| `expected_args` | the reference ARG SHAPE — `required` / `forbidden` keys, `equals` for enums. Not exact values |
| `min_score` | the per-case pass bar. Default 1.0 |
| `grounded_in` | the shipped file(s) this was drawn from. Must exist |
| `rationale` | why this case exists and what breaks if it goes red |

### How grading works

`asserts/tool-selection.js` runs on **every** case and scores the *outcome*, not
the path, with partial credit:

| Component | Weight | Question |
| --- | --- | --- |
| coverage | 0.6 | did the tool(s) that should fire, fire? (or, for a no-tool case, did nothing fire?) |
| restraint | 0.2 | nothing forbidden, nothing unrelated |
| arguments | 0.2 | required keys present, forbidden keys absent, enums equal |

Partial credit is the point: 0.9 ("right tool, one argument missing") and 0.4
("never called it") are different failures, and the `reason` string says which.

One thing sits **outside** the weighted score: calling a `forbidden_tools` entry
fails the case outright, whatever the score and whatever the bar. Restraint is
only worth 0.2, so a case that lowered its bar to 0.8 would otherwise also pass
a run that called the expected tool *and* a forbidden one.

### The per-case bar (`min_score`)

Partial credit only changes anything if a case can act on it.

- **Default 1.0.** With no `min_score`, every component must be perfect.
  Lowering the bar is an opt-in a case makes *for itself*, in the case data,
  where a reviewer sees it in the diff — never a global loosening.
- **Lower it only for a known, accepted partial answer**, and say why in
  `rationale`.
- **It is validated, not trusted.** A value outside `(0, 1]`, or a non-number
  (the YAML quoting slip `min_score: "0.8"` is the common one), is *ignored*:
  the grader falls back to the strict 1.0 and appends a warning to `reason` on
  **both** verdicts. `min_score: 0` would otherwise pass literally every run,
  silently deleting the case. The warning rides on passes too, because a
  misconfigured bar on a case that happens to pass is exactly the one a failure
  report would never show.

### Code-graded, never LLM-judged

"Which tool fired, with what argument shape" is a decidable question. Deciding
it with a second model buys noise and a bill.

Reserve a rubric judge for the one dimension that genuinely cannot be
string-matched — "is this paragraph's description of the trust boundary
accurate?" — and then constrain it: one isolated dimension, an explicit
threshold, a "return Unknown" escape hatch, and **a different model from the
generator**. Cap the number of judged cases and assert that cap in the smoke
tier, because `k` repeats multiply the judge calls too.

## Growing the set — from real failures, not imagination

Target 20–50 cases **drawn from observed failures**. A case invented at a desk
measures your imagination; a case harvested from a failure measures the product.

Hold each polarity to a **share** of the suite (30% is a workable floor), not an
absolute count. An absolute floor is only meaningful at the bottom of the range:
at 40 cases a floor of 5 accepts a 35/5 split — nominally balanced, effectively
one-sided. A proportional floor means harvesting a run of positive failures
obliges you to harvest negative ones too. Negative cases are what stop the suite
from measuring only eagerness, and eagerness is the failure mode a sharpened
tool description most easily creates.

When a prompt-surface change makes an agent do the wrong thing — in CI, in
dogfooding, in a bug report:

1. **Reproduce it as a scenario.** One user turn, in the user's own words. Do
   not sanitise it into the phrasing that makes the right tool obvious.
2. **Add it to the right file** by polarity.
3. **Write the reference solution and `grounded_in`** — the shipped file that
   *should* have prevented it. If you cannot name one, the fix is a prompt edit,
   not just a new case.
4. **Write `rationale` as the failure you saw**, not a restatement of the case.
5. **Watch it go red**, fix the surface, watch it go green. Same loop as TDD.

**Saturation review.** A suite at 100% is a regression gate, not a research
tool. When every case has passed for several consecutive prompt-surface PRs,
that is a signal to harvest harder cases — not that the surface is finished.

## pass^k — the consistency gate

Run every case `k` times and fail if **any** trial fails. A prompt that works
two times in three is a prompt that does not work. Three is a reasonable `k` for
a per-PR job, because cost scales linearly with it; raise it if flaky cases
start slipping through.

Check that your runner's response cache is namespaced per repeat index —
otherwise the `k` trials are one call replayed, and the gate measures nothing.

## Cost control

- **Cache provider responses.** Pinned by prompt + model, so a PR that does not
  touch the surface re-runs mostly from cache — and a PR that *does* re-bills,
  which is exactly right: that is when the eval needs to actually run.
- **Path-scope the trigger** to the files the suite can observe, and be strict.
  Paying for a run on a file no case loads is pure cost with no signal. Name the
  exclusions and their reasons in the workflow, or the list will quietly grow
  back.
- **A cheap generator and a cheaper judge.**

## What this tier does NOT measure

Write your own version of this section, and put it above the fold. It is the
part a green run will otherwise be read as covering.

For the suite as shipped here, the honest list is: only the text that actually
enters the prompt. A packaged skill file, a prompt template your server
registers but this suite never loads, tool *annotations* that have no field in
the provider's tool schema — none of them are measured, however green the job
is. And no handler is ever invoked: this tier grades which tool the model picks
and with what arguments, never what the tool then does.
