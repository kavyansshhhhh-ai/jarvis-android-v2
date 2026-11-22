export type ClickRisk = 'Safe' | 'Outgoing' | 'Destructive' | 'Money';

const HARD_BLOCKED_PACKAGES = new Set([
  'app.shilex.android',
  'com.android.settings',
  'com.android.systemui',
  'org.npci.upi.security.pinactivitycomponent',
  'com.x8bit.bitwarden',
  'com.onepassword.android',
  'com.lastpass.lpandroid',
  'com.google.android.apps.authenticator2',
  'com.azure.authenticator',
  'com.authy.authy',
  'keepass2android.keepass2android',
]);

const FINANCE_APP_NAMES =
  /\b(phonepe|google\s*pay|gpay|paytm|bhim|amazon\s*pay|cred|mobikwik|bharatpe|navi|super\s*money|fampay|yono|sbi|hdfc|icici|imobile|axis|kotak|bob\s*world|pnb|canara|upi|bank(?:ing)?)\b/i;

const CREDENTIAL_RE =
  /\b(upi[\s_-]?pin|m[\s_-]?pin|atm[\s_-]?pin|enter\s+(?:\d+[\s_-]?digit\s+)?(?:upi\s+)?pin|pin[\s_-]?pad|otp|one[\s_-]?time[\s_-]?password|cvv|cvc|passcode|password|card[\s_-]?number|expiry)\b/i;

const MONEY_RE =
  /\b(buy|purchase|checkout|place\s+order|order\s+now|donate|subscribe|pay\s*now|proceed\s+to\s+pay|continue\s+to\s+pay|confirm\s*(?:&|and)?\s*pay|confirm\s+payment|confirm\s+transfer|authorize(?:\s+payment)?|swipe\s+to\s+pay|complete\s+payment|add\s*(?:&|and)?\s*pay|pay|payment|transfer)\b|भुगतान|खरीद/i;

const DESTRUCTIVE_RE =
  /\b(delete|remove|trash|erase|discard|uninstall|format|reset|clear all|empty|sign out|log ?out|block|report)\b|हटा|डिलीट|मिटा/i;

const OUTGOING_RE =
  /\b(send|submit|post|publish|share|reply|forward|upload|confirm|accept|agree|allow|invite|schedule|book|call|dial|install|bhejo|bhejein)\b|भेज|पोस्ट|कॉल/i;

export function isHardBlockedApp(nameOrPkg: string): boolean {
  const lower = nameOrPkg.trim().toLowerCase();
  return HARD_BLOCKED_PACKAGES.has(lower) || /password|authenticator|vault|pinentry|keepass/i.test(lower);
}

export function isFinanceAppName(name: string): boolean {
  return FINANCE_APP_NAMES.test(name);
}

export function isCredentialText(text: string): boolean {
  return CREDENTIAL_RE.test(text);
}

export function classifyActionRisk(actionText: string): ClickRisk {
  if (MONEY_RE.test(actionText)) return 'Money';
  if (DESTRUCTIVE_RE.test(actionText)) return 'Destructive';
  if (OUTGOING_RE.test(actionText)) return 'Outgoing';
  return 'Safe';
}
