# Newax Aegis sync relay — E2E-blind rendezvous + ciphertext relay (docs/SYNC_DESIGN.md §10).
# Deployable on any container host (Fly/Render/Railway/K8s or a plain VPS):
#   docker build -t aegis-relay relay/
#   docker run -p 8080:8080 -e PORT=8080 aegis-relay
# Env: PORT (8080), HOST (0.0.0.0), QUEUE_TTL_MS, MAX_QUEUED_FRAMES, MAX_FRAME_BYTES.
# The relay is stateless — scale horizontally behind a load balancer; no
# affinity or persistence needed. Terminate TLS at the proxy (wss://) and
# forward to this container over plain ws://.
FROM node:20-alpine

WORKDIR /app

# Dependencies first (layer-cached): package.json + lockfile, production deps only.
COPY package*.json ./
RUN npm ci --omit=dev

# The service itself.
COPY server.js protocol.js ./

ENV NODE_ENV=production \
    HOST=0.0.0.0 \
    PORT=8080

EXPOSE 8080

# /health reports { ok, peers, queued, grants } — the readiness probe.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server.js"]
