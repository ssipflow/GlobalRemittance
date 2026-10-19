#!/usr/bin/env python3
"""표준 라이브러리만 사용하는 작업 범위별 Codex 리뷰·검증·커밋 루프."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time


# 저장소를 명시한 Git 명령의 출력만 반환한다.


def git(*args, root=None):
    return subprocess.check_output(['git', *args], cwd=root)


# 현재 작업의 저장소 루트를 Git에서 확인한다.


def root_path():
    return Path(git('rev-parse', '--show-toplevel').decode().strip())


# 분리된 HEAD는 브랜치 없음으로 구분한다.


def current_branch(root):
    result = subprocess.run(['git', 'symbolic-ref', '--quiet', '--short', 'HEAD'],
                            cwd=root, capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else None


# 작업 브랜치 형식과 기록된 브랜치 일치를 강제한다.


def require_work_branch(root, task=None):
    branch = current_branch(root)
    if not branch or not re.fullmatch(r'(feature|hotfix)/[a-z0-9]+(?:-[a-z0-9]+)*', branch):
        raise ValueError('Work requires feature/<kebab-name> or hotfix/<kebab-name>, not main/dev/test or detached HEAD.')
    if task is not None and task.get('branch') != branch:
        raise ValueError('Task branch changed. Return to the recorded task branch before continuing.')
    return branch


# 무시되는 로컬 실행 상태를 읽는다.


def load(root):
    path = root / '.harness/task.json'
    return json.loads(path.read_text()) if path.exists() else None


# 임시 파일 교체로 실행 상태의 부분 쓰기를 피한다.


def save(root, task):
    folder = root / '.harness'
    folder.mkdir(exist_ok=True)
    path = folder / 'task.json'
    temp = folder / 'task.tmp'
    temp.write_text(json.dumps(task, ensure_ascii=False, indent=2) + '\n')
    temp.replace(path)


# 브랜치·커밋·파일 내용을 해시해 리뷰와 검증의 동일 대상을 증명한다.


def snapshot(root, include_index=True):
    # 무시되지 않은 추적·미추적 파일과 인덱스·HEAD를 함께 포함해 변경 증거를 묶는다.
    digest = hashlib.sha256()
    digest.update((current_branch(root) or 'DETACHED').encode())
    digest.update(git('rev-parse', 'HEAD', root=root))
    if include_index:
        digest.update(git('diff', '--cached', '--binary', root=root))
    names = git('ls-files', '--cached', '--others', '--exclude-standard', '-z', root=root)
    names += git('ls-tree', '-r', '--name-only', '-z', 'HEAD', root=root)
    for name in sorted(set(names.split(b'\0')) - {b''}):
        path = root / name.decode()
        digest.update(name + b'\0')
        if path.is_symlink():
            digest.update(b'link:' + str(path.readlink()).encode())
        elif path.is_file():
            digest.update(str(path.stat().st_mode).encode())
            digest.update(path.read_bytes())
        else:
            digest.update(b'deleted')
    return digest.hexdigest()


# 스테이징·작업 트리·미추적 변경을 합쳐 소유 범위를 확인한다.


def changed(root):
    names = set()
    for args in [('diff', '--name-only', '-z'),
                 ('diff', '--cached', '--name-only', '-z'),
                 ('ls-files', '--others', '--exclude-standard', '-z')]:
        names.update(n.decode() for n in git(*args, root=root).split(b'\0') if n)
    return names


# 활성 작업이 없는 상태에서 검증·종료가 실행되지 않게 한다.


def require_task(root):
    task = load(root)
    if not task or task['status'] != 'active':
        raise ValueError('No active task. Use begin first.')
    return task


# 출력과 종료 코드를 보존하며 시간 초과 시 자손 프로세스도 종료한다.


def run_check(check, root, stream, timeout=600):
    process = subprocess.Popen(check, shell=True, cwd=root, stdout=stream,
                               stderr=subprocess.STDOUT, start_new_session=True)
    try:
        return process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        # 셸을 회수하기 전에 살아 있는 프로세스 그룹을 종료한다.
        # TERM을 무시하는 자손까지 종료하고 이미 사라진 그룹에는 신호를 보내지 않는다.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()
        return 124


# 대화형 인증을 막고 원격 명령의 대기 시간을 제한한다.


def remote_output(root, *command):
    env = dict(os.environ, GH_PROMPT_DISABLED='1', GIT_TERMINAL_PROMPT='0')
    return subprocess.check_output(command, cwd=root, env=env, text=True, timeout=120)


# 정확한 커밋·원격·작업 브랜치를 확인한 뒤 dev PR까지 생성한다.


def publish(root, args):
    task = load(root)
    if not task or task['status'] not in ('committed', 'completed'):
        raise ValueError('Commit through finish before publishing.')
    branch = require_work_branch(root, task)
    if changed(root) or git('rev-parse', 'HEAD', root=root).decode().strip() != task.get('commit'):
        raise ValueError('Publish requires the clean, recorded task commit.')
    if task.get('committed_snapshot') != snapshot(root):
        raise ValueError('Committed snapshot changed. Review and verify a new task before publishing.')
    origin = git('remote', 'get-url', 'origin', root=root).decode().strip()
    push_urls = git('remote', 'get-url', '--push', '--all', 'origin', root=root).decode().splitlines()
    pattern = r'(?:https://github\.com/|ssh://git@github\.com/|git@github\.com:)([^/]+/[^/]+?)(?:\.git)?'
    destinations = [re.fullmatch(pattern, url) for url in [origin, *push_urls]]
    if len(push_urls) != 1 or any(not match or match.group(1).lower() != args.repo.lower()
                                  for match in destinations):
        raise ValueError('Explicit --repo must match the GitHub origin repository.')
    body = Path(args.body_file).resolve()
    if not body.is_file() or not body.read_text(encoding='utf-8').strip():
        raise ValueError('Provide a nonempty UTF-8 PR body file.')
    repo = 'github.com/' + args.repo

    # 기존 PR의 대상·헤드·상태를 검사해 중복 생성과 자동 병합을 막는다.

    def find_pr():
        result = json.loads(remote_output(root, 'gh', 'pr', 'list', '--repo', repo,
                            '--head', branch, '--base', 'dev', '--state', 'all', '--limit', '100',
                            '--json', 'number,url,state,headRefOid,isCrossRepository,autoMergeRequest'))
        matches = [pr for pr in result if not pr['isCrossRepository']]
        if len(matches) > 1:
            raise ValueError('Multiple PRs for this branch; inspect them manually.')
        if matches and matches[0]['state'] != 'OPEN':
            raise ValueError('A person closed or merged this PR; do not recreate it automatically.')
        if matches and matches[0]['autoMergeRequest'] is not None:
            raise ValueError('Auto-merge is enabled on this PR. Human-only merge policy requires manual attention.')
        return matches[0] if matches else None

    existing = find_pr()
    # 기록된 작업 브랜치만 push하며 dev/main/test 직접 갱신과 강제 push는 하지 않는다.
    remote_output(root, 'git', 'push', '--set-upstream', 'origin', f'HEAD:refs/heads/{branch}')
    if changed(root) or snapshot(root) != task['committed_snapshot']:
        raise ValueError('Repository changed during push; no PR created.')
    if existing is None:
        remote_output(root, 'gh', 'pr', 'create', '--repo', repo, '--base', 'dev', '--head', branch,
                      '--title', args.title, '--body-file', str(body))
    pr = find_pr()
    if not pr or pr['headRefOid'] != task['commit']:
        raise ValueError('PR does not contain the recorded commit yet. Inspect and retry publish.')
    if changed(root) or snapshot(root) != task['committed_snapshot']:
        raise ValueError('Repository changed during PR creation; inspect before completing.')
    task.update(status='completed', pr={'number': pr['number'], 'url': pr['url'],
                'head': pr['headRefOid'], 'base': 'dev', 'repo': args.repo}, completed_at=time.time())
    save(root, task)
    print(pr['url'])


# 상태와 새 스냅샷으로 후속 개선 지시를 만들며 직접 파일을 바꾸지 않는다.


def hook(root):
    event = json.load(sys.stdin)
    task = load(root)
    name = event.get('hook_event_name')
    output = {}
    if name == 'SessionStart':
        output = {'hookSpecificOutput': {'hookEventName': name,
                  'additionalContext': 'Read AGENTS.md and .codex/instructions/workflow.md first, '
                  'then docs/engineering/principles.md, '
                  'docs/engineering/preferences.md, docs/engineering/branches.md, '
                  'and docs/harness/state.md. '
                  'Run python3 scripts/harness.py status. Continue active or committed tasks; '
                  'committed tasks need publish, not another finish.'}}
    elif task and task['status'] in ('active', 'committed'):
        current = snapshot(root)
        if name == 'PostToolUse' and current != task.get('observed'):
            task['observed'] = current
            save(root, task)
            output = {'hookSpecificOutput': {'hookEventName': name,
                      'additionalContext': 'Repository snapshot changed. Review and verify the '
                      'final snapshot before finish. Update docs/harness/state.md.'}}
        elif name == 'Stop':
            task['continuations'] = task.get('continuations', 0) + 1
            if task['continuations'] <= 3:
                output = {'decision': 'block', 'reason':
                          'Active harness task is unfinished. Read harness status, delegate '
                          'review and verification, fix blocking findings, update memory, '
                          'run finish after gates pass, then publish the task branch as a dev PR. '
                          'Never post PR reviews/comments, approve, merge or enable auto-merge. '
                          'If blocked or user asked to stop, '
                          'run block with the reason. Do not claim unexecuted checks passed.'}
            else:
                task['resume_status'] = task['status']
                task['status'] = 'blocked'
                task['reason'] = 'Stop continuation limit reached; explicit resume required.'
                output = {'systemMessage': task['reason'] + ' No further commit or PR action performed.'}
            save(root, task)
    print(json.dumps(output))


# 소유 파일 입력을 저장소 상대 경로의 개별 파일로 검증해 정규화한다. begin과 own이 같은 규칙을 쓴다.


def owned_paths(root, items):
    files = []
    for item in items:
        path = Path(item)
        if path.is_absolute() or '..' in path.parts or not path.parts:
            raise ValueError('Owned files must be explicit repository-relative paths.')
        if path.parts[0] in ('.git', '.harness') or (root / path).is_dir():
            raise ValueError('Specify individual files, not directories or internal state.')
        files.append(path.as_posix())
    return files


# 명령별 상태 전이를 처리하고 최신 리뷰·검증 증거만 커밋에 사용한다.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    begin = commands.add_parser('begin')
    begin.add_argument('--id', required=True)
    begin.add_argument('--goal', required=True)
    begin.add_argument('--files', nargs='+', required=True)
    begin.add_argument('--check', action='append', required=True,
                       help='Trusted local validation shell command; repeat for multiple checks.')
    review = commands.add_parser('attest-review')
    review.add_argument('--snapshot', required=True)
    review.add_argument('--evidence', required=True,
                        help='Reviewer summary and reference; no unresolved blocking defects.')
    own = commands.add_parser('own', help='Add individual files to the active task ownership.')
    own.add_argument('--files', nargs='+', required=True)
    commands.add_parser('verify')
    finish = commands.add_parser('finish')
    finish.add_argument('--message', required=True)
    publish_command = commands.add_parser('publish')
    publish_command.add_argument('--repo', required=True)
    publish_command.add_argument('--title', required=True)
    publish_command.add_argument('--body-file', required=True)
    block = commands.add_parser('block')
    block.add_argument('--reason', required=True)
    commands.add_parser('resume')
    commands.add_parser('reopen', help='Re-review owned edits after commit without rewriting history.')
    commands.add_parser('status')
    commands.add_parser('hook')
    args = parser.parse_args()
    root = root_path()
    if args.command == 'hook':
        hook(root)
        return
    task = load(root)
    if args.command == 'status':
        print(json.dumps({'task': task, 'snapshot': snapshot(root),
                          'branch': current_branch(root),
                          'changed_files': sorted(changed(root))}, ensure_ascii=False, indent=2))
        return
    # 깨끗한 작업 브랜치와 소유 범위를 기록하며 기존 변경을 끌어들이지 않는다.
    if args.command == 'begin':
        branch = require_work_branch(root)
        ancestry = subprocess.run(['git', 'merge-base', '--is-ancestor', 'dev', 'HEAD'],
                                  cwd=root, capture_output=True)
        if ancestry.returncode != 0:
            raise ValueError('Task branch must include current local dev. Update from dev before begin.')
        if task and task['status'] in ('active', 'committed', 'blocked'):
            raise ValueError('Existing task must be finished or resumed; do not overwrite it.')
        if changed(root):
            raise ValueError('Begin requires a clean working tree; preserve existing changes separately.')
        files = owned_paths(root, args.files)
        task = {'id': args.id, 'goal': args.goal, 'files': sorted(set(files)),
                'branch': branch, 'dev_base': git('rev-parse', 'dev', root=root).decode().strip(),
                'checks': args.check, 'status': 'active', 'continuations': 0,
                'started_at': time.time(), 'observed': snapshot(root)}
        save(root, task)
        print('Task started. Record acceptance criteria in docs/harness/state.md.')
        return
    # 중단 전 상태로 복귀하되 활성 작업의 이전 통과 증거는 버린다.
    if args.command == 'resume':
        if not task or task['status'] != 'blocked':
            raise ValueError('Only a blocked task can be resumed.')
        require_work_branch(root, task)
        task.update(status=task.get('resume_status', 'active'), continuations=0)
        if task['status'] == 'active':
            task.pop('review', None)
            task.pop('verification', None)
        save(root, task)
        return
    # 기존 커밋 이력을 보존하고 후속 수정의 리뷰·검증 단계를 다시 연다.
    if args.command == 'reopen':
        if not task or (task['status'] != 'committed'
                        and not (task['status'] == 'blocked' and task.get('resume_status') == 'committed')):
            raise ValueError('Only an unpublished committed task can be reopened.')
        require_work_branch(root, task)
        if git('rev-parse', 'HEAD', root=root).decode().strip() != task['commit']:
            raise ValueError('HEAD changed; inspect the task history before reopening.')
        names = changed(root)
        if not names or names - set(task['files']):
            raise ValueError('Reopen requires edits within the original owned files.')
        if git('diff', '--cached', '--name-only', root=root).strip():
            raise ValueError('Inspect and unstage existing changes before reopening.')
        task.setdefault('previous_commits', []).append(task['commit'])
        task.update(status='active', continuations=0)
        for field in ('review', 'verification', 'committed_snapshot', 'commit', 'resume_status'):
            task.pop(field, None)
        save(root, task)
        print('Reopened for internal review and validation; finish will add a new commit.')
        return
    if args.command == 'publish':
        publish(root, args)
        return
    # 중단 이유와 재개 상태만 로컬에 기록하며 추적 파일을 바꾸지 않는다.
    if args.command == 'block':
        if not task or task['status'] not in ('active', 'committed'):
            raise ValueError('No unfinished task to block.')
        task['resume_status'] = task['status']
        task.update(status='blocked', reason=args.reason)
        save(root, task)
        return
    task = require_task(root)
    require_work_branch(root, task)
    # 작업 중 필요해진 파일을 소유 목록에 추가한다. task.json을 직접 고치지 않게 하는 경로다.
    # 범위만 넓힐 뿐이다. snapshot은 소유 여부와 무관하게 무시되지 않은 모든 파일을 포함하므로
    # 리뷰·검증 증거는 own 때문에 약해지지도 갱신되지도 않고, 이후 내용이 바뀌면 무효가 된다.
    if args.command == 'own':
        added = sorted(set(owned_paths(root, args.files)) - set(task['files']))
        task['files'] = sorted(set(task['files']) | set(added))
        if added:
            task.setdefault('ownership_additions', []).append({'files': added, 'at': time.time()})
        save(root, task)
        print('Added to ownership: ' + (', '.join(added) if added else '(nothing new)'))
        return
    # 현재 스냅샷을 직접 확인한 리뷰 결과만 유효하게 기록한다.
    if args.command == 'attest-review':
        current = snapshot(root)
        if args.snapshot != current:
            raise ValueError('Review snapshot is stale; review the current diff again.')
        task['review'] = {'snapshot': current, 'evidence': args.evidence}
        save(root, task)
    # 명령 종료 코드뿐 아니라 검증 전후 파일 동일성도 확인한다.
    elif args.command == 'verify':
        before = snapshot(root)
        task.pop('verification', None)
        save(root, task)
        results = []
        for index, check in enumerate(task['checks']):
            log = root / '.harness' / f'check-{index}.log'
            with log.open('w') as stream:
                code = run_check(check, root, stream)
            results.append({'command': check, 'exit_code': code, 'log': str(log.relative_to(root))})
        passed = all(r['exit_code'] == 0 for r in results) and before == snapshot(root)
        task['verification'] = {'snapshot': before, 'passed': passed, 'results': results}
        save(root, task)
        print(json.dumps(task['verification'], indent=2))
        if not passed:
            raise ValueError('Validation failed or changed repository files. Inspect logs and rerun.')
    # 최신 리뷰·검증·소유 범위를 모두 확인한 뒤 명시적 파일만 커밋한다.
    elif args.command == 'finish':
        current = snapshot(root)
        review = task.get('review', {})
        verification = task.get('verification', {})
        if review.get('snapshot') != current or verification.get('snapshot') != current or not verification.get('passed'):
            raise ValueError('Latest snapshot needs independent review and successful verification.')
        names = changed(root)
        if not names or names - set(task['files']):
            raise ValueError('No changes or changes outside task ownership. Check status.')
        if 'docs/harness/state.md' not in names:
            raise ValueError('Update durable task memory before finishing.')
        if git('diff', '--cached', '--name-only', root=root).strip():
            raise ValueError('Index must be empty before finish; inspect existing staging.')
        subprocess.run(['git', 'diff', '--check'], cwd=root, check=True)
        # 쓰기 담당자는 하나다. 사전 검사 중 파일이 변하면 커밋을 거부한다.
        if current != snapshot(root):
            raise ValueError('Snapshot changed during preflight.')
        content = snapshot(root, include_index=False)
        subprocess.run(['git', '--literal-pathspecs', 'add', '--', *sorted(names)], cwd=root, check=True)
        if content != snapshot(root, include_index=False):
            raise ValueError('Content changed during staging; inspect index and rerun gates.')
        if set(n.decode() for n in git('diff', '--cached', '--name-only', '-z', root=root).split(b'\0') if n) != names:
            raise ValueError('Staged file list changed; inspect index.')
        subprocess.run(['git', 'diff', '--cached', '--check'], cwd=root, check=True)
        staged = snapshot(root)
        expected_tree = git('write-tree', root=root).strip()
        require_work_branch(root, task)
        subprocess.run(['git', 'commit', '-m', args.message], cwd=root, check=True)
        if (git('rev-parse', 'HEAD^{tree}', root=root).strip() != expected_tree
                or changed(root) or current_branch(root) != task['branch']):
            task.update(status='blocked', reason='Git hooks or concurrent writes changed the reviewed tree. '
                        'Inspect the created commit and working tree; no automatic rollback.')
            task.pop('review', None)
            task.pop('verification', None)
            save(root, task)
            raise ValueError(task['reason'])
        task.update(status='committed', commit=git('rev-parse', 'HEAD', root=root).decode().strip(),
                    finished_at=time.time(), staged_snapshot=staged, committed_snapshot=snapshot(root))
        save(root, task)
        print('Committed ' + task['commit'])


# 변경 명령과 훅을 같은 잠금 경계로 직렬화하여 두 실행의 상태 쓰기 충돌을 막는다.
if __name__ == '__main__':
    try:
        if len(sys.argv) > 1 and sys.argv[1] not in ('status', '-h', '--help'):
            folder = root_path() / '.harness'
            folder.mkdir(exist_ok=True)
            with (folder / 'lock').open('a') as lock:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    if sys.argv[1] == 'hook':
                        print('{}')
                        sys.exit(0)
                    raise ValueError('Harness operation already running. Retry after it finishes.')
                main()
        else:
            main()
    except (ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError, json.JSONDecodeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
