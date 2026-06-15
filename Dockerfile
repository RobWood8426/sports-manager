# --- build stage ---
FROM clojure:temurin-21-tools-deps AS build
WORKDIR /app

# Cache deps separately from source
COPY deps.edn build.clj ./
RUN clojure -P -M:run && clojure -P -T:build

# Copy source and build uberjar
COPY . .
RUN clojure -T:build uber

# --- runtime stage ---
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd -r app && useradd -r -g app app

COPY --from=build /app/target/sports-manager.jar ./app.jar
COPY docker-entrypoint.sh ./docker-entrypoint.sh

RUN chmod +x docker-entrypoint.sh && \
    mkdir -p data/xtdb && \
    chown -R app:app /app

USER app

EXPOSE 8080

ENTRYPOINT ["./docker-entrypoint.sh"]
