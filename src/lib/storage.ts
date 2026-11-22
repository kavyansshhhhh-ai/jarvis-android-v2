import { GOOD_MORNING_ROUTINE, MAX_NOTES, MAX_NOTE_LENGTH, MAX_ROUTINES, MAX_STEPS, looksSecret, routineKey } from './memoryRules';
import { EMPTY_PROFILE, sanitizeProfile } from './personalProfile';
import {
  AlarmItem,
  AppSettingsState,
  AUTOMATION_CATEGORIES,
  AutomationCategoryId,
  Note,
  PersonalProfile,
  ReminderItem,
  Routine,
} from './types';

const KEYS = {
  SETTINGS: 'jarvis_settings_v1',
  PROFILE: 'jarvis_profile_v1',
  NOTES: 'jarvis_notes_v1',
  ROUTINES: 'jarvis_routines_v1',
  REMINDERS: 'jarvis_reminders_v1',
  ALARMS: 'jarvis_alarms_v1',
};

const DEFAULT_AUTOMATIONS: Record<AutomationCategoryId, boolean> = AUTOMATION_CATEGORIES.reduce(
  (acc, item) => {
    acc[item.id] = item.defaultEnabled;
    return acc;
  },
  {} as Record<AutomationCategoryId, boolean>,
);

export const DEFAULT_SETTINGS: AppSettingsState = {
  appearanceMode: 'Dark',
  replyLanguage: 'auto',
  speakResponses: true,
  ttsVoiceId: null,
  ttsGenderFilter: 'FEMALE',
  ttsSpeechRate: 1.0,
  ttsPitch: 1.0,
  talkOver: true,
  waveToStop: true,
  wakeWord: false,
  wakeIndicator: true,
  wakeDotSizeDp: 12,
  wakeDotDurationMs: 2800,
  localCommands: true,
  learning: true,
  homeCity: '',
  automations: DEFAULT_AUTOMATIONS,
};

export function loadSettings(): AppSettingsState {
  try {
    const raw = localStorage.getItem(KEYS.SETTINGS);
    if (!raw) return DEFAULT_SETTINGS;
    const parsed = JSON.parse(raw) as Partial<AppSettingsState>;
    return {
      ...DEFAULT_SETTINGS,
      ...parsed,
      automations: { ...DEFAULT_AUTOMATIONS, ...(parsed.automations ?? {}) },
      wakeDotSizeDp: Math.max(9, Math.min(50, parsed.wakeDotSizeDp ?? 12)),
      ttsSpeechRate: Math.max(0.5, Math.min(2.0, parsed.ttsSpeechRate ?? 1.0)),
      ttsPitch: Math.max(0.5, Math.min(1.5, parsed.ttsPitch ?? 1.0)),
    };
  } catch {
    return DEFAULT_SETTINGS;
  }
}

export function saveSettings(s: AppSettingsState): void {
  try {
    localStorage.setItem(KEYS.SETTINGS, JSON.stringify(s));
  } catch {
    // ignore
  }
}

export function loadProfile(): PersonalProfile {
  try {
    const raw = localStorage.getItem(KEYS.PROFILE);
    if (!raw) return EMPTY_PROFILE;
    return sanitizeProfile(JSON.parse(raw) as PersonalProfile);
  } catch {
    return EMPTY_PROFILE;
  }
}

export function saveProfile(p: PersonalProfile): PersonalProfile {
  const clean = sanitizeProfile(p);
  try {
    localStorage.setItem(KEYS.PROFILE, JSON.stringify(clean));
  } catch {
    // ignore
  }
  return clean;
}

export function clearProfile(): void {
  try {
    localStorage.removeItem(KEYS.PROFILE);
  } catch {
    // ignore
  }
}

export function loadNotes(): Note[] {
  try {
    const raw = localStorage.getItem(KEYS.NOTES);
    return raw ? (JSON.parse(raw) as Note[]) : [];
  } catch {
    return [];
  }
}

export function saveNotes(notes: Note[]): void {
  try {
    localStorage.setItem(KEYS.NOTES, JSON.stringify(notes.slice(0, MAX_NOTES)));
  } catch {
    // ignore
  }
}

export function addNote(text: string): { ok: boolean; note?: Note; error?: string; notes: Note[] } {
  const clean = text.trim().slice(0, MAX_NOTE_LENGTH);
  const current = loadNotes();
  if (!clean) return { ok: false, error: 'Note cannot be empty.', notes: current };
  if (looksSecret(clean)) {
    return {
      ok: false,
      error: 'Refused: memory never stores passwords, PINs, OTPs, CVVs, or card numbers.',
      notes: current,
    };
  }
  const now = Date.now();
  const note: Note = { id: crypto.randomUUID(), text: clean, atMillis: now, updatedAtMillis: now };
  const next = [note, ...current].slice(0, MAX_NOTES);
  saveNotes(next);
  return { ok: true, note, notes: next };
}

export function removeNote(idOrQuery: string): Note[] {
  const current = loadNotes();
  const lower = idOrQuery.toLowerCase().trim();
  const next = current.filter((n) => n.id !== idOrQuery && !n.text.toLowerCase().includes(lower));
  saveNotes(next);
  return next;
}

export function loadRoutines(): Routine[] {
  try {
    const raw = localStorage.getItem(KEYS.ROUTINES);
    const custom: Routine[] = raw ? (JSON.parse(raw) as Routine[]) : [];
    const hasMorning = custom.some((r) => routineKey(r.name) === routineKey(GOOD_MORNING_ROUTINE.name));
    return hasMorning ? custom : [GOOD_MORNING_ROUTINE, ...custom];
  } catch {
    return [GOOD_MORNING_ROUTINE];
  }
}

export function saveCustomRoutine(name: string, steps: string[]): Routine[] {
  const cleanName = name.trim().slice(0, 60);
  const cleanSteps = steps.map((s) => s.trim()).filter(Boolean).slice(0, MAX_STEPS);
  if (!cleanName || cleanSteps.length === 0) return loadRoutines();
  const current = loadRoutines().filter((r) => !r.builtIn && routineKey(r.name) !== routineKey(cleanName));
  const nextCustom = [{ name: cleanName, steps: cleanSteps }, ...current].slice(0, MAX_ROUTINES);
  try {
    localStorage.setItem(KEYS.ROUTINES, JSON.stringify(nextCustom));
  } catch {
    // ignore
  }
  return loadRoutines();
}

export function deleteCustomRoutine(name: string): Routine[] {
  const current = loadRoutines().filter((r) => !r.builtIn && routineKey(r.name) !== routineKey(name));
  try {
    localStorage.setItem(KEYS.ROUTINES, JSON.stringify(current));
  } catch {
    // ignore
  }
  return loadRoutines();
}

export function loadReminders(): ReminderItem[] {
  try {
    const raw = localStorage.getItem(KEYS.REMINDERS);
    return raw ? (JSON.parse(raw) as ReminderItem[]) : [];
  } catch {
    return [];
  }
}

export function saveReminders(items: ReminderItem[]): void {
  try {
    localStorage.setItem(KEYS.REMINDERS, JSON.stringify(items));
  } catch {
    // ignore
  }
}

export function loadAlarms(): AlarmItem[] {
  try {
    const raw = localStorage.getItem(KEYS.ALARMS);
    return raw ? (JSON.parse(raw) as AlarmItem[]) : [];
  } catch {
    return [];
  }
}

export function saveAlarms(items: AlarmItem[]): void {
  try {
    localStorage.setItem(KEYS.ALARMS, JSON.stringify(items));
  } catch {
    // ignore
  }
}
