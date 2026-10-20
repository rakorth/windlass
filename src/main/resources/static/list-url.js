const taskStatuses = new Set(['PENDING', 'EXECUTING', 'WAITING', 'DONE', 'SKIPPED']);

export function readListUrl(kind, href = window.location.href) {
  const params = new URL(href).searchParams;
  const rawPage = params.get('page') ?? '0';
  const page = /^\d+$/.test(rawPage) && Number.isSafeInteger(Number(rawPage)) && Number(rawPage) <= 2147483647 ? Number(rawPage) : 0;
  const common = { page, search: params.get('search') ?? '' };
  if (kind === 'notifications') {
    return { ...common, source: params.get('source') ?? '', unread: params.get('unread') === 'true' };
  }
  return {
    ...common,
    library: params.get('library') === 'templates' ? 'templates' : '',
    status: taskStatuses.has(params.get('status')) ? params.get('status') : '',
    excludeStatus: [...new Set(params.getAll('excludeStatus').filter(value => taskStatuses.has(value)))]
  };
}

export function buildListUrl(href, values) {
  const url = new URL(href);
  for (const [key, value] of Object.entries(values)) {
    url.searchParams.delete(key);
    if (Array.isArray(value)) value.forEach(item => url.searchParams.append(key, item));
    else if (value) url.searchParams.set(key, String(value));
  }
  return url;
}

export function updateListUrl(values, replace = false) {
  const url = buildListUrl(window.location.href, values);
  if (url.href !== window.location.href) {
    window.history[replace ? 'replaceState' : 'pushState'](null, '', url);
  }
}
