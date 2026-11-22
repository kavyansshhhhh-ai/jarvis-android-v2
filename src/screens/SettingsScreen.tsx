import React, { useEffect, useState } from 'react';
import {
  ArrowLeft,
  Brain,
  Check,
  ChevronRight,
  Info,
  Lock,
  Mic,
  Play,
  Shield,
  Sparkles,
  Square,
  Trash2,
  User,
  Volume2,
} from 'lucide-react';
import { GlassCard, GlassDivider, InfoRow, Section, StatusLabel, SwitchRow } from '../components/GlassPrimitives';
import { isProfileEmpty } from '../lib/personalProfile';
import { buildVoiceCatalog, HindiVoiceCatalog, previewSampleText, speakText, stopSpeaking, TtsVoiceInfo } from '../lib/speech';
import {
  AppearanceMode,
  AppSettingsState,
  AUTOMATION_CATEGORIES,
  Note,
  PersonalProfile,
  PreviewLanguage,
  ReplyLanguage,
} from '../lib/types';

type SettingsFolder = 'Main' | 'Permissions' | 'JarvisVoice';

const APPEARANCE_MODES: AppearanceMode[] = ['Dark', 'Light', 'System'];
const REPLY_LANGUAGES: Array<{ id: ReplyLanguage; label: string }> = [
  { id: 'auto', label: 'Auto' },
  { id: 'english', label: 'English' },
  { id: 'hindi', label: 'Hindi' },
  { id: 'hinglish', label: 'Hinglish' },
];
const DURATION_PRESETS_MS = [1600, 2800, 4200];

export const SettingsScreen: React.FC<{
  settings: AppSettingsState;
  profile: PersonalProfile;
  notes: Note[];
  learnedSummary: string;
  micPermission: 'granted' | 'denied' | 'prompt' | 'unsupported';
  onUpdateSettings: (patch: Partial<AppSettingsState>) => void;
  onRequestMicPermission: () => void;
  onOpenProfileModal: () => void;
  onReplayOnboarding: () => void;
  onForgetLearned: () => void;
  onClearNotes: () => void;
  onDeleteNote: (id: string) => void;
  onTriggerWakePreview: () => void;
}> = ({
  settings,
  profile,
  notes,
  learnedSummary,
  micPermission,
  onUpdateSettings,
  onRequestMicPermission,
  onOpenProfileModal,
  onReplayOnboarding,
  onForgetLearned,
  onClearNotes,
  onDeleteNote,
  onTriggerWakePreview,
}) => {
  const [folder, setFolder] = useState<SettingsFolder>('Main');
  const [catalog, setCatalog] = useState<HindiVoiceCatalog>(() => buildVoiceCatalog());

  useEffect(() => {
    const refresh = () => setCatalog(buildVoiceCatalog());
    refresh();
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.addEventListener?.('voiceschanged', refresh);
      return () => window.speechSynthesis.removeEventListener?.('voiceschanged', refresh);
    }
  }, []);

  if (folder === 'Permissions') {
    return (
      <PermissionsSubScreen
        settings={settings}
        micPermission={micPermission}
        onRequestMicPermission={onRequestMicPermission}
        onUpdateSettings={onUpdateSettings}
        onBack={() => setFolder('Main')}
      />
    );
  }

  if (folder === 'JarvisVoice') {
    return (
      <JarvisVoiceSubScreen
        settings={settings}
        catalog={catalog}
        onUpdateSettings={onUpdateSettings}
        onBack={() => {
          stopSpeaking();
          setFolder('Main');
        }}
      />
    );
  }

  const allVoices = [...catalog.femaleVoices, ...catalog.maleVoices];
  const activeVoice =
    allVoices.find((v) => v.id === settings.ttsVoiceId) ??
    (settings.ttsGenderFilter === 'MALE' ? catalog.maleVoices[0] : catalog.femaleVoices[0]) ??
    allVoices[0];

  const enabledAutomationsCount = Object.values(settings.automations).filter(Boolean).length;
  const totalAutomationsCount = AUTOMATION_CATEGORIES.length;

  const songSummary = profile.favoriteSongs.length
    ? profile.favoriteSongs.join(', ')
    : 'No favourite songs saved';
  const musicAppText = profile.preferredMusicApp || 'Default (YouTube Music / Spotify)';
  const identityLine =
    [profile.preferredName, profile.addressAs ? `call you "${profile.addressAs}"` : '']
      .filter(Boolean)
      .join(' · ') || 'Set preferred name, music taste, and response style';

  return (
    <div className="mx-auto max-w-5xl px-4 sm:px-6 pt-5 pb-32 space-y-6">
      <header>
        <h1 className="text-2xl font-bold tracking-tight text-[var(--text-primary)]">Settings</h1>
        <p className="mt-1 text-sm text-[var(--text-muted)]">
          Customize intelligence, audio response, and privacy boundaries.
        </p>
      </header>

      {/* Top Identity Hero */}
      <GlassCard className="p-4">
        <div className="flex items-center gap-4">
          <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-[var(--accent-cyan)] to-[var(--accent-violet)] text-[#001A22] shadow-lg">
            <Mic className="h-5 w-5" />
          </div>
          <div className="min-w-0 flex-1">
            <div className="text-lg font-bold text-[var(--text-primary)]">JARVIS</div>
            <div className="text-xs text-[var(--text-muted)]">
              {settings.wakeWord && micPermission === 'granted'
                ? 'Hands-free Wake Word · Local & Gemini'
                : 'Tap to Talk · Local & Gemini'}
            </div>
          </div>
          <StatusLabel
            text={settings.wakeWord && micPermission === 'granted' ? 'Active' : 'Standby'}
            tone={settings.wakeWord && micPermission === 'granted' ? 'cyan' : 'muted'}
          />
        </div>
      </GlassCard>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
        <div className="space-y-6">
          {/* Appearance */}
          <Section title="Appearance">
            <p className="pt-2 pb-3 text-xs text-[var(--text-muted)]">
              Choose how JARVIS looks across chat, settings, and dialogs.
            </p>
            <div className="grid grid-cols-3 gap-2 pb-2">
              {APPEARANCE_MODES.map((mode) => {
                const selected = settings.appearanceMode === mode;
                return (
                  <button
                    key={mode}
                    type="button"
                    onClick={() => onUpdateSettings({ appearanceMode: mode })}
                    className={`min-h-[42px] rounded-2xl border text-sm font-semibold transition-colors cursor-pointer ${
                      selected
                        ? 'border-[var(--accent-cyan)] bg-[var(--accent-cyan)]/15 text-[var(--accent-cyan)]'
                        : 'border-[var(--border-glass)] bg-black/5 dark:bg-white/5 text-[var(--text-primary)]'
                    }`}
                  >
                    {mode}
                  </button>
                );
              })}
            </div>
          </Section>

          {/* Permissions Folder */}
          <Section title="Permissions">
            <InfoRow
              title="Permissions & Automations"
              detail={`${enabledAutomationsCount} of ${totalAutomationsCount} automation categories on · Microphone (${micPermission})`}
              icon={<Lock className="h-4 w-4" />}
              onClick={() => setFolder('Permissions')}
              trailing={<ChevronRight className="h-5 w-5 text-[var(--text-muted)]" />}
            />
          </Section>

          {/* JARVIS VOICE Folder */}
          <Section title="JARVIS VOICE">
            <InfoRow
              title="JARVIS VOICE"
              detail={`Female Voices & Male Voices · ${activeVoice?.displayName || 'System speech voice'}`}
              icon={<Volume2 className="h-4 w-4" />}
              onClick={() => setFolder('JarvisVoice')}
              trailing={<ChevronRight className="h-5 w-5 text-[var(--text-muted)]" />}
            />
          </Section>

          {/* Personal Profile */}
          <Section title="Personal Profile">
            <InfoRow
              title="Your preferences"
              detail={`${identityLine}\nSongs: ${songSummary} · Music app: ${musicAppText}`}
              icon={<User className="h-4 w-4" />}
              trailing={
                <button
                  type="button"
                  onClick={onOpenProfileModal}
                  className="min-h-[38px] px-3 text-sm font-semibold text-[var(--accent-cyan)] cursor-pointer hover:underline"
                >
                  {isProfileEmpty(profile) ? 'Set up' : 'Edit'}
                </button>
              }
            />
          </Section>

          {/* Reply Language & Intelligence */}
          <Section title="Gemini Intelligence">
            <div className="py-3 space-y-2.5">
              <div className="text-[15.5px] font-medium text-[var(--text-primary)]">Reply language</div>
              <div className="grid grid-cols-4 gap-1.5">
                {REPLY_LANGUAGES.map((lang) => {
                  const selected = settings.replyLanguage === lang.id;
                  return (
                    <button
                      key={lang.id}
                      type="button"
                      onClick={() => onUpdateSettings({ replyLanguage: lang.id })}
                      className={`min-h-[38px] rounded-xl border text-xs font-semibold cursor-pointer ${
                        selected
                          ? 'border-[var(--accent-cyan)] bg-[var(--accent-cyan)]/15 text-[var(--accent-cyan)]'
                          : 'border-[var(--border-glass)] bg-black/5 dark:bg-white/5 text-[var(--text-muted)]'
                      }`}
                    >
                      {lang.label}
                    </button>
                  );
                })}
              </div>
            </div>
            <GlassDivider />
            <InfoRow
              title="Server-side Gemini connection"
              detail="Powered securely on the server with live search, weather, and tool calling."
              icon={<Sparkles className="h-4 w-4" />}
              trailing={<StatusLabel text="Ready" tone="cyan" />}
            />
            <GlassDivider />
            <InfoRow
              title="Guided setup tour"
              detail="Review how local commands, confirmations, and voice work."
              trailing={
                <button
                  type="button"
                  onClick={onReplayOnboarding}
                  className="min-h-[38px] px-3 text-sm font-semibold text-[var(--accent-cyan)] cursor-pointer hover:underline"
                >
                  Open
                </button>
              }
            />
          </Section>
        </div>

        <div className="space-y-6">
          {/* Wake Word & Activation Dot */}
          <Section title="Wake Word & Feedback">
            <SwitchRow
              title="Talk over JARVIS"
              detail="Tap the orb or speak to cut off a spoken reply immediately."
              checked={settings.talkOver}
              icon={<Mic className="h-4 w-4" />}
              onChange={(v) => onUpdateSettings({ talkOver: v })}
            />
            <GlassDivider />
            <SwitchRow
              title="Hands-free wake listener"
              detail='Listen in browser session for "Hey Jarvis" or "OK Jarvis" when microphone is granted.'
              checked={settings.wakeWord}
              onChange={(v) => {
                if (v && micPermission !== 'granted') {
                  onRequestMicPermission();
                }
                onUpdateSettings({ wakeWord: v });
              }}
            />
            <GlassDivider />
            <SwitchRow
              title="Wake activation dot"
              detail={`Shows a ${settings.wakeDotSizeDp}dp top-center Liquid Glass dot for ${(settings.wakeDotDurationMs / 1000).toFixed(1)}s when triggered.`}
              checked={settings.wakeIndicator}
              onChange={(v) => onUpdateSettings({ wakeIndicator: v })}
            />
            {settings.wakeIndicator && (
              <>
                <GlassDivider />
                <div className="py-3 space-y-3">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-semibold text-[var(--text-primary)]">
                      Wake Dot Size: <span className="tabular-nums">{settings.wakeDotSizeDp}dp</span>
                    </span>
                    <button
                      type="button"
                      onClick={onTriggerWakePreview}
                      className="text-xs font-semibold text-[var(--accent-cyan)] cursor-pointer hover:underline"
                    >
                      Test dot
                    </button>
                  </div>
                  <div className="relative flex h-24 w-full items-center justify-center rounded-2xl border border-[var(--border-glass)] bg-black">
                    <div
                      style={{
                        width: settings.wakeDotSizeDp * 2,
                        height: settings.wakeDotSizeDp * 2,
                      }}
                      className="rounded-full bg-cyan-400/20 blur-md flex items-center justify-center"
                    />
                    <div
                      style={{ width: settings.wakeDotSizeDp, height: settings.wakeDotSizeDp }}
                      className="absolute rounded-full bg-gradient-to-br from-cyan-300 to-violet-400 border border-white/60 shadow-[0_0_12px_rgba(56,229,255,0.75)]"
                    />
                    <span className="absolute bottom-2 text-[11px] text-gray-400 tabular-nums">
                      Live preview · {settings.wakeDotSizeDp}dp
                    </span>
                  </div>
                  <input
                    type="range"
                    min={9}
                    max={50}
                    value={settings.wakeDotSizeDp}
                    aria-label="Wake Dot Size"
                    onChange={(e) => onUpdateSettings({ wakeDotSizeDp: Number(e.target.value) })}
                    className="w-full accent-[var(--accent-cyan)] cursor-pointer"
                  />
                  <div className="flex items-center justify-between gap-2 pt-1">
                    <span className="text-xs text-[var(--text-muted)]">Display duration</span>
                    <div className="flex gap-1.5">
                      {DURATION_PRESETS_MS.map((ms) => (
                        <button
                          key={ms}
                          type="button"
                          onClick={() => onUpdateSettings({ wakeDotDurationMs: ms })}
                          className={`min-h-[34px] rounded-full border px-3 text-xs font-semibold tabular-nums cursor-pointer ${
                            settings.wakeDotDurationMs === ms
                              ? 'border-[var(--accent-cyan)] bg-[var(--accent-cyan)]/15 text-[var(--accent-cyan)]'
                              : 'border-[var(--border-glass)] text-[var(--text-muted)]'
                          }`}
                        >
                          {(ms / 1000).toFixed(1)}s
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              </>
            )}
          </Section>

          {/* Local Commands & Adaptive Learning */}
          <Section title="Local Commands & Adaptive Learning">
            <SwitchRow
              title="Handle simple commands locally"
              detail="Time, date, battery, timers, alarms, and shortcuts run instantly without Gemini."
              checked={settings.localCommands}
              icon={<Brain className="h-4 w-4" />}
              onChange={(v) => onUpdateSettings({ localCommands: v })}
            />
            <GlassDivider />
            <SwitchRow
              title="Learn from how I use JARVIS"
              detail="Kept only in your browser: frequent commands and phrases resolved by Gemini."
              checked={settings.learning}
              onChange={(v) => onUpdateSettings({ learning: v })}
            />
            <GlassDivider />
            <InfoRow
              title="What it knows"
              detail={learnedSummary}
              trailing={
                <button
                  type="button"
                  onClick={onForgetLearned}
                  className="min-h-[38px] px-3 text-sm font-semibold text-[var(--accent-error)] cursor-pointer"
                >
                  Forget
                </button>
              }
            />
          </Section>

          {/* Privacy Boundaries & Memory */}
          <Section title="Privacy Boundaries & Memory">
            <InfoRow
              title="What JARVIS remembers"
              detail={`${notes.length} note(s) stored locally. Secrets (passwords, PINs, OTPs, card numbers) are always refused.`}
              icon={<Shield className="h-4 w-4" />}
              trailing={
                notes.length > 0 ? (
                  <button
                    type="button"
                    onClick={onClearNotes}
                    className="min-h-[38px] px-3 text-sm font-semibold text-[var(--accent-error)] cursor-pointer"
                  >
                    Forget all
                  </button>
                ) : null
              }
            />
            {notes.length > 0 && (
              <div className="space-y-1.5 pb-2">
                {notes.map((n) => (
                  <div
                    key={n.id}
                    className="flex items-center justify-between gap-2 rounded-xl bg-black/5 dark:bg-white/5 px-3 py-2 text-xs"
                  >
                    <span className="text-[var(--text-primary)]">{n.text}</span>
                    <button
                      type="button"
                      aria-label="Delete note"
                      onClick={() => onDeleteNote(n.id)}
                      className="p-1 text-[var(--text-muted)] hover:text-[var(--accent-error)] cursor-pointer shrink-0"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </Section>

          <Section title="About">
            <InfoRow
              title="JARVIS"
              detail="Version 0.1.0 · GNU GPL v3.0"
              icon={<Info className="h-4 w-4" />}
            />
          </Section>
        </div>
      </div>
    </div>
  );
};

const PermissionsSubScreen: React.FC<{
  settings: AppSettingsState;
  micPermission: 'granted' | 'denied' | 'prompt' | 'unsupported';
  onRequestMicPermission: () => void;
  onUpdateSettings: (patch: Partial<AppSettingsState>) => void;
  onBack: () => void;
}> = ({ settings, micPermission, onRequestMicPermission, onUpdateSettings, onBack }) => (
  <div className="mx-auto max-w-4xl px-4 sm:px-6 pt-5 pb-32 space-y-5">
    <button
      type="button"
      onClick={onBack}
      className="inline-flex min-h-[40px] items-center gap-1.5 rounded-full border border-[var(--border-glass)] bg-black/5 dark:bg-white/10 px-4 py-1.5 text-sm font-semibold text-[var(--text-primary)] cursor-pointer"
    >
      <ArrowLeft className="h-4 w-4" />
      Settings
    </button>

    <div>
      <h1 className="text-2xl font-bold text-[var(--text-primary)]">Permissions</h1>
      <p className="mt-1 text-sm text-[var(--text-muted)]">
        Manage browser permissions and automation categories in one place.
      </p>
    </div>

    <Section title="Browser Permissions">
      <InfoRow
        title="Microphone"
        detail={
          micPermission === 'granted'
            ? 'Allowed for voice input and audio reactivity.'
            : micPermission === 'denied'
              ? 'Blocked in browser site settings. Allow microphone access in your address bar.'
              : 'Needed for speech-to-text and voice orb input.'
        }
        trailing={
          micPermission === 'granted' ? (
            <StatusLabel text="Granted" tone="cyan" />
          ) : (
            <button
              type="button"
              onClick={onRequestMicPermission}
              className="min-h-[38px] rounded-full bg-[var(--accent-cyan)] px-4 py-1.5 text-xs font-bold text-[#001E26] cursor-pointer"
            >
              Allow
            </button>
          )
        }
      />
    </Section>

    <Section title="Automation Permissions">
      {AUTOMATION_CATEGORIES.map((cat, idx) => (
        <React.Fragment key={cat.id}>
          {idx > 0 && <GlassDivider />}
          <SwitchRow
            title={cat.title}
            detail={cat.summary}
            checked={Boolean(settings.automations[cat.id])}
            onChange={(checked) =>
              onUpdateSettings({
                automations: { ...settings.automations, [cat.id]: checked },
              })
            }
          />
        </React.Fragment>
      ))}
    </Section>
  </div>
);

const JarvisVoiceSubScreen: React.FC<{
  settings: AppSettingsState;
  catalog: HindiVoiceCatalog;
  onUpdateSettings: (patch: Partial<AppSettingsState>) => void;
  onBack: () => void;
}> = ({ settings, catalog, onUpdateSettings, onBack }) => {
  const [previewLang, setPreviewLang] = useState<PreviewLanguage>('HINDI');
  const [playingVoiceId, setPlayingVoiceId] = useState<string | null>(null);

  const handlePreview = (voice: TtsVoiceInfo) => {
    if (playingVoiceId === voice.id) {
      stopSpeaking();
      setPlayingVoiceId(null);
      return;
    }
    setPlayingVoiceId(voice.id);
    speakText(previewSampleText(previewLang), {
      voiceId: voice.id,
      rate: settings.ttsSpeechRate,
      pitch: settings.ttsPitch,
      lang: previewLang === 'HINDI' ? 'hi-IN' : 'en-IN',
      onEnd: () => setPlayingVoiceId(null),
      onError: () => setPlayingVoiceId(null),
    });
  };

  return (
    <div className="mx-auto max-w-4xl px-4 sm:px-6 pt-5 pb-32 space-y-5">
      <button
        type="button"
        onClick={onBack}
        className="inline-flex min-h-[40px] items-center gap-1.5 rounded-full border border-[var(--border-glass)] bg-black/5 dark:bg-white/10 px-4 py-1.5 text-sm font-semibold text-[var(--text-primary)] cursor-pointer"
      >
        <ArrowLeft className="h-4 w-4" />
        Settings
      </button>

      <div>
        <h1 className="text-2xl font-bold text-[var(--text-primary)]">JARVIS VOICE</h1>
        <p className="mt-1 text-sm text-[var(--text-muted)]">
          Choose your preferred voice and test Hindi or English previews.
        </p>
      </div>

      <GlassCard className="p-4 space-y-3">
        <div>
          <div className="text-sm font-semibold text-[var(--text-primary)]">Preview sample language</div>
          <div className="text-xs text-[var(--text-muted)] mt-0.5">{previewSampleText(previewLang)}</div>
        </div>
        <div className="grid grid-cols-2 gap-2">
          {(['HINDI', 'ENGLISH'] as PreviewLanguage[]).map((lang) => {
            const selected = previewLang === lang;
            return (
              <button
                key={lang}
                type="button"
                onClick={() => {
                  setPreviewLang(lang);
                  stopSpeaking();
                  setPlayingVoiceId(null);
                }}
                className={`min-h-[40px] rounded-xl border text-xs font-semibold cursor-pointer ${
                  selected
                    ? 'border-[var(--accent-cyan)] bg-[var(--accent-cyan)]/15 text-[var(--accent-cyan)]'
                    : 'border-[var(--border-glass)] bg-black/5 dark:bg-white/5 text-[var(--text-primary)]'
                }`}
              >
                {lang === 'HINDI' ? 'Hindi' : 'English'}
              </button>
            );
          })}
        </div>
      </GlassCard>

      <Section title="Female Voices">
        <VoiceChoiceList
          voices={catalog.femaleVoices}
          emptyMessage="No female speech voices found in this browser."
          selectedId={settings.ttsVoiceId}
          playingId={playingVoiceId}
          onSelect={(v) => onUpdateSettings({ ttsVoiceId: v.id, ttsGenderFilter: 'FEMALE' })}
          onPreview={handlePreview}
        />
      </Section>

      <Section title="Male Voices">
        <VoiceChoiceList
          voices={catalog.maleVoices}
          emptyMessage="No male speech voices found in this browser."
          selectedId={settings.ttsVoiceId}
          playingId={playingVoiceId}
          onSelect={(v) => onUpdateSettings({ ttsVoiceId: v.id, ttsGenderFilter: 'MALE' })}
          onPreview={handlePreview}
        />
      </Section>

      <GlassCard className="p-4 space-y-3">
        <SwitchRow
          title="Speak replies"
          detail="Speak JARVIS answers aloud."
          checked={settings.speakResponses}
          onChange={(v) => onUpdateSettings({ speakResponses: v })}
        />
        <GlassDivider />
        <div className="py-2 space-y-1.5">
          <div className="flex justify-between text-sm font-medium text-[var(--text-primary)]">
            <span>Voice speed</span>
            <span className="tabular-nums text-[var(--accent-cyan)]">{settings.ttsSpeechRate.toFixed(2)}x</span>
          </div>
          <input
            type="range"
            min={0.5}
            max={2.0}
            step={0.05}
            value={settings.ttsSpeechRate}
            onChange={(e) => onUpdateSettings({ ttsSpeechRate: Number(e.target.value) })}
            className="w-full accent-[var(--accent-cyan)] cursor-pointer"
          />
        </div>
        <GlassDivider />
        <div className="py-2 space-y-1.5">
          <div className="flex justify-between text-sm font-medium text-[var(--text-primary)]">
            <span>Voice pitch</span>
            <span className="tabular-nums text-[var(--accent-violet)]">{settings.ttsPitch.toFixed(2)}x</span>
          </div>
          <input
            type="range"
            min={0.5}
            max={1.5}
            step={0.05}
            value={settings.ttsPitch}
            onChange={(e) => onUpdateSettings({ ttsPitch: Number(e.target.value) })}
            className="w-full accent-[var(--accent-violet)] cursor-pointer"
          />
        </div>
        <div className="flex justify-end">
          <button
            type="button"
            onClick={() => {
              stopSpeaking();
              setPlayingVoiceId(null);
              onUpdateSettings({
                ttsVoiceId: null,
                ttsGenderFilter: 'FEMALE',
                ttsSpeechRate: 1.0,
                ttsPitch: 1.0,
              });
            }}
            className="min-h-[38px] px-3 text-xs font-semibold text-[var(--text-muted)] hover:text-[var(--text-primary)] cursor-pointer"
          >
            Reset voice
          </button>
        </div>
      </GlassCard>
    </div>
  );
};

const VoiceChoiceList: React.FC<{
  voices: TtsVoiceInfo[];
  emptyMessage: string;
  selectedId: string | null;
  playingId: string | null;
  onSelect: (v: TtsVoiceInfo) => void;
  onPreview: (v: TtsVoiceInfo) => void;
}> = ({ voices, emptyMessage, selectedId, playingId, onSelect, onPreview }) => {
  if (voices.length === 0) {
    return <p className="py-3 text-xs text-[var(--text-muted)]">{emptyMessage}</p>;
  }
  return (
    <div className="space-y-2 py-2">
      {voices.map((voice) => {
        const isSelected = selectedId === voice.id;
        const isPlaying = playingId === voice.id;
        return (
          <div
            key={voice.id}
            onClick={() => onSelect(voice)}
            className={`flex items-center justify-between gap-3 rounded-2xl border px-3.5 py-2.5 cursor-pointer transition-colors ${
              isSelected
                ? 'border-[var(--accent-cyan)] bg-[var(--accent-cyan)]/10'
                : 'border-[var(--border-glass)] bg-black/5 dark:bg-white/5'
            }`}
          >
            <div className="min-w-0 flex-1">
              <div className="text-sm font-semibold text-[var(--text-primary)] truncate">{voice.displayName}</div>
              {isSelected && <div className="text-[11px] font-medium text-[var(--accent-cyan)]">Selected</div>}
            </div>
            <div className="flex items-center gap-2 shrink-0">
              {isSelected ? (
                <span className="flex h-7 w-7 items-center justify-center rounded-full bg-[var(--accent-cyan)]/20 text-[var(--accent-cyan)]">
                  <Check className="h-4 w-4" />
                </span>
              ) : (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    onSelect(voice);
                  }}
                  className="min-h-[34px] rounded-full border border-[var(--accent-cyan)]/50 bg-[var(--accent-cyan)]/15 px-3 text-xs font-semibold text-[var(--text-primary)] cursor-pointer"
                >
                  Select
                </button>
              )}
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  onPreview(voice);
                }}
                className={`inline-flex min-h-[34px] items-center gap-1 rounded-full border px-3 text-xs font-semibold cursor-pointer ${
                  isPlaying
                    ? 'border-[var(--accent-error)] bg-red-500/20 text-[var(--accent-error)]'
                    : 'border-[var(--border-glass)] bg-black/5 dark:bg-white/5 text-[var(--text-primary)]'
                }`}
              >
                {isPlaying ? (
                  <>
                    <Square className="h-3 w-3 fill-current" />
                    Stop
                  </>
                ) : (
                  <>
                    <Play className="h-3 w-3 fill-current" />
                    Play
                  </>
                )}
              </button>
            </div>
          </div>
        );
      })}
    </div>
  );
};
