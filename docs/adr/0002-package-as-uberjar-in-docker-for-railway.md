# ADR-0002: Package as an uberjar in a multi-stage Dockerfile for Railway

- **Status**: Accepted
- **Date**: 2026-08-08
- **Deciders**: Arthur Granado (hosting fixed by the PRD; mechanism researched via agent)
- **Supersedes / amends**: —
- **Superseded by**: —

## Context and problem statement

Hosting on Railway is fixed by PRD [#1](https://github.com/agranado2k/google-books-clojure/issues/1). Railway's zero-config builder (Railpack) auto-detects JVM projects only through Maven/Gradle markers — a `deps.edn` repo fails detection outright — so the repo must tell Railway how to build, and the answer shapes local dev and CI too.

## Decision drivers

- Deploys must work on Railway without custom build plumbing on their side
- The same artifact should run locally and in production (walking-skeleton doctrine)
- Supply-chain auditability and least privilege in the shipped image

## Considered options

1. **Multi-stage Dockerfile building an uberjar via tools.build** *(chosen)* — Railway uses a repo-root Dockerfile automatically; the slim JRE runtime stage ships only the jar.
2. **Railpack/Nixpacks auto-detection** — rejected: does not detect Clojure; would require repackaging the project as Maven/Gradle, fighting the deps.edn ecosystem.
3. **Buildpack with custom build command on Railway** — rejected: moves build knowledge into Railway dashboard config, unreproducible locally.

## Decision outcome

Chosen: **multi-stage Dockerfile → tools.build uberjar → slim JRE runtime**.

1. `build.clj` (tools.build) produces `target/app.jar` with `books.server` as the AOT'd main class.
2. The Dockerfile's build stage is `clojure:temurin-21-tools-deps`; the runtime stage is `eclipse-temurin:21-jre`; both are pinned by digest and share the same Java major, refreshed deliberately and together.
3. The runtime stage runs as an unprivileged user (`app`, uid 1001), never root.
4. The container honors Railway's `PORT` env var and binds `0.0.0.0` (ADR-0001 clause 3).
5. **Explicit non-goal**: this does not decide CI, the Railway project topology, or the database addon — ticket #3 and #8 territory.

## Consequences

- **Good**: `docker build && docker run` is the whole deploy story, identical locally and on Railway; digest pins make "what did we ship" answerable.
- **Bad / trade-off**: digest refreshes are a manual chore someone must own; AOT compilation slows the image build.
- **Honest limitation**: digest pinning protects against upstream re-tags, not against a compromised image published *at* the pinned digest.

## More information

- Implemented in: PR [#11](https://github.com/agranado2k/google-books-clojure/pull/11) (ticket [#2](https://github.com/agranado2k/google-books-clojure/issues/2)); hardened per its review
- Related: ADR-0001; Railway Railpack docs (Clojure undetected, verified 2026-08-08)
