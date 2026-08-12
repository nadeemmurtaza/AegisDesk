'use strict';

// Shared envelope codec for the Newax Aegis sync relay (docs/SYNC_DESIGN.md §10).
// Every WebSocket message is one binary envelope:
//
//   [type:1][deviceId:utf8][0x00][payload]
//
// The deviceId field is the *peer identity* for the routing layer: the
// recipient for client->server SEND, the sender for server->client FORWARD.
// Payloads are opaque to the relay — it never parses past the length limit.
// Mirrored in the JVM client (RelayTransport.kt) — keep the shapes in lockstep.

const T = {
  REG: 0x52, // C->S  register (deviceId = me)
  GRANT: 0x47, // C->S  "I allow payload (a deviceId) to send to me"
  SEND: 0x44, // C->S  deliver payload to deviceId
  ONLINE: 0x4f, // S->C  peer deviceId came online (only to peers who granted it)
  FORWARD: 0x46, // S->C  payload from deviceId
  QUEUED: 0x51, // S->C  payload queued (recipient offline)
  ERROR: 0x45, // S->C  server-level rejection (e.g. not-granted)
};

function build(type, deviceId, payload) {
  const id = Buffer.from(deviceId, 'utf8');
  return Buffer.concat([Buffer.from([type]), id, Buffer.from([0]), payload || Buffer.alloc(0)]);
}

function parse(msg) {
  if (!Buffer.isBuffer(msg) || msg.length < 2) return null;
  const nul = msg.indexOf(0, 1);
  if (nul < 0) return null;
  return {
    type: msg[0],
    deviceId: msg.subarray(1, nul).toString('utf8'),
    payload: msg.subarray(nul + 1),
  };
}

module.exports = { T, build, parse };
