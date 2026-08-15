FROM node:22-alpine

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY src ./src
COPY --chown=node:node storage /data

ENV TFO_STORAGE_HOST=0.0.0.0 \
    TFO_STORAGE_PORT=8080 \
    TFO_STORAGE_ROOT=/data

VOLUME ["/data"]
EXPOSE 8080

USER node

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1

CMD ["node", "src/server.mjs"]
