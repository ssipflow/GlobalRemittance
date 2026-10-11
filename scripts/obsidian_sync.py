#!/usr/bin/env python3
"""Explicit, project-scoped Obsidian export. No Vault discovery or Git mutations."""
import argparse
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import secrets
from urllib.parse import quote

START = '<!-- codex:globalremittance:start -->'
END = '<!-- codex:globalremittance:end -->'
ID = r'[a-z0-9]+(?:-[a-z0-9]+)*'
REPO = r'[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*'
LIMIT = 131072


class SyncError(Exception):
    """Safe, path-free operational message."""


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL).decode().strip()


def validate_path(path):
    path = Path(path)
    if not path.is_absolute() or '..' in path.parts or path == Path('/'):
        raise SyncError('Project root must be an absolute, dedicated directory.')
    for component in (path, *path.parents):
        if component.is_symlink():
            raise SyncError('Symlink components are not allowed.')
    return path


@contextmanager
def directory(path, create=False):
    """Pin each directory through no-follow descriptors, including the final root."""
    path = validate_path(path)
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY)
    try:
        for index, part in enumerate(path.parts[1:]):
            try:
                next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            except FileNotFoundError:
                if not create or index != len(path.parts[1:]) - 1:
                    raise SyncError('Project parent must already exist.')
                os.mkdir(part, mode=0o700, dir_fd=fd)
                next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = next_fd
        yield fd
    finally:
        os.close(fd)


def read_at(fd, name):
    try:
        handle = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
    except FileNotFoundError:
        return None
    try:
        if not stat.S_ISREG(os.fstat(handle).st_mode):
            raise SyncError('Only regular files are supported.')
        with os.fdopen(handle, 'rb', closefd=False) as stream:
            raw = stream.read(LIMIT + 1)
        if len(raw) > LIMIT:
            raise SyncError('Document exceeds the bounded export size.')
        return raw.decode('utf-8')
    finally:
        os.close(handle)


def atomic_at(fd, name, text):
    temp = '.codex-' + secrets.token_hex(12) + '.tmp'
    handle = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=fd)
    try:
        with os.fdopen(handle, 'w', encoding='utf-8') as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        os.rename(temp, name, src_dir_fd=fd, dst_dir_fd=fd)
        if read_at(fd, name) != text:
            raise SyncError('Write verification failed; inspect the project notes.')
    finally:
        try:
            os.unlink(temp, dir_fd=fd)
        except FileNotFoundError:
            pass


def managed(previous, body):
    section = START + '\n' + body.rstrip() + '\n' + END
    if previous is None:
        return section + '\n'
    if previous.count(START) != 1 or previous.count(END) != 1:
        raise SyncError('Existing note needs exactly one managed marker pair; it was preserved.')
    start, end = previous.index(START), previous.index(END)
    if start >= end:
        raise SyncError('Managed marker order is invalid; existing note was preserved.')
    result = previous[:start] + section + previous[end + len(END):]
    if len(result.encode('utf-8')) > LIMIT:
        raise SyncError('Updated note exceeds the bounded export size; existing note was preserved.')
    return result


def local_folder(root):
    folder = root / '.harness'
    validate_path(folder)
    folder.mkdir(mode=0o700, exist_ok=True)
    if not folder.is_dir():
        raise SyncError('Local runtime directory is unavailable.')
    return folder


@contextmanager
def lock(root):
    with directory(local_folder(root)) as fd:
        handle = os.open('obsidian.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600, dir_fd=fd)
        try:
            if not stat.S_ISREG(os.fstat(handle).st_mode):
                raise SyncError('Runtime lock must be a regular file.')
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise SyncError('Another Obsidian operation is running; retry later.')
            yield
        finally:
            os.close(handle)


def runtime(root, name):
    with directory(local_folder(root)) as fd:
        raw = read_at(fd, name)
    return json.loads(raw) if raw is not None else None


def write_runtime(root, name, data):
    with directory(local_folder(root)) as fd:
        # Reject an existing symlink/nonregular file before atomic replacement.
        read_at(fd, name)
        atomic_at(fd, name, json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def validate_config(root, config):
    if not isinstance(config, dict) or config.get('version') != 1:
        raise SyncError('Configure the project export first.')
    repo = config.get('repo')
    if not isinstance(repo, str) or not re.fullmatch(REPO, repo) or any(p in ('.', '..') for p in repo.split('/')):
        raise SyncError('Repository must be a GitHub owner/repository identifier.')
    if not isinstance(config.get('project_root'), str):
        raise SyncError('Project root must be an absolute, dedicated directory.')
    project = validate_path(config['project_root'])
    if project == root or project in root.parents or root in project.parents:
        raise SyncError('Project notes must be outside the code repository.')
    if project == Path.home():
        raise SyncError('Use a dedicated project directory, not the home directory.')
    with directory(project.parent):
        pass
    if project.exists():
        with directory(project):
            pass
    return project


def source(root, relative, tracked):
    if relative not in tracked:
        return None
    path = root / relative
    validate_path(path)
    with directory(path.parent) as fd:
        return read_at(fd, path.name)


def summary(text):
    # Curated repository notes only: headings and bullet summaries, never raw logs/code blocks.
    lines, fence = [], None
    for line in text.splitlines():
        opening = re.match(r'^ {0,3}(`{3,}|~{3,})(.*)$', line)
        if fence is not None:
            if (opening and opening[1][0] == fence[0] and len(opening[1]) >= fence[1]
                    and not opening[2].strip()):
                fence = None
            continue
        if opening:
            fence = (opening[1][0], len(opening[1]))
            continue
        if (line.startswith('#') or line.startswith('- ')):
            lines.append(line[:240])
        if len(lines) == 16:
            break
    return '\n'.join(lines).replace(START, '&lt;managed-start&gt;').replace(END, '&lt;managed-end&gt;')[:2400] or '요약 항목 없음. 원본 문서를 참조한다.'


def render(root, config):
    import harness
    before = harness.snapshot(root)
    tracked = set(git(root, 'ls-files', '-z').split('\0'))
    branch = git(root, 'branch', '--show-current')
    revision = git(root, 'rev-parse', 'HEAD')
    base = 'https://github.com/' + config['repo'] + '/blob/' + revision + '/'
    task = runtime(root, 'task.json')
    valid = (isinstance(task, dict) and isinstance(task.get('id'), str)
             and re.fullmatch(ID, task['id']) and task.get('branch') == branch)
    overview = ['# GlobalRemittance', '', '저장소에서 옵시디언으로 단방향 갱신한 문서다.',
                '새 개발 목표와 범위는 사용자와 확정한다.', '', '## 현재 상태']
    state = source(root, 'docs/harness/state.md', tracked)
    if state is not None:
        overview += [summary(state), '', f'[저장소 상태 원본]({base}docs/harness/state.md)',
                     '요약은 현재 작업 트리 기준이며 링크는 현재 HEAD의 커밋 기준이다.']
    notes = {('프로젝트 개요.md',): '\n'.join(overview)}
    if valid:
        task_id = task['id']
        relative = f'docs/harness/tasks/{task_id}.md'
        content = source(root, relative, tracked)
        if content is not None:
            body = [f'# 작업: {task_id}', '', summary(content), '', f'[작업 기록 원본]({base}{relative})',
                    '요약은 현재 작업 트리 기준이며 링크는 현재 HEAD의 커밋 기준이다.', '', '## 실행 근거']
            verified = False
            if task.get('status') in ('committed', 'completed') and task.get('commit') == revision:
                review, verification = task.get('review', {}), task.get('verification', {})
                verified = (isinstance(review, dict) and isinstance(verification, dict)
                            and isinstance(review.get('snapshot'), str)
                            and re.fullmatch(r'[0-9a-f]{64}', review['snapshot'])
                            and bool(review.get('evidence'))
                            and verification.get('snapshot') == review['snapshot']
                            and verification.get('passed') is True
                            and not harness.changed(root)
                            and task.get('committed_snapshot') == harness.snapshot(root))
            if verified:
                body.append(f'- 검증된 하네스 커밋: `{revision}`')
                pr = task.get('pr', {})
                number = pr.get('number') if isinstance(pr, dict) else None
                if (isinstance(number, int) and not isinstance(number, bool) and number > 0
                        and pr.get('repo') == config['repo'] and pr.get('head') == revision
                        and pr.get('base') == 'dev'
                        and pr.get('url') == f'https://github.com/{config["repo"]}/pull/{number}'):
                    body.append(f'- 하네스 publish에서 확인한 [PR #{number}]({pr["url"]}) (현재 원격 상태 재조회는 아님)')
            else:
                body.append('- 현재 snapshot의 확정된 커밋·PR 근거 없음. 오래된 실행 결과는 표시하지 않는다.')
            notes[('작업', task_id + '.md')] = '\n'.join(body)
    for relative in sorted(tracked):
        if not re.fullmatch(r'docs/decisions/' + ID + r'\.md', relative):
            continue
        content = source(root, relative, tracked)
        if content is not None:
            name = Path(relative).name
            notes[('결정', name)] = '# 결정: ' + name[:-3] + '\n\n' + summary(content) + '\n\n' + f'[설계 결정 원본]({base}{quote(relative)})\n요약은 현재 작업 트리 기준, 링크는 HEAD 기준.'
    if before != harness.snapshot(root) or task != runtime(root, 'task.json'):
        raise SyncError('Repository or current task changed during rendering; retry after writes finish.')
    return notes


@contextmanager
def child_directory(fd, name, create=False):
    try:
        child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
    except FileNotFoundError:
        if not create:
            yield None
            return
        os.mkdir(name, mode=0o700, dir_fd=fd)
        child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
    try:
        yield child
    finally:
        os.close(child)


@contextmanager
def note_parent(fd, parts, create=False):
    if len(parts) == 1:
        yield fd
    else:
        with child_directory(fd, parts[0], create) as child:
            yield child


def plan(project, notes):
    result = []
    if not project.exists():
        validate_path(project)
        return [(parts, None, managed(None, body)) for parts, body in notes.items()]
    with directory(project) as fd:
        for parts, body in notes.items():
            with note_parent(fd, parts) as parent:
                old = None if parent is None else read_at(parent, parts[-1])
                new = managed(old, body)
                result.append((parts, old, new))
    return result


def report_summary(report):
    if not isinstance(report, dict) or report.get('status') not in ('dry-run', 'synced', 'failed'):
        return None
    result = {'status': report['status']}
    for key in ('planned_notes', 'changed_notes'):
        if type(report.get(key)) is int and report[key] >= 0:
            result[key] = report[key]
    return result


def execute(root, args):
    with lock(root):
        if args.command == 'configure':
            config = {'version': 1, 'project_root': args.project_root, 'repo': args.repo}
            validate_config(root, config)
            write_runtime(root, 'obsidian.json', config)
            return {'status': 'configured', 'project_root': 'redacted', 'repo': args.repo}
        config = runtime(root, 'obsidian.json')
        if config is None and args.command == 'status':
            return {'status': 'not-configured'}
        project = validate_config(root, config)
        if args.command == 'status':
            return {'status': 'configured', 'project_root': 'redacted', 'repo': config['repo'],
                    'last_run': report_summary(runtime(root, 'obsidian-report.json'))}
        notes = render(root, config)
        planned = plan(project, notes)  # All marker conflicts preflight before any note write.
        changed = [(parts, old, new) for parts, old, new in planned if old != new]
        report = {'status': 'dry-run' if args.command == 'dry-run' else 'synced',
                  'planned_notes': len(notes), 'changed_notes': len(changed)}
        if args.command == 'sync' and changed:
            with directory(project, create=True) as fd:
                for parts, old, new in changed:
                    with note_parent(fd, parts, create=True) as parent:
                        if read_at(parent, parts[-1]) != old:
                            raise SyncError('A note changed after planning; retry after resolving concurrent edits.')
                        atomic_at(parent, parts[-1], new)
        write_runtime(root, 'obsidian-report.json', report)
        return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    config = commands.add_parser('configure')
    config.add_argument('--project-root', required=True)
    config.add_argument('--repo', required=True)
    for command in ('status', 'dry-run', 'sync'):
        commands.add_parser(command)
    args = parser.parse_args(argv)
    try:
        root = Path(git(Path.cwd(), 'rev-parse', '--show-toplevel'))
        result = execute(root, args)
    except (SyncError, OSError, ValueError, subprocess.CalledProcessError) as error:
        message = str(error) if isinstance(error, SyncError) else 'Operation failed; check permissions, UTF-8, JSON, and non-symlink paths.'
        # Never serialize raw exceptions: they can include a private Vault path.
        if 'root' in locals():
            try:
                with lock(root):
                    write_runtime(root, 'obsidian-report.json', {'status': 'failed', 'reason': message})
            except (SyncError, OSError, ValueError):
                pass
        print(json.dumps({'status': 'failed', 'reason': message}, ensure_ascii=False))
        return 1
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.exit(main())
