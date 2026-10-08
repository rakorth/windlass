export function createTaskEditor(onSaved) {
  const container = document.createElement('div');
  container.innerHTML = `
  <dialog id="editor" class="task-editor">
    <form id="form">
      <div class="dialog-heading"><h2 id="editor-title">New task</h2><button type="button" id="close" class="secondary" aria-label="Close editor">✕</button></div>
      <fieldset id="editor-fields">
        <label for="name">Name <span class="required">*</span></label><input id="name" required maxlength="200">
        <label for="task-status">Status</label><select id="task-status"><option value="PENDING">Pending</option><option value="DONE">Done</option><option value="SKIPPED">Skipped</option><option value="WAITING">Waiting</option></select>
        <label for="notify-me-on">Notify me on</label><input id="notify-me-on" type="datetime-local" step="any">
        <label for="description">Description</label><textarea id="description" rows="3" maxlength="10000"></textarea>
        <label for="metadata">Metadata (JSON object)</label><textarea id="metadata" rows="3" spellcheck="false">{}</textarea>
        <label for="links">Links (JSON object)</label><textarea id="links" rows="3" spellcheck="false" aria-describedby="links-help">{}</textarea><small id="links-help">Map labels to full HTTP or HTTPS URLs, e.g. {"Issue": "https://github.com"}.</small>
        <label for="webhooks">Webhooks (one URL per line)</label><textarea id="webhooks" rows="3" spellcheck="false"></textarea><small>Send a JSON POST when the status changes. Failed deliveries appear in Notifications.</small>
        <div class="list-heading"><h3>Steps</h3><button id="add-step" type="button" class="secondary">+ Add step</button></div>
        <p class="muted">Steps run in the order shown. Use the arrows to reorder them.</p>
        <div id="step-editors"></div>
      </fieldset>
      <p id="form-error" role="alert"></p>
      <div class="actions"><button id="cancel" type="button" class="secondary">Cancel</button><button id="save" type="submit">Save task</button></div>
    </form>
  </dialog>
  <template id="step-template">
    <fieldset class="step-editor">
      <legend></legend>
      <label>Step name <span class="required">*</span><input class="step-name" required maxlength="200"></label>
      <label>Status<select class="step-status"><option value="PENDING">Pending</option><option value="DONE">Done</option><option value="SKIPPED">Skipped</option></select></label>
      <details><summary>Step metadata, links, and webhooks</summary><label>Metadata (JSON object)<textarea class="step-metadata" rows="3" spellcheck="false">{}</textarea></label><label>Links (JSON object)<textarea class="step-links" rows="3" spellcheck="false">{}</textarea></label><label>Webhooks (one URL per line)<textarea class="step-webhooks" rows="3" spellcheck="false"></textarea></label></details>
      <div class="actions"><button type="button" class="up secondary" aria-label="Move step up">↑</button><button type="button" class="down secondary" aria-label="Move step down">↓</button><button type="button" class="remove danger">Remove step</button></div>
    </fieldset>
  </template>
  `;
  document.body.append(container);
  const $ = id => container.querySelector(`#${id}`);
  let editingTemplate = false;
  let editingId = null;
  let saving = false;

  function numberSteps() {
    const rows = [...$('step-editors').children];
    rows.forEach((row, index) => {
      row.querySelector('legend').textContent = `Step ${index + 1}`;
      row.querySelector('.up').disabled = index === 0;
      row.querySelector('.down').disabled = index === rows.length - 1;
    });
  }

  function addStep(step = {}) {
    const row = $('step-template').content.firstElementChild.cloneNode(true);
    row.querySelector('.step-name').value = step.name ?? '';
    row.querySelector('.step-status').value = step.status ?? 'PENDING';
    row.querySelector('.step-metadata').value = JSON.stringify(step.metadata ?? {}, null, 2);
    row.querySelector('.step-webhooks').value = (step.webhooks ?? []).join('\n');
    row.querySelector('.step-links').value = JSON.stringify(step.links ?? {}, null, 2);
    row.querySelector('.remove').addEventListener('click', () => { row.remove(); numberSteps(); });
    row.querySelector('.up').addEventListener('click', () => { if (row.previousElementSibling) row.previousElementSibling.before(row); numberSteps(); });
    row.querySelector('.down').addEventListener('click', () => { if (row.nextElementSibling) row.nextElementSibling.after(row); numberSteps(); });
    $('step-editors').append(row);
    numberSteps();
  }

  function openEditor(task = null, templateLibrary = false, copy = false) {
    editingTemplate = templateLibrary;
    editingId = copy ? null : task?.id ?? null;
    const kind = editingTemplate ? 'template' : 'task';
    $('editor-title').textContent = `${editingId ? 'Edit' : 'New'} ${kind}`;
    $('save').textContent = `Save ${kind}`;
    $('name').value = task?.name ?? '';
    $('description').value = task?.description ?? '';
    $('task-status').value = task?.status ?? 'PENDING';
    const notifyDate = task?.notifyMeOn ? new Date(task.notifyMeOn) : null;
    $('notify-me-on').value = notifyDate
      ? new Date(notifyDate.getTime() - notifyDate.getTimezoneOffset() * 60000).toISOString().slice(0, -1) : '';
    $('metadata').value = JSON.stringify(task?.metadata ?? {}, null, 2);
    $('webhooks').value = (task?.webhooks ?? []).join('\n');
    $('links').value = JSON.stringify(task?.links ?? {}, null, 2);
    $('step-editors').replaceChildren();
    (task?.steps ?? []).forEach(addStep);
    $('form-error').textContent = '';
    $('editor').showModal();
    $('name').focus();
  }

  function parseWebhooks(input) {
    const urls = input.value.split('\n').map(url => url.trim()).filter(Boolean);
    for (const url of urls) {
      let valid = false;
      try { valid = /^https?:\/\//i.test(url) && !!new URL(url).hostname; }
      catch { /* Report validation below. */ }
      if (!valid) throw new Error('Webhooks require full HTTP or HTTPS URLs.');
    }
    return urls;
  }

  function parseObject(input, label, links = false) {
    let value;
    try { value = JSON.parse(input.value || '{}'); }
    catch { throw new Error(`${label} must be valid JSON.`); }
    if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error(`${label} must be a JSON object.`);
    if (links) for (const [name, url] of Object.entries(value)) {
      let valid = false;
      try { valid = name.trim() && typeof url === 'string' && /^https?:\/\//i.test(url) && !!new URL(url).hostname; }
      catch { /* Report validation below. */ }
      if (!valid) throw new Error(`${label} require nonblank labels and full HTTP or HTTPS URLs.`);
    }
    return value;
  }

  $('form').addEventListener('submit', async event => {
    event.preventDefault();
    if (saving) return;
    $('form-error').textContent = '';
    try {
      if (!$('name').value.trim()) throw new Error('Task name is required.');
      const steps = [...$('step-editors').children].map((row, index) => {
        const name = row.querySelector('.step-name').value.trim();
        if (!name) throw new Error(`Step ${index + 1} needs a name.`);
        return { name, webhooks: parseWebhooks(row.querySelector('.step-webhooks')), status: row.querySelector('.step-status').value,
          metadata: parseObject(row.querySelector('.step-metadata'), `Step ${index + 1} metadata`),
          links: parseObject(row.querySelector('.step-links'), `Step ${index + 1} links`, true) };
      });
      const body = { webhooks: parseWebhooks($('webhooks')), notifyMeOn: $('notify-me-on').value ? new Date($('notify-me-on').value).toISOString() : null, status: $('task-status').value, name: $('name').value.trim(), description: $('description').value,
        metadata: parseObject($('metadata'), 'Task metadata'), links: parseObject($('links'), 'Task links', true), steps };
      saving = true;
      $('editor-fields').disabled = $('save').disabled = $('close').disabled = $('cancel').disabled = true;
      const response = await fetch(`/api/${editingTemplate ? 'task-templates' : 'tasks'}${editingId ? `/${encodeURIComponent(editingId)}` : ''}`, {
        method: editingId ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
      });
      if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.detail || error.message || `Request failed (${response.status}). Please try again.`);
      }
      const savedTask = await response.json();
      $('editor').close();
      await onSaved(savedTask, editingTemplate);
    } catch (error) { $('form-error').textContent = error.message; }
    finally {
      saving = false;
      $('editor-fields').disabled = $('save').disabled = $('close').disabled = $('cancel').disabled = false;
    }
  });
  $('add-step').addEventListener('click', () => { addStep(); $('step-editors').lastElementChild.querySelector('input').focus(); });
  for (const id of ['close', 'cancel']) $(id).addEventListener('click', () => $('editor').close());
  $('editor').addEventListener('cancel', event => { if (saving) event.preventDefault(); });
  return { open: openEditor, get saving() { return saving; } };
}
