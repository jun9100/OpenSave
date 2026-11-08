import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./api.js', () => ({
  api: { get: vi.fn(), post: vi.fn() }
}));

import { api } from './api.js';
import {
  authStatusView,
  claimPairing,
  dismissAuthWarning,
  getAuthStatus,
  isPairLink,
  previewPairing,
  previewRequest
} from './pairing.js';

beforeEach(() => {
  vi.clearAllMocks();
});

describe('isPairLink', () => {
  it('accepts the scheme in any case, ignoring surrounding whitespace', () => {
    expect(isPairLink('savevault://pair?v=1&server=nas&code=ABCD-EFGH')).toBe(true);
    expect(isPairLink('  SAVEVault://PAIR?x=1  ')).toBe(true);
  });

  it('refuses anything else', () => {
    expect(isPairLink('https://nas.example:8788')).toBe(false);
    expect(isPairLink('')).toBe(false);
    expect(isPairLink(null)).toBe(false);
  });
});

describe('previewRequest', () => {
  it('uses a pasted link when there is one', () => {
    expect(previewRequest({ server: 'https://nas:8788', code: 'ABCD-EFGH', link: '  savevault://pair?v=1&server=nas  ' })).toEqual({
      link: 'savevault://pair?v=1&server=nas'
    });
  });

  it('takes a whole link in the address field', () => {
    expect(previewRequest({ server: 'savevault://pair?v=1&server=nas&code=ABCD-EFGH', code: '' })).toEqual({
      link: 'savevault://pair?v=1&server=nas&code=ABCD-EFGH'
    });
  });

  it('sends the two fields otherwise', () => {
    expect(previewRequest({ server: ' https://nas:8788 ', code: ' abcd efgh ' })).toEqual({
      server: 'https://nas:8788',
      code: 'abcd efgh'
    });
  });
});

describe('pairing API calls', () => {
  it('previews with the request body the daemon expects', async () => {
    api.post.mockResolvedValue({ server: 'https://nas:8788', host: 'nas:8788', code: 'ABCD-EFGH' });
    await expect(previewPairing({ server: 'https://nas:8788', code: 'abcd efgh' })).resolves.toMatchObject({ host: 'nas:8788' });
    expect(api.post).toHaveBeenCalledWith('/api/cloud/pair/preview', { server: 'https://nas:8788', code: 'abcd efgh' });
  });

  it('claims with the host the user confirmed', async () => {
    api.post.mockResolvedValue({ name: '书房电脑', tokenId: 'abc', server: 'https://nas:8788' });
    await claimPairing({ server: 'https://nas:8788', code: 'ABCD-EFGH', confirmedHost: 'nas:8788' });
    expect(api.post).toHaveBeenCalledWith('/api/cloud/pair/claim', {
      server: 'https://nas:8788',
      code: 'ABCD-EFGH',
      confirmedHost: 'nas:8788'
    });
  });

  it('reads and dismisses the auth status', async () => {
    api.get.mockResolvedValue({ state: 'device' });
    api.post.mockResolvedValue({});
    await getAuthStatus();
    await dismissAuthWarning();
    expect(api.get).toHaveBeenCalledWith('/api/cloud/auth-status');
    expect(api.post).toHaveBeenCalledWith('/api/cloud/auth-warning/dismiss');
  });
});

describe('authStatusView', () => {
  it('names the device token, falling back to its id when the name is empty', () => {
    expect(authStatusView({ state: 'device', name: '书房的电脑', tokenId: '3f9c' })).toEqual({
      tone: 'ok',
      key: 'cloud.auth.device',
      params: { name: '书房的电脑' },
      showPair: false
    });
    expect(authStatusView({ state: 'device', name: '  ', tokenId: '3f9c' }).params).toEqual({ name: '3f9c' });
  });

  it('offers pairing while the shared token is in use', () => {
    expect(authStatusView({ state: 'shared' })).toEqual({
      tone: 'info',
      key: 'cloud.auth.shared',
      params: {},
      showPair: true
    });
  });

  it('explains a revoked device and offers pairing', () => {
    expect(authStatusView({ state: 'revoked' })).toEqual({
      tone: 'error',
      key: 'cloud.auth.revoked',
      params: {},
      showPair: true
    });
  });

  it('explains a disabled shared token and offers pairing', () => {
    expect(authStatusView({ state: 'sharedDisabled' })).toEqual({
      tone: 'error',
      key: 'cloud.auth.sharedDisabled',
      params: {},
      showPair: true
    });
  });

  it('explains a bad sync key and offers pairing', () => {
    expect(authStatusView({ state: 'unauthorized' })).toEqual({
      tone: 'error',
      key: 'cloud.auth.unauthorized',
      params: {},
      showPair: true
    });
  });

  it('says an older server cannot report device tokens', () => {
    expect(authStatusView({ state: 'unsupported' })).toEqual({
      tone: 'info',
      key: 'cloud.auth.unsupported',
      params: {},
      showPair: false
    });
  });

  it('shows the error text as a warning', () => {
    expect(authStatusView({ state: 'error', error: 'connection refused' })).toEqual({
      tone: 'warn',
      key: 'cloud.auth.error',
      params: { error: 'connection refused' },
      showPair: false
    });
  });

  it('draws nothing when auth is off or the state is unknown', () => {
    const none = { tone: 'none', key: '', params: {}, showPair: false };
    expect(authStatusView({ state: 'off' })).toEqual(none);
    expect(authStatusView({ state: 'something-new' })).toEqual(none);
    expect(authStatusView(null)).toEqual(none);
  });
});
