// docs-conformance policy — THIS FILE IS YOURS.
//
// The validators own no rules. Every rule they enforce is data in this file, so
// the policy is reviewable in a pull request instead of buried in a regex
// somewhere in `validators/`. The engine (`index.mjs`, `runner.mjs`,
// `context.mjs`, `validators/`) is shared layer and is copied verbatim from the
// kit; this file is a *starter* you are expected to edit as your repo grows.
//
// It ships deliberately generic: no product name, no vendor, no path that only
// makes sense in the repo the framework was extracted from.

import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

/**
 * Turn a plain list of literal terms into a portability deny-list entry.
 *
 * Local vocabulary is *data*, not regex authorship: you know your product's
 * name, not how to escape it. Terms are escaped, joined into one alternation,
 * and matched case-insensitively on word boundaries.
 */
export function denyEntryFromTerms({ id, terms, reason }) {
  const alt = terms.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|");
  return { id, scope: "prose", re: new RegExp(`\\b(?:${alt})\\b`, "i"), reason };
}

/**
 * Your project's own vocabulary — the words that must NOT appear in the shared
 * layer, because they are exactly what makes a file un-copyable.
 *
 * `local-vocabulary.mjs` is stamped by `bootstrap.sh` from its `.template`
 * sibling, seeded with your project name (the template carries the double-brace
 * mark; this file must not, because the gate rejects unstamped marks outside
 * `*.template` files). Add your service names, internal codenames, and org
 * names to it as they appear.
 *
 * The kit itself has no product, so in the kit repo the template is never
 * stamped and this list is empty — which is the correct reading: the kit's own
 * shared layer must be free of *any* product vocabulary.
 */
const localVocabularyFile = join(here, "local-vocabulary.mjs");
const localVocabulary = existsSync(localVocabularyFile)
  ? ((await import(pathToFileURL(localVocabularyFile).href)).default ?? [])
  : [];

/**
 * The agent manual is a layered set of files, all of them standing instructions
 * the agent obeys: the root `AGENTS.md`, the on-demand articles under
 * `constitutionDir`, and any nested package manuals. Every layer gets the same
 * two existence checks — slash commands must resolve to a skill directory,
 * referenced repo paths must exist — plus, for the shared article only, the
 * portability guard further down.
 */
export const claudeMdRefs = {
  /**
   * The always-loaded root manual.
   *
   * `AGENTS.md` rather than a tool-specific filename: the manual is one file,
   * and the tools that look for a different name are served by the shims below.
   * This value is also the nested-manual filename — a package manual is
   * `<dir>/AGENTS.md` — so there is exactly one knob for "what the manual is
   * called".
   */
  rootManual: "AGENTS.md",

  /**
   * Tool-specific entry points that must contain NOTHING but an import of the
   * root manual (plus, optionally, one comment line saying so).
   *
   * The rule exists because the alternative is silent divergence: the moment a
   * tool-specific file can hold a rule of its own, somebody adds one there, and
   * the repo now has two manuals whose difference nobody can see. Keeping the
   * shims provably empty is what makes "AGENTS.md is the manual" a fact rather
   * than a convention.
   *
   * `shim-invalid` is only evaluated when the root manual exists — an
   * unbootstrapped tree (the kit itself) and a fixture that does not model the
   * manual layer both stay silent, exactly as the other manual checks do.
   * Delete an entry here if you do not want that tool's entry point at all; the
   * gate follows this list, not a hard-coded pair.
   */
  shims: ["CLAUDE.md", "GEMINI.md"],

  /**
   * Where the on-demand articles live. The kit puts them in `constitution/`, at
   * the repo root, so they are visible to a human browsing the repo rather than
   * hidden in a tool-specific dot-directory. Projects that prefer the
   * tool-native location can set `.claude/constitution` here instead — the
   * validator does not care, it just reads this.
   */
  constitutionDir: "constitution",

  /** Where a `/command` resolves: `<skillsDir>/<name>/SKILL.md`. */
  skillsDir: ".claude/skills",

  /**
   * Commands that are real but are not repo skills. Each carries its reason
   * here so the exemption is itself reviewable.
   */
  ignoreCommands: [
    "/loop", // agent-harness global skill (interval runner), not a repo skill
    "/security-review", // agent-harness built-in
    "/review", // agent-harness built-in
    "/init", // agent-harness built-in
  ],

  /**
   * Repo-anchored path roots — the trees the manual layer points into, and the
   * reviewed surface of the repo. A backticked token whose FIRST segment is one
   * of these is resolved repo-relative, from whichever manual names it.
   *
   * This list is also half of the nested-manual resolution rule (see
   * `nestedManuals`): anchored first segment → repo-relative; anything else in
   * a nested manual → relative to that manual's own directory. Roots
   * deliberately absent (dependency trees, worktrees, generated output) are not
   * part of the reviewed surface, so references into them stay unchecked.
   *
   * Add your own source roots here as you create them (`src`, `apps`,
   * `packages`, `infra`, …). A root that does not exist yet is harmless: it only
   * widens what counts as a checkable reference.
   */
  pathRoots: [
    "constitution",
    "scripts",
    "docs",
    "tests",
    "adapters",
    ".githooks",
    ".github",
    ".claude/hooks",
    ".claude/skills",
    ".claude/constitution",
  ],

  /**
   * Nested package manuals. An agent loads one of these only when it works in
   * that tree, which makes them exactly as binding as the root while loaded —
   * and exactly as poisonous when stale.
   *
   * THE RESOLUTION RULE, decided here because it is policy: inside a nested
   * manual, a path token whose first segment is in `pathRoots` above means the
   * repo-root path; every other path token is resolved against `dir`. To keep
   * prose out of the check, a package-relative token must contain a `/` AND
   * either end in `/` (a directory) or have a dotted final segment (a
   * filename) — so `src/tools.ts` and `packaging/` are checked while
   * `server.test.ts`, `*.test.ts` and `SOME_CONSTANT` are not.
   *
   * Repo-level manuals (root + articles) never resolve package-relative: they
   * have no package to be relative to.
   *
   * Nested manuals are NOT subject to the `article-unreferenced` reachability
   * rule — unlike an article, nothing has to point at them to make them load.
   *
   * Starter: empty. Add `{ dir: "apps/api" }` when you write one, with a
   * comment saying why that tree needs its own manual.
   */
  nestedManuals: [],

  /**
   * Portability guard for the shared (framework) article.
   *
   * `cp constitution/shared-invariants.md <other repo>` is the *test* of that
   * file's portability, and its own header states the constraint: it "names no
   * product, no package, no command, and no vendor". That claim is only worth
   * anything if something checks it — otherwise the first local detail to leak
   * in silently converts a framework artifact back into a project document, and
   * nobody notices until the copy fails somewhere else.
   *
   * The deny-list is grounded in that sentence: one entry per category of local
   * vocabulary, each with the reason it cannot appear. `scope` says where an
   * entry is looked for:
   *   - `prose`  — the whole file, fences included. Names leak in sentences.
   *   - `spans`  — markdown code spans only. Paths and commands are written in
   *                code spans by convention, and scanning prose for them would
   *                misread ordinary "either/or" phrasing as a path.
   */
  portability: {
    // TWO files, and `.claude/skills/**` is deliberately not in the list.
    //
    // Portability is not a virtue every file should have — it is a contract
    // these files sign, because they are the articles that get copied verbatim
    // into another repo (see VERSION). The skills are copied too, but they are
    // YOURS on arrival and are expected to name your paths, your commands, and
    // your tooling the moment you adapt them. Adding them here would either
    // produce a permanent wall of violations or force the deny-list to be
    // watered down until it stopped catching the leak it exists for — and a
    // weakened guard is worse than a narrow one, because it still looks like
    // coverage. Keep the scope tight instead.
    //
    // What DOES hold the skills honest is a different check: every `/command`
    // in the manual layer must resolve to a skill directory (`skill-missing`,
    // above), so the map and the skills cannot drift apart.
    files: [
      "constitution/shared-invariants.md",
      "constitution/shared-code-craft.md",
    ],
    deny: [
      // Your vocabulary first: the leaks only you can enumerate.
      ...localVocabulary.map(denyEntryFromTerms),
      {
        id: "product-hostname",
        scope: "prose",
        // No `sh`: every shell script in the tree ends in it, so a TLD
        // alternation containing `sh` reads `check.sh` as a hostname and
        // reports a real leak under the wrong id, with a hint about deployment
        // addresses. Script paths are covered by `repo-path`/`tool-invocation`.
        re: /\b(?:[a-z0-9-]+\.)+(?:com|dev|io|app|net|org)\b/i,
        reason:
          "A hostname is deployment-specific, and the framework has no deployment. Describe the role ('the published origin'), not the address.",
      },
      {
        id: "vendor-name",
        scope: "prose",
        // Vendors and tools that would bind a rule to one stack. Extend it with
        // whatever your local articles legitimately name — the point is that
        // the SHARED file may not.
        // Deliberately excluded, though they are real vendors: "Linear" and
        // "Go" are ordinary English first. A deny-list entry that fires on
        // prose trains people to ignore the guard.
        re: /\b(?:Anthropic|Claude|Gemini|OpenAI|Copilot|GitHub|GitLab|Bitbucket|Jira|Vercel|Netlify|Cloudflare|Terraform|Docker|Kubernetes|Postgres(?:QL)?|MySQL|Redis|Vitest|Mocha|Pytest|JUnit|Cypress|Playwright|Selenium|Cucumber|Stryker|ESLint|Biome|Prettier|Husky|Turborepo|Gradle|Maven)\b/i,
        reason:
          "Naming a vendor or tool binds the rule to this stack. State what the mechanism must achieve; the tool that achieves it belongs in a local-* article.",
      },
      {
        id: "tool-invocation",
        scope: "prose",
        // No `go` and no `make`: "findings go to humans" and "make it pass" are
        // English, not invocations, and a guard that cries wolf on prose gets
        // switched off. The cost is an accepted gap, stated plainly: `go test`
        // and `make check` are NOT caught. If your shared layer is at risk of
        // naming them, add them here and accept the prose noise.
        re: /\b(?:pnpm|npm|npx|yarn|bun|pip|poetry|cargo|mvn|gradle|git|gh|terraform|docker|kubectl)\s+[a-z][\w:-]*/i,
        reason:
          "A concrete command cannot survive a copy into a repo with a different toolchain. Name the obligation ('the docs gate must pass before push'), not the invocation.",
      },
      {
        id: "slash-command",
        scope: "spans",
        re: /^[([{"']?\/[a-z][a-z0-9-]*/,
        reason:
          "A slash command names a skill that exists in THIS repo's skills directory. The portable rule is the practice, not the command that runs it.",
      },
      {
        id: "repo-path",
        scope: "spans",
        re: /^[\w.@-]+(?:\/[\w.@*-]+)+\/?$/,
        reason:
          "A repo path bakes in this project's layout. Sibling article filenames (no directory separator) are part of the framework and stay legal; anything with a `/` does not.",
      },
    ],
  },
};

export default { claudeMdRefs };
