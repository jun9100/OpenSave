import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('./api.js', () => ({
  api: {
    get: vi.fn(async () => ({ cloudSync: { ready: true } })),
    post: vi.fn(async () => ({})),
    patch: vi.fn(async () => ({})),
    del: vi.fn(async () => ({}))
  },
  native: {},
  gameCover: () => ''
}));

import { api } from './api.js';
import { UNDO_DELAY } from './undo.js';
import { canLaunch, gameMenuItems, latestSnapshot, untrackGame } from './gameactions.js';

const game = (extra = {}) => ({
  id: 'g1',
  name: 'Hades',
  activeBranch: 'main',
  branches: {
    main: { snapshots: [{ id: 's1', timestamp: '2026-09-20T10:00:00Z' }, { id: 's2', timestamp: '2026-09-22T10:00:00Z' }] },
    old: { snapshots: [{ id: 's9', timestamp: '2026-09-23T10:00:00Z' }] }
  },
  ...extra
});
const ids = (items) => items.map((i) => i?.id ?? '—');

describe('latestSnapshot', () => {
  it('is the newest on the branch being played, not the newest anywhere', () => {
    expect(latestSnapshot(game()).id).toBe('s2');
  });
  it('is null with nothing to restore', () => {
    expect(latestSnapshot(game({ branches: {} }))).toBe(null);
    expect(latestSnapshot(null)).toBe(null);
  });
});

describe('gameMenuItems', () => {
  it('offers Launch only for a game that can be launched', () => {
    expect(ids(gameMenuItems(game()))).not.toContain('launch');
    expect(ids(gameMenuItems(game({ appId: '1145360' })))).toContain('launch');
    expect(ids(gameMenuItems(game({ exePath: 'C:/Games/Hades.exe' })))).toContain('launch');
  });

  it('does not offer Launch for a game known not to be on this device', () => {
    // Steam asked to run it would offer to install it instead.
    expect(ids(gameMenuItems(game({ appId: '1145360', installed: 'not-found' })))).not.toContain('launch');
    expect(canLaunch(game({ appId: '1145360', installed: 'not-found' }))).toBe(false);
    // Found, or no telling: offered as before.
    expect(canLaunch(game({ appId: '1145360', installed: 'found' }))).toBe(true);
    expect(canLaunch(game({ exePath: 'C:/Games/Hades.exe', installed: '' }))).toBe(true);
  });

  it('greys out restoring when there is no snapshot, and says when the latest is from', () => {
    const none = gameMenuItems(game({ branches: {} })).find((i) => i?.id === 'restore-latest');
    expect(none).toMatchObject({ disabled: true, hint: 'none yet' });
    const some = gameMenuItems(game(), { now: new Date('2026-09-22T12:00:00Z') }).find((i) => i?.id === 'restore-latest');
    expect(some.disabled).toBe(false);
    expect(some.hint).toBeTruthy();
  });

  it('offers to add to Favourites, or to remove when it is one', () => {
    expect(gameMenuItems(game()).find((i) => i?.id === 'favourite').label).toBe('Add to Favourites');
    expect(gameMenuItems(game(), { favourite: true }).find((i) => i?.id === 'favourite').label).toBe('Remove from Favourites');
  });

  it('puts the destructive action last, apart and marked', () => {
    const items = gameMenuItems(game());
    expect(items.at(-1)).toMatchObject({ id: 'untrack', danger: true });
    expect(items.at(-2)).toBe(null);
  });
});

describe('untrackGame', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  const run = async (options) => {
    vi.useFakeTimers();
    const outcome = untrackGame(game(), options);
    await vi.advanceTimersByTimeAsync(UNDO_DELAY);
    return outcome;
  };

  it('offers to delete the cloud copies afterwards', async () => {
    await expect(run()).resolves.toBe('done');
    expect(api.del).toHaveBeenCalledWith('/api/games/g1');
    expect(api.get).toHaveBeenCalledWith('/api/settings');
  });

  it('skips that offer when the caller says the cloud copies are not ours to delete', async () => {
    await expect(run({ skipCloudCleanup: true })).resolves.toBe('done');
    expect(api.del).toHaveBeenCalledWith('/api/games/g1');
    expect(api.get).not.toHaveBeenCalled();
  });
});
