import assert from "node:assert/strict";
import { test } from "node:test";
import { denyEntryFromTerms } from "../config.mjs";
import { run } from "../validators/claude-md-refs.mjs";
import { cleanup, configWith, ctxFor, hasRule } from "./helpers.mjs";

// A conformant tree: an AGENTS.md whose executable references all resolve
// (`/tdd` is a repo skill, `/loop` is an agent-harness built-in on the config
// ignore list, the paths exist), plus the two tool shims that a bootstrapped
// project always has beside it.
//
// The shims belong in the BASE fixture, not in the shim tests alone, because
// `shim-invalid` fires wherever a root manual exists — which is exactly the
// semantics under test. A fixture with a manual and no shims is a broken
// project, and every fixture below that models a manual models a real one.
const SHIM = "<!-- Shim: the agent manual is AGENTS.md. Edit that file, not this one. -->\n@AGENTS.md\n";

const CONFORMANT = {
  "AGENTS.md": [
    "# Instructions",
    "Start with `/tdd <task>` for any code change.",
    "Compose with `/loop /pr-iterate <PR#>` for continuous iteration.",
    "Run the gate yourself with `scripts/check.sh` at any time.",
    "The `.githooks/pre-push` hook runs the docs gate.",
  ].join("\n"),
  "CLAUDE.md": SHIM,
  "GEMINI.md": SHIM,
  ".claude/skills/tdd/SKILL.md": "# tdd",
  ".claude/skills/pr-iterate/SKILL.md": "# pr-iterate",
  "scripts/check.sh": "#!/bin/sh\n",
  ".githooks/pre-push": "sh scripts/check.sh\n",
};

test("passes when every slash command and path reference resolves", () => {
  const ctx = ctxFor(CONFORMANT);
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

test("flags a slash command with no skill directory", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nRun \`/ghost-command\` to do nothing.`,
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "skill-missing"));
  assert.match(out[0].message, /ghost-command/);
  cleanup(ctx);
});

test("ignores commands on the config ignore list (agent-harness built-ins)", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nEscalate with \`/security-review\` before merging.`,
  });
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

test("does not treat multi-segment backticked paths as slash commands", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nThe API lives at \`/api/v1/reports\`.`,
  });
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

test("flags a referenced script path that does not exist", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nOr let \`scripts/ghost-guard.sh\` fire.`,
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "path-missing"));
  assert.match(out[0].message, /ghost-guard/);
  cleanup(ctx);
});

test("flags a referenced git hook that does not exist", () => {
  const files = { ...CONFORMANT };
  delete files[".githooks/pre-push"];
  const ctx = ctxFor(files);
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "path-missing"));
  assert.match(out[0].message, /pre-push/);
  cleanup(ctx);
});

test("flags a referenced docs/ path that does not exist", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nThe registry is \`docs/adr/GHOST-INDEX.md\`.`,
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "path-missing"));
  assert.match(out[0].message, /GHOST-INDEX/);
  cleanup(ctx);
});

test("still resolves references after a fenced code block (``` fences must not desync span pairing)", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": [
      "# Instructions",
      "1. Use a worktree:",
      "",
      "   ```bash",
      "   git worktree add worktree/<slug> -b <type>/<slug>",
      "   ```",
      "",
      "Then run `/ghost-command` to proceed.",
      "And `/tdd <task>` as usual.",
    ].join("\n"),
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "skill-missing"));
  assert.match(out[0].message, /ghost-command/);
  cleanup(ctx);
});

// The reason a manual reaches for `~~~` at all is to show a ``` fence verbatim —
// which leaves an odd number of stray backticks in the document. Unstripped,
// that inverts every span pairing after it and the validator goes blind.
test("still resolves references after a ~~~ fenced block (~~~ fences must not desync span pairing)", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": [
      "# Instructions",
      "1. Wrap shell snippets in a fence:",
      "",
      "   ~~~markdown",
      "   ```bash",
      "   git worktree add worktree/<slug> -b <type>/<slug>",
      "   ~~~",
      "",
      "Then run `/ghost-command` to proceed.",
      "And `/tdd <task>` as usual.",
    ].join("\n"),
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "skill-missing"));
  assert.match(out[0].message, /ghost-command/);
  cleanup(ctx);
});

test("ignores /-tokens in spans that don't open with a slash command (shell snippets, path args)", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nNever run \`rm -rf /tmp\` or \`chmod +x /usr/local/bin\` here.`,
  });
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

test("checks every /-token in a span that opens with a slash command", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nCompose \`/loop /ghost-command <PR#>\` for continuous runs.`,
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "skill-missing"));
  assert.match(out[0].message, /ghost-command/);
  cleanup(ctx);
});

test("checks .claude/skills and .claude/hooks literal path references", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": [
      CONFORMANT["AGENTS.md"],
      "The procedural skill is at `.claude/skills/tdd/SKILL.md`.",
      "See also `.claude/skills/ghost/SKILL.md`.",
      "Enforcement lives in `.claude/hooks/tdd-guard.sh`.",
    ].join("\n"),
  });
  const out = run(ctx);
  assert.equal(out.length, 2);
  assert.ok(out.every((v) => v.rule === "path-missing"));
  assert.match(out.map((v) => v.message).join(" "), /ghost/);
  assert.match(out.map((v) => v.message).join(" "), /tdd-guard/);
  cleanup(ctx);
});

test("stays silent when AGENTS.md does not exist (fixtures that don't model it)", () => {
  const ctx = ctxFor({ "docs/adr/INDEX.md": "# ADRs" });
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

// ── The tool shims ───────────────────────────────────────────────────────────
// AGENTS.md is the manual; CLAUDE.md and GEMINI.md are entry points other agent
// tools look for. Each must import the manual and hold nothing else — because a
// tool-specific file that CAN hold a rule eventually does, and then the repo has
// two manuals and no way to see the difference between them.

test("accepts a bare shim — the import line and nothing else", () => {
  const ctx = ctxFor({ ...CONFORMANT, "CLAUDE.md": "@AGENTS.md\n" });
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

test("accepts a shim carrying one comment line above the import", () => {
  const ctx = ctxFor({ ...CONFORMANT, "GEMINI.md": "<!-- imports the manual -->\n\n@AGENTS.md\n" });
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

test("flags a shim that has grown content of its own", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "CLAUDE.md": `${SHIM}\n## Extra rule\n\nAlways squash before merging.\n`,
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "shim-invalid"));
  assert.equal(out[0].file, "CLAUDE.md");
  assert.match(out[0].message, /content of its own/);
  cleanup(ctx);
});

// A second comment is a second place to write prose. One line saying "this is a
// shim" is metadata; a paragraph of them is a manual wearing a comment marker.
test("flags a shim carrying more than one comment line", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "CLAUDE.md": "<!-- a shim -->\n<!-- but also: never force-push -->\n@AGENTS.md\n",
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "shim-invalid"));
  cleanup(ctx);
});

test("flags a shim that never imports the manual", () => {
  const ctx = ctxFor({ ...CONFORMANT, "GEMINI.md": "<!-- a shim -->\n" });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "shim-invalid"));
  assert.equal(out[0].file, "GEMINI.md");
  assert.match(out[0].message, /never imports it/);
  cleanup(ctx);
});

test("flags a shim importing some other file than the root manual", () => {
  const ctx = ctxFor({ ...CONFORMANT, "CLAUDE.md": "@constitution/shared-invariants.md\n" });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "shim-invalid"));
  assert.match(out[0].message, /never imports it/);
  cleanup(ctx);
});

test("flags a shim that is listed in config but absent from the tree", () => {
  const files = { ...CONFORMANT };
  delete files["GEMINI.md"];
  const ctx = ctxFor(files);
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "shim-invalid"));
  assert.equal(out[0].file, "GEMINI.md");
  assert.match(out[0].message, /does not exist/);
  cleanup(ctx);
});

// The kit's own tree is the case this protects: it ships a manual TEMPLATE and
// no stamped files at all, so the shims are not missing — they have not been
// written yet. Same reasoning as the root manual and reachability: no manual, no
// opinion. A rule that fired here would make the kit's own repo permanently red.
test("stays silent about shims when there is no root manual to shim", () => {
  const files = { ...CONFORMANT };
  delete files["AGENTS.md"];
  delete files["CLAUDE.md"];
  delete files["GEMINI.md"];
  const ctx = ctxFor(files);
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

// Which entry points exist is policy, not a constant: a project that does not
// want one deletes it from the list rather than from the validator.
test("honours an empty shims list", () => {
  const files = { ...CONFORMANT };
  delete files["CLAUDE.md"];
  delete files["GEMINI.md"];
  const ctx = ctxFor(files, configWith({ shims: [] }));
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

// ── The article layer (constitution/*.md) ────────────────────────────────────
// The root delegates its elaboration to articles that agents load on demand.
// They are standing instructions like the root is, so a stale command or a dead
// path poisons context exactly the same way — same checks, same rules.

// The stack article, not the shared one: `/tdd` and `.githooks/pre-push` are
// exactly the local detail the portability guard below forbids in
// shared-invariants.md, so putting them there would conflate two rules.
const WITH_ARTICLE = {
  ...CONFORMANT,
  "AGENTS.md": [
    CONFORMANT["AGENTS.md"],
    "Elaboration lives in `constitution/local-engineering.md`.",
    "Process detail lives in `constitution/local-workflow.md`.",
  ].join("\n"),
  "constitution/local-workflow.md": "# Local workflow\nCurate commits before the PR.",
  "constitution/local-engineering.md": [
    "# Local engineering",
    "Tests are the target function — start with `/tdd`.",
    "The pairing guard lives in `.githooks/pre-push`.",
  ].join("\n"),
};

test("passes when a constitution article's references all resolve", () => {
  const ctx = ctxFor(WITH_ARTICLE);
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

test("flags a stale slash command inside a constitution article", () => {
  const ctx = ctxFor({
    ...WITH_ARTICLE,
    "constitution/local-engineering.md": [
      WITH_ARTICLE["constitution/local-engineering.md"],
      "Then run `/ghost-command` to finish.",
    ].join("\n"),
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "skill-missing"));
  assert.equal(out[0].file, "constitution/local-engineering.md");
  assert.match(out[0].message, /ghost-command/);
  cleanup(ctx);
});

test("flags a dead path referenced from inside a constitution article", () => {
  const ctx = ctxFor({
    ...WITH_ARTICLE,
    "constitution/local-workflow.md": "Run `scripts/ghost-guard.sh` before pushing.",
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "path-missing"));
  assert.equal(out[0].file, "constitution/local-workflow.md");
  assert.match(out[0].message, /ghost-guard/);
  cleanup(ctx);
});

test("flags a constitution article referenced from the root but missing on disk", () => {
  const files = { ...WITH_ARTICLE };
  delete files["constitution/local-engineering.md"];
  const ctx = ctxFor(files);
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "path-missing"));
  assert.equal(out[0].file, "AGENTS.md");
  assert.match(out[0].message, /local-engineering/);
  cleanup(ctx);
});

test("checks articles even when the root AGENTS.md is absent", () => {
  const ctx = ctxFor({
    "constitution/local-engineering.md": "Run `/ghost-command` first.",
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "skill-missing"));
  assert.equal(out[0].file, "constitution/local-engineering.md");
  cleanup(ctx);
});

test("flags an article the root never references (unreachable — no agent will load it)", () => {
  const ctx = ctxFor({
    ...WITH_ARTICLE,
    "constitution/shared-invariants.md": "# Shared invariants\nTests are the target function.",
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "article-unreferenced"));
  assert.equal(out[0].file, "constitution/shared-invariants.md");
  assert.match(out[0].message, /AGENTS\.md/);
  cleanup(ctx);
});

test("stays silent about reachability when there is no root AGENTS.md to be reachable from", () => {
  const ctx = ctxFor({
    "constitution/shared-invariants.md": "# Shared invariants\nTests are the target function.",
  });
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

// The article directory is policy, not a constant: a project that prefers the
// tool-native `.claude/constitution/` sets `constitutionDir` and gets the same
// three checks there. If that knob were ignored, such a project would silently
// have NO article-layer coverage — the failure mode the kit exists to prevent.
test("honours a non-default constitutionDir", () => {
  const ctx = ctxFor(
    {
      ...CONFORMANT,
      "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nSee \`.claude/constitution/local-workflow.md\`.`,
      ".claude/constitution/local-workflow.md": "Run `/ghost-command` before pushing.",
    },
    configWith({ constitutionDir: ".claude/constitution" }),
  );
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "skill-missing"));
  assert.equal(out[0].file, ".claude/constitution/local-workflow.md");
  cleanup(ctx);
});

// ── Nested package manuals ───────────────────────────────────────────────────
// An agent loads a nested AGENTS.md when it works in that tree, so it is a
// standing instruction exactly like the root — and gets the same checks.
//
// THE RESOLUTION RULE (config.claudeMdRefs.pathRoots): a path token whose first
// segment is a repo-anchored root (`docs/`, `tests/`, …) resolves repo-relative
// from any manual; anything else inside a NESTED manual resolves against that
// manual's own directory. Repo-level manuals (root + articles) never resolve
// package-relative — they have no package to be relative to.

const NESTED = configWith({ nestedManuals: [{ dir: "apps/api" }] });

const NESTED_BASE = {
  ...CONFORMANT,
  "apps/api/src/handlers.ts": "export const handlers = {};\n",
  "tests/e2e/README.md": "# e2e",
};

test("checks a nested package manual's slash commands", () => {
  const ctx = ctxFor(
    { ...NESTED_BASE, "apps/api/AGENTS.md": "Run `/ghost-command` before editing a handler." },
    NESTED,
  );
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "skill-missing"));
  assert.equal(out[0].file, "apps/api/AGENTS.md");
  cleanup(ctx);
});

test("resolves a nested manual's package-relative path against its own directory", () => {
  const ctx = ctxFor(
    {
      ...NESTED_BASE,
      "apps/api/AGENTS.md": "Routes are `src/handlers.ts` and `src/ghost.ts`.",
    },
    NESTED,
  );
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "path-missing"));
  assert.equal(out[0].file, "apps/api/AGENTS.md");
  assert.match(out[0].message, /apps\/api\/src\/ghost\.ts/);
  cleanup(ctx);
});

test("resolves a repo-anchored first segment repo-relative even inside a nested manual", () => {
  const ctx = ctxFor(
    {
      ...NESTED_BASE,
      // `tests/e2e/README.md` exists at the REPO root, not under apps/api —
      // the anchored-root rule is what makes that reference legal.
      "apps/api/AGENTS.md": "The suite is `tests/e2e/README.md`; routes are `src/handlers.ts`.",
    },
    NESTED,
  );
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

test("flags a repo-anchored path that does not exist, referenced from a nested manual", () => {
  const ctx = ctxFor(
    { ...NESTED_BASE, "apps/api/AGENTS.md": "See `docs/ghost-surface.md` for the layers." },
    NESTED,
  );
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "path-missing"));
  assert.match(out[0].message, /ghost-surface/);
  cleanup(ctx);
});

test("does not treat bare filenames, globs or identifiers in a nested manual as paths", () => {
  const ctx = ctxFor(
    {
      ...NESTED_BASE,
      "apps/api/AGENTS.md": [
        "`ROUTE_TABLE` is asserted by `server.test.ts` and `handlers.test.ts`.",
        "Tests are `*.test.ts`. The API is `/api/v1`.",
      ].join("\n"),
    },
    NESTED,
  );
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

// The portability deny-list's repo-path regex admits `@`, so `@internal/foo.ts`
// counts as a path there. Package-relative resolution must too, or the same
// token inside a nested manual is silently unchecked — a dead reference that
// one guard calls a path and the other cannot see at all.
test("resolves an @-scoped package-relative path in a nested manual", () => {
  const ctx = ctxFor(
    { ...NESTED_BASE, "apps/api/AGENTS.md": "The shim lives at `@internal/ghost/bar.ts`." },
    NESTED,
  );
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "path-missing"));
  assert.match(out[0].message, /apps\/api\/@internal\/ghost\/bar\.ts/);
  cleanup(ctx);
});

test("stays silent when a configured nested manual does not exist", () => {
  const ctx = ctxFor(CONFORMANT, NESTED);
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

test("checks tests/ paths referenced from the root manual", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nThe browser tier lives in \`tests/ghost-tier/\`.`,
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "path-missing"));
  assert.match(out[0].message, /ghost-tier/);
  cleanup(ctx);
});

// ── Regex edges that fail open if you get them wrong ─────────────────────────

test("flags a slash command adjacent to trailing punctuation", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nWhen finished, run \`/ghost-command.\``,
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "skill-missing"));
  assert.match(out[0].message, /ghost-command/);
  cleanup(ctx);
});

test("flags a slash command wrapped in punctuation inside a command-bearing span", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nCompose \`/loop (/ghost-command), /tdd\` for continuous runs.`,
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "skill-missing"));
  assert.match(out[0].message, /ghost-command/);
  cleanup(ctx);
});

// A doubled span is how markdown quotes a span that itself contains backticks —
// e.g. showing `/tdd` verbatim. Paired on single backticks, the inner command is
// invisible: the pairing lands on the padding spaces instead.
test("parses a double-backtick span quoting a slash command", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nWrite it as \`\` \`/ghost-command\` \`\` in the table.`,
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "skill-missing"));
  assert.match(out[0].message, /ghost-command/);
  cleanup(ctx);
});

test("parses a double-backtick span quoting a repo path", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nWrite it as \`\` \`scripts/ghost-guard.sh\` \`\` in the table.`,
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "path-missing"));
  assert.match(out[0].message, /ghost-guard/);
  cleanup(ctx);
});

// ── Portability guard on the shared (framework) article ──────────────────────
// `cp constitution/shared-invariants.md <other repo>` is the test of
// portability: the file "names no product, no package, no command, and no
// vendor". That claim is only real if something checks it. The deny-list and
// its per-entry reasons live in config.mjs.

const PORTABLE = [
  "# Shared invariants",
  "",
  "## 3. Tests are the target function",
  "",
  "An agent optimizes for whatever signal you give it. Write the failing test first,",
  "then make it pass, then refactor. Continuous integration is the gate; mutation",
  "testing measures whether the suite can detect breakage at all.",
  "",
  "Standards findings go to agents, behavior findings go to humans; the split is",
  "never merged. Judgment is human-in-the-loop by label, and autonomy stops before",
  "the merge action. Where a rule needs a mechanism, see `local-workflow.md`.",
].join("\n");

const SHARED = "constitution/shared-invariants.md";

test("passes when the shared article uses only framework-generic language", () => {
  const ctx = ctxFor({ [SHARED]: PORTABLE });
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});

// The kit ships no product vocabulary, so this category only exists once a
// bootstrapped project stamps `local-vocabulary.mjs`. The mechanism is what is
// under test: terms in, escaped deny entry out, project's own reason reported.
test("flags a local-vocabulary term in the shared article", () => {
  const config = configWith({
    portability: {
      files: [SHARED],
      deny: [
        denyEntryFromTerms({
          id: "product-name",
          terms: ["Acme Reports"],
          reason: "Move the sentence to a local-* article.",
        }),
      ],
    },
  });
  const ctx = ctxFor({ [SHARED]: `${PORTABLE}\nUploads land in Acme Reports.` }, config);
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "portability-leak"));
  assert.match(out[0].message, /product-name/);
  assert.match(out[0].hint, /local-\* article/);
  cleanup(ctx);
});

// Regex metacharacters in a product name must not blow up or silently widen the
// rule — the whole point of terms-as-data is that you never write the escape.
test("escapes regex metacharacters in a local-vocabulary term", () => {
  const config = configWith({
    portability: {
      files: [SHARED],
      deny: [
        denyEntryFromTerms({ id: "product-name", terms: ["C++ Runner"], reason: "Rename it." }),
      ],
    },
  });
  const ctx = ctxFor({ [SHARED]: `${PORTABLE}\nBuilt by C++ Runner.` }, config);
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "portability-leak"));
  cleanup(ctx);
});

test("flags a vendor name in the shared article", () => {
  const ctx = ctxFor({ [SHARED]: `${PORTABLE}\nThe reviewer runs on GitHub Actions.` });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "portability-leak"));
  assert.match(out[0].message, /vendor-name/);
  cleanup(ctx);
});

test("flags a deployment hostname in the shared article", () => {
  const ctx = ctxFor({ [SHARED]: `${PORTABLE}\nPublished at view.example.com.` });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "portability-leak"));
  assert.match(out[0].message, /product-hostname/);
  cleanup(ctx);
});

test("flags a tool invocation in the shared article", () => {
  const ctx = ctxFor({ [SHARED]: `${PORTABLE}\nRun pnpm docs:check before pushing.` });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "portability-leak"));
  assert.match(out[0].message, /tool-invocation/);
  cleanup(ctx);
});

// The path and command below both RESOLVE — they are real. Portability is a
// separate axis from existence: a correct reference is still a leak here.
test("flags a repo path in the shared article even when the path exists", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nSee \`constitution/shared-invariants.md\`.`,
    "docs/adr/INDEX.md": "# ADRs",
    [SHARED]: `${PORTABLE}\nThe registry is \`docs/adr/INDEX.md\`.`,
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "portability-leak"));
  assert.match(out[0].message, /repo-path/);
  cleanup(ctx);
});

test("flags a slash command in the shared article even when the skill exists", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nSee \`constitution/shared-invariants.md\`.`,
    [SHARED]: `${PORTABLE}\nStart with \`/tdd\`.`,
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "portability-leak"));
  assert.match(out[0].message, /slash-command/);
  cleanup(ctx);
});

// `sh` in the hostname TLD alternation would make every script filename in the
// article — `check.sh` — read as a hostname. The leak would still be caught,
// but under the wrong id, with a hint about deployment addresses that has
// nothing to do with a script path. A guard whose reason string sends the
// reader somewhere else is barely better than one that misses.
test("classifies a .sh script path as a repo path, not a deployment hostname", () => {
  const ctx = ctxFor({
    ...CONFORMANT,
    "AGENTS.md": `${CONFORMANT["AGENTS.md"]}\nSee \`constitution/shared-invariants.md\`.`,
    [SHARED]: `${PORTABLE}\nRun \`scripts/check.sh\` afterwards.`,
  });
  const out = run(ctx);
  assert.equal(out.length, 1);
  assert.ok(hasRule(out, "portability-leak"));
  assert.match(out[0].message, /repo-path/);
  assert.doesNotMatch(out[0].message, /product-hostname/);
  cleanup(ctx);
});

test("stays silent about portability when the shared article does not exist", () => {
  const ctx = ctxFor(CONFORMANT);
  const out = run(ctx);
  assert.deepEqual(out, []);
  cleanup(ctx);
});
