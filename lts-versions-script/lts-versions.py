#!/usr/bin/env python3
"""
Generate HTML report with LTS and main SNAPSHOT versions for qubership-core-java-libs.

    lts-versions.py [output.html] [--repo <monorepo-path>]
"""
import argparse
import io
import subprocess
import sys
import tarfile
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from jinja2 import Environment, FileSystemLoader

REPO_ROOT = Path(__file__).resolve().parent.parent  # overridden by --repo


def git_text(*args):
    return subprocess.run(
        ['git', '-C', str(REPO_ROOT), *args],
        capture_output=True, text=True, check=True,
    ).stdout


def git_bytes(*args):
    return subprocess.run(
        ['git', '-C', str(REPO_ROOT), *args],
        capture_output=True, check=True,
    ).stdout


def get_branches():
    lines = git_text('branch', '-a', '--list', '*lts/*').splitlines()
    seen = set()
    for line in lines:
        name = line.strip().lstrip('* ').replace('remotes/origin/', '')
        if name.startswith('lts/'):
            seen.add(name)
    return sorted(seen)


def get_ref(branch):
    for ref in (f'origin/{branch}', branch):
        try:
            return git_text('rev-parse', ref).strip()
        except subprocess.CalledProcessError:
            pass
    raise RuntimeError(f'Cannot resolve ref for branch: {branch}')


def extract_field(text, tag, parent_fallback=False):
    """Return value of first <tag>…</tag> outside the <parent> block.

    With parent_fallback=True: skips variable references (${...}), stops at
    <dependencies>/<build>/etc., and returns the parent's value as a fallback.
    Used for groupId, which may be absent or inherited from parent.
    """
    in_parent = False
    open_tag, close_tag = f'<{tag}>', f'</{tag}>'
    parent_val = None
    for line in text.splitlines():
        if '<parent>'  in line: in_parent = True
        if '</parent>' in line: in_parent = False; continue
        if in_parent:
            if parent_fallback and open_tag in line and close_tag in line:
                parent_val = line.split(open_tag)[1].split(close_tag)[0].strip()
            continue
        if parent_fallback and any(t in line for t in ('<dependencies>', '<build>', '<profiles>', '<reporting>')):
            break
        if open_tag in line and close_tag in line:
            val = line.split(open_tag)[1].split(close_tag)[0].strip()
            if not (parent_fallback and '${' in val):
                return val
    return parent_val


def released_version(snapshot):
    base = snapshot.removesuffix('-SNAPSHOT')
    prefix, patch = base.rsplit('.', 1)
    return f'{prefix}.{int(patch) - 1}'


def collect_branch(branch, raw_version=False):
    """Return {module: {'version': str | None, 'artifacts': set[str]}}"""
    ref = get_ref(branch)

    all_paths = git_text('ls-tree', '-r', '--name-only', ref).splitlines()
    pom_paths = [p for p in all_paths if p.endswith('pom.xml') and '/' in p]
    if not pom_paths:
        return {}

    archive = git_bytes('archive', ref, *pom_paths)
    data = {}

    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar.getmembers():
            f = tar.extractfile(member)
            if f is None:
                continue
            content = f.read().decode('utf-8', errors='replace')
            parts   = member.name.split('/')
            module  = parts[0]
            is_root = len(parts) == 2  # <module>/pom.xml

            artifact_id = extract_field(content, 'artifactId')
            group_id    = extract_field(content, 'groupId', parent_fallback=True)
            if not artifact_id:
                continue

            artifact = f'{group_id}:{artifact_id}' if group_id else artifact_id

            if module not in data:
                data[module] = {'version': None, 'artifacts': set()}
            data[module]['artifacts'].add(artifact)

            if is_root:
                version = extract_field(content, 'version')
                if version and '-SNAPSHOT' in version:
                    data[module]['version'] = version if raw_version else released_version(version)

    return data


def export_filename(branch):
    return branch.replace('/', '_') + '.txt'


def write_export_files(branches, modules, artifacts, versions, main_versions, output_dir):
    def write(filename, ver_map):
        lines = []
        for module in modules:
            ver = ver_map.get(module)
            if ver:
                lines.extend(f'{artifact}:{ver}' for artifact in artifacts[module])
        (output_dir / filename).write_text('\n'.join(lines), encoding='utf-8')

    for branch in branches:
        write(export_filename(branch), versions.get(branch, {}))
    write('main_snapshot.txt', main_versions)


def generate_html(branches, modules, artifacts, versions, main_versions, output_path):
    env = Environment(
        loader=FileSystemLoader(Path(__file__).parent),
        trim_blocks=True,
        lstrip_blocks=True,
    )
    html = env.get_template('report.html.j2').render(
        ts=datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC'),
        branches=branches,
        modules=modules,
        artifacts=artifacts,
        versions=versions,
        main_versions=main_versions,
    )
    output_path.write_text(html, encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        'output', nargs='?', default='versions-report.html',
        help='output HTML file (default: versions-report.html)',
    )
    parser.add_argument(
        '--repo', default=None,
        help='path to the monorepo root (default: two directories above this script)',
    )
    args = parser.parse_args()

    if args.repo:
        global REPO_ROOT
        REPO_ROOT = Path(args.repo).resolve()

    branches = get_branches()
    if not branches:
        print('No lts/* branches found.', file=sys.stderr)
        sys.exit(1)
    print(f'Collecting versions from branches: {", ".join(branches)}')

    all_artifacts = defaultdict(set)
    versions = {}
    for branch in branches:
        data = collect_branch(branch)
        versions[branch] = {m: d['version'] for m, d in data.items()}
        for module, d in data.items():
            all_artifacts[module].update(d['artifacts'])

    print('Collecting SNAPSHOT versions from main...')
    main_data = collect_branch('main', raw_version=True)
    main_versions = {m: d['version'] for m, d in main_data.items()}
    for module, d in main_data.items():
        all_artifacts[module].update(d['artifacts'])

    modules   = sorted(all_artifacts)
    artifacts = {m: sorted(all_artifacts[m]) for m in modules}
    output    = Path(args.output)
    write_export_files(branches, modules, artifacts, versions, main_versions, output.parent)
    generate_html(branches, modules, artifacts, versions, main_versions, output)
    print(f'Report written to: {output}')


if __name__ == '__main__':
    main()
