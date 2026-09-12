# Stage 1: Build the application
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app
# Copy the pom.xml and download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B
# Copy the source code and build
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Run the application
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/core-api-0.0.1-SNAPSHOT.jar app.jar
# The .env file must be injected by docker-compose or copied into the container. 
# We'll rely on docker-compose env_file or copying it if present.
# However, spring-dotenv looks for .env in the root directory. 
# The best practice is to pass it via compose, but we'll let spring handle it.
EXPOSE 8080
# preferIPv4Stack: some hosts (e.g. Render) have no outbound IPv6 route. Supabase/Upstash
# hostnames resolve to both A and AAAA records, and the JVM's default IPv6-first resolution
# order picks the unreachable AAAA address, failing with "Network unreachable" before ever
# trying the IPv4 one. Forcing IPv4-only skips straight to the address that actually works.
# MaxRAMPercentage: the JVM's default container-aware heap sizing is a conservative ~25% of
# available memory, which on a 512MB instance leaves the heap at roughly 128MB - too tight once
# Spring, Hibernate, the Neo4j driver, and a handful of background schedulers are all resident,
# and it was hitting a genuine OutOfMemoryError under normal load. This is a single-process
# container with nothing else competing for memory, so let the heap use most of what's available -
# but not so much that heap + metaspace + thread stacks + native buffers (Neo4j driver, Tomcat,
# WebSocket) can exceed the container's hard 512MB limit, which gets the whole process silently
# SIGKILL'd by Render with no exception ever logged (looks like a random crash/restart, not an
# app error). 65% leaves real headroom for everything outside the heap.
# MaxMetaspaceSize: without a cap, metaspace can grow unbounded as classes load - bound it so it
# can't be the thing that pushes total memory over the container limit.
# Xss: default per-thread stack is 1MB; with Tomcat's thread pool plus scheduler/driver threads,
# smaller stacks measurably reduce native memory reserved just for thread stacks.
ENTRYPOINT ["java", "-Djava.net.preferIPv4Stack=true", "-XX:MaxRAMPercentage=65.0", "-XX:MaxMetaspaceSize=128m", "-Xss512k", "-jar", "app.jar"]
