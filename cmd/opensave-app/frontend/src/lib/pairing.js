// Pairing this device with game-save, and the status line the settings page
// shows afterwards. The dialog only draws these; keeping the request shapes
// and status wording here makes both testable without a Svelte runtime.
import { api } from './api.js';

export function isPairLink(text) {
  return typeof text === 'string' && text.trim().toLowerCase().startsWith('savevault://');
}

/** The body for /api/cloud/pair/preview. A pasted link wins over the fields,
 *  and the address field may itself hold a whole link (paste from a QR code). */
export function previewRequest({ server = '', code = '', link = '' } = {}) {
  const pasted = typeof link === 'string' ? link.trim() : '';
  if (pasted) return { link: pasted };
  const address = typeof server === 'string' ? server.trim() : '';
  if (isPairLink(address)) return { link: address };
  return { server: address, code: typeof code === 'string' ? code.trim() : '' };
}

export const previewPairing = (input) => api.post('/api/cloud/pair/preview', previewRequest(input));

export const claimPairing = ({ server, code, confirmedHost }) =>
  api.post('/api/cloud/pair/claim', { server, code, confirmedHost });

// Account sign-in: the password is sent once to the local daemon, which trades
// it for the tenant token at the service. Nothing else changes downstream.
export const loginAccount = ({ server, username, password, confirmedHost }) =>
  api.post('/api/cloud/login', { server, username, password, confirmedHost });

export const getAuthStatus = () => api.get('/api/cloud/auth-status');

export const dismissAuthWarning = () => api.post('/api/cloud/auth-warning/dismiss');

/** Turn the daemon's auth-status answer into one line for the settings page.
 *  `showPair` means there is a way forward the user should be offered. */
export function authStatusView(status) {
  switch (status?.state) {
    case 'device':
      return {
        tone: 'ok',
        key: 'cloud.auth.device',
        params: { name: String(status.name ?? '').trim() || String(status.tokenId ?? '') },
        showPair: false
      };
    case 'shared':
      return { tone: 'info', key: 'cloud.auth.shared', params: {}, showPair: true };
    case 'revoked':
      return { tone: 'error', key: 'cloud.auth.revoked', params: {}, showPair: true };
    case 'sharedDisabled':
      return { tone: 'error', key: 'cloud.auth.sharedDisabled', params: {}, showPair: true };
    case 'unauthorized':
      return { tone: 'error', key: 'cloud.auth.unauthorized', params: {}, showPair: true };
    case 'unsupported':
      return { tone: 'info', key: 'cloud.auth.unsupported', params: {}, showPair: false };
    case 'error':
      return {
        tone: 'warn',
        key: 'cloud.auth.error',
        params: { error: String(status.error ?? '') },
        showPair: false
      };
    default:
      return { tone: 'none', key: '', params: {}, showPair: false };
  }
}
