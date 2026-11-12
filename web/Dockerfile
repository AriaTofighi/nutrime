# Build context is the repository root, so the web app can ship the same offline USDA food database
# as the Android app. Normally built by server/docker-compose.yml (service "web").

# ---- export the bundled food database to JSON ----
FROM python:3.12-alpine AS foods
COPY app/src/main/assets/foods.db web/scripts/export_foods.py /tmp/
RUN python3 /tmp/export_foods.py /tmp/foods.db /tmp/foods.json

# ---- build the SPA ----
FROM node:22-alpine AS build
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
COPY --from=foods /tmp/foods.json public/foods.json
COPY --from=foods /tmp/foods-search.json public/foods-search.json
RUN npm run build && gzip -k -9 dist/foods.json

# ---- serve ----
FROM nginx:1.27-alpine
ENV NUTRIME_API_URL=http://api:8080 \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    NGINX_ENVSUBST_FILTER="^(NUTRIME_API_URL|NGINX_LOCAL_RESOLVERS)$"
COPY web/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /web/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
