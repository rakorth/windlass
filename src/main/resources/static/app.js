const $ = (id) => document.getElementById(id);
let notifications = [];
let editingId = null;
let loading = false;
let page = 0;
let totalElements = 0;
let totalPages = 0;
const pageSize = 20;

async function api(path = '', options = {}) {
  const response = await fetch(`/api/notifications${path}`, {
    ...options, headers: { 'Content-Type': 'application/json', ...options.headers }
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.detail || body.message || `Request failed (${response.status}). Please try again.`);
  }
  return response.status === 204 ? null : response.json();
}

function render() {
  const query = $('search').value.toLowerCase();
  const visible = notifications.filter(n => [n.title, n.description, n.notification_source].some(v => v.toLowerCase().includes(query)));
  $('count').textContent = totalElements;
  $('unread-count').textContent = `${notifications.filter(n => n.unread).length} unread on this page`;
  $('page-info').textContent = totalPages ? `Page ${page + 1} of ${totalPages}` : 'No pages';
  $('previous').disabled = loading || page === 0;
  $('next').disabled = loading || page + 1 >= totalPages;
  $('notifications').replaceChildren();
  if (!visible.length) {
    const empty = document.createElement('div');
    empty.className = 'empty';
    empty.textContent = query ? 'No notifications match your search.' : 'No notifications match this filter.';
    $('notifications').append(empty);
  }
  for (const notification of visible) {
    const card = $('card-template').content.cloneNode(true);
    card.querySelector('.card').classList.toggle('unread', notification.unread);
    card.querySelector('.read-state').textContent = notification.unread ? '● Unread' : 'Seen';
    const seenButton = card.querySelector('.mark-seen');
    seenButton.hidden = !notification.unread;
    seenButton.addEventListener('click', async () => {
      seenButton.disabled = true;
      try {
        await api(`/${notification.id}/seen`, { method: 'PATCH' });
        await load();
        $('status').textContent = 'Notification marked as seen.';
      } catch (error) {
        $('status').textContent = error.message;
        seenButton.disabled = false;
      }
    });
    card.querySelector('.source').textContent = notification.notification_source;
    card.querySelector('h3').textContent = notification.title;
    card.querySelector('.description').textContent = notification.description;
    const time = card.querySelector('time');
    time.dateTime = notification.received_on;
    time.textContent = new Date(notification.received_on).toLocaleString();
    card.querySelector('.identifier').textContent = `ID: ${notification.id}`;
    card.querySelector('pre').textContent = JSON.stringify(notification.metadata_map, null, 2);
    for (const [label, url] of Object.entries(notification.external_links ?? {})) {
      try {
        if (typeof url !== 'string' || !['http:', 'https:'].includes(new URL(url).protocol)) continue;
      } catch { continue; }
      const link = document.createElement('a');
      link.textContent = `${label} ↗`;
      link.href = url;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.setAttribute('aria-label', `${label} (opens in a new tab)`);
      card.querySelector('.external-links').append(link);
    }
    card.querySelector('.edit').addEventListener('click', () => openEditor(notification));
    card.querySelector('.delete').addEventListener('click', async (event) => {
      if (!confirm(`Delete “${notification.title}”? This cannot be undone.`)) return;
      event.target.disabled = true;
      try {
        await api(`/${notification.id}`, { method: 'DELETE' });
        await load();
        $('status').textContent = 'Notification deleted.';
      } catch (error) { $('status').textContent = error.message; event.target.disabled = false; }
    });
    $('notifications').append(card);
  }
}

async function load() {
  if (loading) return;
  loading = true;
  $('refresh').disabled = true;
  $('unread-only').disabled = true;
  $('previous').disabled = true;
  $('next').disabled = true;
  $('status').textContent = 'Loading notifications…';
  try {
    const suffix = $('unread-only').checked ? '&unread=true' : '';
    let result = await api(`?page=${page}&size=${pageSize}${suffix}`);
    if (page > 0 && page >= result.totalPages) {
      page = Math.max(0, result.totalPages - 1);
      result = await api(`?page=${page}&size=${pageSize}${suffix}`);
    }
    notifications = result.items;
    totalElements = result.totalElements;
    totalPages = result.totalPages;
    render();
    $('status').textContent = '';
  } catch (error) { $('status').textContent = `Could not load notifications. ${error.message}`; }
  finally {
    loading = false;
    $('refresh').disabled = false;
    $('unread-only').disabled = false;
    $('previous').disabled = page === 0;
    $('next').disabled = page + 1 >= totalPages;
  }
}

function openEditor(notification = null) {
  editingId = notification?.id ?? null;
  $('form').reset();
  $('editor-title').textContent = notification ? 'Edit notification' : 'New notification';
  $('title').value = notification?.title ?? '';
  $('description').value = notification?.description ?? '';
  $('source').value = notification?.notification_source ?? '';
  $('metadata').value = JSON.stringify(notification?.metadata_map ?? {}, null, 2);
  $('external-links').value = JSON.stringify(notification?.external_links ?? {}, null, 2);
  $('received').value = '';
  if (notification) {
    const date = new Date(notification.received_on);
    $('received').value = new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 23);
  }
  $('form-error').textContent = '';
  $('editor').showModal();
  $('title').focus();
}

$('new').addEventListener('click', () => openEditor());
$('close').addEventListener('click', () => $('editor').close());
$('cancel').addEventListener('click', () => $('editor').close());
$('refresh').addEventListener('click', load);
$('search').addEventListener('input', render);
$('unread-only').addEventListener('change', () => { page = 0; load(); });
$('previous').addEventListener('click', () => { if (!loading && page > 0) { page--; load(); } });
$('next').addEventListener('click', () => { if (!loading && page + 1 < totalPages) { page++; load(); } });
$('form').addEventListener('submit', async (event) => {
  event.preventDefault();
  $('form-error').textContent = '';
  try {
    let metadata;
    try { metadata = JSON.parse($('metadata').value || '{}'); }
    catch { throw new Error('Metadata must be valid JSON, for example {"priority": "high"}.'); }
    if (!metadata || Array.isArray(metadata) || typeof metadata !== 'object') throw new Error('Metadata must be a JSON object.');
    if (!$('title').value.trim() || !$('source').value.trim()) throw new Error('Title and notification source are required.');
    let links;
    try { links = JSON.parse($('external-links').value || '{}'); }
    catch { throw new Error('External links must be a JSON object mapping labels to URLs.'); }
    if (!links || Array.isArray(links) || typeof links !== 'object') throw new Error('External links must be a JSON object.');
    for (const [label, url] of Object.entries(links)) {
      let valid = false;
      try { valid = label.trim() && typeof url === 'string' && /^https?:\/\//i.test(url) && ['http:', 'https:'].includes(new URL(url).protocol); } catch { /* Show validation below. */ }
      if (!valid) throw new Error('Each external link needs a label and a full HTTP or HTTPS URL.');
    }
    const body = {
      title: $('title').value.trim(), description: $('description').value,
      notification_source: $('source').value.trim(),
      received_on: $('received').value ? new Date($('received').value).toISOString() : null,
      metadata_map: metadata, external_links: links
    };
    $('save').disabled = true;
    $('cancel').disabled = true;
    $('close').disabled = true;
    await api(editingId ? `/${editingId}` : '', { method: editingId ? 'PUT' : 'POST', body: JSON.stringify(body) });
    page = 0;
    await load();
    $('editor').close();
    $('status').textContent = 'Notification saved.';
  } catch (error) { $('form-error').textContent = error.message; }
  finally { $('save').disabled = false; $('cancel').disabled = false; $('close').disabled = false; }
});
$('editor').addEventListener('cancel', event => { if ($('save').disabled) event.preventDefault(); });
load();
