import { addMinutes, formatClock, formatWhen, rangesOverlap, spansMultipleDays } from './dates.js';
import { findCandidates, lookupEvents, numberFrom, parseCommand, parseWhen } from './parse.js';
import { atClock, lines, rangeSpoken, whenSpoken } from './voice.js';

const SKIP_PLACE = /^(?:home|work|the office|office|online|zoom|phone|virtual|my house)$/i;

async function defaultLookup(name) {
  if (typeof window === 'undefined' || typeof window.VellaNative?.lookupPlace !== 'function') return null;
  try {
    const parsed = JSON.parse(window.VellaNative.lookupPlace(name));
    if (parsed.error) return null;
    return parsed;
  } catch {
    return null;
  }
}

async function defaultAsk(question) {
  if (typeof window !== 'undefined' && typeof window.VellaNative?.ask === 'function') {
    try {
      const parsed = JSON.parse(window.VellaNative.ask(question));
      if (parsed.reply) return parsed;
    } catch {
      return { reply: "I couldn't look that up.", spoken: "I couldn't look that up." };
    }
  }
  return { reply: "I couldn't look that up.", spoken: "I couldn't look that up." };
}

async function defaultSetAlarm(at, label, relativeSeconds = 0) {
  if (typeof window === 'undefined' || typeof window.VellaNative?.setAlarm !== 'function') return false;
  try {
    const parsed = JSON.parse(window.VellaNative.setAlarm(new Date(at).toISOString(), label || 'Alarm', Number(relativeSeconds) || 0));
    if (!parsed?.ok) return false;
    return parsed.kind || 'alarm';
  } catch {
    return false;
  }
}

export function createAssistant({ calendar, now = () => new Date(), lookupPlace = defaultLookup, ask = defaultAsk, setAlarm = defaultSetAlarm }) {
  let pending = null;

  async function upcoming() {
    return calendar.listUpcoming(now());
  }

  return {
    async handle(raw) {
      const text = String(raw || '').replace(/\s+/g, ' ').trim();
      if (!text) {
        return { reply: 'Tell me an appointment to add, or ask what is on your calendar.', spoken: lines.empty() };
      }
      if (/^(never mind|nevermind|forget it|stop that|deixa pra l[aá]|deixa quiet[oa]|esquece|esqueça)$/iu.test(text)) {
        pending = null;
        return { reply: 'Okay, I left it as it is.', spoken: lines.neverMind() };
      }
      if (parseCommand(text, now()).intent === 'undo') {
        pending = null;
        return undoLast();
      }
      if (pending?.type === 'when') return finishAdd(text);
      if (pending?.type === 'place') return finishPlace(text);
      if (pending?.type === 'time') return finishTime(text);
      if (pending?.type === 'name') return finishName(text);
      if (pending?.type === 'note') return finishNote(text);
      if (pending?.type === 'phone') return finishPhone(text);
      if (pending?.type === 'day') return finishDay(text);
      if (pending?.type === 'length') return finishLength(text);
      if (pending?.type === 'which') return finishWhich(text);
      if (pending?.type === 'title') return finishTitle(text);
      if (pending?.type === 'alarm') return finishAlarm(text);
      if (pending?.type === 'choose') return finishChoice(text);
      return run(parseCommand(text, now()));
    },
  };

  async function finishAdd(text) {
    const draft = pending.draft;
    const other = parseCommand(text, now());
    if (other.intent !== 'unknown' && other.intent !== 'add') {
      pending = null;
      return run(other);
    }
    const when = other.intent === 'add' && other.start ? other : parseWhen(text, now());
    if (!when.start) return { reply: 'I still need a day and a time, like tomorrow at 3 PM.', spoken: lines.needWhen(), followUp: true };
    pending = null;
    return add({
      ...draft,
      start: when.start,
      end: when.end,
      durationMinutes: when.durationMinutes || draft.durationMinutes,
      assumedTime: when.assumedTime,
      zoneLabel: when.zoneLabel,
      sourceTime: when.sourceTime,
    });
  }

  function clockFrom(text) {
    const cleaned = text.replace(/[?.!]+$/g, '').trim();
    const words = {
      one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12,
    };
    const match = cleaned.match(/^(?:at\s+)?(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|noon|midnight|\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?$/i);
    if (!match) return null;
    const token = match[1].toLowerCase();
    let hour = words[token] ?? Number(token);
    const minute = match[2] ? Number(match[2]) : 0;
    if (!Number.isFinite(hour) || hour > 23 || minute > 59) return null;
    const meridiem = (match[3] || '').toLowerCase().replace(/\./g, '');
    if (token === 'noon') hour = 12;
    else if (token === 'midnight') hour = 0;
    else if (meridiem.startsWith('p') && hour < 12) hour += 12;
    else if (meridiem.startsWith('a') && hour === 12) hour = 0;
    else if (!meridiem && hour >= 1 && hour <= 6) hour += 12;
    const start = new Date(now());
    start.setHours(hour, minute, 0, 0);
    return { start, end: null, hourCertain: true, dayCertain: false };
  }

  function placeTime(event, when) {
    let start = new Date(when.start);
    if (!when.dayCertain) {
      const kept = new Date(event.start);
      start = new Date(kept.getFullYear(), kept.getMonth(), kept.getDate(), start.getHours(), start.getMinutes(), 0, 0);
    }
    const span = when.end
      ? new Date(when.end).getTime() - new Date(when.start).getTime()
      : new Date(event.end).getTime() - new Date(event.start).getTime();
    const end = new Date(start.getTime() + (span > 0 ? span : 60 * 60000));
    return { start, end };
  }

  function askTime(event) {
    pending = { type: 'time', event };
    return {
      reply: 'What time?',
      spoken: 'What time?',
      followUp: true,
      highlightId: event.id,
      focusDate: new Date(event.start),
    };
  }

  function follow(event, type, reply, extra = {}) {
    pending = { type, event, ...extra };
    return {
      reply,
      spoken: reply,
      followUp: true,
      highlightId: event?.id,
      focusDate: event ? new Date(event.start) : undefined,
    };
  }

  function divert(text) {
    if (!/^(?:please\s+)?(?:cancel|delete|remove|add|schedule|move|reschedule|rename|undo|what(?:'s| is)|set|change|update|open|remind|make|extend|shorten|alarm|timer|wake)\b/i.test(text)) return null;
    const other = parseCommand(text, now());
    if (!other || other.intent === 'unknown') return null;
    pending = null;
    return other;
  }

  function answerText(text) {
    return text
      .replace(/[?.!]+$/g, '')
      .replace(/^(?:please\s+)?(?:it(?:'s| is)\s+)?(?:call it\s+|name it\s+|to\s+)?/i, '')
      .trim();
  }

  function titled(text) {
    const value = answerText(text);
    if (!value) return '';
    return value.charAt(0).toUpperCase() + value.slice(1);
  }

  function minutesFrom(text) {
    const spoken = text.replace(/[?.!]+$/g, '').trim();
    if (/^(?:for\s+)?half\s+an?\s+hours?$/i.test(spoken)) return 30;
    if (/^(?:for\s+)?(?:an|one)\s+hours?$/i.test(spoken)) return 60;
    const match = spoken.match(/^(?:for\s+)?(\d+(?:\.\d+)?)\s*(hours|hour|hrs|hr|minutes|minute|mins|min)$/i);
    if (!match) return null;
    const amount = Number(match[1]);
    return /^h/i.test(match[2]) ? Math.round(amount * 60) : amount;
  }

  async function finishName(text) {
    const event = pending.event;
    const other = divert(text);
    if (other) return run(other);
    const title = titled(text);
    if (!title) return follow(event, 'name', 'What name?');
    pending = null;
    return act('rename', event, { title });
  }

  async function finishNote(text) {
    const event = pending.event;
    const other = divert(text);
    if (other) return run(other);
    const notes = text.replace(/^(?:please\s+)?(?:saying|that)\s+/i, '').replace(/[?.!]+$/g, '').trim();
    if (!notes) return follow(event, 'note', 'What should the note say?');
    pending = null;
    return act('notes', event, { notes });
  }

  async function finishPhone(text) {
    const event = pending.event;
    const other = divert(text);
    if (other) return run(other);
    const phone = text
      .replace(/^(?:please\s+)?(?:it(?:'s| is)\s+)?(?:the\s+)?(?:phone\s+)?(?:number\s+)?(?:is\s+)?/i, '')
      .replace(/[?.!]+$/g, '')
      .trim();
    if (!phone) return follow(event, 'phone', "What's the phone number?");
    pending = null;
    return act('phone', event, { phone });
  }

  async function finishDay(text) {
    const event = pending.event;
    const when = parseWhen(text, now());
    if (!when.start) {
      const other = divert(text);
      if (other) return run(other);
      return follow(event, 'day', 'What day?');
    }
    const start = new Date(when.start);
    if (!when.hourCertain) {
      const kept = new Date(event.start);
      start.setHours(kept.getHours(), kept.getMinutes(), 0, 0);
    }
    const span = new Date(event.end).getTime() - new Date(event.start).getTime();
    pending = null;
    return act('move', event, { start, end: new Date(start.getTime() + (span > 0 ? span : 60 * 60000)) });
  }

  async function finishLength(text) {
    const event = pending.event;
    const mode = pending.mode || 'set';
    const minutes = minutesFrom(text);
    if (!minutes) {
      const other = divert(text);
      if (other) return run(other);
      return follow(event, 'length', 'How long?', { mode });
    }
    pending = null;
    return act('duration', event, { durationMinutes: minutes, durationMode: mode });
  }

  async function finishWhich(text) {
    const command = pending.command;
    const other = divert(text);
    if (other) return run(other);
    const query = answerText(text);
    if (!query) return { reply: 'Which appointment?', spoken: 'Which appointment?', followUp: true };
    pending = null;
    return choose({ ...command, query });
  }

  async function finishTitle(text) {
    const other = divert(text);
    if (other) return run(other);
    const value = answerText(text);
    if (!value) return { reply: 'What should I add?', spoken: 'What should I add?', followUp: true };
    pending = null;
    return run(parseCommand(/^add\b/i.test(value) ? value : `add ${value}`, now()));
  }

  async function finishTime(text) {
    const event = pending.event;
    const when = parseWhen(text, now());
    const timed = when.hourCertain ? when : clockFrom(text);
    if (!timed?.start) {
      const other = parseCommand(text, now());
      if (other.intent !== 'unknown') {
        pending = null;
        return run(other);
      }
      return askTime(event);
    }
    pending = null;
    const placed = placeTime(event, timed);
    return act('move', event, { ...placed, zoneLabel: timed.zoneLabel, sourceTime: timed.sourceTime });
  }

  function zoneNote(command) {
    if (!command?.zoneLabel || !command.sourceTime || !command.start) return '';
    if (Math.abs(new Date(command.start).getTime() - new Date(command.sourceTime).getTime()) <= 60000) return '';
    const clock = formatClock(command.sourceTime);
    const phrase = /^(?:GMT|UTC)[+-]/i.test(command.zoneLabel) ? `${clock} ${command.zoneLabel}` : `${clock} in ${command.zoneLabel}`;
    return ` That's ${phrase}.`;
  }

  function delayPhrase(minutes) {
    if (minutes === 60) return '1 hour';
    if (minutes % 60 === 0) return `${minutes / 60} hours`;
    if (minutes === 1) return '1 minute';
    return `${minutes} minutes`;
  }

  async function finishAlarm(text) {
    const other = divert(text);
    if (other && other.intent !== 'alarm') return run(other);
    const parsed = parseCommand(/^(?:please\s+)?(?:set\b|alarm\b|timer\b|wake\b)/i.test(text) ? text : `set an alarm ${text}`, now());
    if (parsed.intent === 'alarm' && parsed.ask === 'time' && parsed.day) {
      pending = { type: 'alarm', day: parsed.day };
      return { reply: 'What time?', spoken: 'What time?', followUp: true };
    }
    if (parsed.intent !== 'alarm' || !parsed.start) {
      return { reply: 'When should I set the alarm?', spoken: 'When should I set the alarm?', followUp: true };
    }
    let start = parsed.start ? new Date(parsed.start) : null;
    if (pending.day && start && !parsed.dayCertain) {
      const day = new Date(pending.day);
      start = new Date(day.getFullYear(), day.getMonth(), day.getDate(), start.getHours(), start.getMinutes(), 0, 0);
    }
    if (!start) return { reply: 'What time?', spoken: 'What time?', followUp: true };
    pending = null;
    return ring({ ...parsed, start });
  }

  async function ring(command) {
    if (!command.start) {
      pending = { type: 'alarm', day: command.day || null };
      const reply = command.ask === 'time' ? 'What time?' : 'When should I set the alarm?';
      return { reply, spoken: reply, followUp: true };
    }
    const at = new Date(command.start);
    if (at.getTime() <= now().getTime() + 1000) {
      return { reply: 'That time has already passed.', spoken: 'That time has already passed.' };
    }
    const kind = await setAlarm(at, 'Alarm', command.relativeMinutes ? command.relativeMinutes * 60 : 0);
    if (!kind) {
      return { reply: "I can't set a phone alarm from here.", spoken: "I can't set a phone alarm from here." };
    }
    const when = formatWhen(at, now());
    const clockWord = kind === 'timer' ? 'Timer' : 'Alarm';
    let reply = command.relativeMinutes
      ? `${clockWord} set for ${delayPhrase(command.relativeMinutes)} from now, ${when}.`
      : `${clockWord} set for ${when}.`;
    reply += zoneNote(command);
    return { reply, spoken: reply };
  }

  async function finishPlace(text) {
    const event = pending.event;
    if (/^(?:please\s+)?(?:cancel|move|undo|rename|what(?:'s| is) on|what do i have)\b/i.test(text)) {
      pending = null;
      return run(parseCommand(text, now()));
    }
    const placeName = text
      .replace(/^(?:please\s+)?(?:it(?: is|'s)\s+)?(?:at\s+|in\s+)?/i, '')
      .replace(/[?.!]+$/g, '')
      .trim();
    if (!placeName) {
      return { reply: `What's the location for ${event.title}?`, spoken: `What's the location for ${event.title}?`, followUp: true };
    }
    pending = null;
    return act('location', event, { location: placeName });
  }

  async function finishChoice(text) {
    const { matches, action, command } = pending;
    if (action === 'cancel' && wantsEvery(text)) {
      pending = null;
      return cancelMany(matches);
    }
    const picked = pick(text, matches);
    if (picked) {
      pending = null;
      return act(action, picked, command);
    }
    const other = parseCommand(text, now());
    if (other.intent !== 'unknown') {
      pending = null;
      return run(other);
    }
    return { reply: 'Say the number of the appointment, or say never mind.', spoken: lines.sayNumber(), followUp: true };
  }

  function wantsEvery(text) {
    const phrase = text.trim().toLowerCase().replace(/[?.!]/g, '');
    return /^(?:please\s+)?(?:(?:cancel|delete|remove)\s+)?(?:both(?:\s+of\s+(?:them|those))?|all(?:\s+of\s+(?:them|those))?|every(?:\s+one)?|each(?:\s+(?:one|of\s+them))?|them all)(?:\s+(?:appointments|meetings|events))?$/i.test(phrase);
  }

  function pick(text, matches) {
    const n = numberFrom(text);
    if (n && n >= 1 && n <= matches.length) return matches[n - 1];
    const narrowed = findCandidates(matches, text, now());
    return narrowed.length === 1 ? narrowed[0] : null;
  }

  async function run(command) {
    switch (command.intent) {
      case 'help':
        return { reply: lines.help(), spoken: lines.help() };
      case 'list':
        return list(command);
      case 'next':
        return nextEvent();
      case 'add':
        if (command.ask === 'title' || !command.title) {
          pending = { type: 'title' };
          return { reply: 'What should I add?', spoken: 'What should I add?', followUp: true };
        }
        if (!command.start) {
          pending = { type: 'when', draft: command };
          return { reply: `When should I schedule ${command.title}?`, spoken: lines.askWhen(command.title), followUp: true };
        }
        return add(command);
      case 'cancel':
      case 'move':
      case 'rename':
      case 'location':
      case 'notes':
      case 'duration':
      case 'open':
      case 'clear':
      case 'phone':
      case 'remind':
      case 'forget':
        return choose(command);
      case 'question':
        return question(command);
      case 'alarm':
        return ring(command);
      default:
        return { reply: lines.missed(), spoken: lines.missed() };
    }
  }

  async function choose(command) {
    if (command.intent === 'cancel' && command.every && !command.query) {
      return {
        reply: 'Tell me the name, like cancel both dentist appointments.',
        spoken: lines.needCancelName(),
        followUp: true,
      };
    }
    if (!String(command.query || '').trim()) {
      pending = { type: 'which', command };
      return { reply: 'Which appointment?', spoken: 'Which appointment?', followUp: true };
    }
    const { matches, named, name, day } = lookupEvents(await upcoming(), command.query, now());
    if (!matches.length) {
      if (day && named.length) {
        const event = named[0];
        const when = formatWhen(new Date(event.start), now());
        const reply = `${event.title} is ${when}, not that day. I left it on the calendar.`;
        return { reply, spoken: `${event.title} is ${whenSpoken(event.start, now())}. I left it on the calendar.` };
      }
      return { reply: `I couldn't find “${name || command.query}” in the next 6 months.`, spoken: lines.notFound(name || command.query) };
    }
    const scoped = day ? { ...command, cancelInstance: true } : command;
    if (matches.length > 1 && command.intent === 'cancel' && command.every) return cancelMany(matches, Boolean(day));
    if (matches.length > 1) {
      pending = { type: 'choose', action: command.intent, command: scoped, matches };
      const choices = matches.slice(0, 4).map((event, index) => `${index + 1}, ${event.title} ${formatWhen(new Date(event.start), now())}`);
      const spokenChoices = matches.slice(0, 4).map((event, index) => `${index + 1}, ${event.title}, ${whenSpoken(event.start, now())}`);
      return {
        reply: `I found ${matches.length} appointments. Say a number: ${choices.join('; ')}.`,
        spoken: lines.choose(matches.length, spokenChoices),
        followUp: true,
      };
    }
    return act(command.intent, matches[0], scoped);
  }

  async function cancelMany(matches, oneInstance) {
    if (typeof calendar.removeMany === 'function' && !oneInstance) await calendar.removeMany(matches.map((event) => event.id));
    else {
      for (const event of matches) await calendar.remove(event.id, oneInstance ? event.start : undefined);
    }
    const title = matches[0].title;
    const sameName = matches.every((event) => event.title.toLowerCase() === title.toLowerCase());
    const count = matches.length;
    const reply = sameName
      ? (count === 2 ? `Cancelled both ${title} appointments.` : `Cancelled all ${count} ${title} appointments.`)
      : `Cancelled ${count} appointments.`;
    return {
      reply,
      spoken: sameName ? lines.cancelledAll(title, count) : lines.cancelledSeveral(count),
      focusDate: new Date(matches[0].start),
    };
  }

  async function act(action, event, command) {
    if (action === 'cancel') {
      await calendar.remove(event.id, command.cancelInstance ? event.start : undefined);
      return {
        reply: `Cancelled ${event.title} ${formatWhen(new Date(event.start), now())}.`,
        spoken: lines.cancelled(event.title, whenSpoken(event.start, now())),
        focusDate: new Date(event.start),
      };
    }
    if (action === 'open') {
      return {
        reply: `Opening ${event.title}.`,
        spoken: lines.opening(event.title),
        openEvent: event,
        focusDate: new Date(event.start),
        highlightId: event.id,
      };
    }
    if (action === 'move') {
      if (!command.start) {
        if (command.ask === 'day') return follow(event, 'day', 'What day?');
        if (command.ask === 'time') return askTime(event);
        pending = { type: 'time', event };
        return {
          reply: `When should I move ${event.title}?`,
          spoken: lines.askMove(event.title),
          followUp: true,
          highlightId: event.id,
          focusDate: new Date(event.start),
        };
      }
      const previous = new Date(event.end) - new Date(event.start);
      const end = command.end ?? new Date(command.start.getTime() + previous);
      const patch = {
        start: command.start.toISOString(),
        end: end.toISOString(),
      };
      let placeNote = '';
      if (command.location) {
        const place = await resolvePlace(command.location);
        patch.location = place.location;
        if (place.phone) patch.phone = place.phone;
        if (place.lookedUp) placeNote = ` The address is ${place.location}.`;
        if (place.phone) placeNote += ` The phone number is ${place.phone}.`;
      }
      const saved = await calendar.update(event.id, patch);
      const reply = `Moved ${saved.title} to ${formatWhen(new Date(saved.start), now())}.${placeNote}${zoneNote(command)}`;
      return {
        reply,
        spoken: `${lines.moved(saved.title, whenSpoken(saved.start, now()))}${placeNote}${zoneNote(command)}`,
        focusDate: new Date(saved.start),
        highlightId: saved.id,
      };
    }
    if (action === 'rename') {
      if (!command.title) return follow(event, 'name', 'What name?');
      const saved = await calendar.update(event.id, { title: command.title });
      return { reply: `Renamed it to ${saved.title}.`, spoken: lines.renamed(saved.title), highlightId: saved.id, focusDate: new Date(saved.start) };
    }
    if (action === 'location') {
      if (!command.location) {
        pending = { type: 'place', event };
        const reply = `What's the location for ${event.title}?`;
        return { reply, spoken: reply, followUp: true, highlightId: event.id, focusDate: new Date(event.start) };
      }
      const place = await resolvePlace(command.location);
      const patch = { location: place.location };
      if (place.phone) patch.phone = place.phone;
      const saved = await calendar.update(event.id, patch);
      let reply = `Location for ${saved.title} is ${saved.location}.`;
      if (place.phone) reply += ` Phone ${place.phone}.`;
      return { reply, spoken: reply, highlightId: saved.id, focusDate: new Date(saved.start) };
    }
    if (action === 'phone') {
      if (!command.phone) return follow(event, 'phone', "What's the phone number?");
      const saved = await calendar.update(event.id, { phone: command.phone });
      const reply = `The phone number for ${saved.title} is ${saved.phone || command.phone}.`;
      return { reply, spoken: reply, highlightId: saved.id, focusDate: new Date(saved.start) };
    }
    if (action === 'clear') {
      const patch = {};
      if (command.field === 'phone') patch.phone = '';
      else if (command.field === 'notes') patch.notes = '';
      else patch.location = '';
      const saved = await calendar.update(event.id, patch);
      const label = command.field === 'notes' ? 'note' : command.field;
      const reply = `I removed the ${label} from ${saved.title}.`;
      return { reply, spoken: reply, highlightId: saved.id, focusDate: new Date(saved.start) };
    }
    if (action === 'remind') {
      const saved = await calendar.update(event.id, { important: true });
      const reply = `I'll remind you an hour before ${saved.title}, and the day before if it's later than that.`;
      return { reply, spoken: reply, highlightId: saved.id, focusDate: new Date(saved.start) };
    }
    if (action === 'forget') {
      const saved = await calendar.update(event.id, { important: false });
      const reply = `I won't remind you about ${saved.title}.`;
      return { reply, spoken: reply, highlightId: saved.id, focusDate: new Date(saved.start) };
    }
    if (action === 'notes') {
      if (!command.notes) return follow(event, 'note', 'What should the note say?');
      const saved = await calendar.update(event.id, { notes: command.notes });
      return { reply: `Added a note to ${saved.title}.`, spoken: lines.notes(saved.title), highlightId: saved.id, focusDate: new Date(saved.start), openEvent: saved };
    }
    if (action === 'duration') {
      if (!command.durationMinutes) return follow(event, 'length', 'How long?', { mode: command.durationMode || 'set' });
      const start = new Date(event.start);
      let end = new Date(event.end);
      if (command.durationMode === 'set') end = addMinutes(start, command.durationMinutes);
      if (command.durationMode === 'extend') end = addMinutes(end, command.durationMinutes);
      if (command.durationMode === 'shorten') end = addMinutes(end, -command.durationMinutes);
      if (end <= start) return { reply: 'That would make the appointment end before it starts.', spoken: lines.badDuration() };
      const saved = await calendar.update(event.id, { end: end.toISOString() });
      return {
        reply: `${saved.title} now ends at ${formatClock(new Date(saved.end))}.`,
        spoken: lines.ends(saved.title, atClock(saved.end)),
        highlightId: saved.id,
        focusDate: new Date(saved.start),
      };
    }
    return { reply: lines.help(), spoken: lines.help() };
  }

  async function undoLast() {
    try {
      const result = await calendar.undo();
      return {
        reply: result.message,
        spoken: result.message,
        focusDate: result.focusDate ? new Date(result.focusDate) : undefined,
        highlightId: result.event?.id,
      };
    } catch (error) {
      return { reply: error.message || 'Nothing to undo.', spoken: lines.nothingToUndo() };
    }
  }

  async function resolvePlace(name) {
    const place = String(name || '').trim();
    if (!place || SKIP_PLACE.test(place) || /\d/.test(place)) return { location: place, phone: '', lookedUp: false };
    try {
      const found = await lookupPlace(place);
      if (!found?.address) return { location: place, phone: '', lookedUp: false };
      return { location: found.address, phone: found.phone || '', lookedUp: true };
    } catch {
      return { location: place, phone: '', lookedUp: false };
    }
  }

  async function question(command) {
    const about = String(command.query || '').match(/^(?:where(?:'s| is)|what(?:'s| is) the (?:address|phone number) (?:of|for))\s+(?:my\s+|the\s+)?(.+?)(?:\s+appointment)?$/i);
    if (about) {
      const matches = findCandidates(await upcoming(), about[1], now());
      if (matches.length === 1) {
        const event = matches[0];
        const place = await resolvePlace(event.location || about[1]);
        const location = place.location || event.location;
        const phone = place.phone || event.phone || '';
        if (place.lookedUp) {
          await calendar.update(event.id, { location, phone });
        }
        let reply = location ? `${event.title} is at ${location}.` : `I don't have an address for ${event.title}.`;
        if (phone) reply += ` Phone ${phone}.`;
        return { reply, spoken: reply, focusDate: new Date(event.start), highlightId: event.id };
      }
    }
    try {
      const result = await ask(command.query);
      const reply = result?.reply || "I couldn't find an answer for that.";
      return { reply, spoken: result?.spoken || reply };
    } catch {
      return { reply: "I couldn't look that up.", spoken: "I couldn't look that up." };
    }
  }

  async function add(command) {
    const start = command.start;
    const end = command.end ?? addMinutes(start, command.durationMinutes || 60);
    const place = await resolvePlace(command.location || '');
    const saved = await calendar.create({
      title: command.title || 'Appointment',
      start: start.toISOString(),
      end: end.toISOString(),
      location: place.location,
      phone: place.phone,
      notes: '',
      important: Boolean(command.important),
    });
    const overlap = (await upcoming()).find((event) => event.id !== saved.id && rangesOverlap(saved, event));
    let reply = `Added ${saved.title} ${formatWhen(new Date(saved.start), now())}.`;
    if (command.assumedTime) reply += ' I set it for 9 AM. Tell me if you want a different time.';
    if (place.lookedUp) reply += ` The address is ${place.location}.`;
    if (place.phone) reply += ` The phone number is ${place.phone}.`;
    if (command.important) reply += ' I will remind you an hour before.';
    if (overlap) reply += ` That overlaps ${overlap.title}.`;
    reply += zoneNote(command);
    let spoken = lines.added(saved.title, whenSpoken(saved.start, now()), command.assumedTime, overlap?.title);
    if (place.lookedUp) spoken += ` The address is ${place.location}.`;
    if (place.phone) spoken += ` The phone number is ${place.phone}.`;
    if (command.important) spoken += ' I will remind you an hour before.';
    spoken += zoneNote(command);
    return {
      reply,
      spoken,
      focusDate: new Date(saved.start),
      highlightId: saved.id,
    };
  }

  async function list(command) {
    const events = (await upcoming())
      .filter((event) => {
        const start = new Date(event.start).getTime();
        const end = new Date(event.end).getTime();
        return start < command.rangeEnd.getTime() && end > command.rangeStart.getTime();
      })
      .sort((a, b) => new Date(a.start) - new Date(b.start));
    const range = rangeSpoken(command);
    if (!events.length) {
      return { reply: `Nothing on your calendar ${command.rangeLabel}.`, spoken: lines.listEmpty(range), focusDate: command.rangeStart };
    }
    const summary = events.map((event) => (
      spansMultipleDays(event) ? event.title : `${event.title} at ${formatClock(new Date(event.start))}`
    )).join(', ');
    const count = events.length === 1 ? '1 appointment' : `${events.length} appointments`;
    return {
      reply: `You have ${count} ${command.rangeLabel}: ${summary}.`,
      spoken: lines.list(range, count, summary, ''),
      focusDate: command.rangeStart,
    };
  }

  async function nextEvent() {
    const events = (await upcoming())
      .filter((event) => new Date(event.end) > now())
      .sort((a, b) => new Date(a.start) - new Date(b.start));
    if (!events.length) return { reply: 'Nothing coming up in the next 6 months.', spoken: lines.nothingNext() };
    const event = events[0];
    return {
      reply: `Next is ${event.title} ${formatWhen(new Date(event.start), now())}.`,
      spoken: lines.next(event.title, whenSpoken(event.start, now())),
      focusDate: new Date(event.start),
      highlightId: event.id,
    };
  }
}
