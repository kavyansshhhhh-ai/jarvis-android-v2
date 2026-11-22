import React from 'react';
import { GlassCard, GlassDivider } from '../components/GlassPrimitives';
import { ActivityEntry } from '../lib/types';

export const ActivityScreen: React.FC<{
  entries: ActivityEntry[];
  onClear: () => void;
}> = ({ entries, onClear }) => {
  return (
    <div className="mx-auto max-w-5xl px-4 sm:px-6 pt-5 pb-32 space-y-4">
      <header className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-[var(--text-primary)]">Activity</h1>
          <p className="mt-1 text-xs text-[var(--text-muted)]">
            What was done, not what was said. Cleared when the session closes.
          </p>
        </div>
        {entries.length > 0 && (
          <button
            type="button"
            onClick={onClear}
            className="min-h-[40px] px-3 text-sm font-semibold text-[var(--accent-error)] hover:opacity-80 cursor-pointer"
          >
            Clear
          </button>
        )}
      </header>

      {entries.length === 0 ? (
        <div className="flex min-h-[45vh] items-center justify-center">
          <GlassCard className="px-6 py-5 text-center">
            <p className="text-sm text-[var(--text-muted)]">Nothing yet. What you ask shows up here.</p>
          </GlassCard>
        </div>
      ) : (
        <GlassCard>
          {entries.map((entry, idx) => (
            <React.Fragment key={entry.id}>
              <div className="flex items-center justify-between gap-4 py-3">
                <div className="min-w-0 flex-1">
                  <div className="text-[15.5px] font-semibold text-[var(--text-primary)]">{entry.what}</div>
                  <div className="mt-0.5 text-xs text-[var(--text-muted)]">{entry.result}</div>
                </div>
                <div className="text-right shrink-0">
                  <div
                    className={`text-xs font-semibold ${
                      entry.local ? 'text-[var(--accent-cyan)]' : 'text-[var(--accent-amber)]'
                    }`}
                  >
                    {entry.local ? 'Local' : 'Gemini'}
                  </div>
                  <div className="mt-0.5 text-[11px] text-[var(--text-muted)] tabular-nums">
                    {new Date(entry.at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}
                  </div>
                </div>
              </div>
              {idx < entries.length - 1 && <GlassDivider />}
            </React.Fragment>
          ))}
        </GlassCard>
      )}
    </div>
  );
};
