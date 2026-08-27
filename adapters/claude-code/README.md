# `claude-code/` — wiring capability tiers into one agent harness

The kit resolves a **capability tier** — and optionally a **task domain** — to a
model identifier and stops there:

```sh
sh scripts/agents.lib.sh implementer           # prints the mapped id, or nothing
sh scripts/agents.lib.sh implementer content   # the same, for prose work
```

What it deliberately does not know is **where that string goes** when an agent
spawns a subagent. That is harness-specific, it is the one part of this feature
that cannot be written portably, and it is why this note exists.

> Read this for the *shape*. If you drive the kit with a different harness, the
> question to answer is the same one — "which parameter of my spawn call takes a
> model identifier, and what does omitting it mean?" — and the answer belongs in
> a sibling directory here, in your own repo.

## The wiring, in one line

Claude Code's sub-agent spawn (the `Task` / `Agent` tool) takes an optional
**`model`** parameter. Omit it and the subagent inherits the parent session's
model — which is exactly why "unmapped resolves to nothing" is a working state
rather than an error: an empty resolution and no parameter are the same call.

So the pattern a skill follows is:

```sh
model=$(sh scripts/agents.lib.sh mechanical)
# then: spawn with model="$model" if it is non-empty, and with no model
#       parameter at all if it is empty.
```

And with a domain, when the ticket carries one — the branch is identical,
because the second axis changes which variable is read and nothing about the
call:

```sh
# ticket says:  Tier: implementer  /  Domain: content
model=$(sh scripts/agents.lib.sh implementer content)
# -> AGENT_TIER_IMPLEMENTER_CONTENT if the project mapped it,
#    AGENT_TIER_IMPLEMENTER if not, and the empty-means-omit branch either way.
```

Two things worth being explicit about:

- **Do not pass an empty string** as the model parameter. Omitting a parameter
  and passing `""` are not the same request, and a harness is within its rights
  to reject the second. Branch on emptiness.
- **The resolver's warning goes to stderr**, never stdout. `$(...)` therefore
  captures the model id and nothing else, and the operator still sees the
  warning. If you ever wrap this in something that merges the streams, you will
  start spawning agents on a model called `! agents: capability tier ...`.

## Filling in `scripts/agents.config.sh`

The values are whatever identifiers your account can actually invoke — not
marketing names, and not the values in anyone's blog post. Get them from the
harness or the provider's own model list at the moment you configure it, and
re-check when a tier's cost/benefit shape moves.

The four variables and the decision each one encodes:

| Variable | Give it | Because |
| --- | --- | --- |
| `AGENT_TIER_PLANNER` | your strongest reasoning model | its output constrains every downstream ticket; a bad decomposition is paid for many times |
| `AGENT_TIER_IMPLEMENTER` | a strong general model | the default for real work — this is where most sessions land |
| `AGENT_TIER_MECHANICAL` | the cheapest model that can hold the task | the suite is the oracle here, so capability past "can follow the pattern" buys nothing |
| `AGENT_TIER_REVIEWER` | a strong model, in fresh context | a review is a verdict, and a cheap verdict is a rubber stamp |

The single biggest saving is `mechanical`, because expand–migrate–contract waves
are mostly migrate tickets. The single most expensive mistake is a cheap
`reviewer`, because it fails silently.

### The optional domain overrides

Each tier also takes `AGENT_TIER_<TIER>_<DOMAIN>`, consulted first and falling
back to the plain variable. Set one only where the medium genuinely changes your
answer — the usual pair being "the best coding model" and "the best writing
model" at the `implementer` tier:

```sh
AGENT_TIER_IMPLEMENTER='<a strong general model>'
AGENT_TIER_IMPLEMENTER_CONTENT='<the one you would hand a launch post to>'
```

Everything you leave unset keeps resolving through the tier, so this stays a
two-line change rather than a matrix to maintain. Note the fold: a domain token
may contain hyphens and a variable name may not, so `html-report` reads
`AGENT_TIER_IMPLEMENTER_HTML_REPORT`.

## What this adapter deliberately does NOT contain

- **No model identifiers.** Not here either. This directory is reference prose
  about a mechanism; the moment it carried a real id it would rot on the same
  schedule the kit is avoiding, and it would rot somewhere a reader is far more
  likely to copy from than a config comment.
- **No executable file.** Nothing under `adapters/` is on an execution path (see
  [`../README.md`](../README.md)); this note is read by a human wiring the kit
  up, and by the agent that reads the repo, not by a script.
- **No hook or workflow.** Tier selection is a spawn-time decision inside a
  session. There is nothing for CI to enforce, and a check that asserted "this
  ticket ran on the right model" would be asserting something the repo has no
  record of.

## Verifying it once

After filling the config in, from the repo root:

```sh
for t in planner implementer mechanical reviewer; do
  printf '%s -> %s\n' "$t" "$(sh scripts/agents.lib.sh "$t")"
done
```

Four non-empty values and no `UNMAPPED` warning on stderr means the mapping is
live. Any tier you deliberately left unmapped will print an empty value and warn
once — which is a decision, as long as it is one you made.

If you mapped any domains, check that the ones you meant to differ actually do:

```sh
for d in code content; do
  printf 'implementer/%s -> %s\n' "$d" "$(sh scripts/agents.lib.sh implementer "$d")"
done
```

Two identical values here mean the override is not being read — most likely the
variable name does not match the token (remember the hyphen fold), and the
resolver fell back to the tier exactly as designed, without a word. That silence
is the right default for the domains you never mapped, which is why this check
is worth running once on the ones you did.
