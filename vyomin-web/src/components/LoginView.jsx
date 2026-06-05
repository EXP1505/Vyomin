import { useState } from 'react';
import { useAuthStore } from '../store/authStore'; 

export const LoginView = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const login = useAuthStore((state) => state.login);

  const [status, setStatus] = useState(null);

  const handleLogin = async (e) => {
    e.preventDefault();
    setStatus('authenticating');
    try {
      const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      });

      if (response.ok) {
        const data = await response.json();
        login(data.token, email);
        setStatus('success');
      } else {
        setStatus('failed');
      }
    } catch (error) {
      console.error("Network error during login", error);
      setStatus('error');
    }
  };

  return (
    <div className="flex h-full items-center justify-center bg-slate-900 rounded-lg shadow-lg border border-slate-700 p-6">
      <div className="w-full max-w-md bg-slate-800 p-8 rounded-xl border border-slate-600 shadow-2xl">
        <h2 className="text-2xl font-bold text-emerald-400 mb-6 text-center">Command Center Access</h2>
        <form onSubmit={handleLogin} className="space-y-4">
          <div>
            <label className="block text-slate-400 text-sm mb-1">Operative Email</label>
            <input 
              type="email" 
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full bg-slate-900 border border-slate-600 rounded px-4 py-2 text-white focus:outline-none focus:border-emerald-500"
              required
            />
          </div>
          <div>
            <label className="block text-slate-400 text-sm mb-1">Passcode</label>
            <input 
              type="password" 
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full bg-slate-900 border border-slate-600 rounded px-4 py-2 text-white focus:outline-none focus:border-emerald-500"
              required
            />
          </div>
          <button 
            type="submit" 
            className="w-full bg-emerald-600 hover:bg-emerald-500 text-white font-bold py-2 px-4 rounded transition-colors mt-4"
          >
            {status === 'authenticating' ? 'Authenticating...' : 'Authenticate'}
          </button>
          
          {status === 'success' && (
            <div className="mt-4 p-3 bg-emerald-900/50 border border-emerald-500 text-emerald-400 rounded text-center text-sm">
              Authentication Successful! You may now access the Radar and Intelligence Graph.
            </div>
          )}
          {status === 'failed' && (
            <div className="mt-4 p-3 bg-red-900/50 border border-red-500 text-red-400 rounded text-center text-sm">
              Authentication Failed. Invalid Operative Email or Passcode.
            </div>
          )}
          {status === 'error' && (
            <div className="mt-4 p-3 bg-red-900/50 border border-red-500 text-red-400 rounded text-center text-sm">
              Network Error. Could not connect to Command Center Core.
            </div>
          )}
        </form>
      </div>
    </div>
  );
};
