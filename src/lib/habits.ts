import { LocalCommand, MediaKey, normalizeCommand } from './localRouter';

const HABITS_STORAGE_KEY = 'jarvis_habits_v1';

interface HabitsData {
  counts: Record<string, number>;
  hours: Record<string, number[]>;
  phrases: Record<string, string>;
}

export function commandToHabitKey(cmd: LocalCommand): string | null {
  switch (cmd.type) {
    case 'SetVolume':
      return `volume:${cmd.percent}`;
    case 'StepVolume':
      return cmd.up ? 'volume:up' : 'volume:down';
    case 'Mute':
      return cmd.on ? 'mute:on' : 'mute:off';
    case 'Media':
      return `media:${cmd.key.toLowerCase()}`;
    case 'Torch':
      return cmd.on ? 'torch:on' : 'torch:off';
    case 'Battery':
      return 'battery';
    case 'Time':
      return 'time';
    case 'Date':
      return 'date';
    case 'Timer':
      return `timer:${cmd.seconds}`;
    case 'OpenApp':
      return `app:${cmd.name.toLowerCase()}`;
    default:
      return null;
  }
}

export function habitKeyToCommand(key: string): LocalCommand | null {
  const [kind, value = ''] = key.split(':', 2);
  switch (kind) {
    case 'volume':
      if (value === 'up') return { type: 'StepVolume', up: true, stream: 'Media' };
      if (value === 'down') return { type: 'StepVolume', up: false, stream: 'Media' };
      {
        const pct = parseInt(value, 10);
        return !isNaN(pct) && pct >= 0 && pct <= 100 ? { type: 'SetVolume', percent: pct, stream: 'Media' } : null;
      }
    case 'mute':
      return { type: 'Mute', on: value === 'on', mediaOnly: false };
    case 'media': {
      const mk = (['Play', 'Pause', 'Next', 'Previous'] as MediaKey[]).find((k) => k.toLowerCase() === value);
      return mk ? { type: 'Media', key: mk } : null;
    }
    case 'torch':
      return { type: 'Torch', on: value === 'on' };
    case 'battery':
      return { type: 'Battery' };
    case 'time':
      return { type: 'Time' };
    case 'date':
      return { type: 'Date' };
    case 'timer': {
      const sec = parseInt(value, 10);
      return !isNaN(sec) && sec >= 1 && sec <= 86400 ? { type: 'Timer', seconds: sec } : null;
    }
    case 'app':
      return value ? { type: 'OpenApp', name: value } : null;
    default:
      return null;
  }
}

export function describeHabitKey(key: string): string {
  const [kind, value = ''] = key.split(':', 2);
  switch (kind) {
    case 'volume':
      return /^\d+$/.test(value) ? `volume ${value}%` : `volume ${value}`;
    case 'mute':
      return value === 'on' ? 'mute' : 'unmute';
    case 'media':
      return `music ${value}`;
    case 'torch':
      return `torch ${value}`;
    case 'timer':
      return `${Math.max(1, Math.round((parseInt(value, 10) || 60) / 60))} min timer`;
    case 'app':
      return `open ${value.charAt(0).toUpperCase() + value.slice(1)}`;
    default:
      return kind;
  }
}

function loadData(): HabitsData {
  try {
    const raw = localStorage.getItem(HABITS_STORAGE_KEY);
    if (!raw) return { counts: {}, hours: {}, phrases: {} };
    const parsed = JSON.parse(raw) as Partial<HabitsData>;
    return {
      counts: parsed.counts ?? {},
      hours: parsed.hours ?? {},
      phrases: parsed.phrases ?? {},
    };
  } catch {
    return { counts: {}, hours: {}, phrases: {} };
  }
}

function saveData(data: HabitsData): void {
  try {
    localStorage.setItem(HABITS_STORAGE_KEY, JSON.stringify(data));
  } catch {
    // ignore storage quota errors
  }
}

let pendingExpect: { phrase: string; at: number } | null = null;

export function recordHabit(command: LocalCommand, hour = new Date().getHours()): void {
  const key = commandToHabitKey(command);
  if (!key) return;
  const data = loadData();
  data.counts[key] = (data.counts[key] ?? 0) + 1;
  if (!data.hours[key]) data.hours[key] = new Array(24).fill(0);
  data.hours[key][Math.max(0, Math.min(23, hour))]++;
  saveData(data);
}

export function expectHabitPhrase(text: string): void {
  const normalized = normalizeCommand(text);
  if (!normalized || normalized.split(' ').length > 8) return;
  pendingExpect = { phrase: normalized, at: Date.now() };
}

export function learnHabitPhrase(command: LocalCommand): void {
  if (!pendingExpect) return;
  const { phrase, at } = pendingExpect;
  pendingExpect = null;
  if (Date.now() - at > 60_000) return;
  const key = commandToHabitKey(command);
  if (!key) return;
  const data = loadData();
  data.phrases[phrase] = key;
  const keys = Object.keys(data.phrases);
  if (keys.length > 200) delete data.phrases[keys[0]];
  saveData(data);
}

export function lookupHabitPhrase(text: string): LocalCommand | null {
  const norm = normalizeCommand(text);
  if (!norm) return null;
  const data = loadData();
  const key = data.phrases[norm];
  return key ? habitKeyToCommand(key) : null;
}

export function topHabits(n: number, min = 3): LocalCommand[] {
  const data = loadData();
  return Object.entries(data.counts)
    .filter(([, count]) => count >= min)
    .sort((a, b) => b[1] - a[1])
    .map(([key]) => habitKeyToCommand(key))
    .filter((cmd): cmd is LocalCommand => cmd !== null)
    .slice(0, n);
}

export function habitsSummary(hour = new Date().getHours()): string {
  const data = loadData();
  const entries = Object.entries(data.counts);
  if (entries.length === 0) return '';
  const often = entries
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .map(([k, count]) => `${describeHabitKey(k)} (${count}×)`)
    .join(', ');
  const h = Math.max(0, Math.min(23, hour));
  const now = Object.entries(data.hours)
    .filter(([, arr]) => (arr[h] ?? 0) >= 2)
    .map(([k]) => describeHabitKey(k))
    .join(', ');
  return `Often asks for: ${often}.${now ? ` Around this hour usually: ${now}.` : ''}`;
}

export function learnedOverview(): string {
  const data = loadData();
  const cmds = Object.keys(data.counts).length;
  const phrases = Object.keys(data.phrases).length;
  if (cmds === 0 && phrases === 0) return 'Nothing learned yet.';
  return `${cmds} frequent command(s), ${phrases} learned phrase(s) stored locally.`;
}

export function forgetHabits(): void {
  pendingExpect = null;
  try {
    localStorage.removeItem(HABITS_STORAGE_KEY);
  } catch {
    // ignore
  }
}
