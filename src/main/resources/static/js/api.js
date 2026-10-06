// Thin wrappers around the HTTP API. Errors carry the server's problem detail when there is one.

async function failure(response) {
  let detail = response.statusText;
  try {
    const body = await response.json();
    detail = body.detail || body.title || detail;
  } catch (e) { /* not JSON */ }
  const error = new Error(detail || `HTTP ${response.status}`);
  error.status = response.status;
  return error;
}

export async function getJson(path) {
  const response = await fetch(path);
  if (!response.ok) throw await failure(response);
  return response.json();
}

export const status = () => getJson('/api/status');
export const listDocuments = () => getJson('/api/documents');
export const getPage = (id, page) => getJson(`/api/documents/${id}/pages/${page}`);

export async function uploadDocument(file) {
  const form = new FormData();
  form.append('file', file);
  const response = await fetch('/api/documents', { method: 'POST', body: form });
  if (!response.ok) throw await failure(response);
  return response.json();
}

export async function deleteDocument(id) {
  const response = await fetch(`/api/documents/${id}`, { method: 'DELETE' });
  if (!response.ok) throw await failure(response);
}

/**
 * Posts a question and calls onEvent(name, data) for every Server-Sent Event as it arrives.
 * fetch is used instead of EventSource because the request needs a JSON body.
 */
export async function ask(body, onEvent, signal) {
  const response = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body),
    signal,
  });
  if (!response.ok) throw await failure(response);
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += value;
    let end;
    while ((end = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const block = buffer.slice(0, end);
      buffer = buffer.slice(end).replace(/^\r?\n\r?\n/, '');
      dispatch(block, onEvent);
    }
  }
  if (buffer.trim()) dispatch(buffer, onEvent);
}

function dispatch(block, onEvent) {
  let name = 'message';
  const data = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) name = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
  }
  if (data.length) onEvent(name, JSON.parse(data.join('\n')));
}
