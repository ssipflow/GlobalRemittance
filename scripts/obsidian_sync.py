#!/usr/bin/env python3
"""명시적으로 실행하는 프로젝트 전용 옵시디언 내보내기. Vault 탐색과 Git 변경은 수행하지 않는다."""
import argparse
from contextlib import contextmanager
import fcntl
import json
import os
import posixpath
from pathlib import Path
import re
import stat
import subprocess
import sys
import secrets
from urllib.parse import quote, unquote, urlsplit

START = '<!-- codex:globalremittance:start -->'
END = '<!-- codex:globalremittance:end -->'
REPO = r'[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*'
LIMIT = 131072
# 검토된 설명 문서만 명시적으로 내보낸다. 작업 기억과 임의 파일은 포함하지 않는다.
DOCUMENTS = {
    'docs/application/overview.md': '프로젝트 개요.md',
    'docs/application/architecture.md': '아키텍처.md',
    'docs/application/api.md': 'API 문서.md',
    'docs/application/design.md': '주요 설계.md',
    'docs/application/concurrency.md': '동시성과 멱등성.md',
    'docs/application/observability.md': 'Observability.md',
    'docs/portfolio/index.md': '프로젝트 안내.md',
    'docs/portfolio/ax-workflow.md': 'AX 개발 방식.md',
    'docs/portfolio/case-studies.md': '대표 개선 사례.md',
    'docs/portfolio/validation.md': '검증 범위와 결과.md',
    'docs/harness/obsidian-workflow.md': '하네스 워크플로우.md',
}


class SyncError(Exception):
    """개인 경로를 포함하지 않는 안전한 실행 오류."""


# 저장소 메타데이터를 조회하며 실패 출력의 개인 경로 노출을 막는다.


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL).decode().strip()


# 전용 절대 경로와 모든 부모의 심볼릭 링크 부재를 확인한다.


def validate_path(path):
    path = Path(path)
    if not path.is_absolute() or '..' in path.parts or path == Path('/'):
        raise SyncError('Project root must be an absolute, dedicated directory.')
    for component in (path, *path.parents):
        if component.is_symlink():
            raise SyncError('Symlink components are not allowed.')
    return path


@contextmanager
# 폴더 디스크립터를 고정하고 허용된 최종 폴더만 필요 시 생성한다.
def directory(path, create=False):
    """최종 폴더까지 링크를 따르지 않는 디스크립터로 각 디렉터리를 고정한다."""
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


# 링크를 따르지 않는 정규 파일만 크기 제한 안에서 읽는다.


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


# 임시 파일 동기화·원자적 교체·재읽기로 쓰기 결과를 확인한다.


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


# 관리 마커 내부만 바꾸고 사용자 영역은 그대로 보존한다.


def managed(previous, body):
    section = START + '\n' + body + ('' if body.endswith('\n') else '\n') + END
    if previous is None:
        result = section + '\n'
    else:
        if previous.count(START) != 1 or previous.count(END) != 1:
            raise SyncError('Existing note needs exactly one managed marker pair; it was preserved.')
        start, end = previous.index(START), previous.index(END)
        if start >= end:
            raise SyncError('Managed marker order is invalid; existing note was preserved.')
        result = previous[:start] + section + previous[end + len(END):]
    if len(result.encode('utf-8')) > LIMIT:
        raise SyncError('Updated note exceeds the bounded export size; existing note was preserved.')
    return result


# 설정과 보고서는 저장소의 무시되는 로컬 폴더에만 둔다.


def local_folder(root):
    folder = root / '.harness'
    validate_path(folder)
    folder.mkdir(mode=0o700, exist_ok=True)
    if not folder.is_dir():
        raise SyncError('Local runtime directory is unavailable.')
    return folder


@contextmanager
# 동일 저장소의 동기화 실행을 배타 잠금으로 직렬화한다.
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


# 설정과 보고서를 안전한 파일 읽기 경계로 가져온다.


def runtime(root, name):
    with directory(local_folder(root)) as fd:
        raw = read_at(fd, name)
    return json.loads(raw) if raw is not None else None


# 실행 결과만 로컬에 저장하고 추적되는 작업 기억은 수정하지 않는다.


def write_runtime(root, name, data):
    with directory(local_folder(root)) as fd:
        # 원자적 교체 전에 기존 링크와 비정규 파일을 거부한다.
        read_at(fd, name)
        atomic_at(fd, name, json.dumps(data, ensure_ascii=False, indent=2) + '\n')


# 구성 형태·원격 주소·전용 폴더 범위를 쓰기 전에 검증한다.


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


# 고정 허용 목록의 추적 문서만 링크를 따르지 않고 읽는다.


def source(root, relative, tracked):
    if relative not in tracked:
        return None
    path = root / relative
    validate_path(path)
    with directory(path.parent) as fd:
        return read_at(fd, path.name)


# 앱 설명 Markdown을 보존하며 제어 마커 예시는 무해하게 바꾼다.


def application_document(text):
    # 검토된 API 예시·다이어그램의 코드 블록은 보존하고 자체 제어 마커만 이스케이프한다.
    return text.replace(START, '&lt;!-- codex:globalremittance:start --&gt;').replace(
        END, '&lt;!-- codex:globalremittance:end --&gt;')


# 링크 경로는 원본 문서 기준으로 계산하고 저장소 밖 경로는 쓰기 전에 거부한다.
def rewrite_links(text, relative, exported, tracked, base):
    def destination(value):
        parsed = urlsplit(value)
        if parsed.scheme or parsed.netloc or not parsed.path or parsed.path.startswith('/'):
            return value
        decoded = unquote(parsed.path)
        target = posixpath.normpath(posixpath.join(posixpath.dirname(relative), decoded))
        if target == '..' or target.startswith('../') or target.startswith('/') or '\\' in decoded:
            raise SyncError('Relative document links must stay inside the repository.')
        # 추적되지 않은 링크는 추측해서 새 대상에 연결하지 않는다.
        if target not in tracked:
            return value
        if target in exported:
            result = quote(DOCUMENTS[target], safe='')
        else:
            result = base + quote(target, safe='/')
        if parsed.query:
            result += '?' + parsed.query
        if parsed.fragment:
            result += '#' + parsed.fragment
        return result

    # 인라인 코드와 fence의 예시 링크는 문서 탐색 링크가 아니므로 그대로 둔다.
    inline = re.compile(r'(`+)(.*?)(?<!`)\1(?!`)|(!?\[[^\]\n]*\]\()(<?)([^\s<>()]+)(>?)([^\n)]*\))')
    reference = re.compile(r'^( {0,3}\[[^\]\n]+\]:[ \t]*)(<?)([^\s<>]+)(>?)(.*)$')
    fence = None
    result = []
    for line in text.splitlines(keepends=True):
        marker = re.match(r'^ {0,3}(`{3,}|~{3,})(.*)$', line.rstrip('\r\n'))
        if fence is not None:
            result.append(line)
            if marker and marker[1][0] == fence[0] and len(marker[1]) >= len(fence) and not marker[2].strip():
                fence = None
            continue
        if marker:
            fence = marker[1]
            result.append(line)
            continue
        # 들여쓴 코드도 예시로 취급한다. 위키 링크는 패턴과 일치하지 않는다.
        if line.startswith(('    ', '\t')):
            result.append(line)
            continue
        def replace(match):
            if match[1] is not None:
                return match[0]
            return match[3] + match[4] + destination(match[5]) + match[6] + match[7]
        rewritten = inline.sub(replace, line)
        match = reference.match(rewritten.rstrip('\r\n'))
        if match:
            ending = rewritten[len(rewritten.rstrip('\r\n')):]
            rewritten = match[1] + match[2] + destination(match[3]) + match[4] + match[5] + ending
        result.append(rewritten)
    return ''.join(result)


# 고정 문서만 렌더링하고 전후 스냅샷이 다르면 혼합 결과를 거부한다.


def render(root, config):
    import harness
    before = harness.snapshot(root)
    tracked = set(git(root, 'ls-files', '-z').split('\0'))
    revision = git(root, 'rev-parse', 'HEAD')
    base = 'https://github.com/' + config['repo'] + '/blob/' + revision + '/'
    notes = {}
    # 실제 내보낼 원본만 로컬 링크 대상으로 인정해 누락 원본의 끊긴 링크를 만들지 않는다.
    contents = {relative: content for relative in DOCUMENTS
                if (content := source(root, relative, tracked)) is not None}
    for relative, content in contents.items():
        destination = DOCUMENTS[relative]
        body = application_document(rewrite_links(content, relative, contents, tracked, base))
        if not body.endswith('\n'):
            body += '\n'
        body += ('\n---\n\n' + f'[저장소 원본]({base}{relative})\n'
                 + '본문은 현재 작업 트리 기준이며 원본 링크는 현재 HEAD의 커밋 기준이다.\n')
        notes[(destination,)] = body
    if before != harness.snapshot(root):
        raise SyncError('Repository changed during rendering; retry after writes finish.')
    return notes


# 전체 대상의 충돌을 먼저 검사하고 변경될 노트만 계산한다.


def plan(project, notes):
    result = []
    if not project.exists():
        validate_path(project)
        return [(parts, None, managed(None, body)) for parts, body in notes.items()]
    with directory(project) as fd:
        for parts, body in notes.items():
            old = read_at(fd, parts[0])
            new = managed(old, body)
            result.append((parts, old, new))
    return result


# 개인 경로와 임의 런타임 필드를 빼고 제한된 상태만 공개한다.


def report_summary(report):
    if not isinstance(report, dict) or report.get('status') not in ('dry-run', 'synced', 'failed'):
        return None
    result = {'status': report['status']}
    for key in ('planned_notes', 'changed_notes'):
        if type(report.get(key)) is int and report[key] >= 0:
            result[key] = report[key]
    return result


# 잠금 아래 설정·렌더링·사전 검사를 마친 뒤 명시적 sync에서만 쓴다.


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
        planned = plan(project, notes)  # 노트를 쓰기 전에 모든 마커 충돌을 사전 검사한다.
        changed = [(parts, old, new) for parts, old, new in planned if old != new]
        report = {'status': 'dry-run' if args.command == 'dry-run' else 'synced',
                  'planned_notes': len(notes), 'changed_notes': len(changed)}
        if args.command == 'sync' and changed:
            with directory(project, create=True) as fd:
                for parts, old, new in changed:
                    if read_at(fd, parts[0]) != old:
                        raise SyncError('A note changed after planning; retry after resolving concurrent edits.')
                    atomic_at(fd, parts[0], new)
        write_runtime(root, 'obsidian-report.json', report)
        return report


# CLI 경계에서 안전한 오류만 출력하고 실패도 로컬 보고서로 남긴다.


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
        # 원시 예외에는 개인 Vault 경로가 있을 수 있으므로 그대로 직렬화하지 않는다.
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
