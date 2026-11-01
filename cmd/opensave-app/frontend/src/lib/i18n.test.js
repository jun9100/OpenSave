import { describe, expect, it } from 'vitest';

import en from '../locales/en.json';
import zhCN from '../locales/zh-CN.json';
import { LOCALES, loadLocale, pluralRulesFor, resolveLocale, sanitizeLocale, segments, translate } from './i18n.js';

// The catalogs are flat, dot-keyed objects; a plural value is one value that
// must expose an "other" form. Comparing top-level keys is enough.
const keysOf = (catalog) => Object.keys(catalog);

describe('locale catalogs', () => {
  it('declares every catalog id in LOCALES', () => {
    for (const id of ['en', 'zh-CN']) expect(LOCALES[id], id).toBeTruthy();
  });

  it('has no keys in zh-CN that en.json does not have', () => {
    const enKeys = new Set(keysOf(en));
    for (const key of keysOf(zhCN)) expect(enKeys.has(key), `extra key: ${key}`).toBe(true);
  });

  it('has a zh-CN value for every key in en.json', () => {
    const zhKeys = new Set(keysOf(zhCN));
    for (const key of keysOf(en)) expect(zhKeys.has(key), `missing key: ${key}`).toBe(true);
  });

  it('keeps every value non-empty, and plurals with an "other" form', () => {
    for (const [catalog, name] of [[en, 'en'], [zhCN, 'zh-CN']]) {
      for (const [key, v] of Object.entries(catalog)) {
        if (v !== null && typeof v === 'object') {
          expect(typeof v.other, `${name}:${key} plural needs "other"`).toBe('string');
          expect(v.other.trim().length, `${name}:${key}`).toBeGreaterThan(0);
        } else {
          expect(typeof v, `${name}:${key}`).toBe('string');
          expect(v.trim().length, `${name}:${key}`).toBeGreaterThan(0);
        }
      }
    }
  });
});

describe('translate', () => {
  const rules = pluralRulesFor('en');

  it('returns the key itself when nothing has it', () => {
    expect(translate({}, 'no.such.key', undefined, rules)).toBe('no.such.key');
  });

  it('falls back to English when the locale lacks the key', () => {
    expect(translate({ other: 'x' }, 'home.title', undefined, rules)).toBe('Home');
  });

  it('fills {params}', () => {
    expect(translate({ k: '{game}: got {count} files' }, 'k', { game: 'Hades', count: 7 }, rules)).toBe('Hades: got 7 files');
  });

  it('selects plural categories and always keeps an "other"', () => {
    const catalog = { k: { one: '{n} snapshot', other: '{n} snapshots' } };
    expect(translate(catalog, 'k', { n: 1 }, rules)).toBe('1 snapshot');
    expect(translate(catalog, 'k', { n: 3 }, rules)).toBe('3 snapshots');
    expect(translate(catalog, 'k', undefined, rules)).toBe('0 snapshots');
  });
});

describe('segments', () => {
  it('splits a template into plain and bold parts, in order', () => {
    expect(segments('open {a} then {b}.', { a: '设备', b: '云备份' }, ['b'])).toEqual([
      { text: 'open ', bold: false },
      { text: '设备', bold: false },
      { text: ' then ', bold: false },
      { text: '云备份', bold: true },
      { text: '.', bold: false }
    ]);
  });
});

describe('locale choice', () => {
  it('rejects unknown locale ids', () => {
    expect(sanitizeLocale('fr')).toBe(null);
    expect(sanitizeLocale('zh-CN')).toBe('zh-CN');
    expect(sanitizeLocale('system')).toBe('system');
  });

  it("reports 'system' when the user has never picked one", () => {
    expect(loadLocale({ getItem: () => null })).toBe('system');
    expect(loadLocale({ getItem: () => 'zh-CN' })).toBe('zh-CN');
  });

  it('resolves system against the OS language, keeps explicit picks', () => {
    expect(resolveLocale('system', 'zh-CN')).toBe('zh-CN');
    expect(resolveLocale('system', 'en-US')).toBe('en');
    expect(resolveLocale('system', '')).toBe('en');
    expect(resolveLocale('zh-CN', 'en-US')).toBe('zh-CN');
  });

  it('survives storage that refuses to read', () => {
    const storage = { getItem: () => { throw new Error('blocked'); } };
    expect(loadLocale(storage)).toBe('system');
  });
});

describe('source keys', () => {
  // Every literal key the app looks up must be in the catalog, or the screen
  // shows the key itself. Read from the sources as text, so a key added to a
  // component without its catalog entry fails here rather than on screen.
  const sources = import.meta.glob(['../**/*.svelte', '../**/*.js', '!../**/*.test.js'], { query: '?raw', import: 'default', eager: true });
  const used = new Map();
  for (const [file, code] of Object.entries(sources)) {
    for (const m of code.matchAll(/(?:\$t|\btt|\bget\(t\))\(\s*(['"])([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9_]+)+)\1/g)) {
      if (!used.has(m[2])) used.set(m[2], file);
    }
  }

  it('finds the lookups to check', () => {
    expect(used.size).toBeGreaterThan(1000);
  });

  it('has an English entry for every key the sources use', () => {
    const missing = [...used].filter(([key]) => !(key in en)).map(([key, file]) => `${key} (${file})`);
    expect(missing).toEqual([]);
  });

  it('never puts a placeholder in Chinese that English does not fill', () => {
    const names = (v) => new Set([...(typeof v === 'string' ? v : Object.values(v).join(' ')).matchAll(/\{(\w+)\}/g)].map((m) => m[1]));
    const bad = Object.entries(zhCN).filter(([key, v]) => [...names(v)].some((p) => !names(en[key]).has(p)));
    expect(bad.map(([k]) => k)).toEqual([]);
  });
});
