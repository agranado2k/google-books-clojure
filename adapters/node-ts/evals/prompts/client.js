// The prompt under eval: a host agent that has just connected to your server
// and is deciding what to do with a user's request.
//
// The system message is your shipped instructions string VERBATIM (generated
// into fixtures/instructions.txt from your own source), and the tool
// definitions the provider carries are the shipped ones. Nothing is paraphrased
// and nothing is added — the eval measures the REAL surface, so a wording
// change in your instructions or tool descriptions changes what is measured.
//
// EXAMPLE. It reads a fixture that this adapter deliberately does not ship; see
// README.md § Before this runs.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const INSTRUCTIONS = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), "..", "fixtures", "instructions.txt"),
  "utf8",
).trim();

export default function clientPrompt({ vars }) {
  return [
    { role: "system", content: INSTRUCTIONS },
    { role: "user", content: String(vars.scenario) },
  ];
}
