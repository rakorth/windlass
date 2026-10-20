const $ = id => document.getElementById(id);
const number = value => value.toLocaleString();

function element(tag, text, className) {
  const node = document.createElement(tag);
  node.textContent = text;
  if (className) node.className = className;
  return node;
}

function render(data) {
  $('unread-total').textContent = number(data.unread);
  $('all-total').textContent = number(data.total);
  $('read-total').textContent = number(data.total - data.unread);
  $('read-percent').textContent = data.total ? `${Math.round((data.total - data.unread) / data.total * 100)}% of retained notifications` : 'No notifications yet';
  $('source-total').textContent = number(data.sources.filter(source => source.unread > 0).length);
  $('source-caption').textContent = `Across ${number(data.sources.length)} notification sources`;
  $('sources').replaceChildren();
  const maxUnread = Math.max(1, ...data.sources.map(source => source.unread));
  for (const source of data.sources) {
    const row = element('div', '', 'dashboard-source');
    const heading = element('div', '', 'dashboard-source-heading');
    heading.append(element('strong', source.source), element('span', `${number(source.unread)} unread / ${number(source.total)} total`, 'muted'));
    const meter = document.createElement('meter');
    meter.min = 0;
    meter.max = maxUnread;
    meter.value = source.unread;
    meter.setAttribute('aria-label', `${source.source}: ${source.unread} unread`);
    row.append(heading, meter);
    $('sources').append(row);
  }
  if (!data.sources.length) $('sources').append(element('p', 'No notifications yet. New sources will appear here.', 'empty'));
  $('activity').replaceChildren();
  const maxActivity = Math.max(1, ...data.activity.map(day => day.total));
  for (const day of data.activity) {
    const column = element('div', '', 'dashboard-day');
    const bar = element('div', '', 'dashboard-bar');
    bar.style.height = `${day.total / maxActivity * 120}px`;
    bar.setAttribute('aria-hidden', 'true');
    const label = new Date(`${day.day}T00:00:00Z`).toLocaleDateString(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' });
    column.append(element('strong', number(day.total)), bar, element('span', label, 'muted'));
    $('activity').append(column);
  }
  $('recent').replaceChildren();
  for (const notification of data.recentUnread) {
    const row = element('article', '', 'dashboard-recent-row');
    const link = element('a', notification.title);
    link.href = `/notification.html?id=${encodeURIComponent(notification.id)}`;
    const time = element('time', new Date(notification.received_on).toLocaleString());
    time.dateTime = notification.received_on;
    row.append(link, element('span', notification.notification_source, 'source'), time);
    $('recent').append(row);
  }
  if (!data.recentUnread.length) $('recent').append(element('p', 'You’re all caught up. No unread notifications.', 'empty'));
  $('dashboard').hidden = false;
}

async function load() {
  $('refresh').disabled = true;
  $('status').textContent = 'Loading dashboard…';
  try {
    const response = await fetch('/api/notifications/dashboard', { cache: 'no-store' });
    if (!response.ok) throw new Error(`Could not load dashboard (${response.status}).`);
    render(await response.json());
    $('status').textContent = `Updated ${new Date().toLocaleTimeString()}. Use Refresh to get the latest counts.`;
  } catch (error) {
    $('status').textContent = `${error.message} ${$('dashboard').hidden ? '' : 'Previously loaded data is still shown. '}Use Refresh to try again.`;
  } finally {
    $('refresh').disabled = false;
  }
}

$('refresh').addEventListener('click', load);
load();
