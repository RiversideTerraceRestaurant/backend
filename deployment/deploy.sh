#!/usr/bin/env bash
# Deploys a CI-built Spring Boot jar to this EC2 host and restarts PM2 process rtr-backend.
# .env, the knowledge file and persistent data stay on the server; a failed deploy rolls back
# to the previous commit and process (Java or the former Node.js API).
set -Eeuo pipefail

revision=${1:?Commit SHA required}
artifact=${2:?Path to the built jar required}
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || exit 1
[[ -s "$artifact" ]] || { echo "Missing jar: $artifact" >&2; exit 1; }
# Processes started by a GitHub Actions job inherit RUNNER_TRACKING_ID and are killed as
# orphans when the job ends; the PM2-managed API must not be tied to the deploy job.
unset RUNNER_TRACKING_ID
mkdir -p "$HOME/.cache"
exec 9>"$HOME/.cache/rtr-backend-deploy.lock"
flock -w 300 9

app=/home/ubuntu/apps/backend
releases=/home/ubuntu/apps/backend-releases
java_root="$HOME/.local/share/rtr-java"
mkdir -p "$releases" "$java_root"

# Node/PM2 come from nvm on this host.
export NVM_DIR="$HOME/.nvm"
if [[ -s "$NVM_DIR/nvm.sh" ]]; then
  set +u
  source "$NVM_DIR/nvm.sh"
  nvm use --silent 24 || true
  set -u
fi

# A Java 21+ runtime: reuse the managed Temurin JRE, otherwise install it under $HOME (no sudo needed).
ensure_java() {
  if [[ -x "$java_root/current/bin/java" ]]; then return 0; fi
  echo "Installing Temurin 21 JRE into $java_root"
  local api="https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jre&os=linux&vendor=eclipse"
  local meta link checksum name tmp
  meta=$(curl -fsSL --retry 3 "$api")
  link=$(python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["binary"]["package"]["link"])' <<<"$meta")
  checksum=$(python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["binary"]["package"]["checksum"])' <<<"$meta")
  name=$(python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["release_name"])' <<<"$meta")
  tmp=$(mktemp -d)
  curl -fsSL --retry 3 -o "$tmp/jre.tar.gz" "$link"
  echo "$checksum  $tmp/jre.tar.gz" | sha256sum -c -
  mkdir -p "$java_root/$name"
  tar -xzf "$tmp/jre.tar.gz" -C "$java_root/$name" --strip-components=1
  rm -rf "$tmp"
  ln -sfn "$java_root/$name" "$java_root/current"
  "$java_root/current/bin/java" -version
}
ensure_java

# Optional: libheif's decoder lets admins upload HEIC/AVIF photos. Never fail a deploy over it.
if ! command -v heif-dec >/dev/null && ! command -v heif-convert >/dev/null && sudo -n true 2>/dev/null; then
  sudo -n apt-get install -y -qq libheif-examples >/dev/null 2>&1 || echo 'libheif-examples not installed; HEIC uploads stay unsupported.' >&2
fi

cd "$app"
git diff --quiet && git diff --cached --quiet || {
  echo 'Tracked server files have local changes; deployment stopped.' >&2
  exit 1
}
previous=$(git rev-parse HEAD)
previous_jar=$(readlink -f "$releases/current.jar" 2>/dev/null || true)
[[ -n "$previous_jar" && -f "$previous_jar" ]] || previous_jar=""
# Remember how the current process was started so a failed deploy can restore it exactly.
pm2 jlist | python3 -c '
import json, sys
apps = [p for p in json.load(sys.stdin) if p.get("name") == "rtr-backend"]
if apps:
    env = apps[0]["pm2_env"]
    app = {"name": "rtr-backend", "script": env.get("pm_exec_path"), "cwd": env.get("pm_cwd"), "args": env.get("args") or [],
           "interpreter": env.get("exec_interpreter") or "none", "node_args": env.get("node_args") or [], "kill_timeout": env.get("kill_timeout") or 1600}
    print(json.dumps({"apps": [app]}))
' > "$releases/previous-process.json" || true
git fetch --no-tags origin "$revision"
git cat-file -e "$revision^{commit}"

health() {
  for attempt in {1..45}; do
    if curl --fail --silent --show-error --max-time 3 http://127.0.0.1:3001/api/health; then
      echo
      return 0
    fi
    sleep 2
  done
  pm2 logs rtr-backend --lines 60 --nostream || true
  return 1
}

start_java() {
  pm2 delete rtr-backend >/dev/null 2>&1 || true
  pm2 start "$app/deployment/run.sh" --name rtr-backend --interpreter bash --cwd "$app" --kill-timeout 10000 --time
}

rollback() {
  status=$?
  trap - ERR
  echo "Deployment failed; restoring $previous" >&2
  set +e
  git switch --detach "$previous"
  if [[ -n "$previous_jar" ]]; then
    ln -sfn "$previous_jar" "$releases/current.jar"
    start_java
  else
    # The previous release was the Node.js API.
    [[ -f package-lock.json ]] && npm ci --omit=dev
    pm2 delete rtr-backend >/dev/null 2>&1
    if [[ -s "$releases/previous-process.json" ]]; then pm2 start "$releases/previous-process.json"
    else pm2 start npm --name rtr-backend -- start; fi
  fi
  health
  pm2 save
  exit "$status"
}
trap rollback ERR

# .env and the persistent knowledge file remain on the server.
git switch --detach "$revision"
install -m 644 "$artifact" "$releases/$revision.jar"
ln -sfn "$releases/$revision.jar" "$releases/current.jar"
start_java
health
pm2 save
trap - ERR

# Keep the five most recent releases for rollback.
ls -1t "$releases"/*.jar 2>/dev/null | grep -v '/current.jar$' | tail -n +6 | xargs -r rm -f
echo "Backend deployed: $revision"
