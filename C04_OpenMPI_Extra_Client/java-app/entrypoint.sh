#!/bin/bash
set -e

echo "[entrypoint] Java container started."
echo "[entrypoint] Data directory contents:"
ls -l /home/mpiuser/data || echo "(empty)"

echo "[entrypoint] Starting SNMP daemon on port 16161..."
# /usr/sbin/snmpd -LS4d -Lf /dev/null -c /etc/snmp/snmpd.conf -p /var/run/snmpd.pid &

echo "[entrypoint] Starting Java application..."
exec java -jar /home/mpiuser/app/app.jar
