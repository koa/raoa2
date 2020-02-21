#!/usr/bin/env bash

namespace=raoa-dev

cd "$(dirname "$0")"
version=$(date "+%Y%m%d-%H%M%S")

sed 's/^appVersion:.*/appVersion: '$version'/' raoa/Chart.yaml >/tmp/chart.$$ && mv /tmp/chart.$$ raoa/Chart.yaml

mvn -Dlocal.version=$version clean deploy

kubectl -n $namespace set image deployment/raoa-image-processor raoa=koa1/raoa-image-processor:$version --record
kubectl -n $namespace set image deployment/raoa-coordinator raoa=koa1/raoa-job-koordinator:$version --record
kubectl -n $namespace set image deployment/raoa-viewer raoa=koa1/raoa-viewer:$version --record

kubectl -n $namespace rollout history deployment/raoa-image-processor
kubectl -n $namespace rollout history deployment/raoa-viewer
kubectl -n $namespace rollout history deployment/raoa-coordinator

kubectl -n $namespace rollout status -w deployment/raoa-coordinator
kubectl -n $namespace rollout status -w deployment/raoa-viewer
kubectl -n $namespace rollout status -w deployment/raoa-image-processor
