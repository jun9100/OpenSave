// Pure helpers for restoring a save from another device: which members are
// shown, which local locations a cloud location may be restored to, and the
// assignments the apply call expects. Kept out of the components so the
// mapping rules can be tested without a browser.

/** Members newest first. A member without a usable time sorts last, and the
 *  input array is left as it was. */
export function sortMembers(members) {
  const time = (member) => {
    if (!member?.lastVersionAt) return NaN;
    const ms = new Date(member.lastVersionAt).getTime();
    return Number.isNaN(ms) ? NaN : ms;
  };
  return [...(members ?? [])].sort((a, b) => {
    const at = time(a);
    const bt = time(b);
    if (Number.isNaN(at) && Number.isNaN(bt)) return 0;
    if (Number.isNaN(at)) return 1;
    if (Number.isNaN(bt)) return -1;
    return bt - at;
  });
}

/** The platform as people know it, not the provider's id. Unknown ids are
 *  shown as they came, which is still more useful than a blank. */
export function platformLabel(platform) {
  const value = platform ?? '';
  if (value === 'android' || value.startsWith('opensave-android')) return 'Android';
  if (value === 'opensave-windows') return 'Windows';
  if (value === 'opensave-darwin') return 'macOS';
  if (value === 'opensave-linux') return 'Linux';
  return value;
}

/** A member's device name, falling back to the scope name and then its id. */
export function memberTitle(member) {
  return member?.deviceName || member?.name || member?.gameId || '';
}

/** Per cloud location, the local root to restore it to, or null to skip.
 *  Suggested pairs are only a starting point; everything else starts skipped.
 *  An empty root is the primary location and must stay an empty string. */
export function initialChoices(plan) {
  const choices = {};
  for (const location of plan?.archiveLocations ?? []) choices[location.id] = null;
  for (const suggestion of plan?.suggested ?? []) {
    if (suggestion && Object.prototype.hasOwnProperty.call(choices, suggestion.archiveLocation)) {
      choices[suggestion.archiveLocation] = suggestion.localRoot ?? null;
    }
  }
  return choices;
}

/** The apply call's assignments: chosen pairs only, in a stable order. */
export function assignmentsFrom(choices) {
  return Object.entries(choices ?? {})
    .filter(([, localRoot]) => localRoot !== null && localRoot !== undefined)
    .map(([archiveLocation, localRoot]) => ({ archiveLocation, localRoot }))
    .sort((a, b) => (a.archiveLocation < b.archiveLocation ? -1 : a.archiveLocation > b.archiveLocation ? 1 : 0));
}

/** The first reason these choices cannot be applied, as an i18n key, or ''.
 *  Checked here rather than in the dialog so the same rules are testable. */
export function validateChoices(plan, choices) {
  const chosen = Object.entries(choices ?? {}).filter(([, root]) => root !== null && root !== undefined);
  if (chosen.length === 0) return 'game.cloud.others.err_none';
  const roots = chosen.map(([, root]) => root);
  if (new Set(roots).size !== roots.length) return 'game.cloud.others.err_duplicate';
  const available = new Set((plan?.localLocations ?? []).map((location) => location.root));
  for (const root of roots) if (!available.has(root)) return 'game.cloud.others.err_unknown';
  return '';
}

/** The local location's own name; the path is appended by the caller. */
export function localLabel(location, t) {
  return location?.root === '' ? t('game.cloud.others.primary_location') : location?.root ?? '';
}
