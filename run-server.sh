#!/usr/bin/env bash
set -euo pipefail
java -jar auction-server/target/auction-server-1.0.0-all.jar --bind=0.0.0.0 --port=5050 --data=data/state.json
