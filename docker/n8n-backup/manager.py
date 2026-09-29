"""Full-volume n8n backups, with a scoped process API and no Docker socket."""
import contextlib
import datetime
import hmac
import json
import os
import posixpath
from pathlib import Path, PurePosixPath
import secrets
import shutil
import sqlite3
import stat
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_ARCHIVE = 512 * 1024 * 1024
MAX_EXPANDED = 2 * 1024 * 1024 * 1024
MAX_ENTRIES = 20000
MANIFEST = 'windlass-n8n.json'


class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def atomic_write(path, data):
    temporary = path.with_suffix('.tmp')
    with temporary.open('wb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    sync_directory(path.parent)


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


class ProcessControl:
    def __init__(self, token, base='http://n8n:5677', readiness='http://n8n:5678/healthz/readiness'):
        self.token, self.base, self.readiness = token, base, readiness

    def request(self, action, method='POST'):
        request = urllib.request.Request(self.base + '/' + action, method=method,
                                         headers={'Authorization': 'Bearer ' + self.token})
        try:
            with urllib.request.urlopen(request, timeout=85) as response:
                return json.load(response)
        except (OSError, ValueError) as error:
            raise ApiError(503, 'Could not control n8n. Check that its container is running.') from error

    def stop(self):
        self.request('stop')

    def ready(self):
        try:
            with urllib.request.urlopen(self.readiness, timeout=2) as response:
                return response.status == 200
        except OSError:
            return False

    def start(self):
        self.request('start')
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(self.readiness, timeout=3) as response:
                    if response.status == 200:
                        return
            except OSError:
                pass
            time.sleep(1)
        raise ApiError(503, 'n8n did not become ready within two minutes.')


class Backups:
    def __init__(self, data, storage, control, version, process):
        self.data, self.storage, self.control = Path(data), Path(storage), Path(control)
        self.version, self.process = version, process
        self.lock = threading.Lock()
        self.phase = 'ready'
        self.error = None
        self.recovery = self.storage / 'recovery.zip'
        self.pending = self.storage / 'restore-pending'
        self.maintenance = self.control / 'maintenance'

    def status(self):
        return {'available': True, 'phase': self.phase, 'error': self.error,
                'n8nVersion': self.version, 'maxUploadBytes': MAX_ARCHIVE,
                'n8nReady': self.phase == 'ready' and self.process.ready(),
                'recoveryAvailable': self.recovery.is_file()}

    @contextlib.contextmanager
    def operation(self, phase):
        if not self.lock.acquire(blocking=False):
            raise ApiError(409, 'Another backup or restore is already in progress.')
        try:
            if self.pending.exists() or self.maintenance.exists():
                raise ApiError(503, 'An interrupted operation needs recovery. Restart the n8n-backup service.')
            self.phase, self.error = phase, None
            yield
        except Exception as error:
            self.error = str(error) if isinstance(error, ApiError) else 'Backup operation failed. Check the backup manager logs.'
            raise
        finally:
            self.phase = 'recovery-required' if self.maintenance.exists() or self.pending.exists() else 'ready'
            self.lock.release()

    def validate_data(self, root):
        try:
            config = root / 'config'
            database = root / 'database.sqlite'
            if config.is_symlink() or database.is_symlink() or not database.is_file():
                raise ValueError('Missing database')
            if config.stat().st_size > 1024 * 1024:
                raise ValueError('Invalid settings')
            settings = json.loads(config.read_text())
            key = settings.get('encryptionKey') if isinstance(settings, dict) else None
            if not isinstance(key, str) or not key.strip():
                raise ValueError('Missing encryption key')
            uri = 'file:' + urllib.parse.quote(str(database.resolve())) + '?mode=ro'
            with contextlib.closing(sqlite3.connect(uri, uri=True)) as connection:
                deadline = time.monotonic() + 30
                connection.set_progress_handler(lambda: int(time.monotonic() > deadline), 10000)
                connection.execute('PRAGMA trusted_schema=OFF')
                if connection.execute('PRAGMA quick_check').fetchone() != ('ok',):
                    raise ValueError('Invalid database')
                tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                if not {'workflow_entity', 'credentials_entity', 'settings', 'migrations'} <= tables:
                    raise ValueError('Not an n8n database')
        except (OSError, ValueError, sqlite3.Error) as error:
            raise ApiError(400, 'The backup must contain a valid n8n SQLite database and its encryption key.') from error

    @staticmethod
    def link_target(name, target):
        resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), target))
        if not target or target.startswith('/') or '\\' in target or resolved == '..' or resolved.startswith('../'):
            raise ApiError(400, 'The backup contains a link outside its data directory.')
        return resolved

    def archive(self, destination):
        self.validate_data(self.data)
        total = 0
        count = 0
        with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
            archive.writestr(MANIFEST, json.dumps({'format': 'windlass-n8n', 'formatVersion': 1,
                               'n8nVersion': self.version,
                               'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat()}))
            for directory, directories, files in os.walk(self.data, followlinks=False):
                for name in sorted(directories + files):
                    path = Path(directory) / name
                    relative = path.relative_to(self.data).as_posix()
                    info = path.lstat()
                    count += 1
                    total += info.st_size if stat.S_ISREG(info.st_mode) else 0
                    if count > MAX_ENTRIES or total > MAX_EXPANDED:
                        raise ApiError(413, 'n8n data exceeds the backup limit (2 GiB or 20,000 entries).')
                    if stat.S_ISLNK(info.st_mode):
                        target = os.readlink(path)
                        self.link_target(relative, target)
                        entry = zipfile.ZipInfo('data/' + relative)
                        entry.create_system = 3
                        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                        archive.writestr(entry, target)
                    elif stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode):
                        archive.write(path, 'data/' + relative)
                    else:
                        raise ApiError(400, 'n8n data contains an unsupported special file.')
                    if destination.stat().st_size > MAX_ARCHIVE:
                        raise ApiError(413, 'The compressed backup exceeds 512 MiB.')
        if destination.stat().st_size > MAX_ARCHIVE:
            raise ApiError(413, 'The compressed backup exceeds 512 MiB.')
        with destination.open('rb') as stream:
            os.fsync(stream.fileno())
        # Never offer a backup that this version of Windlass cannot restore.
        with tempfile.TemporaryDirectory(prefix='work-', dir=self.storage) as work:
            self.extract(destination, Path(work))

    def extract(self, source, destination):
        """Extract into an empty staging directory; never trust zip paths or links."""
        try:
            with zipfile.ZipFile(source) as archive:
                entries = archive.infolist()
                if len(entries) > MAX_ENTRIES + 1 or sum(i.file_size for i in entries) > MAX_EXPANDED:
                    raise ApiError(413, 'The expanded backup exceeds the restore limit.')
                names = set()
                links = {}
                manifest = None
                for info in entries:
                    name = info.filename.rstrip('/')
                    parts = PurePosixPath(name).parts
                    if not name or name in names or name.startswith('/') or '\\' in name or '..' in parts or str(PurePosixPath(name)) != name:
                        raise ApiError(400, 'The backup contains an unsafe or duplicate path.')
                    names.add(name)
                    if name == MANIFEST:
                        if info.file_size > 4096:
                            raise ApiError(400, 'Invalid backup manifest.')
                        manifest = json.loads(archive.read(info))
                        continue
                    if not name.startswith('data/') or len(parts) < 2:
                        raise ApiError(400, 'Choose a backup ZIP downloaded from Windlass.')
                    relative = name[5:]
                    path = destination / relative
                    mode = info.external_attr >> 16
                    if stat.S_ISLNK(mode):
                        if info.file_size > 4096:
                            raise ApiError(400, 'Invalid link in backup.')
                        target = archive.read(info).decode('utf-8')
                        self.link_target(relative, target)
                        links[relative] = target
                    elif info.is_dir():
                        path.mkdir(parents=True, exist_ok=True, mode=0o700)
                    elif stat.S_IFMT(mode) in (0, stat.S_IFREG):
                        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                        with archive.open(info) as incoming, path.open('xb') as output:
                            shutil.copyfileobj(incoming, output, 1024 * 1024)
                        path.chmod(0o700 if mode & 0o111 else 0o600)
                    else:
                        raise ApiError(400, 'The backup contains an unsupported file type.')
                if not isinstance(manifest, dict) or manifest.get('format') != 'windlass-n8n' or manifest.get('formatVersion') != 1:
                    raise ApiError(400, 'Choose a backup ZIP downloaded from Windlass.')
                if manifest.get('n8nVersion') != self.version:
                    raise ApiError(409, 'This backup uses a different n8n version. Run the backup\'s n8n version before restoring.')
                # Create links only after all regular entries have been written.
                # No entry may use a symlink as an ancestor, including link chains.
                for name, target in links.items():
                    resolved = self.link_target(name, target)
                    if any(n == resolved or resolved.startswith(n + '/') for n in links):
                        raise ApiError(400, 'Chained links are not supported in backups.')
                    if any(n.startswith('data/' + name + '/') for n in names):
                        raise ApiError(400, 'A backup entry passes through a symbolic link.')
                    path = destination / name
                    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    path.symlink_to(target)
                self.validate_data(destination)
                return manifest
        except ApiError:
            raise
        except (OSError, ValueError, KeyError, RuntimeError, zipfile.BadZipFile) as error:
            raise ApiError(400, 'The backup ZIP is damaged or has an invalid structure.') from error

    def pause(self):
        atomic_write(self.maintenance, b'maintenance')
        self.process.stop()

    def resume(self):
        self.phase = 'starting'
        self.process.start()
        self.maintenance.unlink(missing_ok=True)
        sync_directory(self.control)

    def backup(self, destination):
        self.phase = 'stopping'
        try:
            self.pause()
            self.phase = 'backing-up'
            self.archive(destination)
        finally:
            self.resume()

    def replace_data(self, staged):
        for path in self.data.iterdir():
            if path.is_dir() and not path.is_symlink():
                shutil.rmtree(path)
            else:
                path.unlink()
        shutil.copytree(staged, self.data, dirs_exist_ok=True, symlinks=True)
        # Make the replacement durable before the pending-restore marker is cleared.
        for directory, _, files in os.walk(self.data):
            for name in files:
                path = Path(directory) / name
                if not path.is_symlink():
                    with path.open('rb') as stream:
                        os.fsync(stream.fileno())
            sync_directory(directory)

    def rollback(self):
        self.phase = 'recovering'
        self.process.stop()
        with tempfile.TemporaryDirectory(prefix='work-', dir=self.storage) as work:
            staged = Path(work) / 'data'
            staged.mkdir()
            self.extract(self.recovery, staged)
            self.replace_data(staged)
        self.process.start()
        self.pending.unlink(missing_ok=True)
        sync_directory(self.storage)
        self.maintenance.unlink(missing_ok=True)
        sync_directory(self.control)

    def restore(self, source):
        self.phase = 'validating'
        with tempfile.TemporaryDirectory(prefix='work-', dir=self.storage) as work:
            staged = Path(work) / 'data'
            staged.mkdir()
            manifest = self.extract(source, staged)
            mutated = False
            try:
                self.phase = 'stopping'
                self.pause()
                self.phase = 'saving-recovery'
                recovery = Path(work) / 'recovery.zip'
                self.archive(recovery)
                os.replace(recovery, self.recovery)
                sync_directory(self.storage)
                atomic_write(self.pending, b'restore-pending')
                mutated = True
                self.phase = 'restoring'
                self.replace_data(staged)
                self.phase = 'starting'
                self.process.start()
                self.pending.unlink()
                sync_directory(self.storage)
                self.maintenance.unlink()
                sync_directory(self.control)
                return {'message': 'Backup restored. n8n is ready.', 'createdAt': manifest.get('createdAt'),
                        'recoveryAvailable': True}
            except Exception as error:
                try:
                    if mutated:
                        self.rollback()
                    else:
                        self.resume()
                except Exception as recovery_error:
                    with contextlib.suppress(Exception):
                        self.process.stop()
                    raise ApiError(503, 'Restore could not complete recovery. n8n remains in maintenance mode; restart the n8n-backup service to retry. The recovery backup is preserved.') from recovery_error
                raise ApiError(503, 'Restore failed. The original n8n data is intact and n8n is running.') from error

    def recover_interrupted(self):
        with self.lock:
            try:
                if self.pending.exists():
                    self.rollback()
                elif self.maintenance.exists():
                    self.resume()
                self.phase, self.error = 'ready', None
            except Exception:
                self.phase = 'recovery-required'
                self.error = 'Interrupted operation needs recovery. Check n8n and restart the n8n-backup service.'


class Handler(BaseHTTPRequestHandler):
    def json(self, status, body):
        encoded = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(encoded)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(encoded)

    def download(self, path, name):
        self.send_response(200)
        self.send_header('Content-Type', 'application/zip')
        self.send_header('Content-Disposition', 'attachment; filename="' + name + '"')
        self.send_header('Content-Length', str(path.stat().st_size))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        with path.open('rb') as stream:
            shutil.copyfileobj(stream, self.wfile, 1024 * 1024)

    def handle_request(self):
        if self.command == 'GET' and self.path == '/healthz':
            return self.json(200, {'status': 'ok'})
        if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + self.server.token):
            return self.json(403, {'message': 'Forbidden'})
        self.connection.settimeout(120)
        backups = self.server.backups
        try:
            if self.command == 'GET' and self.path == '/status':
                return self.json(200, backups.status())
            if self.command == 'GET' and self.path == '/recovery':
                # Downloads must not race replacement of the recovery archive.
                if not backups.lock.acquire(blocking=False):
                    raise ApiError(409, 'Another operation is in progress.')
                try:
                    if not backups.recovery.is_file():
                        raise ApiError(404, 'No recovery backup is available yet.')
                    return self.download(backups.recovery, 'n8n-recovery.zip')
                finally:
                    backups.lock.release()
            if self.command != 'POST' or self.path not in ('/backup', '/restore'):
                raise ApiError(404, 'Not found')
            with backups.operation('preparing'), tempfile.TemporaryDirectory(prefix='work-', dir=backups.storage) as work:
                archive = Path(work) / 'backup.zip'
                if self.path == '/backup':
                    backups.backup(archive)
                    name = 'n8n-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '.zip'
                    return self.download(archive, name)
                try:
                    remaining = int(self.headers.get('Content-Length', '0'))
                except ValueError:
                    raise ApiError(400, 'Invalid upload length')
                if remaining <= 0 or remaining > MAX_ARCHIVE:
                    raise ApiError(413, 'Choose a non-empty backup ZIP no larger than 512 MiB.')
                with archive.open('wb') as output:
                    while remaining:
                        chunk = self.rfile.read(min(1024 * 1024, remaining))
                        if not chunk:
                            raise ApiError(400, 'The backup upload was interrupted.')
                        output.write(chunk)
                        remaining -= len(chunk)
                return self.json(200, backups.restore(archive))
        except ApiError as error:
            self.json(error.status, {'message': str(error)})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.log_error('Backup operation failed; inspect service state before retrying.')
            self.json(500, {'message': 'Backup operation failed. Check the backup manager status before retrying.'})

    do_GET = handle_request
    do_POST = handle_request


def main():
    os.umask(0o077)
    data, storage, control = Path('/n8n-data'), Path('/backups'), Path('/control')
    token_path = control / 'token'
    if not token_path.exists():
        atomic_write(token_path, secrets.token_urlsafe(48).encode())
    token_path.chmod(0o640)
    control.chmod(0o750)
    token = token_path.read_text().strip()
    backups = Backups(data, storage, control, os.environ['N8N_VERSION'], ProcessControl(token))
    server = ThreadingHTTPServer(('0.0.0.0', 5680), Handler)
    server.token, server.backups = token, backups
    # Listen before recovery so n8n can start under Compose's dependency order.
    backups.phase = 'recovering'
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    if backups.maintenance.exists() or backups.pending.exists():
        # Compose starts the n8n container after this server's healthcheck passes.
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                backups.process.request('status', 'GET')
                break
            except ApiError:
                time.sleep(2)
    backups.recover_interrupted()
    thread.join()


if __name__ == '__main__':
    main()
