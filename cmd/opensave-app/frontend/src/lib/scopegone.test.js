import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./api.js', () => ({
  api: { get: vi.fn(async () => ({ items: [] })), post: vi.fn(async () => ({})) }
}));

import { api } from './api.js';
import {
  goneNotice,
  goneStatusLabel,
  isScopeGoneState,
  listScopeGone,
  pendingGone,
  resumeGone,
  stopSyncingGone
} from './scopegone.js';

const at = '2026-11-09T12:00:00Z';

describe('isScopeGoneState', () => {
  it('is true only for the two tombstone states', () => {
    expect(isScopeGoneState('scope_deleted')).toBe(true);
    expect(isScopeGoneState('scope_retired')).toBe(true);
    expect(isScopeGoneState('error')).toBe(false);
    expect(isScopeGoneState(undefined)).toBe(false);
  });
});

describe('goneNotice', () => {
  it('says when a deleted scope went, and offers to stop syncing it', () => {
    const notice = goneNotice({ code: 'scope_deleted', at });
    expect(notice.key).toBe('cloud.gone.deleted');
    expect(notice.params.time).toBe(new Date(at).toLocaleString());
    expect(notice.actions).toEqual(['off', 'later']);
    expect(notice.hintKey).toBeUndefined();
  });

  it('names the successor of a merged scope and recommends untracking', () => {
    const notice = goneNotice({ code: 'scope_retired', successorName: 'Forza Horizon 5' });
    expect(notice.key).toBe('cloud.gone.retired');
    expect(notice.params).toEqual({ successor: 'Forza Horizon 5' });
    expect(notice.actions).toEqual(['untrack', 'later']);
    expect(notice.hintKey).toBeUndefined();
  });

  it('falls back to the successor id, and hints when this device tracks it too', () => {
    const notice = goneNotice({
      code: 'scope_retired',
      successorGameId: 'forza-horizon-5',
      successorLocalGameId: 'fh5'
    });
    expect(notice.params.successor).toBe('forza-horizon-5');
    expect(notice.hintKey).toBe('cloud.gone.retiredDuplicate');
  });

  it('adds the way out when resuming was refused', () => {
    expect(goneNotice({ code: 'scope_deleted', resumeRefused: true }).refusedKey).toBe(
      'cloud.gone.resumeRefusedDeleted'
    );
    expect(goneNotice({ code: 'scope_retired', resumeRefused: true }).refusedKey).toBe(
      'cloud.gone.resumeRefusedRetired'
    );
  });

  it('reads a GameSaveStatus as well as a gone record', () => {
    expect(goneNotice({ state: 'scope_deleted', at }).key).toBe('cloud.gone.deleted');
    expect(goneNotice({ state: 'scope_retired', gone: { successorGameId: 'fh5' } }).params.successor).toBe('fh5');
  });
});

describe('pendingGone', () => {
  const items = [
    { gameId: 'a', notice: 'pending' },
    { gameId: 'b', notice: 'off' },
    { gameId: 'c', notice: 'pending' }
  ];

  it('keeps only what is still waiting for a decision', () => {
    expect(pendingGone(items, new Set()).map((i) => i.gameId)).toEqual(['a', 'c']);
  });

  it('leaves out what was put off this run', () => {
    expect(pendingGone(items, new Set(['a'])).map((i) => i.gameId)).toEqual(['c']);
  });

  it('has nothing to show for an empty list', () => {
    expect(pendingGone(undefined, new Set())).toEqual([]);
  });
});

describe('goneStatusLabel', () => {
  it('labels a deleted scope', () => {
    expect(goneStatusLabel({ state: 'scope_deleted' })).toEqual({ key: 'cloud.gone.statusDeleted' });
  });

  it('labels a merged scope with where it went', () => {
    expect(goneStatusLabel({ state: 'scope_retired', gone: { successorName: 'Forza Horizon 5' } })).toEqual({
      key: 'cloud.gone.statusRetired',
      params: { successor: 'Forza Horizon 5' }
    });
  });
});

describe('API calls', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists every gone scope', async () => {
    api.get.mockResolvedValueOnce({ items: [{ gameId: 'a' }] });
    await expect(listScopeGone()).resolves.toEqual({ items: [{ gameId: 'a' }] });
    expect(api.get).toHaveBeenCalledWith('/api/cloud/scope-gone');
  });

  it('turns syncing off for one game', async () => {
    await stopSyncingGone('a b');
    expect(api.post).toHaveBeenCalledWith('/api/cloud/scope-gone/a%20b/off');
  });

  it('asks to resume one game', async () => {
    await resumeGone('a b');
    expect(api.post).toHaveBeenCalledWith('/api/cloud/scope-gone/a%20b/resume');
  });
});
