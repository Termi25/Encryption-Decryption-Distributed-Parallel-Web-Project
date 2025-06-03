FROM ubuntu:22.04

# Install OpenMPI, OpenSSL, and necessary tools
RUN apt-get update && apt-get install -y \
    build-essential \
    openmpi-bin \
    libopenmpi-dev \
    libssl-dev \
    snmpd \ 
    dos2unix \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user with fixed UID/GID 1001
RUN groupadd -g 1001 mpiuser && useradd -m -u 1001 -g 1001 -s /bin/bash mpiuser

WORKDIR /home/mpiuser/app

# Copy hybrid source and build it
COPY mpi-node/hybrid.c ./hybrid.c
RUN mpicc -fopenmp hybrid.c -o hybrid -lcrypto && chmod +x hybrid

# Create shared data directory (volume)
RUN mkdir -p /home/mpiuser/data && chown mpiuser:mpiuser /home/mpiuser/data
COPY mpi-node/config/snmpd.conf /etc/snmp/snmpd.conf

# Copy entrypoint
COPY mpi-node/entrypoint.sh /home/mpiuser/entrypoint.sh
RUN dos2unix /home/mpiuser/entrypoint.sh && chmod +x /home/mpiuser/entrypoint.sh

# Switch to non-root user
USER mpiuser

ENTRYPOINT ["/home/mpiuser/entrypoint.sh"]
