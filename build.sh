#!/usr/bin/env bash
set -euo pipefail
./mvnw clean verify
mkdir -p dist
cp auction-server/target/auction-server-1.0.0-all.jar dist/auction-server.jar
cp auction-client/target/auction-client-1.0.0-all.jar dist/auction-client.jar
echo "Built dist/auction-server.jar and dist/auction-client.jar"
