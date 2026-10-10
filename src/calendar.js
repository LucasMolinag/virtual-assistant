import { formatLocalDateTime, horizon } from './dates.js';

const SETTINGS_KEY = 'vela-settings';
const SCOPE = 'https://www.googleapis.com/auth/calendar.events';

function readSettings() {
  try {
    return JSON.parse(localStorage.getItem(SETTINGS_KEY)) || {};
  } catch {
    return {};
  }
}

function writeSettings(next) {
  localStorage.setItem(SETTINGS_KEY, JSON.stringify({ ...readSettings(), ...next }));
}

function idbRequest(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

let databasePromise;
function database() {
  if (!databasePromise) {
    databasePromise = new Promise((resolve, reject) => {
      const request = indexedDB.open('vela', 1);
      request.onupgradeneeded = () => {
        request.result.createObjectStore('events', { keyPath: 'id' });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }
  return databasePromise;
}

async function localAll() {
  const db = await database();
  return idbRequest(db.transaction('events').objectStore('events').getAll());
}

function finish(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error || new Error('The calendar database was interrupted.'));
  });
}

async function localPut(event) {
  const db = await database();
  const tx = db.transaction('events', 'readwrite');
  tx.objectStore('events').put(event);
  await finish(tx);
  return event;
}

async function localDelete(id) {
  const db = await database();
  const tx = db.transaction('events', 'readwrite');
  tx.objectStore('events').delete(id);
  await finish(tx);
}

function fail(message, code) {
  const error = new Error(message);
  error.code = code;
  return error;
}

function hasNative() {
  return typeof window !== 'undefined' && typeof window.VellaNative?.list === 'function';
}

function readNative(raw) {
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw fail('The phone calendar returned something I could not read.', 'calendar');
  }
  if (parsed.error) throw fail(parsed.error, 'calendar');
  return parsed;
}

export function createCalendar() {
  let token = '';
  let expiresAt = 0;
  let tokenClient = null;
  let pendingAuth = null;
  let cache = { key: '', at: 0, events: [] };

  function invalidate() {
    cache = { key: '', at: 0, events: [] };
  }

  function connected() {
    return Boolean(token && Date.now() < expiresAt - 60000);
  }

  function loadGsi() {
    if (window.google?.accounts?.oauth2) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = 'https://accounts.google.com/gsi/client';
      script.async = true;
      script.onload = () => resolve();
      script.onerror = () => reject(fail('Could not load Google sign-in.', 'config'));
      document.head.appendChild(script);
    });
  }

  function requestToken(prompt) {
    return new Promise((resolve, reject) => {
      pendingAuth = { resolve, reject };
      tokenClient.requestAccessToken({ prompt });
    });
  }

  async function ensureToken() {
    if (connected()) return;
    if (!tokenClient) throw fail('Connect Google Calendar in settings.', 'auth');
    await requestToken('');
  }

  async function googleFetch(path, options = {}) {
    await ensureToken();
    const response = await fetch(`https://www.googleapis.com/calendar/v3${path}`, {
      ...options,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      },
    });
    if (response.status === 401) {
      token = '';
      expiresAt = 0;
      throw fail('Google Calendar signed you out. Connect it again.', 'auth');
    }
    if (!response.ok) {
      let detail = '';
      try {
        detail = (await response.json()).error?.message || '';
      } catch {
        detail = '';
      }
      throw fail(detail || `Google Calendar returned ${response.status}.`, 'google');
    }
    if (response.status === 204) return null;
    return response.json();
  }

  function toGoogle(event) {
    const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const body = {};
    if (event.title != null) body.summary = event.title;
    if (event.location != null) body.location = event.location;
    if (event.notes != null) body.description = event.notes;
    if (event.start) body.start = { dateTime: formatLocalDateTime(event.start), timeZone };
    if (event.end) body.end = { dateTime: formatLocalDateTime(event.end), timeZone };
    return body;
  }

  function fromGoogle(item) {
    if (item.start?.date && !item.start.dateTime) {
      const [year, month, day] = item.start.date.split('-').map(Number);
      const [endYear, endMonth, endDay] = item.end.date.split('-').map(Number);
      return {
        id: item.id,
        title: item.summary || 'Untitled',
        start: new Date(year, month - 1, day).toISOString(),
        end: new Date(endYear, endMonth - 1, endDay).toISOString(),
        allDay: true,
        location: item.location || '',
        notes: item.description || '',
        htmlLink: item.htmlLink || '',
      };
    }
    return {
      id: item.id,
      title: item.summary || 'Untitled',
      start: new Date(item.start.dateTime).toISOString(),
      end: new Date(item.end.dateTime).toISOString(),
      allDay: false,
      location: item.location || '',
      notes: item.description || '',
      htmlLink: item.htmlLink || '',
    };
  }

  async function listGoogle(start, end) {
    const items = [];
    let pageToken = '';
    do {
      const params = new URLSearchParams({
        timeMin: new Date(start).toISOString(),
        timeMax: new Date(end).toISOString(),
        singleEvents: 'true',
        orderBy: 'startTime',
        maxResults: '250',
      });
      if (pageToken) params.set('pageToken', pageToken);
      const data = await googleFetch(`/calendars/primary/events?${params}`);
      for (const item of data.items || []) {
        if (item.status !== 'cancelled') items.push(fromGoogle(item));
      }
      pageToken = data.nextPageToken || '';
    } while (pageToken);
    return items;
  }

  async function listLocal(start, end) {
    const all = await localAll();
    const from = new Date(start).getTime();
    const to = new Date(end).getTime();
    return all
      .filter((event) => {
        const start = new Date(event.start).getTime();
        const end = new Date(event.end).getTime();
        return start < to && end > from;
      })
      .sort((a, b) => new Date(a.start) - new Date(b.start));
  }

  let undoRecord = null;

  function copyEvent(event) {
    return {
      id: event.id,
      title: event.title,
      start: event.start,
      end: event.end,
      allDay: Boolean(event.allDay),
      location: event.location || '',
      notes: event.notes || '',
      htmlLink: event.htmlLink || '',
    };
  }

  async function findStored(id) {
    const key = String(id);
    if (hasNative()) {
      const start = new Date();
      start.setFullYear(start.getFullYear() - 2);
      const end = new Date();
      end.setFullYear(end.getFullYear() + 2);
      const events = readNative(window.VellaNative.list(start.toISOString(), end.toISOString())).events || [];
      return events.find((event) => String(event.id) === key) || null;
    }
    if (connected()) {
      try {
        return fromGoogle(await googleFetch(`/calendars/primary/events/${encodeURIComponent(id)}`));
      } catch {
        return null;
      }
    }
    return (await localAll()).find((event) => event.id === id) || null;
  }

  async function createStored(input) {
    invalidate();
    const event = {
      title: input.title || 'Appointment',
      start: new Date(input.start).toISOString(),
      end: new Date(input.end).toISOString(),
      location: input.location || '',
      notes: input.notes || '',
      allDay: false,
    };
    if (hasNative()) return readNative(window.VellaNative.create(JSON.stringify(event))).event;
    if (connected()) {
      const created = await googleFetch('/calendars/primary/events', {
        method: 'POST',
        body: JSON.stringify(toGoogle(event)),
      });
      return fromGoogle(created);
    }
    return localPut({ ...event, id: crypto.randomUUID(), htmlLink: '' });
  }

  async function updateStored(id, patch) {
    invalidate();
    if (hasNative()) return readNative(window.VellaNative.update(String(id), JSON.stringify(patch))).event;
    if (connected()) {
      const updated = await googleFetch(`/calendars/primary/events/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        body: JSON.stringify(toGoogle(patch)),
      });
      return fromGoogle(updated);
    }
    const existing = (await localAll()).find((event) => event.id === id);
    if (!existing) throw fail('That appointment is no longer on this phone.', 'missing');
    const next = { ...existing };
    if (patch.title != null) next.title = patch.title;
    if (patch.location != null) next.location = patch.location;
    if (patch.notes != null) next.notes = patch.notes;
    if (patch.start) next.start = new Date(patch.start).toISOString();
    if (patch.end) next.end = new Date(patch.end).toISOString();
    return localPut(next);
  }

  async function removeStored(id) {
    invalidate();
    if (hasNative()) {
      readNative(window.VellaNative.remove(String(id)));
      return;
    }
    if (connected()) {
      await googleFetch(`/calendars/primary/events/${encodeURIComponent(id)}`, { method: 'DELETE' });
      return;
    }
    await localDelete(id);
  }

  return {
    settings: readSettings,
    saveSettings(patch) {
      writeSettings(patch);
    },
    connected,
    sourceLabel() {
      if (hasNative()) return 'Phone calendar';
      return connected() ? 'Google Calendar' : 'This phone';
    },
    async connect() {
      const clientId = readSettings().clientId?.trim();
      if (!clientId) throw fail('Paste a Google OAuth client ID first.', 'config');
      await loadGsi();
      tokenClient = window.google.accounts.oauth2.initTokenClient({
        client_id: clientId,
        scope: SCOPE,
        callback: (response) => {
          const waiter = pendingAuth;
          pendingAuth = null;
          if (!waiter) return;
          if (response.error) {
            waiter.reject(fail(response.error_description || response.error, 'auth'));
            return;
          }
          token = response.access_token;
          expiresAt = Date.now() + (Number(response.expires_in) || 3600) * 1000;
          invalidate();
          waiter.resolve();
        },
        error_callback: (error) => {
          const waiter = pendingAuth;
          pendingAuth = null;
          waiter?.reject(fail(error?.message || 'Google sign-in was closed.', 'auth'));
        },
      });
      await requestToken('consent');
    },
    disconnect() {
      if (token && window.google?.accounts?.oauth2) {
        const current = token;
        window.google.accounts.oauth2.revoke(current, () => {});
      }
      token = '';
      expiresAt = 0;
      invalidate();
    },
    async listBetween(start, end) {
      const from = new Date(start);
      const to = new Date(end);
      if (hasNative()) {
        const key = `native|${from.toISOString()}|${to.toISOString()}`;
        if (cache.key === key && Date.now() - cache.at < 1500) return cache.events;
        const events = readNative(window.VellaNative.list(from.toISOString(), to.toISOString())).events || [];
        cache = { key, at: Date.now(), events };
        return events;
      }
      const key = `${connected()}|${from.toISOString()}|${to.toISOString()}`;
      if (cache.key === key && Date.now() - cache.at < 1500) return cache.events;
      const events = connected() ? await listGoogle(from, to) : await listLocal(from, to);
      cache = { key, at: Date.now(), events };
      return events;
    },
    async listUpcoming(now = new Date()) {
      const { start, end } = horizon(now);
      return this.listBetween(start, end);
    },
    async localCount() {
      return (await localAll()).length;
    },
    canUndo() {
      return Boolean(undoRecord);
    },
    async create(input) {
      const saved = await createStored(input);
      undoRecord = { type: 'create', event: copyEvent(saved) };
      return saved;
    },
    async update(id, patch) {
      const before = await findStored(id);
      const saved = await updateStored(id, patch);
      if (before) undoRecord = { type: 'update', event: copyEvent(before) };
      return saved;
    },
    async remove(id) {
      const before = await findStored(id);
      await removeStored(id);
      if (before) undoRecord = { type: 'delete', event: copyEvent(before) };
    },
    async removeMany(ids) {
      const events = [];
      for (const id of ids) {
        const before = await findStored(id);
        await removeStored(id);
        if (before) events.push(copyEvent(before));
      }
      if (events.length === 1) undoRecord = { type: 'delete', event: events[0] };
      else if (events.length) undoRecord = { type: 'delete-many', events };
    },
    async undo() {
      const record = undoRecord;
      if (!record) throw fail('Nothing to undo.', 'undo');
      undoRecord = null;
      if (record.type === 'create') {
        await removeStored(record.event.id);
        return { message: `Undone. I removed ${record.event.title}.`, focusDate: record.event.start };
      }
      if (record.type === 'delete') {
        const saved = await createStored(record.event);
        return { message: `Undone. I put ${saved.title} back.`, event: saved, focusDate: saved.start };
      }
      if (record.type === 'delete-many') {
        let first = null;
        for (const event of record.events) {
          const saved = await createStored(event);
          if (!first) first = saved;
        }
        return {
          message: `Undone. I put ${record.events.length} appointments back.`,
          event: first,
          focusDate: first?.start,
        };
      }
      const saved = await updateStored(record.event.id, {
        title: record.event.title,
        location: record.event.location,
        notes: record.event.notes,
        start: record.event.start,
        end: record.event.end,
      });
      return { message: `Undone. I restored ${saved.title}.`, event: saved, focusDate: saved.start };
    },
    async importLocalToGoogle() {
      if (!connected()) throw fail('Connect Google Calendar first.', 'auth');
      const locals = await localAll();
      for (const event of locals) {
        await googleFetch('/calendars/primary/events', {
          method: 'POST',
          body: JSON.stringify(toGoogle(event)),
        });
        await localDelete(event.id);
      }
      invalidate();
      return locals.length;
    },
  };
}
