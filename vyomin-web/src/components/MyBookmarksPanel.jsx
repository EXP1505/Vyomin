import { useNavigate } from 'react-router-dom';
import { useBookmarkStore } from '../store/bookmarkStore';

export function MyBookmarksPanel({ onClose }) {
  const navigate = useNavigate();
  const stocks = useBookmarkStore((s) => Array.from(s.stocks));
  const entities = useBookmarkStore((s) => Array.from(s.entities.values()));
  const toggleStock = useBookmarkStore((s) => s.toggleStock);
  const toggleEntity = useBookmarkStore((s) => s.toggleEntity);

  const jumpToStock = () => {
    onClose();
    navigate('/finance');
  };

  const jumpToEntity = () => {
    onClose();
    navigate('/graph');
  };

  return (
    <div className="fixed inset-0 z-50">
      <div className="absolute inset-0 bg-black/70" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }} />
      <div className="absolute inset-0 flex items-center justify-center p-4">
        <div className="w-full max-w-lg max-h-[80vh] overflow-y-auto border" style={{ background: 'var(--panel)', borderColor: 'var(--hairline)' }}>
          <div className="p-4 flex items-center justify-between border-b sticky top-0" style={{ borderColor: 'var(--hairline)', background: 'var(--panel)' }}>
            <h3 className="text-sm font-semibold" style={{ color: 'var(--text)' }}>My Bookmarks</h3>
            <button onClick={onClose} className="text-sm" style={{ color: 'var(--text-dim)' }}>✕</button>
          </div>

          <div className="p-4 space-y-6">
            <div>
              <h4 className="text-xs font-semibold uppercase tracking-wide mb-2" style={{ color: 'var(--text-dim)' }}>Watched Stocks</h4>
              {stocks.length === 0 && (
                <div className="text-sm" style={{ color: 'var(--text-faint)' }}>No stocks bookmarked yet.</div>
              )}
              <div className="flex flex-col gap-1">
                {stocks.map((symbol) => (
                  <div
                    key={symbol}
                    className="flex items-center justify-between px-3 py-2 border-b last:border-b-0 text-sm"
                    style={{ borderColor: 'var(--hairline)' }}
                  >
                    <button onClick={jumpToStock} className="font-mono-data" style={{ color: 'var(--text)' }}>
                      {symbol}
                    </button>
                    <button onClick={() => toggleStock(symbol)} className="text-xs" style={{ color: 'var(--negative)' }}>
                      Remove
                    </button>
                  </div>
                ))}
              </div>
            </div>

            <div>
              <h4 className="text-xs font-semibold uppercase tracking-wide mb-2" style={{ color: 'var(--text-dim)' }}>Watched Countries &amp; Conflicts</h4>
              {entities.length === 0 && (
                <div className="text-sm" style={{ color: 'var(--text-faint)' }}>No countries or conflicts bookmarked yet.</div>
              )}
              <div className="flex flex-col gap-1">
                {entities.map((entity) => (
                  <div
                    key={`${entity.entityType}-${entity.entityId}`}
                    className="flex items-center justify-between px-3 py-2 border-b last:border-b-0 text-sm"
                    style={{ borderColor: 'var(--hairline)' }}
                  >
                    <button onClick={jumpToEntity} className="text-left" style={{ color: 'var(--text)' }}>
                      <span
                        className="font-mono-data text-[10px] uppercase mr-2"
                        style={{ color: entity.entityType === 'COUNTRY' ? '#5b8dee' : '#ffb020' }}
                      >
                        {entity.entityType}
                      </span>
                      {entity.entityName || entity.entityId}
                    </button>
                    <button
                      onClick={() => toggleEntity(entity.entityType, entity.entityId, entity.entityName)}
                      className="text-xs"
                      style={{ color: 'var(--negative)' }}
                    >
                      Remove
                    </button>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
