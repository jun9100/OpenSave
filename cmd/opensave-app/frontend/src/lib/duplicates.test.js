import { describe, expect, it } from 'vitest';
import { coveredCopy, duplicateGroups, groupTitle, isInside, judgeGroup, otherAppIds } from './duplicates.js';

const g = (id, name, savePath, appId = '') => ({ id, name, savePath, appId });
const f = (path, mtimeMs = 1, sizeBytes = 10) => ({ path, mtimeMs, sizeBytes, location: '' });
const byId = (rows) => Object.fromEntries(rows.map((r) => [r.game.id, r]));

describe('isInside', () => {
  it('is about whole folder names, either slash, any case', () => {
    expect(isInside('C:\\Saves\\RUNE\\1086940', 'c:/saves/rune')).toBe(true);
    expect(isInside('C:\\Saves\\RUNE2', 'C:\\Saves\\RUNE')).toBe(false);
    expect(isInside('C:\\Saves', 'C:\\Saves\\')).toBe(false);
  });
});

describe('duplicateGroups', () => {
  it('groups the same game and folders inside one another, and nothing else', () => {
    const games = [
      g('a', 'Hades', 'C:/x/Hades', '1145360'),
      g('b', 'Hades', 'D:/other/Hades', '1145360'),
      g('c', 'Repack', 'C:/repack'),
      g('d', 'Elden Ring', 'C:/repack/1245620/remote', '1245620'),
      g('e', 'Celeste', 'C:/celeste')
    ];
    const groups = duplicateGroups(games).map((grp) => grp.map((x) => x.id).sort());
    expect(groups).toEqual(expect.arrayContaining([['a', 'b'], ['c', 'd']]));
    expect(groups.flat()).not.toContain('e');
  });
});

describe('otherAppIds', () => {
  it('names other games in a per-game folder, but not a lone numbered folder', () => {
    const game = g('a', 'X', 'C:/codex', '1551360');
    expect(otherAppIds(game, [f('1551360/remote/a'), f('374320/remote/b')])).toEqual(['374320']);
    expect(otherAppIds(game, [f('1638/data.bin'), f('profile.dat')])).toEqual([]);
  });
});

describe('judgeGroup', () => {
  it('calls an outer folder redundant when a copy inside it keeps every file', () => {
    const outer = g('o', 'Star Ocean', 'C:/My Games/SO', '2238900');
    const inner = g('i', 'Star Ocean', 'C:/My Games/SO/Steam/7656', '2238900');
    const r = byId(judgeGroup([outer, inner], { o: [f('Steam/7656/save1'), f('Steam/7656/save2')], i: [f('save1'), f('save2')] }));
    expect(r.o.verdict).toBe('redundant');
    expect(r.i.verdict).toBe('keep');
    expect(r.i.reasons.map((x) => x.code)).toContain('insideOf');
  });

  it('asks instead when the outer folder also holds files nothing else keeps', () => {
    const outer = g('o', 'Forza Horizon 5', 'C:/Steam/CODEX', '1551360');
    const inner = g('i', 'Forza Horizon 5', 'C:/Steam/CODEX/1551360/remote', '1551360');
    const r = byId(
      judgeGroup([outer, inner], {
        o: [f('1551360/remote/user.profile'), f('374320/remote/DS30000.sl2')],
        i: [f('user.profile')]
      })
    );
    expect(r.o.verdict).toBe('review');
    const uncovered = r.o.reasons.find((x) => x.code === 'uncovered');
    expect(uncovered).toMatchObject({ count: 1, appIds: ['374320'] });
  });

  it('counts a game tracked outside the group as keeping its files', () => {
    const outer = g('o', 'Forza Horizon 5', 'C:/Steam/CODEX', '1551360');
    const inner = g('i', 'Forza Horizon 5', 'C:/Steam/CODEX/1551360/remote', '1551360');
    const ds3 = g('d', 'DARK SOULS III', 'C:/Steam/CODEX/374320/remote', '374320');
    const files = { o: [f('1551360/remote/a'), f('374320/remote/b')], i: [f('a')], d: [f('b')] };
    expect(byId(judgeGroup([outer, inner], files, [outer, inner, ds3])).o.verdict).toBe('redundant');
  });

  it('keeps two different folders of one game, and marks the newest', () => {
    const steam = g('s', 'Beholder', 'C:/steam/userdata/1/475550', '475550');
    const own = g('w', 'Beholder', 'C:/Documents/Beholder/Saves', '475550');
    const r = byId(judgeGroup([steam, own], { s: [f('remote/a', 5)], w: [f('slot1', 9), f('slot2', 3)] }));
    expect(r.s.verdict).toBe('keep');
    expect(r.w.verdict).toBe('keep');
    expect(r.w.reasons.map((x) => x.code)).toEqual(expect.arrayContaining(['newest', 'different']));
  });

  it('calls a second folder with exactly the same files a copy', () => {
    const a = g('a', 'Hades', 'C:/a/Hades');
    const b = g('b', 'Hades', 'D:/b/Hades');
    const same = [f('Profile1.sav', 7, 100)];
    const r = byId(judgeGroup([a, b], { a: same, b: same.map((x) => ({ ...x })) }));
    expect(r.a.verdict).toBe('keep');
    expect(r.b.verdict).toBe('redundant');
    expect(r.b.reasons[0]).toMatchObject({ code: 'sameFiles', other: a });
  });
});

describe('coveredCopy', () => {
  it('finds a more specific copy of the same game inside a folder', () => {
    const outer = g('o', "Baldur's Gate 3", 'C:/RUNE', '1086940');
    const inner = g('i', "Baldur's Gate 3", 'C:/RUNE/1086940/remote', '1086940');
    const other = g('x', 'Elden Ring', 'C:/RUNE/1245620/remote', '1245620');
    expect(coveredCopy(outer, [outer, inner, other])).toBe(inner);
    expect(coveredCopy(inner, [outer, inner, other])).toBe(null);
  });
});

describe('groupTitle', () => {
  it('names each game in a group once, in order', () => {
    const rune = g('r', "Baldur's Gate 3", 'C:/Steam/RUNE', '1086940');
    const bg3 = g('b', "Baldur's Gate 3", 'C:/Steam/RUNE/1086940/remote', '1086940');
    const lc2 = g('l', 'Lost Castle 2', 'C:/Steam/RUNE/2445690/remote', '2445690');
    expect(groupTitle([rune, bg3, lc2])).toEqual(["Baldur's Gate 3", 'Lost Castle 2']);
    // Judged rows carry the game under .game.
    expect(groupTitle(judgeGroup([rune, bg3], {}).map((r) => r))).toEqual(["Baldur's Gate 3"]);
    expect(groupTitle([])).toEqual([]);
  });

  it('keeps the third group of the AIO library findable: Forza between Beholder and the rest', () => {
    const list = [
      g('baldur-s-gate-3', "Baldur's Gate 3", 'C:/Users/Public/Documents/Steam/RUNE', '1086940'),
      g('baldur-s-gate-3-2', "Baldur's Gate 3", 'C:/Users/Public/Documents/Steam/RUNE/1086940/remote', '1086940'),
      g('beholder', 'Beholder', 'C:/steam/userdata/1/475550', '475550'),
      g('beholder-2', 'Beholder', 'C:/Documents/Beholder/Saves', '475550'),
      g('forza-horizon-5', 'Forza Horizon 5', 'C:\\Users\\Public\\Documents\\Steam\\CODEX', '1551360'),
      g('forza-horizon-5-2', 'Forza Horizon 5', 'C:\\Users\\Public\\Documents\\Steam\\CODEX\\1551360\\remote', '1551360'),
      g('dark-souls-iii', 'DARK SOULS III', 'C:/Users/ron/AppData/Roaming/DarkSoulsIII/0110000100000666', '374320')
    ];
    expect(duplicateGroups(list).map(groupTitle)).toEqual([["Baldur's Gate 3"], ['Beholder'], ['Forza Horizon 5']]);
  });
});
