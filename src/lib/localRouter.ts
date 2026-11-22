export type AudioStream = 'Media' | 'Ring' | 'Notification' | 'Alarm' | 'Call';
export type MediaKey = 'Play' | 'Pause' | 'Next' | 'Previous';
export type Period = 'Am' | 'Pm';
export type SystemPanelTarget =
  | 'Notifications'
  | 'QuickSettings'
  | 'Wifi'
  | 'Hotspot'
  | 'Bluetooth'
  | 'Brightness'
  | 'Battery';

export type LocalCommand =
  | { type: 'SetVolume'; percent: number; stream: AudioStream }
  | { type: 'StepVolume'; up: boolean; stream: AudioStream }
  | { type: 'Mute'; on: boolean; mediaOnly: boolean }
  | { type: 'Media'; key: MediaKey }
  | { type: 'Torch'; on: boolean }
  | { type: 'Battery' }
  | { type: 'Time' }
  | { type: 'Date' }
  | { type: 'Timer'; seconds: number }
  | { type: 'Alarm'; hour: number; minute: number; period: Period | null }
  | { type: 'OpenApp'; name: string }
  | { type: 'SystemPanel'; target: SystemPanelTarget }
  | { type: 'ListApps' }
  | { type: 'SearchApps'; query: string }
  | { type: 'Stop' }
  | { type: 'Shutdown' };

const ONES = [
  'zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten',
  'eleven', 'twelve', 'thirteen', 'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen',
];
const TENS = ['', '', 'twenty', 'thirty', 'forty', 'fifty', 'sixty', 'seventy', 'eighty', 'ninety'];

const SOUNDS: Array<[string, string]> = [
  ['car do', 'kar do'],
  ['car the', 'kar do'],
  ['car or', 'karo'],
  ['car oh', 'karo'],
  ['caro', 'karo'],
  ['cairo', 'karo'],
  ['kairo', 'karo'],
  ['carro', 'karo'],
  ['kero', 'karo'],
  ['call or', 'kholo'],
  ['call oh', 'kholo'],
  ['colo', 'kholo'],
  ['kolo', 'kholo'],
  ['cool oh', 'kholo'],
  ['collar', 'kholo'],
  ['color', 'kholo'],
  ['caller', 'kholo'],
  ['kollo', 'kholo'],
  ['what s up', 'whatsapp'],
  ['whats up', 'whatsapp'],
  ['whats app', 'whatsapp'],
  ['what s app', 'whatsapp'],
  ['you tube', 'youtube'],
  ['kidney', 'kitni'],
  ['ghana', 'gaana'],
  ['garner', 'gaana'],
  ['flash light', 'flashlight'],
  ['bund', 'band'],
  ['bond', 'band'],
  ['jello', 'jalao'],
  ['chalo', 'chalao'],
];

const LEADS = ['hey jarvis', 'hi jarvis', 'ok jarvis', 'okay jarvis', 'jarvis', 'hey shilex', 'shilex'];
const FILLERS = ['please', 'plz', 'zara', 'jara', 'ji', 'na', 'can you', 'could you', 'mera', 'meri', 'the'];
const TAILS = ['kar do', 'kardo', 'kar dijiye', 'kar dena', 'karo', 'kijiye', 'kariye', 'do', 'de', 'please'];

const STOPS = new Set([
  'stop', 'cancel', 'bas', 'ruko', 'ruk jao', 'chup', 'never mind', 'nevermind', 'kuch nahi', 'rehne',
  'go to sleep', 'so jao', 'standby', 'that s all', 'thats all', 'bye',
]);

const SHUTDOWNS = new Set([
  'shut down', 'shutdown', 'turn yourself off', 'power off', 'band ho jao', 'abhi band ho jao',
  'khud ko band kar lo', 'khud ko band kar', 'ab tumhari zarurat nahi hai',
  'ab tumhari zarurat nahi hai khud ko band kar lo', 'ab tumhari jarurat nahi hai khud ko band kar lo',
  'ab tumhari jarurat nahi hai', 'assistant band', 'jarvis band',
]);

const SHUTDOWN_REGEX =
  /\b(khud ko band|tumhari (?:zarurat|jarurat) nahi|abhi band ho jao|(?:assistant|jarvis)\s*band|shut\s*down(?:\s+jarvis)?|turn\s+yourself\s+off|power\s+off\s+jarvis)\b/;

const PLAYS = new Set([
  'play', 'resume', 'play music', 'resume music', 'music play', 'music resume', 'music on',
  'music chalu', 'music chalao', 'gaana chalao', 'gana chalao', 'gaane chalao', 'continue music', 'play song',
]);

const MORE_RE = /\b(and|aur|then|phir|message|msg|call|bhejo|send|whatsapp pe|on whatsapp)\b.*\b\w+/;
const NOT_APPS = new Set([
  'timer', 'alarm', 'music', 'torch', 'flashlight', 'song', 'gaana', 'notifications',
  'quick settings', 'wifi', 'hotspot', 'bluetooth', 'brightness',
]);
const LIST_APPS_RE = /\b(list|show|batao|dikhao)\b.*\b(installed )?apps\b|\b(installed apps list|all apps|a to z apps|apps a to z)\b/;
const SEARCH_APP_INSTALLED = /^do i have (.+?) installed$/;
const SEARCH_APP_IS_INSTALLED = /^is (.+?) installed$/;
const SEARCH_APP_KAUN = /^kya (.+?) installed hai$/;
const OPEN_FIRST = /^(open|launch|start|kholo|khol)\s+(.+)$/;
const OPEN_LAST = /^(.+?)\s+(kholo|khol|kholiye|open|chalu|chalao|start|launch)$/;

export function numbersToDigits(text: string): string {
  const out: string[] = [];
  for (const word of text.split(' ')) {
    const oneIdx = ONES.indexOf(word);
    const tenIdx = TENS.indexOf(word);
    const n = oneIdx >= 0 ? oneIdx : tenIdx >= 2 ? tenIdx * 10 : null;
    const last = out.length > 0 && /^\d+$/.test(out[out.length - 1]) ? parseInt(out[out.length - 1], 10) : null;
    if (word === 'hundred' && last !== null && last < 10) {
      out[out.length - 1] = String(last * 100);
    } else if (word === 'hundred') {
      out.push('100');
    } else if (n === null) {
      out.push(word);
    } else if (n >= 1 && n <= 9 && last !== null && last >= 20 && last % 10 === 0 && last < 100) {
      out[out.length - 1] = String(last + n);
    } else {
      out.push(String(n));
    }
  }
  return out.join(' ');
}

export function normalizeCommand(text: string): string {
  const words = text
    .toLowerCase()
    .replace(/%/g, ' percent ')
    .replace(/wi-fi/g, 'wifi')
    .replace(/wi fi/g, 'wifi')
    .replace(/\ba\.?\s?m\b\.?/g, 'am')
    .replace(/\bp\.?\s?m\b\.?/g, 'pm')
    .replace(/[^a-z0-9: ]/g, ' ')
    .trim()
    .replace(/\s+/g, ' ');

  let s = ` ${numbersToDigits(words)} `;
  for (const [heard, meant] of SOUNDS) {
    s = s.split(` ${heard} `).join(` ${meant} `);
  }
  for (const lead of LEADS) {
    if (s.startsWith(` ${lead} `)) {
      s = s.slice(lead.length + 1);
    }
  }
  for (const filler of FILLERS) {
    s = s.split(` ${filler} `).join(' ');
  }
  for (const tail of TAILS) {
    if (s.endsWith(` ${tail} `)) {
      s = s.slice(0, s.length - tail.length - 1);
    }
  }
  return s.replace(/\s+/g, ' ').trim();
}

function has(s: string, ...words: string[]): boolean {
  return words.some((w) => new RegExp(`\\b${w}\\b`).test(s));
}

function matchTimer(s: string): LocalCommand | null {
  if (!has(s, 'timer')) return null;
  let seconds = 0;
  const re = /(\d+)\s*(seconds?|secs?|minutes?|mins?|minat|hours?|hrs?|ghanta|ghante)\b/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(s)) !== null) {
    const n = parseInt(m[1], 10);
    const unit = m[2];
    if (unit.startsWith('s')) seconds += n;
    else if (unit.startsWith('m')) seconds += n * 60;
    else seconds += n * 3600;
  }
  return seconds >= 1 && seconds <= 86400 ? { type: 'Timer', seconds } : null;
}

function matchAlarm(s: string): LocalCommand | null {
  if (!has(s, 'alarm') || has(s, 'cancel', 'delete', 'hatao', 'off', 'band')) return null;
  const timeMatch = /\b(\d{1,2})(?:[: ](\d{2}))?\b/.exec(s);
  if (!timeMatch) return null;
  const hour = parseInt(timeMatch[1], 10);
  const minute = timeMatch[2] ? parseInt(timeMatch[2], 10) : 0;
  if (hour > 23 || minute > 59) return null;
  const night = has(s, 'raat', 'night');
  let period: Period | null = null;
  if (hour <= 12 && hour > 0) {
    if (has(s, 'am', 'subah', 'morning', 'savere')) period = 'Am';
    else if (night && hour >= 1 && hour <= 4) period = 'Am';
    else if (night || has(s, 'pm', 'shaam', 'sham', 'evening', 'dopahar', 'afternoon')) period = 'Pm';
  }
  return { type: 'Alarm', hour, minute, period };
}

function matchSystemPanel(s: string): LocalCommand | null {
  if (!has(s, 'open', 'show', 'kholo', 'khol', 'kholiye', 'pull', 'down', 'dikhao', 'settings', 'panel')) return null;
  if (has(s, 'quick settings', 'quick setting', 'quicksettings')) return { type: 'SystemPanel', target: 'QuickSettings' };
  if (has(s, 'notification panel', 'notification shade', 'notifications', 'notification'))
    return { type: 'SystemPanel', target: 'Notifications' };
  if (has(s, 'hotspot', 'tethering')) return { type: 'SystemPanel', target: 'Hotspot' };
  if (has(s, 'wifi')) return { type: 'SystemPanel', target: 'Wifi' };
  if (has(s, 'bluetooth')) return { type: 'SystemPanel', target: 'Bluetooth' };
  if (has(s, 'brightness', 'display')) return { type: 'SystemPanel', target: 'Brightness' };
  if (has(s, 'battery') && has(s, 'settings', 'setting')) return { type: 'SystemPanel', target: 'Battery' };
  return null;
}

function matchAppsQuery(s: string): LocalCommand | null {
  if (LIST_APPS_RE.test(s)) return { type: 'ListApps' };
  const m = SEARCH_APP_INSTALLED.exec(s) || SEARCH_APP_IS_INSTALLED.exec(s) || SEARCH_APP_KAUN.exec(s);
  if (m) {
    const q = m[1].trim();
    if (q.length > 0 && q.length <= 40) return { type: 'SearchApps', query: q };
  }
  return null;
}

function matchTorch(s: string): LocalCommand | null {
  if (!has(s, 'torch', 'flashlight', 'flash light', 'flash')) return null;
  if (has(s, 'off', 'of', 'band', 'bujha', 'bujhao', 'bujha do')) return { type: 'Torch', on: false };
  if (has(s, 'on', 'jala', 'jalao', 'chalu', 'start')) return { type: 'Torch', on: true };
  return null;
}

function detectStream(s: string): AudioStream {
  if (has(s, 'ring', 'ringtone', 'ringer')) return 'Ring';
  if (has(s, 'notification', 'notifications')) return 'Notification';
  if (has(s, 'alarm')) return 'Alarm';
  if (has(s, 'call', 'incall')) return 'Call';
  return 'Media';
}

function matchVolume(s: string): LocalCommand | null {
  if (!has(s, 'volume', 'awaaz', 'aawaz', 'awaz', 'sound')) return null;
  const stream = detectStream(s);
  const num = /\b(\d{1,3})\b/.exec(s);
  if (num) {
    return { type: 'SetVolume', percent: Math.max(0, Math.min(100, parseInt(num[1], 10))), stream };
  }
  if (has(s, 'max', 'maximum', 'full', 'poori', 'pura', 'poora')) return { type: 'SetVolume', percent: 100, stream };
  if (has(s, 'unmute', 'wapas')) return { type: 'Mute', on: false, mediaOnly: stream === 'Media' && has(s, 'media') };
  if (has(s, 'mute', 'band', 'zero')) return { type: 'Mute', on: true, mediaOnly: has(s, 'media') };
  if (has(s, 'up', 'badha', 'badhao', 'increase', 'louder', 'tez', 'zyada', 'high', 'raise'))
    return { type: 'StepVolume', up: true, stream };
  if (has(s, 'down', 'kam', 'ghata', 'ghatao', 'decrease', 'lower', 'dheere', 'dheema', 'low', 'reduce'))
    return { type: 'StepVolume', up: false, stream };
  return null;
}

function matchMute(s: string): LocalCommand | null {
  if (/^mute media$|^media mute$/.test(s)) return { type: 'Mute', on: true, mediaOnly: true };
  if (/^unmute media$|^media unmute$/.test(s)) return { type: 'Mute', on: false, mediaOnly: true };
  if (/^(phone |mobile )?unmute( phone| mobile)?$/.test(s)) return { type: 'Mute', on: false, mediaOnly: false };
  if (/^(phone |mobile )?(mute|silent)( phone| mobile| mode)?( pe| par| on)?$/.test(s))
    return { type: 'Mute', on: true, mediaOnly: false };
  return null;
}

function matchMedia(s: string): LocalCommand | null {
  const music = has(s, 'music', 'gaana', 'gana', 'song', 'gaane', 'track');
  if (has(s, 'pause') || (music && has(s, 'band', 'stop', 'roko', 'rok'))) return { type: 'Media', key: 'Pause' };
  if (PLAYS.has(s)) return { type: 'Media', key: 'Play' };
  if (s === 'next' || s === 'skip' || (music && has(s, 'next', 'agla', 'skip'))) return { type: 'Media', key: 'Next' };
  if (s === 'previous' || s === 'back' || (music && has(s, 'previous', 'pichla', 'pichhla')))
    return { type: 'Media', key: 'Previous' };
  return null;
}

function matchBattery(s: string): LocalCommand | null {
  if (has(s, 'battery', 'charge', 'charging') && !has(s, 'saver', 'save', 'mode', 'settings', 'setting')) {
    return { type: 'Battery' };
  }
  return null;
}

function matchTime(s: string): LocalCommand | null {
  if (/\btime in \w+/.test(s)) return null;
  if (has(s, 'time', 'samay', 'kitne baje', 'baj rahe', 'kitna baja', 'baje hai')) return { type: 'Time' };
  if (has(s, 'date', 'tarikh', 'tareekh', 'what day', 'which day', 'kaun sa din', 'kya din')) return { type: 'Date' };
  return null;
}

function matchOpenApp(s: string): LocalCommand | null {
  const m = OPEN_FIRST.exec(s)?.[2] || OPEN_LAST.exec(s)?.[1];
  if (!m) return null;
  const skip = new Set(['app', 'the', 'my', 'mera', 'meri', 'application']);
  const clean = m
    .split(' ')
    .filter((w) => !skip.has(w))
    .join(' ');
  if (!clean || clean.split(' ').length > 3 || NOT_APPS.has(clean)) return null;
  if (clean.includes('chatgpt') && has(s, 'voice')) return null;
  return { type: 'OpenApp', name: clean };
}

export function matchLocalCommand(text: string): LocalCommand | null {
  const s = normalizeCommand(text);
  if (!s) return null;
  if (MORE_RE.test(s)) return null;
  if (SHUTDOWNS.has(s) || SHUTDOWN_REGEX.test(s)) return { type: 'Shutdown' };
  if (STOPS.has(s)) return { type: 'Stop' };
  return (
    matchTimer(s) ||
    matchAlarm(s) ||
    matchSystemPanel(s) ||
    matchAppsQuery(s) ||
    matchTorch(s) ||
    matchVolume(s) ||
    matchMute(s) ||
    matchMedia(s) ||
    matchBattery(s) ||
    matchTime(s) ||
    matchOpenApp(s)
  );
}
