export function startOfMonth(date) {
  const d = startOfDay(date);
  d.setDate(1);
  return d;
}

export function startOfWeek(date) {
  const d = startOfDay(date);
  d.setDate(d.getDate() - d.getDay());
  return d;
}

export function startOfDay(date) {
  const d = new Date(date);
  d.setHours(0, 0, 0, 0);
  return d;
}

export function addDays(date, days) {
  const d = new Date(date);
  d.setDate(d.getDate() + days);
  return d;
}

export function addMinutes(date, minutes) {
  return new Date(new Date(date).getTime() + minutes * 60000);
}

export function formatClock(date) {
  return new Intl.DateTimeFormat(undefined, {
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(date));
}

export function dayPhrase(date, now = new Date()) {
  const value = new Date(date);
  const diff = Math.round((startOfDay(value) - startOfDay(now)) / 86400000);
  if (diff === 0) return 'today';
  if (diff === 1) return 'tomorrow';
  if (diff === -1) return 'yesterday';
  if (diff > 1 && diff < 7) {
    return new Intl.DateTimeFormat(undefined, { weekday: 'long' }).format(value);
  }
  return new Intl.DateTimeFormat(undefined, {
    weekday: 'long',
    month: 'long',
    day: 'numeric',
  }).format(value);
}

export function formatWhen(date, now = new Date()) {
  return `${dayPhrase(date, now)} at ${formatClock(date)}`;
}

export function formatDayHeading(date, now = new Date()) {
  const value = new Date(date);
  const phrase = dayPhrase(value, now);
  const full = new Intl.DateTimeFormat(undefined, {
    weekday: 'long',
    month: 'long',
    day: 'numeric',
  }).format(value);
  if (phrase === 'today' || phrase === 'tomorrow' || phrase === 'yesterday') {
    return `${phrase[0].toUpperCase()}${phrase.slice(1)} · ${full}`;
  }
  return full;
}

export function formatLocalDateTime(date) {
  const d = new Date(date);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

export function dateInputValue(date) {
  const d = new Date(date);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function timeInputValue(date) {
  const d = new Date(date);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function fromDateAndTime(dateStr, timeStr) {
  const [year, month, day] = dateStr.split('-').map(Number);
  const [hour, minute] = timeStr.split(':').map(Number);
  return new Date(year, month - 1, day, hour, minute, 0, 0);
}

export function horizon(now = new Date()) {
  const start = addDays(startOfDay(now), -1);
  const end = addDays(start, 183);
  return { start, end };
}

export function eventCoversDay(event, date) {
  const day = startOfDay(date).getTime();
  const start = new Date(event.start).getTime();
  const end = new Date(event.end).getTime();
  return start < day + 86400000 && end > day;
}

export function spansMultipleDays(event) {
  const start = startOfDay(event.start).getTime();
  const end = new Date(event.end);
  const endMidnight = end.getHours() === 0 && end.getMinutes() === 0 && end.getSeconds() === 0 && end.getMilliseconds() === 0;
  const last = endMidnight ? startOfDay(addDays(end, -1)).getTime() : startOfDay(end).getTime();
  return last > start;
}

export function rangesOverlap(a, b) {
  const startA = new Date(a.start).getTime();
  const endA = new Date(a.end).getTime();
  const startB = new Date(b.start).getTime();
  const endB = new Date(b.end).getTime();
  return startA < endB && startB < endA;
}
