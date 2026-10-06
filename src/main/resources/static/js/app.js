import { status } from './api.js';
import { createChat } from './chat.js';
import { createLibrary } from './library.js';
import { createSourcePanel } from './source.js';

const debugToggle = document.getElementById('debug-toggle');
const themeToggle = document.getElementById('theme-toggle');
const modeBadge = document.getElementById('mode-badge');

const sourcePanel = createSourcePanel();
let chat = null;
const library = createLibrary({ onChange: () => chat?.updateScope() });
chat = createChat({ library, sourcePanel, debugEnabled: () => debugToggle.checked });

try {
  debugToggle.checked = localStorage.getItem('debug') === '1';
} catch (e) { /* storage unavailable */ }
debugToggle.addEventListener('change', () => {
  try { localStorage.setItem('debug', debugToggle.checked ? '1' : '0'); } catch (e) { /* storage unavailable */ }
});

themeToggle.addEventListener('click', () => {
  const root = document.documentElement;
  const dark = (root.dataset.theme || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')) === 'dark';
  root.dataset.theme = dark ? 'light' : 'dark';
  try { localStorage.setItem('theme', root.dataset.theme); } catch (e) { /* storage unavailable */ }
});

status().then((info) => {
  modeBadge.hidden = false;
  modeBadge.className = `badge ${info.mode}`;
  modeBadge.textContent = info.mode === 'generative' ? `Generative: ${info.chatModel}` : 'Extractive mode (no API key)';
  modeBadge.title = `Embeddings: ${info.embeddingModel} (${info.embeddingDimension} dimensions)`;
}).catch(() => { /* the library still works; the badge stays hidden */ });
