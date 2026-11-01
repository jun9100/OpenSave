// One line per game saying where its save stands, for the library and the
// summary above it. The order is the order a person needs to hear things in:
// something waiting on them first, then something happening now, then how
// recently the save was last made safe.
//
// "Synced" means synced with a paired device; with nothing paired, the only
// safety net is a snapshot, so that is what the line reports instead. Never
// "up to date": this device cannot know what another device has done since.
import { timeAgo, latestOf } from './timeago.js';
import { playLength } from './format.js';
import { tt } from './i18n.js';

/**
 * @param {object} game A game from the games payload.
 * @param {object} ctx
 * @param {object} ctx.peers Paired devices, keyed by id.
 * @param {object} [ctx.activity] This game's syncActivity entry.
 * @param {boolean} [ctx.conflicted] A conflict for this game is waiting.
 * @param {number} [ctx.now]
 * @returns {{state: string, label: string, tone: 'warn'|'busy'|'playing'|'ok'|'muted'}}
 */
export function gameStatus(game, { peers = {}, activity, conflicted = false, now = Date.now() } = {}) {
  // Before anything else: with its folder gone nothing else about it can
  // move — no snapshot, no sync, no decision taken.
  if (game.savePathMissing) return { state: 'missing', label: tt('status.saveFolderMissing'), tone: 'warn' };
  // Every save file deleted here at once, held back from the other devices
  // until someone says whether that was meant (lib/emptied.js).
  if (game.emptied?.state === 'held') return { state: 'emptied', label: tt('status.emptied'), tone: 'warn' };
  if (game.emptied?.state === 'fetching') return { state: 'restoring', label: tt('status.restoring'), tone: 'busy' };
  if (conflicted) return { state: 'conflict', label: tt('status.conflict'), tone: 'warn' };
  // Being played here now (see daemon/sessions.go): what matters most about
  // it while it lasts; its save is kept as the session leaves it. Its own
  // tone, green, and never the spinning "busy" one — nothing is syncing.
  if (game.playingSince) return { state: 'playing', label: sessionLabel(game.playingSince, now), tone: 'playing' };
  if (activity?.state === 'running') {
    return { state: 'syncing', label: tt('status.syncing', { percent: activity.percentage ?? 0 }), tone: 'busy' };
  }
  if (activity?.state === 'error') return { state: 'error', label: tt('status.error'), tone: 'warn' };
  if (game.autoSync === false) return { state: 'paused', label: tt('status.paused'), tone: 'muted' };

  if (Object.keys(peers).length > 0) {
    const paired = Object.fromEntries(Object.entries(game.lastSyncedWith ?? {}).filter(([id]) => id in peers));
    const at = latestOf(paired);
    return at
      ? { state: 'synced', label: tt('status.synced', { when: timeAgo(at, now) }), tone: 'ok' }
      : { state: 'unsynced', label: tt('status.unsynced'), tone: 'muted' };
  }

  const snap = latestSnapshotAt(game);
  return snap
    ? { state: 'local', label: tt('status.local', { when: timeAgo(snap, now) }), tone: 'ok' }
    : { state: 'empty', label: tt('status.empty'), tone: 'muted' };
}

/**
 * The one sentence above the library: the most pressing thing about all of
 * it. A decision waiting beats a sync running, which beats a failure that
 * retries on its own, which beats the quiet cases.
 * @param {{game: object, status: {state: string}}[]} rows
 */
export function librarySummary(rows) {
  const by = (state) => rows.filter((r) => r.status.state === state);
  const missing = by('missing');
  const emptied = by('emptied');
  const conflict = by('conflict');
  const syncing = by('syncing');
  const failed = by('error');
  // From the snapshots, not the state: a game with devices paired reports
  // its sync, and may have no snapshot at all behind it. "Backed up" below
  // has to be true of every game it counts.
  const empty = rows.filter((r) => latestSnapshotAt(r.game) === null);
  // Snapshots found damaged when read back (daemon/verify.go): a backup that
  // cannot be restored is worth knowing about before it is needed.
  const damaged = rows.flatMap((r) =>
    Object.values(r.game.branches ?? {}).flatMap((b) => (b.snapshots ?? []).filter((s) => s.problem).map(() => r.game))
  );
  const paused = by('paused');

  if (missing.length > 0) {
    return {
      tone: 'warn',
      headline:
        missing.length === 1
          ? tt('status.summary.missingOne', { game: missing[0].game.name })
          : tt('status.summary.missingMany', { count: missing.length })
    };
  }
  if (emptied.length > 0) {
    return {
      tone: 'warn',
      headline:
        emptied.length === 1
          ? tt('status.summary.emptiedOne', { game: emptied[0].game.name })
          : tt('status.summary.emptiedMany', { count: emptied.length })
    };
  }
  if (conflict.length > 0) {
    return {
      tone: 'warn',
      headline:
        conflict.length === 1
          ? tt('status.summary.conflictOne', { game: conflict[0].game.name })
          : tt('status.summary.conflictMany', { n: conflict.length })
    };
  }
  if (syncing.length > 0) {
    return {
      tone: 'busy',
      headline:
        syncing.length === 1
          ? tt('status.summary.syncingOne', { game: syncing[0].game.name })
          : tt('status.summary.syncingMany', { count: syncing.length })
    };
  }
  if (failed.length > 0) {
    return {
      tone: 'warn',
      headline:
        failed.length === 1
          ? tt('status.summary.failedOne', { game: failed[0].game.name })
          : tt('status.summary.failedMany', { count: failed.length })
    };
  }
  if (damaged.length > 0) {
    return {
      tone: 'warn',
      headline:
        damaged.length === 1
          ? tt('status.summary.damagedOne', { game: damaged[0].name })
          : tt('status.summary.damagedMany', { count: damaged.length })
    };
  }
  if (empty.length > 0) {
    return {
      tone: 'muted',
      headline: tt('status.summary.noSnapshot', { n: empty.length })
    };
  }
  if (paused.length > 0) {
    return {
      tone: 'muted',
      headline: tt('status.summary.paused', { n: paused.length })
    };
  }
  return {
    tone: 'ok',
    headline: rows.length === 1 ? tt('status.summary.okOne') : tt('status.summary.okAll', { count: rows.length })
  };
}

/** The newest snapshot's time on any branch, or null. */
export function latestSnapshotAt(game) {
  let best = null;
  for (const b of Object.values(game?.branches ?? {})) {
    for (const s of b.snapshots ?? []) {
      if (s.timestamp && (best === null || s.timestamp > best)) best = s.timestamp;
    }
  }
  return best;
}

/** Game ids with a conflict waiting, whole-game or in one of its folders. */
export function conflictedIds(conflicts, locationConflicts) {
  const ids = new Set(Object.keys(conflicts ?? {}));
  for (const c of locationConflicts ?? []) if (c?.gameId) ids.add(c.gameId);
  return ids;
}

const byName = (a, b) => a.game.name.localeCompare(b.game.name);

// Larger first, ties and the missing by name.
const descending = (value) => (a, b) => {
  const x = value(a) ?? -Infinity;
  const y = value(b) ?? -Infinity;
  return x === y ? byName(a, b) : x < y ? 1 : -1;
};

const stamp = (s) => (s ? Date.parse(s) : null);
const snapshotsOf = (game) => Object.values(game.branches ?? {}).flatMap((b) => b.snapshots ?? []);

// What needs looking at, in the order the summary above the library reads it.
const URGENCY = { missing: 0, emptied: 1, conflict: 2, error: 3, syncing: 4, restoring: 4, unsynced: 5, empty: 6, paused: 7 };

/**
 * The orders the library can be put in. Each compares two rows, {game,
 * status}, and puts first what that order is about; the View menu can turn
 * any of them around.
 */
export const SORTS = {
  name: {
    get label() {
      return tt('library.sort.name');
    },
    compare: byName
  },
  // The newest snapshot is the last time OpenSave saw the save change.
  recent: {
    get label() {
      return tt('library.sort.recent');
    },
    compare: descending((r) => stamp(latestSnapshotAt(r.game)))
  },
  synced: {
    get label() {
      return tt('library.sort.synced');
    },
    compare: descending((r) => {
      const times = Object.values(r.game.lastSyncedWith ?? {}).map(stamp).filter((t) => t !== null);
      return times.length ? Math.max(...times) : null;
    })
  },
  attention: {
    get label() {
      return tt('library.sort.attention');
    },
    compare: (a, b) => {
      const x = URGENCY[a.status?.state] ?? 9;
      const y = URGENCY[b.status?.state] ?? 9;
      return x === y ? byName(a, b) : x - y;
    }
  },
  snapshots: {
    get label() {
      return tt('library.sort.snapshots');
    },
    compare: descending((r) => snapshotsOf(r.game).length)
  },
  size: {
    get label() {
      return tt('library.sort.size');
    },
    compare: descending((r) => snapshotsOf(r.game).reduce((n, s) => n + (Number(s.sizeBytes) || 0), 0))
  },
  added: {
    get label() {
      return tt('library.sort.added');
    },
    compare: descending((r) => stamp(r.game.createdAt))
  },
  // A game being played now first, then by when it was last played.
  played: {
    get label() {
      return tt('library.sort.played');
    },
    compare: descending((r) => (r.game.playingSince ? Infinity : stamp(r.game.lastPlayedAt || null)))
  }
};

/** Rows in a view's order. */
export function sortRows(rows, sort, reverse = false) {
  const compare = (SORTS[sort] ?? SORTS.name).compare;
  const out = [...rows].sort(compare);
  return reverse ? out.reverse() : out;
}

/** "In session", and for how long once it is a minute or more. */
export function sessionLabel(since, now = Date.now()) {
  const ms = now - Date.parse(since);
  return Number.isFinite(ms) && ms >= 60_000 ? tt('status.inSessionFor', { length: playLength(ms) }) : tt('status.inSession');
}
