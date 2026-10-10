import { addMinutes, formatClock, formatWhen, rangesOverlap, spansMultipleDays } from './dates.js';
import { findCandidates, numberFrom, parseCommand, parseWhen } from './parse.js';
import { atClock, lines, rangeSpoken, whenSpoken } from './voice.js';

const HELP = 'You can say: add dentist tomorrow at 3 PM. Move dentist to Friday at 4. Rename dentist to Dental checkup. Cancel dentist. What is on my calendar today?';

export function createAssistant({ calendar, now = () => new Date() }) {
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
    });
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
        return { reply: HELP, spoken: lines.help() };
      case 'list':
        return list(command);
      case 'next':
        return nextEvent();
      case 'add':
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
        return choose(command);
      default:
        return { reply: `I didn't catch a calendar command. ${HELP}`, spoken: lines.missed() };
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
    const matches = findCandidates(await upcoming(), command.query, now());
    if (!matches.length) {
      return { reply: `I couldn't find “${command.query}” in the next 6 months.`, spoken: lines.notFound(command.query) };
    }
    if (matches.length > 1 && command.intent === 'cancel' && command.every) return cancelMany(matches);
    if (matches.length > 1) {
      pending = { type: 'choose', action: command.intent, command, matches };
      const choices = matches.slice(0, 4).map((event, index) => `${index + 1}, ${event.title} ${formatWhen(new Date(event.start), now())}`);
      const spokenChoices = matches.slice(0, 4).map((event, index) => `${index + 1}, ${event.title}, ${whenSpoken(event.start, now())}`);
      return {
        reply: `I found ${matches.length} appointments. Say a number: ${choices.join('; ')}.`,
        spoken: lines.choose(matches.length, spokenChoices),
        followUp: true,
      };
    }
    return act(command.intent, matches[0], command);
  }

  async function cancelMany(matches) {
    if (typeof calendar.removeMany === 'function') await calendar.removeMany(matches.map((event) => event.id));
    else {
      for (const event of matches) await calendar.remove(event.id);
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
      await calendar.remove(event.id);
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
      if (!command.start) return { reply: `When should I move ${event.title}?`, spoken: lines.askMove(event.title), followUp: true };
      const previous = new Date(event.end) - new Date(event.start);
      const end = command.end ?? new Date(command.start.getTime() + previous);
      const saved = await calendar.update(event.id, {
        start: command.start.toISOString(),
        end: end.toISOString(),
      });
      return {
        reply: `Moved ${saved.title} to ${formatWhen(new Date(saved.start), now())}.`,
        spoken: lines.moved(saved.title, whenSpoken(saved.start, now())),
        focusDate: new Date(saved.start),
        highlightId: saved.id,
      };
    }
    if (action === 'rename') {
      const saved = await calendar.update(event.id, { title: command.title });
      return { reply: `Renamed it to ${saved.title}.`, spoken: lines.renamed(saved.title), highlightId: saved.id, focusDate: new Date(saved.start) };
    }
    if (action === 'location') {
      const saved = await calendar.update(event.id, { location: command.location });
      return { reply: `Location for ${saved.title} is ${saved.location}.`, spoken: lines.location(saved.title, saved.location), highlightId: saved.id, focusDate: new Date(saved.start) };
    }
    if (action === 'notes') {
      const saved = await calendar.update(event.id, { notes: command.notes });
      return { reply: `Added a note to ${saved.title}.`, spoken: lines.notes(saved.title), highlightId: saved.id, focusDate: new Date(saved.start), openEvent: saved };
    }
    if (action === 'duration') {
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
    return { reply: HELP, spoken: lines.help() };
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

  async function add(command) {
    const start = command.start;
    const end = command.end ?? addMinutes(start, command.durationMinutes || 60);
    const saved = await calendar.create({
      title: command.title || 'Appointment',
      start: start.toISOString(),
      end: end.toISOString(),
      location: command.location || '',
      notes: '',
    });
    const overlap = (await upcoming()).find((event) => event.id !== saved.id && rangesOverlap(saved, event));
    let reply = `Added ${saved.title} ${formatWhen(new Date(saved.start), now())}.`;
    if (command.assumedTime) reply += ' I set it for 9 AM. Tell me if you want a different time.';
    if (overlap) reply += ` That overlaps ${overlap.title}.`;
    return {
      reply,
      spoken: lines.added(saved.title, whenSpoken(saved.start, now()), command.assumedTime, overlap?.title),
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
