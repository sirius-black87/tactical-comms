'use strict';
/**
 * Tactical Comms relay server.
 *
 * One WebSocket per device (JSON control messages + binary audio frames) and
 * a small HTTP API for file upload/download. See docs/PROTOCOL.md.
 */
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer, WebSocket } = require('ws');
const { execFileSync } = require('child_process');
const { Store } = require('./store');

/**
 * Self-signed TLS for servers reached by IP address (no domain, so no public CA
 * certificate). The certificate is created once and kept in the data directory;
 * the plugin pins its SHA-256 fingerprint instead of relying on a CA.
 */
function ensureSelfSignedCert(dataDir) {
  const dir = path.join(dataDir, 'tls');
  const cert = path.join(dir, 'cert.pem');
  const key = path.join(dir, 'key.pem');
  if (!fs.existsSync(cert) || !fs.existsSync(key)) {
    fs.mkdirSync(dir, { recursive: true });
    execFileSync('openssl', [
      'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes',
      '-keyout', key, '-out', cert, '-days', '3650', '-subj', '/CN=tactical-comms',
    ], { stdio: 'ignore' });
    fs.chmodSync(key, 0o600);
  }
  return { cert, key };
}

const KIND_PTT = 1;
const KIND_CALL = 2;

function createServer(opts = {}) {
  const cfg = {
    port: Number(opts.port ?? process.env.PORT ?? 8080),
    accessKey: opts.accessKey ?? process.env.ACCESS_KEY ?? '',
    dataDir: opts.dataDir ?? process.env.DATA_DIR ?? path.join(__dirname, '..', 'data'),
    maxFileBytes: Number(opts.maxFileMb ?? process.env.MAX_FILE_MB ?? 200) * 1024 * 1024,
    fileTtlMs: Number(opts.fileTtlHours ?? process.env.FILE_TTL_HOURS ?? 72) * 3600 * 1000,
    pttMaxMs: Number(opts.pttMaxSeconds ?? process.env.PTT_MAX_SECONDS ?? 90) * 1000,
    ringTimeoutMs: Number(opts.ringTimeoutSeconds ?? process.env.RING_TIMEOUT_SECONDS ?? 45) * 1000,
    tlsCert: opts.tlsCert ?? process.env.TLS_CERT,
    tlsKey: opts.tlsKey ?? process.env.TLS_KEY,
    tlsSelfSigned: opts.tlsSelfSigned ?? ['1', 'true', 'yes'].includes(String(process.env.TLS_SELF_SIGNED).toLowerCase()),
    log: opts.log ?? ((...a) => console.log(new Date().toISOString(), ...a)),
  };
  const log = cfg.log;
  const store = new Store(cfg.dataDir);
  const filesDir = path.join(cfg.dataDir, 'files');
  fs.mkdirSync(filesDir, { recursive: true });

  /** uid -> { ws, token, uid, callsign } */
  const sessions = new Map();
  /** token -> uid (for HTTP file API) */
  const tokens = new Map();
  /** sid -> { from, recipients:Set, floorKey, timer } */
  const ptt = new Map();
  /** floorKey -> sid */
  const floors = new Map();
  /** cid -> { a, b, state, timer } */
  const calls = new Map();

  // ---------------------------------------------------------------- helpers
  const send = (uid, msg) => {
    const s = sessions.get(uid);
    if (s && s.ws.readyState === WebSocket.OPEN) {
      s.ws.send(JSON.stringify(msg));
      return true;
    }
    return false;
  };
  const sendRaw = (uid, buf) => {
    const s = sessions.get(uid);
    if (s && s.ws.readyState === WebSocket.OPEN && s.ws.bufferedAmount < 256 * 1024) {
      s.ws.send(buf, { binary: true });
    }
  };
  const broadcast = (msg, exceptUid) => {
    const data = JSON.stringify(msg);
    for (const [uid, s] of sessions) {
      if (uid !== exceptUid && s.ws.readyState === WebSocket.OPEN) s.ws.send(data);
    }
  };
  const err = (uid, code, message, ref) => send(uid, { t: 'error', code, message, ref });
  const str = (v, max = 256) => (typeof v === 'string' ? v.slice(0, max) : '');
  const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
  const newId = () => crypto.randomBytes(12).toString('hex');

  const userView = (u) => ({
    uid: u.uid,
    callsign: u.callsign,
    online: sessions.has(u.uid),
    lastSeen: u.lastSeen,
    pos: u.pos || null,
  });

  /** Resolve a {type,id} target into recipient uids (excluding the sender). */
  function resolveTarget(from, to) {
    if (!to || typeof to !== 'object') return { error: 'bad_target' };
    if (to.type === 'user') {
      if (!store.users[to.id]) return { error: 'unknown_user' };
      if (to.id === from) return { error: 'self_target' };
      return { recipients: [to.id], floorKey: 'u:' + [from, to.id].sort().join('|') };
    }
    if (to.type === 'group') {
      const g = store.groups[to.id];
      if (!g) return { error: 'unknown_group' };
      if (!g.members.includes(from)) return { error: 'not_member' };
      return { recipients: g.members.filter((m) => m !== from), floorKey: 'g:' + g.id };
    }
    return { error: 'bad_target' };
  }

  function deliverOrQueue(uid, msg) {
    if (!send(uid, msg)) store.queue(uid, msg);
  }

  // ---------------------------------------------------------------- groups
  function pushGroup(g) {
    for (const m of g.members) send(m, { t: 'group', group: g });
  }

  function handleGroupCreate(uid, m) {
    const name = str(m.name, 64).trim();
    if (!name) return err(uid, 'bad_request', 'group name required', m.ref);
    const members = new Set([uid]);
    for (const x of Array.isArray(m.members) ? m.members : []) {
      if (typeof x === 'string' && store.users[x]) members.add(x);
    }
    const g = { id: newId(), name, owner: uid, members: [...members], created: Date.now() };
    store.groups[g.id] = g;
    store.save();
    log(`group ${g.id} "${name}" created by ${uid} with ${g.members.length} members`);
    pushGroup(g);
    send(uid, { t: 'ok', ref: m.ref, gid: g.id });
  }

  function handleGroupUpdate(uid, m) {
    const g = store.groups[m.gid];
    if (!g || !g.members.includes(uid)) return err(uid, 'not_member', 'not a member', m.ref);
    const removed = [];
    if (typeof m.name === 'string' && m.name.trim()) {
      if (g.owner !== uid) return err(uid, 'forbidden', 'only the owner can rename', m.ref);
      g.name = str(m.name, 64).trim();
    }
    if (Array.isArray(m.add)) {
      for (const x of m.add) if (typeof x === 'string' && store.users[x] && !g.members.includes(x)) g.members.push(x);
    }
    if (Array.isArray(m.remove)) {
      for (const x of m.remove) {
        if (x !== uid && g.owner !== uid) return err(uid, 'forbidden', 'only the owner can remove others', m.ref);
        const i = g.members.indexOf(x);
        if (i >= 0) { g.members.splice(i, 1); removed.push(x); }
      }
    }
    for (const x of removed) send(x, { t: 'group_removed', gid: g.id });
    finishGroupChange(g);
    send(uid, { t: 'ok', ref: m.ref, gid: g.id });
  }

  function handleGroupLeave(uid, m) {
    const g = store.groups[m.gid];
    if (!g || !g.members.includes(uid)) return err(uid, 'not_member', 'not a member', m.ref);
    g.members = g.members.filter((x) => x !== uid);
    send(uid, { t: 'group_removed', gid: g.id });
    finishGroupChange(g);
    send(uid, { t: 'ok', ref: m.ref, gid: g.id });
  }

  function finishGroupChange(g) {
    if (g.members.length === 0) {
      delete store.groups[g.id];
    } else {
      if (!g.members.includes(g.owner)) g.owner = g.members[0];
      pushGroup(g);
    }
    store.save();
  }

  // ---------------------------------------------------------------- PTT
  function endPtt(sid, reason) {
    const p = ptt.get(sid);
    if (!p) return;
    clearTimeout(p.timer);
    ptt.delete(sid);
    if (floors.get(p.floorKey) === sid) floors.delete(p.floorKey);
    const msg = { t: 'ptt_end', sid, from: p.from, reason };
    for (const r of p.recipients) send(r, msg);
    if (reason !== 'released') send(p.from, msg);
  }

  function handlePttStart(uid, m) {
    const sid = str(m.sid, 64);
    if (!sid || ptt.has(sid)) return err(uid, 'bad_request', 'invalid sid', m.ref);
    const t = resolveTarget(uid, m.to);
    if (t.error) return err(uid, t.error, 'cannot talk to target', m.ref);
    const holder = floors.get(t.floorKey);
    if (holder && ptt.has(holder)) {
      return send(uid, { t: 'ptt_denied', sid, busyBy: ptt.get(holder).from });
    }
    const online = t.recipients.filter((r) => sessions.has(r));
    const p = { from: uid, recipients: new Set(online), floorKey: t.floorKey };
    p.timer = setTimeout(() => endPtt(sid, 'timeout'), cfg.pttMaxMs);
    ptt.set(sid, p);
    floors.set(t.floorKey, sid);
    const callsign = store.users[uid].callsign;
    for (const r of online) send(r, { t: 'ptt_start', sid, from: uid, callsign, to: m.to });
    send(uid, { t: 'ptt_granted', sid, listeners: online.length });
  }

  // ---------------------------------------------------------------- calls
  function endCall(cid, reason, byUid) {
    const c = calls.get(cid);
    if (!c) return;
    clearTimeout(c.timer);
    calls.delete(cid);
    for (const x of [c.a, c.b]) if (x !== byUid) send(x, { t: 'call_end', cid, reason });
  }

  const inCall = (uid) => [...calls.values()].some((c) => c.a === uid || c.b === uid);

  function handleCallInvite(uid, m) {
    const cid = str(m.cid, 64);
    const to = str(m.to, 128);
    if (!cid || calls.has(cid)) return err(uid, 'bad_request', 'invalid cid', m.ref);
    if (!sessions.has(to)) return send(uid, { t: 'call_end', cid, reason: 'offline' });
    if (to === uid) return send(uid, { t: 'call_end', cid, reason: 'self' });
    if (inCall(to)) return send(uid, { t: 'call_end', cid, reason: 'busy' });
    const c = { a: uid, b: to, state: 'ringing' };
    c.timer = setTimeout(() => endCall(cid, 'no_answer'), cfg.ringTimeoutMs);
    calls.set(cid, c);
    send(to, { t: 'call_invite', cid, from: uid, callsign: store.users[uid].callsign });
    send(uid, { t: 'call_ringing', cid });
  }

  function handleCallAccept(uid, m) {
    const c = calls.get(m.cid);
    if (!c || c.b !== uid || c.state !== 'ringing') return err(uid, 'bad_request', 'no such call', m.ref);
    clearTimeout(c.timer);
    c.state = 'active';
    send(c.a, { t: 'call_accepted', cid: m.cid });
  }

  function handleCallEnd(uid, m, reason) {
    const c = calls.get(m.cid);
    if (!c || (c.a !== uid && c.b !== uid)) return;
    endCall(m.cid, reason, uid);
  }

  // ---------------------------------------------------------------- binary
  function handleBinary(uid, buf) {
    if (buf.length < 3) return;
    const kind = buf[0];
    const n = buf[1];
    if (buf.length < 2 + n) return;
    const id = buf.subarray(2, 2 + n).toString('ascii');
    if (kind === KIND_PTT) {
      const p = ptt.get(id);
      if (!p || p.from !== uid) return;
      for (const r of p.recipients) sendRaw(r, buf);
    } else if (kind === KIND_CALL) {
      const c = calls.get(id);
      if (!c || c.state !== 'active') return;
      if (c.a === uid) sendRaw(c.b, buf);
      else if (c.b === uid) sendRaw(c.a, buf);
    }
  }

  // ---------------------------------------------------------------- messages
  function handleMessage(uid, m) {
    switch (m.t) {
      case 'pos': {
        if (!isNum(m.lat) || !isNum(m.lon) || Math.abs(m.lat) > 90 || Math.abs(m.lon) > 180) return;
        const pos = { lat: m.lat, lon: m.lon, ts: Date.now() };
        for (const k of ['hae', 'ce', 'speed', 'course', 'battery']) if (isNum(m[k])) pos[k] = m[k];
        const u = store.users[uid];
        u.pos = pos;
        u.lastSeen = pos.ts;
        store.saveSoon();
        broadcast({ t: 'pos', uid, callsign: u.callsign, ...pos }, uid);
        return;
      }
      case 'chat': {
        const t = resolveTarget(uid, m.to);
        if (t.error) return err(uid, t.error, 'cannot send to target', m.mid);
        const text = str(m.text, 8000);
        let file = null;
        if (m.file && typeof m.file === 'object' && store.files[m.file.id]) {
          const f = store.files[m.file.id];
          file = { id: f.id, name: f.name, size: f.size, mime: f.mime, kind: str(m.file.kind, 16) || 'file' };
          for (const r of t.recipients) f.allowed.push(r);
          store.save();
        }
        if (!text && !file) return err(uid, 'bad_request', 'empty message', m.mid);
        const msg = {
          t: 'chat',
          mid: str(m.mid, 64) || newId(),
          from: uid,
          callsign: store.users[uid].callsign,
          to: { type: m.to.type, id: m.to.id },
          text,
          file,
          ts: Date.now(),
        };
        for (const r of t.recipients) deliverOrQueue(r, msg);
        send(uid, { t: 'ack', mid: msg.mid, ts: msg.ts });
        return;
      }
      case 'users':
        return send(uid, { t: 'users', users: Object.values(store.users).map(userView) });
      case 'group_create': return handleGroupCreate(uid, m);
      case 'group_update': return handleGroupUpdate(uid, m);
      case 'group_leave': return handleGroupLeave(uid, m);
      case 'ptt_start': return handlePttStart(uid, m);
      case 'ptt_end': {
        const p = ptt.get(m.sid);
        if (p && p.from === uid) endPtt(m.sid, 'released');
        return;
      }
      case 'call_invite': return handleCallInvite(uid, m);
      case 'call_accept': return handleCallAccept(uid, m);
      case 'call_reject': return handleCallEnd(uid, m, str(m.reason, 32) || 'rejected');
      case 'call_end': return handleCallEnd(uid, m, 'hangup');
      case 'ping': return send(uid, { t: 'pong', ts: Date.now() });
      default:
        return err(uid, 'unknown_type', 'unknown message type ' + str(m.t, 32));
    }
  }

  function cleanupUser(uid) {
    for (const [sid, p] of ptt) {
      if (p.from === uid) endPtt(sid, 'disconnected');
      else p.recipients.delete(uid);
    }
    for (const [cid, c] of calls) if (c.a === uid || c.b === uid) endCall(cid, 'disconnected', uid);
  }

  // ---------------------------------------------------------------- HTTP
  function authHttp(req) {
    const h = req.headers.authorization || '';
    const tok = h.startsWith('Bearer ') ? h.slice(7) : null;
    return tok && tokens.get(tok);
  }

  function json(res, code, obj) {
    res.writeHead(code, { 'content-type': 'application/json' });
    res.end(JSON.stringify(obj));
  }

  function handleHttp(req, res) {
    const url = new URL(req.url, 'http://x');
    if (url.pathname === '/health') {
      return json(res, 200, { ok: true, online: sessions.size, users: Object.keys(store.users).length });
    }
    if (url.pathname === '/files' && req.method === 'POST') {
      const uid = authHttp(req);
      if (!uid) return json(res, 401, { error: 'unauthorized' });
      const declared = Number(req.headers['content-length'] || 0);
      if (declared > cfg.maxFileBytes) return json(res, 413, { error: 'too_large' });
      const id = newId();
      const name = path.basename(str(url.searchParams.get('name') || 'file', 200)) || 'file';
      const mime = str(req.headers['content-type'] || 'application/octet-stream', 100);
      const dest = path.join(filesDir, id);
      const out = fs.createWriteStream(dest);
      let size = 0;
      let aborted = false;
      req.on('data', (chunk) => {
        size += chunk.length;
        if (size > cfg.maxFileBytes && !aborted) {
          aborted = true;
          req.unpipe(out);
          out.destroy();
          fs.rm(dest, { force: true }, () => {});
          json(res, 413, { error: 'too_large' });
          req.destroy();
        }
      });
      req.pipe(out);
      out.on('finish', () => {
        if (aborted) return;
        store.files[id] = { id, name, size, mime, owner: uid, allowed: [uid], created: Date.now() };
        store.save();
        log(`file ${id} "${name}" (${size} bytes) uploaded by ${uid}`);
        json(res, 200, { id, name, size, mime });
      });
      out.on('error', () => { if (!aborted) json(res, 500, { error: 'write_failed' }); });
      return;
    }
    const mm = url.pathname.match(/^\/files\/([0-9a-f]{24})$/);
    if (mm && req.method === 'GET') {
      const uid = authHttp(req);
      if (!uid) return json(res, 401, { error: 'unauthorized' });
      const f = store.files[mm[1]];
      if (!f) return json(res, 404, { error: 'not_found' });
      if (!f.allowed.includes(uid)) return json(res, 403, { error: 'forbidden' });
      res.writeHead(200, {
        'content-type': f.mime,
        'content-length': f.size,
        'content-disposition': `attachment; filename*=UTF-8''${encodeURIComponent(f.name)}`,
      });
      return fs.createReadStream(path.join(filesDir, f.id)).pipe(res);
    }
    json(res, 404, { error: 'not_found' });
  }

  function cleanupFiles() {
    const cutoff = Date.now() - cfg.fileTtlMs;
    let changed = false;
    for (const [id, f] of Object.entries(store.files)) {
      if (f.created < cutoff) {
        fs.rm(path.join(filesDir, id), { force: true }, () => {});
        delete store.files[id];
        changed = true;
      }
    }
    if (changed) store.save();
  }

  // ---------------------------------------------------------------- wiring
  if (cfg.tlsSelfSigned && !(cfg.tlsCert && cfg.tlsKey)) {
    const generated = ensureSelfSignedCert(cfg.dataDir);
    cfg.tlsCert = generated.cert;
    cfg.tlsKey = generated.key;
  }
  let fingerprint = null;
  let server;
  if (cfg.tlsCert && cfg.tlsKey) {
    const certPem = fs.readFileSync(cfg.tlsCert);
    fingerprint = new crypto.X509Certificate(certPem).fingerprint256;
    server = https.createServer({ cert: certPem, key: fs.readFileSync(cfg.tlsKey) }, handleHttp);
  } else {
    server = http.createServer(handleHttp);
  }

  const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 64 * 1024 });

  wss.on('connection', (ws, req) => {
    let uid = null;
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });
    const helloTimer = setTimeout(() => ws.close(4000, 'hello timeout'), 10000);

    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        if (uid) handleBinary(uid, data);
        return;
      }
      let m;
      try { m = JSON.parse(data.toString()); } catch { return; }
      if (!m || typeof m !== 'object') return;
      if (!uid) {
        if (m.t !== 'hello') return ws.close(4001, 'hello required');
        if (cfg.accessKey && m.key !== cfg.accessKey) {
          ws.send(JSON.stringify({ t: 'error', code: 'auth', message: 'bad access key' }));
          return ws.close(4003, 'bad access key');
        }
        const id = str(m.uid, 128);
        if (!id) return ws.close(4002, 'uid required');
        clearTimeout(helloTimer);
        const old = sessions.get(id);
        if (old) {
          old.ws.close(4004, 'replaced by new connection');
          tokens.delete(old.token);
          sessions.delete(id);
          cleanupUser(id);
        }
        uid = id;
        const callsign = str(m.callsign, 64) || uid;
        const u = store.users[uid] || { uid, created: Date.now() };
        u.callsign = callsign;
        u.lastSeen = Date.now();
        store.users[uid] = u;
        store.save();
        const token = crypto.randomBytes(24).toString('hex');
        sessions.set(uid, { ws, token, uid });
        tokens.set(token, uid);
        log(`connect ${uid} (${callsign}) from ${req.socket.remoteAddress}`);
        ws.send(JSON.stringify({
          t: 'welcome',
          token,
          you: userView(u),
          users: Object.values(store.users).filter((x) => x.uid !== uid).map(userView),
          groups: Object.values(store.groups).filter((g) => g.members.includes(uid)),
          limits: { maxFileBytes: cfg.maxFileBytes, pttMaxMs: cfg.pttMaxMs },
        }));
        for (const q of store.takeQueue(uid)) ws.send(JSON.stringify(q));
        broadcast({ t: 'presence', uid, callsign, online: true }, uid);
        return;
      }
      try { handleMessage(uid, m); } catch (e) { log('handler error', e); }
    });

    ws.on('close', () => {
      clearTimeout(helloTimer);
      if (!uid) return;
      const s = sessions.get(uid);
      if (!s || s.ws !== ws) return; // replaced session
      sessions.delete(uid);
      tokens.delete(s.token);
      cleanupUser(uid);
      const u = store.users[uid];
      u.lastSeen = Date.now();
      store.saveSoon();
      log(`disconnect ${uid}`);
      broadcast({ t: 'presence', uid, callsign: u.callsign, online: false });
    });
    ws.on('error', () => {});
  });

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.isAlive) { ws.terminate(); continue; }
      ws.isAlive = false;
      ws.ping();
    }
  }, 25000);
  const fileGc = setInterval(cleanupFiles, 3600 * 1000);

  return {
    cfg,
    server,
    /** SHA-256 fingerprint of the TLS certificate (AA:BB:...), or null without TLS. */
    fingerprint,
    listen() {
      return new Promise((resolve) => server.listen(cfg.port, () => resolve(server.address().port)));
    },
    close() {
      clearInterval(heartbeat);
      clearInterval(fileGc);
      for (const ws of wss.clients) ws.terminate();
      return new Promise((resolve) => wss.close(() => server.close(() => {
        store.close();
        resolve();
      })));
    },
  };
}

module.exports = { createServer, KIND_PTT, KIND_CALL };

if (require.main === module) {
  const app = createServer();
  app.listen().then((port) => {
    app.cfg.log(`Tactical Comms server listening on :${port} (ws path /ws)` +
      (app.cfg.accessKey ? ' [access key required]' : ' [WARNING: no ACCESS_KEY set]'));
    if (app.fingerprint) {
      app.cfg.log('TLS enabled. Certificate fingerprint (SHA-256), to compare with the plugin settings:');
      app.cfg.log('  ' + app.fingerprint);
    } else {
      app.cfg.log('WARNING: TLS is off - traffic is NOT encrypted. Set TLS_SELF_SIGNED=1 or put a TLS proxy in front.');
    }
  });
  const stop = () => app.close().then(() => process.exit(0));
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}
