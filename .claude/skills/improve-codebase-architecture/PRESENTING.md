# Presenting the candidates

The architectural review is **one self-contained file, written outside the repo
tree**. That is the whole obligation, and it has two halves: self-contained,
because the review has to survive being sent to somebody who cannot run your
build; and outside the tree, because a repo that accumulates dated architecture
reviews has grown a second, staler glossary that nobody deletes.

The rendering is yours. Pick the richest one your project can actually view:

- **A single HTML file** when someone will open it in a browser — the diagrams
  carry the argument, and a picture of a shallow module beside a deep one lands
  in a way a bullet list does not. The worked scaffold below is that rendering.
- **A markdown file** when the review will be read in a terminal, pasted into a
  ticket, or folded into a decision record. The same cards, the same order,
  diagrams as fenced diagram-language blocks or ASCII.

What does not change with the rendering: the cards, their fields, the
before/after comparison, and the vocabulary discipline at the bottom of this
file.

## Header

Repo name, date, and a compact legend: solid box = module, dashed line = seam,
red arrow = leakage, thick dark box = deep module. No introduction paragraph —
straight into the candidates.

## Candidate card

The diagrams carry the weight. Prose is sparse, plain, and uses the glossary
terms ([LANGUAGE.md](./LANGUAGE.md)) without ceremony.

Each candidate is one card:

- **Title** — short, names the deepening (e.g. "Collapse the Order intake pipeline"). Domain nouns come from `docs/domain-glossary.md`.
- **Badge row** — recommendation strength (`Strong`, `Worth exploring`, `Speculative`), plus a tag for the dependency category (`in-process`, `local-substitutable`, `ports & adapters`, `mock` — see [DEEPENING.md](./DEEPENING.md)).
- **Files** — monospaced list.
- **Before / After diagram** — the centrepiece. Two columns, side by side. See patterns below.
- **Problem** — one sentence. What hurts.
- **Solution** — one sentence. What changes.
- **Wins** — bullets, ≤6 words each. e.g. "Tests hit one interface", "Pricing logic stops leaking", "Delete 4 shallow wrappers".
- **Decision-record callout** (if applicable) — one line, in a warning-tinted box, citing the record by its own id.

No paragraphs of explanation. If the diagram needs a paragraph to be understood,
redraw the diagram.

## Diagram patterns

Pick the pattern that fits the candidate. Mix them. Don't make every diagram look
the same — variety is part of the point.

### Node-and-edge graph (the workhorse for dependencies / call flow)

Use a graph when the point is "X calls Y calls Z, and look at the mess." Colour
the leaking edges red and the deep module dark. A sequence diagram works well
for "before: 6 round-trips; after: 1."

### Hand-built boxes-and-arrows (when the layout engine fights you)

Modules as boxes with borders and labels; arrows drawn by hand. Reach for this
when you want the "after" diagram to feel like one thick-bordered deep module
with greyed-out internals — a general-purpose graph renderer will not give that
the right weight.

### Cross-section (good for layered shallowness)

Stack horizontal bands to show the layers a call passes through. Before: six
thin layers each doing nothing. After: one thick band labelled with the
consolidated responsibility.

### Mass diagram (good for "interface as wide as implementation")

Two rectangles per module — one for interface surface area, one for
implementation. Before: the interface rectangle is nearly as tall as the
implementation rectangle (shallow). After: the interface rectangle is short, the
implementation rectangle is tall (deep).

### Call-graph collapse

Before: a tree of calls as nested boxes. After: the same tree collapsed into one
box, with the now-internal calls faded inside it.

## Style guidance

- Lean editorial, not corporate-dashboard. Generous whitespace.
- Colour sparingly: one accent, plus red for leakage and amber for warnings.
- Keep diagrams small enough that before/after sits side by side without scrolling.
- Module labels inside diagrams read as schematic, not as UI: small, uppercase, tracked.

## Top recommendation section

One larger card. Candidate name, one sentence on why, a link to its card. That's
it.

## Tone

Plain English, concise — but the architectural nouns and verbs come straight from
[LANGUAGE.md](./LANGUAGE.md). Concision is not an excuse to drift.

**Use exactly:** module, interface, implementation, depth, deep, shallow, seam, adapter, leverage, locality.

**Never substitute:** component, service, unit (for module) · API, signature (for interface) · boundary (for seam) · layer, wrapper (for module, when you mean module).

**Phrasings that fit the style:**

- "Order intake module is shallow — interface nearly matches the implementation."
- "Pricing leaks across the seam."
- "Deepen: one interface, one place to test."
- "Two adapters justify the seam: the real transport in production, in-memory in tests."

**Wins bullets** name the gain in glossary terms: *"locality: bugs concentrate in
one module"*, *"leverage: one interface, N call sites"*, *"interface shrinks;
implementation absorbs the wrappers"*. Don't write *"easier to maintain"* or
*"cleaner code"* — those terms aren't in the glossary and don't earn their place.

No hedging, no throat-clearing, no "it's worth noting that…". If a sentence could
be a bullet, make it a bullet. If a bullet could be cut, cut it. If a term isn't
in [LANGUAGE.md](./LANGUAGE.md), reach for one that is before inventing a new
one.

---

## Worked example: a single HTML file

One rendering, not the rule. It reaches for a utility CSS framework and a
diagram library over the network, which buys a lot of visual quality for very
little markup — and costs a network connection at *view* time. If the review
will be read somewhere without one, or your project's conventions forbid
third-party origins, write the markdown rendering instead; nothing above changes.

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <title>Architecture review — the repo's name</title>
    <!-- utility CSS framework, from a CDN -->
    <!-- diagram library, from a CDN, initialised to render on load -->
    <style>
      /* small custom layer for what utility classes don't cover cleanly:
         dashed seam lines, hand-drawn-feeling arrow heads, etc. */
      .seam { stroke-dasharray: 4 4; }
      .leak { stroke: #dc2626; }
      .deep { background: linear-gradient(135deg, #0f172a, #1e293b); }
    </style>
  </head>
  <body>
    <main>
      <header>…</header>
      <section id="candidates">…</section>
      <section id="top-recommendation">…</section>
    </main>
  </body>
</html>
```

Write it to `<tmpdir>/architecture-review-<timestamp>.html`, resolving the temp
directory from `$TMPDIR` and falling back to `/tmp` (or `%TEMP%` on Windows).
Open it with the platform's own opener — `xdg-open` on Linux, `open` on macOS,
`start` on Windows — and give the absolute path either way, because the opener is
the part most likely to fail silently.

The only scripts in the page are the two library loads. The page is otherwise
static: no application code, no interactivity beyond the diagram library's own
rendering. A review that needs a runtime is not a review, it's a tool, and a tool
belongs in the repo where it can be tested.
