# Interface Design

When the human wants to explore alternative interfaces for a chosen deepening candidate, use this parallel sub-agent pattern. Based on "Design It Twice" (Ousterhout) — your first idea is unlikely to be the best.

Uses the vocabulary in [LANGUAGE.md](./LANGUAGE.md) — **module**, **interface**, **seam**, **adapter**, **leverage**.

## Process

### 1. Frame the problem space

Before spawning sub-agents, write a human-facing explanation of the problem space for the chosen candidate:

- The constraints any new interface would need to satisfy
- The dependencies it would rely on, and which category they fall into (see [DEEPENING.md](./DEEPENING.md))
- A rough illustrative sketch to ground the constraints — not a proposal, just a way to make the constraints concrete

Show this, then immediately proceed to Step 2. The human reads and thinks while the sub-agents work in parallel.

### 2. Spawn sub-agents

Spawn 3+ sub-agents in parallel. Each must produce a **radically different** interface for the deepened module. Interface design is `planner`-tier work: resolve the model with `sh scripts/agents.lib.sh planner` before spawning, and pass nothing if the resolver prints nothing — an unmapped tier means the spawn inherits this session's model, which is a working state.

Each sub-agent gets its own technical brief in **fresh context** (shared invariant §4): file paths, coupling details, dependency category, what sits behind the seam. The brief is independent of the human-facing framing from Step 1 — a sub-agent that reads your framing designs your idea again. Give each one a different design constraint:

- Agent 1: "Minimise the interface — aim for 1–3 entry points max. Maximise leverage per entry point."
- Agent 2: "Maximise flexibility — support many use cases and extension."
- Agent 3: "Optimise for the most common caller — make the default case trivial."
- Agent 4 (if applicable): "Design around ports & adapters for the cross-seam dependencies."

Include both [LANGUAGE.md](./LANGUAGE.md) vocabulary and the project's own `docs/domain-glossary.md` in every brief, so each sub-agent names things consistently with the architecture language *and* the domain language. Sub-agents that invent their own names produce designs that cannot be compared.

Each sub-agent outputs:

1. Interface (types, methods, params — plus invariants, ordering, error modes)
2. Usage example showing how callers use it
3. What the implementation hides behind the seam
4. Dependency strategy and adapters (see [DEEPENING.md](./DEEPENING.md))
5. Trade-offs — where leverage is high, where it's thin
6. The first test it would be worth writing at that interface, and which tier it lands in

### 3. Present and compare

Present designs sequentially so the human can absorb each one, then compare them in prose. Contrast by **depth** (leverage at the interface), **locality** (where change concentrates), and **seam placement**.

After comparing, give your own recommendation: which design you think is strongest and why. If elements from different designs would combine well, propose a hybrid. Be opinionated — a menu is not a recommendation.

The chosen design is an input to `/to-tickets`, not a licence to start editing: the deepening is a behaviour-preserving change and gets its own ticket and its own `/implement` session (shared invariant §10).
