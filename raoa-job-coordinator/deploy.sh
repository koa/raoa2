#!/usr/bin/env bash

namespace=raoa-dev
#namespace=raoa-prod
#namespace=raoa-lkw
#context=teamkoenig-lkw
context=admin@berg-main
deployment=$namespace-coordinator
#deployment=raoa-coordinator


cd "$(dirname "$0")"
version=$(date "+%Y%m%d%H%M%S")


mvn -Djava.net.preferIPv6Addresses=true -Dlocal.version=$version -Djib.httpTimeout=300000 clean deploy || exit
kubectl --context=$context -n $namespace set image deployment/$deployment raoa=docker-snapshot.berg-turbenthal.ch/raoa-job-koordinator:$version
