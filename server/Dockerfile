# Build context is the repository root: the server compiles the nutrition code shared with the Android
# app (shared/) and ships the app's bundled USDA food database for the AI assistant.
# Normally built by server/docker-compose.yml (service "api").

# ---- build ----
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src/server
COPY server/settings.gradle.kts server/build.gradle.kts ./
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true
COPY shared /src/shared
COPY server/src ./src
RUN gradle --no-daemon installDist -x test

# ---- run ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 nutrime
WORKDIR /app
COPY --from=build /src/server/build/install/nutrime-server /app
COPY app/src/main/assets/foods.db /app/foods.db
COPY data/foods /app/curated-foods
USER nutrime
ENV PORT=8080 \
    FOODS_DB_PATH=/app/foods.db
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT} && printf 'GET /api/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]
ENTRYPOINT ["/app/bin/nutrime-server"]
