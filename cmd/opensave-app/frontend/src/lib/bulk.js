// Doing one thing to many games: back each up, send each to the cloud, put
// each back to its newest snapshot, switch syncing on or off, stop tracking.
//
// One game at a time, in the order shown, so the daemon is never asked for
// twenty snapshots at once and the progress reads as a count. Every game ends
// up as done, failed (with the reason) or skipped (with why it was not even
// tried) — "7 of 10" said nothing about which three, or what to do about
// them. A batch can be stopped between games; whatever was started finishes.
import { writable, get } from 'svelte/store';
import { api } from './api.js';
import { tt, trError } from './i18n.js';
import { askConfirm } from './stores.js';
import { latestSnapshot } from './gameactions.js';
import { whenLabel } from './snapshots.js';

/**
 * What can be done to a selection. Each says
 *   label, verb      — the button, and what the progress line says it is doing
 *   danger           — asks before starting, naming what will change
 *   skip(game, ctx)  — why this game is left out, or '' to go ahead
 *   run(game)        — does it; resolves to a short note, throws on failure
 */
export const BULK_OPS = {
  snapshot: {
    get label() {
      return tt('bulk.snapshot.label');
    },
    get verb() {
      return tt('bulk.snapshot.verb');
    },
    get hint() {
      return tt('bulk.snapshot.hint');
    },
    skip: (g) => (g.savePathMissing ? tt('bulk.skip.saveFolderMissing') : ''),
    run: async (g) => {
      await api.post(`/api/games/${g.id}/snapshot`, { comment: '' });
      return '';
    }
  },
  cloud: {
    get label() {
      return tt('bulk.cloud.label');
    },
    get verb() {
      return tt('bulk.cloud.verb');
    },
    get hint() {
      return tt('bulk.cloud.hint');
    },
    unavailable: (ctx) => (ctx.cloudReady ? '' : tt('bulk.cloud.unavailable')),
    skip: (g) => (snapshotCount(g) === 0 ? tt('bulk.skip.noSnapshots') : ''),
    run: async (g) => {
      const res = await api.post(`/api/cloud/sync-local/${g.id}`);
      if (res.failed > 0) {
        const e = new Error(tt('bulk.cloud.someFailed', { failed: res.failed, total: res.failed + res.uploaded, reason: trError(res.error) }));
        e.said = true; // already in the chosen language
        throw e;
      }
      return res.uploaded > 0 ? tt('bulk.cloud.uploaded', { n: res.uploaded }) : tt('bulk.cloud.alreadyThere');
    }
  },
  restore: {
    get label() {
      return tt('bulk.restore.label');
    },
    get verb() {
      return tt('bulk.restore.verb');
    },
    get hint() {
      return tt('bulk.restore.hint');
    },
    danger: true,
    confirm: (games) => ({
      title: tt('bulk.restore.confirmTitle', { n: games.length }),
      message: tt('bulk.restore.confirmMessage'),
      confirmText: tt('bulk.restore.confirmButton', { n: games.length })
    }),
    skip: (g) => (latestSnapshot(g) ? '' : tt('bulk.skip.noSnapshotToRestore')),
    run: async (g) => {
      const snap = latestSnapshot(g);
      await api.post(`/api/games/${g.id}/rollback`, { snapshotId: snap.id });
      return tt('bulk.restore.done', { when: whenLabel(snap.timestamp) });
    }
  },
  sync: {
    get label() {
      return tt('bulk.sync.label');
    },
    get verb() {
      return tt('bulk.sync.verb');
    },
    get hint() {
      return tt('bulk.sync.hint');
    },
    unavailable: (ctx) => (ctx.paused ? tt('bulk.sync.paused') : ''),
    skip: (g) => (g.autoSync === false ? tt('bulk.skip.autoSyncOff') : ''),
    run: async (g) => {
      await api.post(`/api/games/${g.id}/sync`, {});
      return '';
    }
  },
  enableSync: {
    get label() {
      return tt('bulk.enableSync.label');
    },
    get verb() {
      return tt('bulk.enableSync.verb');
    },
    get hint() {
      return tt('bulk.enableSync.hint');
    },
    skip: (g) => (g.autoSync !== false ? tt('bulk.skip.alreadyOn') : ''),
    run: async (g) => {
      await api.patch(`/api/games/${g.id}`, { autoSync: true });
      return '';
    }
  },
  disableSync: {
    get label() {
      return tt('bulk.disableSync.label');
    },
    get verb() {
      return tt('bulk.disableSync.verb');
    },
    get hint() {
      return tt('bulk.disableSync.hint');
    },
    skip: (g) => (g.autoSync === false ? tt('bulk.skip.alreadyOff') : ''),
    run: async (g) => {
      await api.patch(`/api/games/${g.id}`, { autoSync: false });
      return '';
    }
  },
  favourite: {
    get label() {
      return tt('bulk.favourite.label');
    },
    get verb() {
      return tt('bulk.favourite.verb');
    },
    get hint() {
      return tt('bulk.favourite.hint');
    },
    run: async (g) => {
      await api.post('/api/collections/favourites/games', { gameId: g.id, in: true });
      return '';
    }
  },
  untrack: {
    get label() {
      return tt('bulk.untrack.label');
    },
    get verb() {
      return tt('bulk.untrack.verb');
    },
    get hint() {
      return tt('bulk.untrack.hint');
    },
    danger: true,
    confirm: (games) => ({
      title: tt('bulk.untrack.confirmTitle', { n: games.length }),
      message: tt('bulk.untrack.confirmMessage'),
      confirmText: tt('bulk.untrack.confirmButton', { n: games.length })
    }),
    run: async (g) => {
      await api.del(`/api/games/${g.id}`);
      return '';
    }
  }
};

const snapshotCount = (g) => Object.values(g?.branches ?? {}).reduce((n, b) => n + (b?.snapshots?.length ?? 0), 0);

/** Why an operation can't be started at all right now, or ''. */
export function bulkUnavailable(op, ctx) {
  return BULK_OPS[op]?.unavailable?.(ctx) ?? '';
}

/**
 * Runs op over games one by one. onStep({done, total, current}) reports
 * progress; shouldStop() is asked before each game. Resolves to the results,
 * one per game: {game, status: 'ok'|'failed'|'skipped', note}. Never throws.
 * Pure of the stores, so tested on its own.
 */
export async function runBulk(op, games, { onStep = () => {}, shouldStop = () => false } = {}) {
  const def = BULK_OPS[op];
  const results = [];
  for (let i = 0; i < games.length; i++) {
    const g = games[i];
    if (shouldStop()) {
      for (const rest of games.slice(i)) results.push({ game: rest, status: 'skipped', note: tt('bulk.skip.stopped') });
      break;
    }
    onStep({ done: i, total: games.length, current: g.name });
    const why = def.skip?.(g) ?? '';
    if (why) {
      results.push({ game: g, status: 'skipped', note: why });
      continue;
    }
    try {
      const note = await def.run(g);
      results.push({ game: g, status: 'ok', note: note ?? '' });
    } catch (e) {
      // The daemon's own wording, explained in the chosen language.
      const note = !e?.message ? tt('bulk.unknownError') : e.said ? String(e.message) : trError(String(e.message));
      results.push({ game: g, status: 'failed', note });
    }
  }
  onStep({ done: games.length, total: games.length, current: '' });
  return results;
}

/** {ok, failed, skipped} counts of a batch's results. */
export function tally(results) {
  const t = { ok: 0, failed: 0, skipped: 0 };
  for (const r of results) t[r.status]++;
  return t;
}

/**
 * The batch on screen: null, or {op, total, done, current, results, finished}.
 * One at a time — the dialog shows it, and the toolbar is disabled while it
 * runs.
 */
export const bulkJob = writable(null);
let stopRequested = false;

/** Asks the running batch to stop after the game it is on. */
export function stopBulk() {
  stopRequested = true;
  bulkJob.update((j) => (j && !j.finished ? { ...j, stopping: true } : j));
}

/** Confirms if the operation is a dangerous one, then runs it with progress
 *  in bulkJob. Resolves to the results, or null when not started. */
export async function startBulk(op, games) {
  const def = BULK_OPS[op];
  if (!def || games.length === 0 || (get(bulkJob) && !get(bulkJob).finished)) return null;
  if (def.danger) {
    const c = def.confirm(games);
    const ok = await askConfirm(c.message, { title: c.title, confirmText: c.confirmText, danger: true });
    if (!ok) return null;
  }
  stopRequested = false;
  bulkJob.set({ op, label: def.verb, total: games.length, done: 0, current: '', results: [], finished: false, stopping: false });
  const results = await runBulk(op, games, {
    onStep: (s) => bulkJob.update((j) => (j ? { ...j, ...s } : j)),
    shouldStop: () => stopRequested
  });
  bulkJob.update((j) => (j ? { ...j, results, finished: true, current: '' } : j));
  return results;
}
