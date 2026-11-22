export type Phase = 'Off' | 'Connecting' | 'Processing' | 'Listening' | 'Speaking';
export type LocalPhase = 'Idle' | 'Listening' | 'Doing' | 'Speaking';
export type AppearanceMode = 'Dark' | 'Light' | 'System';
export type ReplyLanguage = 'auto' | 'english' | 'hindi' | 'hinglish';
export type PreviewLanguage = 'HINDI' | 'ENGLISH';

export type AutomationCategoryId =
  | 'auto_whatsapp_messages'
  | 'auto_calling'
  | 'auto_open_apps'
  | 'auto_media_playback'
  | 'auto_gallery_management'
  | 'auto_screen_reading'
  | 'auto_screen_interaction'
  | 'auto_play_store';

export interface AutomationCategoryMeta {
  id: AutomationCategoryId;
  title: string;
  summary: string;
  defaultEnabled: boolean;
}

export const AUTOMATION_CATEGORIES: AutomationCategoryMeta[] = [
  {
    id: 'auto_whatsapp_messages',
    title: 'WhatsApp message automation',
    summary: 'Type drafts and prepare Send for clear WhatsApp commands with recipient verification.',
    defaultEnabled: true,
  },
  {
    id: 'auto_calling',
    title: 'Calling automation',
    summary: 'Prepare unambiguous phone and WhatsApp audio/video calls cleanly after your command.',
    defaultEnabled: true,
  },
  {
    id: 'auto_open_apps',
    title: 'App opening',
    summary: 'Launch web shortcuts and app commands directly when asked.',
    defaultEnabled: true,
  },
  {
    id: 'auto_media_playback',
    title: 'Media playback',
    summary: 'Search, play, pause, and skip music on your preferred music app.',
    defaultEnabled: true,
  },
  {
    id: 'auto_gallery_management',
    title: 'Gallery management',
    summary: 'Locate photos cleanly (deletion ALWAYS asks explicit yes/no confirmation).',
    defaultEnabled: true,
  },
  {
    id: 'auto_screen_reading',
    title: 'Screen reading',
    summary: 'Read visible non-sensitive content directly when requested.',
    defaultEnabled: true,
  },
  {
    id: 'auto_screen_interaction',
    title: 'On-screen interaction',
    summary: 'Allow safe non-payment actions inside non-financial contexts.',
    defaultEnabled: true,
  },
  {
    id: 'auto_play_store',
    title: 'Play Store navigation & free installs',
    summary: 'Search apps and prepare free installs (always stops for age, payment, or sign-in).',
    defaultEnabled: true,
  },
];

export interface ChatLine {
  id: string;
  text: string;
  fromUser: boolean;
  at: number;
  sources?: Array<{ title: string; uri: string }>;
}

export interface ActivityEntry {
  id: string;
  what: string;
  result: string;
  at: number;
  local: boolean;
}

export type TaskState =
  | 'Understanding'
  | 'Planning'
  | 'AwaitingConfirmation'
  | 'Executing'
  | 'WaitingForYou'
  | 'Completed'
  | 'Failed';

export type TaskRisk = 'Low' | 'Medium' | 'High' | 'Financial';

export interface TaskStep {
  label: string;
  done: boolean | null;
}

export interface PendingConfirmAction {
  kind: 'send_message' | 'call' | 'delete' | 'payment_hand_off' | 'calendar_save' | 'open_url';
  title: string;
  summary: string;
  target?: string;
  url?: string;
  confirmLabel: string;
}

export interface TaskItem {
  id: string;
  title: string;
  state: TaskState;
  risk: TaskRisk;
  steps: TaskStep[];
  detail: string;
  at: number;
  pendingAction?: PendingConfirmAction;
}

export interface Note {
  id: string;
  text: string;
  atMillis: number;
  updatedAtMillis: number;
}

export interface Routine {
  name: string;
  steps: string[];
  builtIn?: boolean;
}

export interface ReminderItem {
  id: string;
  text: string;
  dueAt: number;
  done: boolean;
}

export interface TimerItem {
  id: string;
  label: string;
  totalSeconds: number;
  endsAt: number;
  done: boolean;
}

export interface AlarmItem {
  id: string;
  hour: number;
  minute: number;
  label: string;
  enabled: boolean;
  triggeredToday?: boolean;
}

export interface PersonalProfile {
  preferredName: string;
  addressAs: string;
  favoriteSongs: string[];
  favoriteArtists: string[];
  favoriteGenres: string[];
  preferredMusicApp: string;
  frequentApps: string[];
  preferredLanguage: string;
  responseStyle: string;
  customNotes: string;
}

export interface SeenNotification {
  id: string;
  packageName: string;
  app: string;
  title: string;
  text: string;
  atMillis: number;
  group?: boolean;
  category?: 'msg' | 'email' | 'missed_call' | 'promo' | 'reminder' | 'event' | 'alarm' | '';
  replyId?: number;
}

export interface AppSettingsState {
  appearanceMode: AppearanceMode;
  replyLanguage: ReplyLanguage;
  speakResponses: boolean;
  ttsVoiceId: string | null;
  ttsGenderFilter: 'FEMALE' | 'MALE';
  ttsSpeechRate: number;
  ttsPitch: number;
  talkOver: boolean;
  waveToStop: boolean;
  wakeWord: boolean;
  wakeIndicator: boolean;
  wakeDotSizeDp: number;
  wakeDotDurationMs: number;
  localCommands: boolean;
  learning: boolean;
  homeCity: string;
  automations: Record<AutomationCategoryId, boolean>;
}
