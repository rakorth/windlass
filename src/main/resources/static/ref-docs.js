const $ = id => document.getElementById(id);
let selected = null;
let busy = false;
let dirty = false;

async function api(path = '', options = {}) {
  const response = await fetch(`/api/ref-docs${path}`, {
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
  $('fields').disabled = $('new').disabled = $('refresh').disabled = $('upload').disabled = $('delete-binary').disabled = true;
  $('files').querySelectorAll('button').forEach(button => { button.disabled = true; });
  $('status').textContent = '';
  try { await action(); }
  catch (error) { $('status').textContent = error.message; }
  finally {
    busy = false;
    $('fields').disabled = $('new').disabled = $('refresh').disabled = $('upload').disabled = $('delete-binary').disabled = false;
    $('files').querySelectorAll('button').forEach(button => { button.disabled = false; });
  }
}

function edit(document = null) {
  selected = document?.name ?? null;
  const binary = selected !== null && !selected.endsWith('.md');
  $('binary').hidden = !binary;
  $('editor').hidden = binary;
  $('download').href = selected ? `/api/ref-docs/${encodeURIComponent(selected)}/download` : '';
  $('filename').value = selected || '';
  $('filename').readOnly = selected !== null;
  $('content').value = document?.content ?? '';
  $('editor-title').textContent = selected || 'New file';
  $('delete').hidden = selected === null;
  dirty = false;
}

async function load() {
  const names = await api();
  $('files').replaceChildren();
  if (!names.length) {
    const item = document.createElement('li');
    item.textContent = 'No reference documents yet.';
    $('files').append(item);
  }
  for (const name of names) {
    const item = document.createElement('li');
    const button = document.createElement('button');
    button.className = 'secondary';
    button.textContent = name;
    button.disabled = busy;
    button.addEventListener('click', () => {
      if (!busy && canLeave()) run(async () => edit(name.endsWith('.md') ? await api(`/${encodeURIComponent(name)}`) : { name }));
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
    const saved = await api(selected ? `/${encodeURIComponent(selected)}` : '', {
      method: selected ? 'PUT' : 'POST',
      body: JSON.stringify({ name: $('filename').value, content: $('content').value })
    });
    edit(saved);
    $('status').textContent = 'File saved.';
    await load();
  });
});
function deleteSelected() {
  if (!selected || !confirm(`Permanently delete “${selected}” from disk?`)) return;
  run(async () => {
    await api(`/${encodeURIComponent(selected)}`, { method: 'DELETE' });
    edit();
    $('status').textContent = 'File deleted.';
    await load();
  });
}
$('delete').addEventListener('click', deleteSelected);
$('delete-binary').addEventListener('click', deleteSelected);
$('upload').addEventListener('click', () => {
  if (canLeave()) $('upload-file').click();
});
$('upload-file').addEventListener('change', () => {
  const file = $('upload-file').files[0];
  if (!file) return;
  run(async () => {
    const body = new FormData();
    body.append('file', file);
    const response = await fetch('/api/ref-docs/upload', { method: 'POST', body });
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.detail || error.message || `Upload failed (${response.status}).`);
    }
    edit(file.name.endsWith('.md') ? await api(`/${encodeURIComponent(file.name)}`) : { name: file.name });
    await load();
    $('status').textContent = 'File uploaded.';
  });
  $('upload-file').value = '';
});
window.addEventListener('beforeunload', event => {
  if (dirty) { event.preventDefault(); event.returnValue = ''; }
});
run(load);
