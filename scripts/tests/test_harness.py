# 임시 저장소와 가짜 원격 도구로 하네스 상태 전이·커밋·PR 경계를 검증한다.
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


# 실제 작업 환경을 격리한 상태에서 성공 경로와 실패 경계를 검증한다.
class HarnessTest(unittest.TestCase):
    # 각 테스트에 독립된 임시 저장소와 고정 입력을 준비한다.
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

    # 임시 저장소에서만 Git 명령을 실행한다.
    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root).decode().strip()

    # 하네스 CLI를 실행하고 기대한 종료 상태를 확인한다.
    def run_harness(self, *args, success=True, payload=None):
        result = subprocess.run([sys.executable, str(SCRIPT), *args], cwd=self.root,
                                input=json.dumps(payload) if payload is not None else None,
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0 if success else 1, result.stdout + result.stderr)
        return result

    # 소유 파일과 검증 명령을 등록해 테스트 작업을 시작한다.
    def begin(self, check='true'):
        self.run_harness('begin', '--id', 'example', '--goal', 'test gate',
                         '--files', 'code.txt', 'docs/harness/state.md', '--check', check)
        (self.root / 'code.txt').write_text('changed\n')
        (self.root / 'docs/harness/state.md').write_text('completed task and evidence\n')

    # 현재 스냅샷에만 리뷰 통과 증거를 붙인다.
    def review(self):
        status = json.loads(self.run_harness('status').stdout)
        self.run_harness('attest-review', '--snapshot', status['snapshot'],
                         '--evidence', 'Independent reviewer: no blocking findings')

    # 등록한 소유 파일만 커밋되는지 확인한다.
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

    # 오래된 리뷰·검증 증거로 커밋할 수 없는지 확인한다.
    def test_stale_review_or_verification_rejects_commit(self):
        self.begin()
        self.review()
        self.run_harness('verify')
        (self.root / 'code.txt').write_text('changed again\n')
        self.run_harness('finish', '--message', 'bad', success=False)
        self.assertEqual(self.git('log', '-1', '--format=%s'), 'initial')

    # 검증 실패 시 커밋을 거부하는지 확인한다.
    def test_failed_check_rejects_commit(self):
        self.begin('exit 7')
        self.review()
        self.run_harness('verify', success=False)
        self.run_harness('finish', '--message', 'bad', success=False)

    # 소유 범위 밖 변경이 있으면 종료를 거부하는지 확인한다.
    def test_outside_ownership_rejects_commit(self):
        self.begin()
        (self.root / 'unowned.txt').write_text('user change\n')
        self.review()
        self.run_harness('verify')
        self.run_harness('finish', '--message', 'bad', success=False)
        self.assertEqual(self.git('diff', '--cached', '--name-only'), '')

    # 기존 변경이 있는 작업 트리에서는 시작을 거부하는지 확인한다.
    def test_dirty_begin_rejected(self):
        (self.root / 'code.txt').write_text('user change\n')
        self.run_harness('begin', '--id', 'bad', '--goal', 'bad', '--files',
                         'code.txt', '--check', 'true', success=False)

    # 반복 개선 횟수를 제한하고 재개 시 기존 통과 증거를 무효화하는지 확인한다.
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

    # 비활성 작업의 stop 훅이 루프를 다시 시작하지 않는지 확인한다.
    def test_inactive_stop_does_not_restart(self):
        self.assertEqual(json.loads(self.run_harness('hook', payload={'hook_event_name': 'Stop'}).stdout), {})

    # 검증 중 파일이 바뀌면 성공으로 기록하지 않는지 확인한다.
    def test_verification_that_changes_files_is_not_a_pass(self):
        self.begin("printf 'altered\\n' > code.txt")
        self.review()
        self.run_harness('verify', success=False)
        self.run_harness('finish', '--message', 'bad', success=False)

    # 소유 파일 삭제도 정상 변경으로 커밋되는지 확인한다.
    def test_deleted_owned_file_can_be_committed(self):
        self.begin()
        (self.root / 'code.txt').unlink()
        self.review()
        self.run_harness('verify')
        self.run_harness('finish', '--message', 'test: delete')
        self.assertEqual(self.git('status', '--porcelain'), '')

    # 커밋 전 훅의 파일 변경을 완료로 오인하지 않는지 확인한다.
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

    # 커밋 후 훅의 파일 변경을 완료로 오인하지 않는지 확인한다.
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

    # 시간 초과 시 자손 프로세스까지 종료되는지 확인한다.
    def test_timeout_kills_descendants(self):
        child = self.root / 'delayed.py'
        child.write_text("import time\nfrom pathlib import Path\ntime.sleep(0.6)\nPath('orphan.txt').write_text('bad')\n")
        with (self.root / 'timeout.log').open('w') as stream:
            code = HARNESS.run_check(f'"{sys.executable}" delayed.py & wait',
                                     self.root, stream, timeout=0.1)
        self.assertEqual(code, 124)
        time.sleep(0.7)
        self.assertFalse((self.root / 'orphan.txt').exists())

    # 보호 브랜치와 잘못된 작업 브랜치 이름을 거부하는지 확인한다.
    def test_protected_and_invalid_branches_cannot_begin(self):
        for branch in ('dev', 'test', 'feature/Bad_Name', 'feature/nested/name'):
            if branch in ('dev', 'test'):
                self.git('switch', '-q', branch)
            else:
                self.git('switch', '-qc', branch)
            self.run_harness('begin', '--id', 'bad', '--goal', 'bad', '--files',
                             'code.txt', '--check', 'true', success=False)

    # main과 분리된 HEAD에서 작업을 시작할 수 없는지 확인한다.
    def test_main_and_detached_head_cannot_begin(self):
        self.git('switch', '-qc', 'main', 'dev')
        self.run_harness('begin', '--id', 'bad', '--goal', 'bad', '--files',
                         'code.txt', '--check', 'true', success=False)
        self.git('checkout', '-q', '--detach')
        self.run_harness('begin', '--id', 'bad', '--goal', 'bad', '--files',
                         'code.txt', '--check', 'true', success=False)

    # 커밋이 같아도 브랜치가 달라지면 작업 증거를 거부하는지 확인한다.
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

    # 현재 dev 이력을 포함한 작업 브랜치만 허용하는지 확인한다.
    def test_branch_must_contain_current_dev(self):
        self.git('switch', '-q', 'dev')
        (self.root / 'code.txt').write_text('dev advancement\n')
        self.git('add', 'code.txt')
        self.git('commit', '-qm', 'dev advanced')
        self.git('switch', '-q', 'feature/example')
        self.run_harness('begin', '--id', 'bad', '--goal', 'bad', '--files',
                         'code.txt', '--check', 'true', success=False)

    # dev에서 분기한 hotfix도 정상 종료할 수 있는지 확인한다.
    def test_hotfix_from_dev_can_complete(self):
        self.git('switch', '-qc', 'hotfix/example', 'dev')
        self.begin()
        self.review()
        self.run_harness('verify')
        self.run_harness('finish', '--message', 'fix: complete')
        self.assertEqual(self.git('status', '--porcelain'), '')

    # 가짜 원격 도구로 네트워크 없이 PR 게시 조건을 준비한다.
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

    # PR 생성만 수행하고 코멘트·승인·병합은 실행하지 않는지 확인한다.
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

    # 기존 PR을 재사용하여 중복 생성하지 않는지 확인한다.
    def test_publish_reuses_existing_pr(self):
        self.prepare_publish()
        with patch.object(HARNESS, 'remote_output', side_effect=[json.dumps([self.pr]), '', json.dumps([self.pr])]) as remote:
            HARNESS.publish(self.root, self.publish_args)
        self.assertFalse(any('create' in call.args for call in remote.call_args_list))

    # 게시 실패 후 새 커밋 없이 재시도할 수 있는지 확인한다.
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

    # 잘못된 저장소는 네트워크 호출 전에 거부하는지 확인한다.
    def test_publish_rejects_wrong_repository_without_network(self):
        self.prepare_publish()
        self.publish_args.repo = 'other/repository'
        with patch.object(HARNESS, 'remote_output') as remote:
            with self.assertRaises(ValueError):
                HARNESS.publish(self.root, self.publish_args)
            remote.assert_not_called()

    # push 목적지가 다르면 원격 호출 전에 거부하는지 확인한다.
    def test_publish_rejects_different_push_destination_without_network(self):
        self.prepare_publish()
        self.git('config', 'remote.origin.pushurl', 'git@github.com:other/repository.git')
        with patch.object(HARNESS, 'remote_output') as remote:
            with self.assertRaises(ValueError):
                HARNESS.publish(self.root, self.publish_args)
            remote.assert_not_called()

    # 복수 push 목적지를 원격 호출 전에 거부하는지 확인한다.
    def test_publish_rejects_multiple_push_destinations_without_network(self):
        self.prepare_publish()
        self.git('config', '--add', 'remote.origin.pushurl', 'git@github.com:example/remittance.git')
        self.git('config', '--add', 'remote.origin.pushurl', 'git@github.com:other/repository.git')
        with patch.object(HARNESS, 'remote_output') as remote:
            with self.assertRaises(ValueError):
                HARNESS.publish(self.root, self.publish_args)
            remote.assert_not_called()

    # 미커밋 변경이 있으면 게시하지 않는지 확인한다.
    def test_publish_rejects_uncommitted_files_without_network(self):
        self.prepare_publish()
        (self.root / 'code.txt').write_text('unreviewed change\n')
        with patch.object(HARNESS, 'remote_output') as remote:
            with self.assertRaises(ValueError):
                HARNESS.publish(self.root, self.publish_args)
            remote.assert_not_called()

    # 닫히거나 병합된 PR을 새 PR로 자동 대체하지 않는지 확인한다.
    def test_publish_respects_closed_or_merged_pr(self):
        self.prepare_publish()
        for state in ('CLOSED', 'MERGED'):
            self.pr['state'] = state
            with patch.object(HARNESS, 'remote_output', return_value=json.dumps([self.pr])) as remote:
                with self.assertRaises(ValueError):
                    HARNESS.publish(self.root, self.publish_args)
                self.assertEqual(remote.call_count, 1)

    # 기존 PR의 헤드가 다르면 거부하는지 확인한다.
    def test_publish_rejects_wrong_pr_head(self):
        self.prepare_publish()
        self.pr['headRefOid'] = '0' * 40
        with patch.object(HARNESS, 'remote_output', side_effect=[json.dumps([self.pr]), '', json.dumps([self.pr])]):
            with self.assertRaises(ValueError):
                HARNESS.publish(self.root, self.publish_args)
        self.assertEqual(HARNESS.load(self.root)['status'], 'committed')

    # 로컬 커밋만으로 완료 처리하지 않고 PR 확인을 기다리는지 확인한다.
    def test_committed_task_stays_unfinished_until_pr(self):
        self.prepare_publish()
        payload = {'hook_event_name': 'Stop'}
        self.assertEqual(json.loads(self.run_harness('hook', payload=payload).stdout)['decision'], 'block')
        self.run_harness('block', '--reason', 'network unavailable')
        self.run_harness('resume')
        self.assertEqual(HARNESS.load(self.root)['status'], 'committed')

    # 커밋 후 기억 수정은 이력을 보존한 후속 커밋으로 처리하는지 확인한다.
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

    # 기존 PR의 자동 병합 설정을 허용하지 않는지 확인한다.
    def test_publish_rejects_existing_auto_merge(self):
        self.prepare_publish()
        self.pr['autoMergeRequest'] = {'enabledAt': '2026-10-08'}
        with patch.object(HARNESS, 'remote_output', return_value=json.dumps([self.pr])) as remote:
            with self.assertRaises(ValueError):
                HARNESS.publish(self.root, self.publish_args)
            self.assertEqual(remote.call_count, 1)


if __name__ == '__main__':
    unittest.main()
