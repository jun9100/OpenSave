// A tiny, dependency-free i18n layer. `t` is a Svelte store whose value is a
// translate function: in a component, `$t('home.title')` re-renders when the
// user switches language, with no framework wiring.
//
// Catalogs live in src/locales/ as flat, dot-keyed JSON. `en.json` is the
// source of truth: any key missing from another locale falls back to English,
// so a partial translation never shows a blank. A plural value is an object
// keyed by Intl.PluralRules categories (`{ "one": …, "other": … }`); Chinese
// and other single-form languages just use a string.
import { writable, derived } from 'svelte/store';

import en from '../locales/en.json';
import zhCN from '../locales/zh-CN.json';

export const LOCALES = { en: 'English', 'zh-CN': '简体中文' };

const CATALOGS = { en, 'zh-CN': zhCN };
const FALLBACK = 'en';
const KEY = 'opensave.locale';

/** A locale id if valid, otherwise null. */
export function sanitizeLocale(raw) {
  return typeof raw === 'string' && raw in LOCALES ? raw : null;
}

/** The saved choice if any, else what the system asks for, else English. */
export function detectLocale(storage = globalThis.localStorage, language = globalThis.navigator?.language) {
  try {
    const saved = sanitizeLocale(storage?.getItem(KEY));
    if (saved) return saved;
  } catch {
    // Storage refused (private mode): fall through to the system language.
  }
  return (language ?? '').toLowerCase().startsWith('zh') ? 'zh-CN' : FALLBACK;
}

export function loadLocale(storage = globalThis.localStorage) {
  return detectLocale(storage);
}

export function saveLocale(l, storage = globalThis.localStorage) {
  try {
    storage?.setItem(KEY, l);
  } catch {
    // Storage refused: the choice lasts until restart.
  }
}

/** The chosen language. Setting it persists immediately. */
export const locale = writable(loadLocale());
locale.subscribe((l) => saveLocale(l));

/** Plural categories for a locale, as Intl.PluralRules sees them. */
export function pluralRulesFor(l) {
  try {
    return new Intl.PluralRules(l);
  } catch {
    return new Intl.PluralRules(FALLBACK);
  }
}

/** Look a key up with English fallback, resolve plurals, fill {params}. */
export function translate(catalog, key, params, rules) {
  let entry = catalog[key];
  if (entry === undefined) entry = CATALOGS[FALLBACK][key];
  if (entry === undefined) return key;
  if (entry !== null && typeof entry === 'object') {
    const category = rules.select(Number(params?.n ?? 0));
    entry = entry[category] ?? entry.other ?? key;
    // A plural template always mentions {n}; fill it even when the caller
    // passed no params at all.
    params = { n: 0, ...params };
  }
  if (params) {
    for (const [name, value] of Object.entries(params)) {
      entry = entry.replaceAll(`{${name}}`, String(value));
    }
  }
  return entry;
}

/** The translate function, rebound whenever the language changes. */
export const t = derived(locale, (l) => {
  const catalog = CATALOGS[l] ?? {};
  const rules = pluralRulesFor(l);
  return (key, params) => translate(catalog, key, params, rules);
});
