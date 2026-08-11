'use strict';

// Relay server tests (node --test). Exercises the real server process over
// loopback WebSockets: registration + presence fan-out, byte-identical
// (E2E-blind) routing, store-and-forward for offline devices, pair-aware
// rejection of un-granted senders, and the /health endpoint.
// Run: cd relay && npm install && npm test

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const { spawn } = require('node:child_process');
const WebSocket = require('ws');
const { T, build, parse } = require('../protocol.js');

let proc;
let port;
let url;

function waitForHealth(p, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve) => {
    const attempt = () => {
      const req = http.get(`http://127.0.0.1:${p}/health`, (res) => {
        res.resume();
        resolve(res.statusCode === 200);
      });
      req.on('error', () => {
        if (Date.now() > deadline) resolve(false);
        else setTimeout(attempt, 100);
      });
    };
    attempt();
  });
}

before(async () => {
  port = 20000 + Math.floor(Math.random() * 20000);
  proc = spawn(process.execPath, ['server.js', '--port', String(port)], {
    cwd: __dirname + '/..',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const ok = await waitForHealth(port, 10_000);
  assert.ok(ok, 'relay server did not come up');
  url = `ws://127.0.0.1:${port}`;
});

after(() => {
  try {
    proc.kill();
  } catch {
    /* already gone */
  }
});

function connect() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
  });
}

function reg(ws, deviceId) {
  ws.send(build(T.REG, deviceId));
}

function grant(ws, grantee, grantor) {
  ws.send(build(T.GRANT, grantee, Buffer.from(grantor)));
}

function sendFrame(ws, to, payload) {
  ws.send(build(T.SEND, to, payload));
}

/** Collect messages of a given type into an array until [count] of them arrive. */
function collect(ws, type, count, timeoutMs = 4000) {
  return new Promise((resolve, reject) => {
    const out = [];
    const timer = setTimeout(() => reject(new Error(`timeout waiting for ${count} x ${type}`)), timeoutMs);
    const handler = (data, isBinary) => {
      if (!isBinary) return;
      const env = parse(data);
      if (!env || env.type !== type) return;
      out.push(env);
      if (out.length >= count) {
        clearTimeout(timer);
        ws.off('message', handler);
        resolve(out);
      }
    };
    ws.on('message', handler);
  });
}

test('registers and announces presence to granting peers', async () => {
  const a = await connect();
  const b = await connect();
  try {
    reg(b, 'dev-bbbbbbbbbb');
    grant(b, 'dev-bbbbbbbbbb', 'dev-aaaaaaaaaa'); // B allows A to send to it
    const presence = collect(b, T.ONLINE, 1);
    reg(a, 'dev-aaaaaaaaaa'); // A comes online -> B is notified
    const [evt] = await presence;
    assert.strictEqual(evt.deviceId, 'dev-aaaaaaaaaa');
  } finally {
    a.close();
    b.close();
  }
});

test('routes frames byte-identically between granted peers (E2E-blind)', async () => {
  const a = await connect();
  const b = await connect();
  try {
    reg(a, 'dev-aaaaaaaaaa');
    reg(b, 'dev-bbbbbbbbbb');
    grant(a, 'dev-aaaaaaaaaa', 'dev-bbbbbbbbbb');
    grant(b, 'dev-bbbbbbbbbb', 'dev-aaaaaaaaaa');

    // Grants apply asynchronously server-side — a SEND sent in the same tick can
    // arrive before b's GRANT is processed. Give the loopback a settle tick.
    await new Promise((r) => setTimeout(r, 50));

    // Opaque payload containing the full byte range — the relay must never touch it.
    const payload = Buffer.from([0x00, 0x01, 0x7f, 0x80, 0xff, 0x49, 0x53, 0x00, 0x42]);
    const forwarded = collect(b, T.FORWARD, 1);
    sendFrame(a, 'dev-bbbbbbbbbb', payload);
    const [evt] = await forwarded;
    assert.strictEqual(evt.deviceId, 'dev-aaaaaaaaaa', 'FORWARD carries the sender id');
    assert.ok(payload.equals(evt.payload), 'payload must arrive byte-identical (relay is blind)');
  } finally {
    a.close();
    b.close();
  }
});

test('queues for offline peers and flushes on registration', async () => {
  const a = await connect();
  // B registers and grants A, then goes offline — self-contained (no reliance
  // on other tests' leftover grants).
  const b0 = await connect();
  reg(b0, 'dev-bbbbbbbbbb');
  grant(b0, 'dev-bbbbbbbbbb', 'dev-aaaaaaaaaa');
  b0.close();
  await new Promise((r) => setTimeout(r, 50));
  try {
    reg(a, 'dev-aaaaaaaaaa');
    grant(a, 'dev-aaaaaaaaaa', 'dev-bbbbbbbbbb'); // A allows B (offline) to send to it

    const queued = collect(a, T.QUEUED, 1);
    sendFrame(a, 'dev-bbbbbbbbbb', Buffer.from('hello-when-you-are-up'));
    const [q] = await queued;
    assert.strictEqual(q.deviceId, 'dev-bbbbbbbbbb');

    const b = await connect();
    try {
      const forwarded = collect(b, T.FORWARD, 1);
      reg(b, 'dev-bbbbbbbbbb');
      const [evt] = await forwarded;
      assert.strictEqual(evt.deviceId, 'dev-aaaaaaaaaa');
      assert.strictEqual(evt.payload.toString('utf8'), 'hello-when-you-are-up');
    } finally {
      b.close();
    }
  } finally {
    a.close();
  }
});

test('drops un-granted traffic (pair-aware routing)', async () => {
  const a = await connect();
  const c = await connect();
  try {
    reg(a, 'dev-aaaaaaaaaa');
    reg(c, 'dev-cccccccccc');
    // A never granted C -> C's frame must be rejected, and A must see nothing.
    const error = collect(c, T.ERROR, 1);
    const nothingArrives = collect(a, T.FORWARD, 1, 800).then(
      () => 'unexpected-forward',
      () => 'no-forward'
    );
    sendFrame(c, 'dev-aaaaaaaaaa', Buffer.from('sneaky'));
    const [err] = await error;
    assert.strictEqual(err.payload.toString('utf8'), 'not-granted');
    assert.strictEqual(await nothingArrives, 'no-forward');
  } finally {
    a.close();
    c.close();
  }
});

test('re-registration revokes grants (unpair propagates via the REG reset)', async () => {
  const a = await connect();
  const b = await connect();
  try {
    reg(a, 'dev-aaaaaaaaaa');
    grant(a, 'dev-aaaaaaaaaa', 'dev-bbbbbbbbbb'); // A allows B to send to it
    reg(b, 'dev-bbbbbbbbbb');

    // B -> A flows while granted.
    const okForward = collect(a, T.FORWARD, 1);
    sendFrame(b, 'dev-aaaaaaaaaa', Buffer.from('while-granted'));
    const [fwd] = await okForward;
    assert.strictEqual(fwd.payload.toString('utf8'), 'while-granted');

    // A un-pairs B and re-registers (the client re-grants only current peers).
    reg(a, 'dev-aaaaaaaaaa');

    // B -> A must now be rejected and A must see nothing.
    const error = collect(b, T.ERROR, 1);
    const nothingArrives = collect(a, T.FORWARD, 1, 800).then(
      () => 'unexpected-forward',
      () => 'no-forward'
    );
    sendFrame(b, 'dev-aaaaaaaaaa', Buffer.from('after-revoke'));
    const [err] = await error;
    assert.strictEqual(err.payload.toString('utf8'), 'not-granted');
    assert.strictEqual(await nothingArrives, 'no-forward');
  } finally {
    a.close();
    b.close();
  }
});

test('health endpoint reports server state', async () => {
  const res = await new Promise((resolve, reject) => {
    http.get(`http://127.0.0.1:${port}/health`, (r) => {
      let body = '';
      r.on('data', (d) => (body += d));
      r.on('end', () => resolve({ status: r.statusCode, body }));
    }).on('error', reject);
  });
  assert.strictEqual(res.status, 200);
  const state = JSON.parse(res.body);
  assert.strictEqual(state.ok, true);
  assert.ok(typeof state.peers === 'number');
  assert.ok(typeof state.queued === 'number');
});
