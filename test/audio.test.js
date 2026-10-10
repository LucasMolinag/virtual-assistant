import assert from 'node:assert/strict';
import test from 'node:test';
import { resample, rms, shouldStopRecording, usableTranscript } from '../src/audio.js';

test('measures loudness and resamples to the speech model rate', () => {
  assert.equal(rms(new Float32Array()), 0);
  const loud = new Float32Array([0.5, -0.5]);
  assert.ok(rms(loud) > 0.4);

  const input = new Float32Array(48000);
  input[0] = 1;
  const output = resample(input, 48000, 16000);
  assert.equal(output.length, 16000);
  assert.equal(output[0], 1);
});

test('stops after a pause, and ignores silence from the model', () => {
  const startedAt = 0;
  assert.equal(shouldStopRecording({ startedAt, lastVoiceAt: 800, heardVoice: true }, 1900), true);
  assert.equal(shouldStopRecording({ startedAt, lastVoiceAt: 0, heardVoice: false }, 2000), false);
  assert.equal(usableTranscript('  [BLANK_AUDIO] thank you. '), '');
  assert.equal(usableTranscript('  add dentist tomorrow at 3 pm '), 'add dentist tomorrow at 3 pm');
});