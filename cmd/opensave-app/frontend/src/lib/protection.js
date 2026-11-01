// What protects traffic with one paired device, and how to say it.
//
// Kept out of the component so it can be tested. The wording matters as much
// as the logic here: this is the text that tells someone whether the saves
// they sync over the internet are readable by whoever else holds their room
// code, and a badge that overstates the protection is worse than no badge,
// because it converts a missing safeguard into a false assurance.
//
// The fields come from the peers API; see PeerProtection in
// internal/p2p/payloadseal.go. The condition for "encrypted" is deliberately
// the same one the send path applies, so the two cannot drift apart.
import { tt } from './i18n.js';

/**
 * @param {object} peer A peer entry from the peers payload.
 * @returns {'sealed'|'pending'|'open'|'direct'}
 */
export function protectionState(peer) {
  // Fall back to the address when the daemon predates these fields, so an
  // older build shows the neutral local-connection wording rather than
  // claiming a relay pairing is encrypted.
  const overRelay = peer?.overRelay ?? peer?.address === 'relay';
  if (!overRelay) return 'direct';
  if (peer?.encrypted) return 'sealed';
  return peer?.hasKey ? 'pending' : 'open';
}

// Getters, so each label is read in the language chosen at that moment.
export const PROTECTION_LABELS = {
  get sealed() { return tt('protection.label.sealed'); },
  get pending() { return tt('protection.label.pending'); },
  get open() { return tt('protection.label.open'); },
  get direct() { return tt('protection.label.direct'); },
};

export const PROTECTION_ICONS = {
  sealed: '🔒',
  pending: '🔐',
  open: '🔓',
  direct: '🖧',
};

export const PROTECTION_DETAILS = {
  get sealed() { return tt('protection.detail.sealed'); },
  get pending() { return tt('protection.detail.pending'); },
  get open() { return tt('protection.detail.open'); },
  get direct() { return tt('protection.detail.direct'); },
};

/**
 * Summarises the devices paired through a relay, for the room panel.
 * @param {object[]} peers Every paired peer.
 */
export function roomProtection(peers) {
  const relayPeers = (peers ?? []).filter((p) => protectionState(p) !== 'direct');
  const encrypted = relayPeers.filter((p) => protectionState(p) === 'sealed');
  // Only devices with no key are actionable: 'pending' resolves itself, and
  // telling someone to re-pair over a state that clears in seconds would send
  // them through an unpair for nothing.
  const unprotected = relayPeers.filter((p) => protectionState(p) === 'open');
  return { relayPeers, encrypted, unprotected };
}
