#!/usr/bin/env bash
# Container health check without curl or wget (the JRE image does not need them).
# Calls the Actuator readiness endpoint on the management port and succeeds only on HTTP 200.
#
# Usage: healthcheck <management-port> [path]
#   healthcheck 9888
#   healthcheck 9888 /actuator/health/liveness
set -euo pipefail

port="${1:?usage: healthcheck <management-port> [path]}"
path="${2:-/actuator/health/readiness}"

exec 3<>"/dev/tcp/127.0.0.1/${port}"
printf 'GET %s HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' "${path}" >&3
# First response line looks like "HTTP/1.1 200 OK"; the second field is the status code.
read -r _protocol status_code _reason <&3
status_code="${status_code%$'\r'}"   # servers may omit the reason phrase: "HTTP/1.1 200\r"
exec 3>&-

[ "${status_code}" = "200" ]
