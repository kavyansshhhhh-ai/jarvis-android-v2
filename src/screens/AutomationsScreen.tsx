import React, { useState } from 'react';
import { Play, Plus, Trash2 } from 'lucide-react';
import { GlassCard, GlassDivider, InfoRow, StatusLabel } from '../components/GlassPrimitives';
import { triageNotifications } from '../lib/triage';
import { Routine, SeenNotification } from '../lib/types';

interface CommandExample {
  title: string;
  examples: string;
  samplePrompt: string;
}

const LOCAL_COMMANDS: CommandExample[] = [
  { title: 'Volume', examples: '"Volume 30 percent kar do" · "volume badhao" · "phone mute karo"', samplePrompt: 'Volume 30 percent kar do' },
  { title: 'Music', examples: '"Music pause kar do" · "agla gaana" · "resume"', samplePrompt: 'Music pause kar do' },
  { title: 'Flashlight', examples: '"Flashlight on karo" · "torch band karo"', samplePrompt: 'Flashlight on karo' },
  { title: 'Open apps', examples: '"YouTube kholo" · "open Calendar" · "Camera chalu karo"', samplePrompt: 'YouTube kholo' },
  { title: 'Time, date, battery', examples: '"Kitne baje hai?" · "aaj ki date" · "kitni battery hai"', samplePrompt: 'Kitne baje hai?' },
  { title: 'Timers and alarms', examples: '"5 minute ka timer" · "subah 6:30 ka alarm"', samplePrompt: '5 minute ka timer' },
  { title: 'Stop', examples: '"Bas" · "stop" · "never mind"', samplePrompt: 'Stop' },
];

const TASK_COMMANDS: CommandExample[] = [
  {
    title: 'WhatsApp message',
    examples: '"WhatsApp Rahul ko bolo main 7 baje aaunga". Drafts message and awaits your Allow tap before opening WhatsApp.',
    samplePrompt: 'WhatsApp Rahul ko bolo main 7 baje aaunga',
  },
  {
    title: 'Movie tickets & Payments',
    examples: '"Book 4 tickets for Pushpa tomorrow evening". Hands off to you for seat picking and payment — JARVIS never pays.',
    samplePrompt: 'Book 4 tickets for Pushpa tomorrow evening',
  },
  {
    title: 'Calls',
    examples: '"Call Mom". Prepares the call and waits for your Allow tap.',
    samplePrompt: 'Call Mom',
  },
];

const CLOUD_COMMANDS: CommandExample[] = [
  { title: 'Songs', examples: '"Play Kesariya on YouTube Music"', samplePrompt: 'Play Kesariya on YouTube Music' },
  { title: 'Notifications', examples: '"What did I miss?" — people first, OTP codes hidden, suspicious links flagged', samplePrompt: 'What did I miss?' },
  { title: 'Questions & Weather', examples: '"Weather in Delhi" or general knowledge in English, Hindi, or Hinglish', samplePrompt: "What's the weather in Delhi?" },
  { title: 'Reminders & Memory', examples: '"Remind me to call the bank at 5" · "Remember I parked on level 2"', samplePrompt: 'Remind me to call the bank at 5' },
];

export const AutomationsScreen: React.FC<{
  routines: Routine[];
  notifications: SeenNotification[];
  onSaveRoutine: (name: string, steps: string[]) => void;
  onDeleteRoutine: (name: string) => void;
  onRunPrompt: (prompt: string) => void;
  onAddSampleNotification: (item: Omit<SeenNotification, 'id' | 'atMillis'>) => void;
}> = ({
  routines,
  notifications,
  onSaveRoutine,
  onDeleteRoutine,
  onRunPrompt,
  onAddSampleNotification,
}) => {
  const [newName, setNewName] = useState('');
  const [newSteps, setNewSteps] = useState('');
  const [showNewForm, setShowNewForm] = useState(false);

  const triaged = triageNotifications(notifications);

  const handleCreateRoutine = (e: React.FormEvent) => {
    e.preventDefault();
    const steps = newSteps
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    if (!newName.trim() || steps.length === 0) return;
    onSaveRoutine(newName.trim(), steps);
    setNewName('');
    setNewSteps('');
    setShowNewForm(false);
  };

  return (
    <div className="mx-auto max-w-5xl px-4 sm:px-6 pt-5 pb-32 space-y-6">
      <header>
        <h1 className="text-2xl font-bold tracking-tight text-[var(--text-primary)]">Automations</h1>
        <p className="mt-1 text-sm text-[var(--text-muted)]">
          Tap any routine or command example to run it in English, Hindi, or Hinglish.
        </p>
      </header>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5 items-start">
        {/* Routines */}
        <GlassCard className="space-y-2">
          <div className="flex items-center justify-between pt-1">
            <h2 className="text-lg font-semibold text-[var(--text-primary)]">Routines</h2>
            <button
              type="button"
              onClick={() => setShowNewForm((v) => !v)}
              className="inline-flex items-center gap-1 text-xs font-semibold text-[var(--accent-cyan)] hover:underline cursor-pointer"
            >
              <Plus className="h-3.5 w-3.5" />
              {showNewForm ? 'Close' : 'New routine'}
            </button>
          </div>
          <p className="text-xs text-[var(--text-muted)]">
            Say a routine's name to run its steps, or tap Run below.
          </p>

          {showNewForm && (
            <form onSubmit={handleCreateRoutine} className="space-y-2.5 py-2 border-y border-[var(--divider-glass)]">
              <input
                type="text"
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                placeholder="Routine name (e.g. Good night)"
                className="w-full rounded-xl border border-[var(--border-glass)] bg-black/5 dark:bg-white/5 px-3 py-2 text-sm text-[var(--text-primary)] focus:border-[var(--accent-cyan)] focus:outline-none"
              />
              <input
                type="text"
                value={newSteps}
                onChange={(e) => setNewSteps(e.target.value)}
                placeholder="Comma-separated steps (e.g. Alarm at 6:30, Check reminders)"
                className="w-full rounded-xl border border-[var(--border-glass)] bg-black/5 dark:bg-white/5 px-3 py-2 text-sm text-[var(--text-primary)] focus:border-[var(--accent-cyan)] focus:outline-none"
              />
              <button
                type="submit"
                className="min-h-[38px] rounded-full bg-[var(--accent-cyan)] px-4 py-1.5 text-xs font-bold text-[#001E26] cursor-pointer"
              >
                Save Routine
              </button>
            </form>
          )}

          <div>
            {routines.map((r, idx) => (
              <React.Fragment key={r.name}>
                <InfoRow
                  title={r.name}
                  detail={r.steps.join(' · ')}
                  trailing={
                    <div className="flex items-center gap-1">
                      <button
                        type="button"
                        aria-label={`Run ${r.name}`}
                        onClick={() => onRunPrompt(`Run routine ${r.name}`)}
                        className="inline-flex min-h-[36px] items-center gap-1 rounded-full bg-[var(--accent-cyan)]/15 px-3 py-1.5 text-xs font-semibold text-[var(--accent-cyan)] hover:bg-[var(--accent-cyan)]/25 cursor-pointer"
                      >
                        <Play className="h-3 w-3" />
                        Run
                      </button>
                      {!r.builtIn && (
                        <button
                          type="button"
                          aria-label={`Delete ${r.name}`}
                          onClick={() => onDeleteRoutine(r.name)}
                          className="p-2 text-[var(--text-muted)] hover:text-[var(--accent-error)] cursor-pointer"
                        >
                          <Trash2 className="h-4 w-4" />
                        </button>
                      )}
                    </div>
                  }
                />
                {idx < routines.length - 1 && <GlassDivider />}
              </React.Fragment>
            ))}
          </div>
        </GlassCard>

        {/* Notification Triage Inspector */}
        <GlassCard className="space-y-2.5">
          <div className="flex items-center justify-between pt-1">
            <h2 className="text-lg font-semibold text-[var(--text-primary)]">Message Triage</h2>
            <button
              type="button"
              onClick={() => onRunPrompt('What did I miss?')}
              className="text-xs font-semibold text-[var(--accent-cyan)] hover:underline cursor-pointer"
            >
              Summarize with JARVIS
            </button>
          </div>
          <p className="text-xs text-[var(--text-muted)]">
            People and missed calls come first. OTP digits are masked and unknown links are flagged automatically.
          </p>
          <div className="rounded-2xl bg-black/5 dark:bg-white/5 p-3 text-xs leading-relaxed text-[var(--text-primary)] whitespace-pre-line font-mono">
            {triaged.summaryText}
          </div>
          <div className="flex flex-wrap gap-2 pt-1">
            <button
              type="button"
              onClick={() =>
                onAddSampleNotification({
                  packageName: 'com.whatsapp',
                  app: 'WhatsApp',
                  title: 'Ananya',
                  text: 'Are we still meeting at 6pm?',
                  category: 'msg',
                })
              }
              className="min-h-[36px] rounded-full border border-[var(--border-glass)] px-3 py-1.5 text-xs font-medium text-[var(--text-primary)] hover:border-cyan-400/50 cursor-pointer"
            >
              + Simulate chat
            </button>
            <button
              type="button"
              onClick={() =>
                onAddSampleNotification({
                  packageName: 'com.google.android.apps.messaging',
                  app: 'Messages',
                  title: 'HDFC Bank',
                  text: 'Your OTP is 492817 for login.',
                  category: 'msg',
                })
              }
              className="min-h-[36px] rounded-full border border-[var(--border-glass)] px-3 py-1.5 text-xs font-medium text-[var(--text-primary)] hover:border-cyan-400/50 cursor-pointer"
            >
              + Simulate OTP
            </button>
            <button
              type="button"
              onClick={() =>
                onAddSampleNotification({
                  packageName: 'com.google.android.apps.messaging',
                  app: 'Messages',
                  title: '+91 98110 44210',
                  text: 'Claim bonus now at https://bit.ly/fake-claim',
                  category: 'msg',
                })
              }
              className="min-h-[36px] rounded-full border border-[var(--border-glass)] px-3 py-1.5 text-xs font-medium text-[var(--text-primary)] hover:border-cyan-400/50 cursor-pointer"
            >
              + Simulate suspicious link
            </button>
          </div>
        </GlassCard>

        {/* Command Reference Groups */}
        <CommandGroup
          title="On this device"
          detail="Runs locally without internet and doesn't call Gemini."
          badge="Local"
          tone="cyan"
          commands={LOCAL_COMMANDS}
          onRunPrompt={onRunPrompt}
        />

        <CommandGroup
          title="Tasks with your OK"
          detail="Gemini plans them; you approve anything that sends, calls, or pays."
          badge="Needs you"
          tone="amber"
          commands={TASK_COMMANDS}
          onRunPrompt={onRunPrompt}
        />

        <div className="lg:col-span-2">
          <CommandGroup
            title="With Gemini"
            detail="Uses Gemini intelligence and live search/weather."
            badge="Gemini"
            tone="amber"
            commands={CLOUD_COMMANDS}
            onRunPrompt={onRunPrompt}
          />
        </div>
      </div>
    </div>
  );
};

const CommandGroup: React.FC<{
  title: string;
  detail: string;
  badge: string;
  tone: 'cyan' | 'amber';
  commands: CommandExample[];
  onRunPrompt: (prompt: string) => void;
}> = ({ title, detail, badge, tone, commands, onRunPrompt }) => (
  <GlassCard className="space-y-1">
    <div className="flex items-center justify-between pt-1">
      <h2 className="text-lg font-semibold text-[var(--text-primary)]">{title}</h2>
      <StatusLabel text={badge} tone={tone} />
    </div>
    <p className="text-xs text-[var(--text-muted)] pb-2">{detail}</p>
    {commands.map((cmd, idx) => (
      <React.Fragment key={cmd.title}>
        <InfoRow
          title={cmd.title}
          detail={cmd.examples}
          onClick={() => onRunPrompt(cmd.samplePrompt)}
          trailing={<span className="text-xs font-semibold text-[var(--accent-cyan)]">Try</span>}
        />
        {idx < commands.length - 1 && <GlassDivider />}
      </React.Fragment>
    ))}
  </GlassCard>
);
