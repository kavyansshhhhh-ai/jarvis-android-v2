import React from 'react';
import { Home, List, Settings, Sparkles } from 'lucide-react';

export type AppTab = 'Home' | 'Automations' | 'Activity' | 'Settings';

const TABS: Array<{ id: AppTab; label: string; icon: React.ComponentType<{ className?: string }> }> = [
  { id: 'Home', label: 'Home', icon: Home },
  { id: 'Automations', label: 'Automations', icon: Sparkles },
  { id: 'Activity', label: 'Activity', icon: List },
  { id: 'Settings', label: 'Settings', icon: Settings },
];

export const LiquidDock: React.FC<{
  activeTab: AppTab;
  onSelect: (tab: AppTab) => void;
}> = ({ activeTab, onSelect }) => {
  const activeIdx = Math.max(
    0,
    TABS.findIndex((t) => t.id === activeTab),
  );

  return (
    <nav
      aria-label="Primary navigation"
      className="fixed bottom-3 left-1/2 -translate-x-1/2 z-40 w-[calc(100%-24px)] max-w-lg"
    >
      <div className="glass-dock relative grid grid-cols-4 items-center rounded-[32px] p-1.5 shadow-[0_16px_40px_rgba(0,0,0,0.45)]">
        {/* Sliding Liquid Glass Lens */}
        <div
          aria-hidden="true"
          style={{
            width: 'calc((100% - 12px) / 4)',
            transform: `translateX(${activeIdx * 100}%)`,
          }}
          className="pointer-events-none absolute left-1.5 top-1.5 bottom-1.5 rounded-[26px] border border-white/30 bg-gradient-to-b from-white/20 to-white/5 shadow-[inset_0_1px_1px_rgba(255,255,255,0.28)] transition-transform duration-200 ease-out"
        />

        {TABS.map((tab) => {
          const Icon = tab.icon;
          const selected = tab.id === activeTab;
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={selected}
              onClick={() => onSelect(tab.id)}
              className={`relative z-10 flex min-h-[46px] flex-col items-center justify-center gap-0.5 rounded-[26px] px-2 py-1 transition-colors duration-150 cursor-pointer focus-visible:outline-2 focus-visible:outline-cyan-400 ${
                selected ? 'text-[var(--text-primary)]' : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
              }`}
            >
              <Icon className="h-[19px] w-[19px]" />
              <span className={`text-[11px] leading-tight whitespace-nowrap ${selected ? 'font-semibold' : 'font-medium'}`}>
                {tab.label}
              </span>
            </button>
          );
        })}
      </div>
    </nav>
  );
};
