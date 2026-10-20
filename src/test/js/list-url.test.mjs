import test from 'node:test';
import assert from 'node:assert/strict';
import { readListUrl, buildListUrl, updateListUrl } from '../../main/resources/static/list-url.js';

const base = 'https://windlass.example';

test('notification bookmarks round-trip special characters and page filters', () => {
  const values = { search: 'release & QA + 日本語', source: 'CI / build? #1', unread: true, page: 3 };
  const url = buildListUrl(`${base}/?tracking=keep#inbox`, values);
  assert.deepEqual(readListUrl('notifications', url.href), values);
  assert.equal(url.searchParams.get('tracking'), 'keep');
  assert.equal(url.hash, '#inbox');
});

test('task bookmarks restore search, status, exclusions, library and page', () => {
  const values = { search: 'Deploy + review', status: 'WAITING', excludeStatus: ['DONE', 'SKIPPED'], library: 'templates', page: 2 };
  const url = buildListUrl(`${base}/tasks.html`, values);
  assert.deepEqual(readListUrl('tasks', url.href), values);
  assert.deepEqual(url.searchParams.getAll('excludeStatus'), ['DONE', 'SKIPPED']);
});

test('clearing filters removes their old values and default page', () => {
  const url = buildListUrl(`${base}/?search=old&source=CI&unread=true&page=3`, { search: '', source: '', unread: false, page: 0 });
  assert.equal(url.href, `${base}/`);
  const tasks = buildListUrl(`${base}/tasks.html?status=DONE&excludeStatus=DONE&excludeStatus=SKIPPED&library=templates&page=2`,
    { status: '', excludeStatus: [], library: '', page: 0 });
  assert.equal(tasks.href, `${base}/tasks.html`);
});

test('invalid page and task enum values safely fall back to defaults', () => {
  for (const page of ['-1', '1.5', 'NaN', '1e2', '2147483648', '999999999999999999999999']) {
    assert.equal(readListUrl('tasks', `${base}/tasks.html?page=${page}`).page, 0);
  }
  const state = readListUrl('tasks', `${base}/tasks.html?status=unknown&excludeStatus=DONE&excludeStatus=unknown&excludeStatus=DONE&library=unknown`);
  assert.equal(state.status, '');
  assert.deepEqual(state.excludeStatus, ['DONE']);
  assert.equal(state.library, '');
  assert.equal(readListUrl('notifications', `${base}/?unread=false`).unread, false);
});

test('URL updates use history without navigation and do not add duplicate entries', () => {
  const calls = [];
  globalThis.window = {
    location: { href: `${base}/tasks.html` },
    history: {
      pushState: (_state, _title, url) => { calls.push('push'); window.location.href = url.href; },
      replaceState: (_state, _title, url) => { calls.push('replace'); window.location.href = url.href; }
    }
  };
  try {
    updateListUrl({ excludeStatus: ['DONE', 'SKIPPED'] });
    updateListUrl({ excludeStatus: ['DONE', 'SKIPPED'] });
    updateListUrl({ search: 'draft' }, true);
    assert.deepEqual(calls, ['push', 'replace']);
    assert.deepEqual(readListUrl('tasks').excludeStatus, ['DONE', 'SKIPPED']);
  } finally { delete globalThis.window; }
});
