// Shared by the worker runners (mcp/*-runner.cjs), each appended to it by WorkerRunners.
// Runs only inside a worker; every file it touches is in the task directory, the first argument.
const fs = require('node:fs');
const path = require('node:path');
const {spawn} = require('node:child_process');
const {StringDecoder} = require('node:string_decoder');
const dir = process.argv[1], run = process.argv[2];
const LIMIT = 1024 * 1024;
const validId = id => typeof id === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$/.test(id);
let scratch;
function read(file, limit = LIMIT, optional = false) {
  let fd;
  try {
    fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NONBLOCK | fs.constants.O_NOFOLLOW);
    const st = fs.fstatSync(fd);
    if (!st.isFile()) throw new Error('not a regular file: ' + file);
    const buf = Buffer.alloc(Math.min(st.size, limit));
    let used = 0, n;
    while (used < buf.length && (n = fs.readSync(fd, buf, used, buf.length - used, null)) > 0) used += n;
    return {text: utf8(buf.subarray(0, used)), bytes: st.size};
  } catch (e) {
    if (optional) return {text: '', bytes: 0};
    throw e;
  } finally { if (fd !== undefined) fs.closeSync(fd); }
}
// A cut never splits a character: an incomplete one at the end is left out, not replaced.
const utf8 = buf => new StringDecoder('utf8').write(buf);
function publish(name, text) {
  const tmp = path.join(scratch, 'publish-' + Math.random().toString(36).slice(2));
  fs.writeFileSync(tmp, text, {flag: 'wx', mode: 0o600});
  fs.renameSync(tmp, path.join(dir, name));
}
function output(name) {
  const tmp = path.join(scratch, name);
  const fd = fs.openSync(tmp, 'wx', 0o600);
  fs.renameSync(tmp, path.join(dir, name));
  return fd;
}
// Writes all of data to fd, which a short write would otherwise cut.
function copy(fd, data) {
  let offset = 0;
  while (offset < data.length) offset += fs.writeSync(fd, data, offset, data.length - offset);
}
// Splits a JSONL stream on LF alone, as Pi and Codex frame it (never on Unicode separators). A
// record longer than limit is dropped, not held: dropped hears its start once.
function lines(onLine, limit = LIMIT, dropped = () => {}) {
  const decoder = new StringDecoder('utf8');
  let pending = '', dropping = false;
  return {
    write(data) {
      const parts = decoder.write(data).split('\n');
      for (let i = 0; i < parts.length; i++) {
        if (!dropping) pending += parts[i];
        if (pending.length > limit) { dropped(pending.slice(0, 64)); pending = ''; dropping = true; }
        if (i < parts.length - 1) {
          if (!dropping) onLine(pending);
          pending = ''; dropping = false;
        }
      }
    },
    end() {
      pending += decoder.end();
      if (pending && !dropping) onLine(pending);
    }
  };
}
