import { SeenNotification } from './types';

export type TriageKind = 'Needs' | 'Mail' | 'Code' | 'Suspicious' | 'Quiet';

export interface TriageItem {
  kind: TriageKind;
  line: string;
}

const CHAT_APPS = new Set([
  'com.whatsapp',
  'com.whatsapp.w4b',
  'org.telegram.messenger',
  'com.google.android.apps.messaging',
  'com.samsung.android.messaging',
  'com.instagram.android',
  'com.facebook.orca',
  'com.Slack',
]);

const MAIL_APPS = new Set([
  'com.google.android.gm',
  'com.microsoft.office.outlook',
  'com.samsung.android.email.provider',
  'com.yahoo.mobile.client.android.mail',
]);

const CALL_APPS = new Set([
  'com.android.phone',
  'com.google.android.dialer',
  'com.samsung.android.dialer',
  'com.android.server.telecom',
]);

const NO_REPLY_RE = /\bno.?reply\b|\bnotifications?@|\bnewsletter/i;
const CODE_RE = /\b(otp|one[- ]time|verification|passcode|security code|login code|code is)\b/i;
const DIGITS_RE = /\b\d{4,8}\b/;
const LINK_RE = /https?:\/\/|www\.|\b[a-z0-9-]+\.(ly|link|xyz|top|click|in|com)\//i;
const PROMO_RE = /\b(offer|sale|% ?off|cashback|discount|deal|coupon|free delivery|subscribe|newsletter|limited time|unsubscribe)\b/i;
const PHONE_NUMBER_RE = /^[+\d][\d\s()-]{6,}$/;
const IGNORED_CATS = new Set(['transport', 'progress', 'service', 'sys', 'status', 'navigation']);

function short(text: string): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length <= 90 ? flat : `${flat.slice(0, 89)}…`;
}

function unknownSender(n: SeenNotification): boolean {
  return PHONE_NUMBER_RE.test(n.title.trim()) || n.title.trim().length === 0;
}

export function classifyNotification(n: SeenNotification): TriageItem {
  const who = n.title.trim() || n.app;
  const body = `${n.title} ${n.text}`;
  if (CODE_RE.test(body) && DIGITS_RE.test(body)) {
    return { kind: 'Code', line: `A verification code from ${n.app} (digits hidden).` };
  }
  if (LINK_RE.test(n.text) && unknownSender(n)) {
    return { kind: 'Suspicious', line: `${who} (${n.app}, not a saved contact) sent a link.` };
  }
  if (n.group || n.category === 'promo' || PROMO_RE.test(body)) {
    return { kind: 'Quiet', line: n.app };
  }
  if (MAIL_APPS.has(n.packageName) || n.category === 'email') {
    return NO_REPLY_RE.test(n.title)
      ? { kind: 'Quiet', line: n.app }
      : { kind: 'Mail', line: `${who}: ${short(n.text)}` };
  }
  if (CALL_APPS.has(n.packageName) || n.category === 'missed_call') {
    return { kind: 'Needs', line: `${who}: ${short(n.text.trim() || 'missed call')}` };
  }
  if (CHAT_APPS.has(n.packageName) || n.category === 'msg') {
    return {
      kind: 'Needs',
      line: `${who} (${n.app}): ${short(n.text)}${n.replyId != null ? ` [reply ${n.replyId}]` : ''}`,
    };
  }
  if (n.category === 'reminder' || n.category === 'event' || n.category === 'alarm') {
    return { kind: 'Needs', line: `${n.app}: ${short(n.title)} ${short(n.text)}`.trim() };
  }
  return { kind: 'Quiet', line: n.app };
}

export function triageNotifications(all: SeenNotification[]): { items: TriageItem[]; summaryText: string } {
  const seenKeys = new Set<string>();
  const filtered = all.filter((n) => {
    if (IGNORED_CATS.has(n.category || '')) return false;
    if (!n.title.trim() && !n.text.trim()) return false;
    const key = `${n.packageName}|${n.title}|${n.text}`;
    if (seenKeys.has(key)) return false;
    seenKeys.add(key);
    return true;
  });

  const items = filtered.map(classifyNotification);
  const lines: string[] = [];
  const needs = items.filter((i) => i.kind === 'Needs');
  if (needs.length === 0) {
    lines.push('No messages or missed calls from people.');
  } else {
    needs.slice(0, 8).forEach((i) => lines.push(`- ${i.line}`));
    if (needs.length > 8) lines.push(`- …and ${needs.length - 8} more.`);
  }

  const mail = items.filter((i) => i.kind === 'Mail');
  if (mail.length > 0) {
    lines.push('Emails:');
    mail.slice(0, 8).forEach((i) => lines.push(`- ${i.line}`));
  }

  items.filter((i) => i.kind === 'Suspicious').forEach((i) => lines.push(`Suspicious link flagged: ${i.line}`));
  items.filter((i) => i.kind === 'Code').forEach((i) => lines.push(`Code: ${i.line}`));

  const quietCounts = new Map<string, number>();
  items
    .filter((i) => i.kind === 'Quiet')
    .forEach((i) => quietCounts.set(i.line, (quietCounts.get(i.line) ?? 0) + 1));
  if (quietCounts.size > 0) {
    const q = Array.from(quietCounts.entries())
      .map(([app, count]) => `${app} (${count})`)
      .join(', ');
    lines.push(`Quiet (groups, promos, apps): ${q}`);
  }

  return { items, summaryText: lines.join('\n') };
}
