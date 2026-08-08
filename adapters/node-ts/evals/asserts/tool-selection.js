// The CODE grader for tool selection. Tool selection is code-graded, never
// LLM-judged: "which tool fired, with what argument shape" is a decidable
// question, and deciding it with a second model buys noise and a bill.
//
// It reads the reference solution off the case's `metadata` and scores the
// OUTCOME, not the path:
//
//   coverage  (0.6) — did the tool(s) that should fire, fire? `expected_any_of`
//                     turns the set into "at least one of these is right", which
//                     is how a case with two defensible answers is expressed.
//                     For a no-tool case (`expected_tools: []`) coverage is 1
//                     exactly when nothing outside `acceptable_tools` fired.
//   restraint (0.2) — nothing forbidden fired, and nothing fired that is
//                     neither expected nor explicitly acceptable. Reading a
//                     list before acting on it is not a mistake, so
//                     `acceptable_tools` costs nothing; a destructive call when
//                     asked to confirm first does.
//   arguments (0.2) — the reference ARG SHAPE, not exact values: which keys
//                     must be present, which absent, which equal to an enum.
//
// Partial credit is the point — 0.8 for "right tool, one argument missing" is a
// different failure from 0, and the reason string says which. `min_score`
// (default 1) is the per-case bar, and it is what makes partial credit change
// OUTCOMES rather than merely decorate reasons.
//
// A FORBIDDEN CALL IS A HARD FAIL, outside the weighted score entirely.
// Restraint is worth only 0.2, so a case that lowered its bar to 0.8 for a
// known partial answer would otherwise also buy a pass for a run that called
// the expected tool AND a forbidden one (0.6 + 0 + 0.2 = 0.8).
//
// EXAMPLE, and a deliberately reduced one. The version this was drawn from also
// carries a brace-balancing scanner for providers that interleave prose with
// pretty-printed tool blocks. Check what YOUR provider's output actually looks
// like before trusting the extractor below — and when in doubt, fail CLOSED
// (report a tool call), because failing open scores every negative case as a
// pass, which is the one direction this grader must never fail in.

/** Pull every tool call out of whatever shape the provider returned. */
function extractToolCalls(output) {
  const calls = [];

  const visit = (value) => {
    if (!value || typeof value !== "object") return;
    if (Array.isArray(value)) {
      for (const item of value) visit(item);
      return;
    }
    if (value.type === "tool_use" && typeof value.name === "string") {
      calls.push({ name: value.name, input: value.input ?? {} });
      return;
    }
    if (Array.isArray(value.content)) visit(value.content);
    if (Array.isArray(value.tool_calls)) visit(value.tool_calls);
  };

  if (typeof output !== "string") {
    visit(output);
    return calls;
  }

  // Whole-document first: an output that is itself one JSON value parses here,
  // and the line scan below would never see it — every brace sits on its own
  // line.
  const whole = output.trim();
  if (whole.startsWith("{") || whole.startsWith("[")) {
    try {
      visit(JSON.parse(whole));
      if (calls.length > 0) return calls;
    } catch {
      // Not a single JSON document — fall through.
    }
  }

  // Providers commonly render a tool-using turn as the text blocks plus one
  // JSON-stringified block per non-text block, one block per line.
  for (const line of output.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) continue;
    try {
      visit(JSON.parse(trimmed));
    } catch {
      // Prose that merely looks like JSON — not a tool call.
    }
  }
  return calls;
}

function asList(value) {
  return Array.isArray(value) ? value.map(String) : [];
}

/** Score the reference arg shape for one tool. Returns [satisfied, total, notes]. */
function scoreArgs(spec, calls) {
  const required = asList(spec.required);
  const forbidden = asList(spec.forbidden);
  const equals = spec.equals && typeof spec.equals === "object" ? spec.equals : {};
  const checks = required.length + forbidden.length + Object.keys(equals).length;
  if (checks === 0) return [0, 0, []];

  // Grade against the BEST-matching call, so a retry inside one turn is not
  // punished for the first attempt.
  let best = { satisfied: -1, notes: [] };
  for (const call of calls) {
    const input = call.input && typeof call.input === "object" ? call.input : {};
    const notes = [];
    let satisfied = 0;
    for (const key of required) {
      if (input[key] !== undefined && input[key] !== null && input[key] !== "") satisfied += 1;
      else notes.push(`${call.name} is missing required arg \`${key}\``);
    }
    for (const key of forbidden) {
      if (input[key] === undefined) satisfied += 1;
      else notes.push(`${call.name} passed \`${key}\`, which this scenario must not set`);
    }
    for (const [key, want] of Object.entries(equals)) {
      if (input[key] === want) satisfied += 1;
      else
        notes.push(
          `${call.name}.${key} is ${JSON.stringify(input[key])}, expected ${JSON.stringify(want)}`,
        );
    }
    if (satisfied > best.satisfied) best = { satisfied, notes };
  }
  return [Math.max(best.satisfied, 0), checks, best.notes];
}

/**
 * The per-case pass bar, read off `metadata.min_score` and VALIDATED.
 *
 * The default is 1 — every component perfect — and stays there for any value
 * this grader cannot honour: a non-number (`min_score: "0.8"` is a plausible
 * YAML quoting slip), a bar of 0 (which passes every run short of a forbidden
 * call, silently deleting the case), or a bar above 1 (which no run can clear).
 * Falling back to the strict default is the fail-CLOSED direction; the warning
 * it pushes is what stops a typo from living forever behind a pass.
 */
function resolveMinScore(raw, warnings) {
  if (raw === undefined || raw === null) return 1;
  if (typeof raw !== "number" || !Number.isFinite(raw) || raw <= 0 || raw > 1) {
    warnings.push(
      `ignored unusable metadata.min_score ${JSON.stringify(raw)} (want a number in (0, 1]); graded at the strict default 1`,
    );
    return 1;
  }
  return raw;
}

export default function gradeToolSelection(output, context) {
  const metadata = context?.test?.metadata ?? {};
  const expected = asList(metadata.expected_tools);
  const forbidden = asList(metadata.forbidden_tools);
  const acceptable = asList(metadata.acceptable_tools);
  const expectedArgs = metadata.expected_args ?? {};
  const anyOf = metadata.expected_any_of === true;
  const warnings = [];
  const minScore = resolveMinScore(metadata.min_score, warnings);

  const calls = extractToolCalls(output);
  const called = [...new Set(calls.map((c) => c.name))];
  const allowed = new Set([...expected, ...acceptable]);
  const hit = expected.filter((tool) => called.includes(tool));
  const unexpected = called.filter((tool) => !allowed.has(tool));
  const forbiddenHit = called.filter((tool) => forbidden.includes(tool));

  const reasons = [];

  // --- coverage -------------------------------------------------------------
  let coverage;
  if (expected.length === 0) {
    coverage = unexpected.length === 0 ? 1 : 0;
    if (coverage === 0) reasons.push(`no tool should have fired, but ${unexpected.join(", ")} did`);
  } else if (anyOf) {
    coverage = hit.length > 0 ? 1 : 0;
    if (coverage === 0)
      reasons.push(
        `expected any of [${expected.join(", ")}]; called [${called.join(", ") || "none"}]`,
      );
  } else {
    coverage = hit.length / expected.length;
    const missed = expected.filter((tool) => !called.includes(tool));
    if (missed.length > 0) reasons.push(`did not call ${missed.join(", ")}`);
  }

  // --- restraint ------------------------------------------------------------
  let restraint = 1;
  if (forbiddenHit.length > 0) {
    restraint = 0;
    reasons.push(`called forbidden tool(s) ${forbiddenHit.join(", ")}`);
  } else if (expected.length > 0 && unexpected.length > 0) {
    restraint = 0.5;
    reasons.push(`called unrelated tool(s) ${unexpected.join(", ")}`);
  }

  // --- argument shape -------------------------------------------------------
  let argsSatisfied = 0;
  let argsChecked = 0;
  for (const [tool, spec] of Object.entries(expectedArgs)) {
    const toolCalls = calls.filter((c) => c.name === tool);
    if (toolCalls.length === 0) continue; // already penalised by coverage
    const [satisfied, checks, notes] = scoreArgs(spec ?? {}, toolCalls);
    argsSatisfied += satisfied;
    argsChecked += checks;
    reasons.push(...notes);
  }
  const args = argsChecked === 0 ? 1 : argsSatisfied / argsChecked;

  const score = 0.6 * coverage + 0.2 * restraint + 0.2 * args;
  const pass = forbiddenHit.length === 0 && score + 1e-9 >= minScore;

  const bar = minScore.toFixed(2);
  let verdict;
  if (pass) {
    verdict = `tool selection ok (score ${score.toFixed(2)} >= ${bar}); called [${called.join(", ") || "none"}]`;
  } else if (forbiddenHit.length > 0) {
    verdict = `forbidden tool(s) ${forbiddenHit.join(", ")} fired — a hard fail regardless of score (${score.toFixed(2)}) or bar (${bar}): ${reasons.join("; ")}`;
  } else {
    verdict = `score ${score.toFixed(2)} < ${bar}: ${reasons.join("; ") || "no reason recorded"}`;
  }

  return {
    pass,
    score,
    // Warnings ride on BOTH verdicts: a misconfigured bar on a case that
    // happens to pass is exactly the one a failure report would never show.
    reason: warnings.length > 0 ? `${verdict} [${warnings.join("; ")}]` : verdict,
  };
}
