import { useEffect, useState } from 'react';
import { useAuthStore } from '../store/authStore';
import { useBookmarkStore } from '../store/bookmarkStore';
import { MyBookmarksPanel } from './MyBookmarksPanel';

export function AccountControl() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const email = useAuthStore((s) => s.email);
  const name = useAuthStore((s) => s.name);
  const pictureUrl = useAuthStore((s) => s.pictureUrl);
  const logout = useAuthStore((s) => s.logout);
  const fetchBookmarks = useBookmarkStore((s) => s.fetchAll);
  const clearBookmarks = useBookmarkStore((s) => s.clear);

  const [showBookmarks, setShowBookmarks] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    if (isAuthenticated) fetchBookmarks();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthenticated]);

  const handleLogout = () => {
    logout();
    clearBookmarks();
    setMenuOpen(false);
  };

  // AccountControl only ever renders inside AppLayout, which RequireAuth already gates -
  // by the time this mounts isAuthenticated is guaranteed true.
  const initial = (name || email || '?').charAt(0).toUpperCase();

  return (
    <div className="relative">
      <button onClick={() => setMenuOpen((v) => !v)} className="flex items-center gap-2">
        {pictureUrl ? (
          <img src={pictureUrl} alt="" className="h-6 w-6 rounded-full" referrerPolicy="no-referrer" />
        ) : (
          <span
            className="flex h-6 w-6 items-center justify-center rounded-full text-[11px] font-semibold"
            style={{ background: 'var(--accent-soft)', color: 'var(--accent)' }}
          >
            {initial}
          </span>
        )}
        <span className="font-mono-data text-xs" style={{ color: 'var(--text-dim)' }}>{name || email}</span>
      </button>

      {menuOpen && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setMenuOpen(false)} />
          <div
            className="absolute right-0 top-full mt-2 w-44 border z-20"
            style={{ background: 'var(--panel)', borderColor: 'var(--hairline)' }}
          >
            <button
              onClick={() => { setShowBookmarks(true); setMenuOpen(false); }}
              className="w-full text-left px-3 py-2 text-xs transition-colors hover:bg-[var(--panel-2)]"
              style={{ color: 'var(--text)' }}
            >
              My Bookmarks
            </button>
            <button
              onClick={handleLogout}
              className="w-full text-left px-3 py-2 text-xs transition-colors hover:bg-[var(--panel-2)]"
              style={{ color: 'var(--negative)' }}
            >
              Logout
            </button>
          </div>
        </>
      )}

      {showBookmarks && <MyBookmarksPanel onClose={() => setShowBookmarks(false)} />}
    </div>
  );
}
