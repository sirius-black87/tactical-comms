'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const WebSocket = require('ws');
const { createServer } = require('../src/server');

test('self-signed TLS: stable certificate, fingerprint matches what clients see', async () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tc-tls-'));
  let app = createServer({ port: 0, accessKey: 'k', dataDir, log: () => {}, tlsSelfSigned: true });
  const port = await app.listen();
  assert.match(app.fingerprint, /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/);

  const ws = new WebSocket(`wss://127.0.0.1:${port}/ws`, { rejectUnauthorized: false });
  await new Promise((res, rej) => { ws.on('open', res); ws.on('error', rej); });
  assert.strictEqual(ws._socket.getPeerCertificate().fingerprint256, app.fingerprint);
  ws.send(JSON.stringify({ t: 'hello', uid: 'u1', callsign: 'One', key: 'k' }));
  const welcome = JSON.parse(await new Promise((r) => ws.once('message', (d) => r(d.toString()))));
  assert.strictEqual(welcome.t, 'welcome');
  ws.close();

  // a CA-validating client must refuse the self-signed certificate
  await assert.rejects(new Promise((res, rej) => {
    const strict = new WebSocket(`wss://127.0.0.1:${port}/ws`);
    strict.on('open', res); strict.on('error', rej);
  }));

  // restart keeps the same certificate (otherwise every phone would have to re-pin)
  const first = app.fingerprint;
  await app.close();
  app = createServer({ port: 0, accessKey: 'k', dataDir, log: () => {}, tlsSelfSigned: true });
  assert.strictEqual(app.fingerprint, first);
  await app.close();
  fs.rmSync(dataDir, { recursive: true, force: true });
});
