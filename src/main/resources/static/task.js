import { createTaskEditor } from './task-editor.js';

const $ = id => document.getElementById(id);
const statuses = { PENDING: 'Pending', WAITING: 'Waiting', DONE: 'Done', SKIPPED: 'Skipped' };
let loading = false;
let currentTask = null;
const editor = createTaskEditor(async () => { await loadTask('Task saved.'); });

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function appendLinks(parent, links) {
  for (const [label, url] of Object.entries(links ?? {})) {
    try { if (typeof url !== 'string' || !['http:', 'https:'].includes(new URL(url).protocol)) continue; }
    catch { continue; }
    const link = element('a', `${label} ↗`);
    link.href = url;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.setAttribute('aria-label', `${label} (opens in a new tab)`);
    parent.append(link);
  }
}

async function loadTask(message = '') {
  if (loading) return;
  const id = $('task-id').value.trim();
  currentTask = null;
  $('task-detail').hidden = true;
  document.title = 'Windlass · Task details';
  if (!id) {
    $('status').textContent = 'Enter a task ID.';
    return;
  }
  const url = new URL(window.location.href);
  url.searchParams.set('id', id);
  window.history.replaceState(null, '', url);
  loading = true;
  for (const name of ['view', 'refresh', 'edit', 'task-id']) $(name).disabled = true;
  $('status').textContent = 'Loading task…';
  try {
    const response = await fetch(`/api/tasks/${encodeURIComponent(id)}`);
    if (!response.ok) {
      if (response.status === 404) throw new Error('No task found with this ID. Check the ID and try again.');
      throw new Error(`Could not load task (${response.status}). Please try again.`);
    }
    const task = await response.json();
    currentTask = task;
    $('task-title').textContent = task.name;
    document.title = `Windlass · ${task.name}`;
    $('identifier').textContent = task.id;
    $('task-state').textContent = statuses[task.status];
    $('task-state').className = `step-badge ${task.status.toLowerCase()}`;
    $('reminder').replaceChildren();
    if (task.notifyMeOn) {
      const time = element('time', new Date(task.notifyMeOn).toLocaleString());
      time.dateTime = task.notifyMeOn;
      $('reminder').append(time);
    } else $('reminder').textContent = 'No reminder.';
    $('task-description').textContent = task.description || 'No description.';
    $('task-metadata').textContent = JSON.stringify(task.metadata ?? {}, null, 2);
    $('task-webhooks').textContent = task.webhooks?.length ? task.webhooks.join('\n') : 'No webhooks.';
    $('external-links').replaceChildren();
    appendLinks($('external-links'), task.links);
    $('no-links').hidden = $('external-links').childElementCount > 0;
    $('steps').replaceChildren();
    const steps = task.steps ?? [];
    $('progress').textContent = `${steps.filter(step => step.status === 'DONE').length} of ${steps.length} steps done`;
    for (const step of steps) {
      const item = element('li');
      const heading = element('div', undefined, 'task-step-heading');
      heading.append(element('h4', step.name, 'task-step-name'), element('span', statuses[step.status], `step-badge ${step.status.toLowerCase()}`));
      item.append(heading);
      if (step.description) item.append(element('p', step.description, 'task-step-description muted'));
      const links = element('nav', undefined, 'external-links');
      links.setAttribute('aria-label', `${step.name} external links`);
      appendLinks(links, step.links);
      item.append(links);
      if (Object.keys(step.metadata ?? {}).length || step.webhooks?.length) {
        const details = element('details');
        details.append(element('summary', 'Step metadata and webhooks'), element('h5', 'Metadata'), element('pre', JSON.stringify(step.metadata ?? {}, null, 2)), element('h5', 'Webhooks'), element('pre', step.webhooks?.length ? step.webhooks.join('\n') : 'No webhooks.'));
        item.append(details);
      }
      $('steps').append(item);
    }
    $('no-steps').hidden = steps.length > 0;
    $('task-detail').hidden = false;
    $('status').textContent = message;
  } catch (error) { $('status').textContent = error.message; }
  finally {
    loading = false;
    for (const name of ['view', 'refresh', 'edit', 'task-id']) $(name).disabled = false;
  }
}

$('lookup').addEventListener('submit', event => {
  event.preventDefault();
  loadTask();
});
$('refresh').addEventListener('click', () => loadTask());
$('edit').addEventListener('click', () => { if (currentTask && !loading) editor.open(currentTask); });
$('task-id').value = new URLSearchParams(window.location.search).get('id') ?? '';
if ($('task-id').value.trim()) loadTask();
