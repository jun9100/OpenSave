// A game's cloud scope can be deleted or merged into another one on the
// server (C6). Either leaves a tombstone: the scope answers 410 from then on,
// this device stops syncing it, and the user is asked what to do — once, in
// the banner, and again on the game's Cloud tab until it is settled.
//
// These are the words and the calls for that; the components only draw them.
import { api } from './api.js';
import { fmtTime } from './format.js';

const DELETED = 'scope_deleted';
const RETIRED = 'scope_retired';

/** Whether a GameSaveStatus.state (or a gone record's code) is a tombstone
 *  rather than a sync failure. */
export function isScopeGoneState(state) {
  return state === DELETED || state === RETIRED;
}

// A gone record carries `code`; a GameSaveStatus carries `state`. Accept
// either so a caller holding one does not have to know which.
const codeOf = (item) => item?.code ?? item?.state ?? '';

const successorOf = (item) => item?.successorName || item?.successorGameId || '';

/**
 * How a gone scope is said: the sentence, its placeholders, the actions to
 * offer, and the extra lines that only apply in some cases.
 * @param {object} item A gone record, or a GameSaveStatus whose state is gone.
 * @returns {{key: string, params: object, actions: string[], hintKey?: string, refusedKey?: string}}
 */
export function goneNotice(item) {
  // A gone record, or a GameSaveStatus carrying one under `gone`.
  const info = item?.gone ?? item;
  const retired = (codeOf(info) || codeOf(item)) === RETIRED;
  const notice = retired
    ? { key: 'cloud.gone.retired', params: { successor: successorOf(info) }, actions: ['untrack', 'later'] }
    : { key: 'cloud.gone.deleted', params: { time: fmtTime(info?.at) }, actions: ['off', 'later'] };
  // This device tracking the successor too is the duplicate the merge left
  // behind; stopping this entry is the obvious thing to suggest.
  if (retired && info?.successorLocalGameId) notice.hintKey = 'cloud.gone.retiredDuplicate';
  // "Resume" was tried and the server still has the tombstone: say what has
  // to happen on the web page first.
  if (info?.resumeRefused) {
    notice.refusedKey = retired ? 'cloud.gone.resumeRefusedRetired' : 'cloud.gone.resumeRefusedDeleted';
  }
  return notice;
}

/** The entries still waiting for a decision, minus those put off this run. */
export function pendingGone(items, hiddenIds = new Set()) {
  return (items ?? []).filter((item) => item?.notice === 'pending' && !hiddenIds.has(item.gameId));
}

/** The heading for a game whose cloud scope is gone. */
export function goneStatusLabel(status) {
  const gone = status?.gone ?? status;
  // A gone record carries the code; a bare status carries the state.
  const code = codeOf(gone) || codeOf(status);
  if (code === RETIRED) {
    return { key: 'cloud.gone.statusRetired', params: { successor: successorOf(gone) } };
  }
  return { key: 'cloud.gone.statusDeleted' };
}

/** Every game whose cloud scope is gone, settled or not. */
export const listScopeGone = () => api.get('/api/cloud/scope-gone');

/** "Stop syncing this game": leave the cloud alone, stop asking about it. */
export const stopSyncingGone = (gameId) => api.post(`/api/cloud/scope-gone/${encodeURIComponent(gameId)}/off`);

/** "Resume cloud sync": drop the record and read the scope again. A 409 means
 *  the server still has the tombstone (the error says which). */
export const resumeGone = (gameId) => api.post(`/api/cloud/scope-gone/${encodeURIComponent(gameId)}/resume`);
