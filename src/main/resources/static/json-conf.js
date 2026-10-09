const $ = id => document.getElementById(id);
let selected = null;
let busy = false;
let dirty = false;

async function api(path = '', options = {}) {
  const response = await fetch(`/api/json-conf${path}`, {
    ...options, headers: { 'Content-Type': 'application/json' }
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.detail || body.message || `Request failed (${response.status}).`);
  }
  return response.status === 204 ? null : response.json();
}

function canLeave() {
  return !dirty || confirm('Discard your unsaved changes?');
}

async function run(action) {
  if (busy) return;
  busy = true;
  $('fields').disabled = $('new').disabled = $('refresh').disabled = true;
  $('files').querySelectorAll('button').forEach(button => { button.disabled = true; });
  $('status').textContent = '';
  try { await action(); }
  catch (error) { $('status').textContent = error.message; }
  finally {
    busy = false;
    $('fields').disabled = $('new').disabled = $('refresh').disabled = false;
    $('files').querySelectorAll('button').forEach(button => { button.disabled = false; });
  }
}

function edit(document = null) {
  selected = document?.name ?? null;
  $('filename').value = selected || '';
  $('filename').readOnly = selected !== null;
  $('content').value = document?.content ?? '{}';
  $('editor-title').textContent = selected || 'New file';
  $('delete').hidden = selected === null;
  dirty = false;
}

async function load() {
  const names = await api();
  $('files').replaceChildren();
  if (!names.length) {
    const item = document.createElement('li');
    item.textContent = 'No JSON configuration files yet.';
    $('files').append(item);
  }
  for (const name of names) {
    const item = document.createElement('li');
    const button = document.createElement('button');
    button.className = 'secondary';
    button.textContent = name;
    button.disabled = busy;
    button.addEventListener('click', () => {
      if (!busy && canLeave()) run(async () => edit(await api(`/${encodeURIComponent(name)}`)));
    });
    item.append(button);
    $('files').append(item);
  }
}

$('editor').addEventListener('input', () => { dirty = true; });
$('new').addEventListener('click', () => { if (canLeave()) { edit(); $('filename').focus(); } });
$('refresh').addEventListener('click', () => run(load));
$('editor').addEventListener('submit', event => {
  event.preventDefault();
  run(async () => {
    try { JSON.parse($('content').value); }
    catch { throw new Error('Content must contain valid JSON.'); }
    const saved = await api(selected ? `/${encodeURIComponent(selected)}` : '', {
      method: selected ? 'PUT' : 'POST',
      body: JSON.stringify({ name: $('filename').value, content: $('content').value })
    });
    edit(saved);
    $('status').textContent = 'File saved.';
    await load();
  });
});
$('delete').addEventListener('click', () => {
  if (!selected || !confirm(`Permanently delete “${selected}” from disk?`)) return;
  run(async () => {
    await api(`/${encodeURIComponent(selected)}`, { method: 'DELETE' });
    edit();
    $('status').textContent = 'File deleted.';
    await load();
  });
});
window.addEventListener('beforeunload', event => {
  if (dirty) { event.preventDefault(); event.returnValue = ''; }
});
run(load);
