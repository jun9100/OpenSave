// What can be done to a game from anywhere: the right-click menu on the
// library and the sidebar, the game's own page, the Ctrl+K palette.
//
// One place, so that "Stop tracking" from a menu is the same act as the
// button on the game's page — same undo, same cloud question afterwards —
// rather than a second, slightly different copy of it.
import { derived, get, writable } from 'svelte/store';
import { api, native } from './api.js';
import { askConfirm, gameList, games, navigate, toast, view } from './stores.js';
import { hiddenKeys, withUndo } from './undo.js';
import { whenLabel } from './snapshots.js';
import { askRestore } from './restore.js';
import { collections, toggleFavourite } from './collections.js';
import { tt, trError } from './i18n.js';

/** The game whose collections are being edited, or null. */
export const collectionsDialog = writable(null);

/** The library as shown: without games on their way out (see undo.js). */
export const visibleGames = derived([gameList, hiddenKeys], ([$games, $hidden]) => $games.filter((g) => !$hidden.has(g.id)));

/** The newest snapshot on the branch being played, or null. */
export function latestSnapshot(game) {
  const snaps = game?.branches?.[game.activeBranch]?.snapshots ?? [];
  return snaps.reduce((best, s) => (!best || s.timestamp > best.timestamp ? s : best), null);
}

/** Whether a game can be launched from here: it has a program or a Steam App
 *  ID, and is not known to be missing from this device — Steam asked to run a
 *  game it does not have offers to install it instead (daemon.InstallState). */
export function canLaunch(game) {
  return !!(game?.appId || game?.exePath) && game.installed !== 'not-found';
}

/** The menu for one game, in order; `null` is a divider. Pure, so tested.
 *  `favourite` is whether the game is in Favourites. */
export function gameMenuItems(game, { now = new Date(), favourite = false } = {}) {
  const latest = latestSnapshot(game);
  return [
    { id: 'open', label: tt('gameActions.open') },
    { id: 'favourite', label: favourite ? tt('gameActions.removeFavourite') : tt('gameActions.addFavourite') },
    { id: 'collections', label: tt('gameActions.collections') },
    null,
    { id: 'sync', label: tt('gameActions.syncNow') },
    { id: 'snapshot', label: tt('gameActions.snapshotNow') },
    ...(canLaunch(game) ? [{ id: 'launch', label: tt('gameActions.launch') }] : []),
    { id: 'folder', label: tt('gameActions.openFolder') },
    {
      id: 'restore-latest',
      label: tt('gameActions.restoreLatest'),
      disabled: !latest,
      hint: latest ? whenLabel(latest.timestamp, now) : tt('gameActions.noneYet')
    },
    null,
    { id: 'untrack', label: tt('gameActions.untrack'), danger: true }
  ];
}

async function attempt(fn, success) {
  try {
    await fn();
    if (success) toast(success, 'success');
  } catch (e) {
    toast(trError(e.message), 'error');
  }
}

export async function runGameAction(id, game) {
  switch (id) {
    case 'open':
      return navigate('game', { gameId: game.id });
    case 'sync':
      return attempt(() => api.post(`/api/games/${game.id}/sync`), tt('gameActions.syncingToast', { game: game.name }));
    case 'snapshot':
      return attempt(() => api.post(`/api/games/${game.id}/snapshot`, { comment: '' }), tt('gameActions.snapshotTaken', { game: game.name }));
    case 'launch':
      return attempt(() => api.post(`/api/games/${game.id}/launch`), tt('gameActions.launching', { game: game.name }));
    case 'folder': {
      const problem = await native.openFolder(game.savePath);
      if (problem) toast(trError(problem), 'error');
      return;
    }
    case 'restore-latest':
      return restoreLatest(game);
    case 'untrack':
      return untrackGame(game);
    case 'favourite':
      return toggleFavourite(get(collections), game);
    case 'collections':
      return collectionsDialog.set(game);
  }
}

async function restoreLatest(game) {
  const snap = latestSnapshot(game);
  if (!snap) return;
  if (!(await askRestore(game, snap))) return;
  return attempt(() => api.post(`/api/games/${game.id}/rollback`, { snapshotId: snap.id }), tt('gameActions.restored', { game: game.name }));
}

/** Stops tracking a game, with a few seconds to undo it. */
export async function untrackGame(game) {
  const current = get(view);
  if (current.name === 'game' && current.params?.gameId === game.id) navigate('home');
  const outcome = await withUndo({
    message: tt('gameActions.untracked', { game: game.name }),
    keys: [game.id],
    stillThere: (id) => !!get(games)[id],
    run: () => api.del(`/api/games/${game.id}`)
  });
  if (outcome === 'done') offerCloudCleanup(game);
  return outcome;
}

/** Stops tracking several games at once, with the same few seconds. */
export function untrackGames(list) {
  const ids = list.map((g) => g.id);
  const n = ids.length;
  return withUndo({
    message: tt('gameActions.untrackedMany', { n }),
    keys: ids,
    stillThere: (id) => !!get(games)[id],
    run: () => api.post('/api/games/untrack-bulk', { ids })
  });
}

// Cloud copies outlive an untrack unless removed. Offered, not asked: a
// dialog arriving seconds after the click, over whatever is on screen by
// then, would be answered without being read.
async function offerCloudCleanup(game) {
  let settings;
  try {
    settings = await api.get('/api/settings');
  } catch {
    return;
  }
  if (!settings?.cloudSync?.ready) return;
  toast(tt('gameActions.cloudKept', { game: game.name }), 'info', {
    ttl: 12000,
    action: {
      label: tt('gameActions.deleteThem'),
      run: async () => {
        const ok = await askConfirm(tt('gameActions.deleteCloudBody', { game: game.name }), {
          title: tt('gameActions.deleteCloudTitle'),
          confirmText: tt('gameActions.deleteCloudConfirm'),
          danger: true
        });
        if (!ok) return;
        try {
          const res = await api.post(`/api/cloud/delete-game/${game.id}`);
          toast(
            res.deleted > 0 ? tt('gameActions.cloudRemoved', { n: res.deleted }) : tt('gameActions.cloudNothing'),
            'success'
          );
        } catch (e) {
          toast(tt('gameActions.cloudFailed', { error: trError(e.message) }), 'error');
        }
      }
    }
  });
}
