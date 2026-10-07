#!/usr/bin/env python3
"""Task-scoped Codex review/verify/commit loop. Standard library only."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def git(*args, root=None):
    return subprocess.check_output(['git', *args], cwd=root)


def root_path():
    return Path(git('rev-parse', '--show-toplevel').decode().strip())


def load(root):
    path = root / '.harness/task.json'
    return json.loads(path.read_text()) if path.exists() else None


def save(root, task):
    folder = root / '.harness'
    folder.mkdir(exist_ok=True)
    path = folder / 'task.json'
    temp = folder / 'task.tmp'
    temp.write_text(json.dumps(task, ensure_ascii=False, indent=2) + '\n')
    temp.replace(path)


def snapshot(root, include_index=True):
    # Include tracked and untracked, nonignored files, plus index state and HEAD.
    digest = hashlib.sha256()
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


def changed(root):
    names = set()
    for args in [('diff', '--name-only', '-z'),
                 ('diff', '--cached', '--name-only', '-z'),
                 ('ls-files', '--others', '--exclude-standard', '-z')]:
        names.update(n.decode() for n in git(*args, root=root).split(b'\0') if n)
    return names


def require_task(root):
    task = load(root)
    if not task or task['status'] != 'active':
        raise ValueError('No active task. Use begin first.')
    return task


def run_check(check, root, stream, timeout=600):
    process = subprocess.Popen(check, shell=True, cwd=root, stdout=stream,
                               stderr=subprocess.STDOUT, start_new_session=True)
    try:
        return process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        # Kill the still-live group before reaping the shell. This also stops
        # descendants ignoring TERM and avoids signaling an already-dead group.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()
        return 124


def hook(root):
    event = json.load(sys.stdin)
    task = load(root)
    name = event.get('hook_event_name')
    output = {}
    if name == 'SessionStart':
        output = {'hookSpecificOutput': {'hookEventName': name,
                  'additionalContext': 'Read AGENTS.md and docs/harness/state.md. '
                  'Run python3 scripts/harness.py status. Resume only the active task.'}}
    elif task and task['status'] == 'active':
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
                          'and run finish after gates pass. If blocked or user asked to stop, '
                          'run block with the reason. Do not claim unexecuted checks passed.'}
            else:
                task['status'] = 'blocked'
                task['reason'] = 'Stop continuation limit reached; explicit resume required.'
                output = {'systemMessage': task['reason'] + ' No automatic commit performed.'}
            save(root, task)
    print(json.dumps(output))


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
    commands.add_parser('verify')
    finish = commands.add_parser('finish')
    finish.add_argument('--message', required=True)
    block = commands.add_parser('block')
    block.add_argument('--reason', required=True)
    commands.add_parser('resume')
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
                          'changed_files': sorted(changed(root))}, ensure_ascii=False, indent=2))
        return
    if args.command == 'begin':
        if task and task['status'] in ('active', 'blocked'):
            raise ValueError('Existing task must be finished or resumed; do not overwrite it.')
        if changed(root):
            raise ValueError('Begin requires a clean working tree; preserve existing changes separately.')
        files = []
        for item in args.files:
            path = Path(item)
            if path.is_absolute() or '..' in path.parts or not path.parts:
                raise ValueError('Owned files must be explicit repository-relative paths.')
            if path.parts[0] in ('.git', '.harness') or (root / path).is_dir():
                raise ValueError('Specify individual files, not directories or internal state.')
            files.append(path.as_posix())
        task = {'id': args.id, 'goal': args.goal, 'files': sorted(set(files)),
                'checks': args.check, 'status': 'active', 'continuations': 0,
                'started_at': time.time(), 'observed': snapshot(root)}
        save(root, task)
        print('Task started. Record acceptance criteria in docs/harness/state.md.')
        return
    if args.command == 'resume':
        if not task or task['status'] != 'blocked':
            raise ValueError('Only a blocked task can be resumed.')
        task.update(status='active', continuations=0)
        task.pop('review', None)
        task.pop('verification', None)
        save(root, task)
        return
    task = require_task(root)
    if args.command == 'block':
        task.update(status='blocked', reason=args.reason)
        save(root, task)
        return
    if args.command == 'attest-review':
        current = snapshot(root)
        if args.snapshot != current:
            raise ValueError('Review snapshot is stale; review the current diff again.')
        task['review'] = {'snapshot': current, 'evidence': args.evidence}
        save(root, task)
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
        # One coordinator owns writes. Refuse if anything changed during preflight.
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
        subprocess.run(['git', 'commit', '-m', args.message], cwd=root, check=True)
        if git('rev-parse', 'HEAD^{tree}', root=root).strip() != expected_tree or changed(root):
            task.update(status='blocked', reason='Git hooks or concurrent writes changed the reviewed tree. '
                        'Inspect the created commit and working tree; no automatic rollback.')
            task.pop('review', None)
            task.pop('verification', None)
            save(root, task)
            raise ValueError(task['reason'])
        task.update(status='completed', commit=git('rev-parse', 'HEAD', root=root).decode().strip(),
                    finished_at=time.time(), staged_snapshot=staged)
        save(root, task)
        print('Committed ' + task['commit'])


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
    except (ValueError, subprocess.CalledProcessError, OSError, json.JSONDecodeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
