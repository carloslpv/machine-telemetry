#!/bin/sh
# Passes the Grafana password from the environment to init.sql as a psql variable, properly quoted.
set -e
psql -v ON_ERROR_STOP=1 -v grafana_password="$GRAFANA_DB_PASSWORD" \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f /docker-entrypoint-initdb.d/init.sql.in
