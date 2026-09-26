import { readFile, writeFile, appendFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const manifestPath = '.github/moons-updates.json';
const types = new Set(['feat', 'fix', 'perf', 'refactor', 'docs', 'build', 'chore']);
const text = (v, n) => typeof v === 'string' && v.trim().length > 0 && v.length <= n;
export const fingerprint = batch => createHash('sha256').update(JSON.stringify(batch)).digest('hex');

const typeLabels = { feat: '新增', fix: '修复', perf: '优化', refactor: '调整', docs: '说明', build: '构建与发布', chore: '维护' };
export function renderNotes(current) {
  if (!current) return '## 本次更新内容\n\n本次没有新的功能更新说明。\n';
  return ['## 本次更新内容', '', `### ${current.summary}`, '', `更新批次：${current.id}`, '',
    ...current.changes.map(c => `- **${typeLabels[c.type]}**：${c.text}`), ''].join('\n');
}

export function validateManifest(manifest) {
  if (manifest?.schema !== 1 || !Array.isArray(manifest.batches) || !manifest.batches.length || manifest.batches.length > 500) throw new Error('Invalid update manifest');
  const ids = new Set();
  return manifest.batches.map((b, i) => {
    if (!b || !/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}$/.test(b.id || '') || ids.has(b.id) || !text(b.summary, 160) ||
        !Array.isArray(b.changes) || b.changes.length > 60 || (!b.changes.length && i !== manifest.batches.length - 1) ||
        b.changes.some(c => !c || !types.has(c.type) || !text(c.text, 300))) throw new Error(`Invalid batch at index ${i}`);
    ids.add(b.id);
    return { id: b.id, summary: b.summary.trim(), changes: b.changes.map(c => ({ type: c.type, text: c.text.trim() })) };
  });
}

export function makePayload(manifest, env) {
  const required = ['GITHUB_REPOSITORY', 'GITHUB_REF_NAME', 'GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_RUN_NUMBER', 'CLIENT_VERSION'];
  for (const key of required) if (!env[key]) throw new Error(`Missing ${key}`);
  if (!/^[\w.-]+\/[\w.-]+$/.test(env.GITHUB_REPOSITORY) || !/^[0-9a-f]{40}$/.test(env.GITHUB_SHA) ||
      !['GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_RUN_NUMBER'].every(k => /^\d+$/.test(env[k]))) throw new Error('Invalid GitHub build identity');
  return {
    schema: 1, project: 'moons',
    build: {
      repository: env.GITHUB_REPOSITORY, branch: env.GITHUB_REF_NAME, commit: env.GITHUB_SHA,
      run_id: env.GITHUB_RUN_ID, run_attempt: env.GITHUB_RUN_ATTEMPT, build_no: env.GITHUB_RUN_NUMBER, version: env.CLIENT_VERSION,
    },
    updates: validateManifest(manifest).filter(b => b.changes.length),
  };
}

function endpoint(env, id) {
  if (!env.MOONS_BOT_URL || !env.MOONS_BOT_SECRET) throw new Error('Set MOONS_BOT_URL and MOONS_BOT_SECRET');
  const url = new URL(env.MOONS_BOT_URL);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || !url.pathname.endsWith('/ci/build')) {
    throw new Error('MOONS_BOT_URL must be an HTTPS URL ending in /ci/build (no credentials/query/fragment)');
  }
  if (id) url.pathname = url.pathname.replace(/\/ci\/build$/, `/ci/updates/${encodeURIComponent(id)}`);
  return url;
}

export async function requestBot(env, { id, payload, request = fetch } = {}) {
  const url = endpoint(env, id);
  const response = await request(url, {
    method: payload ? 'POST' : 'GET', redirect: 'error',
    headers: { 'Content-Type': 'application/json', 'X-CI-Secret': env.MOONS_BOT_SECRET },
    ...(payload ? { body: JSON.stringify(payload) } : {}), signal: AbortSignal.timeout(20000),
  });
  if (!response.ok) throw new Error(`Bot HTTP ${response.status}; check bot logs (404 = batch not received, 409 = immutable batch changed)`);
  const result = await response.json();
  if (!result.ok) throw new Error('Bot did not acknowledge the request');
  return result;
}

export function addNextBatch(manifest, receipt, id) {
  const batches = validateManifest(manifest);
  const current = batches.at(-1);
  if (!current.changes.length) throw new Error('The current draft is empty; keep writing into it');
  if (receipt?.id !== current.id || receipt.status !== 'pushed' || !receipt.pushed_at || receipt.fingerprint !== fingerprint(current)) {
    throw new Error('Current batch has no matching successful push receipt; do not rotate or clear it');
  }
  const next = { schema: 1, batches: [...batches, { id, summary: '下一次 Moons 更新', changes: [] }] };
  validateManifest(next);
  return next;
}

export async function main(args, env = process.env) {
  const [command, argument] = args;
  if (command === 'notify') {
    if (!env.MOONS_BOT_URL || !env.MOONS_BOT_SECRET) {
      if (!env.GITHUB_ACTIONS) throw new Error('Set MOONS_BOT_URL and MOONS_BOT_SECRET');
      console.log('::warning::Moons QQ notification skipped: configure MOONS_BOT_URL and MOONS_BOT_SECRET. No push receipt was created.');
      return;
    }
    const payload = JSON.parse(await readFile(argument || 'artifacts/moons-update.json', 'utf8'));
    for (let attempt = 0; ; attempt++) {
      try {
        const result = await requestBot(env, { payload });
        console.log(JSON.stringify(result.updates.map(u => ({ id: u.id, status: u.status })), null, 2));
        console.log('Accepted into the bot queue. Only status=pushed confirms QQ delivery.');
        return;
      } catch (error) { if (attempt >= 2) throw error; await delay(2000 * (attempt + 1)); }
    }
  }
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  const batches = validateManifest(manifest);
  if (command === 'check') {
    console.log(`Update manifest OK: ${batches.length} batch(es), current=${batches.at(-1).id}`);
  } else if (command === 'prepare') {
    const payload = makePayload(manifest, env);
    const folder = argument || 'build/ci';
    await mkdir(folder, { recursive: true });
    await writeFile(resolve(folder, 'moons-update.json'), JSON.stringify(payload, null, 2) + '\n');
    // An empty new draft must not relabel the previous batch as this run's changes.
    const current = batches.at(-1);
    const notes = renderNotes(current.changes.length ? current : null);
    await writeFile(resolve(folder, 'moons-update-notes.md'), notes);
    if (env.GITHUB_STEP_SUMMARY) await appendFile(env.GITHUB_STEP_SUMMARY, `\n${notes}\n`);
  } else if (command === 'status' || command === 'next') {
    const current = batches.at(-1);
    const { update } = await requestBot(env, { id: command === 'status' && argument ? argument : current.id });
    if (command === 'status') console.log(JSON.stringify(update, null, 2));
    else {
      if (!argument) throw new Error('Usage: node .github/scripts/moons-updates.mjs next <new-batch-id>');
      const next = addNextBatch(manifest, update, argument);
      await writeFile(manifestPath, JSON.stringify(next, null, 2) + '\n');
      console.log(`Created ${argument}; previous batches were preserved.`);
    }
  } else throw new Error('Usage: moons-updates.mjs check | prepare [output-dir] | notify [payload] | status [id] | next <new-id>');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
