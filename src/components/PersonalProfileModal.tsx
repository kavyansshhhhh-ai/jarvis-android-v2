import React, { useState } from 'react';
import { EMPTY_PROFILE, isProfileEmpty } from '../lib/personalProfile';
import { PersonalProfile } from '../lib/types';

function splitCsv(raw: string): string[] {
  return Array.from(
    new Set(
      raw
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean),
    ),
  );
}

export const PersonalProfileModal: React.FC<{
  initial: PersonalProfile;
  onSave: (profile: PersonalProfile) => void;
  onClear: () => void;
  onClose: () => void;
}> = ({ initial, onSave, onClear, onClose }) => {
  const [preferredName, setPreferredName] = useState(initial.preferredName);
  const [addressAs, setAddressAs] = useState(initial.addressAs);
  const [favoriteSongs, setFavoriteSongs] = useState(initial.favoriteSongs.join(', '));
  const [favoriteArtists, setFavoriteArtists] = useState(initial.favoriteArtists.join(', '));
  const [favoriteGenres, setFavoriteGenres] = useState(initial.favoriteGenres.join(', '));
  const [preferredMusicApp, setPreferredMusicApp] = useState(initial.preferredMusicApp);
  const [frequentApps, setFrequentApps] = useState(initial.frequentApps.join(', '));
  const [preferredLanguage, setPreferredLanguage] = useState(initial.preferredLanguage);
  const [responseStyle, setResponseStyle] = useState(initial.responseStyle);
  const [customNotes, setCustomNotes] = useState(initial.customNotes);

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault();
    onSave({
      preferredName,
      addressAs,
      favoriteSongs: splitCsv(favoriteSongs),
      favoriteArtists: splitCsv(favoriteArtists),
      favoriteGenres: splitCsv(favoriteGenres),
      preferredMusicApp,
      frequentApps: splitCsv(frequentApps),
      preferredLanguage,
      responseStyle,
      customNotes,
    });
    onClose();
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-md p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="profile-modal-title"
    >
      <form
        onSubmit={handleSave}
        className="glass-card flex max-h-[88vh] w-full max-w-lg flex-col rounded-[28px] p-5 shadow-2xl"
      >
        <h2 id="profile-modal-title" className="text-xl font-bold text-[var(--text-primary)]">
          Personal Profile
        </h2>
        <p className="mt-1 text-xs leading-relaxed text-[var(--text-muted)]">
          Saved privately in your browser only. Helps JARVIS play your favourite songs, pick your music app, and match your style.
        </p>

        <div className="mt-4 flex-1 space-y-3 overflow-y-auto pr-1">
          <Field label="Preferred name" placeholder="e.g. Alex" value={preferredName} onChange={setPreferredName} />
          <Field label="Address you as" placeholder="e.g. Boss, Sir, or first name" value={addressAs} onChange={setAddressAs} />
          <Field label="Favourite songs (comma-separated)" placeholder="e.g. Kesariya, Blinding Lights" value={favoriteSongs} onChange={setFavoriteSongs} />
          <Field label="Favourite artists (comma-separated)" placeholder="e.g. Arijit Singh, The Weeknd" value={favoriteArtists} onChange={setFavoriteArtists} />
          <Field label="Favourite genres" placeholder="e.g. Lo-fi, Indie, Sufi" value={favoriteGenres} onChange={setFavoriteGenres} />
          <Field label="Preferred music app" placeholder="e.g. Spotify or YouTube Music" value={preferredMusicApp} onChange={setPreferredMusicApp} />
          <Field label="Frequent apps" placeholder="e.g. WhatsApp, Spotify, Maps" value={frequentApps} onChange={setFrequentApps} />
          <Field label="Preferred language" placeholder="e.g. Hinglish, English, Hindi" value={preferredLanguage} onChange={setPreferredLanguage} />
          <Field label="Response style" placeholder="e.g. Short and direct" value={responseStyle} onChange={setResponseStyle} />
          <Field label="Other harmless preferences" placeholder="Anything helpful to remember" value={customNotes} onChange={setCustomNotes} />
        </div>

        <div className="mt-5 flex items-center justify-between gap-2 pt-2 border-t border-[var(--divider-glass)]">
          {!isProfileEmpty(initial) ? (
            <button
              type="button"
              onClick={() => {
                onClear();
                onSave(EMPTY_PROFILE);
                onClose();
              }}
              className="min-h-[40px] px-3 text-sm font-semibold text-[var(--accent-error)] cursor-pointer hover:opacity-85"
            >
              Clear
            </button>
          ) : (
            <span />
          )}
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="min-h-[40px] rounded-full px-4 py-2 text-sm font-medium text-[var(--text-muted)] hover:text-[var(--text-primary)] cursor-pointer"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="min-h-[40px] rounded-full bg-[var(--accent-cyan)] px-6 py-2 text-sm font-bold text-[#001E26] cursor-pointer hover:opacity-90"
            >
              Save
            </button>
          </div>
        </div>
      </form>
    </div>
  );
};

const Field: React.FC<{
  label: string;
  placeholder: string;
  value: string;
  onChange: (val: string) => void;
}> = ({ label, placeholder, value, onChange }) => (
  <label className="block">
    <span className="block text-xs font-medium text-[var(--text-muted)] mb-1">{label}</span>
    <input
      type="text"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      className="w-full rounded-2xl border border-[var(--border-glass)] bg-black/5 dark:bg-white/5 px-3.5 py-2.5 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-muted)]/60 focus:border-[var(--accent-cyan)] focus:outline-none"
    />
  </label>
);
