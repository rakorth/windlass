import contextlib
import json
from pathlib import Path
import sqlite3
import stat
import tempfile
import unittest
import warnings
from unittest.mock import patch
import zipfile

from manager import ApiError, Backups, MANIFEST, atomic_write


class Process:
    def __init__(self):
        self.calls = []
        self.failed_starts = 0

    def stop(self):
        self.calls.append('stop')

    def start(self):
        self.calls.append('start')
        if self.failed_starts:
            self.failed_starts -= 1
            raise ApiError(503, 'Startup failed')

    def ready(self):
        return True


class BackupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.data, self.storage, self.control = (self.root / name for name in ('data', 'backups', 'control'))
        for path in (self.data, self.storage, self.control):
            path.mkdir()
        (self.data / 'config').write_text(json.dumps({'encryptionKey': 'test-only-encryption-key'}))
        with contextlib.closing(sqlite3.connect(self.data / 'database.sqlite')) as connection:
            for table in ('workflow_entity', 'credentials_entity', 'settings', 'migrations'):
                connection.execute(f'CREATE TABLE {table} (id INTEGER PRIMARY KEY, value TEXT)')
            connection.execute("INSERT INTO workflow_entity VALUES (1, 'original workflow')")
            connection.commit()
        (self.data / 'binaryData').mkdir()
        (self.data / 'binaryData' / 'example').write_bytes(b'original attachment')
        self.process = Process()
        self.backups = Backups(self.data, self.storage, self.control, '2.41.3', self.process)
        self.archive = self.root / 'backup.zip'

    def backup(self):
        with self.backups.operation('preparing'):
            self.backups.backup(self.archive)

    def restore(self, source=None):
        with self.backups.operation('preparing'):
            return self.backups.restore(source or self.archive)

    def rewrite(self, transform):
        changed = self.root / 'changed.zip'
        with zipfile.ZipFile(self.archive) as source, zipfile.ZipFile(changed, 'w') as output:
            for info in source.infolist():
                output.writestr(info, transform(info.filename, source.read(info)))
        return changed

    def test_roundtrip_and_downloadable_recovery_preserve_database_key_and_binary_data(self):
        self.backup()
        key = (self.data / 'config').read_bytes()
        with contextlib.closing(sqlite3.connect(self.data / 'database.sqlite')) as connection:
            connection.execute("UPDATE workflow_entity SET value='newer workflow'")
            connection.commit()
        (self.data / 'binaryData' / 'example').write_bytes(b'newer attachment')
        (self.data / 'added-later').write_text('remove during restore')
        result = self.restore()
        self.assertTrue(result['recoveryAvailable'])
        self.assertEqual((self.data / 'config').read_bytes(), key)
        self.assertEqual((self.data / 'binaryData' / 'example').read_bytes(), b'original attachment')
        self.assertFalse((self.data / 'added-later').exists())
        with contextlib.closing(sqlite3.connect(self.data / 'database.sqlite')) as connection:
            self.assertEqual(connection.execute('SELECT value FROM workflow_entity').fetchone()[0], 'original workflow')
        restored = self.root / 'recovery'
        restored.mkdir()
        self.backups.extract(self.backups.recovery, restored)
        self.assertEqual((restored / 'binaryData' / 'example').read_bytes(), b'newer attachment')
        self.assertEqual(self.process.calls, ['stop', 'start', 'stop', 'start'])
        self.assertFalse(self.backups.maintenance.exists())
        self.assertFalse(self.backups.pending.exists())

    def test_invalid_archive_and_paths_are_rejected_before_stopping_n8n(self):
        for name in ('../outside', '/absolute', 'data/../../outside', 'data\\outside', 'data/a/../outside'):
            with self.subTest(name=name):
                with zipfile.ZipFile(self.archive, 'w') as archive:
                    archive.writestr(name, 'unsafe')
                with self.assertRaises(ApiError) as error:
                    self.restore()
                self.assertEqual(error.exception.status, 400)
        self.archive.write_bytes(b'not a zip')
        with self.assertRaises(ApiError):
            self.restore()
        self.assertEqual(self.process.calls, [])
        self.assertTrue((self.data / 'database.sqlite').exists())

    def test_version_mismatch_missing_key_and_broken_database_are_rejected(self):
        self.backup()
        self.process.calls.clear()
        transforms = (
            lambda name, data: data.replace(b'2.41.3', b'2.40.0') if name == MANIFEST else data,
            lambda name, data: b'{}' if name == 'data/config' else data,
            lambda name, data: b'invalid SQLite' if name == 'data/database.sqlite' else data,
        )
        for transform in transforms:
            with self.assertRaises(ApiError):
                self.restore(self.rewrite(transform))
        self.assertEqual(self.process.calls, [])

    def test_unsafe_links_duplicates_and_expansion_limits_are_rejected(self):
        self.backup()
        self.process.calls.clear()
        unsafe = self.root / 'unsafe.zip'
        with zipfile.ZipFile(unsafe, 'w') as archive:
            entry = zipfile.ZipInfo('data/link')
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(entry, '../../outside')
        with self.assertRaises(ApiError):
            self.restore(unsafe)
        with warnings.catch_warnings(), zipfile.ZipFile(unsafe, 'w') as archive:
            warnings.simplefilter('ignore', UserWarning)
            archive.writestr('data/config', '{}')
            archive.writestr('data/config', '{}')
        with self.assertRaises(ApiError):
            self.restore(unsafe)
        with patch('manager.MAX_EXPANDED', 10):
            with self.assertRaises(ApiError) as error:
                self.restore()
            self.assertEqual(error.exception.status, 413)
        self.assertEqual(self.process.calls, [])

    def test_safe_internal_symlink_roundtrip(self):
        (self.data / 'attachment-link').symlink_to('binaryData/example')
        self.backup()
        (self.data / 'attachment-link').unlink()
        self.restore()
        self.assertTrue((self.data / 'attachment-link').is_symlink())
        self.assertEqual((self.data / 'attachment-link').read_bytes(), b'original attachment')

    def test_failed_new_instance_rolls_back_original_data(self):
        self.backup()
        (self.data / 'binaryData' / 'example').write_bytes(b'keep this newer data')
        self.process.failed_starts = 1
        with self.assertRaisesRegex(ApiError, 'original n8n data is intact'):
            self.restore()
        self.assertEqual((self.data / 'binaryData' / 'example').read_bytes(), b'keep this newer data')
        self.assertFalse(self.backups.pending.exists())
        self.assertFalse(self.backups.maintenance.exists())

    def test_interrupted_restore_is_recovered_after_manager_restart(self):
        self.backups.archive(self.backups.recovery)
        atomic_write(self.backups.pending, b'pending')
        atomic_write(self.backups.maintenance, b'maintenance')
        (self.data / 'config').unlink()
        (self.data / 'binaryData' / 'example').write_bytes(b'partially restored')
        self.backups.recover_interrupted()
        self.assertEqual(self.backups.phase, 'ready')
        self.assertEqual((self.data / 'binaryData' / 'example').read_bytes(), b'original attachment')
        self.assertTrue((self.data / 'config').exists())
        self.assertFalse(self.backups.pending.exists())

    def test_failed_rollback_keeps_recovery_and_blocks_more_mutations(self):
        self.backup()
        self.process.failed_starts = 2
        with self.assertRaisesRegex(ApiError, 'maintenance mode'):
            self.restore()
        self.assertTrue(self.backups.pending.exists())
        self.assertTrue(self.backups.maintenance.exists())
        self.assertTrue(self.backups.recovery.exists())
        self.assertEqual(self.backups.status()['phase'], 'recovery-required')
        with self.assertRaises(ApiError):
            self.backup()
        self.backups.recover_interrupted()
        self.assertEqual(self.backups.status()['phase'], 'ready')
        self.assertTrue(self.backups.status()['n8nReady'])

    def test_interrupted_backup_restarts_without_replacing_data(self):
        atomic_write(self.backups.maintenance, b'maintenance')
        self.backups.recover_interrupted()
        self.assertEqual(self.process.calls, ['start'])
        self.assertEqual((self.data / 'binaryData' / 'example').read_bytes(), b'original attachment')

    def test_backup_failure_restarts_n8n_and_concurrent_operations_are_rejected(self):
        with patch.object(self.backups, 'archive', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                self.backup()
        self.assertEqual(self.process.calls, ['stop', 'start'])
        self.assertFalse(self.backups.maintenance.exists())
        with self.backups.operation('backing-up'):
            with self.assertRaises(ApiError) as error:
                with self.backups.operation('restoring'):
                    pass
            self.assertEqual(error.exception.status, 409)


if __name__ == '__main__':
    unittest.main()
