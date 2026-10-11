"""Privacy and preservation tests run only in temporary repositories/Vaults."""
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import harness
import obsidian_sync as sync


class ObsidianSyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / 'repo'
        self.root.mkdir()
        self.project = self.base / 'vault' / 'Projects' / 'GlobalRemittance'
        self.project.parent.mkdir(parents=True)
        self.run_git('init', '-q')
        self.run_git('config', 'user.email', 'test@example.invalid')
        self.run_git('config', 'user.name', 'Test')
        self.write('.gitignore', '.harness/\n')
        self.write('docs/harness/state.md', '# 현재 상태\n- 목표: 새 목표 대기\n```\n- code-secret\n```\n')
        self.write('docs/harness/tasks/obsidian-sync.md', '# 작업\n- 결정: 단방향\n')
        self.write('docs/decisions/001-documentation.md', '# 결정\n- 이유: 문서 원본 보존\n')
        self.run_git('add', '.')
        self.run_git('commit', '-qm', 'fixture')
        self.run_git('switch', '-qc', 'feature/obsidian-sync')
        self.write('.harness/task.json', json.dumps({'id': 'obsidian-sync', 'branch': 'feature/obsidian-sync', 'status': 'active'}))
        self.cwd = Path.cwd()
        os.chdir(self.root)
        self.assertEqual(self.cli('configure', '--project-root', str(self.project), '--repo', 'ssipflow/Moin-GlobalRemittance')[0], 0)

    def tearDown(self):
        os.chdir(self.cwd)
        self.temp.cleanup()

    def run_git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.DEVNULL).decode().strip()

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8')

    def cli(self, *args):
        output = io.StringIO()
        with redirect_stdout(output):
            result = sync.main(list(args))
        self.assertNotIn(str(self.base), output.getvalue())
        return result, json.loads(output.getvalue())

    def test_dry_run_no_vault_creation_and_repeat_idempotent(self):
        status, report = self.cli('dry-run')
        self.assertEqual(status, 0)
        self.assertEqual(report['planned_notes'], 3)
        self.assertFalse(self.project.exists())
        self.assertEqual(self.cli('sync')[0], 0)
        contents = {str(p.relative_to(self.project)): p.read_bytes() for p in self.project.rglob('*.md')}
        stamps = {str(p.relative_to(self.project)): p.stat().st_mtime_ns for p in self.project.rglob('*.md')}
        self.assertEqual(self.cli('sync')[1]['changed_notes'], 0)
        self.assertEqual(contents, {str(p.relative_to(self.project)): p.read_bytes() for p in self.project.rglob('*.md')})
        self.assertEqual(stamps, {str(p.relative_to(self.project)): p.stat().st_mtime_ns for p in self.project.rglob('*.md')})
        self.assertEqual(self.run_git('status', '--porcelain'), '')

    def test_manual_content_preserved_and_no_personal_vault_reads(self):
        self.cli('sync')
        note = self.project / '프로젝트 개요.md'
        note.write_text('나의 노트\n' + note.read_text() + '\n개인 메모\n')
        private = self.project.parent.parent / 'private.md'
        private.write_text('PERSONAL-SECRET')
        self.write('docs/harness/state.md', '# 상태\n- 목표: 수정됨\n')
        self.cli('sync')
        self.assertTrue(note.read_text().startswith('나의 노트\n'))
        self.assertTrue(note.read_text().endswith('\n개인 메모\n'))
        self.assertIn('수정됨', note.read_text())
        self.assertNotIn('PERSONAL-SECRET', ''.join(p.read_text() for p in self.project.rglob('*.md')))
        self.assertEqual(private.read_text(), 'PERSONAL-SECRET')

    def test_missing_or_malformed_markers_refuse_entire_preflight(self):
        self.cli('sync')
        overview = self.project / '프로젝트 개요.md'
        for content in ('사용자 소유 파일', sync.END + sync.START, sync.START + sync.START + sync.END):
            with self.subTest(content=content):
                overview.write_text(content)
                self.write('docs/decisions/001-documentation.md', '# 수정\n- 결정: 새 내용\n')
                decision = self.project / '결정/001-documentation.md'
                before = decision.read_text()
                self.assertEqual(self.cli('sync')[0], 1)
                self.assertEqual(overview.read_text(), content)
                self.assertEqual(decision.read_text(), before)
                self.assertEqual(json.loads((self.root / '.harness/obsidian-report.json').read_text())['status'], 'failed')

    def test_symlink_note_and_subdirectory_refused(self):
        self.cli('sync')
        victim = self.base / 'victim.md'
        victim.write_text('PRIVATE')
        note = self.project / '프로젝트 개요.md'
        note.unlink()
        note.symlink_to(victim)
        self.assertEqual(self.cli('sync')[0], 1)
        self.assertEqual(victim.read_text(), 'PRIVATE')
        note.unlink()
        self.cli('sync')
        decision = self.project / '결정/001-documentation.md'
        decision.unlink()
        (self.project / '결정').rmdir()
        (self.project / '결정').symlink_to(self.base, target_is_directory=True)
        self.assertEqual(self.cli('sync')[0], 1)
        self.assertFalse((self.base / '001-documentation.md').exists())

    def test_config_root_symlink_traversal_and_repo_validation(self):
        alias = self.base / 'alias'
        alias.symlink_to(self.project.parent, target_is_directory=True)
        for path, repo in [(str(alias / 'Project'), 'a/b'), (str(self.project.parent / '..' / 'Project'), 'a/b'),
                           (str(self.root / 'notes'), 'a/b'), (str(self.project), 'https://evil.invalid/a/b'),
                           (str(self.project), 'a/b?token=secret')]:
            with self.subTest(path=path, repo=repo):
                self.assertEqual(self.cli('configure', '--project-root', path, '--repo', repo)[0], 1)
        self.assertFalse(self.project.exists())

    def test_source_symlink_is_not_ingested(self):
        victim = self.base / 'private.md'
        victim.write_text('- PRIVATE-SECRET')
        state = self.root / 'docs/harness/state.md'
        state.unlink()
        state.symlink_to(victim)
        self.assertEqual(self.cli('sync')[0], 1)
        self.assertFalse(self.project.exists())

    def test_untracked_and_code_sources_not_exported_or_stale_notes_deleted(self):
        self.write('docs/decisions/private.md', '- PRIVATE-SECRET')
        self.write('src/private.py', '- CODE-SECRET')
        self.cli('sync')
        self.assertFalse((self.project / '결정/private.md').exists())
        texts = ''.join(p.read_text() for p in self.project.rglob('*.md'))
        self.assertNotIn('CODE-SECRET', texts)
        self.assertNotIn('code-secret', texts)
        self.run_git('rm', '-q', 'docs/decisions/001-documentation.md')
        self.cli('sync')
        self.assertTrue((self.project / '결정/001-documentation.md').exists())

    def evidence(self):
        sha = self.run_git('rev-parse', 'HEAD')
        task = {'id': 'obsidian-sync', 'branch': 'feature/obsidian-sync', 'status': 'completed',
                'commit': sha, 'committed_snapshot': harness.snapshot(self.root),
                'review': {'snapshot': 'a' * 64, 'evidence': 'independent review'},
                'verification': {'snapshot': 'a' * 64, 'passed': True},
                'pr': {'number': 4, 'url': 'https://github.com/ssipflow/Moin-GlobalRemittance/pull/4',
                       'head': sha, 'repo': 'ssipflow/Moin-GlobalRemittance', 'base': 'dev'}}
        self.write('.harness/task.json', json.dumps(task))
        return task

    def test_verified_metadata_and_stale_snapshot_or_branch_guard(self):
        task = self.evidence()
        self.cli('sync')
        note = self.project / '작업/obsidian-sync.md'
        self.assertIn('[PR #4]', note.read_text())
        self.assertIn(task['commit'], note.read_text())
        self.write('docs/harness/state.md', '# 상태\n- 변경됨\n')
        self.cli('sync')
        self.assertNotIn('[PR #4]', note.read_text())
        self.assertIn('확정된 커밋·PR 근거 없음', note.read_text())
        task['id'] = '../../private'
        self.write('.harness/task.json', json.dumps(task))
        self.assertEqual(self.cli('sync')[0], 0)
        self.assertFalse((self.base / 'private.md').exists())
        task['id'], task['branch'] = 'obsidian-sync', 'feature/other'
        self.write('.harness/task.json', json.dumps(task))
        config = sync.runtime(self.root, 'obsidian.json')
        self.assertNotIn(('작업', 'obsidian-sync.md'), sync.render(self.root, config))

    def test_invalid_pr_and_failed_verification_not_claimed(self):
        task = self.evidence()
        task['pr']['url'] = 'https://evil.invalid/private'
        self.write('.harness/task.json', json.dumps(task))
        self.cli('sync')
        note = self.project / '작업/obsidian-sync.md'
        self.assertNotIn('evil.invalid', note.read_text())
        self.assertNotIn('[PR #4]', note.read_text())
        task['verification']['passed'] = False
        self.write('.harness/task.json', json.dumps(task))
        self.cli('sync')
        self.assertNotIn('검증된 하네스 커밋', note.read_text())

    def test_status_redacts_runtime_payload_and_concurrent_lock(self):
        self.write('.harness/obsidian-report.json', json.dumps({'status': 'failed', 'reason': str(self.project)}))
        self.assertEqual(self.cli('status')[1]['last_run'], {'status': 'failed'})
        with sync.lock(self.root):
            self.assertEqual(self.cli('sync')[0], 1)
        self.assertFalse(self.project.exists())

    def test_mismatched_or_short_fences_do_not_export_code(self):
        text = '# 공개\n````python\n~~~\n- PRIVATE-1\n```\n- PRIVATE-2\n````\n- 공개 항목\n~~~text\n```\n- PRIVATE-3\n~~~\n'
        result = sync.summary(text)
        self.assertIn('공개 항목', result)
        self.assertNotIn('PRIVATE', result)

    def test_corrupt_runtime_shapes_have_redacted_failure(self):
        for project in (None, [], 7):
            self.write('.harness/obsidian.json', json.dumps({'version': 1, 'repo': 'a/b', 'project_root': project}))
            self.assertEqual(self.cli('status')[0], 1)
        self.cli('configure', '--project-root', str(self.project), '--repo', 'ssipflow/Moin-GlobalRemittance')
        self.write('.harness/task.json', json.dumps({'id': [], 'branch': 'feature/obsidian-sync'}))
        self.assertEqual(self.cli('dry-run')[0], 0)

    def test_repository_change_during_render_refuses_write(self):
        with patch.object(harness, 'snapshot', side_effect=['before', 'after']):
            self.assertEqual(self.cli('sync')[0], 1)
        self.assertFalse(self.project.exists())

    def test_atomic_failure_preserves_existing_note_and_removes_temp(self):
        self.cli('sync')
        note = self.project / '프로젝트 개요.md'
        before = note.read_bytes()
        self.write('docs/harness/state.md', '# 상태\n- 목표: 변경됨\n')
        with patch.object(sync.os, 'rename', side_effect=PermissionError('private path')):
            self.assertEqual(self.cli('sync')[0], 1)
        self.assertEqual(note.read_bytes(), before)
        self.assertEqual(list(self.project.glob('.codex-*.tmp')), [])

    def test_local_config_symlink_refused(self):
        config = self.root / '.harness/obsidian.json'
        config.unlink()
        victim = self.base / 'secret.json'
        victim.write_text('{}')
        config.symlink_to(victim)
        self.assertEqual(self.cli('status')[0], 1)
        self.assertEqual(victim.read_text(), '{}')


if __name__ == '__main__':
    unittest.main()
