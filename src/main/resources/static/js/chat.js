import { ask } from './api.js';

const MARKER = /\[(\d{1,3})\]/g;

/** Chat pane: streams an answer, then swaps in the server-verified text with clickable citation chips. */
export function createChat({ library, sourcePanel, getStatus, debugEnabled }) {
  const messages = document.getElementById('messages');
  const emptyState = document.getElementById('chat-empty');
  const form = document.getElementById('chat-form');
  const input = document.getElementById('question');
  const send = document.getElementById('send');
  const scopeNote = document.getElementById('scope-note');
  const history = [];
  let busy = false;

  form.addEventListener('submit', (event) => { event.preventDefault(); submit(input.value); });
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); form.requestSubmit(); }
  });
  for (const button of document.querySelectorAll('.example')) {
    button.addEventListener('click', () => submit(button.textContent));
  }

  function updateScope() {
    const ids = library.selectedIds();
    scopeNote.hidden = ids.length === 0;
    scopeNote.textContent = ids.length ? `Questions are limited to ${ids.length} checked document(s).` : '';
  }

  async function submit(raw) {
    const question = raw.trim();
    if (!question || busy) return;
    busy = true;
    send.disabled = true;
    emptyState.hidden = true;
    input.value = '';
    add('user').textContent = question;
    const bubble = add('assistant');
    const meta = el('div', 'msg-meta');
    const answer = el('div', 'answer pending');
    bubble.append(meta, answer);
    const state = { sources: [], raw: '' };
    try {
      await ask({
        question,
        history: history.slice(-6),
        documentIds: library.selectedIds(),
        debug: debugEnabled(),
      }, (name, data) => handle(name, data, { meta, answer, bubble, state }));
      history.push({ role: 'user', content: question }, { role: 'assistant', content: state.final || state.raw });
    } catch (error) {
      answer.classList.remove('pending');
      answer.textContent = `The question could not be answered: ${error.message}`;
    } finally {
      answer.classList.remove('pending');
      busy = false;
      send.disabled = false;
      input.focus();
    }
  }

  function handle(name, data, ui) {
    const { meta, answer, bubble, state } = ui;
    if (name === 'sources') {
      state.sources = data.sources;
      const mode = el('span', `badge ${data.mode}`);
      mode.textContent = data.mode === 'extractive' ? 'Extractive answer: quoted passages, no model' : 'Generated answer';
      meta.append(mode);
      if (data.rewrite !== 'none') {
        const rewrite = el('span', 'badge');
        rewrite.textContent = `searched as: ${data.standaloneQuestion}`;
        meta.append(rewrite);
      }
      if (data.candidates) bubble.append(debugTable(data.candidates));
    } else if (name === 'token') {
      state.raw += data.text;
      answer.textContent = state.raw;
    } else if (name === 'done') {
      state.final = data.answer;
      render(answer, data.answer, data.citations, state.sources);
      if (data.invalidCitations.length) flag(bubble, `Removed citation(s) that match no supplied source: ${data.invalidCitations.map((n) => `[${n}]`).join(' ')}.`);
      if (data.uncited) flag(bubble, 'This answer has no valid citation; treat it as unverified.');
      if (data.usage) {
        const usage = el('div', 'usage');
        usage.textContent = `${data.model || ''} - ${data.usage.inputTokens} input / ${data.usage.outputTokens} output tokens`;
        bubble.append(usage);
      }
      if (!data.notFound) bubble.append(sourceList(data.citations));
    } else if (name === 'error') {
      answer.textContent = state.raw ? `${state.raw}\n\n` : '';
      flag(bubble, data.message);
    }
    messages.scrollTop = messages.scrollHeight;
  }

  /** Replaces [n] in the verified answer with a chip that opens the cited passage. */
  function render(target, text, citations, sources) {
    target.replaceChildren();
    let last = 0;
    for (const match of text.matchAll(MARKER)) {
      target.append(text.slice(last, match.index));
      const n = Number(match[1]);
      const citation = citations.find((c) => c.n === n);
      const chip = el('button', citation ? 'chip' : 'chip missing');
      chip.type = 'button';
      chip.textContent = String(n);
      if (citation) {
        chip.title = `${citation.filename}, page ${citation.page}`;
        chip.addEventListener('click', () => openCitation(citation, chip));
      }
      target.append(chip);
      last = match.index + match[0].length;
    }
    target.append(text.slice(last));
    const first = citations[0];
    if (first) openCitation(first);
    else if (sources.length) openSource(sources[0]);
  }

  function sourceList(citations) {
    const list = el('ul', 'sources-list');
    for (const citation of citations) {
      const item = document.createElement('li');
      const button = el('button');
      button.type = 'button';
      button.textContent = `[${citation.n}] ${citation.filename}, page ${citation.page}: "${shorten(citation.quote)}"`;
      button.addEventListener('click', () => openCitation(citation));
      item.append(button);
      list.append(item);
    }
    return list;
  }

  function openCitation(citation, chip) {
    for (const active of document.querySelectorAll('.chip.active')) active.classList.remove('active');
    chip?.classList.add('active');
    sourcePanel.open({
      documentId: citation.documentId, filename: citation.filename, page: citation.page,
      heading: citation.heading, start: citation.quoteStart, end: citation.quoteEnd,
    });
  }

  function openSource(source) {
    sourcePanel.open({
      documentId: source.documentId, filename: source.filename, page: source.pageStart,
      heading: source.heading, start: source.charStart, end: source.charEnd,
    });
  }

  function debugTable(candidates) {
    const wrap = el('div', 'debug');
    const table = el('table');
    const head = table.createTHead().insertRow();
    for (const title of ['Doc', 'Pages', 'Vector', 'Keyword', 'Cosine', 'Fused', 'Used', 'Passage']) {
      const th = document.createElement('th');
      th.textContent = title;
      head.append(th);
    }
    const body = table.createTBody();
    for (const c of candidates) {
      const row = body.insertRow();
      if (c.selectedAs) row.className = 'picked';
      const cells = [c.filename, c.pageStart === c.pageEnd ? c.pageStart : `${c.pageStart}-${c.pageEnd}`,
        c.vectorRank ?? '-', c.keywordRank ?? '-', c.similarity.toFixed(3), c.score.toFixed(4),
        c.selectedAs ? `[${c.selectedAs}]` : '', c.preview];
      cells.forEach((value, i) => {
        const cell = row.insertCell();
        cell.textContent = String(value);
        if (i >= 2 && i <= 6) cell.className = 'num';
      });
    }
    wrap.append(table);
    return wrap;
  }

  function add(role) {
    const node = el('div', `msg ${role}`);
    messages.append(node);
    return node;
  }

  function flag(parent, text) {
    const node = el('div', 'flag');
    node.textContent = text;
    parent.append(node);
  }

  function shorten(text) {
    return text.length > 110 ? `${text.slice(0, 107)}...` : text;
  }

  function el(tag, className = '') {
    const node = document.createElement(tag);
    if (className) node.className = className;
    return node;
  }

  return { updateScope };
}
