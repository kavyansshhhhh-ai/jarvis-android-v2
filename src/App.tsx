import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { WakeIndicatorDot } from './components/GlassPrimitives';
import { AppTab, LiquidDock } from './components/LiquidDock';
import { OnboardingModal } from './components/OnboardingModal';
import { PersonalProfileModal } from './components/PersonalProfileModal';
import {
  expectHabitPhrase,
  forgetHabits,
  habitsSummary,
  learnedOverview,
  learnHabitPhrase,
  lookupHabitPhrase,
  recordHabit,
  topHabits,
} from './lib/habits';
import { LocalCommand, matchLocalCommand } from './lib/localRouter';
import { profileSummary } from './lib/personalProfile';
import { playChime, speakText, stopSpeaking } from './lib/speech';
import {
  addNote,
  clearProfile,
  deleteCustomRoutine,
  loadAlarms,
  loadNotes,
  loadProfile,
  loadReminders,
  loadRoutines,
  loadSettings,
  removeNote,
  saveAlarms,
  saveCustomRoutine,
  saveNotes,
  saveProfile,
  saveReminders,
  saveSettings,
} from './lib/storage';
import { triageNotifications } from './lib/triage';
import {
  ActivityEntry,
  AlarmItem,
  AppSettingsState,
  ChatLine,
  LocalPhase,
  Note,
  PersonalProfile,
  Phase,
  ReminderItem,
  Routine,
  SeenNotification,
  TaskItem,
  TimerItem,
} from './lib/types';
import { ActivityScreen } from './screens/ActivityScreen';
import { AutomationsScreen } from './screens/AutomationsScreen';
import { HomeScreen } from './screens/HomeScreen';
import { SettingsScreen } from './screens/SettingsScreen';

interface SpeechRecognitionInstance {
  lang: string;
  interimResults: boolean;
  continuous: boolean;
  onresult: ((ev: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null;
  onerror: ((ev: { error?: string }) => void) | null;
  onend: (() => void) | null;
  start: () => void;
  stop: () => void;
}

type SpeechRecognitionCtor = new () => SpeechRecognitionInstance;

const INITIAL_NOTIFICATIONS: SeenNotification[] = [
  {
    id: 'n1',
    packageName: 'com.whatsapp',
    app: 'WhatsApp',
    title: 'Mom',
    text: 'Call me when you get free.',
    atMillis: Date.now() - 12 * 60_000,
    category: 'msg',
  },
  {
    id: 'n2',
    packageName: 'com.google.android.gm',
    app: 'Gmail',
    title: 'Team Standup',
    text: 'Notes from this morning sync',
    atMillis: Date.now() - 28 * 60_000,
    category: 'email',
  },
  {
    id: 'n3',
    packageName: 'com.google.android.apps.messaging',
    app: 'Messages',
    title: 'Bank Alert',
    text: 'Your OTP is 847291 for verification.',
    atMillis: Date.now() - 40 * 60_000,
    category: 'msg',
  },
];

function commandLabel(cmd: LocalCommand): string {
  switch (cmd.type) {
    case 'Time':
      return 'Time';
    case 'Date':
      return 'Date';
    case 'Battery':
      return 'Battery';
    case 'Timer':
      return `${Math.max(1, Math.round(cmd.seconds / 60))}m Timer`;
    case 'Torch':
      return cmd.on ? 'Torch on' : 'Torch off';
    case 'Media':
      return cmd.key;
    case 'SetVolume':
      return `Vol ${cmd.percent}%`;
    case 'StepVolume':
      return cmd.up ? 'Vol up' : 'Vol down';
    case 'Mute':
      return cmd.on ? 'Mute' : 'Unmute';
    case 'OpenApp':
      return `Open ${cmd.name}`;
    default:
      return cmd.type;
  }
}

export function App() {
  const [tab, setTab] = useState<AppTab>('Home');
  const [settings, setSettings] = useState<AppSettingsState>(() => loadSettings());
  const [profile, setProfile] = useState<PersonalProfile>(() => loadProfile());
  const [notes, setNotes] = useState<Note[]>(() => loadNotes());
  const [routines, setRoutines] = useState<Routine[]>(() => loadRoutines());
  const [reminders, setReminders] = useState<ReminderItem[]>(() => loadReminders());
  const [alarms, setAlarms] = useState<AlarmItem[]>(() => loadAlarms());
  const [timers, setTimers] = useState<TimerItem[]>([]);
  const [notifications, setNotifications] = useState<SeenNotification[]>(INITIAL_NOTIFICATIONS);

  const [phase, setPhase] = useState<Phase>('Off');
  const [localPhase, setLocalPhase] = useState<LocalPhase>('Idle');
  const [audioLevel, setAudioLevel] = useState(0);
  const [problem, setProblem] = useState<string | null>(null);
  const [lines, setLines] = useState<ChatLine[]>([]);
  const [activity, setActivity] = useState<ActivityEntry[]>([]);
  const [task, setTask] = useState<TaskItem | null>(null);
  const [learnedText, setLearnedText] = useState(() => learnedOverview());
  const [wakeDotVisible, setWakeDotVisible] = useState(false);

  const [showProfileModal, setShowProfileModal] = useState(false);
  const [showOnboarding, setShowOnboarding] = useState(false);
  const [isDictating, setIsDictating] = useState(false);
  const [micPermission, setMicPermission] = useState<'granted' | 'denied' | 'prompt' | 'unsupported'>('prompt');
  const [systemDark, setSystemDark] = useState(() =>
    typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches,
  );

  const recognitionRef = useRef<SpeechRecognitionInstance | null>(null);
  const micStreamRef = useRef<MediaStream | null>(null);
  const levelRafRef = useRef<number>(0);

  const isDark = useMemo(() => {
    if (settings.appearanceMode === 'Dark') return true;
    if (settings.appearanceMode === 'Light') return false;
    return systemDark;
  }, [settings.appearanceMode, systemDark]);

  useEffect(() => {
    document.documentElement.classList.toggle('dark', isDark);
  }, [isDark]);

  useEffect(() => {
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)');
    if (!mq) return;
    const handler = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    mq.addEventListener('change', handler);
    return () => mq.removeEventListener('change', handler);
  }, []);

  useEffect(() => {
    if (!navigator.permissions?.query) return;
    navigator.permissions
      .query({ name: 'microphone' as PermissionName })
      .then((status) => {
        setMicPermission(status.state);
        status.onchange = () => setMicPermission(status.state);
      })
      .catch(() => {});
  }, []);

  // Timer & alarm ticker
  useEffect(() => {
    const id = window.setInterval(() => {
      const now = Date.now();
      setTimers((prev) => {
        let changed = false;
        const next = prev.map((t) => {
          if (!t.done && now >= t.endsAt) {
            changed = true;
            playChime();
            return { ...t, done: true };
          }
          return t;
        });
        return changed ? next : prev;
      });
    }, 1000);
    return () => window.clearInterval(id);
  }, []);

  const updateSettings = useCallback((patch: Partial<AppSettingsState>) => {
    setSettings((prev) => {
      const next = { ...prev, ...patch };
      saveSettings(next);
      return next;
    });
  }, []);

  const triggerWakeDot = useCallback(() => {
    if (!settings.wakeIndicator) return;
    setWakeDotVisible(true);
    window.setTimeout(() => setWakeDotVisible(false), settings.wakeDotDurationMs);
  }, [settings.wakeIndicator, settings.wakeDotDurationMs]);

  const addActivity = useCallback((what: string, result: string, local: boolean) => {
    setActivity((prev) => [{ id: crypto.randomUUID(), what, result, at: Date.now(), local }, ...prev].slice(0, 100));
  }, []);

  const speakReply = useCallback(
    (text: string, isLocal = false) => {
      if (!settings.speakResponses) {
        setPhase('Off');
        setLocalPhase('Idle');
        return;
      }
      if (isLocal) setLocalPhase('Speaking');
      else setPhase('Speaking');

      speakText(text, {
        voiceId: settings.ttsVoiceId,
        rate: settings.ttsSpeechRate,
        pitch: settings.ttsPitch,
        onEnd: () => {
          setPhase('Off');
          setLocalPhase('Idle');
        },
        onError: () => {
          setPhase('Off');
          setLocalPhase('Idle');
        },
      });
    },
    [settings.speakResponses, settings.ttsVoiceId, settings.ttsSpeechRate, settings.ttsPitch],
  );

  const stopAudioMeter = useCallback(() => {
    cancelAnimationFrame(levelRafRef.current);
    setAudioLevel(0);
    if (micStreamRef.current) {
      micStreamRef.current.getTracks().forEach((tr) => tr.stop());
      micStreamRef.current = null;
    }
  }, []);

  const startAudioMeter = useCallback(async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      setMicPermission('granted');
      micStreamRef.current = stream;
      const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      const ctx = new AudioCtx();
      const source = ctx.createMediaStreamSource(stream);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 256;
      source.connect(analyser);
      const data = new Uint8Array(analyser.frequencyBinCount);
      const tick = () => {
        analyser.getByteFrequencyData(data);
        const avg = data.reduce((sum, v) => sum + v, 0) / data.length;
        setAudioLevel(Math.min(1, avg / 90));
        levelRafRef.current = requestAnimationFrame(tick);
      };
      levelRafRef.current = requestAnimationFrame(tick);
    } catch {
      setMicPermission('denied');
    }
  }, []);

  const executeLocalCommand = useCallback(
    async (cmd: LocalCommand): Promise<string> => {
      if (settings.learning) {
        recordHabit(cmd);
        setLearnedText(learnedOverview());
      }
      const now = new Date();
      switch (cmd.type) {
        case 'Time': {
          const t = now.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
          return `It is ${t}.`;
        }
        case 'Date': {
          const d = now.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
          return `Today is ${d}.`;
        }
        case 'Battery': {
          const nav = navigator as Navigator & { getBattery?: () => Promise<{ level: number; charging: boolean }> };
          if (nav.getBattery) {
            try {
              const bat = await nav.getBattery();
              const pct = Math.round(bat.level * 100);
              return `Battery is at ${pct}%${bat.charging ? ' and charging' : ''}.`;
            } catch {
              // fall through
            }
          }
          return 'Battery status is not exposed by this browser.';
        }
        case 'Timer': {
          const label = cmd.seconds >= 60 ? `${Math.round(cmd.seconds / 60)} min timer` : `${cmd.seconds}s timer`;
          setTimers((prev) => [
            { id: crypto.randomUUID(), label, totalSeconds: cmd.seconds, endsAt: Date.now() + cmd.seconds * 1000, done: false },
            ...prev,
          ]);
          return `Started a ${label}.`;
        }
        case 'Alarm': {
          let hour24 = cmd.hour;
          if (cmd.period === 'Pm' && hour24 < 12) hour24 += 12;
          if (cmd.period === 'Am' && hour24 === 12) hour24 = 0;
          const nextAlarms: AlarmItem[] = [
            { id: crypto.randomUUID(), hour: hour24, minute: cmd.minute, label: 'Alarm', enabled: true },
            ...alarms,
          ];
          setAlarms(nextAlarms);
          saveAlarms(nextAlarms);
          return `Alarm set for ${String(hour24).padStart(2, '0')}:${String(cmd.minute).padStart(2, '0')}.`;
        }
        case 'Torch':
          return `Flashlight ${cmd.on ? 'on' : 'off'} (hardware torch is available on mobile devices).`;
        case 'SetVolume':
          return `Volume target noted: ${cmd.percent}% (${cmd.stream}).`;
        case 'StepVolume':
          return `Volume ${cmd.up ? 'increased' : 'decreased'} (${cmd.stream}).`;
        case 'Mute':
          return cmd.on ? 'Muted audio.' : 'Unmuted audio.';
        case 'Media':
          return `Media command: ${cmd.key}.`;
        case 'OpenApp': {
          const lower = cmd.name.toLowerCase();
          const urlMap: Record<string, string> = {
            youtube: 'https://www.youtube.com',
            spotify: 'https://open.spotify.com',
            whatsapp: 'https://web.whatsapp.com',
            gmail: 'https://mail.google.com',
            calendar: 'https://calendar.google.com',
            maps: 'https://maps.google.com',
          };
          const matchedUrl = Object.entries(urlMap).find(([k]) => lower.includes(k))?.[1];
          if (matchedUrl) {
            setTask({
              id: crypto.randomUUID(),
              title: `Open ${cmd.name}`,
              state: 'Completed',
              risk: 'Low',
              steps: [{ label: `Ready to open ${cmd.name}`, done: true }],
              detail: matchedUrl,
              at: Date.now(),
              pendingAction: {
                kind: 'open_url',
                title: `Open ${cmd.name}`,
                summary: matchedUrl,
                url: matchedUrl,
                confirmLabel: `Open ${cmd.name}`,
              },
            });
            return `Prepared ${cmd.name} link below.`;
          }
          return `App shortcut "${cmd.name}" is ready on Android; in browser, ask me to open YouTube, Spotify, Maps, Calendar, Gmail, or WhatsApp.`;
        }
        case 'ListApps':
          return 'Supported web shortcuts: YouTube, Spotify, WhatsApp Web, Gmail, Google Calendar, and Google Maps.';
        case 'SearchApps':
          return `"${cmd.query}" can be opened as a web shortcut if supported, or handled by Gemini.`;
        case 'Stop':
        case 'Shutdown':
          stopSpeaking();
          setPhase('Off');
          setLocalPhase('Idle');
          return 'Stopped.';
        case 'SystemPanel':
          return `${cmd.target} panel is a device-level Android setting.`;
      }
    },
    [alarms, settings.learning],
  );

  const handleQuickCommand = useCallback(
    (cmd: LocalCommand): string => {
      const label = commandLabel(cmd);
      let syncMsg = 'Done.';
      executeLocalCommand(cmd).then((msg) => {
        addActivity(label, msg, true);
      });
      if (cmd.type === 'Time') syncMsg = `It is ${new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}.`;
      else if (cmd.type === 'Date') syncMsg = `Today is ${new Date().toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' })}.`;
      else if (cmd.type === 'Timer') syncMsg = `Started ${Math.round(cmd.seconds / 60)} min timer.`;
      else if (cmd.type === 'Battery') syncMsg = 'Checking battery status…';
      return syncMsg;
    },
    [addActivity, executeLocalCommand],
  );

  const sendMessage = useCallback(
    async (rawText: string) => {
      const text = rawText.trim();
      if (!text) return;

      setProblem(null);
      stopSpeaking();
      const userLine: ChatLine = { id: crypto.randomUUID(), text, fromUser: true, at: Date.now() };
      setLines((prev) => [...prev, userLine]);

      // 1. Check local router first when enabled
      if (settings.localCommands) {
        const localCmd = matchLocalCommand(text) ?? (settings.learning ? lookupHabitPhrase(text) : null);
        if (localCmd) {
          setLocalPhase('Doing');
          const reply = await executeLocalCommand(localCmd);
          addActivity(text, reply, true);
          setLines((prev) => [...prev, { id: crypto.randomUUID(), text: reply, fromUser: false, at: Date.now() }]);
          speakReply(reply, true);
          return;
        }
      }

      if (settings.learning) {
        expectHabitPhrase(text);
      }

      // 2. Call server-side Gemini Intelligence
      setPhase('Connecting');
      try {
        const triaged = triageNotifications(notifications);
        const res = await fetch('/api/assistant/chat', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            message: text,
            history: lines.slice(-10).map((l) => ({ fromUser: l.fromUser, text: l.text })),
            context: {
              replyLanguage: settings.replyLanguage,
              profileSummary: profileSummary(profile),
              habitsSummary: settings.learning ? habitsSummary() : '',
              homeCity: settings.homeCity,
              notes: notes.slice(0, 30),
              routines,
              reminders,
              triageSummary: triaged.summaryText,
              automations: settings.automations,
            },
          }),
        });

        const data = (await res.json()) as {
          reply?: string;
          error?: string;
          actions?: Array<{ type: string; payload: Record<string, unknown> }>;
          task?: Omit<TaskItem, 'id' | 'at'> | null;
          sources?: Array<{ title: string; uri: string }>;
        };

        if (!res.ok || data.error) {
          const errMsg = data.error || `Request failed (${res.status})`;
          setProblem(errMsg);
          setPhase('Off');
          setLines((prev) => [
            ...prev,
            { id: crypto.randomUUID(), text: `Error: ${errMsg}`, fromUser: false, at: Date.now() },
          ]);
          return;
        }

        // Apply returned client actions
        for (const act of data.actions ?? []) {
          switch (act.type) {
            case 'remember_note': {
              const resNote = addNote(String(act.payload.text || ''));
              if (resNote.ok) setNotes(resNote.notes);
              break;
            }
            case 'forget_note': {
              setNotes(removeNote(String(act.payload.query || '')));
              break;
            }
            case 'routine_save': {
              const name = String(act.payload.name || '');
              const steps = Array.isArray(act.payload.steps) ? act.payload.steps.map(String) : [];
              setRoutines(saveCustomRoutine(name, steps));
              break;
            }
            case 'set_timer': {
              const sec = Number(act.payload.seconds) || 60;
              const label = String(act.payload.label || 'Timer');
              setTimers((prev) => [
                { id: crypto.randomUUID(), label, totalSeconds: sec, endsAt: Date.now() + sec * 1000, done: false },
                ...prev,
              ]);
              learnHabitPhrase({ type: 'Timer', seconds: sec });
              break;
            }
            case 'set_alarm': {
              const hour = Number(act.payload.hour) || 7;
              const minute = Number(act.payload.minute) || 0;
              const label = String(act.payload.label || 'Alarm');
              const next = [{ id: crypto.randomUUID(), hour, minute, label, enabled: true }, ...alarms];
              setAlarms(next);
              saveAlarms(next);
              break;
            }
            case 'create_reminder': {
              const rText = String(act.payload.text || '');
              const mins = Number(act.payload.minutesFromNow) || 30;
              const next = [
                { id: crypto.randomUUID(), text: rText, dueAt: Date.now() + mins * 60_000, done: false },
                ...reminders,
              ];
              setReminders(next);
              saveReminders(next);
              break;
            }
            case 'set_home_city': {
              const city = String(act.payload.city || '');
              if (city) updateSettings({ homeCity: city });
              break;
            }
          }
        }

        if (data.task) {
          setTask({
            ...data.task,
            id: crypto.randomUUID(),
            at: Date.now(),
          });
        }

        const reply = data.reply || 'Done.';
        addActivity(text, reply.slice(0, 120), false);
        setLines((prev) => [
          ...prev,
          { id: crypto.randomUUID(), text: reply, fromUser: false, at: Date.now(), sources: data.sources },
        ]);
        speakReply(reply, false);
      } catch (err) {
        const msg = err instanceof Error ? err.message : 'Network error';
        setProblem(msg);
        setPhase('Off');
        setLines((prev) => [
          ...prev,
          { id: crypto.randomUUID(), text: `Connection error: ${msg}`, fromUser: false, at: Date.now() },
        ]);
      }
    },
    [
      addActivity,
      alarms,
      executeLocalCommand,
      lines,
      notes,
      notifications,
      profile,
      reminders,
      routines,
      settings,
      speakReply,
      updateSettings,
    ],
  );

  const toggleVoiceListening = useCallback(
    (autoSubmit = true) => {
      const win = window as unknown as {
        SpeechRecognition?: SpeechRecognitionCtor;
        webkitSpeechRecognition?: SpeechRecognitionCtor;
      };
      const Ctor = win.SpeechRecognition || win.webkitSpeechRecognition;

      if (!Ctor) {
        setProblem('Speech recognition is not supported in this browser. Type your message below.');
        return;
      }

      if (isDictating || phase === 'Listening') {
        recognitionRef.current?.stop();
        stopAudioMeter();
        setIsDictating(false);
        setPhase('Off');
        return;
      }

      stopSpeaking();
      const rec = new Ctor();
      rec.lang = settings.replyLanguage === 'hindi' ? 'hi-IN' : 'en-IN';
      rec.interimResults = false;
      rec.continuous = false;
      recognitionRef.current = rec;

      rec.onresult = (ev) => {
        const transcript = ev.results?.[0]?.[0]?.transcript?.trim();
        stopAudioMeter();
        setIsDictating(false);
        setPhase('Off');
        if (transcript && autoSubmit) {
          triggerWakeDot();
          sendMessage(transcript);
        }
      };
      rec.onerror = (ev) => {
        stopAudioMeter();
        setIsDictating(false);
        setPhase('Off');
        if (ev.error === 'not-allowed') {
          setMicPermission('denied');
          setProblem('Microphone permission denied. Enable it in browser settings.');
        }
      };
      rec.onend = () => {
        stopAudioMeter();
        setIsDictating(false);
        setPhase((prev) => (prev === 'Listening' ? 'Off' : prev));
      };

      setIsDictating(true);
      setPhase('Listening');
      startAudioMeter();
      try {
        rec.start();
      } catch {
        stopAudioMeter();
        setIsDictating(false);
        setPhase('Off');
      }
    },
    [isDictating, phase, sendMessage, settings.replyLanguage, startAudioMeter, stopAudioMeter, triggerWakeDot],
  );

  const handleOrbTap = useCallback(() => {
    if (phase === 'Speaking' || phase === 'Processing' || localPhase === 'Speaking') {
      stopSpeaking();
      setPhase('Off');
      setLocalPhase('Idle');
      return;
    }
    toggleVoiceListening(true);
  }, [localPhase, phase, toggleVoiceListening]);

  const handleStopLive = useCallback(() => {
    recognitionRef.current?.stop();
    stopAudioMeter();
    stopSpeaking();
    setIsDictating(false);
    setPhase('Off');
    setLocalPhase('Idle');
  }, [stopAudioMeter]);

  const favourites = useMemo(
    () =>
      topHabits(4).map((cmd) => ({
        label: commandLabel(cmd),
        command: cmd,
      })),
    [activity.length],
  );

  return (
    <div className="min-h-dvh bg-[var(--bg-canvas)] text-[var(--text-primary)] transition-colors duration-200">
      <WakeIndicatorDot visible={wakeDotVisible} sizeDp={settings.wakeDotSizeDp} />

      <main>
        {tab === 'Home' && (
          <HomeScreen
            phase={phase}
            localPhase={localPhase}
            level={audioLevel}
            wakeOn={settings.wakeWord && micPermission === 'granted'}
            problem={problem}
            isDark={isDark}
            lines={lines}
            task={task}
            activity={activity}
            favourites={favourites}
            timers={timers}
            alarms={alarms}
            reminders={reminders}
            isDictating={isDictating}
            onOrbTap={handleOrbTap}
            onStopLive={handleStopLive}
            onToggleDictation={() => toggleVoiceListening(true)}
            onSendText={sendMessage}
            onQuick={handleQuickCommand}
            onSeeActivity={() => setTab('Activity')}
            onClearChat={() => {
              stopSpeaking();
              setLines([]);
              setTask(null);
              setPhase('Off');
            }}
            onConfirmTask={(t) => {
              setTask({
                ...t,
                state: 'Completed',
                steps: t.steps.map((s) => ({ ...s, done: true })),
              });
              addActivity(t.title, 'Approved by user', false);
            }}
            onCancelTask={(t) => {
              setTask({
                ...t,
                state: 'Failed',
                detail: 'Cancelled by you.',
                steps: t.steps.map((s) => (s.done === null ? { ...s, done: false } : s)),
              });
              addActivity(t.title, 'Cancelled by user', false);
            }}
            onDeleteTimer={(id) => setTimers((prev) => prev.filter((t) => t.id !== id))}
            onToggleAlarm={(id) => {
              const next = alarms.map((a) => (a.id === id ? { ...a, enabled: !a.enabled } : a));
              setAlarms(next);
              saveAlarms(next);
            }}
            onToggleReminder={(id) => {
              const next = reminders.map((r) => (r.id === id ? { ...r, done: !r.done } : r));
              setReminders(next);
              saveReminders(next);
            }}
          />
        )}

        {tab === 'Automations' && (
          <AutomationsScreen
            routines={routines}
            notifications={notifications}
            onSaveRoutine={(name, steps) => setRoutines(saveCustomRoutine(name, steps))}
            onDeleteRoutine={(name) => setRoutines(deleteCustomRoutine(name))}
            onRunPrompt={(prompt) => {
              setTab('Home');
              sendMessage(prompt);
            }}
            onAddSampleNotification={(item) =>
              setNotifications((prev) => [{ ...item, id: crypto.randomUUID(), atMillis: Date.now() }, ...prev])
            }
          />
        )}

        {tab === 'Activity' && (
          <ActivityScreen entries={activity} onClear={() => setActivity([])} />
        )}

        {tab === 'Settings' && (
          <SettingsScreen
            settings={settings}
            profile={profile}
            notes={notes}
            learnedSummary={learnedText}
            micPermission={micPermission}
            onUpdateSettings={updateSettings}
            onRequestMicPermission={startAudioMeter}
            onOpenProfileModal={() => setShowProfileModal(true)}
            onReplayOnboarding={() => setShowOnboarding(true)}
            onForgetLearned={() => {
              forgetHabits();
              setLearnedText(learnedOverview());
            }}
            onClearNotes={() => {
              saveNotes([]);
              setNotes([]);
            }}
            onDeleteNote={(id) => {
              const next = notes.filter((n) => n.id !== id);
              saveNotes(next);
              setNotes(next);
            }}
            onTriggerWakePreview={triggerWakeDot}
          />
        )}
      </main>

      <LiquidDock activeTab={tab} onSelect={setTab} />

      {showProfileModal && (
        <PersonalProfileModal
          initial={profile}
          onSave={(updated) => setProfile(saveProfile(updated))}
          onClear={() => clearProfile()}
          onClose={() => setShowProfileModal(false)}
        />
      )}

      {showOnboarding && (
        <OnboardingModal
          settings={settings}
          isDark={isDark}
          onUpdateSettings={updateSettings}
          onFinish={() => setShowOnboarding(false)}
        />
      )}
    </div>
  );
}

export default App;
