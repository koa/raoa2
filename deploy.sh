#!/usr/bin/env bash

namespace=raoa-dev
#namespace=raoa-lkw

cd "$(dirname "$0")"
version=$(date "+%Y%m%d%H%M%S")

# sed 's/^appVersion:.*/appVersion: '$version'/' raoa/Chart.yaml >/tmp/chart.$$ && mv /tmp/chart.$$ raoa/Chart.yaml

mvn -Dlocal.version=$version -Djib.httpTimeout=300000 clean deploy || exit

#helm -n $namespace get values raoa | tail +2 >/tmp/raoa.yaml

#helm upgrade -n $namespace raoa . -f /tmp/raoa.yaml --set image.version=$version

#exit 0

kubectl -n raoa-dev get helmrelease raoa -o yaml | sed "s/      version:.*/      version: \"$version\"/" | kubectl -n raoa-dev apply -f -

#kubectl -n $namespace rollout status -w deployment/raoa-coordinator
#kubectl -n $namespace rollout status -w deployment/raoa-viewer
#kubectl -n $namespace rollout status -w deployment/raoa-image-processor
