import { PreviewLanguage } from './types';

export interface TtsVoiceInfo {
  id: string;
  displayName: string;
  lang: string;
  gender: 'FEMALE' | 'MALE';
  voice: SpeechSynthesisVoice;
}

export interface HindiVoiceCatalog {
  femaleVoices: TtsVoiceInfo[];
  maleVoices: TtsVoiceInfo[];
}

const FEMALE_HINTS = /\b(female|woman|lekha|kalpana|heera|swara|aditi|kajal|raveena|priya|ananya|neerja|shruti|sangeeta|divya|isha|aarti|meera|kavya|samantha|victoria|karen|zira|aria|jenny|natasha|sara|emma|google हिन्दी|hi-in-x-hia|hi-in-x-hic|hi-in-x-hie)\b/i;
const MALE_HINTS = /\b(male|man|hemant|prabhat|madhur|rishi|kabir|arjun|aarav|rohan|amit|rahul|vikram|dev|daniel|alex|david|mark|guy|ryan|andrew|hi-in-x-hid|hi-in-x-hif)\b/i;

export function previewSampleText(language: PreviewLanguage): string {
  return language === 'HINDI'
    ? 'नमस्ते, मैं जार्विस हूँ। आपकी क्या मदद कर सकता हूँ?'
    : 'Hello, I am JARVIS. Ready when you are.';
}

export function classifyVoiceGender(voice: SpeechSynthesisVoice, index: number): 'FEMALE' | 'MALE' {
  const haystack = `${voice.name} ${voice.voiceURI}`.toLowerCase();
  if (MALE_HINTS.test(haystack)) return 'MALE';
  if (FEMALE_HINTS.test(haystack)) return 'FEMALE';
  return index % 2 === 0 ? 'FEMALE' : 'MALE';
}

export function buildVoiceCatalog(): HindiVoiceCatalog {
  if (typeof window === 'undefined' || !('speechSynthesis' in window)) {
    return { femaleVoices: [], maleVoices: [] };
  }
  const raw = window.speechSynthesis.getVoices();
  if (!raw.length) return { femaleVoices: [], maleVoices: [] };

  // Prioritize hi-IN, en-IN, then English voices so users always have genuine voices available
  const prioritized = [...raw].sort((a, b) => {
    const score = (v: SpeechSynthesisVoice) => {
      const l = v.lang.toLowerCase();
      if (l.startsWith('hi-in') || l === 'hi') return 0;
      if (l.startsWith('en-in')) return 1;
      if (l.startsWith('en')) return 2;
      return 3;
    };
    return score(a) - score(b);
  });

  const relevant = prioritized.filter((v) => {
    const l = v.lang.toLowerCase();
    return l.startsWith('hi') || l.startsWith('en');
  });
  const pool = relevant.length > 0 ? relevant : prioritized;

  const femaleVoices: TtsVoiceInfo[] = [];
  const maleVoices: TtsVoiceInfo[] = [];

  pool.forEach((voice, idx) => {
    const gender = classifyVoiceGender(voice, idx);
    const info: TtsVoiceInfo = {
      id: voice.voiceURI || voice.name,
      displayName: `${voice.name.replace(/Google\s+/i, '').replace(/Microsoft\s+/i, '')} (${voice.lang})`,
      lang: voice.lang,
      gender,
      voice,
    };
    if (gender === 'FEMALE' && femaleVoices.length < 5) {
      femaleVoices.push(info);
    } else if (gender === 'MALE' && maleVoices.length < 5) {
      maleVoices.push(info);
    }
  });

  return { femaleVoices, maleVoices };
}

export function speakText(
  text: string,
  options: {
    voiceId?: string | null;
    rate?: number;
    pitch?: number;
    lang?: string;
    onStart?: () => void;
    onEnd?: () => void;
    onError?: () => void;
  } = {},
): void {
  if (typeof window === 'undefined' || !('speechSynthesis' in window)) {
    options.onEnd?.();
    return;
  }
  window.speechSynthesis.cancel();
  const clean = text.replace(/[*_`#]/g, '').trim();
  if (!clean) {
    options.onEnd?.();
    return;
  }
  const utterance = new SpeechSynthesisUtterance(clean);
  const catalog = buildVoiceCatalog();
  const all = [...catalog.femaleVoices, ...catalog.maleVoices];
  const chosen = options.voiceId ? all.find((v) => v.id === options.voiceId) : null;
  if (chosen) {
    utterance.voice = chosen.voice;
    utterance.lang = chosen.lang;
  } else {
    const hasDevanagari = /[\u0900-\u097f]/.test(clean);
    utterance.lang = options.lang ?? (hasDevanagari ? 'hi-IN' : 'en-IN');
  }
  utterance.rate = Math.max(0.5, Math.min(2.0, options.rate ?? 1.0));
  utterance.pitch = Math.max(0.5, Math.min(1.5, options.pitch ?? 1.0));
  utterance.onstart = () => options.onStart?.();
  utterance.onend = () => options.onEnd?.();
  utterance.onerror = () => options.onError?.();
  window.speechSynthesis.speak(utterance);
}

export function stopSpeaking(): void {
  if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
    window.speechSynthesis.cancel();
  }
}

export function playChime(): void {
  try {
    const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    if (!AudioCtx) return;
    const ctx = new AudioCtx();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(660, ctx.currentTime);
    osc.frequency.exponentialRampToValueAtTime(880, ctx.currentTime + 0.18);
    gain.gain.setValueAtTime(0.08, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.55);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.55);
  } catch {
    // ignore audio context restrictions
  }
}
