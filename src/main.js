import './styles.css';
import { createAssistant } from './assistant.js';
import { createCalendar } from './calendar.js';
import { addDays, dateInputValue, eventCoversDay, formatClock, formatDayHeading, fromDateAndTime, spansMultipleDays, startOfDay, startOfMonth, startOfWeek, timeInputValue } from './dates.js';
import { canSpeak, createSpeechController, silence, speak, transcribePcm16, unlockSpeech } from './speech.js';
import { commandAfterWake } from './wake.js';
import { lines } from './voice.js';

const EXAMPLES = [
  'Add lunch tomorrow at 8 PM',
  "What's on today?",
  'Move lunch to Friday at 4',
];

const calendar = createCalendar();
const assistant = createAssistant({ calendar });
const overlay = new URLSearchParams(location.search).get('overlay') === '1';
const settings = calendar.settings();
if (typeof settings.speak !== 'boolean') calendar.saveSettings({ speak: true });

const todayLabel = document.querySelector('#today-label');
const dayHeading = document.querySelector('#day-heading');
const monthDayHeading = document.querySelector('#month-day-heading');
const monthLabel = document.querySelector('#month-label');
const monthGrid = document.querySelector('#month-grid');
const monthAgenda = document.querySelector('#month-agenda');
const daysEl = document.querySelector('#days');
const agendaEl = document.querySelector('#agenda');
const threadEl = document.querySelector('#thread');
const examplesEl = document.querySelector('#examples');
const noticeEl = document.querySelector('#notice');
const liveEl = document.querySelector('#live');
const commandEl = document.querySelector('#command');
const composerEl = document.querySelector('#composer');
const micEl = document.querySelector('#mic');
const micHint = document.querySelector('#mic-hint');
const installEl = document.querySelector('#install');
const eventDialog = document.querySelector('#event-dialog');
const settingsDialog = document.querySelector('#settings-dialog');
const eventForm = document.querySelector('#event-form');
const eventError = document.querySelector('#event-error');
const settingsError = document.querySelector('#settings-error');

let selected = startOfDay(new Date());
let visibleMonth = startOfMonth(new Date());
let events = [];
let editing = null;
let installEvent = null;
let view = 'month';
let unread = false;
let noticeTimer = 0;

todayLabel.textContent = new Intl.DateTimeFormat(undefined, {
  weekday: 'long',
  month: 'long',
  day: 'numeric',
}).format(new Date());

const speech = createSpeechController({
  onListen(active) {
    setListening(active);
  },
  onTranscript(text) {
    commandEl.value = text;
  },
  onFinal(text) {
    commandEl.value = text;
    submit(text);
  },
  onStatus(text) {
    setHint(text);
  },
  onError(message) {
    setHint('');
    push('assistant', message, { spoken: voiceForError(message) });
  },
});

function voiceForError(message) {
  return message;
}

function setHint(text) {
  micHint.textContent = text === 'Listening…' && !micEl.classList.contains('is-live') ? '' : text;
}

function setListening(active) {
  micEl.classList.toggle('is-live', active);
  micEl.setAttribute('aria-pressed', String(active));
  micHint.textContent = active ? 'Listening…' : (micHint.textContent === 'Listening…' ? '' : micHint.textContent);
}

function push(role, text, options = {}) {
  const bubble = document.createElement('p');
  bubble.className = `bubble ${role}`;
  const who = document.createElement('span');
  who.className = 'who';
  who.textContent = role === 'user' ? 'You' : 'Vella';
  bubble.append(who, document.createTextNode(text));
  threadEl.append(bubble);
  while (threadEl.children.length > 12) threadEl.firstChild.remove();
  threadEl.scrollTop = threadEl.scrollHeight;
  if (role === 'assistant') {
    liveEl.textContent = text;
    if (!options.quiet) {
      showNotice(text, Boolean(options.followUp));
      if (view !== 'chat') {
        unread = true;
        document.querySelector('#view-chat').classList.add('has-new');
      }
    }
    if (options.silent) {
      window.VellaNative?.turnDone?.();
      return;
    }
    const spoken = options.spoken || text;
    if (overlay) window.VellaNative?.reply?.(spoken, Boolean(options.followUp));
    else if (calendar.settings().speak !== false && canSpeak()) speak(spoken);
    window.VellaNative?.turnDone?.();
  }
}

function showNotice(text, keep) {
  noticeEl.textContent = text;
  noticeEl.hidden = view === 'chat';
  window.clearTimeout(noticeTimer);
  if (keep) return;
  noticeTimer = window.setTimeout(() => {
    noticeEl.hidden = true;
    noticeEl.textContent = '';
  }, 6500);
}

function syncUndo() {
  const button = document.querySelector('#undo');
  button.hidden = false;
  button.disabled = !calendar.canUndo();
}

function sameDay(a, b) {
  return startOfDay(a).getTime() === startOfDay(b).getTime();
}

function renderDays() {
  daysEl.replaceChildren();
  for (let offset = 0; offset < 14; offset += 1) {
    const date = new Date();
    date.setHours(0, 0, 0, 0);
    date.setDate(date.getDate() + offset);
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'day';
    button.setAttribute('role', 'tab');
    button.setAttribute('aria-selected', String(sameDay(date, selected)));
    const isSelected = sameDay(date, selected);
    if (isSelected) button.classList.add('is-selected');
    if (events.some((event) => eventCoversDay(event, date))) button.classList.add('has-events');
    const name = document.createElement('span');
    name.textContent = new Intl.DateTimeFormat(undefined, { weekday: 'short' }).format(date);
    const number = document.createElement('strong');
    number.textContent = String(date.getDate());
    const dot = document.createElement('i');
    dot.className = 'dot';
    button.append(name, number, dot);
    button.addEventListener('click', () => {
      selected = startOfDay(date);
      render();
    });
    daysEl.append(button);
    if (isSelected) {
      const left = button.offsetLeft - (daysEl.clientWidth - button.offsetWidth) / 2;
      daysEl.scrollLeft = Math.max(0, left);
    }
  }
}

function eventsOn(date) {
  return events
    .filter((event) => eventCoversDay(event, date))
    .sort((a, b) => new Date(a.start) - new Date(b.start) || a.title.localeCompare(b.title));
}

function inclusiveEnd(event) {
  const end = new Date(event.end);
  const midnight = end.getHours() === 0 && end.getMinutes() === 0 && end.getSeconds() === 0 && end.getMilliseconds() === 0;
  if (midnight && end > new Date(event.start)) return addDays(end, -1);
  return end;
}

function spanLabel(event) {
  const fmt = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' });
  return `${fmt.format(new Date(event.start))} – ${fmt.format(inclusiveEnd(event))}`;
}

function eventCard(event, highlightId) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'event';
  if (event.id === highlightId) button.classList.add('is-hit');
  if (event.holiday) button.classList.add('is-holiday');
  const when = document.createElement('div');
  when.className = 'when';
  const multi = spansMultipleDays(event);
  when.textContent = event.allDay
    ? 'All day'
    : multi && !sameDay(event.start, selected)
      ? 'Continues'
      : formatClock(new Date(event.start));
  const title = document.createElement('div');
  title.className = 'title';
  title.textContent = event.title;
  button.append(when, title);
  const detail = [
    multi ? spanLabel(event) : event.allDay ? '' : `until ${formatClock(new Date(event.end))}`,
    event.location,
    event.phone,
  ].filter(Boolean).join(' · ');
  if (detail) {
    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.textContent = detail;
    button.append(meta);
  }
  button.addEventListener('click', () => openEvent(event));
  return button;
}

function fillAgenda(container, heading, highlightId, continuousFirst = false) {
  heading.textContent = formatDayHeading(selected);
  container.replaceChildren();
  const visible = eventsOn(selected).slice().sort((a, b) => {
    if (continuousFirst) {
      const rank = Number(spansMultipleDays(b)) - Number(spansMultipleDays(a));
      if (rank) return rank;
    }
    return new Date(a.start) - new Date(b.start) || a.title.localeCompare(b.title);
  });
  if (!visible.length) {
    const empty = document.createElement('p');
    empty.className = 'empty';
    empty.textContent = 'Nothing on this day.';
    container.append(empty);
    return;
  }
  for (const event of visible) container.append(eventCard(event, highlightId));
  if (highlightId) container.querySelector('.is-hit')?.scrollIntoView({ block: 'nearest' });
}

function layoutSpans(list, weekStart) {
  const weekEnd = addDays(weekStart, 7);
  const pieces = list
    .filter((event) => spansMultipleDays(event))
    .map((event) => {
      const start = startOfDay(event.start);
      const last = startOfDay(inclusiveEnd(event));
      const segStart = start < weekStart ? weekStart : start;
      const segLast = last >= weekEnd ? addDays(weekEnd, -1) : last;
      if (segStart > segLast) return null;
      const col = Math.round((segStart - weekStart) / 86400000);
      const length = Math.round((segLast - segStart) / 86400000) + 1;
      return {
        title: event.title,
        col,
        length,
        continuesBefore: start < weekStart,
        continuesAfter: last >= weekEnd,
        holiday: Boolean(event.holiday),
      };
    })
    .filter(Boolean)
    .sort((a, b) => a.col - b.col || b.length - a.length);
  const laneEnds = [];
  const placed = [];
  const overflow = Array(7).fill(0);
  for (const piece of pieces) {
    let lane = laneEnds.findIndex((endCol) => endCol <= piece.col);
    if (lane === -1) {
      if (laneEnds.length < 2) {
        lane = laneEnds.length;
        laneEnds.push(piece.col + piece.length);
      } else {
        for (let i = 0; i < piece.length; i += 1) overflow[piece.col + i] += 1;
        continue;
      }
    } else {
      laneEnds[lane] = piece.col + piece.length;
    }
    placed.push({ ...piece, lane });
  }
  return { placed, overflow };
}

function renderMonth(highlightId) {
  const monthOptions = { month: 'long' };
  if (visibleMonth.getFullYear() !== new Date().getFullYear()) monthOptions.year = 'numeric';
  monthLabel.textContent = new Intl.DateTimeFormat(undefined, monthOptions).format(visibleMonth);
  monthGrid.replaceChildren();
  const first = startOfWeek(startOfMonth(visibleMonth));
  const weekCount = weeksInView(visibleMonth);
  for (let week = 0; week < weekCount; week += 1) {
    const weekStart = addDays(first, week * 7);
    const row = document.createElement('div');
    row.className = 'month-week';
    const { placed, overflow } = layoutSpans(events, weekStart);
    for (let day = 0; day < 7; day += 1) {
      const date = addDays(weekStart, day);
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'cell';
      button.setAttribute('role', 'gridcell');
      if (date.getMonth() !== visibleMonth.getMonth()) button.classList.add('is-outside');
      if (sameDay(date, new Date())) button.classList.add('is-today');
      if (sameDay(date, selected)) button.classList.add('is-selected');
      button.style.gridColumn = String(day + 1);
      button.style.gridRow = '1';
      const lanesHere = placed.filter((piece) => day >= piece.col && day < piece.col + piece.length).length;
      const number = document.createElement('span');
      number.className = 'num';
      number.textContent = String(date.getDate());
      button.append(number);
      if (lanesHere) {
        const spacer = document.createElement('span');
        spacer.className = 'span-spacer';
        spacer.style.height = `${lanesHere * 16 + 8}px`;
        button.append(spacer);
      }
      const dayEvents = eventsOn(date).filter((event) => !spansMultipleDays(event));
      for (const event of dayEvents.slice(0, 2)) {
        const line = document.createElement('span');
        line.className = 'ev';
        if (event.holiday) line.classList.add('is-holiday');
        line.textContent = event.title;
        button.append(line);
      }
      const extra = Math.max(0, dayEvents.length - 2) + overflow[day];
      if (extra > 0) {
        const more = document.createElement('span');
        more.className = 'more';
        more.textContent = `+${extra}`;
        button.append(more);
      }
      button.addEventListener('click', () => {
        selected = startOfDay(date);
        if (date.getMonth() !== visibleMonth.getMonth() || date.getFullYear() !== visibleMonth.getFullYear()) {
          visibleMonth = startOfMonth(date);
          refresh();
          return;
        }
        render();
      });
      row.append(button);
    }
    for (const piece of placed) {
      const bar = document.createElement('div');
      bar.className = 'span-bar';
      if (piece.holiday) bar.classList.add('is-holiday');
      if (!piece.continuesBefore) bar.classList.add('is-start');
      if (!piece.continuesAfter) bar.classList.add('is-end');
      bar.style.gridColumn = `${piece.col + 1} / ${piece.col + piece.length + 1}`;
      bar.style.gridRow = '1';
      bar.style.marginTop = `calc(${10 + piece.lane * 16}px + 1.35rem)`;
      bar.textContent = piece.title;
      row.append(bar);
    }
    monthGrid.append(row);
  }
  fillAgenda(monthAgenda, monthDayHeading, highlightId, true);
}

function showView(next) {
  view = next;
  document.querySelector('#panel-month').hidden = view !== 'month';
  document.querySelector('#panel-day').hidden = view !== 'day';
  document.querySelector('#panel-chat').hidden = view !== 'chat';
  for (const name of ['month', 'day', 'chat']) {
    document.querySelector(`#view-${name}`).setAttribute('aria-selected', String(view === name));
  }
  if (view === 'chat') {
    unread = false;
    document.querySelector('#view-chat').classList.remove('has-new');
    noticeEl.hidden = true;
    threadEl.scrollTop = threadEl.scrollHeight;
  } else if (noticeEl.textContent) {
    noticeEl.hidden = false;
  }
}

function weeksInView(monthDate) {
  const first = startOfWeek(startOfMonth(monthDate));
  const last = new Date(monthDate.getFullYear(), monthDate.getMonth() + 1, 0);
  const lastWeek = startOfWeek(last);
  return Math.round((lastWeek.getTime() - first.getTime()) / 604800000) + 1;
}

function render(highlightId) {
  renderDays();
  fillAgenda(agendaEl, dayHeading, highlightId);
  renderMonth(highlightId);
}

function loadedRange() {
  const gridStart = startOfWeek(startOfMonth(visibleMonth));
  const gridEnd = addDays(gridStart, weeksInView(visibleMonth) * 7);
  const today = startOfDay(new Date());
  const early = addDays(today, -1);
  const late = addDays(today, 183);
  return {
    from: gridStart.getTime() < early.getTime() ? gridStart : early,
    to: gridEnd.getTime() > late.getTime() ? gridEnd : late,
  };
}

async function refresh(highlightId) {
  const range = loadedRange();
  events = await calendar.listBetween(range.from, range.to);
  render(highlightId);
}

function openEvent(event) {
  editing = event;
  document.querySelector('#event-heading').textContent = 'Edit appointment';
  document.querySelector('#event-title').value = event.title;
  document.querySelector('#event-date').value = dateInputValue(event.start);
  document.querySelector('#event-end-date').value = dateInputValue(inclusiveEnd(event));
  document.querySelector('#event-start').value = event.allDay ? '09:00' : timeInputValue(event.start);
  document.querySelector('#event-end').value = event.allDay ? '17:00' : timeInputValue(inclusiveEnd(event));
  document.querySelector('#event-location').value = event.location || '';
  document.querySelector('#event-phone').value = event.phone || '';
  document.querySelector('#event-important').checked = Boolean(event.important);
  document.querySelector('#event-notes').value = event.notes || '';
  eventError.hidden = true;
  const link = document.querySelector('#event-google');
  if (event.htmlLink) {
    link.href = event.htmlLink;
    link.hidden = false;
  } else {
    link.hidden = true;
  }
  document.querySelector('#event-delete').hidden = false;
  eventDialog.showModal();
}

function newAppointment() {
  editing = null;
  const start = new Date();
  start.setMinutes(0, 0, 0);
  start.setHours(start.getHours() + 1);
  const end = new Date(start.getTime() + 60 * 60 * 1000);
  document.querySelector('#event-heading').textContent = 'New appointment';
  document.querySelector('#event-title').value = '';
  document.querySelector('#event-date').value = dateInputValue(start);
  document.querySelector('#event-end-date').value = dateInputValue(end);
  document.querySelector('#event-start').value = timeInputValue(start);
  document.querySelector('#event-end').value = timeInputValue(end);
  document.querySelector('#event-location').value = '';
  document.querySelector('#event-phone').value = '';
  document.querySelector('#event-important').checked = false;
  document.querySelector('#event-notes').value = '';
  eventError.hidden = true;
  document.querySelector('#event-google').hidden = true;
  document.querySelector('#event-delete').hidden = true;
  eventDialog.showModal();
  document.querySelector('#event-title').focus();
}

async function submit(text) {
  const value = text.trim();
  if (!value) return null;
  commandEl.value = '';
  push('user', value);
  try {
    const result = await assistant.handle(value);
    if (result.focusDate) selected = startOfDay(result.focusDate);
    await refresh(result.highlightId);
    push('assistant', result.reply, { spoken: result.spoken, followUp: result.followUp });
    if (result.openEvent) openEvent(events.find((event) => event.id === result.openEvent.id) || result.openEvent);
    return result;
  } catch (error) {
    push('assistant', error.message || 'Something went wrong talking to the calendar.');
    if (error.code === 'auth' || error.code === 'config') settingsDialog.showModal();
    return null;
  } finally {
    syncUndo();
  }
}

commandEl.addEventListener('keydown', (event) => {
  if (event.key !== 'Enter' || event.shiftKey) return;
  event.preventDefault();
  composerEl.requestSubmit();
});

composerEl.addEventListener('submit', (event) => {
  event.preventDefault();
  unlockSpeech();
  submit(commandEl.value);
});

micEl.addEventListener('click', () => {
  unlockSpeech();
  silence();
  if (window.VellaNative?.listen) {
    if (micEl.classList.contains('is-live')) return;
    setHint('Opening the mic…');
    window.VellaNative.listen();
    return;
  }
  speech.toggle();
});

for (const name of ['month', 'day', 'chat']) {
  document.querySelector(`#view-${name}`).addEventListener('click', () => showView(name));
}
function shiftMonth(delta) {
  const next = new Date(visibleMonth);
  next.setMonth(next.getMonth() + delta);
  visibleMonth = startOfMonth(next);
  const today = startOfDay(new Date());
  const sameMonth = selected.getMonth() === visibleMonth.getMonth() && selected.getFullYear() === visibleMonth.getFullYear();
  if (!sameMonth) {
    selected = today.getMonth() === visibleMonth.getMonth() && today.getFullYear() === visibleMonth.getFullYear()
      ? today
      : startOfMonth(visibleMonth);
  }
  refresh();
}
document.querySelector('#month-prev').addEventListener('click', () => shiftMonth(-1));
document.querySelector('#month-next').addEventListener('click', () => shiftMonth(1));
noticeEl.addEventListener('click', () => showView('chat'));
for (const example of EXAMPLES) {
  const chip = document.createElement('button');
  chip.type = 'button';
  chip.className = 'chip';
  chip.textContent = example;
  chip.addEventListener('click', () => submit(example));
  examplesEl.append(chip);
}

document.querySelector('#settings-form').addEventListener('submit', (event) => event.preventDefault());
document.querySelector('#settings-open').addEventListener('click', () => openSettings());
document.querySelector('#settings-close').addEventListener('click', () => settingsDialog.close());
document.querySelector('#event-close').addEventListener('click', () => eventDialog.close());

function openSettings() {
  const current = calendar.settings();
  document.querySelector('#client-id').value = current.clientId || '';
  document.querySelector('#speak-toggle').checked = current.speak !== false;
  document.querySelector('#origin').textContent = location.origin;
  settingsError.hidden = true;
  const connected = calendar.connected();
  document.querySelector('#connect').hidden = connected;
  document.querySelector('#disconnect').hidden = !connected;
  settingsDialog.showModal();
  refreshImportButton();
}

async function refreshImportButton() {
  const button = document.querySelector('#import-local');
  const count = await calendar.localCount();
  button.hidden = !(calendar.connected() && count);
  button.textContent = `Copy ${count} phone appointment${count === 1 ? '' : 's'} to Google`;
}

document.querySelector('#speak-toggle').addEventListener('change', (event) => {
  calendar.saveSettings({ speak: event.target.checked });
  if (!event.target.checked) silence();
});

document.querySelector('#client-id').addEventListener('change', (event) => {
  calendar.saveSettings({ clientId: event.target.value.trim() });
});

document.querySelector('#connect').addEventListener('click', async () => {
  settingsError.hidden = true;
  calendar.saveSettings({ clientId: document.querySelector('#client-id').value.trim() });
  try {
    await calendar.connect();
    await refresh();
    settingsDialog.close();
    push('assistant', 'Connected to Google Calendar. New commands will add and edit appointments there.', { spoken: lines.connected() });
  } catch (error) {
    settingsError.hidden = false;
    settingsError.textContent = error.message;
  }
});

document.querySelector('#disconnect').addEventListener('click', async () => {
  calendar.disconnect();
  await refresh();
  openSettings();
  push('assistant', 'Disconnected. Appointments you add now stay on this phone.', { spoken: lines.disconnected() });
});

document.querySelector('#import-local').addEventListener('click', async () => {
  settingsError.hidden = true;
  try {
    const count = await calendar.importLocalToGoogle();
    await refresh();
    settingsDialog.close();
    push('assistant', `Copied ${count} appointment${count === 1 ? '' : 's'} to Google Calendar.`, { spoken: lines.copied(count) });
  } catch (error) {
    settingsError.hidden = false;
    settingsError.textContent = error.message;
  }
});

eventForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  eventError.hidden = true;
  const dateValue = document.querySelector('#event-date').value;
  const endDateValue = document.querySelector('#event-end-date').value || dateValue;
  const startValue = document.querySelector('#event-start').value;
  const endValue = document.querySelector('#event-end').value;
  const start = fromDateAndTime(dateValue, startValue);
  const end = fromDateAndTime(endDateValue, endValue);
  if (end <= start) {
    eventError.hidden = false;
    eventError.textContent = 'The end time has to be after the start.';
    return;
  }
  const patch = {
    title: document.querySelector('#event-title').value.trim() || 'Appointment',
    location: document.querySelector('#event-location').value.trim(),
    phone: document.querySelector('#event-phone').value.trim(),
    important: document.querySelector('#event-important').checked,
    notes: document.querySelector('#event-notes').value.trim(),
    start: start.toISOString(),
    end: end.toISOString(),
  };
  try {
    const saved = editing
      ? await calendar.update(editing.id, patch)
      : await calendar.create(patch);
    selected = startOfDay(start);
    eventDialog.close();
    await refresh(saved.id);
    push('assistant', `Saved ${saved.title}.`, { silent: true, quiet: true });
  } catch (error) {
    eventError.hidden = false;
    eventError.textContent = error.message;
  } finally {
    syncUndo();
  }
});

document.querySelector('#undo').addEventListener('click', async () => {
  try {
    const result = await calendar.undo();
    if (result.focusDate) selected = startOfDay(new Date(result.focusDate));
    await refresh(result.event?.id);
    push('assistant', result.message, { silent: true, quiet: true });
  } catch (error) {
    push('assistant', error.message || 'Nothing to undo.', { silent: true, quiet: true });
  } finally {
    syncUndo();
  }
});

document.querySelector('#event-delete').addEventListener('click', async () => {
  if (!editing) return;
  const title = editing.title;
  eventError.hidden = true;
  try {
    await calendar.remove(editing.id);
    editing = null;
    eventDialog.close();
    await refresh();
    push('assistant', `Deleted ${title}.`, { silent: true, quiet: true });
  } catch (error) {
    eventError.hidden = false;
    eventError.textContent = error.message;
  } finally {
    syncUndo();
  }
});

window.addEventListener('beforeinstallprompt', (event) => {
  event.preventDefault();
  installEvent = event;
  installEl.hidden = false;
});

installEl.addEventListener('click', async () => {
  if (!installEvent) return;
  installEvent.prompt();
  await installEvent.userChoice;
  installEvent = null;
  installEl.hidden = true;
});

if (import.meta.env.PROD && 'serviceWorker' in navigator && !window.VellaNative && location.protocol !== 'file:') {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`).catch(() => {});
  });
}

if (window.VellaNative) document.querySelector('#google-setup')?.setAttribute('hidden', '');

window.__vellaSubmit = (text) => submit(text);
window.__vellaAdd = () => newAppointment();
window.__vellaDraft = (text) => {
  commandEl.value = text;
};
window.__vellaStatus = (text) => {
  setHint(text);
};
window.__vellaListening = (active) => {
  setListening(Boolean(active));
};
window.__vellaRefresh = () => {
  calendar.invalidateCache();
  calendar.reloadUndo();
  return refresh().then(() => syncUndo());
};

let wakeAwaiting = false;
let wakeBusy = false;
let ignoreHearUntil = 0;
window.__vellaExpectCommand = () => {
  wakeAwaiting = true;
};

window.__vellaHear = async (audio) => {
  if (wakeBusy || Date.now() < ignoreHearUntil) return;
  wakeBusy = true;
  try {
    let text = '';
    try {
      text = await transcribePcm16(audio);
    } catch {
      return;
    }
    const command = commandAfterWake(text);
    if (command === null && !wakeAwaiting) return;
    if (command === '') {
      wakeAwaiting = true;
      ignoreHearUntil = Date.now() + 1600;
      speak('Yes?');
      return;
    }
    const value = command === null ? text : command;
    if (!value) return;
    wakeAwaiting = false;
    ignoreHearUntil = Date.now() + 2200;
    const result = await submit(value);
    wakeAwaiting = Boolean(result?.followUp);
  } finally {
    wakeBusy = false;
  }
};

const wakeRow = document.querySelector('#wake-row');
const wakeToggle = document.querySelector('#wake-toggle');
if (window.VellaNative?.setWake) {
  wakeRow.hidden = false;
  const wakeOn = calendar.settings().wake !== false;
  wakeToggle.checked = wakeOn;
  window.VellaNative.setWake(wakeOn);
}
wakeToggle.addEventListener('change', (event) => {
  calendar.saveSettings({ wake: event.target.checked });
  window.VellaNative?.setWake?.(event.target.checked);
});

refresh().then(() => syncUndo()).catch((error) => {
  syncUndo();
  push('assistant', error.message);
});
if (!overlay) {
  push('assistant', window.VellaNative
    ? 'Tell me what to add or change. I will update the calendar on this phone.'
    : calendar.connected()
      ? 'Google Calendar is connected. Tell me what to add or change.'
      : 'Tell me what to add. Appointments stay on this phone until you connect Google Calendar in settings.', { silent: true, quiet: true });
}
window.VellaNative?.pageReady();
