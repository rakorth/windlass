# Reusable task templates

Step descriptions are copied with the rest of each step. They are optional,
limited to 10,000 characters, and default to an empty string when omitted or null.

Templates hold all eight editable task fields: `name`, `description`, `status`,
`notifyMeOn`, `links`, `metadata`, `webhooks`, and ordered `steps`. They live in a separate
library and do not appear as actual tasks. See the [data model](data-model.md#task)
for defaults and validation and the [API reference](api.md#task-templates)
for all endpoints.

## Create and use a template in the UI

1. Open **Tasks** (`/tasks.html`) and select **Task templates**.
2. Select **+ New template**, fill in its fields and steps, then **Save template**.
3. Select **Use template** on its card. This opens a new task with the fields
   filled in; no task is created yet.
4. Adjust the task as needed and select **Save task**. Cancel saves nothing.

To reuse an existing task as a template, select **Save as template** on its task
card, adjust the copy, then **Save template**. Use **Edit** or **Delete** in the
template library to maintain templates.

Each saved task has a new ID. Changes to a template or one of its copies do not
change the others. Deleting a template leaves existing tasks intact. Both task
and step statuses, plus reminder timestamps, are copied exactly; review them
when starting fresh work. Reminder dates are absolute, not relative offsets.

## Create a template through REST

Use your deployment's reachable base URL. Standalone runs default to
`http://localhost:8080`; Docker Compose publishes `http://localhost:6080`.
Save this payload as `template.json`:

```json
{
  "name": "Release checklist",
  "description": "Prepare and publish a release.",
  "status": "PENDING",
  "notifyMeOn": null,
  "links": {},
  "metadata": {"category": "release"},
  "steps": [
    {"name": "Build and test", "status": "PENDING", "metadata": {}, "links": {}},
    {"name": "Deploy", "status": "PENDING", "metadata": {}, "links": {}}
  ]
}
```

```sh
WINDLASS_BASE_URL="http://localhost:8080"
curl --fail-with-body --silent --show-error --max-time 15 \
  -H 'Content-Type: application/json' --data-binary @template.json \
  "$WINDLASS_BASE_URL/api/task-templates"
```

The response is HTTP 201 with the saved template's `id` and `Location`. Templates
are user-created; no built-in template is seeded. Names and metadata markers
are not unique constraints.

## Copy through REST

Set `template_id` to the actual ID from a template response. For an exact copy:

```sh
curl --fail-with-body --silent --show-error --max-time 15 \
  -X POST "$WINDLASS_BASE_URL/api/task-templates/$template_id/tasks"
```

This takes no body and creates a task immediately, returning HTTP 201, the task,
and its new `/api/tasks/{id}` location. It copies all fields, including statuses
and the reminder instant, without modifying the template. Each call creates a
new task; it cannot customize fields through an override body.

To customize before creation, GET `/api/task-templates/{id}`, select its eight
writable fields with a JSON parser, omit `id`, and apply your changes. Serialize
the result as `task.json`, then submit:

```sh
curl --fail-with-body --silent --show-error --max-time 15 \
  -H 'Content-Type: application/json' --data-binary @task.json \
  "$WINDLASS_BASE_URL/api/tasks"
```

To update a template, GET it first and PUT the complete edited payload to
`/api/task-templates/{id}`. Omitted optional fields reset to defaults, including
empty steps and webhook lists, `PENDING` status, and null reminder. The template library
supports the same pagination, search, and status filters as tasks. Search matches
names, descriptions, and metadata; it does not search links or steps.

Missing IDs return 404 and invalid input returns 400. Creation is not idempotent:
inspect saved records before retrying a POST after a timeout or uncertain result.
Agents can use the [Windlass REST skill](../skills/windlass-rest/SKILL.md) and its
[task reference](../skills/windlass-rest/references/tasks.md) for these workflows.
