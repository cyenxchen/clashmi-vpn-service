#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
helper="${script_dir}/commit-rebuilt-artifacts.sh"
workflow="${repo_root}/.github/workflows/rebuild-android-core.yml"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -f "${helper}" ]] || fail "missing artifact commit helper: ${helper}"
grep -Fq 'bash .github/scripts/commit-rebuilt-artifacts.sh' "${workflow}" ||
  fail "rebuild workflow does not invoke the tested artifact commit helper"
grep -Fq "if: steps.commit.outputs.changed == 'true'" "${workflow}" ||
  fail "downstream dispatch is not guarded by a successful artifact push"

tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/clashmi-artifact-push-test.XXXXXX")"
cleanup() {
  # The path is created above and is never supplied by the caller.
  rm -rf -- "${tmp_root}"
}
trap cleanup EXIT

remote="${tmp_root}/remote.git"
seed="${tmp_root}/seed"
worker="${tmp_root}/worker"
concurrent="${tmp_root}/concurrent"

git init --bare --initial-branch=main "${remote}" >/dev/null
git clone "${remote}" "${seed}" >/dev/null 2>&1
git -C "${seed}" config user.name "CI Test"
git -C "${seed}" config user.email "ci-test@example.invalid"
printf 'initial artifact\n' >"${seed}/artifact.bin"
git -C "${seed}" add artifact.bin
git -C "${seed}" commit -m "seed" >/dev/null
git -C "${seed}" push origin main >/dev/null 2>&1

git clone "${remote}" "${worker}" >/dev/null 2>&1
git clone "${remote}" "${concurrent}" >/dev/null 2>&1
git -C "${worker}" config user.name "CI Worker"
git -C "${worker}" config user.email "ci-worker@example.invalid"
git -C "${concurrent}" config user.name "Concurrent Writer"
git -C "${concurrent}" config user.email "concurrent@example.invalid"

# Reproduce the Actions failure: the worker has staged rebuilt output while a
# separate push advances main before the worker can commit and push.
printf 'new service source\n' >"${concurrent}/source.txt"
git -C "${concurrent}" add source.txt
git -C "${concurrent}" commit -m "advance main" >/dev/null
git -C "${concurrent}" push origin main >/dev/null 2>&1
concurrent_head="$(git -C "${concurrent}" rev-parse HEAD)"

printf 'stale rebuilt artifact\n' >"${worker}/artifact.bin"
git -C "${worker}" add artifact.bin
stale_output="${tmp_root}/stale-output"
(
  cd "${worker}"
  GITHUB_OUTPUT="${stale_output}" TARGET_BRANCH=main bash "${helper}"
)

grep -Fxq 'changed=false' "${stale_output}" ||
  fail "stale build was not reported as unchanged"
grep -Fxq 'stale=true' "${stale_output}" ||
  fail "concurrent remote update was not reported as stale"
remote_head="$(git --git-dir="${remote}" rev-parse refs/heads/main)"
[[ "${remote_head}" == "${concurrent_head}" ]] ||
  fail "stale worker overwrote the concurrent main update"

# Confirm the same helper still commits and pushes when the worker starts from
# the current remote head.
git -C "${worker}" fetch origin main >/dev/null 2>&1
git -C "${worker}" reset --hard origin/main >/dev/null
printf 'fresh rebuilt artifact\n' >"${worker}/artifact.bin"
git -C "${worker}" add artifact.bin
fresh_output="${tmp_root}/fresh-output"
(
  cd "${worker}"
  GITHUB_OUTPUT="${fresh_output}" TARGET_BRANCH=main bash "${helper}"
)

grep -Fxq 'changed=true' "${fresh_output}" ||
  fail "fresh build was not reported as changed"
grep -Fxq 'stale=false' "${fresh_output}" ||
  fail "fresh build was incorrectly reported as stale"
[[ "$(git --git-dir="${remote}" show main:artifact.bin)" == 'fresh rebuilt artifact' ]] ||
  fail "fresh rebuilt artifact was not pushed"

echo "PASS: artifact commit helper handles stale and current main"
