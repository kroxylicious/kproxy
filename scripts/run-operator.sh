#!/usr/bin/env bash
#
# Copyright Kroxylicious Authors.
#
# Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
#

# simple script to build and run the operator
cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" || exit
cd ..
echo "building operator maven project"
mvn package --projects :kroxylicious-operator --also-make -DskipTests
cd kroxylicious-operator || exit

if ! minikube status
then
  echo "starting minikube"
  minikube start
fi

echo "building operator image in minikube"
minikube image build . -t quay.io/kroxylicious/operator:latest --build-opt=build-arg=KROXYLICIOUS_VERSION=0.9.0-SNAPSHOT

echo "installing kafka (no-op if already installed)"
kubectl create namespace kafka
kubectl create -n kafka -f 'https://strimzi.io/install/latest?namespace=kafka'
kubectl wait -n kafka deployment/strimzi-cluster-operator --for=condition=Available=True --timeout=300s
kubectl apply -n kafka -f https://strimzi.io/examples/latest/kafka/kraft/kafka-single-node.yaml
kubectl wait -n kafka kafka/my-cluster --for=condition=Ready --timeout=300s

echo "deleting example"
kubectl delete -f examples/simple/ --ignore-not-found=true --timeout=30s --grace-period=1

echo "deleting kroxylicious-operator installation"
kubectl delete -n kroxylicious-operator all --all --timeout=30s --grace-period=1
kubectl delete -f install --ignore-not-found=true --timeout=30s --grace-period=1

echo "deleting all kroxylicious.io resources and crds"
for crd in $(kubectl get crds -oname | grep kroxylicious.io | awk -F / '{ print $2 }');
  do
  export crd
  echo "deleting resources for crd: $crd"
  kubectl delete -A --all "$(kubectl get crd "${crd}" -o=jsonpath='{.spec.names.singular}')" --timeout=30s --grace-period=1
  echo "deleting crd: ${crd}"
  kubectl delete crd "${crd}"
done

echo "installing crds"
kubectl apply -f src/main/resources/META-INF/fabric8
echo "installing kroxylicious-operator"
kubectl apply -f install
echo "installing simple proxy"
kubectl apply -f examples/simple/

if kubectl wait -n my-proxy kafkaproxy/simple --for=condition=Ready=True --timeout=300s
then
  echo "simple proxy should now be available in-cluster at my-cluster.my-proxy.svc.cluster.local:9292"
else
  echo "something went wrong!"
  exit 1
fi
