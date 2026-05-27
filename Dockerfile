# =========================================================================
# Stage 1: Dependency Caching and Application Compilation
# =========================================================================
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy only the project file to resolve and cache maven dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy application source code and compile
COPY src ./src
RUN mvn clean package -DskipTests -B

# =========================================================================
# Stage 2: Minimal Lightweight Production Runtime
# =========================================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create a non-root group and user for security compliance
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy compile package from compile stage
COPY --from=build --chown=appuser:appgroup /app/target/*.jar app.jar

# Configure JVM flags for optimized container memory utilization
ENV JAVA_OPTS="-XX:+UseG1GC -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
