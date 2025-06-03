#!/bin/bash
echo "[mpi-node] MPI node container started."

echo "[mpi-node] Starting SNMP daemon..."
# Start snmpd with the specific config file, listening on all interfaces
/usr/sbin/snmpd -LS4d -Lf /dev/null -u snmp -g snmp -p /var/run/snmpd.pid -c /etc/snmp/snmpd.conf

echo "[mpi-node] Copying hybrid binary to shared data directory..."
cp /home/mpiuser/app/hybrid /home/mpiuser/data/hybrid
chmod +x /home/mpiuser/data/hybrid

echo "[mpi-node] Shared data directory contents:"
ls -l /home/mpiuser/data || echo "(empty)"

# Keep container alive
while true; do sleep 3600; done