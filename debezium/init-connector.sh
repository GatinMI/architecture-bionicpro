#!/bin/sh
set -eu

CONNECT_URL="${CONNECT_URL:-http://kafka-connect:8083}"
CONFIG_FILE="${CONFIG_FILE:-/config/crm-connector.json}"
CONNECTOR_NAME="bionicpro-crm"

echo "Waiting for Kafka Connect at $CONNECT_URL ..."
i=0
until curl -sf "$CONNECT_URL/connectors" >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 150 ]; then
    echo "Kafka Connect did not come up in time" >&2
    exit 1
  fi
  sleep 2
done

echo "Kafka Connect is up. Registering connector '$CONNECTOR_NAME' ..."
code=$(curl -s -o /tmp/conn_resp.json -w '%{http_code}' -X POST "$CONNECT_URL/connectors" \
  -H 'Content-Type: application/json' \
  --data @"$CONFIG_FILE")
if [ "$code" = "201" ]; then
  echo "Connector '$CONNECTOR_NAME' registered."
elif [ "$code" = "409" ]; then
  echo "Connector '$CONNECTOR_NAME' already registered."
else
  echo "Failed to register connector (HTTP $code):" >&2
  cat /tmp/conn_resp.json >&2
  echo >&2
  exit 1
fi
