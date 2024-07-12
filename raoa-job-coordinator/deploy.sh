#!/usr/bin/env bash

#namespace=raoa-dev
#namespace=raoa-prod
namespace=raoa-lkw

cd "$(dirname "$0")"
version=$(date "+%Y%m%d%H%M%S")


mvn -Dlocal.version=$version -Djib.httpTimeout=300000 clean deploy || exit
kubectl -n $namespace set image deployment/$namespace-coordinator raoa=docker-snapshot.berg-turbenthal.ch/raoa-job-koordinator:$version
