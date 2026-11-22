import { Note, Routine } from './types';

const SECRET_WORDS =
  /\b(password|passcode|pass word|pin|otp|cvv|upi pin|atm pin|secret|private key|seed phrase|login code)\b/i;
const CARD = /\b(?:\d[ -]?){13,19}\b/;
const STOP = new Set([
  'the', 'a', 'an', 'my', 'i', 'is', 'was', 'where', 'what', 'did', 'do', 'to', 'of', 'in', 'on', 'at',
  'that', 'this', 'me', 'remember', 'about', 'kya', 'kaha', 'kahan', 'hai', 'tha', 'mera', 'meri', 'ka', 'ki', 'ke',
]);

export const MAX_NOTES = 200;
export const MAX_NOTE_LENGTH = 300;
export const MAX_ROUTINES = 20;
export const MAX_STEPS = 10;

export const GOOD_MORNING_ROUTINE: Routine = {
  name: 'Good morning',
  steps: [
    'Say the date and time',
    "Today's weather",
    'What I missed (messages, calls, emails)',
    "Today's notes and reminders",
  ],
  builtIn: true,
};

export function looksSecret(text: string): boolean {
  return SECRET_WORDS.test(text) || CARD.test(text);
}

function words(text: string): string[] {
  return text
    .toLowerCase()
    .split(/[^a-z0-9\u0900-\u097f]+/)
    .filter((w) => w.length >= 2 && !STOP.has(w));
}

export function findNotes(query: string, notes: Note[], limit = 5): Note[] {
  const wanted = words(query);
  if (wanted.length === 0) {
    return [...notes].sort((a, b) => b.atMillis - a.atMillis).slice(0, limit);
  }
  return notes
    .map((note) => {
      const noteWords = words(note.text);
      const score = noteWords.filter((w) => wanted.some((q) => w.startsWith(q) || q.startsWith(w))).length;
      return { note, score };
    })
    .filter((item) => item.score > 0)
    .sort((a, b) => b.score - a.score || b.note.atMillis - a.note.atMillis)
    .slice(0, limit)
    .map((item) => item.note);
}

export function routineKey(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9\u0900-\u097f]+/g, ' ').trim();
}

export function findRoutine(name: string, all: Routine[]): Routine | undefined {
  const k = routineKey(name);
  if (!k) return undefined;
  return (
    all.find((r) => routineKey(r.name) === k) ??
    all.find((r) => routineKey(r.name).includes(k) || k.includes(routineKey(r.name)))
  );
}
