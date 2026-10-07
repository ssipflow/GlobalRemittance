import json
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace


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
        self.git('branch', 'dev')
        self.git('branch', 'test')
        self.git('switch', '-qc', 'feature/example', 'dev')

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
        self.assertEqual(task['status'], 'committed')
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

    def test_protected_and_invalid_branches_cannot_begin(self):
        for branch in ('dev', 'test', 'feature/Bad_Name', 'feature/nested/name'):
            if branch in ('dev', 'test'):
                self.git('switch', '-q', branch)
            else:
                self.git('switch', '-qc', branch)
            self.run_harness('begin', '--id', 'bad', '--goal', 'bad', '--files',
                             'code.txt', '--check', 'true', success=False)

    def test_main_and_detached_head_cannot_begin(self):
        self.git('switch', '-qc', 'main', 'dev')
        self.run_harness('begin', '--id', 'bad', '--goal', 'bad', '--files',
                         'code.txt', '--check', 'true', success=False)
        self.git('checkout', '-q', '--detach')
        self.run_harness('begin', '--id', 'bad', '--goal', 'bad', '--files',
                         'code.txt', '--check', 'true', success=False)

    def test_switching_to_same_head_branch_invalidates_task(self):
        self.begin()
        self.review()
        self.run_harness('verify')
        old = json.loads(self.run_harness('status').stdout)['snapshot']
        self.git('switch', '-qc', 'feature/other')
        new = json.loads(self.run_harness('status').stdout)['snapshot']
        self.assertNotEqual(old, new)
        self.run_harness('finish', '--message', 'bad', success=False)
        self.run_harness('verify', success=False)

    def test_branch_must_contain_current_dev(self):
        self.git('switch', '-q', 'dev')
        (self.root / 'code.txt').write_text('dev advancement\n')
        self.git('add', 'code.txt')
        self.git('commit', '-qm', 'dev advanced')
        self.git('switch', '-q', 'feature/example')
        self.run_harness('begin', '--id', 'bad', '--goal', 'bad', '--files',
                         'code.txt', '--check', 'true', success=False)

    def test_hotfix_from_dev_can_complete(self):
        self.git('switch', '-qc', 'hotfix/example', 'dev')
        self.begin()
        self.review()
        self.run_harness('verify')
        self.run_harness('finish', '--message', 'fix: complete')
        self.assertEqual(self.git('status', '--porcelain'), '')

    def prepare_publish(self):
        self.begin()
        self.review()
        self.run_harness('verify')
        self.run_harness('finish', '--message', 'test: prepare PR')
        self.git('remote', 'add', 'origin', 'git@github.com:example/remittance.git')
        body = self.root / '.harness/pr-body.md'
        body.write_text('Concrete change and validation evidence\n')
        self.publish_args = SimpleNamespace(repo='example/remittance', title='test: PR', body_file=str(body))
        self.pr = {'number': 1, 'url': 'https://github.com/example/remittance/pull/1',
                   'state': 'OPEN', 'headRefOid': self.git('rev-parse', 'HEAD'),
                   'isCrossRepository': False, 'autoMergeRequest': None}

    def test_publish_creates_pr_without_comment_approval_or_merge(self):
        self.prepare_publish()
        with patch.object(HARNESS, 'remote_output', side_effect=['[]', '', self.pr['url'], json.dumps([self.pr])]) as remote:
            HARNESS.publish(self.root, self.publish_args)
        commands = [call.args[1:4] for call in remote.call_args_list]
        self.assertEqual(commands, [('gh', 'pr', 'list'), ('git', 'push', '--set-upstream'),
                                    ('gh', 'pr', 'create'), ('gh', 'pr', 'list')])
        task = HARNESS.load(self.root)
        self.assertEqual(task['status'], 'completed')
        self.assertEqual(task['pr']['head'], self.pr['headRefOid'])

    def test_publish_reuses_existing_pr(self):
        self.prepare_publish()
        with patch.object(HARNESS, 'remote_output', side_effect=[json.dumps([self.pr]), '', json.dumps([self.pr])]) as remote:
            HARNESS.publish(self.root, self.publish_args)
        self.assertFalse(any('create' in call.args for call in remote.call_args_list))

    def test_publish_failure_is_retryable_without_new_commit(self):
        self.prepare_publish()
        failure = subprocess.CalledProcessError(1, ['gh', 'pr', 'create'])
        with patch.object(HARNESS, 'remote_output', side_effect=['[]', '', failure]):
            with self.assertRaises(subprocess.CalledProcessError):
                HARNESS.publish(self.root, self.publish_args)
        self.assertEqual(HARNESS.load(self.root)['status'], 'committed')
        with patch.object(HARNESS, 'remote_output', side_effect=[json.dumps([self.pr]), '', json.dumps([self.pr])]):
            HARNESS.publish(self.root, self.publish_args)
        self.assertEqual(self.git('rev-parse', 'HEAD'), self.pr['headRefOid'])

    def test_publish_rejects_wrong_repository_without_network(self):
        self.prepare_publish()
        self.publish_args.repo = 'other/repository'
        with patch.object(HARNESS, 'remote_output') as remote:
            with self.assertRaises(ValueError):
                HARNESS.publish(self.root, self.publish_args)
            remote.assert_not_called()

    def test_publish_rejects_different_push_destination_without_network(self):
        self.prepare_publish()
        self.git('config', 'remote.origin.pushurl', 'git@github.com:other/repository.git')
        with patch.object(HARNESS, 'remote_output') as remote:
            with self.assertRaises(ValueError):
                HARNESS.publish(self.root, self.publish_args)
            remote.assert_not_called()

    def test_publish_rejects_multiple_push_destinations_without_network(self):
        self.prepare_publish()
        self.git('config', '--add', 'remote.origin.pushurl', 'git@github.com:example/remittance.git')
        self.git('config', '--add', 'remote.origin.pushurl', 'git@github.com:other/repository.git')
        with patch.object(HARNESS, 'remote_output') as remote:
            with self.assertRaises(ValueError):
                HARNESS.publish(self.root, self.publish_args)
            remote.assert_not_called()

    def test_publish_rejects_uncommitted_files_without_network(self):
        self.prepare_publish()
        (self.root / 'code.txt').write_text('unreviewed change\n')
        with patch.object(HARNESS, 'remote_output') as remote:
            with self.assertRaises(ValueError):
                HARNESS.publish(self.root, self.publish_args)
            remote.assert_not_called()

    def test_publish_respects_closed_or_merged_pr(self):
        self.prepare_publish()
        for state in ('CLOSED', 'MERGED'):
            self.pr['state'] = state
            with patch.object(HARNESS, 'remote_output', return_value=json.dumps([self.pr])) as remote:
                with self.assertRaises(ValueError):
                    HARNESS.publish(self.root, self.publish_args)
                self.assertEqual(remote.call_count, 1)

    def test_publish_rejects_wrong_pr_head(self):
        self.prepare_publish()
        self.pr['headRefOid'] = '0' * 40
        with patch.object(HARNESS, 'remote_output', side_effect=[json.dumps([self.pr]), '', json.dumps([self.pr])]):
            with self.assertRaises(ValueError):
                HARNESS.publish(self.root, self.publish_args)
        self.assertEqual(HARNESS.load(self.root)['status'], 'committed')

    def test_committed_task_stays_unfinished_until_pr(self):
        self.prepare_publish()
        payload = {'hook_event_name': 'Stop'}
        self.assertEqual(json.loads(self.run_harness('hook', payload=payload).stdout)['decision'], 'block')
        self.run_harness('block', '--reason', 'network unavailable')
        self.run_harness('resume')
        self.assertEqual(HARNESS.load(self.root)['status'], 'committed')

    def test_reopen_after_committed_memory_edit_preserves_history(self):
        self.prepare_publish()
        old_commit = self.git('rev-parse', 'HEAD')
        (self.root / 'docs/harness/state.md').write_text('post-commit task correction\n')
        self.run_harness('reopen')
        self.assertEqual(HARNESS.load(self.root)['status'], 'active')
        self.run_harness('finish', '--message', 'bad', success=False)
        self.review()
        self.run_harness('verify')
        self.run_harness('finish', '--message', 'test: follow-up')
        self.assertEqual(self.git('rev-parse', 'HEAD^'), old_commit)
        self.pr['headRefOid'] = self.git('rev-parse', 'HEAD')
        with patch.object(HARNESS, 'remote_output', side_effect=['[]', '', self.pr['url'], json.dumps([self.pr])]):
            HARNESS.publish(self.root, self.publish_args)

    def test_publish_rejects_existing_auto_merge(self):
        self.prepare_publish()
        self.pr['autoMergeRequest'] = {'enabledAt': '2026-10-08'}
        with patch.object(HARNESS, 'remote_output', return_value=json.dumps([self.pr])) as remote:
            with self.assertRaises(ValueError):
                HARNESS.publish(self.root, self.publish_args)
            self.assertEqual(remote.call_count, 1)


if __name__ == '__main__':
    unittest.main()
