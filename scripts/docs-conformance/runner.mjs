// Aggregates every validator. A validator that throws is itself reported as a
// violation (validator-crash) rather than taking the whole run down.
//
// One validator ships today — `claude-md-refs`, the one that guards the layer
// every agent session loads. K4 (#6) adds the docs-skeleton validators (ADR
// index sync, MADR shape, glossary aliases) to this same list; the seam is here
// so they arrive as data, not as a rewrite.

import * as claudeMdRefs from "./validators/claude-md-refs.mjs";

export const VALIDATORS = [claudeMdRefs];

/** Run all validators against the context; returns a flat list of violations. */
export function runAll(ctx) {
  const violations = [];
  for (const validator of VALIDATORS) {
    try {
      violations.push(...validator.run(ctx));
    } catch (err) {
      violations.push({
        validator: validator.id,
        file: "-",
        rule: "validator-crash",
        message: `Validator threw: ${err?.message ?? String(err)}`,
        hint: "This is a bug in the validator, not the docs.",
      });
    }
  }
  return violations;
}
