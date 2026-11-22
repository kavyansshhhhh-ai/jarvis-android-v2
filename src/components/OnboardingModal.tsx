import React, { useState } from 'react';
import { Orb } from './Orb';
import { InfoRow, Section, StatusLabel, SwitchRow } from './GlassPrimitives';
import { AppSettingsState } from '../lib/types';

const STEPS = ['Welcome', 'Local & Cloud', 'Voice', 'Ready'] as const;

export const OnboardingModal: React.FC<{
  settings: AppSettingsState;
  isDark: boolean;
  onUpdateSettings: (patch: Partial<AppSettingsState>) => void;
  onFinish: () => void;
}> = ({ settings, isDark, onUpdateSettings, onFinish }) => {
  const [stepIdx, setStepIdx] = useState(0);
  const step = STEPS[stepIdx];

  const next = () => {
    if (stepIdx < STEPS.length - 1) setStepIdx(stepIdx + 1);
    else onFinish();
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-[var(--bg-canvas)]/95 backdrop-blur-lg p-4 overflow-y-auto"
      role="dialog"
      aria-modal="true"
      aria-label="JARVIS Guided Setup"
    >
      <div className="w-full max-w-lg py-4 space-y-6">
        <div className="flex items-center gap-1.5">
          {STEPS.map((s, i) => (
            <div
              key={s}
              className={`h-1 flex-1 rounded-full transition-colors ${
                i <= stepIdx
                  ? 'bg-gradient-to-r from-[var(--accent-cyan)] to-[var(--accent-violet)]'
                  : 'bg-black/15 dark:bg-white/15'
              }`}
            />
          ))}
        </div>

        {step === 'Welcome' && (
          <div className="space-y-6">
            <div>
              <h1 className="text-3xl font-bold tracking-tight text-[var(--text-primary)]">JARVIS</h1>
              <p className="mt-2 text-base text-[var(--text-muted)]">
                A private voice and AI assistant for English, Hindi, and Hinglish with offline-first local tools and Gemini intelligence.
              </p>
            </div>
            <div className="flex justify-center py-2">
              <Orb phase="Listening" size={170} isDark={isDark} />
            </div>
            <div className="glass-card rounded-3xl p-4 space-y-2">
              <h3 className="text-sm font-semibold text-[var(--text-primary)]">Transparent setup</h3>
              <ul className="space-y-1.5 text-xs leading-relaxed text-[var(--text-muted)]">
                <li>· Simple local commands (time, date, timers, alarms, quick actions) run directly on your device without calling Gemini.</li>
                <li>· Outgoing messages, calls, and deletions always show an Allow card for your explicit confirmation.</li>
                <li>· Payments, UPI PINs, OTPs, and passwords are never automated or saved in memory.</li>
              </ul>
            </div>
            <button
              type="button"
              onClick={next}
              className="w-full min-h-[48px] rounded-full bg-[var(--accent-cyan)] px-6 py-3 text-sm font-bold text-[#001E26] cursor-pointer hover:opacity-90"
            >
              Start Setup
            </button>
          </div>
        )}

        {step === 'Local & Cloud' && (
          <div className="space-y-6">
            <div>
              <h2 className="text-2xl font-bold text-[var(--text-primary)]">What runs where</h2>
              <p className="mt-1.5 text-sm text-[var(--text-muted)]">
                Simple commands stay local. Complex reasoning, weather, web search, and routines use server-side Gemini intelligence.
              </p>
            </div>
            <Section>
              <InfoRow
                title="On this device"
                detail="Time, date, battery, timers, alarms, volume, media controls, and learned shortcuts."
                trailing={<StatusLabel text="Local" tone="cyan" />}
              />
              <div className="glass-divider" />
              <InfoRow
                title="With Gemini"
                detail="Questions in English, Hindi, or Hinglish, live weather, notification triage, memory notes, and routines."
                trailing={<StatusLabel text="Gemini" tone="amber" />}
              />
            </Section>
            <button
              type="button"
              onClick={next}
              className="w-full min-h-[48px] rounded-full bg-[var(--accent-cyan)] px-6 py-3 text-sm font-bold text-[#001E26] cursor-pointer hover:opacity-90"
            >
              Continue
            </button>
          </div>
        )}

        {step === 'Voice' && (
          <div className="space-y-6">
            <div>
              <h2 className="text-2xl font-bold text-[var(--text-primary)]">Hands-free voice</h2>
              <p className="mt-1.5 text-sm text-[var(--text-muted)]">
                Tap the orb or microphone to speak, or enable spoken replies in Hindi and English.
              </p>
            </div>
            <Section>
              <SwitchRow
                title="Speak replies aloud"
                detail="Uses your browser's Hindi and English speech voices."
                checked={settings.speakResponses}
                onChange={(v) => onUpdateSettings({ speakResponses: v })}
              />
              <div className="glass-divider" />
              <SwitchRow
                title="Wake activation dot"
                detail='Shows a subtle top-center Liquid Glass dot when "Hey Jarvis" activates.'
                checked={settings.wakeIndicator}
                onChange={(v) => onUpdateSettings({ wakeIndicator: v })}
              />
            </Section>
            <button
              type="button"
              onClick={next}
              className="w-full min-h-[48px] rounded-full bg-[var(--accent-cyan)] px-6 py-3 text-sm font-bold text-[#001E26] cursor-pointer hover:opacity-90"
            >
              Continue
            </button>
          </div>
        )}

        {step === 'Ready' && (
          <div className="space-y-6">
            <div>
              <h2 className="text-2xl font-bold text-[var(--text-primary)]">You're set</h2>
              <p className="mt-1.5 text-sm text-[var(--text-muted)]">
                Tap the orb or microphone to talk, or type any request in English, Hindi, or Hinglish.
              </p>
            </div>
            <div className="flex justify-center py-4">
              <Orb phase="Off" size={160} isDark={isDark} />
            </div>
            <button
              type="button"
              onClick={onFinish}
              className="w-full min-h-[48px] rounded-full bg-[var(--accent-cyan)] px-6 py-3 text-sm font-bold text-[#001E26] cursor-pointer hover:opacity-90"
            >
              Start
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
