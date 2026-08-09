#!/usr/bin/env bash

set -euo pipefail

target_branch="${TARGET_BRANCH:-main}"
output_file="${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"

set_output() {
  printf '%s\n' "$1" >>"${output_file}"
}

report_stale() {
  local base_sha="$1"
  local remote_sha="$2"
  # A newer main push starts its own serialized workflow run, so an older
  # rebuild must not overwrite it or dispatch an unpublished local commit.
  echo "::warning title=Stale Android core rebuild::Skipping artifact push because origin/${target_branch} advanced from ${base_sha} to ${remote_sha}; the newer main run will rebuild it."
  set_output 'changed=false'
  set_output 'stale=true'
}

if git diff --cached --quiet; then
  echo "No rebuilt artifact changes to commit."
  set_output 'changed=false'
  set_output 'stale=false'
  exit 0
fi

base_sha="$(git rev-parse HEAD)"
git fetch --no-tags origin "${target_branch}"
remote_sha="$(git rev-parse "origin/${target_branch}")"

if [[ "${remote_sha}" != "${base_sha}" ]]; then
  report_stale "${base_sha}" "${remote_sha}"
  exit 0
fi

git commit -m "chore: rebuild Android mihomo core"
if git push origin "HEAD:${target_branch}"; then
  set_output 'changed=true'
  set_output 'stale=false'
  exit 0
fi

# Close the small race between the pre-commit fetch and the push. Only convert
# a confirmed remote advance into a stale success; authentication and other
# push failures must remain visible as real workflow failures.
git fetch --no-tags origin "${target_branch}"
latest_remote_sha="$(git rev-parse "origin/${target_branch}")"
if [[ "${latest_remote_sha}" != "${base_sha}" ]]; then
  report_stale "${base_sha}" "${latest_remote_sha}"
  exit 0
fi

echo "::error title=Android core artifact push failed::origin/${target_branch} did not advance, so the push failure is not a safe stale-build case."
exit 1
