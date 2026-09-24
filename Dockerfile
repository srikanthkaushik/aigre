# syntax=docker/dockerfile:1
#
# Three-stage build: Angular frontend -> Spring Boot jar (frontend baked into
# static/) -> slim runtime. Builds the frontend inside Docker rather than
# relying on the manual `ng build` + copy step from docs/RUNNING.md, so the
# image is reproducible from a bare checkout. See PROJECT.md "Two-machine
# split" for why this exists (Unraid deployment, no GPU on that box -- Ollama
# stays on the GPU machine and is reached over the LAN via OLLAMA_BASE_URL).

# ---- Stage 1: build the Angular frontend ----
FROM node:22-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npx ng build

# ---- Stage 2: build the Spring Boot jar, with the frontend baked into static/ ----
# No mvnw wrapper in this repo, so a Maven-preinstalled image is used directly.
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
# test-data/ is read from the filesystem at runtime (rag.ingest.corpus-path),
# not the classpath -- CorpusIngestionService/DepartmentOnboardingService
# resolve it via Path.of(...) against the JVM's working directory. Must ship
# in the final image or /ingest/reset silently no-ops.
COPY test-data ./test-data
COPY --from=frontend-build /frontend/dist/frontend/browser/ ./src/main/resources/static/
RUN mvn -B -DskipTests package

# ---- Stage 3: slim runtime ----
# Alpine is safe here: the dependency tree (LangChain4j, LangGraph4j, Spring AI,
# Tika, the Postgres driver, jjwt) is pure-JVM, no native/glibc requirement.
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=backend-build /build/target/aigre-0.1.0-SNAPSHOT.jar ./app.jar
COPY --from=backend-build /build/test-data ./test-data
EXPOSE 8085
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=5 \
  CMD curl -sf http://localhost:8085/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
