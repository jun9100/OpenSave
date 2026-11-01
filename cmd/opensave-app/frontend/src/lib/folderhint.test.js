import { describe, expect, it } from 'vitest';
import { folderHints } from './folderhint.js';

const bg3 = [
  { id: 'a', name: "Baldur's Gate 3", savePath: 'C:\\Users\\Public\\Documents\\Steam\\RUNE' },
  { id: 'b', name: "Baldur's Gate 3", savePath: "C:\\Users\\ron\\AppData\\Local\\Larian Studios\\Baldur's Gate 3\\PlayerProfiles\\Public\\Savegames\\Story" },
  { id: 'c', name: "Baldur's Gate 3", savePath: 'C:\\Users\\Public\\Documents\\Steam\\RUNE\\1086940\\remote' },
  { id: 'd', name: 'Elden Ring', savePath: 'C:\\Users\\ron\\AppData\\Roaming\\EldenRing' }
];

describe('folderHints', () => {
  it('names the folder of each copy of a shared name, at least two folders deep', () => {
    const h = folderHints(bg3);
    expect(h.get('a')).toBe('…\\Steam\\RUNE');
    expect(h.get('b')).toBe('…\\Savegames\\Story');
    expect(h.get('c')).toBe('…\\1086940\\remote');
  });

  it('gives nothing to a game whose name is its own', () => {
    expect(folderHints(bg3).has('d')).toBe(false);
  });

  it('goes deeper until the copies read differently', () => {
    const h = folderHints([
      { id: 'x', name: 'Hades', savePath: '/home/a/Saves/Hades/profile' },
      { id: 'y', name: 'hades', savePath: '/mnt/b/Saves/Hades/profile' }
    ]);
    expect(h.get('x')).toBe('…/a/Saves/Hades/profile');
    expect(h.get('y')).toBe('…/b/Saves/Hades/profile');
  });

  it('keeps a long hint short', () => {
    const deep = 'D:\\' + 'very-long-folder-name\\'.repeat(6);
    const h = folderHints([
      { id: 'p', name: 'G', savePath: deep + 'one' },
      { id: 'q', name: 'G', savePath: deep + 'two' }
    ]);
    expect(h.get('p').length).toBeLessThanOrEqual(48);
    expect(h.get('p').endsWith('one')).toBe(true);
  });
});
