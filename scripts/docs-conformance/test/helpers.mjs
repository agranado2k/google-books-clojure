import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import defaultConfig from "../config.mjs";
import { makeContext } from "../context.mjs";

/** Write a `{ 'rel/path': 'content' }` map into a fresh temp dir; return root. */
export function makeFixture(files) {
  const root = mkdtempSync(join(tmpdir(), "docs-conf-"));
  for (const [rel, content] of Object.entries(files)) {
    const p = join(root, rel);
    mkdirSync(dirname(p), { recursive: true });
    writeFileSync(p, content);
  }
  return root;
}

/** Build a context over a fixture tree, with an optional config override. */
export function ctxFor(files, config = defaultConfig) {
  return makeContext({ repoRoot: makeFixture(files), config });
}

export function cleanup(ctx) {
  rmSync(ctx.repoRoot, { recursive: true, force: true });
}

/** A rule id appears among the given violations. */
export function hasRule(violations, rule) {
  return violations.some((v) => v.rule === rule);
}

/**
 * The shipped config with one section replaced. Used by tests that exercise a
 * policy knob (a local vocabulary, a different constitution directory) without
 * mutating the module-level config every other test shares.
 */
export function configWith(overrides) {
  return {
    ...defaultConfig,
    claudeMdRefs: { ...defaultConfig.claudeMdRefs, ...overrides },
  };
}
