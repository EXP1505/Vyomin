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
# can't be the thing that pushes total memory over the container limit. Originally capped at 128m,
# which was fine when this was set but got outgrown as the app gained more code paths (the ticker
# search/on-demand-price-fetch/event-study-bootstrap machinery) - the first request to exercise
# several brand-new classes all at once threw a genuine OutOfMemoryError: Metaspace, confirmed by
# logs showing non-heap pinned at the 128m-ish ceiling while heap (82MB used) and direct (0MB)
# both had plenty of room to spare - so raised to 200m, with MaxRAMPercentage correspondingly
# trimmed down to keep the combined worst-case ceiling from creeping toward the container's
# 512MB limit, even though real-world heap usage has stayed well under its own ceiling for weeks.
# Xss: default per-thread stack is 1MB; with Tomcat's thread pool plus scheduler/driver threads,
# smaller stacks measurably reduce native memory reserved just for thread stacks.
# MaxDirectMemorySize: without an explicit cap this defaults to the max heap size, meaning NIO
# direct buffers (which Netty - underneath the Neo4j driver's TLS connections - allocates
# off-heap, one buffer set per live connection) could balloon by another 322MB on top of
# everything MemoryMXBean already reports, completely invisible to heap/non-heap logging. That
# invisible-to-the-JVM's-own-accounting memory is what was actually pushing the container over its
# 512MB limit even while logged heap+non-heap sat around 320MB. Capping it forces an explicit,
# visible OutOfMemoryError instead of a silent container-level kill if it's ever exceeded.
# Netty (used internally by the Neo4j driver for its TLS connections) allocates its own pooled
# off-heap memory via sun.misc.Unsafe, NOT via java.nio's DirectByteBuffer - which means it is
# completely invisible to BufferPoolMXBean and everything else the JVM exposes for monitoring.
# Confirmed by logs: heap+non-heap+direct topped out around 220MB combined right up until Render's
# own OOM kill at >512MB - a ~290MB gap accounted for by nothing the JVM can report on. Netty's
# default pooled allocator sizes its arenas off detected CPU count (2x availableProcessors, 16MB
# chunks each), which can massively overshoot on a container with a fractional (0.1) CPU quota.
# Forcing the unpooled allocator and hard-capping Netty's own direct memory tracker removes that
# entire invisible-memory blind spot.
ENTRYPOINT ["java", "-Djava.net.preferIPv4Stack=true", "-XX:MaxRAMPercentage=55.0", "-XX:MaxMetaspaceSize=200m", "-XX:MaxDirectMemorySize=48m", "-Xss512k", "-Dio.netty.allocator.type=unpooled", "-Dio.netty.maxDirectMemory=33554432", "-jar", "app.jar"]
