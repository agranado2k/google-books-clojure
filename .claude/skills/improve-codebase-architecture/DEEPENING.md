# Deepening

How to deepen a cluster of shallow modules safely, given its dependencies. Assumes the vocabulary in [LANGUAGE.md](./LANGUAGE.md) — **module**, **interface**, **seam**, **adapter**.

## Dependency categories

When assessing a candidate for deepening, classify its dependencies. The category determines how the deepened module is tested across its seam — and **which test tier that lands in is a local question**: `constitution/local-engineering.md` names this stack's tiers and the command that runs each one.

### 1. In-process

Pure computation, in-memory state, no I/O. Always deepenable — merge the modules and test through the new interface directly. No adapter needed. This is the cheapest tier your project has.

### 2. Local-substitutable

Dependencies that have a faithful local stand-in — an in-process build of your database, a virtual filesystem, a fake clock. Deepenable if the stand-in exists. The deepened module is tested with the stand-in running inside the suite. The seam is internal; no port at the module's external interface.

Before recommending this, check whether the stand-in actually exists in this project: `constitution/local-engineering.md` and the test setup say what is already wired. "Deepenable once you build a stand-in" is a different, larger recommendation, and should be labelled as one.

### 3. Remote but owned (ports & adapters)

Your own services across a network boundary. Define a **port** (interface) at the seam. The deep module owns the logic; the transport is injected as an **adapter**. Tests use an in-memory adapter. Production uses the real transport adapter.

Recommendation shape: *"Define a port at the seam, implement a transport adapter for production and an in-memory adapter for testing, so the logic sits in one deep module even though it's deployed across a network."*

### 4. True external (mock)

Third-party services you don't control — a payment processor, a messaging gateway, a mapping provider. The deepened module takes the external dependency as an injected port; tests provide a mock adapter. Two adapters, so the seam is real by the rule below.

## Seam discipline

- **One adapter means a hypothetical seam. Two adapters means a real one.** Don't introduce a port unless at least two adapters are justified (typically production + test). A single-adapter seam is just indirection.
- **Internal seams vs external seams.** A deep module can have internal seams (private to its implementation, used by its own tests) as well as the external seam at its interface. Don't expose internal seams through the interface just because tests use them.

## Testing strategy: replace, don't layer

- Old tests on the shallow modules become waste once tests at the deepened module's interface exist — delete them. Leaving both is how a suite doubles in size without gaining a single new assertion about behaviour.
- Write new tests at the deepened module's interface. The **interface is the test surface**.
- Tests assert on observable outcomes through the interface, not internal state.
- Tests should survive internal refactors — they describe behaviour, not implementation. If a test has to change when the implementation changes, it's testing past the interface.

**Deleting tests is exactly the move a pairing guard is built to be suspicious of** — `scripts/tdd-pairing-guard.sh` sees source changing and tests changing and cannot tell a replacement from a removal. So the replacement lands in one commit with the deepening, the commit message says which tests moved to which interface, and the coverage claim is made in the pull request where a human can check it (shared invariant §3: the suite is the specification, and a specification that got smaller needs a reason).
