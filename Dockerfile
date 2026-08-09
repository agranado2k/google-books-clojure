# syntax=docker/dockerfile:1
# The syntax line pins the BuildKit frontend rather than inheriting whatever the
# builder happens to default to: `ADD --checksum` below needs frontend >= 1.6,
# and the build host (Railway's) is not ours to assume.
#
# Build and runtime stages must run the same Java major (21) and are pinned by
# digest: refresh the two digests deliberately, and together.

# The Tailwind release fetched below. Keep it equal to
# EXPECTED_TAILWIND_VERSION in scripts/build-css.sh — that script is what the
# build stage runs, and it fails the build on drift.
ARG TAILWIND_VERSION=4.3.3

FROM clojure:temurin-21-tools-deps@sha256:d08d523486049ae293012a57a267d290d0400378e097ea84eddb5e8337510a7a AS deps
WORKDIR /app
COPY deps.edn build.clj ./
RUN clojure -P && clojure -P -T:build

# Tailwind standalone CLI (no Node toolchain), version-pinned and fetched with
# a BuildKit checksum so the download is digest-verified like the base images.
# One stage per arch; TARGETARCH selects the right one below. The checksums are
# per-arch literals for TAILWIND_VERSION above — recompute BOTH when bumping it.
FROM deps AS tailwind-amd64
ARG TAILWIND_VERSION
# sha256 of tailwindcss-linux-x64 for v4.3.3
ADD --checksum=sha256:dc61b3ac6b8c9ca874c0cc4c57b2409791a64c5540404ca5f5367360babc313a --chmod=755 \
    https://github.com/tailwindlabs/tailwindcss/releases/download/v${TAILWIND_VERSION}/tailwindcss-linux-x64 \
    /usr/local/bin/tailwindcss

FROM deps AS tailwind-arm64
ARG TAILWIND_VERSION
# sha256 of tailwindcss-linux-arm64 for v4.3.3
ADD --checksum=sha256:55fd0b241214eff3de1e8ee4f22796662f2d2e7a49bcfca7477cfd0bac398195 --chmod=755 \
    https://github.com/tailwindlabs/tailwindcss/releases/download/v${TAILWIND_VERSION}/tailwindcss-linux-arm64 \
    /usr/local/bin/tailwindcss

FROM tailwind-${TARGETARCH} AS build
COPY src ./src
COPY resources ./resources
COPY styles ./styles
COPY scripts ./scripts
# One CSS build definition, shared with local dev. The script also asserts the
# binary is the expected Tailwind, so ARG TAILWIND_VERSION above and
# EXPECTED_TAILWIND_VERSION in the script cannot drift apart unnoticed.
RUN sh scripts/build-css.sh
RUN clojure -T:build uber

FROM eclipse-temurin:21-jre@sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55
WORKDIR /app
RUN useradd -r -u 1001 app
USER app
# target/app.jar is uber-file in build.clj — keep the two in step.
COPY --from=build /app/target/app.jar app.jar
CMD ["java", "-jar", "app.jar"]
