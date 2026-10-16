const WEEKDAY = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

export function clockSpoken(date) {
  return new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit' }).format(new Date(date));
}

export function atClock(date) {
  return `at ${clockSpoken(date)}`;
}

export function daySpoken(date, now = new Date()) {
  const value = new Date(date);
  const today = new Date(now);
  today.setHours(0, 0, 0, 0);
  value.setHours(0, 0, 0, 0);
  const diff = Math.round((value - today) / 86400000);
  if (diff === 0) return 'today';
  if (diff === 1) return 'tomorrow';
  if (diff === -1) return 'yesterday';
  if (diff > 1 && diff < 7) return WEEKDAY[value.getDay()];
  return new Intl.DateTimeFormat('en-US', { month: 'long', day: 'numeric' }).format(new Date(date));
}

export function whenSpoken(date, now = new Date()) {
  return `${daySpoken(date, now)} ${atClock(date)}`;
}

function cap(text) {
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : text;
}

export function rangeSpoken(command) {
  return command.rangeLabel || 'then';
}

export const lines = {
  empty: () => 'Tell me what to add, or ask what is on your calendar.',
  neverMind: () => 'Okay, I left it as it is.',
  needWhen: () => 'I still need a day and a time, like tomorrow at 3 PM.',
  sayNumber: () => 'Say the number of the appointment, or say never mind.',
  help: () => 'I can add, move, rename, and cancel appointments. I can set a location, a phone number, notes, a reminder, or an alarm. Say a time in another timezone and I will put it on your local clock. Ask what is on today, find a place, or ask me a question.',
  askWhen: (title) => `When should I schedule ${title}?`,
  askMove: (title) => `When should I move ${title}?`,
  notFound: (query) => `I couldn't find “${query}” coming up.`,
  choose: (count, bits) => `I found ${count}. Say a number: ${bits.join('. ')}.`,
  cancelled: (title, when) => `Done. I cancelled ${title}. It was ${when}.`,
  cancelledAll: (title, count) => count === 2
    ? `Done. I cancelled both ${title} appointments.`
    : `Done. I cancelled all ${count} ${title} appointments.`,
  cancelledSeveral: (count) => `Done. I cancelled ${count} appointments.`,
  needCancelName: () => 'Tell me the name, like cancel both dentist appointments.',
  opening: (title) => `Opening ${title}.`,
  moved: (title, when) => `Okay, I moved ${title} to ${when}.`,
  renamed: (title) => `Okay, the name is ${title} now.`,
  location: (title, place) => `I set the location for ${title} to ${place}.`,
  notes: (title) => `I added a note to ${title}.`,
  badDuration: () => 'That would make it end before it starts.',
  ends: (title, clock) => `${title} now ends ${clock}.`,
  added: (title, when, assumed, overlap) => {
    let line = `Okay, I added ${title} for ${when}.`;
    if (assumed) line += ' I set it for the morning. Tell me if you want a different time.';
    if (overlap) line += ` It overlaps ${overlap}.`;
    return line;
  },
  listEmpty: (range) => `${cap(range)} is clear.`,
  list: (range, countLabel, summary, extra) => `${cap(range)} has ${countLabel}: ${summary}${extra}.`,
  nothingNext: () => 'Nothing is coming up in the next few months.',
  next: (title, when) => `Next is ${title}, ${when}.`,
  missed: () => "I didn't catch a command.",
  nothingToUndo: () => 'Nothing to undo.',
  greeting: () => 'Tell me what to add, move, or cancel.',
  calendarError: () => 'Something went wrong talking to the calendar.',
};
