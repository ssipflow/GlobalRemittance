"""Application-document export and privacy tests use temporary repos/Vaults only."""
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
        self.documents = {
            'overview': '# GlobalRemittance\n\n정확한 송금 서비스의 목적과 실행 방법을 설명한다.\n',
            'architecture': '# 아키텍처\n\n```mermaid\ngraph LR\n Adapter --> Application --> Domain\n```\n',
            'api': '# API\n\n' + '긴 계약 설명. ' * 400 + '\n\n'
                   + '| 필드 | 타입 |\n| --- | --- |\n| quoteId | string |\n\n'
                   + '\n'.join(f'- 필드 설명 {number}' for number in range(30))
                   + '\n\n```json\n{"resultCode": 200, "quote": {"quoteId": "1"}}\n```\n',
            'design': '# 주요 설계\n\nKST 일자와 사용자 행 잠금으로 한도를 검증한다.\n',
        }
        for name, content in self.documents.items():
            self.write(f'docs/application/{name}.md', content)
        self.write('docs/harness/state.md', '# TASK-LOG-PRIVATE\n- 실행 로그\n')
        self.write('docs/harness/tasks/obsidian-sync.md', '# TASK-RECORD-PRIVATE\n')
        self.write('docs/decisions/001-documentation.md', '# HARNESS-DECISION-PRIVATE\n')
        self.run_git('add', '.')
        self.run_git('commit', '-qm', 'fixture')
        self.run_git('switch', '-qc', 'feature/application-docs')
        self.write('.harness/task.json', json.dumps({'id': 'obsidian-sync', 'branch': 'feature/application-docs',
                    'status': 'completed', 'pr': {'url': 'https://github.com/owner/repo/pull/999'}}))
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
        self.assertEqual(report['planned_notes'], 4)
        self.assertFalse(self.project.exists())
        self.assertEqual(self.cli('sync')[0], 0)
        contents = {p.name: p.read_bytes() for p in self.project.glob('*.md')}
        stamps = {p.name: p.stat().st_mtime_ns for p in self.project.glob('*.md')}
        self.assertEqual(self.cli('sync')[1]['changed_notes'], 0)
        self.assertEqual(contents, {p.name: p.read_bytes() for p in self.project.glob('*.md')})
        self.assertEqual(stamps, {p.name: p.stat().st_mtime_ns for p in self.project.glob('*.md')})
        self.assertEqual(self.run_git('status', '--porcelain'), '')

    def test_full_prose_tables_json_and_mermaid_are_preserved_without_summary_cutoff(self):
        self.cli('sync')
        for name, destination in sync.DOCUMENTS.items():
            original = (self.root / name).read_text()
            self.assertIn(original, (self.project / destination).read_text())
        api = (self.project / 'API 문서.md').read_text()
        self.assertIn('- 필드 설명 29', api)
        self.assertIn('| quoteId | string |', api)
        self.assertIn('```json\n{"resultCode": 200', api)
        self.assertGreater(len(api), 2400)
        self.assertIn('```mermaid\ngraph LR', (self.project / '아키텍처.md').read_text())

    def test_only_fixed_tracked_application_docs_export_without_task_or_pr_metadata(self):
        self.write('docs/application/private.md', 'ARBITRARY-DOC-PRIVATE')
        self.write('src/private.py', 'RAW-CODE-PRIVATE')
        self.write('runtime.log', 'RAW-LOG-PRIVATE')
        self.run_git('add', 'docs/application/private.md', 'src/private.py', 'runtime.log')
        with patch.object(sync, 'runtime', wraps=sync.runtime) as reads:
            self.assertEqual(self.cli('sync')[0], 0)
        self.assertNotIn('task.json', [call.args[1] for call in reads.call_args_list])
        self.assertEqual(set(sync.DOCUMENTS.values()), {p.name for p in self.project.iterdir()})
        text = ''.join(p.read_text() for p in self.project.glob('*.md'))
        for forbidden in ('TASK-LOG-PRIVATE', 'TASK-RECORD-PRIVATE', 'HARNESS-DECISION-PRIVATE',
                          'ARBITRARY-DOC-PRIVATE', 'RAW-CODE-PRIVATE', 'RAW-LOG-PRIVATE', '/pull/999'):
            self.assertNotIn(forbidden, text)
        self.write('.harness/task.json', '{corrupt task JSON')
        self.assertEqual(self.cli('sync')[1]['changed_notes'], 0)

    def test_untracked_allowlisted_file_is_not_exported(self):
        self.run_git('rm', '--cached', '-q', 'docs/application/api.md')
        self.assertEqual(self.cli('sync')[1]['planned_notes'], 3)
        self.assertFalse((self.project / 'API 문서.md').exists())
        self.assertTrue((self.root / 'docs/application/api.md').exists())

    def test_manual_content_preserved_and_personal_vault_files_untouched(self):
        self.cli('sync')
        note = self.project / '프로젝트 개요.md'
        note.write_text('나의 노트\n' + note.read_text() + '\n개인 메모\n')
        private = self.project.parent.parent / 'private.md'
        private.write_text('PERSONAL-SECRET')
        self.write('docs/application/overview.md', '# 프로젝트\n\n새로운 앱 개요.\n')
        self.assertEqual(self.cli('sync')[0], 0)
        self.assertTrue(note.read_text().startswith('나의 노트\n'))
        self.assertTrue(note.read_text().endswith('\n개인 메모\n'))
        self.assertIn('새로운 앱 개요', note.read_text())
        self.assertNotIn('PERSONAL-SECRET', ''.join(p.read_text() for p in self.project.glob('*.md')))
        self.assertEqual(private.read_text(), 'PERSONAL-SECRET')

    def test_missing_or_malformed_markers_refuse_entire_preflight(self):
        self.cli('sync')
        overview = self.project / '프로젝트 개요.md'
        for content in ('사용자 소유 파일', sync.END + sync.START, sync.START + sync.START + sync.END):
            with self.subTest(content=content):
                overview.write_text(content)
                self.write('docs/application/api.md', '# 변경된 API\n')
                api = self.project / 'API 문서.md'
                before = api.read_text()
                self.assertEqual(self.cli('sync')[0], 1)
                self.assertEqual(overview.read_text(), content)
                self.assertEqual(api.read_text(), before)
                self.assertEqual(json.loads((self.root / '.harness/obsidian-report.json').read_text())['status'], 'failed')

    def test_managed_marker_examples_are_escaped_and_repeated_sync_stays_valid(self):
        self.write('docs/application/api.md', '# 관리 예시\n\n```markdown\n' + sync.START + '\n' + sync.END + '\n```\n')
        self.assertEqual(self.cli('sync')[0], 0)
        text = (self.project / 'API 문서.md').read_text()
        self.assertEqual(text.count(sync.START), 1)
        self.assertEqual(text.count(sync.END), 1)
        self.assertIn('&lt;!-- codex:globalremittance:start --&gt;', text)
        self.assertEqual(self.cli('sync')[1]['changed_notes'], 0)

    def test_symlink_note_and_reconfigured_project_root_refused(self):
        self.cli('sync')
        victim = self.base / 'victim.md'
        victim.write_text('PRIVATE')
        note = self.project / '프로젝트 개요.md'
        note.unlink()
        note.symlink_to(victim)
        self.assertEqual(self.cli('sync')[0], 1)
        self.assertEqual(victim.read_text(), 'PRIVATE')
        other = self.base / 'other-project'
        other.mkdir()
        alias = self.project.parent / 'Alias'
        alias.symlink_to(other, target_is_directory=True)
        config = json.loads((self.root / '.harness/obsidian.json').read_text())
        config['project_root'] = str(alias)
        self.write('.harness/obsidian.json', json.dumps(config))
        self.assertEqual(self.cli('sync')[0], 1)
        self.assertEqual(list(other.iterdir()), [])

    def test_config_root_symlink_traversal_and_repo_validation(self):
        alias = self.base / 'alias'
        alias.symlink_to(self.project.parent, target_is_directory=True)
        for path, repo in [(str(alias / 'Project'), 'a/b'), (str(self.project.parent / '..' / 'Project'), 'a/b'),
                           (str(self.root / 'notes'), 'a/b'), (str(self.project), 'https://evil.invalid/a/b'),
                           (str(self.project), 'a/b?token=secret')]:
            with self.subTest(path=path, repo=repo):
                self.assertEqual(self.cli('configure', '--project-root', path, '--repo', repo)[0], 1)
        self.assertFalse(self.project.exists())

    def test_source_file_and_source_parent_symlinks_are_not_ingested(self):
        victim = self.base / 'private.md'
        victim.write_text('PRIVATE-SECRET')
        overview = self.root / 'docs/application/overview.md'
        overview.unlink()
        overview.symlink_to(victim)
        self.assertEqual(self.cli('sync')[0], 1)
        self.assertFalse(self.project.exists())
        overview.unlink()
        overview.write_text(self.documents['overview'])
        original = self.root / 'docs/application'
        relocated = self.root / 'docs/relocated'
        original.rename(relocated)
        original.symlink_to(relocated, target_is_directory=True)
        self.assertEqual(self.cli('sync')[0], 1)
        self.assertFalse(self.project.exists())

    def test_missing_sources_and_legacy_work_notes_are_not_deleted_or_read(self):
        self.cli('sync')
        previous = (self.project / 'API 문서.md').read_bytes()
        self.run_git('rm', '-q', 'docs/application/api.md')
        self.assertEqual(self.cli('sync')[1]['planned_notes'], 3)
        self.assertEqual((self.project / 'API 문서.md').read_bytes(), previous)
        personal = self.base / 'personal'
        personal.mkdir()
        legacy = personal / 'task.md'
        legacy.write_text('LEGACY-PRIVATE')
        (self.project / '작업').symlink_to(personal, target_is_directory=True)
        self.assertEqual(self.cli('sync')[0], 0)
        self.assertEqual(legacy.read_text(), 'LEGACY-PRIVATE')
        self.run_git('rm', '-qr', 'docs/application')
        self.assertEqual(self.cli('sync')[1]['planned_notes'], 0)
        self.assertEqual((self.project / 'API 문서.md').read_bytes(), previous)

    def test_status_redacts_runtime_payload_and_concurrent_lock(self):
        self.write('.harness/obsidian-report.json', json.dumps({'status': 'failed', 'reason': str(self.project)}))
        self.assertEqual(self.cli('status')[1]['last_run'], {'status': 'failed'})
        with sync.lock(self.root):
            self.assertEqual(self.cli('sync')[0], 1)
        self.assertFalse(self.project.exists())

    def test_corrupt_config_shapes_have_redacted_failure(self):
        for project in (None, [], 7):
            self.write('.harness/obsidian.json', json.dumps({'version': 1, 'repo': 'a/b', 'project_root': project}))
            self.assertEqual(self.cli('status')[0], 1)

    def test_repository_change_during_render_refuses_write(self):
        with patch.object(harness, 'snapshot', side_effect=['before', 'after']):
            self.assertEqual(self.cli('sync')[0], 1)
        self.assertFalse(self.project.exists())

    def test_source_and_assembled_note_size_fail_before_any_note_write(self):
        for content in ('x' * (sync.LIMIT + 1), 'x' * (sync.LIMIT - 1)):
            with self.subTest(length=len(content)):
                self.write('docs/application/api.md', content)
                self.assertEqual(self.cli('sync')[0], 1)
                self.assertFalse(self.project.exists())
        self.write('docs/application/api.md', self.documents['api'])
        self.cli('sync')
        api = self.project / 'API 문서.md'
        current = api.read_text()
        api.write_text('유' * ((sync.LIMIT - len(current.encode())) // 3) + current)
        self.write('docs/application/api.md', self.documents['api'] + '추가 문서' * 100)
        before = api.read_bytes()
        self.assertEqual(self.cli('sync')[0], 1)
        self.assertEqual(api.read_bytes(), before)

    def test_atomic_failure_preserves_existing_note_and_removes_temp(self):
        self.cli('sync')
        note = self.project / '프로젝트 개요.md'
        before = note.read_bytes()
        self.write('docs/application/overview.md', '# 변경된 앱 개요\n')
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
