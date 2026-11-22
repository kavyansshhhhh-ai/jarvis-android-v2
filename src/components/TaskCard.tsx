import React from 'react';
import { ExternalLink, ShieldAlert } from 'lucide-react';
import { TaskItem, TaskState } from '../lib/types';

function stateMeta(state: TaskState): { label: string; toneClass: string } {
  switch (state) {
    case 'Understanding':
      return { label: 'Understanding', toneClass: 'text-[var(--accent-cyan)]' };
    case 'Planning':
      return { label: 'Needs your answer', toneClass: 'text-[var(--accent-amber)]' };
    case 'AwaitingConfirmation':
      return { label: 'Awaiting your OK', toneClass: 'text-[var(--accent-amber)]' };
    case 'Executing':
      return { label: 'Working', toneClass: 'text-[var(--accent-cyan)]' };
    case 'WaitingForYou':
      return { label: 'Your turn', toneClass: 'text-[var(--accent-amber)]' };
    case 'Completed':
      return { label: 'Done', toneClass: 'text-[var(--accent-cyan)]' };
    case 'Failed':
      return { label: "Didn't work", toneClass: 'text-[var(--accent-error)]' };
  }
}

export const TaskCard: React.FC<{
  task: TaskItem;
  onConfirm?: (task: TaskItem) => void;
  onCancel?: (task: TaskItem) => void;
}> = ({ task, onConfirm, onCancel }) => {
  const { label, toneClass } = stateMeta(task.state);
  const needsAction =
    task.state === 'AwaitingConfirmation' ||
    task.state === 'WaitingForYou' ||
    Boolean(task.pendingAction?.url);

  return (
    <div className="glass-card rounded-3xl p-4 space-y-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-[15.5px] font-semibold text-[var(--text-primary)]">{task.title}</div>
          <div className="mt-0.5 flex items-center gap-2 text-xs text-[var(--text-muted)]">
            <span className={`font-semibold ${toneClass}`}>{label}</span>
            {(task.risk === 'High' || task.risk === 'Financial') && (
              <>
                <span aria-hidden="true">·</span>
                <span className="inline-flex items-center gap-1 font-medium text-[var(--accent-amber)]">
                  <ShieldAlert className="h-3.5 w-3.5" />
                  {task.risk === 'Financial' ? 'Payment: you' : 'Needs you'}
                </span>
              </>
            )}
          </div>
        </div>
      </div>

      {task.steps.length > 0 && (
        <div className="space-y-1.5">
          {task.steps.map((step, idx) => (
            <div key={idx} className="flex items-center gap-2.5 text-sm">
              <span
                className={`h-2.5 w-2.5 shrink-0 rounded-full ${
                  step.done === true
                    ? 'bg-[var(--accent-cyan)] shadow-[0_0_8px_var(--accent-cyan)]'
                    : step.done === false
                      ? 'bg-[var(--accent-error)]'
                      : 'border-2 border-[var(--accent-cyan)] animate-pulse'
                }`}
              />
              <span
                className={
                  step.done === false
                    ? 'text-[var(--accent-error)]'
                    : step.done === true
                      ? 'text-[var(--text-primary)]'
                      : 'text-[var(--accent-cyan)]'
                }
              >
                {idx + 1}. {step.label}
              </span>
            </div>
          ))}
        </div>
      )}

      {task.detail && (
        <div className="rounded-xl bg-black/10 dark:bg-white/10 px-3 py-2 text-xs text-[var(--text-muted)]">
          {task.detail}
        </div>
      )}

      {needsAction && task.pendingAction && (
        <div className="flex flex-wrap items-center justify-end gap-2 pt-1">
          {onCancel && task.state !== 'Completed' && (
            <button
              type="button"
              onClick={() => onCancel(task)}
              className="min-h-[40px] rounded-full border border-[var(--border-glass)] px-4 py-2 text-xs font-semibold text-[var(--text-muted)] hover:text-[var(--text-primary)] cursor-pointer transition-colors"
            >
              Cancel
            </button>
          )}
          {task.pendingAction.url ? (
            <a
              href={task.pendingAction.url}
              target="_blank"
              rel="noopener noreferrer"
              onClick={() => onConfirm?.(task)}
              className="inline-flex min-h-[40px] items-center gap-1.5 rounded-full bg-[var(--accent-cyan)] px-5 py-2 text-xs font-bold text-[#001E26] hover:opacity-90 transition-opacity"
            >
              {task.pendingAction.confirmLabel}
              <ExternalLink className="h-3.5 w-3.5" />
            </a>
          ) : (
            onConfirm && (
              <button
                type="button"
                onClick={() => onConfirm(task)}
                className="min-h-[40px] rounded-full bg-[var(--accent-cyan)] px-5 py-2 text-xs font-bold text-[#001E26] cursor-pointer hover:opacity-90 transition-opacity"
              >
                {task.pendingAction.confirmLabel}
              </button>
            )
          )}
        </div>
      )}
    </div>
  );
};
