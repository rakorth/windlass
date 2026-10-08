const $ = (id) => document.getElementById(id);
let loading = false;
let currentNotification = null;

function renderReadState(notification) {
  currentNotification = notification;
  $('read-state').textContent = notification.unread ? '● Unread' : 'Seen';
  $('notification-detail').classList.toggle('unread', notification.unread);
  $('toggle-read').textContent = notification.unread ? 'Mark as read' : 'Mark as unread';
}

function setLoading(value) {
  loading = value;
  for (const id of ['view', 'refresh', 'toggle-read', 'notification-id']) {
    $(id).disabled = value;
  }
}

async function toggleReadState() {
  if (loading || !currentNotification) return;
  const unread = !currentNotification.unread;
  setLoading(true);
  $('status').textContent = unread ? 'Marking notification as unread…' : 'Marking notification as read…';
  try {
    const response = await fetch(`/api/notifications/${encodeURIComponent(currentNotification.id)}/${unread ? 'unread' : 'seen'}`, { method: 'PATCH' });
    if (!response.ok) throw new Error(`Could not update notification (${response.status}). Please try again.`);
    renderReadState(await response.json());
    $('status').textContent = currentNotification.unread ? 'Notification marked as unread.' : 'Notification marked as read.';
  } catch (error) {
    $('status').textContent = error.message;
  } finally {
    setLoading(false);
  }
}

async function loadNotification() {
  if (loading) return;
  const id = $('notification-id').value.trim();
  currentNotification = null;
  $('notification-detail').hidden = true;
  document.title = 'Windlass · Notification details';
  if (!id) {
    $('status').textContent = 'Enter a notification ID.';
    return;
  }
  const url = new URL(window.location.href);
  url.searchParams.set('id', id);
  window.history.replaceState(null, '', url);
  setLoading(true);
  $('status').textContent = 'Loading notification…';
  try {
    const response = await fetch(`/api/notifications/${encodeURIComponent(id)}`);
    if (!response.ok) {
      if (response.status === 404) throw new Error('No notification found with this ID. Check the ID and try again.');
      throw new Error(`Could not load notification (${response.status}). Please try again.`);
    }
    const notification = await response.json();
    $('notification-title').textContent = notification.title;
    document.title = `Windlass · ${notification.title}`;
    $('identifier').textContent = notification.id;
    $('source').textContent = notification.notification_source;
    renderReadState(notification);
    $('received').dateTime = notification.received_on;
    $('received').textContent = new Date(notification.received_on).toLocaleString();
    $('description').textContent = notification.description || 'No description.';
    $('metadata').textContent = JSON.stringify(notification.metadata_map ?? {}, null, 2);
    $('external-links').replaceChildren();
    for (const [label, linkUrl] of Object.entries(notification.external_links ?? {})) {
      try {
        if (typeof linkUrl !== 'string' || !['http:', 'https:'].includes(new URL(linkUrl).protocol)) continue;
      } catch { continue; }
      const link = document.createElement('a');
      link.textContent = `${label} ↗`;
      link.href = linkUrl;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.setAttribute('aria-label', `${label} (opens in a new tab)`);
      $('external-links').append(link);
    }
    $('no-links').hidden = $('external-links').childElementCount > 0;
    $('notification-detail').hidden = false;
    $('status').textContent = '';
  } catch (error) {
    $('status').textContent = error.message;
  } finally {
    setLoading(false);
  }
}

$('lookup').addEventListener('submit', (event) => {
  event.preventDefault();
  loadNotification();
});
$('toggle-read').addEventListener('click', toggleReadState);
$('refresh').addEventListener('click', loadNotification);
$('notification-id').value = new URLSearchParams(window.location.search).get('id') ?? '';
if ($('notification-id').value.trim()) loadNotification();
