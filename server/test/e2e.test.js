'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const WebSocket = require('ws');
const { createServer, KIND_PTT, KIND_CALL } = require('../src/server');

let app, base, dataDir;

class Client {
  constructor(uid, callsign, key = 'secret') {
    this.uid = uid;
    this.inbox = [];
    this.bin = [];
    this.waiters = [];
    this.ws = new WebSocket(base.replace('http', 'ws') + '/ws');
    this.ws.on('message', (d, isBin) => {
      if (isBin) { this.bin.push(Buffer.from(d)); this._check(); return; }
      this.inbox.push(JSON.parse(d.toString()));
      this._check();
    });
    this.closed = new Promise((r) => this.ws.on('close', (code) => r(code)));
    this.ready = new Promise((r) => this.ws.on('open', r)).then(() => {
      this.send({ t: 'hello', uid, callsign, key });
      return this.next('welcome');
    }).then((w) => { this.token = w.token; return w; });
  }
  send(m) { this.ws.send(JSON.stringify(m)); }
  _check() {
    for (const w of [...this.waiters]) {
      const i = this.inbox.findIndex(w.pred);
      if (i >= 0) {
        this.waiters.splice(this.waiters.indexOf(w), 1);
        w.resolve(this.inbox.splice(i, 1)[0]);
      } else if (w.binary && this.bin.length) {
        this.waiters.splice(this.waiters.indexOf(w), 1);
        w.resolve(this.bin.shift());
      }
    }
  }
  next(t, extra = () => true) {
    return new Promise((resolve, reject) => {
      const w = { pred: (m) => m.t === t && extra(m), resolve };
      this.waiters.push(w);
      setTimeout(() => reject(new Error(`${this.uid}: timeout waiting for ${t}`)), 2000);
      this._check();
    });
  }
  nextBinary() {
    return new Promise((resolve, reject) => {
      const w = { pred: () => false, binary: true, resolve };
      this.waiters.push(w);
      setTimeout(() => reject(new Error(`${this.uid}: timeout waiting for binary`)), 2000);
      this._check();
    });
  }
  close() { this.ws.close(); return this.closed; }
}

const frame = (kind, id, payload) =>
  Buffer.concat([Buffer.from([kind, id.length]), Buffer.from(id, 'ascii'), payload]);

test.before(async () => {
  dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tc-'));
  app = createServer({ port: 0, accessKey: 'secret', dataDir, log: () => {}, ringTimeoutSeconds: 0.3 });
  const port = await app.listen();
  base = `http://127.0.0.1:${port}`;
});
test.after(async () => { await app.close(); fs.rmSync(dataDir, { recursive: true, force: true }); });

test('rejects bad access key', async () => {
  const ws = new WebSocket(base.replace('http', 'ws') + '/ws');
  await new Promise((r) => ws.on('open', r));
  ws.send(JSON.stringify({ t: 'hello', uid: 'x', callsign: 'X', key: 'wrong' }));
  const code = await new Promise((r) => ws.on('close', r));
  assert.strictEqual(code, 4003);
});

test('presence, positions, chat, groups, PTT, calls, files', async () => {
  const a = new Client('ANDROID-a', 'Alpha');
  await a.ready;
  const b = new Client('ANDROID-b', 'Bravo');
  const wb = await b.ready;
  assert.ok(wb.users.find((u) => u.uid === 'ANDROID-a' && u.online));
  await a.next('presence', (m) => m.uid === 'ANDROID-b' && m.online);
  const c = new Client('ANDROID-c', 'Charlie');
  await c.ready;

  // positions
  a.send({ t: 'pos', lat: 32.08, lon: 34.78, hae: 20, battery: 80 });
  const p = await b.next('pos');
  assert.deepStrictEqual([p.uid, p.callsign, p.lat, p.lon, p.battery], ['ANDROID-a', 'Alpha', 32.08, 34.78, 80]);
  await c.next('pos');

  // direct chat
  a.send({ t: 'chat', mid: 'm1', to: { type: 'user', id: 'ANDROID-b' }, text: 'shalom' });
  const msg = await b.next('chat');
  assert.strictEqual(msg.text, 'shalom');
  assert.strictEqual(msg.callsign, 'Alpha');
  await a.next('ack', (m) => m.mid === 'm1');

  // group created by a user (no admin involved)
  a.send({ t: 'group_create', name: 'Team 1', members: ['ANDROID-b', 'ANDROID-c'], ref: 'r1' });
  const g = (await b.next('group')).group;
  assert.deepStrictEqual(g.members.sort(), ['ANDROID-a', 'ANDROID-b', 'ANDROID-c']);
  await c.next('group');
  await a.next('group');

  b.send({ t: 'chat', mid: 'm2', to: { type: 'group', id: g.id }, text: 'to all' });
  assert.strictEqual((await a.next('chat')).text, 'to all');
  assert.strictEqual((await c.next('chat')).to.id, g.id);

  // PTT to group with floor control
  a.send({ t: 'ptt_start', sid: 'p1', to: { type: 'group', id: g.id } });
  assert.strictEqual((await a.next('ptt_granted')).listeners, 2);
  await b.next('ptt_start', (m) => m.sid === 'p1');
  await c.next('ptt_start');
  c.send({ t: 'ptt_start', sid: 'p2', to: { type: 'group', id: g.id } });
  assert.strictEqual((await c.next('ptt_denied')).busyBy, 'ANDROID-a');
  const audio = Buffer.alloc(160, 0x7f);
  a.ws.send(frame(KIND_PTT, 'p1', audio));
  const fb = await b.nextBinary();
  assert.strictEqual(fb.length, 2 + 2 + 160);
  await c.nextBinary();
  a.send({ t: 'ptt_end', sid: 'p1' });
  await b.next('ptt_end', (m) => m.sid === 'p1');
  c.send({ t: 'ptt_start', sid: 'p3', to: { type: 'group', id: g.id } });
  await c.next('ptt_granted');
  c.send({ t: 'ptt_end', sid: 'p3' });

  // PTT direct
  b.send({ t: 'ptt_start', sid: 'p4', to: { type: 'user', id: 'ANDROID-c' } });
  assert.strictEqual((await b.next('ptt_granted')).listeners, 1);
  await c.next('ptt_start', (m) => m.sid === 'p4');
  b.send({ t: 'ptt_end', sid: 'p4' });

  // voice call
  a.send({ t: 'call_invite', cid: 'c1', to: 'ANDROID-b' });
  await a.next('call_ringing');
  const inv = await b.next('call_invite');
  assert.strictEqual(inv.callsign, 'Alpha');
  c.send({ t: 'call_invite', cid: 'c2', to: 'ANDROID-b' });
  assert.strictEqual((await c.next('call_end', (m) => m.cid === 'c2')).reason, 'busy');
  b.send({ t: 'call_accept', cid: 'c1' });
  await a.next('call_accepted');
  b.ws.send(frame(KIND_CALL, 'c1', Buffer.alloc(160, 1)));
  a.ws.send(frame(KIND_CALL, 'c1', Buffer.alloc(160, 2)));
  assert.strictEqual((await a.nextBinary())[6], 1);
  assert.strictEqual((await b.nextBinary())[6], 2);
  a.send({ t: 'call_end', cid: 'c1' });
  assert.strictEqual((await b.next('call_end')).reason, 'hangup');

  // unanswered call times out
  a.send({ t: 'call_invite', cid: 'c3', to: 'ANDROID-c' });
  await c.next('call_invite');
  assert.strictEqual((await a.next('call_end', (m) => m.cid === 'c3')).reason, 'no_answer');

  // file transfer
  const content = Buffer.from('hello file ' + 'x'.repeat(5000));
  const up = await fetch(`${base}/files?name=${encodeURIComponent('דוח.txt')}`, {
    method: 'POST', body: content, headers: { authorization: `Bearer ${a.token}`, 'content-type': 'text/plain' },
  }).then((r) => r.json());
  assert.strictEqual(up.size, content.length);
  assert.strictEqual(up.name, 'דוח.txt');
  // c is not a recipient yet -> forbidden
  assert.strictEqual((await fetch(`${base}/files/${up.id}`, { headers: { authorization: `Bearer ${c.token}` } })).status, 403);
  a.send({ t: 'chat', mid: 'm3', to: { type: 'user', id: 'ANDROID-c' }, text: '', file: { id: up.id, kind: 'file' } });
  const fm = await c.next('chat', (m) => m.mid === 'm3');
  assert.strictEqual(fm.file.name, 'דוח.txt');
  const down = Buffer.from(await (await fetch(`${base}/files/${fm.file.id}`, { headers: { authorization: `Bearer ${c.token}` } })).arrayBuffer());
  assert.ok(down.equals(content));
  assert.strictEqual((await fetch(`${base}/files/${up.id}`)).status, 401);

  // group management: owner removes, member leaves
  a.send({ t: 'group_update', gid: g.id, remove: ['ANDROID-c'], ref: 'r2' });
  await c.next('group_removed');
  assert.deepStrictEqual((await b.next('group', (m) => m.group.members.length === 2)).group.members.length, 2);
  b.send({ t: 'group_update', gid: g.id, remove: ['ANDROID-a'] });
  assert.strictEqual((await b.next('error')).code, 'forbidden');

  // offline queue
  await c.close();
  await a.next('presence', (m) => m.uid === 'ANDROID-c' && !m.online);
  a.send({ t: 'chat', mid: 'm4', to: { type: 'user', id: 'ANDROID-c' }, text: 'while you were away' });
  await a.next('ack', (m) => m.mid === 'm4');
  const c2 = new Client('ANDROID-c', 'Charlie');
  await c2.ready;
  assert.strictEqual((await c2.next('chat')).text, 'while you were away');

  await a.close(); await b.close(); await c2.close();
});
