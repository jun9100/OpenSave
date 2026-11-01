// Which folder a game is, for games that share a name.
//
// The same game is often tracked more than once — a launcher's save folder
// and a repack's, or a whole emulator-style folder and one title inside it —
// and every copy carries the same name and the same cover art, so the library
// showed identical tiles with nothing to tell them apart. For each name used
// more than once this gives the end of each copy's path, as few folders as
// tell them apart (never fewer than two, since one folder name like "remote"
// or "Story" says little on its own). Games with a name of their own get none.

const MIN_SEGMENTS = 2;
const MAX_LENGTH = 48;

const segmentsOf = (path) =>
  String(path ?? '')
    .split(/[\\/]+/)
    .filter(Boolean);

/** Map of game id → short folder hint, only for games whose name is shared. */
export function folderHints(games) {
  const byName = new Map();
  for (const g of games ?? []) {
    const key = String(g?.name ?? '').trim().toLowerCase();
    if (!byName.has(key)) byName.set(key, []);
    byName.get(key).push(g);
  }
  const hints = new Map();
  for (const group of byName.values()) {
    if (group.length < 2) continue;
    const parts = group.map((g) => segmentsOf(g.savePath));
    const sep = (g) => (String(g.savePath ?? '').includes('\\') ? '\\' : '/');
    const longest = Math.max(...parts.map((p) => p.length));
    let k = MIN_SEGMENTS;
    // Grow the suffix until every copy reads differently, or the paths run out.
    while (k < longest && new Set(parts.map((p) => p.slice(-k).join('/').toLowerCase())).size < group.length) k++;
    group.forEach((g, i) => {
      const p = parts[i];
      let hint = p.slice(-k).join(sep(g));
      if (p.length > k) hint = '…' + sep(g) + hint;
      if (hint.length > MAX_LENGTH) hint = '…' + hint.slice(-(MAX_LENGTH - 1));
      if (hint) hints.set(g.id, hint);
    });
  }
  return hints;
}
