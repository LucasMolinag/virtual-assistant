import assert from 'node:assert/strict';
import test from 'node:test';
import { findCandidates, numberFrom, parseCommand } from '../src/parse.js';

const now = new Date(2026, 9, 9, 15, 0, 0);

function at(date) {
  return {
    y: date.getFullYear(),
    m: date.getMonth(),
    d: date.getDate(),
    h: date.getHours(),
    min: date.getMinutes(),
  };
}

test('understands brazilian portuguese commands', () => {
  const added = parseCommand('marca almoço amanhã às 8 da noite', now);
  assert.equal(added.intent, 'add');
  assert.equal(added.title, 'Lunch');
  assert.deepEqual(at(added.start), { y: 2026, m: 9, d: 10, h: 20, min: 0 });

  const dinner = parseCommand('marca jantar com a Maria no restaurante amanhã às 8 da noite', now);
  assert.equal(dinner.intent, 'add');
  assert.equal(dinner.title, 'Dinner with Maria at the restaurant');
  assert.equal(dinner.start.getHours(), 20);

  const afternoon = parseCommand('adiciona dentista amanhã às 3 da tarde', now);
  assert.equal(afternoon.title, 'Dentist');
  assert.equal(afternoon.start.getHours(), 15);

  const listed = parseCommand('o que tem amanhã', now);
  assert.equal(listed.intent, 'list');
  assert.equal(listed.rangeLabel, 'tomorrow');

  const moved = parseCommand('muda o dentista pra sexta às 4', now);
  assert.equal(moved.intent, 'move');
  assert.equal(moved.query, 'dentist');
  assert.equal(moved.start.getHours(), 16);
  assert.equal(moved.start.getDate(), 16);

  const cancelled = parseCommand('cancela o dentista', now);
  assert.equal(cancelled.intent, 'cancel');
  assert.equal(cancelled.query, 'dentist');
  assert.equal(numberFrom('dois'), 2);
  assert.equal(numberFrom('número 3'), 3);
});

test('understands undo', () => {
  assert.equal(parseCommand('undo', now).intent, 'undo');
  assert.equal(parseCommand('undo that', now).intent, 'undo');
  assert.equal(parseCommand('desfaz', now).intent, 'undo');
});

test('keeps pm out of the lunch title', () => {
  for (const phrase of ['add lunch at 8 pm', 'add lunch at 8 p.m.', 'add lunch at 8 p . m']) {
    const command = parseCommand(phrase, now);
    assert.equal(command.intent, 'add', phrase);
    assert.equal(command.title, 'Lunch', phrase);
    assert.equal(command.start.getHours(), 20, phrase);
    assert.equal(command.start.getMinutes(), 0, phrase);
  }
});

test('hears a leading at as add', () => {
  const command = parseCommand('at dentist tomorrow at 3pm', now);
  assert.equal(command.intent, 'add');
  assert.equal(command.title, 'Dentist');
  assert.deepEqual(at(command.start), { y: 2026, m: 9, d: 10, h: 15, min: 0 });
});

test('does not turn a clock phrase into a new appointment', () => {
  const command = parseCommand('at 3pm', now);
  assert.notEqual(command.intent, 'add');
});

test('adds a timed appointment', () => {
  const command = parseCommand('add dentist tomorrow at 3pm', now);
  assert.equal(command.intent, 'add');
  assert.equal(command.title, 'Dentist');
  assert.deepEqual(at(command.start), { y: 2026, m: 9, d: 10, h: 15, min: 0 });
  assert.deepEqual(at(command.end), { y: 2026, m: 9, d: 10, h: 16, min: 0 });
  assert.equal(command.assumedTime, false);
});

test('keeps the person in the title and applies duration', () => {
  const command = parseCommand('schedule lunch with Priya Friday at noon for 90 minutes', now);
  assert.equal(command.title, 'Lunch with Priya');
  assert.equal(command.durationMinutes, 90);
  assert.deepEqual(at(command.start), { y: 2026, m: 9, d: 16, h: 12, min: 0 });
});

test('reads a start and end on the same day', () => {
  const command = parseCommand('add team sync next Monday from 9am to 10am', now);
  assert.equal(command.title, 'Team sync');
  assert.deepEqual(at(command.start), { y: 2026, m: 9, d: 12, h: 9, min: 0 });
  assert.deepEqual(at(command.end), { y: 2026, m: 9, d: 12, h: 10, min: 0 });
});

test('turns a bare afternoon hour into PM and pulls the end onto the same day', () => {
  const command = parseCommand('add focus block tomorrow from 9am to 4', now);
  assert.deepEqual(at(command.start), { y: 2026, m: 9, d: 10, h: 9, min: 0 });
  assert.deepEqual(at(command.end), { y: 2026, m: 9, d: 10, h: 16, min: 0 });
});

test('treats an evening end without meridiem as after midnight', () => {
  const command = parseCommand('add party tomorrow from 9pm to 1', now);
  assert.deepEqual(at(command.start), { y: 2026, m: 9, d: 10, h: 21, min: 0 });
  assert.deepEqual(at(command.end), { y: 2026, m: 9, d: 11, h: 1, min: 0 });
});

test('asks for a time when the command has none', () => {
  const command = parseCommand('add dentist', now);
  assert.equal(command.intent, 'add');
  assert.equal(command.title, 'Dentist');
  assert.equal(command.start, null);
});

test('moves, renames, and cancels by name', () => {
  const move = parseCommand('move dentist to Friday at 4', now);
  assert.equal(move.intent, 'move');
  assert.equal(move.query, 'dentist');
  assert.equal(move.start.getHours(), 16);
  assert.equal(move.start.getDate(), 16);

  const rename = parseCommand('change dentist to Dental checkup', now);
  assert.equal(rename.intent, 'rename');
  assert.equal(rename.title, 'Dental checkup');

  const cancel = parseCommand('cancel the dentist appointment', now);
  assert.equal(cancel.intent, 'cancel');
  assert.equal(cancel.query, 'dentist');
});

test('captures notes, location, and duration edits', () => {
  const notes = parseCommand('add a note to dentist saying bring insurance', now);
  assert.equal(notes.intent, 'notes');
  assert.equal(notes.query, 'dentist');
  assert.equal(notes.notes, 'bring insurance');

  const location = parseCommand('set the location of dentist to 12 Market Street', now);
  assert.equal(location.intent, 'location');
  assert.equal(location.location, '12 Market Street');

  const duration = parseCommand('make dentist 30 minutes long', now);
  assert.equal(duration.intent, 'duration');
  assert.equal(duration.durationMinutes, 30);
  assert.equal(duration.durationMode, 'set');
});

test('lists today and puts a place in the location when it follows the time', () => {
  const list = parseCommand("what's on my calendar today?", now);
  assert.equal(list.intent, 'list');
  assert.equal(list.rangeLabel, 'today');

  const placed = parseCommand('add dentist tomorrow at 3pm at the clinic', now);
  assert.equal(placed.title, 'Dentist');
  assert.equal(placed.location, 'the clinic');
});

test('finds every appointment that shares a name', () => {
  const events = [
    { id: '1', title: 'Dentist', start: '2026-10-10T22:00:00.000Z', end: '2026-10-10T23:00:00.000Z' },
    { id: '2', title: 'Dentist', start: '2026-10-16T16:00:00.000Z', end: '2026-10-16T17:00:00.000Z' },
    { id: '3', title: 'Lunch with Priya', start: '2026-10-16T19:00:00.000Z', end: '2026-10-16T20:00:00.000Z' },
  ];
  const matches = findCandidates(events, 'dentist', now);
  assert.deepEqual(matches.map((event) => event.id), ['1', '2']);
});
