#!/usr/bin/env bash
# PM2 entry point for the Spring Boot API. Runs from the backend checkout so ./.env and
# knowledge/restaurant.md resolve exactly as they did for the Node.js process.
set -euo pipefail
cd "$(dirname "$0")/.."
java="$HOME/.local/share/rtr-java/current/bin/java"
[[ -x "$java" ]] || java=java
exec "$java" \
  -Xms64m -XX:MaxRAMPercentage=35 -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k \
  -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
  -jar "${RTR_BACKEND_JAR:-/home/ubuntu/apps/backend-releases/current.jar}"
