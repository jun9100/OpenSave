import { describe, expect, it } from 'vitest';

import en from '../locales/en.json';
import zhCN from '../locales/zh-CN.json';
import { LOCALES, detectLocale, pluralRulesFor, sanitizeLocale, translate } from './i18n.js';

// Walk the leaf keys of a catalog (a plural value is one leaf).
function leafKeys(obj, prefix = '') {
  const keys = [];
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v !== null && typeof v === 'object') keys.push(...leafKeys(v, key));
    else keys.push(key);
  }
  return keys;
}

describe('locale catalogs', () => {
  it('declares every catalog id in LOCALES', () => {
    for (const id of ['en', 'zh-CN']) expect(LOCALES[id], id).toBeTruthy();
  });

  it('has no keys in zh-CN that en.json does not have', () => {
    const enKeys = new Set(leafKeys(en));
    for (const key of leafKeys(zhCN)) expect(enKeys.has(key), `extra key: ${key}`).toBe(true);
  });

  it('has a zh-CN value for every key in en.json', () => {
    const zhKeys = new Set(leafKeys(zhCN));
    for (const key of leafKeys(en)) expect(zhKeys.has(key), `missing key: ${key}`).toBe(true);
  });

  it('keeps every en value non-empty', () => {
    for (const key of leafKeys(en)) {
      const v = en[key];
      expect(typeof v === 'string' || typeof v === 'object', key).toBe(true);
      if (typeof v === 'string') expect(v.trim().length, key).toBeGreaterThan(0);
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

describe('locale choice', () => {
  it('rejects unknown locale ids', () => {
    expect(sanitizeLocale('fr')).toBe(null);
    expect(sanitizeLocale('zh-CN')).toBe('zh-CN');
  });

  it('prefers the saved choice over the system language', () => {
    const storage = { getItem: () => 'en' };
    expect(detectLocale(storage, 'zh-CN')).toBe('en');
  });

  it('maps a Chinese system language to zh-CN when nothing is saved', () => {
    expect(detectLocale({ getItem: () => null }, 'zh-CN')).toBe('zh-CN');
    expect(detectLocale({ getItem: () => null }, 'en-US')).toBe('en');
  });

  it('survives storage that refuses to read', () => {
    const storage = { getItem: () => { throw new Error('blocked'); } };
    expect(detectLocale(storage, 'zh-CN')).toBe('zh-CN');
  });
});
