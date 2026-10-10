import assert from 'node:assert/strict';
import test from 'node:test';
import { createAssistant } from '../src/assistant.js';

function memoryCalendar(seed = []) {
  let events = seed.map((event) => ({ location: '', notes: '', allDay: false, ...event }));
  let seq = events.length;
  return {
    async listUpcoming() {
      return events.filter((event) => {
        const start = new Date(event.start).getTime();
        const end = new Date(event.end).getTime();
        return start < new Date(2027, 3, 9).getTime() && end > new Date(2026, 9, 8).getTime();
      });
    },
    async create(event) {
      seq += 1;
      const saved = { id: `id-${seq}`, htmlLink: '', allDay: false, ...event };
      events.push(saved);
      return saved;
    },
    async update(id, patch) {
      const event = events.find((item) => item.id === id);
      if (patch.title != null) event.title = patch.title;
      if (patch.location != null) event.location = patch.location;
      if (patch.notes != null) event.notes = patch.notes;
      if (patch.start) event.start = patch.start;
      if (patch.end) event.end = patch.end;
      return event;
    },
    async remove(id) {
      events = events.filter((event) => event.id !== id);
    },
    all: () => events,
  };
}

const now = () => new Date(2026, 9, 9, 15, 0, 0);

test('adds, lists, moves, and cancels an appointment', async () => {
  const calendar = memoryCalendar();
  const assistant = createAssistant({ calendar, now });

  const added = await assistant.handle('add dentist tomorrow at 3pm');
  assert.match(added.reply, /Added Dentist/);
  assert.match(added.spoken, /added Dentist/i);
  assert.match(added.spoken, /tomorrow/i);
  assert.match(added.spoken, /3:00 PM/);
  assert.doesNotMatch(added.spoken, /marquei/);
  assert.equal(calendar.all().length, 1);

  const listed = await assistant.handle("what's on my calendar tomorrow?");
  assert.match(listed.reply, /Dentist/);

  const moved = await assistant.handle('move dentist to Friday at 4');
  assert.match(moved.reply, /Moved Dentist/);
  assert.equal(new Date(calendar.all()[0].start).getDate(), 16);
  assert.equal(new Date(calendar.all()[0].start).getHours(), 16);

  const cancelled = await assistant.handle('cancel dentist');
  assert.match(cancelled.reply, /Cancelled Dentist/);
  assert.equal(calendar.all().length, 0);
});

test('undoes the last add and the last cancel', async () => {
  let events = [];
  let seq = 0;
  let record = null;
  const calendar = {
    async listUpcoming() {
      return events;
    },
    async create(event) {
      seq += 1;
      const saved = { id: `id-${seq}`, location: '', notes: '', allDay: false, ...event };
      events.push(saved);
      record = { type: 'create', event: saved };
      return saved;
    },
    async remove(id) {
      const event = events.find((item) => item.id === id);
      events = events.filter((item) => item.id !== id);
      record = event ? { type: 'delete', event } : record;
    },
    async undo() {
      if (!record) throw new Error('Nothing to undo.');
      const current = record;
      record = null;
      if (current.type === 'create') {
        events = events.filter((item) => item.id !== current.event.id);
        return { message: `Undone. I removed ${current.event.title}.` };
      }
      events.push(current.event);
      return { message: `Undone. I put ${current.event.title} back.`, event: current.event, focusDate: current.event.start };
    },
    all: () => events,
  };
  const assistant = createAssistant({ calendar, now });
  await assistant.handle('add dentist tomorrow at 3pm');
  const undoneAdd = await assistant.handle('undo');
  assert.match(undoneAdd.reply, /removed Dentist/i);
  assert.equal(calendar.all().length, 0);

  await assistant.handle('add dentist tomorrow at 3pm');
  await assistant.handle('cancel dentist');
  assert.equal(calendar.all().length, 0);
  const undoneCancel = await assistant.handle('undo that');
  assert.match(undoneCancel.reply, /put Dentist back/i);
  assert.equal(calendar.all().length, 1);
});

test('lists every appointment today', async () => {
  const calendar = memoryCalendar([
    { id: '1', title: 'Lunch', start: new Date(2026, 9, 9, 12).toISOString(), end: new Date(2026, 9, 9, 13).toISOString() },
    { id: '2', title: 'Dinner', start: new Date(2026, 9, 9, 18).toISOString(), end: new Date(2026, 9, 9, 19).toISOString() },
    { id: '3', title: 'Dessert', start: new Date(2026, 9, 9, 20).toISOString(), end: new Date(2026, 9, 9, 21).toISOString() },
    { id: '4', title: 'Call', start: new Date(2026, 9, 9, 9).toISOString(), end: new Date(2026, 9, 9, 10).toISOString() },
    { id: '5', title: 'Gym', start: new Date(2026, 9, 9, 7).toISOString(), end: new Date(2026, 9, 9, 8).toISOString() },
    { id: '6', title: 'Movie', start: new Date(2026, 9, 9, 21).toISOString(), end: new Date(2026, 9, 9, 23).toISOString() },
  ]);
  const assistant = createAssistant({ calendar, now });
  const listed = await assistant.handle("what's on my calendar today?");
  assert.match(listed.reply, /Lunch/);
  assert.match(listed.reply, /Dinner/);
  assert.match(listed.reply, /Dessert/);
  assert.match(listed.reply, /Call/);
  assert.match(listed.reply, /Gym/);
  assert.match(listed.reply, /Movie/);
  assert.doesNotMatch(listed.reply, /more/);
  assert.doesNotMatch(listed.spoken, /more/);
  assert.match(listed.spoken, /Movie/);
});

test('lists a multi-day appointment on a day in the middle of it', async () => {
  const calendar = memoryCalendar([
    { id: 'trip', title: 'Trip', start: new Date(2026, 9, 5, 9).toISOString(), end: new Date(2026, 9, 15, 17).toISOString() },
    { id: 'lunch', title: 'Lunch', start: new Date(2026, 9, 9, 12).toISOString(), end: new Date(2026, 9, 9, 13).toISOString() },
  ]);
  const assistant = createAssistant({ calendar, now });
  const listed = await assistant.handle("what's on my calendar today?");
  assert.match(listed.reply, /Trip/);
  assert.match(listed.reply, /Lunch/);
  assert.match(listed.spoken, /Trip/);
});

test('cancels both appointments with the same name', async () => {
  const calendar = memoryCalendar([
    { id: 'a', title: 'Dentist', start: new Date(2026, 9, 10, 15).toISOString(), end: new Date(2026, 9, 10, 16).toISOString() },
    { id: 'b', title: 'Dentist', start: new Date(2026, 9, 16, 9).toISOString(), end: new Date(2026, 9, 16, 10).toISOString() },
    { id: 'c', title: 'Lunch', start: new Date(2026, 9, 11, 12).toISOString(), end: new Date(2026, 9, 11, 13).toISOString() },
  ]);
  const assistant = createAssistant({ calendar, now });
  const done = await assistant.handle('cancel both dentist appointments');
  assert.match(done.reply, /Cancelled both Dentist/);
  assert.match(done.spoken, /both Dentist/i);
  assert.equal(calendar.all().map((event) => event.id).join(','), 'c');
});

test('cancels both after being asked to choose', async () => {
  const calendar = memoryCalendar([
    { id: 'a', title: 'Dentist', start: new Date(2026, 9, 10, 15).toISOString(), end: new Date(2026, 9, 10, 16).toISOString() },
    { id: 'b', title: 'Dentist', start: new Date(2026, 9, 16, 9).toISOString(), end: new Date(2026, 9, 16, 10).toISOString() },
  ]);
  const assistant = createAssistant({ calendar, now });
  const ask = await assistant.handle('cancel dentist');
  assert.match(ask.reply, /Say a number/);
  const done = await assistant.handle('both');
  assert.match(done.reply, /Cancelled both Dentist/);
  assert.equal(calendar.all().length, 0);
});

test('asks which appointment, then edits the one you pick', async () => {
  const calendar = memoryCalendar([
    { id: 'a', title: 'Dentist', start: new Date(2026, 9, 10, 15).toISOString(), end: new Date(2026, 9, 10, 16).toISOString() },
    { id: 'b', title: 'Dentist', start: new Date(2026, 9, 16, 9).toISOString(), end: new Date(2026, 9, 16, 10).toISOString() },
  ]);
  const assistant = createAssistant({ calendar, now });
  const ask = await assistant.handle('rename dentist to Dental checkup');
  assert.match(ask.reply, /Say a number/);
  const done = await assistant.handle('2');
  assert.match(done.reply, /Dental checkup/);
  assert.equal(calendar.all().find((event) => event.id === 'b').title, 'Dental checkup');
  assert.equal(calendar.all().find((event) => event.id === 'a').title, 'Dentist');
});

test('accepts the time as a follow-up', async () => {
  const calendar = memoryCalendar();
  const assistant = createAssistant({ calendar, now });
  const ask = await assistant.handle('add oil change');
  assert.match(ask.reply, /When should I schedule Oil change/);
  const saved = await assistant.handle('October 20 at 9am');
  assert.match(saved.reply, /Added Oil change/);
  const start = new Date(calendar.all()[0].start);
  assert.equal(start.getDate(), 20);
  assert.equal(start.getHours(), 9);
});
