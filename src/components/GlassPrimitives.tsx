import React from 'react';

export const GlassCard: React.FC<{
  children: React.ReactNode;
  className?: string;
}> = ({ children, className = '' }) => (
  <div className={`glass-card rounded-3xl px-4 py-3 ${className}`}>{children}</div>
);

export const GlassDivider: React.FC = () => <div className="glass-divider my-0.5" />;

export const Section: React.FC<{
  title?: string;
  children: React.ReactNode;
  className?: string;
}> = ({ title, children, className = '' }) => (
  <section className={`w-full ${className}`}>
    {title && (
      <h2 className="text-[13.5px] font-medium tracking-wide text-[var(--text-muted)] px-2 pb-2">
        {title}
      </h2>
    )}
    <GlassCard>{children}</GlassCard>
  </section>
);

export const IosGlassSwitch: React.FC<{
  checked: boolean;
  disabled?: boolean;
  label: string;
  onChange: (next: boolean) => void;
}> = ({ checked, disabled = false, label, onChange }) => (
  <button
    type="button"
    role="switch"
    aria-checked={checked}
    aria-label={label}
    disabled={disabled}
    onClick={() => !disabled && onChange(!checked)}
    className={`relative inline-flex h-[30px] w-[50px] shrink-0 items-center rounded-full border transition-colors duration-200 focus-visible:outline-2 focus-visible:outline-cyan-400 ${
      disabled ? 'opacity-45 cursor-not-allowed' : 'cursor-pointer'
    } ${
      checked
        ? 'border-cyan-400/70 bg-gradient-to-r from-[var(--accent-cyan)] to-[var(--accent-violet)]'
        : 'border-[var(--border-glass-strong)] bg-black/10 dark:bg-white/10'
    }`}
  >
    <span
      className={`inline-block h-[24px] w-[24px] rounded-full bg-white shadow-md transition-transform duration-200 ${
        checked ? 'translate-x-[22px]' : 'translate-x-[2px]'
      }`}
    />
  </button>
);

export const SwitchRow: React.FC<{
  title: string;
  detail: string;
  checked: boolean;
  disabled?: boolean;
  icon?: React.ReactNode;
  onChange: (next: boolean) => void;
}> = ({ title, detail, checked, disabled = false, icon, onChange }) => (
  <div
    onClick={() => !disabled && onChange(!checked)}
    className={`flex items-center justify-between gap-3 py-3 ${disabled ? 'cursor-not-allowed' : 'cursor-pointer'}`}
  >
    <div className="flex items-center gap-3 min-w-0 flex-1">
      {icon && (
        <div
          className={`flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-[10px] border ${
            checked && !disabled
              ? 'border-cyan-400/40 bg-cyan-400/15 text-[var(--accent-cyan)]'
              : 'border-[var(--border-glass)] bg-black/5 dark:bg-white/5 text-[var(--text-muted)]'
          }`}
        >
          {icon}
        </div>
      )}
      <div className="min-w-0 flex-1">
        <div className={`text-[15.5px] font-medium leading-snug ${disabled ? 'text-[var(--text-muted)]' : 'text-[var(--text-primary)]'}`}>
          {title}
        </div>
        <div className="text-[12.5px] leading-relaxed text-[var(--text-muted)] mt-0.5">{detail}</div>
      </div>
    </div>
    <IosGlassSwitch checked={checked} disabled={disabled} label={title} onChange={onChange} />
  </div>
);

export const InfoRow: React.FC<{
  title: string;
  detail: string;
  icon?: React.ReactNode;
  trailing?: React.ReactNode;
  onClick?: () => void;
}> = ({ title, detail, icon, trailing, onClick }) => {
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag
      type={onClick ? 'button' : undefined}
      onClick={onClick}
      className={`flex w-full items-center justify-between gap-3 py-3 text-left ${
        onClick ? 'cursor-pointer hover:opacity-90 transition-opacity' : ''
      }`}
    >
      <div className="flex items-center gap-3 min-w-0 flex-1">
        {icon && (
          <div className="flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-[10px] border border-[var(--border-glass)] bg-black/5 dark:bg-white/5 text-[var(--accent-cyan)]">
            {icon}
          </div>
        )}
        <div className="min-w-0 flex-1">
          <div className="text-[15.5px] font-medium leading-snug text-[var(--text-primary)]">{title}</div>
          <div className="text-[12.5px] leading-relaxed text-[var(--text-muted)] mt-0.5 whitespace-pre-line">
            {detail}
          </div>
        </div>
      </div>
      {trailing && <div className="shrink-0">{trailing}</div>}
    </Tag>
  );
};

export const StatusLabel: React.FC<{
  text: string;
  tone?: 'cyan' | 'amber' | 'coral' | 'muted';
}> = ({ text, tone = 'muted' }) => {
  const colorClass =
    tone === 'cyan'
      ? 'text-[var(--accent-cyan)]'
      : tone === 'amber'
        ? 'text-[var(--accent-amber)]'
        : tone === 'coral'
          ? 'text-[var(--accent-error)]'
          : 'text-[var(--text-muted)]';

  return <span className={`text-xs font-semibold whitespace-nowrap ${colorClass}`}>{text}</span>;
};

export const WakeIndicatorDot: React.FC<{
  visible: boolean;
  sizeDp: number;
}> = ({ visible, sizeDp }) => {
  if (!visible) return null;
  const px = Math.max(9, Math.min(50, sizeDp));
  return (
    <div
      aria-label="Wake word triggered"
      className="fixed top-3 left-1/2 -translate-x-1/2 z-50 pointer-events-none flex items-center justify-center"
    >
      <div
        style={{ width: px * 2.2, height: px * 2.2 }}
        className="rounded-full bg-cyan-400/20 blur-md animate-pulse flex items-center justify-center"
      />
      <div
        style={{ width: px, height: px }}
        className="absolute rounded-full bg-gradient-to-br from-cyan-300 to-violet-400 border border-white/60 shadow-[0_0_14px_rgba(56,229,255,0.75)]"
      />
    </div>
  );
};
