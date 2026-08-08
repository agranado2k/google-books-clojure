#!/bin/sh
# mutation-delta-ci.sh — the CI half of the differential mutation diagnostic.
#
# Runs mutation-delta.sh over the pull request's own changes and publishes the
# summary as exactly ONE pull-request comment, edited in place on every re-run.
#
# Usage: mutation-delta-ci.sh          (no arguments; reads the Actions env)
#
# Requires, from the GitHub Actions environment: GITHUB_EVENT_PATH (the
# pull_request event payload), GITHUB_REPOSITORY, `jq`, and a `gh`
# authenticated for `pull-requests: write`.
#
# WHY THE LOGIC IS HERE AND NOT IN THE WORKFLOW. Everything this gate decides —
# the label rule, the comment body, find-the-marker-or-create — lives in this
# script, where it is executable and can be driven by simulated event payloads
# and a recording `gh` stub in a test. What is left in the workflow is checkout,
# install, and one call. The workflow's job-level `if:` is the one deliberate
# restatement: it is the only place that can stop a runner from being allocated
# at all, and this script re-checks the same rule so that widening the trigger
# cannot quietly start spending.

set -eu

MARKER='<!-- mutation-delta -->'
LABEL='mutation-check'

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

: "${GITHUB_EVENT_PATH:?not set — this script runs inside a GitHub Actions pull_request event}"
: "${GITHUB_REPOSITORY:?not set — this script runs inside a GitHub Actions pull_request event}"

event=$GITHUB_EVENT_PATH
action=$(jq -r '.action // empty' "$event")

# THE SPEND GATE, and the whole reason this is not a per-push job. Two actions
# reach it and they ask DIFFERENT questions:
#
#   labeled      — did THIS event add `mutation-check`? A labelled PR keeps its
#                  label, so `labeled` fires again for every other label anyone
#                  adds; asking "does the PR carry it" here would charge a
#                  mutation run to an event that changed no code.
#   synchronize  — does the PR carry `mutation-check` now? New commits on an
#                  already-labelled PR must re-measure, or the comment describes
#                  a diff that has moved on, which is worse than no comment.
if [ "$action" = labeled ]; then
	added=$(jq -r '.label.name // empty' "$event")
	if [ "$added" != "$LABEL" ]; then
		echo "::notice title=Mutation delta::skipped — this event added '$added', not '$LABEL'."
		exit 0
	fi
elif ! jq -e --arg l "$LABEL" '[.pull_request.labels[]?.name] | index($l)' "$event" >/dev/null; then
	echo "::notice title=Mutation delta::skipped — this pull request does not carry the '$LABEL' label."
	exit 0
fi

pr=$(jq -r '.pull_request.number // empty' "$event")
base=$(jq -r '.pull_request.base.sha // empty' "$event")

# Say so rather than sending an empty PR number to the API, where the answer
# would be an ordinary 404 and the cause would be invisible.
if [ -z "$pr" ] || [ -z "$base" ]; then
	echo "::error title=Mutation delta::the event payload names no pull request (number='$pr', base='$base') — this job only runs on \`pull_request\`." >&2
	exit 1
fi

# The base commit the PR forked from, not `origin/main`: the checkout is the
# merge commit, so this scopes the run to precisely the PR's own changes.
delta=$("$here/mutation-delta.sh" "$base")
printf '%s\n' "$delta"

body=$(mktemp)
trap 'rm -f "$body"' EXIT
{
	printf '%s\n' "$MARKER"
	printf '## Mutation delta\n\n'
	printf '```\n%s\n```\n' "$delta"
	# The reader may be meeting this comment for the first time. A mutation
	# score is not a pass mark, and a score of 83 percent must not read as a
	# failed gate.
	printf '\n<sub>A **diagnostic, never a gate**: it gates no merge and is not a required check. '
	printf 'Each survivor is behaviour this branch'"'"'s tests do not enforce — strengthen the test, or say why the mutant is equivalent. '
	printf 'Pushing again refreshes this comment; removing the `%s` label stops it. Locally: `scripts/mutation-delta.sh`.</sub>\n' "$LABEL"
} >"$body"

# ONE comment per pull request, edited in place. Find-by-MARKER, not by author:
# the token's identity is `github-actions[bot]`, which every other workflow in
# the repo also posts as, and an author match would let this script overwrite
# one of their comments. The marker is an HTML comment, invisible when rendered.
#
# `--paginate` emits one JSON array per page and jq filters each in turn — hence
# `head -n 1` rather than a slurp.
existing=$(
	gh api --paginate "repos/$GITHUB_REPOSITORY/issues/$pr/comments" |
		jq -r --arg m "$MARKER" 'map(select((.body // "") | contains($m))) | .[0].id // empty' |
		head -n 1
)

payload=$(jq -n --rawfile body "$body" '{body: $body}')
if [ -n "$existing" ]; then
	printf '%s' "$payload" |
		gh api --method PATCH "repos/$GITHUB_REPOSITORY/issues/comments/$existing" --input - --silent
	echo "::notice title=Mutation delta::updated comment $existing on PR #$pr."
else
	printf '%s' "$payload" |
		gh api --method POST "repos/$GITHUB_REPOSITORY/issues/$pr/comments" --input - --silent
	echo "::notice title=Mutation delta::posted the mutation delta on PR #$pr."
fi
