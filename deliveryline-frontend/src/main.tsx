import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './styles/globals.css';
import App from './App.tsx';

const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error(
    'deliveryline-frontend: missing #root element in index.html — the Spring Boot static-resource pipeline may have shipped a stale or partial dist.',
  );
}
createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
