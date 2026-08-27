# ADR-0008: Trigger the Railway deploy from CI, by API, naming the commit

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

It has never once run. `main` has been advancing without reaching production.

**What was actually wrong.** The Railway service's *source* is connected —
`repo: agranado2k/google-books-clojure`, `branch: main` — which made the setup
look complete. But a connected source is not a trigger. Railway fires a deploy
on push through a **deployment trigger**, which only exists once the **Railway
GitHub App** has been granted access to the repository, and this account has
never installed it. A GraphQL query against Railway's API for this project
settles it:

```graphql
query { deploymentTriggers(projectId: "…", environmentId: "…", serviceId: "…")
        { edges { node { id branch } } } }
```

It returns **zero rows**. `railway service source connect` set the source but
created no trigger, so there is nothing listening to a push. Installing the App
is a browser-only step in GitHub's own UI that no token or CLI in this project
can perform.

So two things have to be settled: what replaces the trigger, and — since we are
choosing anyway — whether the replacement deploys on *push* (what the trigger
was trying to do) or on *green* (what CI can additionally offer).

## Decision drivers

1. **A deploy must actually happen.** The current state is a silent no-op, which
   is the worst of the options because it looks configured.
2. **A red `main` must not reach production.** Branch protection is unavailable
   on this repo (private, no GitHub Pro), so nothing stops a red merge; the
   deploy gate is the only place left where "green" can be made to mean
   something.
3. **A deployment must say which commit it is.** "What is in production?" has to
   be answerable from the deployment record itself, by anyone, without
   reconstructing which runner uploaded what.
4. **Blast radius of the credential.** Whatever holds a deploy token can deploy.
   It must not be reachable from a pull request, least of all a fork's.
5. **No permanently red pipeline for anyone without the token.** A fork, or this
   repo before the secret exists, must show green.
6. **No new trust.** Prefer not to hand a production credential to an additional
   third-party action.

## Considered options

1. **CI calls Railway's API to deploy a named commit, after the gate jobs are
   green** *(chosen)* — a `deploy` job in `.github/workflows/ci.yml`,
   `needs: [test, docs-gate]`, push-to-`main` only, that POSTs the
   `serviceInstanceDeployV2` mutation with `commitSha: ${{ github.sha }}` and
   then polls the deployment to a terminal state.
2. **Install the Railway GitHub App and use Railway's native "Wait for CI"** —
   rejected *for now*, on two grounds. First, it is not available: the toggle
   only appears once a workflow **and** a deployment trigger both exist, and
   there is no trigger without the App, which cannot be installed from here.
   Second, on how it behaves once it is available — per a statement from Railway
   staff, **not** from Railway's documentation, and therefore recorded here as
   an unverified claim — Wait-for-CI waits on **all** check suites for the
   commit, with no way to select which. This repo ships
   `.github/workflows/ai-review.example.yml`, two deliberately **advisory** AI
   review jobs; under Wait-for-CI, activating that file would silently promote
   both into deploy blockers. Advisory review that can block production is no
   longer advisory. Revisit if Railway adds per-check selection.
3. **`railway up` from CI** — rejected on **commit provenance**, and this is the
   substantive reason the first cut of this branch was rewritten.
   `railway up` uploads a **gzipped tarball of the local working directory** and
   transmits no commit metadata whatsoever (read in `railwayapp/cli`:
   `src/controllers/upload.rs`, `src/commands/up.rs`). Confirmed live on this
   project: the deployment produced by `railway up` records **`commit = none`**,
   while one produced by the API mutation records `commit = e9573f5`. Two
   consequences, both disqualifying against drivers 1 and 3: nobody can tell
   which commit is in production, and what ships is the *runner's working tree*
   rather than the repository's state — a deploy that cannot be reproduced by
   pointing Railway at a revision, only by re-running a workflow.
4. **Deploy manually from a laptop** — rejected: it puts the production
   credential on a developer machine and makes "what is deployed?" a question
   only one person can answer.
5. **A scheduled job that deploys the tip of `main` periodically** — rejected:
   it decouples the deploy from the change that caused it, so a bad deploy has
   no obvious author and no obvious diff.

## Decision outcome

Chosen: **CI triggers the deploy through Railway's API, naming the commit,
gated on green.**

1. `.github/workflows/ci.yml` carries a `deploy` job that runs **only** on
   `push` to `main` — `if: github.event_name == 'push' && github.ref ==
   'refs/heads/main'`. Both clauses are kept even though either alone suffices
   (a `pull_request` run's `github.ref` is `refs/pull/N/merge`): a deploy job is
   the wrong place to rely on one condition.
2. It `needs: [test, docs-gate]`. A failed or cancelled dependency skips it, so
   **a red `main` never reaches production**. This is the part of the decision
   that stands on its own merit rather than on the trigger's absence: it would
   be the better mechanism even if the trigger existed, because a push trigger
   deploys on push and cannot see a test result.
3. `tdd-pairing` is deliberately **not** a `needs` edge. It is
   `pull_request`-only by construction (it compares a PR's head against its
   merge base, which a push to `main` does not have), and a dependency on a job
   that never runs on `push` would skip the deploy forever.
4. **The mechanism is one HTTP call**, not a CLI: `POST
   https://backboard.railway.com/graphql/v2` with the mutation
   `serviceInstanceDeployV2(serviceId:, environmentId:, commitSha:)`, which
   returns a deployment id. `commitSha` is `${{ github.sha }}` — the exact
   revision CI proved green. Railway pulls that revision from the connected
   repo itself, and validates the sha against it (an unknown sha comes back as
   "Commit not found"). **The connected source is what makes this work despite
   there being no trigger**: connection is what Railway needs to *fetch* a
   commit; a trigger is only what it needs to *notice* a push.
5. **The service is named explicitly, by id, and that is load-bearing.** An
   earlier hand-run deploy that left service resolution to a default hit the
   project's Postgres service and took the database down. The workflow hardcodes
   the **app** service id (`17d364a0-…`) alongside the project and production
   environment ids. These three identifiers are **not credentials** — they are
   safe in the clear, and keeping them in the file makes the deploy target a
   reviewable fact rather than a lookup. The token is the secret.
6. Authentication is `RAILWAY_TOKEN`, a Railway **project** token scoped to this
   project and its `production` environment, read from GitHub Actions repository
   secrets. **No token value is committed.** It is sent as the
   **`Project-Access-Token`** header — *not* `Authorization: Bearer`, which is
   the form for account/workspace tokens and is rejected for a project token.
   It is mapped to a job-level `env` so that a *step-level*
   `if: env.RAILWAY_TOKEN != ''` can guard on it — the `secrets` context is not
   available in a job-level `if:`.
7. **Missing secret is a skip, not a failure.** With no token, the job runs one
   step that emits a `::notice` explaining that nothing was deployed and how to
   enable it, and every other step is skipped. The run stays green.
8. **A GraphQL error is a job failure.** GraphQL returns errors as **HTTP 200
   with an `errors` array**, so the status code alone proves nothing. The job
   checks the status code, checks for a non-empty `errors` array, and checks
   that `data.serviceInstanceDeployV2` is present; any of the three failing
   prints the response and fails the job. A deploy that did not start must never
   look like a deploy that did.
9. **The job then polls to completion**, so green means *deployed*. It queries
   `deployment(id:) { status }` every 10 seconds; `SUCCESS` passes,
   `FAILED`/`CRASHED` fail, `REMOVED`/`SKIPPED` fail as "did not land", anything
   else (`BUILDING`, `DEPLOYING`, …) keeps waiting. Bounded at **15 minutes**,
   after which the job fails with a message saying the deploy may still be
   running. Five consecutive unreadable polls also fail, so "could not ask" is
   never reported as "timed out".
10. **No action, no CLI, no package install.** The job uses `curl` and `jq`,
    both present on `ubuntu-latest`, so nothing beyond GitHub and Railway is
    trusted with a production credential (driver 6). It also has **no
    `actions/checkout`**: Railway fetches the source, so the working tree is
    not needed and the step is gone rather than left dead.
11. **Explicit non-goal**: this does not decide *what* is built or how it is
    packaged — that is ADR-0002 — and it does not introduce environments,
    approvals, staged rollouts, or a rollback mechanism. Rollback remains a
    Railway-console action.

## Consequences

- **Good**: deploys happen again, and only from a commit that passed the suite,
  the uberjar build and the docs gate.
- **Good**: **every deployment records its commit.** The Railway deployment
  carries the sha, so "what is in production?" is answerable from Railway alone,
  and a deploy is reproducible by naming the same revision again.
- **Good**: a green `deploy` job means the app is deployed, not that a deploy
  was requested — clause 9 holds the job open until Railway reaches a terminal
  state.
- **Good**: the deploy credential lives in exactly one place (a repository
  secret), is reachable from exactly one job that cannot run on a pull request,
  and is scoped to one project and one environment.
- **Bad / trade-off**: GitHub Actions is no longer credential-free. The
  workflow's header comment used to be able to say "the jobs hold a read-only
  token and make no external call"; that is now true of every job except
  `deploy`. Anyone reviewing a change to this file is reviewing a change to
  something that can deploy.
- **Bad / trade-off**: a push-to-`main` run is now as long as a Railway build —
  up to 15 minutes of runner time per merge, where `--detach` cost seconds.
  That is the price of clause 9 and it is paid deliberately.
- **Honest limitation — this bypasses Railway's own trigger model.** Railway's
  intended mechanism is App → trigger → deploy-on-push; this reaches past it and
  calls the deploy directly. **If the Railway GitHub App is ever installed, a
  deployment trigger appears and every merge deploys twice** — once from the
  trigger, once from this job. Whoever installs the App turns one of the two
  paths off *in the same change*, and records which. The check that tells you
  which world you are in is the `deploymentTriggers` query quoted above: zero
  rows means only this job deploys.
- **Honest limitation — the failure mode if the secret is missing**: the job
  turns into a one-step no-op that prints a notice and passes. **A missing or
  revoked token therefore looks exactly like a healthy pipeline**, which is the
  same silent-no-op failure this ADR exists to fix, merely moved. It is accepted
  because driver 5 (forks and token-less clones must stay green) and a hard
  failure cannot both hold. The mitigation is not in CI: after a merge, confirm
  in the Railway console that a deployment actually started.
- **Honest limitation**: `SUCCESS` from Railway means the deployment is live,
  not that the app is *well*. Nothing here calls `GET /health` on the deployed
  revision; that is a human check or a later ticket.
- **Honest limitation**: branch protection is still unavailable, so a red run
  cannot block a *merge*. It can only block the *deploy*, which is what clause 2
  buys.

## More information

- Implemented on branch `ci/deploy-on-green`, in `.github/workflows/ci.yml`.
- Operator step, one time, in the browser. **No GitHub App install is required
  for this path** — the service source connection this project already has is
  enough.
  1. **Railway → this project → Settings → Tokens** → create a **project
     token**, scoped to this project and the **`production`** environment. A
     project token is preferred over a personal/account token: a leak cannot
     reach the account's other projects, and it is not tied to a person who may
     leave.
  2. **GitHub → this repo → Settings → Secrets and variables → Actions → New
     repository secret**, named `RAILWAY_TOKEN`, with that value.
- The three ids in the workflow (project, production environment, app service)
  are non-secret. If the Railway project is ever recreated they change, and the
  workflow must be updated with them.
- Related: ADR-0002 (packaging and Railway as the runtime), ADR-0003 (the
  database this deploy must not disturb — see clause 5), and the diary entry of
  2026-08-09 that this record reverses in part.
