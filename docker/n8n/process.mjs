// Supervise only this container's n8n process. No Docker socket is needed.
import { spawn } from 'node:child_process';
import { readFile, access } from 'node:fs/promises';
import { createServer } from 'node:http';
import { timingSafeEqual } from 'node:crypto';

const control = process.env.WINDLASS_CONTROL_DIR || '/control';
let child;
let stopping = false;
let closing = false;

function start() {
  if (child) return;
  child = spawn('/docker-entrypoint.sh', [], { stdio: 'inherit', detached: true });
  child.once('error', () => process.exit(1));
  child.once('exit', (code) => {
    child = undefined;
    if (!stopping && !closing) process.exit(code || 1);
  });
}

async function stop() {
  if (!child) return;
  const current = child;
  stopping = true;
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        current.removeListener('exit', done);
        reject(new Error('n8n did not stop within 75 seconds. Try again when workflows are idle.'));
      }, 75000);
      function done() { clearTimeout(timer); resolve(); }
      current.once('exit', done);
      try { process.kill(-current.pid, 'SIGTERM'); }
      catch (error) { clearTimeout(timer); current.removeListener('exit', done); reject(error); }
    });
  } finally { stopping = false; }
}

function reply(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(body));
}

const server = createServer(async (request, response) => {
  try {
    const token = (await readFile(`${control}/token`, 'utf8')).trim();
    const supplied = Buffer.from(request.headers.authorization || '');
    const expected = Buffer.from(`Bearer ${token}`);
    if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) {
      return reply(response, 403, { message: 'Forbidden' });
    }
    if (request.method === 'GET' && request.url === '/status') {
      return reply(response, 200, { running: !!child, stopping });
    }
    if (stopping || closing) return reply(response, 409, { message: 'n8n is stopping.' });
    if (request.method === 'POST' && request.url === '/stop') {
      await stop();
      return reply(response, 200, { running: false });
    }
    if (request.method === 'POST' && request.url === '/start') {
      start();
      return reply(response, 200, { running: true });
    }
    reply(response, 404, { message: 'Not found' });
  } catch (error) {
    reply(response, 503, { message: error.code === 'ENOENT' ? 'Backup manager is starting.' : error.message });
  }
});
server.requestTimeout = 10000;
server.headersTimeout = 10000;
server.listen(5677, '0.0.0.0');

// A persisted maintenance marker prevents a container restart from opening a
// database while the backup manager is recovering an interrupted restore.
try { await access(`${control}/maintenance`); }
catch (error) { if (error.code !== 'ENOENT') throw error; start(); }

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.once(signal, async () => {
    closing = true;
    server.close();
    try { await stop(); process.exit(0); }
    catch { process.exit(1); }
  });
}
