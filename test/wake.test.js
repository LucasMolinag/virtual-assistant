import assert from 'node:assert/strict';
import test from 'node:test';
import { commandAfterWake } from '../src/wake.js';

test('pulls the command out of a wake phrase', () => {
  assert.equal(commandAfterWake('hey vella'), '');
  assert.equal(commandAfterWake('Hey, Vella add lunch tomorrow'), 'add lunch tomorrow');
  assert.equal(commandAfterWake('hey bella move lunch'), 'move lunch');
  assert.equal(commandAfterWake('what time is lunch'), null);
});
