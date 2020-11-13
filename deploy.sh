#!/usr/bin/env bash

namespace=raoa-dev
#namespace=raoa-lkw

cd "$(dirname "$0")"
version=$(date "+%Y%m%d-%H%M%S")

sed 's/^appVersion:.*/appVersion: '$version'/' raoa/Chart.yaml >/tmp/chart.$$ && mv /tmp/chart.$$ raoa/Chart.yaml

mvn -Dlocal.version=$version clean deploy || exit

#helm -n $namespace get values raoa | tail +2 >/tmp/raoa.yaml

#helm upgrade -n $namespace raoa . -f /tmp/raoa.yaml --set image.version=$version

#exit 0

kubectl -n $namespace set image deployment/raoa-image-processor raoa=docker-snapshot.berg-turbenthal.ch/raoa-image-processor:$version --record
kubectl -n $namespace set image deployment/raoa-coordinator raoa=docker-snapshot.berg-turbenthal.ch/raoa-job-koordinator:$version --record
kubectl -n $namespace set image deployment/raoa-viewer raoa=docker-snapshot.berg-turbenthal.ch/raoa-viewer:$version --record

kubectl -n $namespace rollout history deployment/raoa-image-processor
kubectl -n $namespace rollout history deployment/raoa-viewer
kubectl -n $namespace rollout history deployment/raoa-coordinator

kubectl -n $namespace rollout status -w deployment/raoa-coordinator
kubectl -n $namespace rollout status -w deployment/raoa-viewer
kubectl -n $namespace rollout status -w deployment/raoa-image-processor
