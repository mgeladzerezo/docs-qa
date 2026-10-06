import { getPage } from './api.js';

/**
 * The right-hand panel: the cleaned text of the page a citation points at, with the cited passage
 * highlighted. Offsets in the API are document-wide; subtracting the page's charStart gives the
 * position inside the page text.
 */
export function createSourcePanel() {
  const empty = document.getElementById('source-empty');
  const view = document.getElementById('source-view');
  const name = document.getElementById('source-name');
  const pageBadge = document.getElementById('source-page');
  const pdf = document.getElementById('source-pdf');
  const heading = document.getElementById('source-heading');
  const text = document.getElementById('source-text');
  let token = 0;

  /**
   * @param target { documentId, filename, page, heading, start, end } with start/end as document offsets
   */
  async function open(target) {
    const mine = ++token;
    empty.hidden = true;
    view.hidden = false;
    name.textContent = target.filename;
    pageBadge.textContent = `page ${target.page}`;
    pdf.href = `/api/documents/${target.documentId}/file#page=${target.page}`;
    heading.textContent = target.heading ? `Section: ${target.heading}` : '';
    text.textContent = 'Loading page...';
    let page;
    try {
      page = await getPage(target.documentId, target.page);
    } catch (error) {
      if (mine === token) text.textContent = `Could not load the page: ${error.message}`;
      return;
    }
    if (mine !== token) return;
    const start = Math.max(0, Math.min(page.text.length, target.start - page.charStart));
    const end = Math.max(start, Math.min(page.text.length, target.end - page.charStart));
    const mark = document.createElement('mark');
    mark.textContent = page.text.slice(start, end);
    text.replaceChildren(page.text.slice(0, start), mark, page.text.slice(end));
    mark.scrollIntoView({ block: 'center' });
  }

  return { open };
}
