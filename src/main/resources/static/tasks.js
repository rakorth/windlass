import { createTaskEditor } from './task-editor.js';

const $ = id => document.getElementById(id);
let templates = false;
let page = 0;
let totalPages = 0;
let search = '';
let statusFilter = '';
let loading = false;
const editor = createTaskEditor(async (task, templateLibrary) => {
  setLibrary(templateLibrary);
  await load(`${templateLibrary ? 'Template' : 'Task'} saved.`);
});
const statuses = { PENDING: 'Pending', EXECUTING: 'Executing', DONE: 'Done', SKIPPED: 'Skipped', WAITING: 'Waiting' };

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
    const row = element('article', undefined, 'task-row');
    row.setAttribute('role', 'listitem');
    const content = element('div', undefined, 'task-content');
    const title = element('h2');
    if (templates) title.textContent = task.name;
    else {
      const link = element('a', task.name);
      link.href = `/task.html?id=${encodeURIComponent(task.id)}`;
      title.append(link);
    }
    content.append(title, element('p', task.description || 'No description.', 'description'));
    const state = element('div', undefined, 'task-state');
    state.append(element('span', 'Status', 'task-label'), element('span', statuses[task.status], `step-badge ${task.status.toLowerCase()}`));
    if (task.notifyMeOn) content.append(element('p', `Notify me on: ${new Date(task.notifyMeOn).toLocaleString()}`, 'muted'));
    const done = task.steps.filter(step => step.status === 'DONE').length;
    const stepSection = element('section', undefined, 'task-step-section');
    stepSection.append(element('h3', 'Steps', 'task-label'), element('p', `${done} of ${task.steps.length} steps done`, 'task-progress'));
    const details = element('details');
    details.append(element('summary', 'View task details'));
    details.append(element('p', `ID: ${task.id}`, 'identifier'));
    appendExtras(details, task);
    const steps = element('ol', undefined, 'task-steps');
    for (const step of task.steps) {
      const item = element('li');
      const heading = element('div', undefined, 'task-step-heading');
      heading.append(element('span', step.name, 'task-step-name'), element('span', statuses[step.status], `step-badge ${step.status.toLowerCase()}`));
      item.append(heading);
      if (step.description) item.append(element('p', step.description, 'task-step-description muted'));
      if (Object.keys(step.links ?? {}).length || Object.keys(step.metadata ?? {}).length) {
        const extras = element('details');
        extras.append(element('summary', 'Step details'));
        appendExtras(extras, step);
        item.append(extras);
      }
      steps.append(item);
    }
    stepSection.append(steps);
    if (!task.steps.length) stepSection.append(element('p', 'No steps.', 'muted'));
    content.append(details);
    const actions = element('div', undefined, 'actions task-actions');
    const edit = element('button', 'Edit', 'secondary');
    edit.addEventListener('click', () => editor.open(task, templates));
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
    copy.addEventListener('click', () => editor.open(task, !templates, true));
    actions.append(copy, edit, remove);
    row.append(content, state, stepSection, actions);
    $('tasks').append(row);
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
  $(id).addEventListener('click', () => { if (!loading && !editor.saving) { setLibrary(value); load(); } });
}
$('new').addEventListener('click', () => editor.open(null, templates));
$('search-form').addEventListener('submit', event => { event.preventDefault(); search = $('search').value.trim(); statusFilter = $('status-filter').value; page = 0; load(); });
$('status-filter').addEventListener('change', () => { search = $('search').value.trim(); statusFilter = $('status-filter').value; page = 0; load(); });
$('refresh').addEventListener('click', () => load());
$('previous').addEventListener('click', () => { if (!loading && page > 0) { page--; load(); } });
$('next').addEventListener('click', () => { if (!loading && page + 1 < totalPages) { page++; load(); } });
load();
