const $ = (id) => document.getElementById(id);
let loading = false;

async function loadNotification() {
  if (loading) return;
  const id = $('notification-id').value.trim();
  $('notification-detail').hidden = true;
  document.title = 'Windlass · Notification details';
  if (!id) {
    $('status').textContent = 'Enter a notification ID.';
    return;
  }
  const url = new URL(window.location.href);
  url.searchParams.set('id', id);
  window.history.replaceState(null, '', url);
  loading = true;
  $('view').disabled = true;
  $('refresh').disabled = true;
  $('notification-id').disabled = true;
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
    $('read-state').textContent = notification.unread ? '● Unread' : 'Seen';
    $('notification-detail').classList.toggle('unread', notification.unread);
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
    loading = false;
    $('view').disabled = false;
    $('refresh').disabled = false;
    $('notification-id').disabled = false;
  }
}

$('lookup').addEventListener('submit', (event) => {
  event.preventDefault();
  loadNotification();
});
$('refresh').addEventListener('click', loadNotification);
$('notification-id').value = new URLSearchParams(window.location.search).get('id') ?? '';
if ($('notification-id').value.trim()) loadNotification();
