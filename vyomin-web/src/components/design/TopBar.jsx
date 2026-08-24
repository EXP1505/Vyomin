import { Logo } from './Logo';

export function TopBar({ navItems, activeTab, onNavigate, statusLabel, statusOk = true, children }) {
  return (
    <header
      className="flex h-14 shrink-0 items-center justify-between border-b px-5"
      style={{ borderColor: 'var(--hairline)', background: 'var(--panel-2)' }}
    >
      <div className="flex items-center gap-3">
        <Logo size={36} />
        <span className="text-sm font-semibold tracking-[0.2em]" style={{ color: 'var(--text)' }}>
          VYOMIN
        </span>
      </div>

      {navItems && (
        <nav className="flex items-center gap-1">
          {navItems.map((item) => (
            <button
              key={item.key}
              onClick={() => onNavigate?.(item.key)}
              className="px-3 py-1.5 text-xs tracking-wide transition-colors"
              style={{
                color: activeTab === item.key ? 'var(--accent)' : 'var(--text-dim)',
                background: activeTab === item.key ? 'var(--accent-soft)' : 'transparent',
                borderBottom: activeTab === item.key ? '2px solid var(--accent)' : '2px solid transparent',
              }}
            >
              {item.label}
            </button>
          ))}
        </nav>
      )}

      <div className="flex items-center gap-4 font-mono-data text-xs" style={{ color: 'var(--text-dim)' }}>
        {children}
        {statusLabel && (
          <span className="flex items-center gap-2">
            <span
              className="h-1.5 w-1.5 rounded-full"
              style={{ background: statusOk ? 'var(--positive)' : 'var(--negative)' }}
            />
            {statusLabel}
          </span>
        )}
      </div>

      <style>{`
        @keyframes vyomin-pulse {
          0%, 100% { opacity: 1; transform: rotate(45deg) scale(1); }
          50% { opacity: 0.45; transform: rotate(45deg) scale(0.85); }
        }
      `}</style>
    </header>
  );
}
