# ADR-0008: Trigger the Railway deploy from CI, gated on green checks

- **Status**: Accepted
- **Date**: 2026-08-27
- **Deciders**: Arthur Granado (owner)
- **Supersedes / amends**: Reverses, in part, the diary decision of 2026-08-09
  ("deploys via Railway's native GitHub integration; GitHub Actions is
  CI-only") — the half that says the forge does not trigger deploys. Does not
  touch ADR-0002, which decides what is *built* and shipped (an uberjar in a
  multi-stage Dockerfile on Railway) and explicitly disclaims deciding CI.
- **Superseded by**: —

<!--
Numbering note: 0006 and 0007 are already claimed by in-flight work on
`feat/bookmark-volume` (the Bookmark data model, and the bearer-only mutation
rule). Numbers are never reused, so this record takes the next free one after
them rather than the next one visible on `main`.
-->

## Context and problem statement

Deploys were supposed to be somebody else's problem. The 2026-08-09 decision was
that Railway's own GitHub integration watches `main` and redeploys on every
push, leaving GitHub Actions purely as a gate — tests, docs gate, TDD pairing
guard — with no credential and no external side effect. That is a genuinely
good shape: exactly one system holds the deploy credential, and it is the system
that already holds the runtime secrets.

It does not work here. The Railway service's source is correctly connected to
`agranado2k/google-books-clojure` on `main`, and Railway can read the repo, but
merges to `main` produce no deployment at all — the push webhook never fires.
The cause appears to be that the Railway GitHub App is not installed on the
account, and installing it is a browser-only step in GitHub's own UI that no
agent, token or CLI in this project can perform. So the recorded decision
describes a mechanism that has never once run, and `main` has been advancing
without reaching production.

Two things have to be settled: what replaces the webhook, and — separately —
whether the replacement should deploy on *push* (what the webhook was trying to
do) or on *green* (what CI can additionally offer).

## Decision drivers

1. **A deploy must actually happen.** The current state is a silent no-op, which
   is the worst of the options because it looks configured.
2. **A red `main` must not reach production.** Branch protection is unavailable
   on this repo (private, no GitHub Pro), so nothing stops a red merge; the
   deploy gate is the only place left where "green" can be made to mean
   something.
3. **Blast radius of the credential.** Whatever holds a deploy token can deploy.
   It must not be reachable from a pull request, least of all a fork's.
4. **No permanently red pipeline for anyone without the token.** A fork, or this
   repo before the secret exists, must show green.
5. **No new trust.** Prefer not to hand a production credential to an additional
   third-party action.

## Considered options

1. **CI triggers `railway up` after the gate jobs are green** *(chosen)* — a
   `deploy` job in `.github/workflows/ci.yml`, `needs: [test, docs-gate]`,
   push-to-`main` only, authenticating with a `RAILWAY_TOKEN` repository secret.
2. **Install the Railway GitHub App and keep the webhook** — not rejected on
   merit; it is simply not available to this session, and it would still deploy
   on *push* rather than on *green* (driver 2). If it is installed later it
   becomes a duplicate trigger and one of the two must be turned off — see the
   non-goal below.
3. **Deploy manually from a laptop** — rejected: it puts the production
   credential on a developer machine and makes "what is deployed?" a question
   only one person can answer.
4. **A scheduled job that deploys the tip of `main` periodically** — rejected:
   it decouples the deploy from the change that caused it, so a bad deploy has
   no obvious author and no obvious diff.

## Decision outcome

Chosen: **CI triggers the deploy, gated on green.**

1. `.github/workflows/ci.yml` carries a `deploy` job that runs **only** on
   `push` to `main` — `if: github.event_name == 'push' && github.ref ==
   'refs/heads/main'`. Both clauses are kept even though either alone suffices
   (a `pull_request` run's `github.ref` is `refs/pull/N/merge`): a deploy job is
   the wrong place to rely on one condition.
2. It `needs: [test, docs-gate]`. A failed or cancelled dependency skips it, so
   **a red `main` never reaches production**. This is the part of the decision
   that stands on its own merit rather than on the webhook's failure: it would
   be the better mechanism even if the webhook worked, because Railway's
   integration deploys on push and cannot see a test result.
3. `tdd-pairing` is deliberately **not** a `needs` edge. It is
   `pull_request`-only by construction (it compares a PR's head against its
   merge base, which a push to `main` does not have), and a dependency on a job
   that never runs on `push` would skip the deploy forever.
4. The deploy names its target explicitly:
   `railway up --service google-books-clojure --detach`. **`--service` is
   load-bearing.** An earlier hand-run deploy that omitted it resolved to the
   project's Postgres service and took the database down. Service resolution is
   never left to a default here, and any future deploy invocation added to this
   repo names its service too.
5. Authentication is `RAILWAY_TOKEN`, read from GitHub Actions repository
   secrets. **No token value is committed.** It is mapped to a job-level `env`
   so that a *step-level* `if: env.RAILWAY_TOKEN != ''` can guard on it — the
   `secrets` context is not available in a job-level `if:`, which is why the
   guard lives at step level rather than on the job.
6. **Missing secret is a skip, not a failure.** With no token, the job runs one
   step that emits a `::notice` explaining that nothing was deployed and how to
   enable it, and every other step is skipped. The run stays green.
7. The Railway CLI is installed with `npm install -g @railway/cli@<version>`, at
   a pinned version, rather than through a third-party action: the deploy step
   holds a production credential, and this avoids adding another action to the
   set of things trusted with it. Since npm gives no SHA to pin, the version is
   pinned instead — the same discipline the Tailwind download in the same
   workflow already uses.
8. **Explicit non-goal**: this does not decide *what* is built or how it is
   packaged — that is ADR-0002 — and it does not introduce environments,
   approvals, staged rollouts, or a rollback mechanism. Rollback remains a
   Railway-console action.
9. **Explicit non-goal**: this does not decide that the Railway GitHub App must
   stay uninstalled. If it is ever installed, the two triggers overlap and
   double-deploy every merge; whoever installs it turns one of them off in the
   same change, and records which.

## Consequences

- **Good**: deploys happen again, and they happen only from a commit that
  passed the suite, the uberjar build and the docs gate. The deploy is tied to a
  specific commit with a specific author and a visible run log.
- **Good**: the deploy credential lives in exactly one place (a repository
  secret) and is reachable from exactly one job that cannot run on a pull
  request.
- **Bad / trade-off**: GitHub Actions is no longer credential-free. The
  workflow's header comment used to be able to say "the jobs hold a read-only
  token and make no external call"; that is now true of every job except
  `deploy`. Anyone reviewing a change to this file is reviewing a change to
  something that can deploy.
- **Bad / trade-off**: `railway up` uploads the runner's checkout as the build
  context rather than having Railway pull the repo. What ships is what CI had —
  which is the intent — but it means a deploy can no longer be reproduced by
  pointing Railway at a commit; it is reproduced by re-running the workflow.
- **Neutral**: `--detach` means the job returns once the build is queued. CI
  reports "deploy triggered", not "deploy succeeded"; the deployment's own
  outcome is watched in the Railway console.
- **Honest limitation — the failure mode if the secret is missing**: the job
  turns into a one-step no-op that prints a notice and passes. **A missing or
  revoked token therefore looks exactly like a healthy pipeline**, which is the
  same silent-no-op failure this ADR exists to fix, merely moved. It is accepted
  because driver 4 (forks and token-less clones must stay green) and a hard
  failure cannot both hold. The mitigation is not in CI: after a merge, confirm
  in the Railway console that a deployment actually started.
- **Honest limitation**: nothing here verifies that the deployed revision is
  healthy. `GET /health` exists and is what a human or a later ticket should
  check; the workflow does not.
- **Honest limitation**: branch protection is still unavailable, so a red run
  cannot block a *merge*. It can only block the *deploy*, which is what clause 2
  buys.

## More information

- Implemented on branch `ci/deploy-on-green`, in `.github/workflows/ci.yml`.
- Operator step, one time, in the browser: **Railway project → Settings →
  Tokens → create a project token** for the production environment, then
  **GitHub repo → Settings → Secrets and variables → Actions → New repository
  secret**, named `RAILWAY_TOKEN`. A *project* token is recommended over a
  personal/account token: it is scoped to this one project and environment, so a
  leak cannot reach the account's other projects, and it is not tied to a person
  who may leave.
- Related: ADR-0002 (packaging and Railway as the runtime), ADR-0003 (the
  database this deploy must not disturb — see clause 4), and the diary entry of
  2026-08-09 that this record reverses in part.
