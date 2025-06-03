# Stage 1: Build Java app
FROM eclipse-temurin:21-jdk AS builder

RUN apt-get update && apt-get install -y maven

WORKDIR /app

COPY java-app/pom.xml ./java-app/pom.xml
COPY java-app/src ./java-app/src

RUN mvn -f java-app/pom.xml clean package -DskipTests

# Stage 2: Runtime image
FROM eclipse-temurin:21-jdk

# Install MPI runtime and helper tools
RUN apt-get update && apt-get install -y \
    openmpi-bin libgomp1 dos2unix libssl-dev snmpd snmp iputils-ping \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user with fixed UID/GID 1001
RUN groupadd -r snmp && useradd -r -g snmp snmp \
    && groupadd -g 1001 mpiuser && useradd -m -u 1001 -g 1001 -s /bin/bash mpiuser

# App directory (not shared)
WORKDIR /home/mpiuser/app
COPY --from=builder --chown=mpiuser:mpiuser /app/java-app/target/*jar-with-dependencies.jar ./app.jar

COPY java-app/config/snmpd.conf /etc/snmp/snmpd.conf
RUN mkdir -p /var/lib/snmp && chown mpiuser:mpiuser /var/lib/snmp

# Copy entrypoint
COPY java-app/entrypoint.sh /home/mpiuser/entrypoint.sh
RUN dos2unix /home/mpiuser/entrypoint.sh && chmod +x /home/mpiuser/entrypoint.sh

# Switch to non-root user
USER mpiuser

ENTRYPOINT ["/home/mpiuser/entrypoint.sh"]
