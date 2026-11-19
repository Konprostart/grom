// SPDX-License-Identifier: Apache-2.0
/*
 * Tests for the Grom Node agent runtime.
 *
 * The protocol-level tests spawn agent/grom-agent.js so the real stdin pipe,
 * stdout readiness lines and process exit codes are exercised. The sqlite
 * absence path is exercised in-process through the injectable loader on
 * createAgent, which is why the agent is a factory and not a top-level script.
 */

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { Readable } = require('node:stream');

const {
  MAX_STDIN_BYTES,
  MAX_BODY_BYTES,
  OPS,
  STORAGE_UNAVAILABLE_MESSAGE,
  createAgent,
  parseArgs,
  parseConfig,
  readStdinConfig,
} = require('../grom-agent.js');

const AGENT_DIR = path.resolve(__dirname, '..');
const AGENT_SCRIPT = path.join(AGENT_DIR, 'grom-agent.js');
const TOKEN = `test-token-${process.pid}-${Date.now()}`;
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/* ------------------------------------------------------------------------- *
 * Helpers
 * ------------------------------------------------------------------------- */

/** A tiny writable sink that records everything written to it. */
function collector() {
  let text = '';
  return {
    write(chunk, callback) {
      text += String(chunk);
      if (typeof callback === 'function') callback();
      return true;
    },
    text() {
      return text;
    },
  };
}

/**
 * Minimal HTTP client. `agent: false` makes every request a fresh connection,
 * so a child agent can shut down immediately on SIGTERM.
 */
function request(port, options = {}) {
  const method = options.method || 'GET';
  const requestPath = options.path || '/';
  const headers = { ...(options.headers || {}) };
  let payload = null;

  if (options.body !== undefined) {
    payload = Buffer.isBuffer(options.body)
      ? options.body
      : Buffer.from(
          typeof options.body === 'string' ? options.body : JSON.stringify(options.body),
          'utf8',
        );
    headers['Content-Type'] = 'application/json';
    headers['Content-Length'] = payload.length;
  }
  if (options.token !== undefined) {
    headers.Authorization = `Bearer ${options.token}`;
  }

  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: '127.0.0.1',
        port,
        path: requestPath,
        method,
        headers,
        agent: false,
      },
      (res) => {
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          let json = null;
          try {
            json = JSON.parse(text);
          } catch {
            /* some tests only need the status/content type */
          }
          resolve({ status: res.statusCode, headers: res.headers, text, json });
        });
      },
    );
    req.setTimeout(5000, () => req.destroy(new Error('request timed out')));
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

function spawnAgentChild(configText, options = {}) {
  const args =
    options.args || ['--port', '47001', '--parent-pid', String(process.pid)];
  const child = spawn(process.execPath, [AGENT_SCRIPT, ...args], {
    cwd: AGENT_DIR,
    stdio: ['pipe', 'pipe', 'pipe'],
    env: { ...process.env },
  });

  const state = { stdout: '', stderr: '', exited: false };
  child.stdout.setEncoding('utf8');
  child.stderr.setEncoding('utf8');
  child.stdout.on('data', (chunk) => {
    state.stdout += chunk;
  });
  child.stderr.on('data', (chunk) => {
    state.stderr += chunk;
  });
  child.once('exit', () => {
    state.exited = true;
  });
  // A child that rejects its config may exit while the pipe is still open.
  child.stdin.on('error', () => {});

  child.stdin.end(configText);
  return { child, state };
}

async function startAgentChild(config, options = {}) {
  const { child, state } = spawnAgentChild(`${JSON.stringify(config)}\n`, options);
  const deadline = Date.now() + 8000;

  while (Date.now() < deadline) {
    const match = /^GROM_AGENT_READY (\d+) (\d+)\r?\n/m.exec(state.stdout);
    if (match) {
      return {
        child,
        state,
        pid: Number(match[1]),
        port: Number(match[2]),
      };
    }
    if (state.exited) {
      throw new Error(`agent exited before ready: ${state.stderr || state.stdout}`);
    }
    await delay(20);
  }

  child.kill('SIGKILL');
  throw new Error(`agent did not become ready: ${state.stderr || state.stdout}`);
}

async function waitForClose(child, timeoutMs = 5000) {
  return Promise.race([
    once(child, 'close'),
    delay(timeoutMs).then(() => {
      child.kill('SIGKILL');
      return ['timeout', null];
    }),
  ]);
}

async function stopAgentChild(child) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  const closed = once(child, 'close');
  child.kill('SIGTERM');
  await Promise.race([closed, delay(3000)]);
  if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
}

async function runAgentToExit(configText, options = {}) {
  const { child, state } = spawnAgentChild(configText, options);
  const [code, signal] = await waitForClose(child);
  return { code, signal, ...state };
}

function assertNoToken(text, token) {
  assert.equal(
    String(text).includes(token),
    false,
    'a response or log line contained the bearer token',
  );
}

/* ------------------------------------------------------------------------- *
 * Configuration parsing (stdin)
 * ------------------------------------------------------------------------- */

test('readStdinConfig accepts one valid JSON object and stops at EOF', async () => {
  const result = await readStdinConfig(
    Readable.from([
      `${JSON.stringify({ token: TOKEN, port: 47001, logLevel: 'info' })}\n`,
    ]),
  );
  assert.deepEqual(result, {
    ok: true,
    config: { token: TOKEN, port: 47001, logLevel: 'info' },
  });
});

test('readStdinConfig rejects invalid JSON, missing token and bad fields', async () => {
  const cases = [
    '',
    'not-json',
    '[]',
    '{"port":47001,"logLevel":"info"}',
    '{"token":"","port":47001,"logLevel":"info"}',
    '{"token":"abc","port":"not-a-port","logLevel":"info"}',
    '{"token":"abc","port":47001,"logLevel":7}',
  ];
  for (const text of cases) {
    const result = await readStdinConfig(Readable.from([text]));
    assert.deepEqual(result, { ok: false, reason: 'invalid-config' }, `input: ${text}`);
  }
});

test('readStdinConfig enforces the 16 KiB cap in bytes', async () => {
  const result = await readStdinConfig(
    Readable.from([`${JSON.stringify({ token: TOKEN, port: 47001 })}\n`.padEnd(MAX_STDIN_BYTES + 1, ' ')]),
  );
  assert.deepEqual(result, { ok: false, reason: 'config-too-large' });

  const atCap = await readStdinConfig(
    Readable.from([`${JSON.stringify({ token: TOKEN, port: 47001 })}`.padEnd(MAX_STDIN_BYTES, ' ')]),
  );
  assert.equal(atCap.ok, true, 'a payload of exactly the cap must be accepted');
});

test('parseArgs reads --port and --parent-pid without inventing values', () => {
  assert.deepEqual(parseArgs(['--port', '47001', '--parent-pid', '4242']), {
    port: 47001,
    parentPid: 4242,
  });
  assert.deepEqual(parseArgs(['--port=47001', '--parent-pid=4242']), {
    port: 47001,
    parentPid: 4242,
  });
  assert.deepEqual(parseArgs(['--port', 'nope', '--parent-pid', '-1']), {
    port: null,
    parentPid: null,
  });
});

/* ------------------------------------------------------------------------- *
 * CLI-level fatal paths
 * ------------------------------------------------------------------------- */

test('the CLI prints GROM_FATAL on invalid/oversized stdin and never leaks the token', async () => {
  const badJson = `{"token":"${TOKEN}","port":47001`;
  const oversized = JSON.stringify({
    token: TOKEN,
    port: 47001,
    logLevel: 'info',
    padding: 'x'.repeat(MAX_STDIN_BYTES),
  });
  const cases = [
    ['empty stdin', ''],
    ['missing token', '{"port":47001,"logLevel":"info"}\n'],
    ['bad JSON', `${badJson}\n`],
    ['oversized', `${oversized}\n`],
  ];

  for (const [name, payload] of cases) {
    const result = await runAgentToExit(payload);
    assert.notEqual(result.code, 0, `${name}: exit code must be non-zero`);
    assert.match(
      result.stdout,
      /^GROM_FATAL \S+\r?\n$/,
      `${name}: expected exactly one GROM_FATAL line, got ${JSON.stringify(result.stdout)}`,
    );
    assertNoToken(result.stdout, TOKEN);
    assertNoToken(result.stderr, TOKEN);
  }
});

/* ------------------------------------------------------------------------- *
 * Full child-process integration
 * ------------------------------------------------------------------------- */

test('child agent: readiness lines, auth, ops, routing and clean SIGTERM', async (t) => {
  const started = await startAgentChild({
    token: TOKEN,
    port: 47001,
    logLevel: 'info',
  });
  t.after(async () => {
    await stopAgentChild(started.child);
  });

  const { child, state, port } = started;
  const responses = [];

  // --- readiness ---------------------------------------------------------
  const lines = state.stdout.split(/\r?\n/).filter((line) => line.length > 0);
  assert.equal(lines.length, 2, `stdout must be exactly two lines: ${JSON.stringify(lines)}`);
  assert.equal(lines[0], `GROM_READY ${child.pid}`);
  assert.equal(lines[1], `GROM_AGENT_READY ${child.pid} ${port}`);
  assert.equal(started.pid, child.pid);
  assert.ok(port > 0 && port < 65536, 'a real ephemeral port is reported');
  assert.equal(port, started.port);

  // --- /healthz (unauthenticated) ---------------------------------------
  let res = await request(port, { path: '/healthz' });
  responses.push(res.text);
  assert.equal(res.status, 200);
  assert.match(res.headers['content-type'], /^application\/json; charset=utf-8$/);
  assert.equal(res.json.ok, true);
  assert.equal(res.json.pid, child.pid);
  assert.equal(typeof res.json.uptimeMs, 'number');
  assert.deepEqual(res.json.ops, OPS);
  assert.equal(typeof res.json.sqlite.available, 'boolean');
  assert.ok(['node:sqlite', 'memory'].includes(res.json.sqlite.backend));

  // --- /status authentication -------------------------------------------
  res = await request(port, { path: '/status' });
  responses.push(res.text);
  assert.equal(res.status, 401);
  assert.equal(res.json.code, 'UNAUTHORIZED');

  res = await request(port, { path: '/status', token: 'wrong-token' });
  responses.push(res.text);
  assert.equal(res.status, 401);
  assert.equal(res.json.code, 'UNAUTHORIZED');

  res = await request(port, { path: '/status', token: TOKEN });
  responses.push(res.text);
  assert.equal(res.status, 200);
  assert.equal(res.json.pid, child.pid);
  assert.equal(res.json.parentPid, process.pid);
  assert.equal(res.json.nodeVersion, process.version);
  assert.deepEqual(res.json.config, { port: 47001, logLevel: 'info' });
  assert.deepEqual(res.json.ops, OPS);
  assert.equal(typeof res.json.uptimeMs, 'number');
  assert.equal(typeof res.json.sqlite.available, 'boolean');
  assert.equal(
    Object.prototype.hasOwnProperty.call(res.json, 'token'),
    false,
    'status must never contain the token',
  );

  // --- /exec ping and echo ----------------------------------------------
  res = await request(port, {
    method: 'POST',
    path: '/exec',
    token: TOKEN,
    body: { op: 'ping' },
  });
  responses.push(res.text);
  assert.equal(res.status, 200);
  assert.equal(res.json.op, 'ping');
  assert.equal(res.json.ok, true);
  assert.equal(res.json.result, 'pong');
  assert.equal(res.json.pid, child.pid);
  assert.equal(typeof res.json.uptimeMs, 'number');

  res = await request(port, {
    method: 'POST',
    path: '/exec',
    token: TOKEN,
    body: { op: 'echo', args: { echo: 'hello' } },
  });
  responses.push(res.text);
  assert.equal(res.status, 200);
  assert.deepEqual(res.json, { op: 'echo', ok: true, result: 'hello' });

  res = await request(port, {
    method: 'POST',
    path: '/exec',
    token: TOKEN,
    body: { op: 'echo', args: { a: 1, b: [2] } },
  });
  responses.push(res.text);
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.result, { a: 1, b: [2] });

  // --- /exec auth and unknown op ----------------------------------------
  res = await request(port, {
    method: 'POST',
    path: '/exec',
    body: { op: 'ping' },
  });
  responses.push(res.text);
  assert.equal(res.status, 401);
  assert.equal(res.json.code, 'UNAUTHORIZED');

  res = await request(port, {
    method: 'POST',
    path: '/exec',
    token: 'nope',
    body: { op: 'ping' },
  });
  responses.push(res.text);
  assert.equal(res.status, 401);

  res = await request(port, {
    method: 'POST',
    path: '/exec',
    token: TOKEN,
    body: { op: 'definitely.not.an.op' },
  });
  responses.push(res.text);
  assert.equal(res.status, 400);
  assert.equal(res.json.code, 'UNKNOWN_OP');

  // --- routing: 404, 405 with Allow, 413 ---------------------------------
  res = await request(port, { path: '/no-such-path' });
  responses.push(res.text);
  assert.equal(res.status, 404);

  res = await request(port, { method: 'POST', path: '/healthz' });
  responses.push(res.text);
  assert.equal(res.status, 405);
  assert.equal(res.headers.allow, 'GET');

  res = await request(port, { path: '/exec', token: TOKEN });
  responses.push(res.text);
  assert.equal(res.status, 405);
  assert.equal(res.headers.allow, 'POST');

  res = await request(port, {
    method: 'POST',
    path: '/exec',
    token: TOKEN,
    body: Buffer.alloc(MAX_BODY_BYTES + 1, 0x20),
  });
  responses.push(res.text);
  assert.equal(res.status, 413);

  // --- the token appears in no response and no log line -----------------
  for (const text of responses) assertNoToken(text, TOKEN);
  assertNoToken(state.stdout, TOKEN);
  assertNoToken(state.stderr, TOKEN);

  // --- SIGTERM is a clean exit ------------------------------------------
  const closed = once(child, 'close');
  child.kill('SIGTERM');
  const [code, signal] = await Promise.race([closed, delay(4000).then(() => ['timeout', null])]);
  assert.equal(signal, null, 'the agent should exit normally, not by signal');
  assert.equal(code, 0);
});

/* ------------------------------------------------------------------------- *
 * sqlite degradation (injected loader)
 * ------------------------------------------------------------------------- */

test('kv ops answer 503 STORAGE_UNAVAILABLE when node:sqlite is absent', async (t) => {
  const stdout = collector();
  const stderr = collector();
  const agent = createAgent({
    config: { token: TOKEN, port: 47001, logLevel: 'info' },
    // Simulate a Node build without node:sqlite. The agent must keep serving.
    sqliteLoader() {
      throw new Error('node:sqlite is not available');
    },
    stdout,
    stderr,
  });
  t.after(async () => {
    await agent.stop();
  });

  const { port } = await agent.start();
  assert.ok(port > 0);

  // The factory still emits the same two readiness lines.
  const lines = stdout.text().split(/\r?\n/).filter((line) => line.length > 0);
  assert.deepEqual(lines, [
    `GROM_READY ${process.pid}`,
    `GROM_AGENT_READY ${process.pid} ${port}`,
  ]);

  let res = await request(port, { path: '/healthz' });
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.sqlite, { available: false, backend: 'memory' });

  res = await request(port, { path: '/status', token: TOKEN });
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.sqlite, { available: false, backend: 'memory' });

  for (const body of [
    { op: 'kv.get', args: { key: 'missing' } },
    { op: 'kv.put', args: { key: 'k', value: 'v' } },
  ]) {
    res = await request(port, {
      method: 'POST',
      path: '/exec',
      token: TOKEN,
      body,
    });
    assert.equal(res.status, 503, `${body.op} must not fake success`);
    assert.deepEqual(res.json, {
      ok: false,
      code: 'STORAGE_UNAVAILABLE',
      message: STORAGE_UNAVAILABLE_MESSAGE,
    });
    assertNoToken(res.text, TOKEN);
  }

  // Exactly one redacted degradation warning, and it never contains the token.
  const warningLines = stderr
    .text()
    .split(/\r?\n/)
    .filter((line) => line.includes('node:sqlite'));
  assert.equal(warningLines.length, 1, 'expected exactly one sqlite warning line');
  assert.match(warningLines[0], /node:sqlite unavailable/);
  assertNoToken(stderr.text(), TOKEN);
  assertNoToken(stdout.text(), TOKEN);
});

test('kv ops round-trip through node:sqlite when it is available', async (t) => {
  let sqlite;
  try {
    sqlite = require('node:sqlite');
  } catch {
    t.skip('node:sqlite is not available in this runtime');
    return;
  }

  const stdout = collector();
  const stderr = collector();
  const agent = createAgent({
    config: { token: TOKEN, port: 47001, logLevel: 'info' },
    sqliteLoader: () => sqlite,
    stdout,
    stderr,
  });
  t.after(async () => {
    await agent.stop();
  });

  const { port } = await agent.start();
  let res = await request(port, { path: '/healthz' });
  assert.deepEqual(res.json.sqlite, { available: true, backend: 'node:sqlite' });

  res = await request(port, {
    method: 'POST',
    path: '/exec',
    token: TOKEN,
    body: { op: 'kv.put', args: { key: 'greeting', value: 'hello' } },
  });
  assert.equal(res.status, 200);
  assert.equal(res.json.ok, true);
  assert.equal(res.json.op, 'kv.put');

  res = await request(port, {
    method: 'POST',
    path: '/exec',
    token: TOKEN,
    body: { op: 'kv.get', args: { key: 'greeting' } },
  });
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.result, {
    key: 'greeting',
    found: true,
    value: 'hello',
  });

  res = await request(port, {
    method: 'POST',
    path: '/exec',
    token: TOKEN,
    body: { op: 'kv.get', args: { key: 'absent' } },
  });
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.result, { key: 'absent', found: false, value: null });
});

/* ------------------------------------------------------------------------- *
 * Parent-death supervision
 * ------------------------------------------------------------------------- */

test('the agent exits 0 when its parent disappears', async (t) => {
  const holder = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 1000)'], {
    stdio: 'ignore',
  });
  t.after(() => {
    try {
      holder.kill('SIGKILL');
    } catch {
      /* already gone */
    }
  });

  const started = await startAgentChild(
    { token: TOKEN, port: 47001, logLevel: 'info' },
    { args: ['--port', '47001', '--parent-pid', String(holder.pid)] },
  );
  t.after(async () => {
    await stopAgentChild(started.child);
  });

  await once(holder, 'exit');

  // The death watch polls once a second, so allow a little more than that.
  const closed = once(started.child, 'close');
  const [code, signal] = await Promise.race([closed, delay(4000).then(() => ['timeout', null])]);
  assert.equal(signal, null);
  assert.equal(code, 0);
});

/* ------------------------------------------------------------------------- *
 * Defensive helpers
 * ------------------------------------------------------------------------- */

test('parseConfig is the only stdin decoder and keeps the reason short', () => {
  assert.deepEqual(parseConfig('nope'), { ok: false, reason: 'invalid-config' });
  assert.deepEqual(parseConfig('{"token":"x","port":1}'), {
    ok: true,
    config: { token: 'x', port: 1, logLevel: 'info' },
  });
});
