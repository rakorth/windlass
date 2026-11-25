# json-conf

Open **json-conf** in the navigation, or visit `/json-conf.html`, to list, read,
create, edit, and permanently delete JSON configuration files. The editor shows raw
JSON text. It does not render HTML. Existing filenames cannot be renamed
in the editor. Unsaved edits prompt before switching files or leaving the page.
Refresh discovers files added or changed outside Windlass; reopen a file to read
its latest content. Saving replaces its content, so concurrent external edits
can be overwritten.

## Storage and Docker

Files are UTF-8 text on the server's filesystem, separate from PostgreSQL. Standalone
runs default to `./json-conf`; set `JSON_CONF_DIRECTORY` to another directory.
Windlass creates the directory on startup and requires read/write access.
Only top-level `.json` files are supported, with filenames starting with an ASCII
letter or digit and containing ASCII letters, digits, spaces, dots, underscores,
or hyphens, at most 199 characters. Subdirectories and symlinks are excluded.

The Docker image uses `/data/json-conf`. A named volume mounted at `/data`
preserves the files across container replacement. To use a host folder, bind
mount it at `/data/json-conf`. With a remote Docker daemon, the source path is
on the daemon host.

Prepare a bind-mounted folder before starting Docker. The application runs as UID/GID
10001; the folder must be writable by that account, using ownership or an ACL.
For a new default folder on Linux:

```sh
mkdir -p json-conf
sudo chown 10001:10001 json-conf
```

For an existing folder, grant UID 10001 directory and file read/write access
using your preferred permissions policy. Do not change ownership of an existing
folder without considering other applications that use it. Standalone runs and
Docker can share files by configuring the same host folder.

Back up this directory separately; PostgreSQL backups do not include it.
Deletion in Windlass deletes the host file. No authentication is provided.

## REST API

| Method | Path | Result |
| --- | --- | --- |
| GET | `/api/json-conf` | Sorted array of supported filenames, without pagination |
| GET | `/api/json-conf/{name}` | `{ "name": "settings.json", "content": "{\"enabled\":true}" }` |
| POST | `/api/json-conf` | 201 and Location; body requires `name` and `content` |
| PUT | `/api/json-conf/{name}` | 200; body requires `content`, replaces existing content |
| DELETE | `/api/json-conf/{name}` | 204; permanently deletes the file |

URL-encode filenames in paths. Content must be a string containing exactly one
valid JSON value (object, array, string, number, boolean, or null). Empty strings,
comments, trailing commas, and multiple values are rejected with 400 before
writing. Formatting is preserved. Files added externally can be listed and read
even if their contents are invalid, so they can be repaired in the editor. POST never overwrites an existing entry (409). GET, PUT,
and DELETE return 404 for absent files or symlinks; invalid filenames or missing
content return 400. PUT uses the path filename and does not rename files.
Filesystem failures return 5xx. The json-conf collection differs from other
Windlass collections: it is a complete array of filenames.
