const HALLUCINATIONS = new Set([
  'thank you',
  'thanks for watching',
  'you',
  'bye',
  'silence',
  '.',
]);

export function rms(samples) {
  if (!samples.length) return 0;
  let sum = 0;
  for (let i = 0; i < samples.length; i += 1) sum += samples[i] * samples[i];
  return Math.sqrt(sum / samples.length);
}

export function concatFloat32(chunks) {
  const length = chunks.reduce((total, chunk) => total + chunk.length, 0);
  const merged = new Float32Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.length;
  }
  return merged;
}

export function resample(input, fromRate, toRate) {
  if (!input.length || fromRate === toRate) return input;
  const ratio = fromRate / toRate;
  const length = Math.max(1, Math.round(input.length / ratio));
  const output = new Float32Array(length);
  const last = input.length - 1;
  for (let i = 0; i < length; i += 1) {
    const position = i * ratio;
    const left = Math.floor(position);
    const right = Math.min(left + 1, last);
    const mix = position - left;
    output[i] = input[left] * (1 - mix) + input[right] * mix;
  }
  return output;
}

export function shouldStopRecording(state, now) {
  if (now - state.startedAt >= 15000) return true;
  if (!state.heardVoice || now - state.startedAt < 700) return false;
  return now - state.lastVoiceAt >= 1000;
}

export function usableTranscript(text) {
  const cleaned = String(text || '')
    .replace(/\[[^\]]*\]/g, '')
    .replace(/\([^)]*\)/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  if (!cleaned) return '';
  const plain = cleaned.toLowerCase().replace(/[.!?,]+$/g, '').trim();
  if (HALLUCINATIONS.has(plain)) return '';
  return cleaned;
}
