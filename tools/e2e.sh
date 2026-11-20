#!/usr/bin/env bash
# End to end: the whole compose stack, then checks through the REST API that each path delivered.
#   OPC UA:  plc-ens01 -> gateway -> Mosquitto -> platform
#   S7:      plc-ens02 -> gateway -> Mosquitto -> platform
#   LoRaWAN: lorawan-device -> TTN-shaped webhook -> platform
#   ESP32:   messages shaped like esp32-node's, published with the node's login -> Mosquitto -> platform
# Usage: tools/e2e.sh            (builds the images; E2E_BUILD=0 to use what is there)
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
  echo "creating .env with random passwords"
  rand() { head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9'; }
  {
    echo "DB_PASSWORD=$(rand)"; echo "GRAFANA_DB_PASSWORD=$(rand)"
    echo "MQTT_PLATFORM_PASSWORD=$(rand)"; echo "MQTT_GATEWAY_PASSWORD=$(rand)"; echo "MQTT_ADMIN_PASSWORD=$(rand)"
    echo "PLATFORM_API_KEY=$(rand)$(rand)"; echo "TTN_WEBHOOK_TOKEN=$(rand)"; echo "TTN_APPLICATION_ID=tcc-carlos"
    echo "GRAFANA_ADMIN_PASSWORD=$(rand)"; echo "MQTT_NODE_PASSWORD=$(rand)"
  } > .env
fi
set -a; source .env; set +a

build=(--build)
[[ "${E2E_BUILD:-1}" == "0" ]] && build=()
docker compose up -d "${build[@]}"

echo "== commissioning check: the gateway reads both PLCs once"
for i in $(seq 1 20); do
  if docker compose run --rm -T gateway --check; then break; fi
  [[ $i == 20 ]] && { echo "the gateway could not read the PLCs"; exit 1; }
  sleep 3
done

api() { curl -fsS -H "X-API-Key: $PLATFORM_API_KEY" "http://localhost:8080/api/v1/$1"; }

echo "== waiting for every machine to report"
for i in $(seq 1 60); do
  machines=$(api machines 2>/dev/null || echo '[]')
  ready=$(echo "$machines" | python3 -c '
import json, sys
ms = {m["id"]: m for m in json.load(sys.stdin)}
ok = [i for i in ("ENS-01", "ENS-02", "ENS-03") if i in ms and ms[i]["state"] in ("RUNNING", "IDLE", "FAULT") and not ms[i]["stale"]]
print(len(ok))')
  [[ "$ready" == "3" ]] && break
  [[ $i == 60 ]] && { echo "not every machine reported:"; echo "$machines"; docker compose logs --tail 50 gateway platform lorawan-device; exit 1; }
  sleep 5
done
echo "$machines" | python3 -m json.tool

if [[ -n "${MQTT_NODE_PASSWORD:-}" ]]; then
  echo "== ESP32 node: a start and a telemetry window, with the node's login and site"
  # numbers above anything a previous run used, as the firmware's are above its previous boot's
  seq=$(( $(date +%s) * 1000 ))
  at=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)
  pub() { docker compose exec -T mosquitto mosquitto_pub -u node-maleta-01 -P "$MQTT_NODE_PASSWORD" -q 1 -t "$1" -m "$2"; }
  pub mt/maleta/MAQ-01/state "{\"seq\":$seq,\"gatewayId\":\"node-maleta-01\",\"machineId\":\"MAQ-01\",\"at\":\"$at\",\"state\":\"RUNNING\",\"faultCode\":0,\"previousState\":null,\"previousFaultCode\":null}"
  pub mt/maleta/MAQ-01/telemetry "{\"seq\":$((seq + 1)),\"gatewayId\":\"node-maleta-01\",\"machineId\":\"MAQ-01\",\"at\":\"$at\",\"state\":\"RUNNING\",\"faultCode\":0,\"windowSeconds\":10.0,\"goodCount\":null,\"rejectCount\":null,\"counterBits\":32,\"analogs\":{\"motor-current\":{\"avg\":0.215,\"min\":0.200,\"max\":0.230,\"last\":0.210,\"samples\":10}}}"
  for i in $(seq 1 20); do
    state=$(api machines | python3 -c 'import json, sys; print(next((m["state"] for m in json.load(sys.stdin) if m["id"] == "MAQ-01"), ""))')
    [[ "$state" == "RUNNING" ]] && break
    [[ $i == 20 ]] && { echo "the node's messages did not arrive (state: '$state')"; docker compose logs --tail 50 mosquitto platform; exit 1; }
    sleep 2
  done
  echo "MAQ-01 is $state"
fi

echo "== OEE of the machine read over S7, after a minute of telemetry"
sleep 60
api machines/ENS-02/oee | python3 -c '
import json, sys
o = json.load(sys.stdin)
print(json.dumps(o, indent=2))
assert o["runningSeconds"] + o["idleSeconds"] + o["faultSeconds"] > 30, "no time recorded"
assert o["availability"] is not None, "no availability"
'
echo "end to end OK"
