#!/usr/bin/env python3
"""Use PostgreSQL tools from the exact disposable server image.

Invoked through psql/pg_dump/pg_restore symlinks. Backup bytes stay in the runner's
evidence directory; they are streamed over docker exec, not written to arbitrary
container paths. No reliance on the runner's multiple installed client versions.
"""
import os
from pathlib import Path
import re
import subprocess
import sys
from contextlib import ExitStack


def main():
    tool = Path(sys.argv[0]).name
    container = os.environ.get('SMOKE_PG_CONTAINER', '')
    if (os.environ.get('GITHUB_ACTIONS') != 'true'
            or os.environ.get('SMOKE_DISPOSABLE_ENV') != '1'
            or not re.fullmatch(r'[0-9a-f]{12,64}', container)
            or tool not in {'psql', 'pg_dump', 'pg_restore'}):
        raise SystemExit('PostgreSQL test adapter requires an explicit disposable service container')
    args = sys.argv[1:]
    root = Path(os.environ['RUNNER_TEMP']).resolve()
    def evidence_path(value):
        path = Path(value).resolve()
        if not path.is_relative_to(root):
            raise SystemExit('Backup files must be inside the disposable runner evidence directory')
        return path
    with ExitStack() as stack:
        stdin, stdout = None, None
        if tool == 'pg_dump' and '-f' in args:
            index = args.index('-f')
            destination = evidence_path(args[index + 1])
            del args[index:index + 2]
            stdout = stack.enter_context(destination.open('wb'))
        elif tool == 'pg_restore' and args and Path(args[-1]).is_file():
            source = evidence_path(args.pop())
            stdin = stack.enter_context(source.open('rb'))
        command = ['docker', 'exec', '-i', '-e', 'PGPASSWORD', '-e', 'PGCLIENTENCODING',
                   container, tool, *args]
        result = subprocess.run(command, stdin=stdin, stdout=stdout, timeout=60)
        raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
