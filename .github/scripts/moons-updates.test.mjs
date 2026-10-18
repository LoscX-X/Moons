import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { requestBot, main, validateManifest, makePayload, fingerprint, addNextBatch } from './moons-updates.mjs';

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

const buildEnv = {
  GITHUB_REPOSITORY: 'LoscX-X/Moons', GITHUB_REF_NAME: 'latest', GITHUB_SHA: 'a'.repeat(40),
  GITHUB_RUN_ID: '1', GITHUB_RUN_ATTEMPT: '1', GITHUB_RUN_NUMBER: '1', CLIENT_VERSION: '1.0.1.2-Release',
};
const scopedManifest = changes => ({ schema: 1, batches: [{
  id: '2026-10-14-02', summary: 'Moons 客户端与服务器更新', changes,
}] });
test('new Moons batches require explicit client/server classification', () => {
  for (const scope of [undefined, null, 'bot', 'internal', 'website', '']) {
    assert.throws(() => validateManifest(scopedManifest([{ type: 'feat', text: '更新内容', scope }])), /client\/server scope/);
  }
  for (const id of ['2026-10-14-10', '2026-10-15-01', '2026-11-01-01', 'new-batch']) {
    const manifest = scopedManifest([{ type: 'feat', text: '未分类内容' }]);
    manifest.batches[0].id = id;
    assert.throws(() => makePayload(manifest, buildEnv), /client\/server scope/);
  }
});
test('client/server entries keep the existing bot protocol and renderable content', () => {
  const manifest = scopedManifest([
    { type: 'feat', text: '客户端瞄准更新', scope: 'client' },
    { type: 'fix', text: '游戏服务器场地修复', scope: 'server' },
  ]);
  const validated = validateManifest(manifest);
  assert.deepEqual(validated, manifest.batches);
  const payload = makePayload(manifest, buildEnv);
  assert.deepEqual(payload.updates[0].changes, [
    { type: 'feat', text: '客户端瞄准更新' }, { type: 'fix', text: '游戏服务器场地修复' },
  ]);
  assert.equal(fingerprint(validated[0]), createHash('sha256').update(JSON.stringify(payload.updates[0])).digest('hex'));
  const receipt = { id: validated[0].id, status: 'pushed', pushed_at: '2026-10-14T06:00:00Z', fingerprint: fingerprint(validated[0]) };
  const next = addNextBatch(manifest, receipt, '2026-10-14-03');
  assert.deepEqual(next.batches[0], manifest.batches[0], 'Rotation must retain classification in saved snapshots');
  assert.deepEqual(next.batches.at(-1).changes, []);
});
test('frozen legacy snapshots and receipt fingerprints remain unchanged', async () => {
  const manifest = JSON.parse(await readFile(new URL('../moons-updates.json', import.meta.url), 'utf8'));
  const legacy = manifest.batches.filter(batch => batch.id <= '2026-10-14-01');
  assert.equal(legacy.length, 15);
  const payload = makePayload({ schema: 1, batches: legacy }, buildEnv);
  assert.deepEqual(payload.updates, legacy);
  for (const batch of legacy) {
    assert.equal(fingerprint(batch), createHash('sha256').update(JSON.stringify(batch)).digest('hex'));
  }
  assert.deepEqual(validateManifest(scopedManifest([]))[0].changes, []);
});
