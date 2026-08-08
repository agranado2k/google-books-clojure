import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * Build the read-only context every validator receives. Rooted at `repoRoot`
 * so tests can point it at a fixture tree instead of the real repo.
 *
 * There is deliberately no `paths` map of well-known project locations here:
 * every path a validator cares about is policy and lives in `config.mjs`, so a
 * project can move its docs without patching the harness.
 *
 * @param {{ repoRoot: string, config: object }} opts
 */
export function makeContext({ repoRoot, config }) {
  /** Read a repo-relative file, or null if it does not exist. */
  const read = (rel) => {
    const p = join(repoRoot, rel);
    return existsSync(p) ? readFileSync(p, "utf8") : null;
  };

  /** List file names in a repo-relative dir, optionally filtered by extension. */
  const list = (relDir, ext) => {
    const p = join(repoRoot, relDir);
    if (!existsSync(p)) return [];
    return readdirSync(p)
      .filter((f) => !ext || f.endsWith(ext))
      .sort();
  };

  /**
   * List repo-relative file paths under a dir, recursing into subdirectories,
   * optionally filtered by extension. Returns `[]` if the dir doesn't exist —
   * validators that use this can stay silent on fixtures that don't model it.
   */
  const listRecursive = (relDir, ext) => {
    const root = join(repoRoot, relDir);
    if (!existsSync(root)) return [];
    const out = [];
    const walk = (dir, relBase) => {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const abs = join(dir, entry.name);
        const rel = relBase ? join(relBase, entry.name) : entry.name;
        if (entry.isDirectory()) {
          walk(abs, rel);
        } else if (!ext || entry.name.endsWith(ext)) {
          out.push(join(relDir, rel));
        }
      }
    };
    walk(root, "");
    return out.sort();
  };

  const exists = (rel) => existsSync(join(repoRoot, rel));

  return { repoRoot, config, read, list, listRecursive, exists };
}
