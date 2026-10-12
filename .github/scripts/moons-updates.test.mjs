import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { requestBot, main } from './moons-updates.mjs';

const env = { MOONS_BOT_URL: 'https://bot.example.com/ci/build', MOONS_BOT_SECRET: 'test-ci-secret' };
test('bot errors identify branch and immutable-batch failures without exposing response data', async () => {
  for (const [code, expected] of [['BRANCH_MISMATCH', /UPDATE_BRANCH \/ MOONS_BRANCH/], ['IMMUTABLE_BATCH', /frozen content/]]) {
    await assert.rejects(requestBot(env, { request: async () => Response.json({ code, error: env.MOONS_BOT_SECRET }, { status: 400 }) }), error => {
      assert.equal(error.status, 400);
      assert.match(error.message, expected);
      assert.ok(!error.message.includes(env.MOONS_BOT_SECRET));
      return true;
    });
  }
});
test('older bot and non-JSON errors remain diagnosable without echoing arbitrary content', async () => {
  await assert.rejects(requestBot(env, { request: async () => Response.json({ error: 'Invalid build payload or repository/branch mismatch' }, { status: 400 }) }), /UPDATE_BRANCH/);
  for (const body of [env.MOONS_BOT_SECRET, JSON.stringify({ code: 'toString', error: env.MOONS_BOT_SECRET }), JSON.stringify({ code: { toString: 'invalid' } })]) {
    await assert.rejects(requestBot(env, { request: async () => new Response(body, { status: 502 }) }), error => {
      assert.equal(error.message, 'Bot HTTP 502: Check bot service logs');
      return true;
    });
  }
});
test('receipt requests retain authentication, escaped IDs and successful response semantics', async () => {
  const result = await requestBot(env, { id: '2026-10-12-01', request: async (url, options) => {
    assert.equal(url.href, 'https://bot.example.com/ci/updates/2026-10-12-01');
    assert.equal(options.method, 'GET');
    assert.equal(options.headers['X-CI-Secret'], env.MOONS_BOT_SECRET);
    assert.equal(options.redirect, 'error');
    return Response.json({ ok: true, update: { status: 'pending' } });
  } });
  assert.equal(result.update.status, 'pending');
});
test('notify does not retry permanent configuration failures', async t => {
  const folder = await mkdtemp(join(tmpdir(), 'moons-notify-test-'));
  t.after(() => rm(folder, { recursive: true, force: true }));
  const path = join(folder, 'payload.json');
  await writeFile(path, '{}');
  let calls = 0;
  const original = globalThis.fetch;
  globalThis.fetch = async () => { calls++; return Response.json({ code: 'BRANCH_MISMATCH' }, { status: 400 }); };
  try { await assert.rejects(main(['notify', path], env), /Branch mismatch/); }
  finally { globalThis.fetch = original; }
  assert.equal(calls, 1);
});
