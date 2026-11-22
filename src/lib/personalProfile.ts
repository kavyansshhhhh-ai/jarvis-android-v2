import { PersonalProfile } from './types';

export const EMPTY_PROFILE: PersonalProfile = {
  preferredName: '',
  addressAs: '',
  favoriteSongs: [],
  favoriteArtists: [],
  favoriteGenres: [],
  preferredMusicApp: '',
  frequentApps: [],
  preferredLanguage: '',
  responseStyle: '',
  customNotes: '',
};

function dedupe(arr: string[], maxLen: number, limit: number): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const item of arr) {
    const clean = item.trim().slice(0, maxLen);
    if (clean && !seen.has(clean)) {
      seen.add(clean);
      out.push(clean);
      if (out.length >= limit) break;
    }
  }
  return out;
}

export function sanitizeProfile(p: PersonalProfile): PersonalProfile {
  return {
    preferredName: p.preferredName.trim().slice(0, 60),
    addressAs: p.addressAs.trim().slice(0, 40),
    favoriteSongs: dedupe(p.favoriteSongs, 80, 20),
    favoriteArtists: dedupe(p.favoriteArtists, 60, 20),
    favoriteGenres: dedupe(p.favoriteGenres, 40, 15),
    preferredMusicApp: p.preferredMusicApp.trim().slice(0, 40),
    frequentApps: dedupe(p.frequentApps, 40, 20),
    preferredLanguage: p.preferredLanguage.trim().slice(0, 30),
    responseStyle: p.responseStyle.trim().slice(0, 120),
    customNotes: p.customNotes.trim().slice(0, 300),
  };
}

export function isProfileEmpty(p: PersonalProfile): boolean {
  return (
    !p.preferredName.trim() &&
    !p.addressAs.trim() &&
    p.favoriteSongs.length === 0 &&
    p.favoriteArtists.length === 0 &&
    p.favoriteGenres.length === 0 &&
    !p.preferredMusicApp.trim() &&
    p.frequentApps.length === 0 &&
    !p.preferredLanguage.trim() &&
    !p.responseStyle.trim() &&
    !p.customNotes.trim()
  );
}

export function profileSummary(raw: PersonalProfile): string {
  const p = sanitizeProfile(raw);
  if (isProfileEmpty(p)) return '';
  const parts: string[] = [];
  if (p.preferredName) parts.push(`Name: ${p.preferredName}.`);
  if (p.addressAs) parts.push(`Address user as: ${p.addressAs}.`);
  if (p.favoriteSongs.length) parts.push(`Favourite songs: ${p.favoriteSongs.join(', ')}.`);
  if (p.favoriteArtists.length) parts.push(`Favourite artists: ${p.favoriteArtists.join(', ')}.`);
  if (p.favoriteGenres.length) parts.push(`Favourite genres: ${p.favoriteGenres.join(', ')}.`);
  if (p.preferredMusicApp) parts.push(`Preferred music app: ${p.preferredMusicApp}.`);
  if (p.frequentApps.length) parts.push(`Frequent apps: ${p.frequentApps.join(', ')}.`);
  if (p.preferredLanguage) parts.push(`Preferred language: ${p.preferredLanguage}.`);
  if (p.responseStyle) parts.push(`Response style: ${p.responseStyle}.`);
  if (p.customNotes) parts.push(`User preferences: ${p.customNotes}.`);
  return parts.join(' ');
}
