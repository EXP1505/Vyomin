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
ENTRYPOINT ["java", "-Djava.net.preferIPv4Stack=true", "-jar", "app.jar"]
