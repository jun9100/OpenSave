import { describe, expect, it } from 'vitest';
import {
  assignmentsFrom,
  initialChoices,
  localLabel,
  memberTitle,
  platformLabel,
  sortMembers,
  validateChoices
} from './crossrestore.js';

const pcSingle = {
  format: 'pc',
  archiveLocations: [{ id: 'pc-root', kind: '', relativePath: '', files: 4, bytes: 1234 }],
  localLocations: [{ root: '', path: '/home/me/save', kind: 'gamehub-pc' }],
  suggested: [{ archiveLocation: 'pc-root', localRoot: '' }]
};

const pcMultiple = {
  format: 'pc',
  archiveLocations: [{ id: 'pc-root', kind: '', relativePath: '', files: 4, bytes: 1234 }],
  localLocations: [
    { root: '', path: '/home/me/save', kind: 'gamehub-pc' },
    { root: 'config', path: '/home/me/config', kind: '' }
  ],
  suggested: []
};

const androidPlan = {
  format: 'android',
  archiveLocations: [
    { id: 'location-0000', kind: 'switch-title', relativePath: 'title/0100', files: 12, bytes: 34567 },
    { id: 'location-0001', kind: '', relativePath: 'config', files: 1, bytes: 12 }
  ],
  localLocations: [
    { root: '', path: '/home/me/save', kind: 'switch-title' },
    { root: 'config', path: '/home/me/config', kind: '' }
  ],
  suggested: [{ archiveLocation: 'location-0000', localRoot: '' }]
};

describe('sortMembers', () => {
  it('orders newest first, puts members without a time last, and does not mutate', () => {
    const members = [
      { gameId: 'none' },
      { gameId: 'old', lastVersionAt: '2026-01-01T00:00:00Z' },
      { gameId: 'new', lastVersionAt: '2026-11-08T10:00:00Z' },
      { gameId: 'bad', lastVersionAt: 'not a time' }
    ];
    const sorted = sortMembers(members);
    expect(sorted.map((m) => m.gameId)).toEqual(['new', 'old', 'none', 'bad']);
    expect(members.map((m) => m.gameId)).toEqual(['none', 'old', 'new', 'bad']);
    expect(sorted).not.toBe(members);
  });
});

describe('platformLabel', () => {
  it('names the known platforms and passes anything else through', () => {
    expect(platformLabel('android')).toBe('Android');
    expect(platformLabel('opensave-android-arm64')).toBe('Android');
    expect(platformLabel('opensave-windows')).toBe('Windows');
    expect(platformLabel('opensave-darwin')).toBe('macOS');
    expect(platformLabel('opensave-linux')).toBe('Linux');
    expect(platformLabel('nintendo-switch')).toBe('nintendo-switch');
    expect(platformLabel('')).toBe('');
    expect(platformLabel(undefined)).toBe('');
  });
});

describe('memberTitle', () => {
  it('prefers the device name, then the scope name, then the game id', () => {
    expect(memberTitle({ deviceName: 'Pixel 8', name: 'Phone', gameId: 'g1' })).toBe('Pixel 8');
    expect(memberTitle({ deviceName: '', name: 'Phone', gameId: 'g1' })).toBe('Phone');
    expect(memberTitle({ name: '', gameId: 'g1' })).toBe('g1');
    expect(memberTitle({})).toBe('');
  });
});

describe('initialChoices', () => {
  it('prefills a PC save when there is only one local location', () => {
    expect(initialChoices(pcSingle)).toEqual({ 'pc-root': '' });
  });

  it('starts a PC save with several local locations skipped', () => {
    expect(initialChoices(pcMultiple)).toEqual({ 'pc-root': null });
  });

  it('prefills only the Android location the plan suggests', () => {
    expect(initialChoices(androidPlan)).toEqual({
      'location-0000': '',
      'location-0001': null
    });
  });
});

describe('assignmentsFrom', () => {
  it('drops skipped locations, keeps the primary root as an empty string, and sorts', () => {
    expect(assignmentsFrom({ b: '', a: 'config', c: null })).toEqual([
      { archiveLocation: 'a', localRoot: 'config' },
      { archiveLocation: 'b', localRoot: '' }
    ]);
  });

  it('turns the prefilled primary location into an assignment, not a skip', () => {
    expect(assignmentsFrom(initialChoices(pcSingle))).toEqual([{ archiveLocation: 'pc-root', localRoot: '' }]);
  });
});

describe('validateChoices', () => {
  it('accepts the prefilled primary location', () => {
    expect(validateChoices(pcSingle, initialChoices(pcSingle))).toBe('');
  });

  it('rejects leaving everything skipped', () => {
    expect(validateChoices(androidPlan, { 'location-0000': null, 'location-0001': null })).toBe('game.cloud.others.err_none');
    expect(validateChoices(androidPlan, {})).toBe('game.cloud.others.err_none');
  });

  it('rejects two cloud locations restored to the same local location', () => {
    expect(validateChoices(androidPlan, { 'location-0000': '', 'location-0001': '' })).toBe('game.cloud.others.err_duplicate');
  });

  it('rejects a local location the plan does not offer', () => {
    expect(validateChoices(androidPlan, { 'location-0000': 'missing' })).toBe('game.cloud.others.err_unknown');
  });

  it('accepts distinct available locations', () => {
    expect(validateChoices(androidPlan, { 'location-0000': '', 'location-0001': 'config' })).toBe('');
  });
});

describe('localLabel', () => {
  it('names the primary location and shows an additional root by its own name', () => {
    const t = (key) => (key === 'game.cloud.others.primary_location' ? 'Primary location' : key);
    expect(localLabel({ root: '' }, t)).toBe('Primary location');
    expect(localLabel({ root: 'config' }, t)).toBe('config');
  });
});
