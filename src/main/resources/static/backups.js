const backupElement = id => document.getElementById(id);
const backupApi = '/api/automations/backups';
let backupBusy = false;
let backupStatus = null;
let statusRequest = null;

const phases = {
  ready: 'Ready', preparing: 'Preparing…', stopping: 'Stopping n8n…',
  'backing-up': 'Creating backup…', validating: 'Checking backup…',
  'saving-recovery': 'Saving a recovery copy…', restoring: 'Restoring n8n…',
  starting: 'Waiting for n8n to start…', recovering: 'Recovering the previous data…',
  'recovery-required': 'Recovery needs attention'
};

function updateBackupControls() {
  const ready = backupStatus?.available && backupStatus.n8nReady && backupStatus.phase === 'ready' && !backupBusy;
  backupElement('backup-download').disabled = !ready;
  backupElement('restore-file').disabled = !ready;
  backupElement('restore-confirm').disabled = !ready;
  backupElement('backup-restore').disabled = !ready || !backupElement('restore-file').files.length || !backupElement('restore-confirm').checked;
  backupElement('recovery-section').hidden = !backupStatus?.recoveryAvailable;
  backupElement('recovery-download').disabled = backupBusy || !backupStatus?.recoveryAvailable || !['ready', 'recovery-required'].includes(backupStatus?.phase);
  backupElement('restore-form').setAttribute('aria-busy', String(backupBusy));
}

async function checkedResponse(response) {
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.detail || error.message || `Request failed (${response.status}). Refresh status before trying again.`);
  }
  return response;
}

async function refreshBackupStatus() {
  if (statusRequest) return statusRequest;
  statusRequest = (async () => {
    try {
      const response = await checkedResponse(await fetch(`${backupApi}/status`, {cache: 'no-store'}));
      backupStatus = await response.json();
      const phase = backupStatus.phase === 'ready' && !backupStatus.n8nReady
        ? 'n8n is starting or unavailable' : phases[backupStatus.phase] || 'Working…';
      backupElement('backup-state').textContent = backupStatus.available
        ? `n8n ${backupStatus.n8nVersion} · ${phase}${backupStatus.error ? ` — ${backupStatus.error}` : ''}`
        : backupStatus.message;
    } catch (error) {
      backupStatus = null;
      backupElement('backup-state').textContent = error.message;
    } finally {
      updateBackupControls();
    }
  })();
  try { await statusRequest; } finally { statusRequest = null; }
}

async function runBackupOperation(action) {
  if (backupBusy) return;
  backupBusy = true;
  backupElement('backup-message').textContent = 'Working… Keep this page open until the operation completes.';
  updateBackupControls();
  const timer = setInterval(refreshBackupStatus, 2000);
  try {
    await action();
  } catch (error) {
    backupElement('backup-message').textContent = `${error.message} Refresh status before retrying.`;
  } finally {
    clearInterval(timer);
    backupBusy = false;
    await refreshBackupStatus();
    updateBackupControls();
  }
}

async function downloadBackup(path, method, fallbackName) {
  const response = await checkedResponse(await fetch(path, {
    method, headers: {'X-Windlass-Backup': '1'}, cache: 'no-store'
  }));
  const blob = await response.blob();
  const filename = response.headers.get('Content-Disposition')?.match(/filename="([^"]+)"/)?.[1] || fallbackName;
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
  backupElement('backup-message').textContent = 'Backup is ready. Save the download somewhere secure.';
}

backupElement('backup-download').addEventListener('click', () => runBackupOperation(
  () => downloadBackup(backupApi, 'POST', 'n8n-backup.zip')
));
backupElement('recovery-download').addEventListener('click', () => runBackupOperation(
  () => downloadBackup(`${backupApi}/recovery`, 'GET', 'n8n-recovery.zip')
));
backupElement('backup-refresh').addEventListener('click', refreshBackupStatus);
backupElement('restore-file').addEventListener('change', () => {
  backupElement('restore-confirm').checked = false;
  updateBackupControls();
});
backupElement('restore-confirm').addEventListener('change', updateBackupControls);
backupElement('restore-form').addEventListener('submit', event => {
  event.preventDefault();
  const file = backupElement('restore-file').files[0];
  if (!file || !backupElement('restore-confirm').checked || backupBusy) return;
  if (!file.size || file.size > (backupStatus?.maxUploadBytes || 512 * 1024 * 1024)) {
    backupElement('backup-message').textContent = 'Choose a non-empty backup ZIP no larger than 512 MiB.';
    return;
  }
  runBackupOperation(async () => {
    const body = new FormData();
    body.append('file', file);
    body.append('confirm', 'RESTORE');
    const response = await checkedResponse(await fetch(`${backupApi}/restore`, {
      method: 'POST', headers: {'X-Windlass-Backup': '1'}, body
    }));
    const result = await response.json();
    backupElement('backup-message').textContent = result.message;
    backupElement('restore-form').reset();
  });
});
window.addEventListener('beforeunload', event => {
  if (backupBusy) { event.preventDefault(); event.returnValue = ''; }
});
refreshBackupStatus();
