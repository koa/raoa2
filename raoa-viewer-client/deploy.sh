#!/usr/bin/env bash

cd $(dirname "$0")
mvn clean install && cd ../raoa-viewer && ./deploy.sh
