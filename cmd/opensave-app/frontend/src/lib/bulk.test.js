import { describe, expect, it, vi, beforeEach } from 'vitest';

const calls = [];
let fail = new Set();
let cloudAnswer = { uploaded: 2, skipped: 0, failed: 0, error: '' };
vi.mock('./api.js', () => {
  const answer = (method) => async (path, body) => {
    calls.push([method, path, body]);
    const id = path.split('/')[3];
    if (fail.has(id)) throw new Error('permission denied');
    if (path.startsWith('/api/cloud/sync-local/')) return cloudAnswer;
    return {};
  };
  return { api: { get: answer('GET'), post: answer('POST'), patch: answer('PATCH'), del: answer('DELETE') }, native: {} };
});

const { runBulk, tally, bulkUnavailable } = await import('./bulk.js');

const game = (id, extra = {}) => ({
  id,
  name: id.toUpperCase(),
  activeBranch: 'main',
  autoSync: true,
  branches: { main: { snapshots: [{ id: `${id}-s1`, timestamp: '2026-10-01T10:00:00Z' }] } },
  ...extra
});

beforeEach(() => {
  calls.length = 0;
  fail = new Set();
  cloudAnswer = { uploaded: 2, skipped: 0, failed: 0, error: '' };
});

describe('runBulk', () => {
  it('runs one game at a time and reports each', async () => {
    const steps = [];
    const res = await runBulk('snapshot', [game('a'), game('b')], { onStep: (s) => steps.push(s) });
    expect(res.map((r) => r.status)).toEqual(['ok', 'ok']);
    expect(calls.map((c) => c[1])).toEqual(['/api/games/a/snapshot', '/api/games/b/snapshot']);
    expect(steps[0]).toEqual({ done: 0, total: 2, current: 'A' });
    expect(steps.at(-1)).toEqual({ done: 2, total: 2, current: '' });
  });

  it('keeps going past a failure and says why', async () => {
    fail.add('b');
    const res = await runBulk('snapshot', [game('a'), game('b'), game('c')]);
    expect(res.map((r) => r.status)).toEqual(['ok', 'failed', 'ok']);
    expect(res[1].note).toBe('permission denied');
    expect(tally(res)).toEqual({ ok: 2, failed: 1, skipped: 0 });
  });

  it('skips a restore with no snapshot to restore, and restores the newest otherwise', async () => {
    const res = await runBulk('restore', [game('a'), game('b', { branches: {} })]);
    expect(res.map((r) => r.status)).toEqual(['ok', 'skipped']);
    expect(calls).toEqual([['POST', '/api/games/a/rollback', { snapshotId: 'a-s1' }]]);
  });

  it('skips games already in the state asked for', async () => {
    const res = await runBulk('disableSync', [game('a'), game('b', { autoSync: false })]);
    expect(res.map((r) => r.status)).toEqual(['ok', 'skipped']);
    expect(calls).toEqual([['PATCH', '/api/games/a', { autoSync: false }]]);
  });

  it('counts a cloud upload with failed files as failed, with the reason', async () => {
    cloudAnswer = { uploaded: 1, skipped: 0, failed: 2, error: 'HTTP 507' };
    const res = await runBulk('cloud', [game('a')]);
    expect(res[0].status).toBe('failed');
    expect(res[0].note).toContain('HTTP 507');
  });

  it('stops between games when asked, and lists the rest as skipped', async () => {
    let n = 0;
    const res = await runBulk('untrack', [game('a'), game('b'), game('c')], { shouldStop: () => n++ >= 1 });
    expect(res.map((r) => r.status)).toEqual(['ok', 'skipped', 'skipped']);
    expect(calls).toEqual([['DELETE', '/api/games/a', undefined]]);
  });
});

describe('bulkUnavailable', () => {
  it('needs cloud backup set up before uploading', () => {
    expect(bulkUnavailable('cloud', { cloudReady: false })).not.toBe('');
    expect(bulkUnavailable('cloud', { cloudReady: true })).toBe('');
    expect(bulkUnavailable('snapshot', {})).toBe('');
  });
});
