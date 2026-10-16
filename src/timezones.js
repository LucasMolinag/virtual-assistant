const NAMED = [
  ['fernando de noronha', 'America/Noronha', 'Fernando de Noronha'],
  ['new zealand', 'Pacific/Auckland', 'New Zealand'],
  ['central european', 'Europe/Paris', 'Central European'],
  ['buenos aires', 'America/Argentina/Buenos_Aires', 'Argentina'],
  ['mexico city', 'America/Mexico_City', 'Mexico City'],
  ['los angeles', 'America/Los_Angeles', 'Pacific'],
  ['hong kong', 'Asia/Hong_Kong', 'Hong Kong'],
  ['new york', 'America/New_York', 'Eastern'],
  ['east coast', 'America/New_York', 'Eastern'],
  ['west coast', 'America/Los_Angeles', 'Pacific'],
  ['brazilian', 'America/Sao_Paulo', 'Brazil'],
  ['brasilia', 'America/Sao_Paulo', 'Brazil'],
  ['brasília', 'America/Sao_Paulo', 'Brazil'],
  ['brazil', 'America/Sao_Paulo', 'Brazil'],
  ['brasil', 'America/Sao_Paulo', 'Brazil'],
  ['amazonas', 'America/Manaus', 'the Amazon'],
  ['manaus', 'America/Manaus', 'the Amazon'],
  ['amazon', 'America/Manaus', 'the Amazon'],
  ['noronha', 'America/Noronha', 'Fernando de Noronha'],
  ['eastern', 'America/New_York', 'Eastern'],
  ['central', 'America/Chicago', 'Central'],
  ['chicago', 'America/Chicago', 'Central'],
  ['mountain', 'America/Denver', 'Mountain'],
  ['denver', 'America/Denver', 'Mountain'],
  ['pacific', 'America/Los_Angeles', 'Pacific'],
  ['alaska', 'America/Anchorage', 'Alaska'],
  ['hawaii', 'Pacific/Honolulu', 'Hawaii'],
  ['britain', 'Europe/London', 'London'],
  ['british', 'Europe/London', 'London'],
  ['england', 'Europe/London', 'London'],
  ['london', 'Europe/London', 'London'],
  ['portugal', 'Europe/Lisbon', 'Portugal'],
  ['portuguese', 'Europe/Lisbon', 'Portugal'],
  ['lisbon', 'Europe/Lisbon', 'Portugal'],
  ['germany', 'Europe/Berlin', 'Germany'],
  ['berlin', 'Europe/Berlin', 'Germany'],
  ['france', 'Europe/Paris', 'France'],
  ['paris', 'Europe/Paris', 'France'],
  ['spain', 'Europe/Madrid', 'Spain'],
  ['madrid', 'Europe/Madrid', 'Spain'],
  ['italy', 'Europe/Rome', 'Italy'],
  ['rome', 'Europe/Rome', 'Italy'],
  ['japanese', 'Asia/Tokyo', 'Japan'],
  ['japan', 'Asia/Tokyo', 'Japan'],
  ['tokyo', 'Asia/Tokyo', 'Japan'],
  ['chinese', 'Asia/Shanghai', 'China'],
  ['china', 'Asia/Shanghai', 'China'],
  ['beijing', 'Asia/Shanghai', 'China'],
  ['shanghai', 'Asia/Shanghai', 'China'],
  ['indian', 'Asia/Kolkata', 'India'],
  ['india', 'Asia/Kolkata', 'India'],
  ['kolkata', 'Asia/Kolkata', 'India'],
  ['mumbai', 'Asia/Kolkata', 'India'],
  ['delhi', 'Asia/Kolkata', 'India'],
  ['korean', 'Asia/Seoul', 'Korea'],
  ['korea', 'Asia/Seoul', 'Korea'],
  ['seoul', 'Asia/Seoul', 'Korea'],
  ['australian', 'Australia/Sydney', 'Sydney'],
  ['australia', 'Australia/Sydney', 'Sydney'],
  ['sydney', 'Australia/Sydney', 'Sydney'],
  ['auckland', 'Pacific/Auckland', 'New Zealand'],
  ['mexican', 'America/Mexico_City', 'Mexico City'],
  ['mexico', 'America/Mexico_City', 'Mexico City'],
  ['argentine', 'America/Argentina/Buenos_Aires', 'Argentina'],
  ['argentina', 'America/Argentina/Buenos_Aires', 'Argentina'],
  ['colombian', 'America/Bogota', 'Colombia'],
  ['colombia', 'America/Bogota', 'Colombia'],
  ['bogotá', 'America/Bogota', 'Colombia'],
  ['bogota', 'America/Bogota', 'Colombia'],
  ['peruvian', 'America/Lima', 'Peru'],
  ['peru', 'America/Lima', 'Peru'],
  ['lima', 'America/Lima', 'Peru'],
  ['chilean', 'America/Santiago', 'Chile'],
  ['chile', 'America/Santiago', 'Chile'],
  ['santiago', 'America/Santiago', 'Chile'],
  ['acre', 'America/Rio_Branco', 'Acre'],
  ['uk', 'Europe/London', 'London'],
];

const ABBREV = {
  brt: ['America/Sao_Paulo', 'Brazil'],
  brst: ['America/Sao_Paulo', 'Brazil'],
  est: ['America/New_York', 'Eastern'],
  edt: ['America/New_York', 'Eastern'],
  cst: ['America/Chicago', 'Central'],
  cdt: ['America/Chicago', 'Central'],
  mst: ['America/Denver', 'Mountain'],
  mdt: ['America/Denver', 'Mountain'],
  pst: ['America/Los_Angeles', 'Pacific'],
  pdt: ['America/Los_Angeles', 'Pacific'],
  akst: ['America/Anchorage', 'Alaska'],
  akdt: ['America/Anchorage', 'Alaska'],
  hst: ['Pacific/Honolulu', 'Hawaii'],
  gmt: ['Europe/London', 'London'],
  bst: ['Europe/London', 'London'],
  utc: ['UTC', 'UTC'],
  cet: ['Europe/Paris', 'Central European'],
  cest: ['Europe/Paris', 'Central European'],
  jst: ['Asia/Tokyo', 'Japan'],
  kst: ['Asia/Seoul', 'Korea'],
  ist: ['Asia/Kolkata', 'India'],
  aest: ['Australia/Sydney', 'Sydney'],
  aedt: ['Australia/Sydney', 'Sydney'],
};

function escapeRegExp(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function tzOffsetMs(instant, timeZone) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(instant);
  const bag = {};
  for (const part of parts) bag[part.type] = part.value;
  const asUtc = Date.UTC(
    Number(bag.year),
    Number(bag.month) - 1,
    Number(bag.day),
    Number(bag.hour) % 24,
    Number(bag.minute),
    Number(bag.second),
  );
  return asUtc - instant.getTime();
}

export function clockInZone(wall, zone) {
  const date = new Date(wall);
  if (zone && typeof zone === 'object' && Number.isFinite(zone.offsetMinutes)) {
    const utc = Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), date.getHours(), date.getMinutes(), date.getSeconds());
    return new Date(utc - zone.offsetMinutes * 60000);
  }
  const utcGuess = Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), date.getHours(), date.getMinutes(), date.getSeconds());
  const offset = tzOffsetMs(new Date(utcGuess), zone);
  let result = new Date(utcGuess - offset);
  const refined = tzOffsetMs(result, zone);
  if (refined !== offset) result = new Date(utcGuess - refined);
  return result;
}

export function pullTimeZone(text) {
  const source = String(text || '');
  const offset = source.match(/\b(?:utc|gmt)\s*([+-])\s*(\d{1,2})(?::?(\d{2}))?\b/i);
  if (offset) {
    const sign = offset[1] === '-' ? -1 : 1;
    const minutes = sign * (Number(offset[2]) * 60 + Number(offset[3] || 0));
    const label = `GMT${offset[1]}${Number(offset[2])}${offset[3] ? `:${offset[3]}` : ''}`;
    return {
      text: source.replace(offset[0], ' ').replace(/\s+/g, ' ').trim(),
      timeZone: { offsetMinutes: minutes },
      label,
    };
  }
  const names = [...NAMED].sort((a, b) => b[0].length - a[0].length);
  const named = new RegExp(`\\b(${names.map((item) => escapeRegExp(item[0])).join('|')})\\s+time(?:\\s*zone)?\\b`, 'i');
  const found = source.match(named);
  if (found) {
    const zone = names.find((item) => item[0].toLowerCase() === found[1].toLowerCase());
    return {
      text: source.replace(found[0], ' ').replace(/\s+/g, ' ').trim(),
      timeZone: zone[1],
      label: zone[2],
    };
  }
  const abbrev = source.match(new RegExp(`\\b(${Object.keys(ABBREV).join('|')})\\b`, 'i'));
  if (abbrev) {
    const zone = ABBREV[abbrev[1].toLowerCase()];
    return {
      text: source.replace(abbrev[0], ' ').replace(/\s+/g, ' ').trim(),
      timeZone: zone[0],
      label: zone[1],
    };
  }
  return { text: source, timeZone: null, label: null };
}
