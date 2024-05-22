#! /usr/bin/env bash
#
# Copyright Kroxylicious Authors.
#
# Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
#

PERF_TESTS_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

source "${PERF_TESTS_DIR}"/../scripts/common.sh

GREEN='\033[0;32m'
NOCOLOR='\033[0m'

FIND_COMMAND="find"
if [ "$OS" = 'Darwin' ] && resolveCommand gfind ; then
  FIND_COMMAND=gfind
  ENABLE_REGEX="-regextype posix-extended"
elif [ "$OS" = 'Darwin' ] ; then
  # for BSD find
  ENABLE_REGEX="-E"
else
  # for gnu find
  ENABLE_REGEX="-regextype posix-extended"
fi

COMMITS=( "$@" )

#Cross platform temp directory creation based on https://unix.stackexchange.com/a/84980
CHECKOUT_DIR=${CHECKOUT_DIR:=$(mktemp -d -t kroxyliciousPerfTestCheckout.XXXXXX 2>/dev/null || mktemp -d -t 'kroxyliciousPerfTestCheckout')}
RESULTS_DIR=${RESULTS_DIR:=$(mktemp -d -t kroxyliciousPerfTestResults.XXXXXX 2>/dev/null || mktemp -d -t 'kroxyliciousPerfTestResults')}
JSON_TEMP_DIR=${JSON_TEMP_DIR:=$(mktemp -d -t kroxyliciousPerfTestJson.XXXXXX 2>/dev/null || mktemp -d -t 'kroxyliciousPerfTestJson')}
CLONE_URL=${CLONE_URL:-"$(git config --get remote.origin.url)"}

export PUSH_IMAGE=y #remove this once pulling is optional to save some time.
export TEMP_BUILD=y

cloneRepo() {
  cd "${CHECKOUT_DIR}" || exit 127
  git clone -q "${CLONE_URL}" || exit 128
  cd "kroxylicious" || exit 129
}

checkoutCommit() {
  local COMMIT_ID=$1
  echo -e "Checkout ${GREEN}${COMMIT_ID}${NOCOLOR}"
  git checkout --quiet "${COMMIT_ID}"
}

buildImage() {
  local COMMIT_ID=$1
  echo -e "Building image with tag ${GREEN}g_${COMMIT_ID}${NOCOLOR}"
  "${PERF_TESTS_DIR}/../scripts/build-image.sh" -t "g_${COMMIT_ID}" -s > /dev/null
}

runPerfTest() {
  local COMMIT_ID=$1
  export KIBANA_OUTPUT_DIR=${RESULTS_DIR}/${COMMIT_ID}
  mkdir -p "${KIBANA_OUTPUT_DIR}"
  export KROXYLICIOUS_IMAGE="${REGISTRY_DESTINATION}:g_${COMMIT_ID}"
  echo -e "Running tests using ${GREEN}${KROXYLICIOUS_IMAGE}${NOCOLOR}"
  "${PERF_TESTS_DIR}/perf-tests.sh"
}

mergeResults() {
  echo -e "Merging results in:  ${GREEN}${RESULTS_DIR}${NOCOLOR}"
  mapfile -t TEST_NAMES < <(${FIND_COMMAND} "${RESULTS_DIR}" -type d ${ENABLE_REGEX} -regex  ".*\/[0-9]{2}-.*" -exec basename {} \; | sort | uniq)

  for TEST_NAME in "${TEST_NAMES[@]}"; do
   echo "generating ${TEST_NAME}"
   JQ_COMMAND=".[0]"
   idx=0
   for COMMIT in "${COMMITS[@]}"; do
     if [ ${idx} -ne 0 ]; then
       JQ_COMMAND+=" * .[$((idx++))]"
     else
       ((idx++))
     fi
     jq -cn \
      --arg commit "${COMMIT}" \
      --arg key "${TEST_NAME}" \
      --slurpfile producer_results "${RESULTS_DIR}/${COMMIT}/${TEST_NAME}/producer.json" \
      --slurpfile consumer_results "${RESULTS_DIR}/${COMMIT}/${TEST_NAME}/consumer.json" \
      '{producer: {($key): {($commit): $producer_results}}, consumer: {($key): {($commit): $consumer_results}}}' \
     > "${JSON_TEMP_DIR}/${TEST_NAME}-${COMMIT}.json"
   done

   jq  -c -s "${JQ_COMMAND}" "${JSON_TEMP_DIR}/${TEST_NAME}"-*.json > "${RESULTS_DIR}/${TEST_NAME}-all.json"
  done
}

cloneRepo

for COMMIT in "${COMMITS[@]}"; do
    checkoutCommit "${COMMIT}"

    SHORT_COMMIT=$(git rev-parse --short HEAD)

    buildImage "${SHORT_COMMIT}"

    runPerfTest "${SHORT_COMMIT}"
done

mergeResults
