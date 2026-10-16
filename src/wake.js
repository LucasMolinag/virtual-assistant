const WAKE = /\bhey\b[\s,.'’"!?-]*\b(?:vella|vela|velma|bella|villa|fella)\b/i;

export function commandAfterWake(text) {
  const match = WAKE.exec(String(text || ''));
  if (!match) return null;
  return text.slice(match.index + match[0].length).replace(/^[\s,.'’"!?-]+/, '').trim();
}
