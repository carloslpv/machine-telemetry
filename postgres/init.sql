-- Runs once, when the database volume is created (see init.sh). Grafana reads with its own role that can only
-- SELECT; the platform's tables are created later by Flyway, as the telemetry user, hence the default privileges.
CREATE ROLE grafana LOGIN PASSWORD :'grafana_password';
GRANT CONNECT ON DATABASE telemetry TO grafana;
GRANT USAGE ON SCHEMA public TO grafana;
ALTER DEFAULT PRIVILEGES FOR ROLE telemetry IN SCHEMA public GRANT SELECT ON TABLES TO grafana;
