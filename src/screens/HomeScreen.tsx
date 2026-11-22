import React, { useEffect, useRef, useState } from 'react';
import { Bell, Clock, ExternalLink, Mic, MicOff, Send, Square, Trash2 } from 'lucide-react';
import { GlassCard, GlassDivider } from '../components/GlassPrimitives';
import { Orb } from '../components/Orb';
import { TaskCard } from '../components/TaskCard';
import { LocalCommand } from '../lib/localRouter';
import {
  ActivityEntry,
  AlarmItem,
  ChatLine,
  LocalPhase,
  Phase,
  ReminderItem,
  TaskItem,
  TimerItem,
} from '../lib/types';

function computeStatus(phase: Phase, localPhase: LocalPhase, wakeOn: boolean, problem: string | null) {
  if (localPhase === 'Listening') {
    return {
      title: 'Listening',
      hint: 'Say a command. Simple ones run locally on this device.',
      badge: 'Local',
      orb: 'Listening' as Phase,
      live: true,
    };
  }
  if (localPhase === 'Doing') {
    return {
      title: 'Working',
      hint: 'Executing locally…',
      badge: 'Local',
      orb: 'Connecting' as Phase,
      live: true,
    };
  }
  if (localPhase === 'Speaking') {
    return {
      title: 'Speaking',
      hint: 'Answered locally. Tap the orb to interrupt.',
      badge: 'Local',
      orb: 'Speaking' as Phase,
      live: true,
    };
  }
  if (phase === 'Connecting') {
    return {
      title: 'Thinking',
      hint: 'Connecting to Gemini intelligence…',
      badge: 'Gemini',
      orb: 'Connecting' as Phase,
      live: true,
    };
  }
  if (phase === 'Processing') {
    return {
      title: 'Preparing voice',
      hint: 'Applying your selected voice…',
      badge: 'JARVIS',
      orb: 'Processing' as Phase,
      live: true,
    };
  }
  if (phase === 'Listening') {
    return {
      title: 'Listening',
      hint: 'Speak anytime in English, Hindi, or Hinglish.',
      badge: 'Voice',
      orb: 'Listening' as Phase,
      live: true,
    };
  }
  if (phase === 'Speaking') {
    return {
      title: 'Speaking',
      hint: 'Tap the orb to cut in.',
      badge: 'JARVIS',
      orb: 'Speaking' as Phase,
      live: true,
    };
  }
  return {
    title: 'Standby',
    hint: problem || (wakeOn ? 'Say "Hey Jarvis", or tap the orb to talk.' : 'Tap the orb or type below to talk.'),
    badge: wakeOn ? 'Wake word on' : 'Wake word off',
    orb: 'Off' as Phase,
    live: false,
  };
}

function formatRemaining(endsAt: number, now: number): string {
  const sec = Math.max(0, Math.ceil((endsAt - now) / 1000));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

export const HomeScreen: React.FC<{
  phase: Phase;
  localPhase: LocalPhase;
  level: number;
  wakeOn: boolean;
  problem: string | null;
  isDark: boolean;
  lines: ChatLine[];
  task: TaskItem | null;
  activity: ActivityEntry[];
  favourites: Array<{ label: string; command: LocalCommand }>;
  timers: TimerItem[];
  alarms: AlarmItem[];
  reminders: ReminderItem[];
  isDictating: boolean;
  onOrbTap: () => void;
  onStopLive: () => void;
  onToggleDictation: () => void;
  onSendText: (text: string) => void;
  onQuick: (cmd: LocalCommand) => string;
  onSeeActivity: () => void;
  onClearChat: () => void;
  onConfirmTask: (task: TaskItem) => void;
  onCancelTask: (task: TaskItem) => void;
  onDeleteTimer: (id: string) => void;
  onToggleAlarm: (id: string) => void;
  onToggleReminder: (id: string) => void;
}> = ({
  phase,
  localPhase,
  level,
  wakeOn,
  problem,
  isDark,
  lines,
  task,
  activity,
  favourites,
  timers,
  alarms,
  reminders,
  isDictating,
  onOrbTap,
  onStopLive,
  onToggleDictation,
  onSendText,
  onQuick,
  onSeeActivity,
  onClearChat,
  onConfirmTask,
  onCancelTask,
  onDeleteTimer,
  onToggleAlarm,
  onToggleReminder,
}) => {
  const [inputText, setInputText] = useState('');
  const [quickAnswer, setQuickAnswer] = useState<string | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const chatEndRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (timers.every((t) => t.done)) return;
    const id = window.setInterval(() => setNowMs(Date.now()), 500);
    return () => window.clearInterval(id);
  }, [timers]);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [lines.length]);

  const now = computeStatus(phase, localPhase, wakeOn, problem);
  const hasProblem = Boolean(problem) && !now.live;
  const inConversation = phase !== 'Off' || lines.length > 0;

  const defaultQuick: Array<{ label: string; command: LocalCommand }> = [
    { label: 'Time', command: { type: 'Time' } },
    { label: 'Date', command: { type: 'Date' } },
    { label: 'Battery', command: { type: 'Battery' } },
    { label: '5m Timer', command: { type: 'Timer', seconds: 300 } },
  ];

  const mergedQuick = [
    ...favourites,
    ...defaultQuick.filter((d) => !favourites.some((f) => f.label.toLowerCase() === d.label.toLowerCase())),
  ].slice(0, 4);

  const handleSubmit = (e?: React.FormEvent) => {
    e?.preventDefault();
    const trimmed = inputText.trim();
    if (!trimmed) return;
    setInputText('');
    onSendText(trimmed);
  };

  const activeTimers = timers.filter((t) => !t.done || Date.now() - t.endsAt < 60_000);
  const activeAlarms = alarms.filter((a) => a.enabled);
  const openReminders = reminders.filter((r) => !r.done);

  return (
    <div className="mx-auto flex h-full min-h-[calc(100dvh-16px)] max-w-5xl flex-col px-4 sm:px-6 pt-3 pb-36">
      {/* Top Bar */}
      <header className="flex items-center justify-between gap-4 py-2">
        <div className="flex items-center gap-2.5">
          <span className="text-xl font-bold tracking-tight text-[var(--text-primary)] whitespace-nowrap">
            JARVIS
          </span>
          {now.live && (
            <span
              aria-hidden="true"
              className="h-2 w-2 rounded-full bg-[var(--accent-cyan)] shadow-[0_0_8px_var(--accent-cyan)] animate-pulse"
            />
          )}
        </div>
        <div className="flex items-center gap-4 text-xs font-medium">
          {lines.length > 0 && (
            <button
              type="button"
              onClick={onClearChat}
              className="text-[var(--text-muted)] hover:text-[var(--text-primary)] cursor-pointer transition-colors"
            >
              New chat
            </button>
          )}
          <span className={now.live ? 'text-[var(--accent-cyan)] font-semibold' : 'text-[var(--text-muted)]'}>
            {now.badge}
          </span>
        </div>
      </header>

      {/* Main Responsive Content */}
      <div className="flex-1 grid grid-cols-1 lg:grid-cols-12 gap-6 items-start pt-2">
        {/* Left / Center Presence Column */}
        <div className={`${inConversation ? 'lg:col-span-5' : 'lg:col-span-6'} flex flex-col items-center lg:items-start`}>
          <div className="flex w-full justify-center py-2">
            <Orb
              phase={now.orb}
              level={level}
              size={inConversation ? 148 : 204}
              isDark={isDark}
              label={`JARVIS: ${now.title}`}
              onClick={onOrbTap}
            />
          </div>

          <div className="w-full text-center lg:text-left mt-1">
            <h1 className="text-3xl font-bold tracking-tight text-[var(--text-primary)]">{now.title}</h1>
            <p className={`mt-1 text-sm leading-relaxed ${hasProblem ? 'text-[var(--accent-error)]' : 'text-[var(--text-muted)]'}`}>
              {now.hint}
            </p>
          </div>

          {task && (
            <div className="w-full mt-4">
              <TaskCard task={task} onConfirm={onConfirmTask} onCancel={onCancelTask} />
            </div>
          )}

          {/* Quick Actions */}
          <div className="w-full mt-5">
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
              {mergedQuick.map((item) => (
                <button
                  key={item.label}
                  type="button"
                  onClick={() => setQuickAnswer(onQuick(item.command))}
                  className="glass-card min-h-[44px] rounded-full px-3 py-2.5 text-xs font-semibold text-[var(--text-primary)] hover:border-cyan-400/50 active:scale-[0.97] transition-all cursor-pointer truncate"
                >
                  {item.label}
                </button>
              ))}
            </div>
            {quickAnswer && (
              <p className="mt-2.5 px-1 text-xs font-medium text-[var(--accent-cyan)]">{quickAnswer}</p>
            )}
          </div>

          {/* Live Timers / Alarms / Reminders */}
          {(activeTimers.length > 0 || activeAlarms.length > 0 || openReminders.length > 0) && (
            <div className="w-full mt-4">
              <GlassCard className="space-y-2">
                {activeTimers.map((t) => (
                  <div key={t.id} className="flex items-center justify-between gap-2 py-1 text-xs">
                    <div className="flex items-center gap-2 text-[var(--text-primary)]">
                      <Clock className="h-3.5 w-3.5 text-[var(--accent-cyan)]" />
                      <span className="font-medium">{t.label}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="font-mono tabular-nums font-semibold text-[var(--accent-cyan)]">
                        {t.done ? 'Time up' : formatRemaining(t.endsAt, nowMs)}
                      </span>
                      <button
                        type="button"
                        aria-label={`Remove timer ${t.label}`}
                        onClick={() => onDeleteTimer(t.id)}
                        className="p-1 text-[var(--text-muted)] hover:text-[var(--accent-error)] cursor-pointer"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  </div>
                ))}
                {activeAlarms.map((a) => (
                  <div key={a.id} className="flex items-center justify-between gap-2 py-1 text-xs">
                    <div className="flex items-center gap-2 text-[var(--text-primary)]">
                      <Bell className="h-3.5 w-3.5 text-[var(--accent-amber)]" />
                      <span className="font-medium">{a.label}</span>
                    </div>
                    <button
                      type="button"
                      onClick={() => onToggleAlarm(a.id)}
                      className="font-mono tabular-nums font-semibold text-[var(--accent-amber)] cursor-pointer"
                    >
                      {String(a.hour).padStart(2, '0')}:{String(a.minute).padStart(2, '0')} · Turn off
                    </button>
                  </div>
                ))}
                {openReminders.map((r) => (
                  <div key={r.id} className="flex items-center justify-between gap-2 py-1 text-xs">
                    <span className="text-[var(--text-primary)] truncate">{r.text}</span>
                    <button
                      type="button"
                      onClick={() => onToggleReminder(r.id)}
                      className="text-[var(--accent-cyan)] font-semibold shrink-0 cursor-pointer"
                    >
                      Done
                    </button>
                  </div>
                ))}
              </GlassCard>
            </div>
          )}
        </div>

        {/* Right Column: Conversation Stream OR Recent Activity + Suggestions */}
        <div className={`${inConversation ? 'lg:col-span-7' : 'lg:col-span-6'} w-full`}>
          {inConversation ? (
            <div className="space-y-3 max-h-[54vh] lg:max-h-[65vh] overflow-y-auto pr-1">
              {lines.map((line) => (
                <div
                  key={line.id}
                  className={`flex flex-col ${line.fromUser ? 'items-end' : 'items-start'}`}
                >
                  <div
                    style={
                      line.fromUser
                        ? { background: 'var(--user-bubble-gradient)' }
                        : undefined
                    }
                    className={`max-w-[88%] px-4 py-2.5 text-sm leading-relaxed ${
                      line.fromUser
                        ? 'rounded-[20px] rounded-br-[6px] border border-white/30 text-white font-medium'
                        : 'glass-card rounded-[20px] rounded-bl-[6px] text-[var(--text-primary)]'
                    }`}
                  >
                    <p className="whitespace-pre-wrap">{line.text}</p>
                    {line.sources && line.sources.length > 0 && (
                      <div className="mt-2 pt-2 border-t border-[var(--divider-glass)] flex flex-wrap gap-2">
                        {line.sources.slice(0, 3).map((src) => (
                          <a
                            key={src.uri}
                            href={src.uri}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-flex items-center gap-1 text-[11px] font-medium text-[var(--accent-cyan)] hover:underline"
                          >
                            {src.title}
                            <ExternalLink className="h-3 w-3" />
                          </a>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              ))}
              <div ref={chatEndRef} />
            </div>
          ) : (
            <div className="space-y-4">
              <div>
                <div className="flex items-center justify-between px-1 mb-2">
                  <h2 className="text-[13.5px] font-medium text-[var(--text-muted)]">Recent activity</h2>
                  <button
                    type="button"
                    onClick={onSeeActivity}
                    className="text-xs font-semibold text-[var(--accent-cyan)] hover:underline cursor-pointer"
                  >
                    See all
                  </button>
                </div>
                {activity.length === 0 ? (
                  <GlassCard>
                    <p className="py-2 text-xs text-[var(--text-muted)]">
                      Nothing yet. What you ask shows up here.
                    </p>
                  </GlassCard>
                ) : (
                  <GlassCard>
                    {activity.slice(0, 3).map((entry, idx) => (
                      <React.Fragment key={entry.id}>
                        <div className="flex items-center justify-between gap-3 py-2.5">
                          <div className="min-w-0">
                            <div className="text-sm font-semibold text-[var(--text-primary)] truncate">
                              {entry.what}
                            </div>
                            <div className="text-xs text-[var(--text-muted)] truncate">{entry.result}</div>
                          </div>
                          <div className="text-right shrink-0">
                            <div className={`text-xs font-semibold ${entry.local ? 'text-[var(--accent-cyan)]' : 'text-[var(--accent-amber)]'}`}>
                              {entry.local ? 'Local' : 'Gemini'}
                            </div>
                            <div className="text-[11px] text-[var(--text-muted)] tabular-nums">
                              {new Date(entry.at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}
                            </div>
                          </div>
                        </div>
                        {idx < Math.min(3, activity.length) - 1 && <GlassDivider />}
                      </React.Fragment>
                    ))}
                  </GlassCard>
                )}
              </div>

              <div>
                <h2 className="text-[13.5px] font-medium text-[var(--text-muted)] px-1 mb-2">Try asking</h2>
                <GlassCard className="space-y-1">
                  {[
                    'Good morning',
                    "What's the weather in Mumbai?",
                    'WhatsApp Rahul ko bolo main 7 baje aaunga',
                    'Remember I parked on level 2',
                    'What did I miss?',
                  ].map((prompt, idx, arr) => (
                    <React.Fragment key={prompt}>
                      <button
                        type="button"
                        onClick={() => onSendText(prompt)}
                        className="w-full py-2.5 text-left text-sm text-[var(--text-primary)] hover:text-[var(--accent-cyan)] transition-colors cursor-pointer"
                      >
                        "{prompt}"
                      </button>
                      {idx < arr.length - 1 && <GlassDivider />}
                    </React.Fragment>
                  ))}
                </GlassCard>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Permanent Chat Input Bar anchored above Floating Liquid Dock */}
      <form
        onSubmit={handleSubmit}
        className="fixed bottom-[76px] left-1/2 -translate-x-1/2 z-30 w-[calc(100%-24px)] max-w-2xl"
      >
        <div className="glass-card flex items-center gap-2 rounded-[28px] px-2.5 py-1.5 shadow-[0_12px_32px_rgba(0,0,0,0.45)]">
          <button
            type="button"
            onClick={onToggleDictation}
            aria-label={isDictating ? 'Stop speech recognition' : 'Speech to text'}
            className={`flex h-[42px] w-[42px] shrink-0 items-center justify-center rounded-full border transition-colors cursor-pointer ${
              isDictating
                ? 'border-[var(--accent-error)] bg-red-500/20 text-[var(--accent-error)]'
                : 'border-[var(--border-glass)] bg-black/5 dark:bg-white/10 text-[var(--accent-cyan)]'
            }`}
          >
            {isDictating ? <MicOff className="h-[18px] w-[18px]" /> : <Mic className="h-[18px] w-[18px]" />}
          </button>

          <input
            type="text"
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            placeholder={isDictating ? 'Listening to speech…' : 'Ask JARVIS in English, Hindi, or Hinglish…'}
            className="min-w-0 flex-1 bg-transparent px-2 py-2 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none"
          />

          {now.live ? (
            <button
              type="button"
              onClick={onStopLive}
              aria-label="Stop"
              className="flex h-[42px] w-[42px] shrink-0 items-center justify-center rounded-full bg-[var(--accent-error)] text-white cursor-pointer hover:opacity-90"
            >
              <Square className="h-4 w-4 fill-current" />
            </button>
          ) : (
            <button
              type="submit"
              disabled={!inputText.trim()}
              aria-label="Send message"
              className={`flex h-[42px] w-[42px] shrink-0 items-center justify-center rounded-full transition-opacity cursor-pointer ${
                inputText.trim()
                  ? 'bg-[var(--accent-cyan)] text-[#001E26] hover:opacity-90'
                  : 'bg-black/5 dark:bg-white/5 text-[var(--text-muted)] cursor-not-allowed'
              }`}
            >
              <Send className="h-[17px] w-[17px]" />
            </button>
          )}
        </div>
      </form>
    </div>
  );
};
