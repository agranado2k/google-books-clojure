---
name: merge-train
description: Serially land a batch of green PRs through the forge's own merge API — migration-aware ordering, update-branch for stale PRs, wait for the post-merge workflows between merges, then run /worktree-cleanup. Invoke as `/merge-train` (discover all green PRs) or `/merge-train <PR#> [<PR#>…]` (explicit batch). Operator-invoked only; complements /pr-iterate, which never merges.
---

# /merge-train — serialized landing of a green PR batch

## What this does

Parallel worktree agents produce batches of pull requests that each go green in
isolation. Landing them is where the manual toil and the risk concentrate: the
operator clicks merge N times, each merge instantly makes the surviving PRs
stale against the base branch, and — in most projects — a merge triggers
deployment or migration workflows against a shared environment, so **order
matters**.

This skill is the operator-side merge train: it merges a batch **one PR at a
time through the forge's own API**, re-validating between merges.

**It does not weaken shared invariant §7.** That invariant says the merge action
is a human decision with a human's name on it, and `/pr-iterate`'s hard rule
"you never merge" still stands unchanged. `/merge-train` is the human decision,
made explicitly, for a named batch, at a moment the operator chose — it is
delegation of the *mechanics* after the decision, not of the decision. An agent
never starts a train on its own initiative.

Everything stays inside branch protection: the merge and update-branch calls
produce exactly what the UI button produces. Nothing is bypassed, rebased, or
force-pushed.

## Hard rules — do not break

1. **Only the operator starts a train.** Never invoke this from another skill, a
   loop, or on your own initiative.
2. **A PR boards the train only if**: all required checks are green, it is not a
   draft, and no human review requests changes. Advisory bot reviews do not
   block; a human's "changes requested" does.
3. **The merge method is whatever `constitution/local-workflow.md` says it is**,
   and the reason it says so usually involves commit signing — read the article
   before reaching for a different flag. If the operator explicitly asks for a
   squash on a given PR, its title must satisfy Conventional Commits, because
   the title becomes the commit subject.
4. **Never** use an admin override, never touch branch protection, never
   force-push, never merge locally and push.
5. **Stale PRs are updated through the forge's update-branch API only** — a
   local rebase would strip signatures, which is the usual reason a repo forbids
   rebase merges in the first place.
6. **A failed post-merge workflow stops the train dead.** If a migration or
   deploy fails on the base branch after a merge, do not merge anything else;
   escalate immediately.
7. **When in doubt, stop the train and report.** A half-landed batch in a known
   state beats a fully-landed batch in an unknown one.

## Procedure

### 1 — Assemble the batch

If the operator gave PR numbers, use exactly those (still verify each is green —
refuse red ones with a one-line reason). Otherwise discover:

```bash
gh pr list --state open --json number,title,isDraft,reviewDecision,mergeable,mergeStateStatus,headRefName
gh pr checks <N>          # per candidate — every required check green?
```

Drop: drafts, `reviewDecision == "CHANGES_REQUESTED"`, any red or pending
required check, `mergeable == "CONFLICTING"` (send those to `/pr-iterate`
instead).

### 2 — Order the batch (contract-artifact aware)

```bash
gh pr view <N> --json files --jq '.files[].path'
```

- **PRs touching a shared, ordered, single-writer artifact go first, one at a
  time.** Database migrations and their journal/lock files are the canonical
  case, but the same reasoning covers any generated index, lockfile, or numbered
  sequence. `scripts/guards.config.sh`'s `BEHAVIOR_DELTA_SURFACES` is where this
  repo lists its contract artifacts — the persistence and schema surfaces there
  are the ones to look for. Landing them early means the siblings get updated
  against the new state instead of colliding at the end.
- **Two or more PRs adding entries to the same ordered artifact in one batch →
  escalate before merging the second.** They almost certainly claim the same
  number or slot; the later one needs regenerating, not a mechanical retry.
- Everything else: first-in-first-out by ascending PR number.

### 3 — Present the plan

Before the first merge, show the ordered list (PR, title, why it's positioned
there, which carry the ordered artifacts). In auto-discovery mode, wait for the
operator's go-ahead. When the operator passed explicit PR numbers, that message
*is* the go-ahead — proceed.

### 4 — Land each PR, in order

```bash
# a. Stale against the base branch? Update through the forge.
gh pr view "$PR" --json mergeStateStatus --jq .mergeStateStatus   # BEHIND?
gh api -X PUT "repos/{owner}/{repo}/pulls/$PR/update-branch"

# b. Wait for checks to re-run and go green.
gh pr checks "$PR" --watch

# c. Merge, with the method the local workflow article mandates.
gh pr merge "$PR" --merge

# d. Wait for the post-merge workflows before the next merge — the train should
#    observe each result, not outrun it, even when their concurrency groups
#    would queue anyway.
gh run list --branch <base> --limit 5 --json name,status,conclusion,databaseId
gh run watch <databaseId>
```

**If checks go red after update-branch (4b)**: that is a real cross-PR
interaction surfaced early — skip the PR, record it as a `/pr-iterate`
candidate, continue the train.
**If the merge itself is rejected**: re-read state; if it is not a transient
(e.g. checks re-queued), stop and report.
**If a post-merge workflow fails (4d)**: hard rule 6 — stop the train, escalate
with the run log.

### 5 — After the batch

Run **`/worktree-cleanup`** — the merged PRs' worktrees are now prunable, and
the root checkout's base branch should fast-forward to include the batch.

## Output format

```
merge-train — <date>

Plan:      #A (schema) -> #B -> #C
Landed:    #A <merge sha> · #B <merge sha>
Skipped:   #C — checks went red after update-branch (-> /pr-iterate #C)
base:      <sha before> -> <sha after> · post-merge workflows ✅
Cleanup:   <worktrees removed> removed · <kept> kept
```

## Cross-references

- `constitution/local-workflow.md` — the merge method, the rejected one and why,
  and the required checks. That article is the authority for step 4c.
- `docs/adr/INDEX.md` — if the merge method was a recorded decision, cite its
  number when you explain an ordering or a refusal.
- `/pr-iterate` — drives a PR to green; never merges.
- `/worktree-cleanup` — the final step of every train.
