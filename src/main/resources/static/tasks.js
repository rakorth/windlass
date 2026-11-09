import { createTaskEditor } from './task-editor.js';
import { readListUrl, updateListUrl } from './list-url.js';

const $ = id => document.getElementById(id);
let templates = false;
let view = 'board';
let page = 0;
let totalPages = 0;
let search = '';
let statusFilter = '';
let excludedStatuses = [];
let loading = false;
const editor = createTaskEditor(async (task, templateLibrary) => {
  setLibrary(templateLibrary);
  await load(`${templateLibrary ? 'Template' : 'Task'} saved.`);
});
const statuses = { PENDING: 'Pending', EXECUTING: 'Executing', DONE: 'Done', SKIPPED: 'Skipped', WAITING: 'Waiting' };

function syncUrl(replace = false) {
  updateListUrl({ search, status: statusFilter, excludeStatus: excludedStatuses,
    library: templates ? 'templates' : '', view: view === 'list' ? 'list' : '', page }, replace);
}

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

function boardStatusControl(task, stepIndex = null) {
  const value = stepIndex === null ? task : task.steps[stepIndex];
  const label = element('label', 'Move to', 'task-label');
  const select = element('select');
  select.setAttribute('aria-label', stepIndex === null ? `Status for ${task.name}` : `Status for step ${stepIndex + 1}: ${value.name} (${task.name})`);
  for (const [value, name] of Object.entries(statuses)) {
    const option = element('option', name);
    option.value = value;
    select.append(option);
  }
  select.value = value.status;
  select.addEventListener('change', async () => {
    if (loading) { select.value = value.status; return; }
    const nextStatus = select.value;
    // Read current fields before replacing the task so stale board data is not saved.
    loading = true;
    $('tasks').inert = true;
    document.querySelectorAll('#search-form input, #search-form select, #search-form button, #show-tasks, #show-templates, #new, #view-board, #view-list').forEach(node => { node.disabled = true; });
    select.disabled = true;
    $('status').textContent = 'Saving status…';
    try {
      const path = `/${encodeURIComponent(task.id)}`;
      if (stepIndex === null) {
        const current = await api(path, {}, false);
        const fields = ['name', 'description', 'links', 'metadata', 'steps', 'notifyMeOn', 'webhooks'];
        const body = Object.fromEntries(fields.map(key => [key, current[key]]));
        await api(path, { method: 'PUT', body: JSON.stringify({ ...body, status: nextStatus }) }, false);
      } else {
        await api(`${path}/steps/${stepIndex}/status`, {
          method: 'PATCH', body: JSON.stringify({ status: nextStatus })
        }, false);
      }
      loading = false;
      await load(`${stepIndex === null ? 'Task' : 'Step'} moved to ${statuses[nextStatus]}.`);
    } catch (error) {
      select.value = value.status;
      $('status').textContent = `Could not save status. ${error.message}`;
    } finally {
      loading = false;
      $('tasks').inert = false;
      document.querySelectorAll('#search-form input, #search-form select, #search-form button, #show-tasks, #show-templates, #new, #view-board, #view-list').forEach(node => { node.disabled = false; });
      select.disabled = false;
    }
  });
  label.append(select);
  return label;
}

function matchesBoardStatus(status) {
  return !excludedStatuses.includes(status) && (!statusFilter || statusFilter === status);
}

function render(items, total) {
  const board = view === 'board' && !templates;
  $('tasks').className = board ? 'kanban-board' : 'task-list';
  $('tasks').setAttribute('role', board ? 'region' : 'list');
  $('pagination').hidden = board;
  const columns = new Map();
  $('count').textContent = total;
  $('page-info').textContent = totalPages ? `Page ${page + 1} of ${totalPages}` : 'No pages';
  $('tasks').replaceChildren();
  if (!items.length) $('tasks').append(element('p', search || statusFilter || excludedStatuses.length ? `No ${templates ? 'templates' : 'tasks'} match your filters.` : templates ? 'No templates yet. Create a template to reuse its fields and steps.' : 'No tasks yet. Create a task to get started.', 'empty'));
  if (board && items.length) {
    for (const status of ['PENDING', 'EXECUTING', 'WAITING', 'DONE', 'SKIPPED']) {
      if (!matchesBoardStatus(status)) continue;
      const column = element('section', undefined, 'kanban-column');
      const heading = element('h3', undefined, 'kanban-heading');
      heading.id = `column-${status}`;
      heading.append(element('span', statuses[status], `step-badge ${status.toLowerCase()}`),
        element('span', '', 'muted column-count'));
      column.setAttribute('aria-labelledby', heading.id);
      const cards = element('div', undefined, 'kanban-cards');
      cards.setAttribute('role', 'list');
      cards.setAttribute('aria-label', `${statuses[status]} tasks and steps`);
      column.append(heading, cards);
      columns.set(status, cards);
      $('tasks').append(column);
    }
  }
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
    if (board) content.append(element('span', 'Task', 'kanban-kind'));
    content.append(title, element('p', task.description || 'No description.', 'description'));
    const state = element('div', undefined, 'task-state');
    state.append(element('span', 'Status', 'task-label'), element('span', statuses[task.status], `step-badge ${task.status.toLowerCase()}`));
    if (board) state.replaceChildren(boardStatusControl(task));
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
    if (!board) stepSection.append(steps);
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
    if (!board) $('tasks').append(row);
    else {
      columns.get(task.status)?.append(row);
      for (const [stepIndex, step] of task.steps.entries()) {
        const column = columns.get(step.status);
        if (!column) continue;
        const card = element('article', undefined, 'task-row kanban-step');
        card.setAttribute('role', 'listitem');
        const content = element('div', undefined, 'task-content');
        const parent = element('a', task.name, 'kanban-parent');
        parent.href = `/task.html?id=${encodeURIComponent(task.id)}`;
        content.append(element('span', `Step ${stepIndex + 1} of ${task.steps.length}`, 'kanban-kind'),
          parent, element('h2', step.name));
        if (step.description) content.append(element('p', step.description, 'description'));
        const details = element('details');
        details.append(element('summary', 'Step details'));
        appendExtras(details, step);
        content.append(details);
        const state = element('div', undefined, 'task-state');
        state.append(boardStatusControl(task, stepIndex));
        card.append(content, state);
        column.append(card);
      }
    }
  }
  if (board) {
    let taskCount = 0;
    let stepCount = 0;
    for (const cards of columns.values()) {
      const steps = cards.querySelectorAll('.kanban-step').length;
      const tasks = cards.children.length - steps;
      taskCount += tasks;
      stepCount += steps;
      cards.previousElementSibling.querySelector('.column-count').textContent = `${tasks} tasks · ${steps} steps`;
      if (!cards.children.length) cards.append(element('p', 'No tasks or steps', 'kanban-empty muted'));
    }
    $('count').textContent = `${taskCount} tasks · ${stepCount} steps`;
    if (!columns.size && items.length) $('tasks').append(element('p', 'No tasks or steps match your filters.', 'empty'));
  }
}

async function load(message = '') {
  if (loading) return;
  loading = true;
  syncUrl(true);
  $('tasks').querySelectorAll('button, select').forEach(node => { node.disabled = true; });
  for (const id of ['show-tasks', 'show-templates', 'new', 'view-board', 'view-list']) $(id).disabled = true;
  $('search-form').querySelectorAll('input, select, button').forEach(node => { node.disabled = true; });
  $('previous').disabled = $('next').disabled = true;
  $('status').textContent = templates ? 'Loading templates…' : 'Loading tasks…';
  try {
    const board = view === 'board' && !templates;
    const query = () => `?page=${page}&size=20&search=${encodeURIComponent(search)}${!board && statusFilter ? `&status=${encodeURIComponent(statusFilter)}` : ''}${(board ? [] : excludedStatuses).map(value => `&excludeStatus=${encodeURIComponent(value)}`).join('')}`;
    if (board) page = 0;
    let result = await api(query());
    if (board) {
      const items = [...result.items];
      for (let boardPage = 1; boardPage < result.totalPages; boardPage++) {
        const next = await api(query().replace('?page=0&', `?page=${boardPage}&`));
        items.push(...next.items);
      }
      result = { ...result, items };
    }
    if (page > 0 && page >= result.totalPages) {
      page = Math.max(0, result.totalPages - 1);
      result = await api(query());
    }
    syncUrl(true);
    totalPages = result.totalPages;
    render(result.items, result.totalElements);
    $('status').textContent = message;
  } catch (error) { $('status').textContent = `${message ? `${message} ` : ''}Could not load ${templates ? 'templates' : 'tasks'}. ${error.message}`; }
  finally {
    loading = false;
    $('tasks').querySelectorAll('button, select').forEach(node => { node.disabled = false; });
    for (const id of ['show-tasks', 'show-templates', 'new', 'view-board', 'view-list']) $(id).disabled = false;
    $('search-form').querySelectorAll('input, select, button').forEach(node => { node.disabled = false; });
    $('previous').disabled = page === 0;
    $('next').disabled = page + 1 >= totalPages;
  }
}

function setLibrary(value) {
  templates = value;
  $('view-board').hidden = $('view-list').hidden = templates;
  page = 0;
  $('library-title').textContent = $('list-title').textContent = templates ? 'Task templates' : 'Tasks';
  $('new').textContent = templates ? '+ New template' : '+ New task';
  $('search-form').querySelector('label').textContent = templates ? 'Search templates' : 'Search tasks';
  $('show-tasks').setAttribute('aria-pressed', String(!templates));
  $('show-templates').setAttribute('aria-pressed', String(templates));
}
for (const [id, value] of [['show-tasks', false], ['show-templates', true]]) {
  $(id).addEventListener('click', () => { if (!loading && !editor.saving) { setLibrary(value); syncUrl(); load(); } });
}
for (const mode of ['board', 'list']) {
  $(`view-${mode}`).addEventListener('click', () => {
    if (loading || editor.saving) return;
    view = mode;
    page = 0;
    updateViewButtons();
    syncUrl();
    load();
  });
}
function updateViewButtons() {
  for (const mode of ['board', 'list']) $(`view-${mode}`).setAttribute('aria-pressed', String(view === mode));
}
$('new').addEventListener('click', () => editor.open(null, templates));
function applyFilters() {
  search = $('search').value.trim();
  statusFilter = $('status-filter').value;
  excludedStatuses = Array.from(document.querySelectorAll('input[name="exclude-status"]:checked'), input => input.value);
  page = 0;
  syncUrl();
  load();
}
$('search-form').addEventListener('submit', event => { event.preventDefault(); applyFilters(); });
$('status-filter').addEventListener('change', applyFilters);
document.querySelectorAll('input[name="exclude-status"]').forEach(input => input.addEventListener('change', applyFilters));
$('refresh').addEventListener('click', () => load());
$('previous').addEventListener('click', () => { if (!loading && page > 0) { page--; syncUrl(); load(); } });
$('next').addEventListener('click', () => { if (!loading && page + 1 < totalPages) { page++; syncUrl(); load(); } });
const initialFilters = readListUrl('tasks');
view = initialFilters.view;
updateViewButtons();
search = initialFilters.search;
statusFilter = initialFilters.status;
excludedStatuses = initialFilters.excludeStatus;
setLibrary(initialFilters.library === 'templates');
page = initialFilters.page;
$('search').value = search;
$('status-filter').value = statusFilter;
document.querySelectorAll('input[name="exclude-status"]').forEach(input => {
  input.checked = excludedStatuses.includes(input.value);
});
window.addEventListener('popstate', () => window.location.reload());
load();
