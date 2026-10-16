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
      if (patch.phone != null) event.phone = patch.phone;
      if (patch.important != null) event.important = patch.important;
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
  const done = await assistant.handle('the second one');
  assert.match(done.reply, /Dental checkup/);
  assert.equal(calendar.all().find((event) => event.id === 'b').title, 'Dental checkup');
  assert.equal(calendar.all().find((event) => event.id === 'a').title, 'Dentist');
});

test('hears a spoken two as the second choice', async () => {
  const calendar = memoryCalendar([
    { id: 'a', title: 'Dentist', start: new Date(2026, 9, 10, 15).toISOString(), end: new Date(2026, 9, 10, 16).toISOString() },
    { id: 'b', title: 'Dentist', start: new Date(2026, 9, 16, 9).toISOString(), end: new Date(2026, 9, 16, 10).toISOString() },
  ]);
  const assistant = createAssistant({ calendar, now });
  await assistant.handle('cancel dentist');
  const done = await assistant.handle('to');
  assert.match(done.reply, /Cancelled Dentist/);
  assert.equal(calendar.all().length, 1);
  assert.equal(calendar.all()[0].id, 'a');
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

test('changes and clears details on an existing appointment', async () => {
  const calendar = memoryCalendar([
    { id: '1', title: 'Dentist', start: new Date(2026, 9, 10, 15).toISOString(), end: new Date(2026, 9, 10, 16).toISOString(), location: 'Clinic' },
  ]);
  const assistant = createAssistant({ calendar, now, lookupPlace: async () => null });
  const phone = await assistant.handle('set the phone number of dentist to 555-0100');
  assert.match(phone.reply, /555-0100/);
  assert.equal(calendar.all()[0].phone, '555-0100');
  const cleared = await assistant.handle('remove the location from dentist');
  assert.match(cleared.reply, /removed the location/i);
  assert.equal(calendar.all()[0].location, '');
  const reminded = await assistant.handle('remind me about dentist');
  assert.match(reminded.reply, /remind you/i);
  assert.equal(calendar.all()[0].important, true);
});

test('looks up a place name and answers a question', async () => {
  const calendar = memoryCalendar();
  const assistant = createAssistant({
    calendar,
    now,
    lookupPlace: async () => ({ address: '12 Market Street', phone: '555-0199' }),
    ask: async () => ({ reply: 'Paris is the capital of France.', spoken: 'Paris is the capital of France.' }),
  });
  const added = await assistant.handle('add dentist tomorrow at 3pm at the clinic');
  assert.match(added.reply, /12 Market Street/);
  assert.match(added.reply, /555-0199/);
  assert.equal(calendar.all()[0].location, '12 Market Street');
  const answer = await assistant.handle('what is the capital of France');
  assert.match(answer.reply, /Paris/);
});

test('cancels the lunch on the day you name', async () => {
  const calendar = memoryCalendar([
    { id: '1', title: 'Lunch', start: new Date(2026, 9, 10, 12).toISOString(), end: new Date(2026, 9, 10, 13).toISOString() },
    { id: '2', title: 'Lunch', start: new Date(2026, 9, 12, 12).toISOString(), end: new Date(2026, 9, 12, 13).toISOString() },
  ]);
  const assistant = createAssistant({ calendar, now });
  const cancelled = await assistant.handle('cancel Lunch tomorrow');
  assert.match(cancelled.reply, /Cancelled Lunch/);
  assert.match(cancelled.reply, /tomorrow/i);
  assert.deepEqual(calendar.all().map((event) => event.id), ['2']);
  const later = await assistant.handle("cancel tomorrow's lunch");
  assert.match(later.reply, /Monday/);
  assert.match(later.reply, /left it/i);
  assert.deepEqual(calendar.all().map((event) => event.id), ['2']);
});

test('keeps a missed command short and explains what she can do', async () => {
  const assistant = createAssistant({ calendar: memoryCalendar(), now });
  const missed = await assistant.handle('blah blah blah');
  assert.equal(missed.spoken, "I didn't catch a command.");
  assert.equal(missed.reply, missed.spoken);
  const help = await assistant.handle('what can you do?');
  assert.match(help.spoken, /add, move, rename, and cancel/);
  assert.match(help.spoken, /reminder/);
  assert.equal(help.reply, help.spoken);
});

test('asks what time when a time change leaves it out', async () => {
  const calendar = memoryCalendar([
    { id: '1', title: 'Lunch', start: new Date(2026, 9, 10, 12).toISOString(), end: new Date(2026, 9, 10, 13).toISOString() },
    { id: '2', title: 'Lunch', start: new Date(2026, 9, 12, 12).toISOString(), end: new Date(2026, 9, 12, 13).toISOString() },
  ]);
  const assistant = createAssistant({ calendar, now });
  const asked = await assistant.handle('Change the time for Lunch tomorrow');
  assert.equal(asked.followUp, true);
  assert.match(asked.spoken, /what time/i);
  const saved = await assistant.handle('8 PM');
  assert.match(saved.reply, /Moved Lunch/i);
  const lunch = calendar.all().find((event) => event.id === '1');
  assert.equal(new Date(lunch.start).getDate(), 10);
  assert.equal(new Date(lunch.start).getHours(), 20);
  assert.equal(new Date(lunch.end).getHours(), 21);
  const other = calendar.all().find((event) => event.id === '2');
  assert.equal(new Date(other.start).getDate(), 12);
  assert.equal(new Date(other.start).getHours(), 12);
});

test('asks what name when a rename leaves it out', async () => {
  const calendar = memoryCalendar([
    { id: '1', title: 'Lunch', start: new Date(2026, 9, 10, 12).toISOString(), end: new Date(2026, 9, 10, 13).toISOString() },
    { id: '2', title: 'Dentist', start: new Date(2026, 9, 12, 9).toISOString(), end: new Date(2026, 9, 12, 10).toISOString() },
  ]);
  const assistant = createAssistant({ calendar, now });
  const asked = await assistant.handle('rename Lunch');
  assert.equal(asked.followUp, true);
  assert.match(asked.spoken, /what name/i);
  const saved = await assistant.handle('Brunch');
  assert.match(saved.reply, /Brunch/);
  assert.equal(calendar.all().find((event) => event.id === '1').title, 'Brunch');
  assert.equal(calendar.all().find((event) => event.id === '2').title, 'Dentist');
});

test('asks for the missing detail on other actions', async () => {
  const calendar = memoryCalendar([
    { id: '1', title: 'Lunch', start: new Date(2026, 9, 10, 12).toISOString(), end: new Date(2026, 9, 10, 13).toISOString() },
  ]);
  const assistant = createAssistant({ calendar, now, lookupPlace: async () => null });
  const note = await assistant.handle('add a note to Lunch tomorrow');
  assert.match(note.spoken, /what should the note say/i);
  const noted = await assistant.handle('bring a book');
  assert.match(noted.reply, /note/i);
  assert.equal(calendar.all()[0].notes, 'bring a book');

  const phone = await assistant.handle('set the phone number of Lunch');
  assert.match(phone.spoken, /phone number/i);
  const numbered = await assistant.handle('555-0100');
  assert.match(numbered.reply, /555-0100/);

  const day = await assistant.handle('change the date for Lunch tomorrow');
  assert.match(day.spoken, /what day/i);
  const moved = await assistant.handle('Friday');
  assert.match(moved.reply, /Moved Lunch/i);
  assert.equal(new Date(calendar.all()[0].start).getHours(), 12);
  assert.equal(new Date(calendar.all()[0].start).getDay(), 5);

  const which = await assistant.handle('cancel');
  assert.match(which.spoken, /which appointment/i);
});

test('asks which place to add when the location was left out', async () => {
  const calendar = memoryCalendar([
    { id: '1', title: 'Lunch', start: new Date(2026, 9, 10, 12).toISOString(), end: new Date(2026, 9, 10, 13).toISOString() },
    { id: '2', title: 'Lunch', start: new Date(2026, 9, 9, 12).toISOString(), end: new Date(2026, 9, 9, 13).toISOString() },
  ]);
  const assistant = createAssistant({
    calendar,
    now,
    lookupPlace: async () => ({ address: '1 King Street, Toronto', phone: '416-555-0100' }),
  });
  const asked = await assistant.handle('add location to lunch tomorrow');
  assert.equal(asked.followUp, true);
  assert.match(asked.spoken, /What's the location for Lunch/i);
  const saved = await assistant.handle('at the diner');
  assert.match(saved.reply, /1 King Street, Toronto/);
  assert.equal(calendar.all().find((event) => event.id === '1').location, '1 King Street, Toronto');
  assert.equal(calendar.all().find((event) => event.id === '2').location, '');
});

test('schedules a foreign clock time in local time', async () => {
  const calendar = memoryCalendar();
  const assistant = createAssistant({ calendar, now });
  const added = await assistant.handle("Schedule doctor's appointment at 7pm Brazil time");
  assert.match(added.reply, /Doctor's appointment/);
  assert.match(added.reply, /Brazil/);
  assert.equal(new Date(calendar.all()[0].start).toISOString(), '2026-10-09T22:00:00.000Z');
});

test('sets an alarm for a delay or a clock time', async () => {
  const alarms = [];
  const assistant = createAssistant({
    calendar: memoryCalendar(),
    now,
    setAlarm: async (at) => {
      alarms.push(new Date(at));
      return true;
    },
  });
  const soon = await assistant.handle('set an alarm for 20 minutes');
  assert.equal(alarms[0].getHours(), 15);
  assert.equal(alarms[0].getMinutes(), 20);
  assert.match(soon.reply, /20 minutes/);

  const asked = await assistant.handle('set an alarm');
  assert.match(asked.reply, /When should I set the alarm/i);
  const tomorrow = await assistant.handle('tomorrow at 10am');
  assert.equal(alarms[1].getDate(), 10);
  assert.equal(alarms[1].getHours(), 10);
  assert.match(tomorrow.reply, /tomorrow/i);
  assert.match(tomorrow.reply, /10:00 AM/);
});
