import json
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'harness.py'
SPEC = importlib.util.spec_from_file_location('harness', SCRIPT)
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.test')
        self.git('config', 'user.name', 'Harness Test')
        (self.root / '.gitignore').write_text('.harness/\n')
        (self.root / 'docs/harness').mkdir(parents=True)
        (self.root / 'docs/harness/state.md').write_text('initial\n')
        (self.root / 'code.txt').write_text('initial\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'initial')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root).decode().strip()

    def run_harness(self, *args, success=True, payload=None):
        result = subprocess.run([sys.executable, str(SCRIPT), *args], cwd=self.root,
                                input=json.dumps(payload) if payload is not None else None,
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0 if success else 1, result.stdout + result.stderr)
        return result

    def begin(self, check='true'):
        self.run_harness('begin', '--id', 'example', '--goal', 'test gate',
                         '--files', 'code.txt', 'docs/harness/state.md', '--check', check)
        (self.root / 'code.txt').write_text('changed\n')
        (self.root / 'docs/harness/state.md').write_text('completed task and evidence\n')

    def review(self):
        status = json.loads(self.run_harness('status').stdout)
        self.run_harness('attest-review', '--snapshot', status['snapshot'],
                         '--evidence', 'Independent reviewer: no blocking findings')

    def test_complete_commits_only_task_files(self):
        self.begin()
        self.review()
        self.run_harness('verify')
        self.run_harness('finish', '--message', 'test: complete')
        self.assertEqual(self.git('status', '--porcelain'), '')
        self.assertEqual(self.git('log', '-1', '--format=%s'), 'test: complete')
        task = json.loads(self.run_harness('status').stdout)['task']
        self.assertEqual(task['status'], 'completed')
        self.assertEqual(task['commit'], self.git('rev-parse', 'HEAD'))

    def test_stale_review_or_verification_rejects_commit(self):
        self.begin()
        self.review()
        self.run_harness('verify')
        (self.root / 'code.txt').write_text('changed again\n')
        self.run_harness('finish', '--message', 'bad', success=False)
        self.assertEqual(self.git('log', '-1', '--format=%s'), 'initial')

    def test_failed_check_rejects_commit(self):
        self.begin('exit 7')
        self.review()
        self.run_harness('verify', success=False)
        self.run_harness('finish', '--message', 'bad', success=False)

    def test_outside_ownership_rejects_commit(self):
        self.begin()
        (self.root / 'unowned.txt').write_text('user change\n')
        self.review()
        self.run_harness('verify')
        self.run_harness('finish', '--message', 'bad', success=False)
        self.assertEqual(self.git('diff', '--cached', '--name-only'), '')

    def test_dirty_begin_rejected(self):
        (self.root / 'code.txt').write_text('user change\n')
        self.run_harness('begin', '--id', 'bad', '--goal', 'bad', '--files',
                         'code.txt', '--check', 'true', success=False)

    def test_stop_is_bounded_and_resume_invalidates_gates(self):
        self.begin()
        self.review()
        self.run_harness('verify')
        payload = {'hook_event_name': 'Stop', 'session_id': 'test', 'stop_hook_active': True}
        for _ in range(3):
            self.assertEqual(json.loads(self.run_harness('hook', payload=payload).stdout)['decision'], 'block')
        last = json.loads(self.run_harness('hook', payload=payload).stdout)
        self.assertIn('systemMessage', last)
        self.run_harness('resume')
        self.run_harness('finish', '--message', 'bad', success=False)

    def test_inactive_stop_does_not_restart(self):
        self.assertEqual(json.loads(self.run_harness('hook', payload={'hook_event_name': 'Stop'}).stdout), {})

    def test_verification_that_changes_files_is_not_a_pass(self):
        self.begin("printf 'altered\\n' > code.txt")
        self.review()
        self.run_harness('verify', success=False)
        self.run_harness('finish', '--message', 'bad', success=False)

    def test_deleted_owned_file_can_be_committed(self):
        self.begin()
        (self.root / 'code.txt').unlink()
        self.review()
        self.run_harness('verify')
        self.run_harness('finish', '--message', 'test: delete')
        self.assertEqual(self.git('status', '--porcelain'), '')

    def test_pre_commit_mutation_does_not_mark_completed(self):
        self.begin()
        self.review()
        self.run_harness('verify')
        hook = self.root / '.git/hooks/pre-commit'
        hook.write_text("#!/bin/sh\nprintf 'unreviewed\\n' > code.txt\ngit add code.txt\n")
        hook.chmod(0o755)
        self.run_harness('finish', '--message', 'test: hook mutation', success=False)
        task = json.loads(self.run_harness('status').stdout)['task']
        self.assertEqual(task['status'], 'blocked')
        self.assertNotIn('review', task)

    def test_post_commit_mutation_does_not_mark_completed(self):
        self.begin()
        self.review()
        self.run_harness('verify')
        hook = self.root / '.git/hooks/post-commit'
        hook.write_text("#!/bin/sh\nprintf 'unreviewed\\n' > code.txt\n")
        hook.chmod(0o755)
        self.run_harness('finish', '--message', 'test: post hook mutation', success=False)
        task = json.loads(self.run_harness('status').stdout)['task']
        self.assertEqual(task['status'], 'blocked')

    def test_timeout_kills_descendants(self):
        child = self.root / 'delayed.py'
        child.write_text("import time\nfrom pathlib import Path\ntime.sleep(0.6)\nPath('orphan.txt').write_text('bad')\n")
        with (self.root / 'timeout.log').open('w') as stream:
            code = HARNESS.run_check(f'"{sys.executable}" delayed.py & wait',
                                     self.root, stream, timeout=0.1)
        self.assertEqual(code, 124)
        time.sleep(0.7)
        self.assertFalse((self.root / 'orphan.txt').exists())


if __name__ == '__main__':
    unittest.main()
