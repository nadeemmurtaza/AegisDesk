# Newax Aegis sync relay

The internet leg of the Newax Aegis 4-device sync mesh (docs/SYNC_DESIGN.md §10): a
stateless, **E2E-blind** WebSocket rendezvous + ciphertext relay. It sees only
device ids, routing grants, and opaque frames — never keys, never plaintext,
never parses payload contents. Pair-aware: a frame is forwarded only from a
device the recipient has GRANTed (the relay-side mirror of mesh pairing).

Clients: Android `RelayTransport` (OkHttp) and the JVM desktops (`RelayTransport`
over `java.net.http`), both behind the shared `FrameChannel` seam. The wire
protocol lives in `protocol.js` (mirrored in `RelayTransport.kt` — keep the
shapes in lockstep).

## Run locally

```bash
cd relay
npm install
npm test          # node --test suite (6 tests)
node server.js    # ws://0.0.0.0:8080
```

## Deploy (container)

```bash
docker build -t aegis-relay relay/
docker run -p 8080:8080 aegis-relay
```

Or push the image to your registry and point Fly/Render/Railway/K8s at it —
the Dockerfile exposes the health probe (`/health` → `{ ok, peers, queued,
grants }`) and binds `0.0.0.0`. The relay is stateless, so it scales
horizontally with no persistence or affinity.

## Configuration

| Env | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | listen port |
| `HOST` | `0.0.0.0` | bind address |
| `QUEUE_TTL_MS` | `86400000` (24 h) | how long frames for offline devices are held |
| `MAX_QUEUED_FRAMES` | `1000` | per-device offline queue cap |
| `MAX_FRAME_BYTES` | `16777216` (16 MiB) | per-frame size cap (design §10) |

Terminate TLS at the proxy/load balancer (`wss://` outward, `ws://` inward to
this container) — the relay itself speaks plain WebSocket by design.

## Pointing devices at it

In the Android Sync screen's *Internet relay* field (or the desktop CLI
`sync peer` flow / relay URL setting), enter the public URL — e.g.
`wss://relay.example.com` — and pair as usual. Pairing journals a `peer_trust`
record that the relay's REG/GRANT flow turns into routing grants; unpairing
propagates the revocation (the next REG resets the grant allowlist, test
`re-registration revokes grants`).
