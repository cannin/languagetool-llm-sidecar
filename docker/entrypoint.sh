#!/bin/sh
set -eu

sidecar_config="${LT_SIDECAR_CONFIG:-/config/sidecar.properties}"
server_config="${LT_SERVER_CONFIG:-/config/languagetool.properties}"
http_port="${LT_HTTP_PORT:-8081}"
allow_origin="${LT_ALLOW_ORIGIN:-*}"

for config_file in "$sidecar_config" "$server_config"; do
  if [ ! -r "$config_file" ]; then
    echo "Required configuration file is not readable: $config_file" >&2
    exit 1
  fi
done

mkdir -p /data/cache

languagetool_home=/opt/languagetool
if [ ! -r "$languagetool_home/languagetool-server.jar" ]; then
  for candidate in /opt/languagetool/LanguageTool-*; do
    if [ -r "$candidate/languagetool-server.jar" ]; then
      languagetool_home=$candidate
      break
    fi
  done
fi
if [ ! -r "$languagetool_home/languagetool-server.jar" ]; then
  echo "LanguageTool server JAR was not found under /opt/languagetool." >&2
  exit 1
fi

java -jar /opt/sidecar/languagetool-llm-sidecar.jar \
  --config "$sidecar_config" &
sidecar_pid=$!

(
  cd "$languagetool_home"
  exec java -jar languagetool-server.jar \
    --config "$server_config" \
    --port "$http_port" \
    --public \
    --allow-origin "$allow_origin"
) &
server_pid=$!

shutdown() {
  trap - TERM INT
  kill -TERM "$server_pid" "$sidecar_pid" 2>/dev/null || true
  wait "$server_pid" 2>/dev/null || true
  wait "$sidecar_pid" 2>/dev/null || true
  exit 0
}

trap shutdown TERM INT

while kill -0 "$sidecar_pid" 2>/dev/null \
    && kill -0 "$server_pid" 2>/dev/null; do
  sleep 2
done

echo "A managed Java process exited; stopping the container." >&2
kill -TERM "$server_pid" "$sidecar_pid" 2>/dev/null || true
wait "$server_pid" 2>/dev/null || true
wait "$sidecar_pid" 2>/dev/null || true
exit 1
