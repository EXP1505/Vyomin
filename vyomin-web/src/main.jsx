import { createRoot } from 'react-dom/client'
import { GoogleOAuthProvider } from '@react-oauth/google'
import './index.css'
import App from './App.jsx'

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || ''

// StrictMode's dev-only double-invoked effects corrupt @react-oauth/google's internal DOM
// refs (it calls google.accounts.id.initialize() twice), causing a later unrelated re-render
// to crash with "Failed to execute 'removeChild' on 'Node'". Production builds don't use
// StrictMode's double-invocation, so this is dev-only either way.
createRoot(document.getElementById('root')).render(
  <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
    <App />
  </GoogleOAuthProvider>,
)
