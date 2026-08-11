#!/usr/bin/env node
'use strict';

// Aegis sync relay — E2E-blind rendezvous + ciphertext relay.
// (docs/SYNC_DESIGN.md §10: "No plaintext ever transits it.")
//
// What the relay sees: device ids, routing grants, and opaque frame payloads.
// It never parses payload contents, never stores anything durable, and never
// sees keys or plaintext. Routing is pair-aware: a frame is forwarded only
// from a device the recipient has GRANTed ("I allow P to send to me") — the
// relay-side mirror of mesh pairing. Frames for offline devices are queued
// in memory (bounded, TTL) and flushed when the device registers.
//
// Deployable as a bare Node service (repo hosting image is Node-only):
//   cd relay && npm install && node server.js
// Env / CLI: PORT (8080), HOST (0.0.0.0), QUEUE_TTL_MS (24h),
//            MAX_QUEUED_FRAMES (1000), MAX_FRAME_BYTES (16 MiB — design §10).

const http = require('http');
const { WebSocketServer } = require('ws');
const { T, build, parse } = require('./protocol.js');

function arg(name, def) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : def;
}

const PORT = parseInt(process.env.PORT || arg('--port', '8080'), 10);
const HOST = process.env.HOST || '0.0.0.0';
const QUEUE_TTL_MS = parseInt(process.env.QUEUE_TTL_MS || String(24 * 3600 * 1000), 10);
const MAX_QUEUED = parseInt(process.env.MAX_QUEUED_FRAMES || '1000', 10);
const MAX_FRAME = parseInt(process.env.MAX_FRAME_BYTES || String(16 * 1024 * 1024), 10);
const DEVICE_ID_RE = /^dev-[0-9a-f]{10}$/;

// ── state (in-memory by design — the relay is stateless across restarts) ────
const sockets = new Map(); // deviceId -> ws
const queues = new Map(); // deviceId -> [{ from, payload, at }]
const grants = new Map(); // deviceId -> Set(peer ids allowed to send to me)

function granted(to, from) {
  const set = grants.get(to);
  return !!set && set.has(from);
}

/** Devices that allow [deviceId] to send to them — the presence fan-out set. */
function peersWhoAllow(deviceId) {
  const out = [];
  for (const [grantee, set] of grants) if (set.has(deviceId)) out.push(grantee);
  return out;
}

function push(grantee, type, peerId, payload) {
  const ws = sockets.get(grantee);
  if (ws && ws.readyState === 1) ws.send(build(type, peerId, payload)); // 1 = OPEN
}

function announce(deviceId) {
  for (const grantee of peersWhoAllow(deviceId)) push(grantee, T.ONLINE, deviceId);
}

function flushQueue(deviceId) {
  const q = queues.get(deviceId);
  if (!q) return;
  queues.delete(deviceId);
  for (const { from, payload } of q) {
    push(deviceId, T.FORWARD, from, payload);
  }
}

function onMessage(ws, msg) {
  const env = parse(msg);
  if (!env) return;

  switch (env.type) {
    case T.REG: {
      if (!DEVICE_ID_RE.test(env.deviceId)) return;
      const prev = sockets.get(env.deviceId);
      if (prev && prev !== ws) prev.close(4001, 'replaced');
      sockets.set(env.deviceId, ws);
      ws.deviceId = env.deviceId;
      announce(env.deviceId);
      flushQueue(env.deviceId);
      break;
    }

    case T.GRANT: {
      const grantee = env.deviceId;
      const grantor = env.payload.toString('utf8');
      if (!DEVICE_ID_RE.test(grantee) || !DEVICE_ID_RE.test(grantor)) return;
      const set = grants.get(grantee) || new Set();
      set.add(grantor);
      grants.set(grantee, set);
      break;
    }

    case T.SEND: {
      const from = ws.deviceId;
      const to = env.deviceId;
      if (!from || !DEVICE_ID_RE.test(to)) return;
      if (!granted(to, from)) {
        push(from, T.ERROR, to, Buffer.from('not-granted'));
        return;
      }
      if (env.payload.length > MAX_FRAME) {
        push(from, T.ERROR, to, Buffer.from('frame-too-large'));
        return;
      }
      const target = sockets.get(to);
      if (target && target.readyState === 1) {
        target.send(build(T.FORWARD, from, env.payload));
      } else {
        const q = queues.get(to) || [];
        if (q.length < MAX_QUEUED) {
          q.push({ from, payload: env.payload, at: Date.now() });
          queues.set(to, q);
          push(from, T.QUEUED, to);
        }
      }
      break;
    }

    default:
      break;
  }
}

// ── sweep expired queue entries ──────────────────────────────────────────────
setInterval(() => {
  const now = Date.now();
  for (const [deviceId, q] of queues) {
    const alive = q.filter((e) => now - e.at < QUEUE_TTL_MS);
    if (alive.length === 0) queues.delete(deviceId);
    else if (alive.length !== q.length) queues.set(deviceId, alive);
  }
}, 60_000).unref();

// ── http server (health) + websocket upgrade ────────────────────────────────
const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, peers: sockets.size, queued: queues.size, grants: grants.size }));
  } else {
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('not found');
  }
});

const wss = new WebSocketServer({ server, maxPayload: MAX_FRAME + 4096, perMessageDeflate: false });

wss.on('connection', (ws) => {
  ws.on('message', (data, isBinary) => {
    if (isBinary) onMessage(ws, data);
  });
  ws.on('close', () => {
    if (ws.deviceId && sockets.get(ws.deviceId) === ws) sockets.delete(ws.deviceId);
  });
  ws.on('error', () => {});
});

server.listen(PORT, HOST, () => {
  console.log(`aegis relay listening on ws://${HOST}:${PORT} (blind: never sees plaintext)`);
});

function shutdown() {
  try {
    wss.close();
    server.close();
  } finally {
    process.exit(0);
  }
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
