#!/usr/bin/env bash
#
# Copyright Kroxylicious Authors.
#
# Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
#

set -euo pipefail
DEFAULT_REGISTRY_DESTINATION='quay.io/kroxylicious/kroxylicious-developer'
REGISTRY_DESTINATION=${REGISTRY_DESTINATION:-${DEFAULT_REGISTRY_DESTINATION}}

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
. "${SCRIPT_DIR}/common.sh"
cd "${SCRIPT_DIR}/.."

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <SAMPLE_DIR>"
  exit 1
elif [[ ! -d "${1}"  ]]; then
  echo "$0: Sample directory ${1} does not exist."
  exit 1
fi

SAMPLE_DIR=${1}
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NOCOLOR='\033[0m'


KUSTOMIZE_TMP=$(mktemp -d)
function cleanTmpDir {
  rm -rf "${KUSTOMIZE_TMP}"
}
trap cleanTmpDir EXIT

if [[ "${REGISTRY_DESTINATION}" != "${DEFAULT_REGISTRY_DESTINATION}" ]]; then
  echo "building and pushing image to ${REGISTRY_DESTINATION}"
  PUSH_IMAGE=y "${SCRIPT_DIR}/deploy-image.sh"
else
  echo "REGISTRY_DESTINATION is ${REGISTRY_DESTINATION}, not building/deploying image"
fi


if ! ${MINIKUBE} status 1>/dev/null 2>/dev/null; then
  set +e
  MINIKUBE_MEM=$(${MINIKUBE} config get memory 2>/dev/null)
  MINIKUBE_MEM_EXIT=$?
  set -e
  MINIKUBE_CONF='--memory=4096'
  if [ $MINIKUBE_MEM_EXIT -eq 0 ]; then
    if [[ "$MINIKUBE_MEM" -lt 4096 ]]; then
      echo "minikube memory is configured to below 4096 by user, overriding to 4096"
    else
      echo "minikube memory configured by user"
      MINIKUBE_CONF=''
    fi
  else
    echo "no minikube memory configuration, defaulting to 4096M"
  fi
  ${MINIKUBE} start "${MINIKUBE_CONF}"
  echo -e "${GREEN}minikube started.${NOCOLOR}"
else
  echo -e "${GREEN}minikube instance already available, we'll use it.${NOCOLOR}"
fi

NAMESPACE=kafka

# Prepare kustomize overlay
cp -r "${SAMPLE_DIR}" "${KUSTOMIZE_TMP}"
OVERLAY_DIR=$(find "${KUSTOMIZE_TMP}" -type d -name minikube)

if [[ ! -d "${OVERLAY_DIR}" ]]; then
     echo "$0: Cannot find minikube overlay within sample."
     exit 1
fi

pushd "${OVERLAY_DIR}" > /dev/null
${KUSTOMIZE} edit set namespace ${NAMESPACE}
if [[ "${REGISTRY_DESTINATION}" != "${DEFAULT_REGISTRY_DESTINATION}" ]]; then
  ${KUSTOMIZE} edit set image "${DEFAULT_REGISTRY_DESTINATION}=${REGISTRY_DESTINATION}"
fi
popd > /dev/null

# Install cert-manager (if necessary)
if grep --count --quiet --recursive cert-manager.io "${SAMPLE_DIR}"; then
  ${KUBECTL} apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.12.0/cert-manager.yaml
  ${KUBECTL} wait deployment/cert-manager-webhook --for=condition=Available=True --timeout=300s -n cert-manager
  echo -e "${GREEN}cert-manager installed.${NOCOLOR}"
fi

# Install HashiCorp Vault (if necessary)
if [[ -f ${SAMPLE_DIR}/helm-vault-values.yml ]];
then
  ${KUBECTL} create ns vault 2>/dev/null || true
  ${HELM} repo add hashicorp https://helm.releases.hashicorp.com
  # use helm's idempotent install technique
  ${HELM} upgrade --install vault hashicorp/vault --namespace vault --values "${SAMPLE_DIR}/helm-vault-values.yml" --wait
  ${KUBECTL} exec vault-0 -n vault -- vault secrets enable transit
  echo -e "${GREEN}HashiCorp Vault installed.${NOCOLOR}"
fi

# Install strimzi
${KUBECTL} create namespace kafka 2>/dev/null || true
${KUBECTL} apply -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
${KUBECTL} wait deployment strimzi-cluster-operator --for=condition=Available=True --timeout=300s -n kafka
${KUBECTL} set env deployment strimzi-cluster-operator -n kafka STRIMZI_FEATURE_GATES=+UseKRaft,+KafkaNodePools
echo -e "${GREEN}Strimzi installed.${NOCOLOR}"


# Apply sample using Kustomize
COUNTER=0
while ! ${KUBECTL} apply -k "${OVERLAY_DIR}"; do
  echo "Retrying ${KUBECTL} apply -k ${OVERLAY_DIR} .. probably a transient webhook issue."
  # Sometimes the cert-manager's muting webhook is not ready, so retry
  (( COUNTER++ )) || true
  sleep 5
  if [[ "${COUNTER}" -gt 10 ]]; then
    echo "$0: Cannot apply sample."
    exit 1
  fi
done
echo -e "${GREEN}Kafka and Kroxylicious config successfully applied.${NOCOLOR}"

${KUBECTL} wait kafka/my-cluster --for=condition=Ready --timeout=300s -n ${NAMESPACE}
${KUBECTL} wait deployment/kroxylicious-proxy --for=condition=Available=True --timeout=300s -n ${NAMESPACE}
echo -e "${GREEN}Kafka and Kroxylicious deployments are ready.${NOCOLOR}"

if [[ -f "${SAMPLE_DIR}"/postinstall.sh ]]; then
   "${SAMPLE_DIR}"/postinstall.sh
fi
