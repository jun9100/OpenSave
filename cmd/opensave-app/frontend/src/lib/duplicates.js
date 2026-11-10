// Games tracked more than once, and which copy is the one worth keeping.
//
// A scan that finds the same game in several places, or a whole repack folder
// tracked alongside one title inside it, leaves several entries that snapshot
// and upload overlapping files under one name. What can be told for certain:
//   - one entry's folder inside another's: the outer one copies the inner
//     one's files again, and the inner one is the more specific;
//   - an outer folder holding other games' App ID folders: its snapshots mix
//     several games' saves under one name;
//   - two entries with the same files (path, size, time): one is a copy.
// What cannot: two different folders with different files (a game's own save
// folder and a Steam-emulator "cloud" folder, say). Both may matter, so those
// are left for the person to decide.

const norm = (p) =>
  String(p ?? '')
    .replace(/\\/g, '/')
    .replace(/\/+$/, '')
    .toLowerCase();

/** Whether folder `inner` is inside folder `outer` (not the same folder). */
export function isInside(inner, outer) {
  const a = norm(inner);
  const b = norm(outer);
  return !!a && !!b && a !== b && a.startsWith(b + '/');
}

const nameKey = (g) => String(g?.name ?? '').trim().toLowerCase();
const appKey = (g) => (g?.appId ? String(g.appId) : '');

/** Whether two tracked games look like the same game: same App ID, or same name. */
const sameGame = (a, b) => (appKey(a) && appKey(a) === appKey(b)) || (nameKey(a) && nameKey(a) === nameKey(b));

/**
 * Groups of tracked games that are copies of one another: the same game
 * (App ID or name) or one folder inside another's. Cheap — no file listing —
 * so the library can say whether there is anything to check.
 */
export function duplicateGroups(games) {
  const list = (games ?? []).filter((g) => g?.id && g.savePath);
  const parent = new Map(list.map((g) => [g.id, g.id]));
  const find = (id) => (parent.get(id) === id ? id : (parent.set(id, find(parent.get(id))), parent.get(id)));
  const join = (a, b) => parent.set(find(a), find(b));
  for (let i = 0; i < list.length; i++) {
    for (let j = i + 1; j < list.length; j++) {
      const a = list[i];
      const b = list[j];
      if (sameGame(a, b) || isInside(a.savePath, b.savePath) || isInside(b.savePath, a.savePath)) join(a.id, b.id);
    }
  }
  const groups = new Map();
  for (const g of list) {
    const root = find(g.id);
    if (!groups.has(root)) groups.set(root, []);
    groups.get(root).push(g);
  }
  return [...groups.values()].filter((members) => members.length > 1);
}

/**
 * What to call one group in the list: the names of the games in it, each
 * once, in the group's order. A group joined by one folder holding others
 * (a repack directory, say) carries several games, and the dialog scrolls,
 * so each group needs a heading to be found by.
 */
export function groupTitle(members) {
  const names = [];
  for (const m of members ?? []) {
    const name = String(m?.game?.name ?? m?.name ?? '').trim();
    if (name && !names.includes(name)) names.push(name);
  }
  return names;
}

// A Steam App ID folder at the top of a save folder (repacks and Steam
// emulators keep one per game): digits, 3 to 8 of them.
const APP_ID_DIR = /^\d{3,8}$/;

/**
 * The App IDs of other games' folders at the top of a file listing. A folder
 * laid out per game holds several such folders; a single numbered folder is
 * more likely a game's own data (a slot, a version), so it does not count —
 * unless minFolders says otherwise (files already known to be left over).
 */
export function otherAppIds(game, files, minFolders = 2) {
  const own = appKey(game);
  const dirs = new Set();
  for (const f of files ?? []) {
    const first = String(f.path ?? '').split('/')[0];
    if (APP_ID_DIR.test(first) && String(f.path).includes('/')) dirs.add(first);
  }
  if (dirs.size < minFolders) return [];
  return [...dirs].filter((d) => d !== own).sort();
}

const fingerprint = (files) =>
  (files ?? [])
    .filter((f) => !f.location)
    .map((f) => `${f.path}|${f.sizeBytes}|${f.mtimeMs}`)
    .sort()
    .join('\n');

/**
 * What to do about each member of one group, given each member's file
 * listing (GET /api/games/{id}/save-files → files). Returns one entry per
 * member, in the group's order (allGames: every tracked game, for coverage):
 *   { game, verdict: 'redundant' | 'keep' | 'review', reasons: [{code, ...}],
 *     fileCount, latestMs }
 * reasons codes:
 *   insideOf {other}     — its folder is inside other's (kept: the specific one)
 *   covers {others}      — its folder holds the others' folders (redundant when
 *                          one of them is the same game)
 *   mixed {appIds}       — it holds other games' App ID folders
 *   sameFiles {other}    — exactly the same files as other
 *   newest               — the most recently written of the group
 *   different            — its own files, not found in any other member
 */
export function judgeGroup(members, filesById, allGames = []) {
  // Coverage counts every tracked folder, not only the group's: another game
  // tracked inside an outer folder keeps its files too.
  const tracked = [...members, ...allGames.filter((g) => !members.some((m) => m.id === g.id))];
  const info = members.map((g) => {
    const files = filesById?.[g.id] ?? [];
    return {
      game: g,
      files,
      fileCount: files.length,
      latestMs: files.reduce((m, f) => Math.max(m, f.mtimeMs ?? 0), 0),
      reasons: [],
      verdict: 'keep'
    };
  });
  const newest = Math.max(...info.map((i) => i.latestMs));

  for (const a of info) {
    const covered = info.filter((b) => b !== a && isInside(b.game.savePath, a.game.savePath));
    const inside = info.find((b) => b !== a && isInside(a.game.savePath, b.game.savePath));
    if (covered.length) a.reasons.push({ code: 'covers', others: covered.map((b) => b.game) });
    if (inside) a.reasons.push({ code: 'insideOf', other: inside.game });
    const mixed = otherAppIds(a.game, a.files);
    if (mixed.length) a.reasons.push({ code: 'mixed', appIds: mixed });
    if (covered.some((b) => sameGame(a.game, b.game))) {
      // The outer folder is redundant only when every file in it is kept by
      // some entry inside it. Stopping it would otherwise stop backing up
      // whatever else it holds — another game's saves, say.
      const prefixes = coveredPrefixes(a.game, tracked);
      const uncovered = a.files.filter((f) => !f.location && !prefixes.some((p) => String(f.path).toLowerCase().startsWith(p)));
      if (uncovered.length === 0) a.verdict = 'redundant';
      else {
        a.verdict = 'review';
        a.reasons.push({ code: 'uncovered', count: uncovered.length, appIds: otherAppIds(a.game, uncovered, 1) });
      }
    } else if (mixed.length) a.verdict = 'review';
  }

  // Same files in two separate folders: keep the first, the rest are copies.
  for (let i = 0; i < info.length; i++) {
    const a = info[i];
    if (a.verdict === 'redundant' || !a.fileCount) continue;
    for (let j = 0; j < i; j++) {
      const b = info[j];
      if (b.verdict === 'redundant' || !b.fileCount) continue;
      if (isInside(a.game.savePath, b.game.savePath) || isInside(b.game.savePath, a.game.savePath)) continue;
      if (fingerprint(a.files) === fingerprint(b.files)) {
        a.reasons.push({ code: 'sameFiles', other: b.game });
        a.verdict = 'redundant';
        break;
      }
    }
  }

  for (const a of info) {
    if (a.latestMs && a.latestMs === newest && info.filter((b) => b.latestMs === newest).length === 1) a.reasons.push({ code: 'newest' });
    if (a.verdict === 'keep' && !a.reasons.some((r) => r.code === 'insideOf' || r.code === 'sameFiles')) a.reasons.push({ code: 'different' });
  }
  return info.map(({ files, ...rest }) => rest);
}

/** Relative path prefixes (lower case, ending in /) of tracked folders inside `outer`. */
function coveredPrefixes(outer, games) {
  const base = norm(outer.savePath) + '/';
  return games.filter((g) => g.id !== outer.id && isInside(g.savePath, outer.savePath)).map((g) => norm(g.savePath).slice(base.length) + '/');
}

/**
 * Whether a game's folder holds a more specific copy of the same game that is
 * tracked on its own — then uploading this one sends those files twice.
 */
export function coveredCopy(game, allGames) {
  return (allGames ?? []).find((b) => b.id !== game.id && isInside(b.savePath, game.savePath) && sameGame(game, b)) ?? null;
}
