import { deleteDocument, listDocuments, uploadDocument } from './api.js';

const STAGES = { QUEUED: 'Queued', EXTRACTING: 'Extracting text', CHUNKING: 'Chunking', EMBEDDING: 'Embedding', READY: 'Ready', FAILED: 'Failed' };
const ACTIVE = new Set(['QUEUED', 'EXTRACTING', 'CHUNKING', 'EMBEDDING']);

/** Document library: drag-and-drop upload, live ingestion progress, and the choice of which documents a question covers. */
export function createLibrary({ onChange }) {
  const list = document.getElementById('documents');
  const empty = document.getElementById('documents-empty');
  const message = document.getElementById('upload-message');
  const dropzone = document.getElementById('dropzone');
  const input = document.getElementById('file-input');
  const selected = new Set();
  let documents = [];
  let timer = null;

  dropzone.addEventListener('click', () => input.click());
  dropzone.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); input.click(); }
  });
  input.addEventListener('change', () => { upload([...input.files]); input.value = ''; });
  for (const type of ['dragenter', 'dragover']) {
    dropzone.addEventListener(type, (event) => { event.preventDefault(); dropzone.classList.add('over'); });
  }
  for (const type of ['dragleave', 'drop']) {
    dropzone.addEventListener(type, (event) => { event.preventDefault(); dropzone.classList.remove('over'); });
  }
  dropzone.addEventListener('drop', (event) => upload([...event.dataTransfer.files]));

  async function upload(files) {
    message.className = 'note';
    message.textContent = '';
    for (const file of files) {
      if (!/\.pdf$/i.test(file.name)) { show(`${file.name}: only PDF files are accepted.`, true); continue; }
      try {
        const result = await uploadDocument(file);
        show(result.duplicate ? `${file.name} is already in the library.` : `${file.name} queued.`, false);
      } catch (error) {
        show(`${file.name}: ${error.message}`, true);
      }
    }
    await refresh();
  }

  function show(text, isError) {
    message.textContent = [message.textContent, text].filter(Boolean).join(' ');
    message.className = isError ? 'note error' : 'note';
  }

  async function refresh() {
    try {
      documents = await listDocuments();
    } catch (error) {
      show(`Could not load the library: ${error.message}`, true);
    }
    for (const id of [...selected]) if (!documents.some((doc) => doc.id === id)) selected.delete(id);
    render();
    onChange();
    clearTimeout(timer);
    timer = setTimeout(refresh, documents.some((doc) => ACTIVE.has(doc.status)) ? 1000 : 5000);
  }

  function render() {
    list.replaceChildren(...documents.map(row));
    empty.hidden = documents.length > 0;
  }

  function row(doc) {
    const item = document.createElement('li');
    item.className = 'doc';
    const top = document.createElement('div');
    top.className = 'doc-row';
    const check = document.createElement('input');
    check.type = 'checkbox';
    check.checked = selected.has(doc.id);
    check.disabled = doc.status !== 'READY';
    check.title = 'Restrict questions to the checked documents';
    check.setAttribute('aria-label', `Restrict questions to ${doc.filename}`);
    check.addEventListener('change', () => { check.checked ? selected.add(doc.id) : selected.delete(doc.id); onChange(); });
    const name = document.createElement('span');
    name.className = 'doc-name';
    name.textContent = doc.filename;
    const state = document.createElement('span');
    state.className = `status ${doc.status}`;
    state.textContent = STAGES[doc.status] || doc.status;
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'link-button';
    remove.textContent = 'Delete';
    remove.setAttribute('aria-label', `Delete ${doc.filename}`);
    remove.addEventListener('click', async () => {
      if (!confirm(`Delete ${doc.filename} and its chunks?`)) return;
      try { await deleteDocument(doc.id); } catch (error) { show(error.message, true); }
      refresh();
    });
    top.append(check, name, state, remove);
    item.append(top);

    const meta = document.createElement('div');
    meta.className = 'doc-meta';
    meta.textContent = [doc.pageCount ? `${doc.pageCount} pages` : null, doc.chunksTotal ? `${doc.chunksTotal} chunks` : null,
      doc.sample ? 'sample' : null].filter(Boolean).join(' - ');
    if (ACTIVE.has(doc.status)) {
      const detail = doc.status === 'EMBEDDING' ? `Embedding ${doc.chunksEmbedded}/${doc.chunksTotal}` : STAGES[doc.status];
      meta.textContent = [detail, meta.textContent].filter(Boolean).join(' - ');
      const bar = document.createElement('div');
      bar.className = 'progress';
      bar.setAttribute('role', 'progressbar');
      const fill = document.createElement('div');
      const fraction = doc.chunksTotal > 0 ? doc.chunksEmbedded / doc.chunksTotal : 0;
      fill.style.width = `${Math.round(fraction * 100)}%`;
      bar.append(fill);
      item.append(meta, bar);
    } else {
      item.append(meta);
    }
    if (doc.failureReason) {
      const reason = document.createElement('div');
      reason.className = 'doc-reason';
      reason.textContent = doc.failureReason;
      item.append(reason);
    }
    return item;
  }

  refresh();
  return { selectedIds: () => [...selected], hasDocuments: () => documents.some((doc) => doc.status === 'READY') };
}
