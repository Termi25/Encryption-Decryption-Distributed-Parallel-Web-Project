# Stage 1: Build the app using Maven
FROM maven:3.9.4-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom.xml and source code
COPY pom.xml .
COPY src ./src

# Build the app (skip tests for faster build, remove -DskipTests to run tests)
RUN mvn clean package -DskipTests

FROM alpine:latest

RUN apk add --no-cache openjdk21 curl iputils

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk
ENV PATH="$JAVA_HOME/bin:$PATH"

WORKDIR /app

RUN mkdir -p /app/fileInput
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN chown -R appuser:appgroup /app/fileInput

COPY --from=builder /app/target/C01_BackEnd_Javalin-1.0-SNAPSHOT.jar /app/app.jar

USER appuser

EXPOSE 7000

CMD ["java", "-jar", "/app/app.jar"]
