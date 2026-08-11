import { useState } from 'react';
import { GoogleLogin } from '@react-oauth/google';

export function AuthPanel({ onSuccess }) {
  const [mode, setMode] = useState('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [status, setStatus] = useState(null);
  const [errorMsg, setErrorMsg] = useState(null);

  const submit = async (e) => {
    e.preventDefault();
    setStatus('working');
    setErrorMsg(null);
    try {
      const endpoint = mode === 'login' ? '/api/auth/login' : '/api/auth/signup';
      const res = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(typeof data === 'string' ? data : data?.message || `${mode === 'login' ? 'Login' : 'Registration'} failed`);
      }

      onSuccess(data);
    } catch (err) {
      setErrorMsg(err.message || 'Something went wrong');
      setStatus('idle');
    }
  };

  const handleGoogleSuccess = async (credentialResponse) => {
    setErrorMsg(null);
    try {
      const res = await fetch('/api/auth/google', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ idToken: credentialResponse.credential }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(typeof data === 'string' ? data : 'Google sign-in failed');
      onSuccess(data);
    } catch (err) {
      setErrorMsg(err.message || 'Google sign-in failed');
    }
  };

  return (
    <div className="w-full max-w-sm border" style={{ background: 'var(--panel)', borderColor: 'var(--hairline)' }}>
      <div className="p-4 flex items-center gap-1 border-b" style={{ borderColor: 'var(--hairline)' }}>
        {['login', 'register'].map((m) => (
          <button
            key={m}
            onClick={() => { setMode(m); setErrorMsg(null); }}
            className="font-mono-data text-xs px-3 py-1.5 border transition-colors"
            style={
              mode === m
                ? { borderColor: 'var(--accent)', color: 'var(--accent)', background: 'var(--accent-soft)' }
                : { borderColor: 'var(--hairline)', color: 'var(--text-dim)' }
            }
          >
            {m === 'login' ? 'Log In' : 'Register'}
          </button>
        ))}
      </div>

      <div className="p-5">
        <form onSubmit={submit} className="space-y-3">
          <div>
            <label className="block text-xs mb-1" style={{ color: 'var(--text-dim)' }}>Email</label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              className="w-full border px-3 py-2 text-sm focus:outline-none"
              style={{ background: 'var(--panel-2)', borderColor: 'var(--hairline)', color: 'var(--text)' }}
            />
          </div>
          <div>
            <label className="block text-xs mb-1" style={{ color: 'var(--text-dim)' }}>Password</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={6}
              className="w-full border px-3 py-2 text-sm focus:outline-none"
              style={{ background: 'var(--panel-2)', borderColor: 'var(--hairline)', color: 'var(--text)' }}
            />
          </div>

          {errorMsg && (
            <div className="text-xs px-3 py-2 border" style={{ background: 'rgba(255,92,108,0.1)', borderColor: 'var(--negative)', color: 'var(--negative)' }}>
              {errorMsg}
            </div>
          )}

          <button
            type="submit"
            disabled={status === 'working'}
            className="w-full font-mono-data font-semibold px-4 py-2 border transition-colors disabled:opacity-50"
            style={{ borderColor: 'var(--accent)', color: 'var(--accent)', background: 'var(--accent-soft)' }}
          >
            {status === 'working' ? 'Please wait…' : mode === 'login' ? 'Log In' : 'Create Account'}
          </button>
        </form>

        <div className="flex items-center gap-2 my-4">
          <div className="flex-1 h-px" style={{ background: 'var(--hairline)' }} />
          <span className="text-[10px] uppercase tracking-wide" style={{ color: 'var(--text-faint)' }}>or</span>
          <div className="flex-1 h-px" style={{ background: 'var(--hairline)' }} />
        </div>

        <div className="flex justify-center">
          <GoogleLogin
            onSuccess={handleGoogleSuccess}
            onError={() => setErrorMsg('Google sign-in failed')}
            theme="filled_black"
            size="medium"
          />
        </div>
      </div>
    </div>
  );
}
