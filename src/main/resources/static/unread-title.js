const baseTitle = document.title;

async function refreshUnreadTitle() {
  try {
    const response = await fetch('/api/notifications?unread=true&size=1', {
      cache: 'no-store',
      signal: AbortSignal.timeout(10000),
    });
    if (response.ok) {
      const { totalElements } = await response.json();
      if (Number.isSafeInteger(totalElements) && totalElements >= 0) {
        document.title = `(${totalElements}) ${baseTitle}`;
      }
    }
  } catch {
    // Keep the last count until the next successful poll.
  } finally {
    setTimeout(refreshUnreadTitle, 30000);
  }
}

refreshUnreadTitle();
