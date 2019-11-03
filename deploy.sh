#!/usr/bin/env bash

namespace=raoa

cd "$(dirname "$0")"
version=$(date "+%Y%m%d-%H%M%S")
mvn -Dlocal.version=$version clean deploy

kubectl -n $namespace set image deployment/raoa-image-thumbnailer main=koa1/raoa-image-thumbnailer:$version --record
kubectl -n $namespace set image deployment/raoa-viewer main=koa1/raoa-viewer:$version --record

kubectl -n $namespace rollout history deployment/raoa-image-thumbnailer
kubectl -n $namespace rollout history deployment/raoa-viewer

kubectl -n $namespace rollout status -w deployment/raoa-viewer
kubectl -n $namespace rollout status -w deployment/raoa-image-thumbnailer
