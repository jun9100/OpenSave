/** The tracked game a refused "track this folder" overlaps, read from the
 *  daemon's message (`…overlaps a tracked save location: "Name" at path`);
 *  null for any other error. */
export function overlapOf(message) {
  const m = /overlaps a tracked save location: "((?:[^"\\]|\\.)*)" at (.+)$/.exec(String(message ?? ''));
  if (!m) return null;
  return { name: m[1].replace(/\\(.)/g, '$1'), path: m[2] };
}
