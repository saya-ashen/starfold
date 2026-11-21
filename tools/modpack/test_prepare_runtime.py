"""File-preservation contracts; temporary fixtures never stage a real instance."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    'prepare_runtime', Path(__file__).with_name('prepare-runtime.py'))
staging = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(staging)


class PrepareRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.pack = root / 'pack'
        self.dest = root / 'instance'
        self.cache = root / 'cache'
        for path in (self.pack / 'config', self.pack / 'kubejs',
                     self.pack / 'mods', self.dest, self.cache):
            path.mkdir(parents=True)
        for name, value in (('PACK', self.pack), ('DEST', self.dest), ('CACHE', self.cache)):
            context = patch.object(staging, name, value)
            context.start()
            self.addCleanup(context.stop)
        (self.pack / 'config/current.txt').write_text('new config')

    def put(self, relative, content):
        path = self.dest / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def manifest(self, paths):
        self.put('.prepare-runtime-managed.json', json.dumps(paths))

    def test_update_preserves_world_properties_options_and_unmanaged_config(self):
        originals = {
            'world/level.dat': 'world bytes',
            'server.properties': 'private settings',
            'options.txt': 'player options',
            'config/local.txt': 'local additions',
        }
        for path, text in originals.items():
            self.put(path, text)
        self.put('config/current.txt', 'old config')
        staging.stage_managed_files()
        self.assertEqual('new config', (self.dest / 'config/current.txt').read_text())
        for path, text in originals.items():
            self.assertEqual(text, (self.dest / path).read_text())

    def test_removed_managed_file_is_pruned_but_local_neighbor_remains(self):
        obsolete = self.put('kubejs/obsolete.txt', 'old')
        local = self.put('kubejs/local.txt', 'custom')
        self.manifest(['kubejs/obsolete.txt'])
        staging.stage_managed_files()
        self.assertFalse(obsolete.exists())
        self.assertEqual('custom', local.read_text())
        self.assertEqual(['config/current.txt'], json.loads(
            (self.dest / '.prepare-runtime-managed.json').read_text()))

    def test_first_migration_only_removes_known_legacy_chapter_names(self):
        old = self.put('config/ftbquests/quests/chapters/00.snbt', 'legacy')
        custom = self.put('config/ftbquests/quests/chapters/custom.snbt', 'user chapter')
        staging.stage_managed_files()
        self.assertFalse(old.exists())
        self.assertEqual('user chapter', custom.read_text())

    def test_invalid_manifest_paths_abort_before_copy_or_delete(self):
        target = self.put('config/current.txt', 'unchanged')
        for unsafe in ('../world/level.dat', '/tmp/absolute',
                       'config/../world/level.dat', 'world/level.dat'):
            with self.subTest(path=unsafe):
                self.manifest([unsafe])
                with self.assertRaises(ValueError):
                    staging.stage_managed_files()
                self.assertEqual('unchanged', target.read_text())

    def test_runtime_symlink_is_rejected_without_following_it(self):
        outside = self.dest.parent / 'outside'
        outside.mkdir()
        protected = outside / 'current.txt'
        protected.write_text('external file')
        (self.dest / 'config').symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            staging.stage_managed_files()
        self.assertEqual('external file', protected.read_text())

    def test_bad_archive_aborts_before_mods_or_config_changes(self):
        (self.cache / 'example.jar').write_bytes(b'not the pinned bytes')
        (self.pack / 'mods/example.pw.toml').write_text(
            'filename = "example.jar"\npin = true\n[download]\n'
            'hash-format = "sha256"\nhash = "' + '0' * 64 + '"\n')
        target = self.put('config/current.txt', 'unchanged')
        with self.assertRaises(ValueError):
            staging.main()
        self.assertFalse((self.dest / 'mods').exists())
        self.assertEqual('unchanged', target.read_text())

    def test_unexpected_archive_is_preserved_and_staging_aborts(self):
        archive = self.dest / 'mods/local.jar'
        archive.parent.mkdir()
        archive.write_bytes(b'user mod')
        target = self.put('config/current.txt', 'unchanged')
        with self.assertRaises(ValueError):
            staging.main()
        self.assertEqual(b'user mod', archive.read_bytes())
        self.assertEqual('unchanged', target.read_text())


if __name__ == '__main__':
    unittest.main()
