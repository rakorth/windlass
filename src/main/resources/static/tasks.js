const $ = id => document.getElementById(id);
let templates = false;
let editingTemplate = false;
let page = 0;
let totalPages = 0;
let search = '';
let statusFilter = '';
let editingId = null;
let loading = false;
let saving = false;
const statuses = { PENDING: 'Pending', DONE: 'Done', SKIPPED: 'Skipped', WAITING: 'Waiting' };

async function api(path = '', options = {}, templateLibrary = templates) {
  const response = await fetch(`/api/${templateLibrary ? 'task-templates' : 'tasks'}${path}`, {
    ...options, headers: { 'Content-Type': 'application/json' }
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.detail || body.message || `Request failed (${response.status}). Please try again.`);
  }
  return response.status === 204 ? null : response.json();
}

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function appendExtras(parent, value) {
  const links = element('nav', undefined, 'external-links');
  links.setAttribute('aria-label', 'External links');
  for (const [label, url] of Object.entries(value.links ?? {})) {
    try { if (!['http:', 'https:'].includes(new URL(url).protocol)) continue; }
    catch { continue; }
    const link = element('a', `${label} ↗`);
    link.href = url;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    links.append(link);
  }
  parent.append(links);
  const details = element('details');
  details.append(element('summary', 'Metadata'), element('pre', JSON.stringify(value.metadata ?? {}, null, 2)));
  parent.append(details);
}

function render(items, total) {
  $('count').textContent = total;
  $('page-info').textContent = totalPages ? `Page ${page + 1} of ${totalPages}` : 'No pages';
  $('tasks').replaceChildren();
  if (!items.length) $('tasks').append(element('p', search || statusFilter ? `No ${templates ? 'templates' : 'tasks'} match your filters.` : templates ? 'No templates yet. Create a template to reuse its fields and steps.' : 'No tasks yet. Create a task to get started.', 'empty'));
  for (const task of items) {
    const card = element('article', undefined, 'card task-card');
    card.append(element('h2', task.name), element('p', task.description || 'No description.', 'description'));
    card.append(element('span', statuses[task.status], `step-badge ${task.status.toLowerCase()}`));
    if (task.notifyMeOn) card.append(element('p', `Notify me on: ${new Date(task.notifyMeOn).toLocaleString()}`, 'muted'));
    const done = task.steps.filter(step => step.status === 'DONE').length;
    card.append(element('p', `${done} of ${task.steps.length} steps done`, 'muted'));
    const details = element('details');
    details.append(element('summary', 'View task details'));
    details.append(element('p', `ID: ${task.id}`, 'identifier'));
    appendExtras(details, task);
    const steps = element('ol', undefined, 'task-steps');
    for (const step of task.steps) {
      const item = element('li');
      item.append(element('h3', step.name), element('span', statuses[step.status], `step-badge ${step.status.toLowerCase()}`));
      appendExtras(item, step);
      steps.append(item);
    }
    details.append(steps);
    if (!task.steps.length) details.append(element('p', 'No steps.', 'muted'));
    card.append(details);
    const actions = element('div', undefined, 'actions');
    const edit = element('button', 'Edit', 'secondary');
    edit.addEventListener('click', () => openEditor(task));
    const remove = element('button', 'Delete', 'danger');
    remove.addEventListener('click', async () => {
      if (!confirm(`Delete ${templates ? 'template' : 'task'} “${task.name}” and all its steps? This cannot be undone.`)) return;
      remove.disabled = true;
      try {
        await api(`/${encodeURIComponent(task.id)}`, { method: 'DELETE' });
        await load(`${templates ? 'Template' : 'Task'} deleted.`);
      } catch (error) { $('status').textContent = error.message; remove.disabled = false; }
    });
    const copy = element('button', templates ? 'Use template' : 'Save as template', 'secondary');
    copy.addEventListener('click', () => openEditor(task, !templates, true));
    actions.append(copy, edit, remove);
    card.append(actions);
    $('tasks').append(card);
  }
}

async function load(message = '') {
  if (loading) return;
  loading = true;
  $('tasks').querySelectorAll('button').forEach(node => { node.disabled = true; });
  for (const id of ['show-tasks', 'show-templates', 'new']) $(id).disabled = true;
  $('search-form').querySelectorAll('input, select, button').forEach(node => { node.disabled = true; });
  $('previous').disabled = $('next').disabled = true;
  $('status').textContent = templates ? 'Loading templates…' : 'Loading tasks…';
  try {
    const query = () => `?page=${page}&size=20&search=${encodeURIComponent(search)}${statusFilter ? `&status=${encodeURIComponent(statusFilter)}` : ''}`;
    let result = await api(query());
    if (page > 0 && page >= result.totalPages) {
      page = Math.max(0, result.totalPages - 1);
      result = await api(query());
    }
    totalPages = result.totalPages;
    render(result.items, result.totalElements);
    $('status').textContent = message;
  } catch (error) { $('status').textContent = `${message ? `${message} ` : ''}Could not load ${templates ? 'templates' : 'tasks'}. ${error.message}`; }
  finally {
    loading = false;
    $('tasks').querySelectorAll('button').forEach(node => { node.disabled = false; });
    for (const id of ['show-tasks', 'show-templates', 'new']) $(id).disabled = false;
    $('search-form').querySelectorAll('input, select, button').forEach(node => { node.disabled = false; });
    $('previous').disabled = page === 0;
    $('next').disabled = page + 1 >= totalPages;
  }
}

function numberSteps() {
  const rows = [...$('step-editors').children];
  rows.forEach((row, index) => {
    row.querySelector('legend').textContent = `Step ${index + 1}`;
    row.querySelector('.up').disabled = index === 0;
    row.querySelector('.down').disabled = index === rows.length - 1;
  });
}

function addStep(step = {}) {
  const row = $('step-template').content.firstElementChild.cloneNode(true);
  row.querySelector('.step-name').value = step.name ?? '';
  row.querySelector('.step-status').value = step.status ?? 'PENDING';
  row.querySelector('.step-metadata').value = JSON.stringify(step.metadata ?? {}, null, 2);
  row.querySelector('.step-links').value = JSON.stringify(step.links ?? {}, null, 2);
  row.querySelector('.remove').addEventListener('click', () => { row.remove(); numberSteps(); });
  row.querySelector('.up').addEventListener('click', () => { if (row.previousElementSibling) row.before(row.previousElementSibling); numberSteps(); });
  row.querySelector('.down').addEventListener('click', () => { if (row.nextElementSibling) row.after(row.nextElementSibling); numberSteps(); });
  $('step-editors').append(row);
  numberSteps();
}

function openEditor(task = null, templateLibrary = templates, copy = false) {
  editingTemplate = templateLibrary;
  editingId = copy ? null : task?.id ?? null;
  const kind = editingTemplate ? 'template' : 'task';
  $('editor-title').textContent = `${editingId ? 'Edit' : 'New'} ${kind}`;
  $('save').textContent = `Save ${kind}`;
  $('name').value = task?.name ?? '';
  $('description').value = task?.description ?? '';
  $('task-status').value = task?.status ?? 'PENDING';
  const notifyDate = task?.notifyMeOn ? new Date(task.notifyMeOn) : null;
  $('notify-me-on').value = notifyDate
    ? new Date(notifyDate.getTime() - notifyDate.getTimezoneOffset() * 60000).toISOString().slice(0, -1) : '';
  $('metadata').value = JSON.stringify(task?.metadata ?? {}, null, 2);
  $('links').value = JSON.stringify(task?.links ?? {}, null, 2);
  $('step-editors').replaceChildren();
  (task?.steps ?? []).forEach(addStep);
  $('form-error').textContent = '';
  $('editor').showModal();
  $('name').focus();
}

function parseObject(input, label, links = false) {
  let value;
  try { value = JSON.parse(input.value || '{}'); }
  catch { throw new Error(`${label} must be valid JSON.`); }
  if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error(`${label} must be a JSON object.`);
  if (links) for (const [name, url] of Object.entries(value)) {
    let valid = false;
    try { valid = name.trim() && typeof url === 'string' && /^https?:\/\//i.test(url) && !!new URL(url).hostname; }
    catch { /* Report validation below. */ }
    if (!valid) throw new Error(`${label} require nonblank labels and full HTTP or HTTPS URLs.`);
  }
  return value;
}

$('form').addEventListener('submit', async event => {
  event.preventDefault();
  if (saving) return;
  $('form-error').textContent = '';
  try {
    if (!$('name').value.trim()) throw new Error('Task name is required.');
    const steps = [...$('step-editors').children].map((row, index) => {
      const name = row.querySelector('.step-name').value.trim();
      if (!name) throw new Error(`Step ${index + 1} needs a name.`);
      return { name, status: row.querySelector('.step-status').value,
        metadata: parseObject(row.querySelector('.step-metadata'), `Step ${index + 1} metadata`),
        links: parseObject(row.querySelector('.step-links'), `Step ${index + 1} links`, true) };
    });
    const body = { notifyMeOn: $('notify-me-on').value ? new Date($('notify-me-on').value).toISOString() : null, status: $('task-status').value, name: $('name').value.trim(), description: $('description').value,
      metadata: parseObject($('metadata'), 'Task metadata'), links: parseObject($('links'), 'Task links', true), steps };
    saving = true;
    $('editor-fields').disabled = $('save').disabled = $('close').disabled = $('cancel').disabled = true;
    await api(editingId ? `/${encodeURIComponent(editingId)}` : '', { method: editingId ? 'PUT' : 'POST', body: JSON.stringify(body) }, editingTemplate);
    $('editor').close();
    page = 0;
    setLibrary(editingTemplate);
    await load(`${editingTemplate ? 'Template' : 'Task'} saved.`);
  } catch (error) { $('form-error').textContent = error.message; }
  finally {
    saving = false;
    $('editor-fields').disabled = $('save').disabled = $('close').disabled = $('cancel').disabled = false;
  }
});
function setLibrary(value) {
  templates = value;
  page = 0;
  $('library-title').textContent = $('list-title').textContent = templates ? 'Task templates' : 'Tasks';
  $('new').textContent = templates ? '+ New template' : '+ New task';
  $('search-form').querySelector('label').textContent = templates ? 'Search templates' : 'Search tasks';
  $('show-tasks').setAttribute('aria-pressed', String(!templates));
  $('show-templates').setAttribute('aria-pressed', String(templates));
}
for (const [id, value] of [['show-tasks', false], ['show-templates', true]]) {
  $(id).addEventListener('click', () => { if (!loading && !saving) { setLibrary(value); load(); } });
}
$('new').addEventListener('click', () => openEditor());
$('add-step').addEventListener('click', () => { addStep(); $('step-editors').lastElementChild.querySelector('input').focus(); });
for (const id of ['close', 'cancel']) $(id).addEventListener('click', () => $('editor').close());
$('editor').addEventListener('cancel', event => { if (saving) event.preventDefault(); });
$('search-form').addEventListener('submit', event => { event.preventDefault(); search = $('search').value.trim(); statusFilter = $('status-filter').value; page = 0; load(); });
$('status-filter').addEventListener('change', () => { search = $('search').value.trim(); statusFilter = $('status-filter').value; page = 0; load(); });
$('refresh').addEventListener('click', () => load());
$('previous').addEventListener('click', () => { if (!loading && page > 0) { page--; load(); } });
$('next').addEventListener('click', () => { if (!loading && page + 1 < totalPages) { page++; load(); } });
load();
