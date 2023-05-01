#!/usr/bin/env bash

#namespace=raoa-dev
#namespace=raoa-prod
namespace=raoa-lkw
deployment=raoa-lkw-viewer

cd "$(dirname "$0")" || exit
version=$(date "+%Y%m%d%H%M%S")

mvn -Dlocal.version=$version -Djib.httpTimeout=300000 clean deploy || exit
kubectl -n $namespace set image deployment/$deployment raoa=docker-snapshot.berg-turbenthal.ch/raoa-viewer:$version
