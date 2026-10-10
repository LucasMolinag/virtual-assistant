import * as chrono from 'chrono-node';
import { addDays } from './dates.js';

const DAY_WORD = /\b(today|tomorrow|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday|jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\b/i;
const STOP = new Set(['my', 'the', 'a', 'an', 'appointment', 'meeting', 'event', 'please', 'today', 'tomorrow', 'meu', 'minha', 'compromisso', 'reuniao', 'reunião', 'agenda', 'hoje', 'amanha', 'amanhã', 'para', 'pra']);

function blank(raw) {
  return {
    intent: 'unknown',
    title: null,
    query: null,
    start: null,
    end: null,
    location: null,
    notes: null,
    durationMinutes: null,
    durationMode: null,
    assumedTime: false,
    rangeStart: null,
    rangeEnd: null,
    rangeLabel: null,
    every: false,
    raw,
  };
}

function command(raw, fields) {
  return { ...blank(raw), ...fields };
}

function sentence(text) {
  const t = text.replace(/\s+/g, ' ').trim().replace(/[?.!]+$/g, '');
  if (!t) return '';
  return t.charAt(0).toUpperCase() + t.slice(1);
}

function cleanQuery(text) {
  return text
    .replace(/^(?:my\s+)?(?:the\s+)?(?:appointment|meeting|event)\s+(?:called|named\s+)?/i, '')
    .replace(/^(?:the|my|an?|o|os|as|um|uma)\s+/iu, '')
    .replace(/\s+(?:appointments|meetings|events|appointment|meeting|event)$/i, '')
    .replace(/[?.!]+$/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function cleanTitle(text) {
  let t = text.replace(/\s+/g, ' ').trim();
  t = t.replace(/^(?:please\s+)?/i, '');
  t = t.replace(/^(?:an?\s+)(?=(?:meeting|appointment|call|event|lunch|dinner)\b)/i, '');
  t = t.replace(/^(?:called|named)\s+/i, '');
  t = t.replace(/^(?:my\s+)/i, '');
  t = t.replace(/^(?:o|os|as|um|uma|a)\s+/iu, '');
  t = t.replace(/\s+on my calendar\b/gi, '');
  t = t.replace(/\s+to my calendar\b/gi, '');
  t = t.replace(/\b(?:on|at|for|from|to)\s*$/i, '');
  t = t.replace(/^(?:on|at|for|from|to)\s+/i, '');
  t = t.replace(/\s+[ap]\.?\s*m\.?$/i, '');
  t = t.replace(/\s*\.m$/i, '');
  t = t.replace(/[?.!,]+$/g, '');
  return sentence(t);
}

function toMinutes(amount, unit) {
  const value = Number(amount);
  if (/^h/i.test(unit)) return Math.round(value * 60);
  return value;
}

function hasMeridiem(phrase) {
  return /\b(a\.?m\.?|p\.?m\.?)\b/i.test(phrase);
}

function maybeAfternoon(date, phrase) {
  const d = new Date(date);
  if (hasMeridiem(phrase) || /\b(noon|midnight)\b/i.test(phrase)) return d;
  const hour = d.getHours();
  if (hour >= 1 && hour <= 6) d.setHours(hour + 12);
  return d;
}

function splitRangePhrase(phrase) {
  const parts = phrase.split(/\bto\b/i);
  if (parts.length < 2) return { startPhrase: phrase, endPhrase: phrase };
  return { startPhrase: parts[0], endPhrase: parts.slice(1).join(' to ') };
}

function normalizeRange(startDate, endDate, phrase) {
  const { startPhrase, endPhrase } = splitRangePhrase(phrase);
  const start = maybeAfternoon(startDate, startPhrase);
  if (!endDate) return { start, end: null };
  let end = new Date(endDate);
  const startIsEvening = start.getHours() >= 17;
  const endIsEarly = end.getHours() >= 0 && end.getHours() <= 6;
  if (!(startIsEvening && endIsEarly)) end = maybeAfternoon(end, endPhrase);
  if (!DAY_WORD.test(endPhrase)) {
    end.setFullYear(start.getFullYear(), start.getMonth(), start.getDate());
    if (end <= start) end = addDays(end, 1);
  }
  return { start, end };
}

function applyPartOfDay(date, phrase) {
  const d = new Date(date);
  if (/\bafternoon\b/i.test(phrase)) d.setHours(14, 0, 0, 0);
  else if (/\b(evening|tonight|night)\b/i.test(phrase)) d.setHours(19, 0, 0, 0);
  else d.setHours(9, 0, 0, 0);
  return d;
}

function bestResult(results) {
  return [...results].sort((a, b) => {
    const score = (result) => (result.start?.isCertain('hour') ? 2 : 0) + (result.end ? 1 : 0);
    return score(b) - score(a) || b.text.length - a.text.length;
  })[0] || null;
}

function extractDuration(text) {
  const rules = [
    [/\bfor\s+half\s+an?\s+hour\b/i, () => 30],
    [/\bfor\s+an?\s+hour\b/i, () => 60],
    [/\bfor\s+(\d+(?:\.\d+)?)\s*(hours|hour|hrs|hr)\b/i, (m) => toMinutes(m[1], m[2])],
    [/\bfor\s+(\d+)\s*(minutes|minute|mins|min)\b/i, (m) => toMinutes(m[1], m[2])],
  ];
  for (const [re, value] of rules) {
    const match = text.match(re);
    if (!match) continue;
    return {
      text: text.replace(re, ' ').replace(/\s+/g, ' ').trim(),
      minutes: value(match),
    };
  }
  return { text, minutes: null };
}

function extractWhen(text, now) {
  const results = chrono.parse(text, now, { forwardDate: true });
  const best = bestResult(results);
  if (!best) {
    return { before: text, after: '', start: null, end: null, assumedTime: false };
  }
  let start = best.start.date();
  let end = best.end ? best.end.date() : null;
  let assumedTime = false;
  if (!best.start.isCertain('hour')) {
    const namedPart = /\b(morning|afternoon|evening|tonight|night|noon)\b/i.test(best.text);
    start = applyPartOfDay(start, best.text);
    end = null;
    assumedTime = !namedPart;
  } else {
    const normalized = normalizeRange(start, end, best.text);
    start = normalized.start;
    end = normalized.end;
  }
  return {
    before: text.slice(0, best.index),
    after: text.slice(best.index + best.text.length),
    start,
    end,
    assumedTime,
  };
}

export function parseWhen(text, now = new Date()) {
  const spoken = repairMishearing(normalizeSpeech(toEnglishCommand(text)));
  const duration = extractDuration(spoken);
  const when = extractWhen(duration.text, now);
  return {
    start: when.start,
    end: when.end,
    assumedTime: when.assumedTime,
    durationMinutes: duration.minutes,
  };
}

function extractLocation(before, after) {
  const trailing = after.trim().match(/^(?:at|in)\s+(.+)$/i);
  if (trailing) {
    return { titleSource: before, location: trailing[1].trim() };
  }
  const generic = before.trim().match(/^(?:(?:a|an|the)\s+)?(meeting|appointment|lunch|dinner|breakfast|coffee|call|drinks|visit)\s+at\s+(.+)$/i);
  if (generic) {
    return { titleSource: generic[1], location: generic[2].trim() };
  }
  return { titleSource: `${before} ${after}`, location: null };
}

function parseAdd(text, now, raw) {
  let body = text.replace(/^(?:please\s+)?(?:add|schedule|create|book|set up|setup|put|new)\b\s*/i, '');
  body = body.replace(/^(?:an?\s+)?(?:appointment|meeting|event)\s+(?:called|named)\s+/i, '');
  const duration = extractDuration(body);
  const when = extractWhen(duration.text, now);
  const located = extractLocation(when.before, when.after);
  const title = cleanTitle(located.titleSource) || 'Appointment';
  let end = when.end;
  if (when.start && !end) {
    end = new Date(when.start.getTime() + (duration.minutes || 60) * 60000);
  }
  return command(raw, {
    intent: 'add',
    title,
    start: when.start,
    end,
    location: located.location,
    durationMinutes: duration.minutes,
    assumedTime: when.assumedTime,
  });
}

function rangeFrom(text, now) {
  const today = new Date(now);
  today.setHours(0, 0, 0, 0);
  if (/\btomorrow\b/i.test(text)) {
    const start = addDays(today, 1);
    return { rangeStart: start, rangeEnd: addDays(start, 1), rangeLabel: 'tomorrow' };
  }
  if (/\b(today|tonight)\b/i.test(text)) {
    return { rangeStart: today, rangeEnd: addDays(today, 1), rangeLabel: 'today' };
  }
  if (/\bnext week\b/i.test(text)) {
    const dow = today.getDay();
    const daysUntilNextMonday = dow === 1 ? 7 : (8 - dow) % 7 || 7;
    const start = addDays(today, daysUntilNextMonday);
    return { rangeStart: start, rangeEnd: addDays(start, 7), rangeLabel: 'next week' };
  }
  if (/\bthis week\b/i.test(text)) {
    const untilSunday = today.getDay() === 0 ? 1 : 8 - today.getDay();
    return { rangeStart: today, rangeEnd: addDays(today, untilSunday), rangeLabel: 'this week' };
  }
  const when = extractWhen(text, now);
  if (when.start) {
    const start = new Date(when.start);
    start.setHours(0, 0, 0, 0);
    const label = new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'long', day: 'numeric' }).format(start);
    return { rangeStart: start, rangeEnd: addDays(start, 1), rangeLabel: `on ${label}` };
  }
  return { rangeStart: today, rangeEnd: addDays(today, 7), rangeLabel: 'the next 7 days' };
}

function isList(text) {
  return /^(?:please\s+)?(?:what(?:'s| is) on(?: my)?(?: calendar| schedule)?|what do i have|whats on|do i have anything|anything on|how does my day look|my agenda|show my|list my|agenda\b)/i.test(text);
}

function ptWord(pattern, flags = 'giu') {
  return new RegExp(`(?<![\\p{L}\\p{N}])(?:${pattern})(?![\\p{L}\\p{N}])`, flags);
}

function toEnglishCommand(text) {
  let t = text;
  const swap = (pattern, replacement) => {
    t = t.replace(ptWord(pattern), replacement);
  };
  t = t.replace(ptWord('depois de amanh[aã]'), 'day after tomorrow');
  swap('hoje', 'today');
  swap('amanh[aã]', 'tomorrow');
  swap('ontem', 'yesterday');
  swap('segunda(?:-feira)?', 'monday');
  swap('ter[cç]a(?:-feira)?', 'tuesday');
  swap('quarta(?:-feira)?', 'wednesday');
  swap('quinta(?:-feira)?', 'thursday');
  swap('sexta(?:-feira)?', 'friday');
  swap('s[aá]bado', 'saturday');
  swap('domingo', 'sunday');
  swap('meio[-\\s]?dia', 'noon');
  swap('meia[-\\s]?noite', 'midnight');
  t = t.replace(ptWord('(\\d{1,2})\\s*h\\s*(\\d{2})'), '$1:$2');
  t = t.replace(ptWord('(\\d{1,2})\\s*h'), '$1:00');
  t = t.replace(ptWord('(\\d{1,2})(?::(\\d{2}))?\\s+da\\s+manh[aã]'), (_, hour, minute) => `${hour}:${minute || '00'} am`);
  t = t.replace(ptWord('(\\d{1,2})(?::(\\d{2}))?\\s+da\\s+tarde'), (_, hour, minute) => `${hour}:${minute || '00'} pm`);
  t = t.replace(ptWord('(\\d{1,2})(?::(\\d{2}))?\\s+da\\s+noite'), (_, hour, minute) => `${hour}:${minute || '00'} pm`);
  t = t.replace(ptWord('(\\d{1,2})(?::(\\d{2}))?\\s+da\\s+madrugada'), (_, hour, minute) => `${hour}:${minute || '00'} am`);
  t = t.replace(ptWord('[àa]s(?=\\s*\\d)'), 'at');
  t = t.replace(ptWord('por\\s+uma\\s+hora'), 'for an hour');
  t = t.replace(ptWord('por\\s+meia\\s+hora'), 'for half an hour');
  t = t.replace(ptWord('por\\s+(\\d+)\\s+horas?'), 'for $1 hours');
  t = t.replace(ptWord('por\\s+(\\d+)\\s+minutos?'), 'for $1 minutes');
  t = t.replace(/^(?:por favor[, ]+)?(?:desfaz(?:er)?(?:\s+isso)?|volta atr[aá]s)\b.*/iu, 'undo');
  t = t.replace(/^(?:por favor[, ]+)?(?:o que (?:eu )?tenho|o que tem(?: na agenda)?|minha agenda|mostra(?: a)?(?: minha)? agenda|lista(?: a)?(?: minha)? agenda)\b/iu, "what's on my calendar");
  t = t.replace(/^(?:por favor[, ]+)?(?:qual [eé] o pr[oó]ximo|qual o pr[oó]ximo|o que vem agora|pr[oó]ximo compromisso)\b/iu, "what's next");
  t = t.replace(/^(?:por favor[, ]+)?(?:renomeia|renomear)\s+(.+?)\s+(?:pra|para|pro)\s+/iu, 'rename $1 to ');
  t = t.replace(/^(?:por favor[, ]+)?(?:muda|mudar|move|mover|remarca|remarcar|passa|passar)\s+(.+?)\s+(?:pra|para|pro)\s+/iu, 'move $1 to ');
  t = t.replace(/^(?:por favor[, ]+)?(?:marca|marcar|adicione|adiciona|adicionar|coloca|colocar|bota|botar|agenda|agendar|cria|criar)\b/iu, 'add');
  t = t.replace(/^(?:por favor[, ]+)?(?:cancela|cancelar|apaga|apagar|remove|remover|tira|tirar|deleta|deletar)\b/iu, 'cancel');
  t = t.replace(/^(?:por favor[, ]+)?(?:abre|abrir|edita|editar)\b/iu, 'open');
  t = translateWords(t);
  return t.replace(/\s+/g, ' ').trim();
}

const WORD_MAP = [
  ['jantar de anivers[aá]rio', 'birthday dinner'],
  ['almo[cç]o de neg[oó]cios', 'business lunch'],
  ['reuni[aã]o de planejamento', 'planning meeting'],
  ['reuni[aã]o de equipe', 'team meeting'],
  ['consulta m[eé]dica', "doctor's appointment"],
  ['caf[eé] da manh[aã]', 'breakfast'],
  ['em casa', 'at home'],
  ['com o', 'with'],
  ['com a', 'with'],
  ['para o', 'for'],
  ['para a', 'for'],
  ['pra o', 'for'],
  ['pra a', 'for'],
  ['almo[cç]o', 'lunch'],
  ['jantar', 'dinner'],
  ['lanche', 'snack'],
  ['caf[eé]', 'coffee'],
  ['reuni[aã]o', 'meeting'],
  ['consulta', 'appointment'],
  ['dentista', 'dentist'],
  ['m[eé]dic[oa]', 'doctor'],
  ['academia', 'gym'],
  ['treino', 'workout'],
  ['nata[cç][aã]o', 'swim'],
  ['escola', 'school'],
  ['trabalho', 'work'],
  ['escrit[oó]rio', 'office'],
  ['cl[ií]nica', 'clinic'],
  ['hospital', 'hospital'],
  ['aeroporto', 'airport'],
  ['voo', 'flight'],
  ['anivers[aá]rio', 'birthday'],
  ['festa', 'party'],
  ['cinema', 'movies'],
  ['filme', 'movie'],
  ['compras', 'shopping'],
  ['mercado', 'grocery store'],
  ['supermercado', 'supermarket'],
  ['farm[aá]cia', 'pharmacy'],
  ['padaria', 'bakery'],
  ['restaurante', 'restaurant'],
  ['cabeleireiro', 'haircut'],
  ['barbeiro', 'barber'],
  ['aula', 'class'],
  ['entrevista', 'interview'],
  ['liga[cç][aã]o', 'call'],
  ['chamada', 'call'],
  ['caminhada', 'walk'],
  ['corrida', 'run'],
  ['futebol', 'soccer'],
  ['praia', 'beach'],
  ['parque', 'park'],
  ['casa', 'home'],
  ['m[aã]e', 'mom'],
  ['pai', 'dad'],
  ['equipe', 'team'],
  ['planejamento', 'planning'],
  ['com', 'with'],
  ['para', 'for'],
  ['pra', 'for'],
  ['no', 'at the'],
  ['na', 'at the'],
  ['nos', 'at the'],
  ['nas', 'at the'],
  ['em', 'at'],
].sort((a, b) => b[0].length - a[0].length);

const TITLE_ALIASES = {
  lunch: ['almoço', 'almoco'],
  dinner: ['jantar'],
  snack: ['lanche'],
  coffee: ['café', 'cafe'],
  breakfast: ['café da manhã', 'cafe da manha'],
  meeting: ['reunião', 'reuniao'],
  appointment: ['consulta'],
  dentist: ['dentista'],
  doctor: ['médico', 'medico', 'médica', 'medica'],
  gym: ['academia'],
  workout: ['treino'],
  school: ['escola'],
  work: ['trabalho'],
  office: ['escritório', 'escritorio'],
  clinic: ['clínica', 'clinica'],
  airport: ['aeroporto'],
  flight: ['voo'],
  birthday: ['aniversário', 'aniversario'],
  party: ['festa'],
  shopping: ['compras'],
  pharmacy: ['farmácia', 'farmacia'],
  bakery: ['padaria'],
  restaurant: ['restaurante'],
  haircut: ['cabeleireiro'],
  barber: ['barbeiro'],
  class: ['aula'],
  interview: ['entrevista'],
  call: ['ligação', 'ligacao', 'chamada'],
  walk: ['caminhada'],
  run: ['corrida'],
  soccer: ['futebol'],
  beach: ['praia'],
  park: ['parque'],
  home: ['casa'],
  mom: ['mãe', 'mae'],
  dad: ['pai'],
  team: ['equipe'],
};

function translateWords(text) {
  let t = text;
  for (const [pattern, english] of WORD_MAP) {
    t = t.replace(ptWord(pattern), english);
  }
  return t;
}

function titleForms(word) {
  const key = word.toLowerCase();
  return [key, ...(TITLE_ALIASES[key] || [])];
}

function normalizeSpeech(text) {
  return text
    .replace(/\b(\d{1,2})(?::(\d{2}))?\s*([ap])\s*\.?\s*m\.?\b/gi, (_, hour, minute, meridiem) => {
      return `${hour}:${minute || '00'} ${meridiem.toLowerCase()}m`;
    })
    .replace(/\s+\.m\b/gi, '');
}

function repairMishearing(text) {
  return text.replace(
    /^(please\s+)?at\s+(?!(?:\d|a\.?m\.?\b|p\.?m\.?\b|noon\b|midnight\b|morning\b|afternoon\b|evening\b|night\b|tonight\b))/i,
    (_, please = '') => `${please}add `,
  );
}

export function parseCommand(input, now = new Date()) {
  const raw = repairMishearing(normalizeSpeech(toEnglishCommand(input.replace(/\s+/g, ' ').trim())));
  const text = raw.replace(/[?.!]+$/g, '').trim();
  if (!text) return command(raw, { intent: 'unknown' });

  if (/^(?:help|commands)$/i.test(text) || /what can you do/i.test(text)) {
    return command(raw, { intent: 'help' });
  }
  if (/^(?:please\s+)?(?:undo(?:\s+that)?|take that back)$/i.test(text)) {
    return command(raw, { intent: 'undo' });
  }
  if (/what(?:'s| is) next|what do i have next|next appointment|what(?:'s| is) coming up|whats next/i.test(text)) {
    return command(raw, { intent: 'next' });
  }

  const note = text.match(/^(?:please\s+)?(?:add\s+)?(?:a\s+)?note\s+(?:on|for|to)\s+(.+?)\s+(?:saying|that|:)\s+(.+)$/i)
    || text.match(/^(?:please\s+)?(?:change|update|set)\s+(?:the\s+)?notes?\s+(?:on|for|of)\s+(.+?)\s+to\s+(.+)$/i);
  if (note) return command(raw, { intent: 'notes', query: cleanQuery(note[1]), notes: note[2].trim() });

  const location = text.match(/^(?:please\s+)?(?:set|change|update)\s+(?:the\s+)?location\s+(?:of|for)\s+(.+?)\s+to\s+(.+)$/i);
  if (location) return command(raw, { intent: 'location', query: cleanQuery(location[1]), location: location[2].trim() });

  const makeDuration = text.match(/^(?:please\s+)?(?:make|set)\s+(.+?)\s+(\d+)\s*(minutes|minute|mins|min|hours|hour|hrs|hr)(?:\s+long)?$/i);
  if (makeDuration) {
    return command(raw, {
      intent: 'duration',
      query: cleanQuery(makeDuration[1]),
      durationMinutes: toMinutes(makeDuration[2], makeDuration[3]),
      durationMode: 'set',
    });
  }
  const extend = text.match(/^(?:please\s+)?(?:extend|lengthen)\s+(.+?)\s+by\s+(\d+)\s*(minutes|minute|mins|min|hours|hour|hrs|hr)$/i);
  if (extend) {
    return command(raw, {
      intent: 'duration',
      query: cleanQuery(extend[1]),
      durationMinutes: toMinutes(extend[2], extend[3]),
      durationMode: 'extend',
    });
  }
  const shorten = text.match(/^(?:please\s+)?(?:shorten|reduce)\s+(.+?)\s+by\s+(\d+)\s*(minutes|minute|mins|min|hours|hour|hrs|hr)$/i);
  if (shorten) {
    return command(raw, {
      intent: 'duration',
      query: cleanQuery(shorten[1]),
      durationMinutes: toMinutes(shorten[2], shorten[3]),
      durationMode: 'shorten',
    });
  }

  if (/^(?:please\s+)?(?:cancel|delete|remove|drop)\b/i.test(text)) {
    let rest = text.replace(/^(?:please\s+)?(?:cancel|delete|remove|drop)\b\s*/i, '');
    const every = /^(?:both|all|every|each)\b/i.test(rest.trim());
    if (every) {
      rest = rest.replace(/^(?:both|all|every|each)(?:\s+of\s+(?:the|my|them|those))?\s*/i, '');
      rest = rest.replace(/^(?:appointments|meetings|events)\s+(?:called|named)\s+/i, '');
      rest = rest.replace(/^(?:appointments|meetings|events)$/i, '');
    }
    return command(raw, { intent: 'cancel', query: cleanQuery(rest), every });
  }
  if (isList(text)) return command(raw, { intent: 'list', ...rangeFrom(text, now) });

  const rename = text.match(/^(?:please\s+)?(?:rename|retitle)\s+(.+?)\s+\bto\b\s+(.+)$/i);
  if (rename) return command(raw, { intent: 'rename', query: cleanQuery(rename[1]), title: sentence(rename[2]) });

  const move = text.match(/^(?:please\s+)?(?:move|reschedule|push|shift)\s+(.+?)\s+\bto\b\s+(.+)$/i);
  if (move) {
    const when = parseWhen(move[2], now);
    return command(raw, {
      intent: 'move',
      query: cleanQuery(move[1]),
      start: when.start,
      end: when.end,
      assumedTime: when.assumedTime,
    });
  }

  const changeTo = text.match(/^(?:please\s+)?(?:change|update)\s+(.+?)\s+\bto\b\s+(.+)$/i);
  if (changeTo && !/^location\b/i.test(changeTo[1])) {
    const when = parseWhen(changeTo[2], now);
    if (when.start) {
      return command(raw, {
        intent: 'move',
        query: cleanQuery(changeTo[1]),
        start: when.start,
        end: when.end,
        assumedTime: when.assumedTime,
      });
    }
    return command(raw, { intent: 'rename', query: cleanQuery(changeTo[1]), title: sentence(changeTo[2]) });
  }

  if (/^(?:please\s+)?(?:add|schedule|create|book|set up|setup|put|new)\b/i.test(text)) {
    return parseAdd(text, now, raw);
  }
  if (/^(?:please\s+)?(?:edit|open)\b/i.test(text)) {
    const query = cleanQuery(text.replace(/^(?:please\s+)?(?:edit|open)\b\s*/i, ''));
    return command(raw, { intent: 'open', query });
  }
  return command(raw, { intent: 'unknown' });
}

export function numberFrom(text) {
  const t = text.trim().toLowerCase();
  const digit = t.match(/^(?:number\s+|the\s+|n[uú]mero\s+|o\s+)?(\d+)$/u);
  if (digit) return Number(digit[1]);
  const words = {
    one: 1, two: 2, three: 3, four: 4, five: 5,
    first: 1, second: 2, third: 3, fourth: 4, fifth: 5,
    um: 1, dois: 2, tres: 3, três: 3, quatro: 4, cinco: 5,
    primeiro: 1, segundo: 2, terceiro: 3, quarto: 4, quinto: 5,
  };
  const word = t.match(/^(?:the\s+|o\s+|a\s+|n[uú]mero\s+)?(one|two|three|four|five|first|second|third|fourth|fifth|um|dois|tres|três|quatro|cinco|primeiro|segundo|terceiro|quarto|quinto)$/u);
  return word ? words[word[1]] : null;
}

export function findCandidates(events, query, now = new Date()) {
  const q = (query || '').toLowerCase().replace(/[?.!]/g, '').replace(/\s+/g, ' ').trim();
  if (!q) return [];
  const when = chrono.parse(q, now, { forwardDate: true })[0];
  let time = null;
  if (when?.start?.isCertain('hour')) time = maybeAfternoon(when.start.date(), when.text);
  const words = q.split(' ').filter((word) => word.length > 2 && !STOP.has(word));
  const scored = [];
  for (const event of events) {
    const title = event.title.toLowerCase();
    let score = 0;
    if (title === q) score = 100;
    else if (title.length > 2 && (q.includes(title) || title.includes(q))) score = 80;
    else if (words.length) {
      const hits = words.filter((word) => titleForms(word).some((form) => title.includes(form))).length;
      if (hits === words.length) score = 65;
      else if (hits) score = hits * 20;
    }
    if (time) {
      const start = new Date(event.start);
      if (start.getHours() === time.getHours() && start.getMinutes() === time.getMinutes()) score += 45;
    }
    if (score >= 40) scored.push({ event, score });
  }
  scored.sort((a, b) => b.score - a.score || new Date(a.event.start) - new Date(b.event.start));
  if (!scored.length) return [];
  const top = scored[0].score;
  return scored.filter((item) => item.score >= top - 5).map((item) => item.event);
}
