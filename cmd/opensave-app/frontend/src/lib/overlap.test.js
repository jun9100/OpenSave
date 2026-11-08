import { describe, expect, it } from 'vitest';
import { overlapOf } from './overlap.js';

describe('overlapOf', () => {
  it('reads the tracked game and its folder', () => {
    expect(
      overlapOf('this folder overlaps a tracked save location: "Forza Horizon 5" at C:\\Steam\\CODEX')
    ).toEqual({ name: 'Forza Horizon 5', path: 'C:\\Steam\\CODEX' });
  });

  it('unescapes quotes in the name', () => {
    expect(overlapOf('this folder overlaps a tracked save location: "Say \\"hi\\"" at /s')).toEqual({
      name: 'Say "hi"',
      path: '/s'
    });
  });

  it('ignores other errors', () => {
    expect(overlapOf('this folder is already tracked (as "X")')).toBeNull();
    expect(overlapOf(undefined)).toBeNull();
  });
});
