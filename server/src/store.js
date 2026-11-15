'use strict';
const fs = require('fs');
const path = require('path');

const MAX_QUEUE = 500;

/** Tiny JSON-file persistence for users, groups, file metadata and offline message queues. */
class Store {
  constructor(dir) {
    this.dir = dir;
    this.file = path.join(dir, 'state.json');
    fs.mkdirSync(dir, { recursive: true });
    let data = {};
    try { data = JSON.parse(fs.readFileSync(this.file, 'utf8')); } catch { /* fresh start */ }
    this.users = data.users || {};
    this.groups = data.groups || {};
    this.files = data.files || {};
    this.queues = data.queues || {};
    this.timer = null;
  }

  queue(uid, msg) {
    const q = (this.queues[uid] ||= []);
    q.push(msg);
    if (q.length > MAX_QUEUE) q.splice(0, q.length - MAX_QUEUE);
    this.saveSoon();
  }

  takeQueue(uid) {
    const q = this.queues[uid] || [];
    if (q.length) {
      delete this.queues[uid];
      this.save();
    }
    return q;
  }

  saveSoon() {
    if (!this.timer && !this.closed) this.timer = setTimeout(() => this.flush(), 2000);
  }

  /** Final write; later changes (e.g. sockets closing during shutdown) are not scheduled. */
  close() {
    this.flush();
    this.closed = true;
  }

  save() { this.saveSoon(); }

  flush() {
    clearTimeout(this.timer);
    this.timer = null;
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify({
      users: this.users, groups: this.groups, files: this.files, queues: this.queues,
    }));
    fs.renameSync(tmp, this.file);
  }
}

module.exports = { Store };
