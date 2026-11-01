// Keyboard shortcuts: which keys do what, in one list, so the shortcuts
// sheet shows exactly what the handler does and cannot drift from it.
import { writable } from 'svelte/store';
import { tt } from './i18n.js';

/** Whether the Ctrl+K palette and the shortcuts list are open — stores, so
 *  a button elsewhere (the status bar's) can open them too. */
export const paletteOpen = writable(false);
export const shortcutsOpen = writable(false);

// Pages in the order of the sidebar, for Ctrl+1 … Ctrl+6. The labels are
// getters, so they are in the language chosen when they are read.
export const PAGES = [
  { id: 'home', get label() { return tt('shortcuts.page.home'); } },
  { id: 'devices', get label() { return tt('shortcuts.page.devices'); } },
  { id: 'cloud', get label() { return tt('shortcuts.page.cloud'); } },
  { id: 'activity', get label() { return tt('shortcuts.page.activity'); } },
  { id: 'settings', get label() { return tt('shortcuts.page.settings'); } },
  { id: 'help', get label() { return tt('shortcuts.page.help'); } },
  { id: 'changelog', get label() { return tt('shortcuts.page.changelog'); } }
];

/** A [keys, what they do] pair whose second half is worked out when read. */
function row(keys, describe) {
  const r = [keys, ''];
  Object.defineProperty(r, 1, { get: describe, enumerable: true });
  return r;
}

/** For the sheet: [keys, what they do], in the order shown. */
export const SHORTCUTS = [
  row('Ctrl K', () => tt('shortcuts.jump')),
  row('Ctrl F', () => tt('shortcuts.find')),
  ...PAGES.map((p, i) => row(`Ctrl ${i + 1}`, () => tt('shortcuts.goTo', { page: p.label }))),
  row('Ctrl ,', () => tt('shortcuts.settings')),
  row('← → ↑ ↓', () => tt('shortcuts.moveGames')),
  row('Shift F10', () => tt('shortcuts.gameActions')),
  row('?', () => tt('shortcuts.thisList')),
  row('Esc', () => tt('shortcuts.close'))
];

/** Whether keys pressed now are someone typing, which a bare key must not interrupt. */
export function isTyping(target) {
  if (!target) return false;
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || !!target.isContentEditable;
}

/**
 * What a keydown means, or null. Ctrl works as Cmd on a Mac. Combinations
 * work while typing — Ctrl+K from a search box is the point of it — but a
 * bare "?" typed into a field is a question mark.
 */
export function matchShortcut(e) {
  const mod = e.ctrlKey || e.metaKey;
  if (mod && !e.altKey) {
    const k = e.key.toLowerCase();
    if (k === 'k') return 'palette';
    if (k === 'f') return 'find';
    if (k === ',') return 'go:settings';
    const n = Number(e.key);
    if (Number.isInteger(n) && n >= 1 && n <= PAGES.length && !e.shiftKey) return `go:${PAGES[n - 1].id}`;
    return null;
  }
  if (e.key === '?' && !e.ctrlKey && !e.metaKey && !e.altKey && !isTyping(e.target)) return 'help';
  return null;
}
