import test from 'node:test';
import assert from 'node:assert/strict';
import { readListUrl, buildListUrl, updateListUrl } from '../../main/resources/static/list-url.js';

const base = 'https://windlass.example';

test('notification bookmarks round-trip special characters and page filters', () => {
  const values = { search: 'release & QA + 日本語', source: 'CI / build? #1', unread: true, page: 3 };
  const url = buildListUrl(`${base}/?tracking=keep#inbox`, values);
  assert.deepEqual(readListUrl(url.href), values);
  assert.equal(url.searchParams.get('tracking'), 'keep');
  assert.equal(url.hash, '#inbox');
});

test('clearing filters removes their old values and default page', () => {
  const url = buildListUrl(`${base}/?search=old&source=CI&unread=true&page=3`, { search: '', source: '', unread: false, page: 0 });
  assert.equal(url.href, `${base}/`);
});

test('invalid page numbers fall back to page zero', () => {
  for (const page of ['-1', '1.5', 'NaN', '1e2', '2147483648', '999999999999999999999999']) {
    assert.equal(readListUrl(`${base}/?page=${page}`).page, 0);
  }
  assert.equal(readListUrl(`${base}/?unread=false`).unread, false);
});

test('URL updates use history without navigation and do not add duplicate entries', () => {
  const calls = [];
  globalThis.window = {
    location: { href: `${base}/` },
    history: {
      pushState: (_state, _title, url) => { calls.push('push'); window.location.href = url.href; },
      replaceState: (_state, _title, url) => { calls.push('replace'); window.location.href = url.href; }
    }
  };
  try {
    updateListUrl({ source: 'CI', unread: true });
    updateListUrl({ source: 'CI', unread: true });
    updateListUrl({ search: 'draft' }, true);
    assert.deepEqual(calls, ['push', 'replace']);
    assert.equal(readListUrl().source, 'CI');
    assert.equal(readListUrl().unread, true);
  } finally { delete globalThis.window; }
});
