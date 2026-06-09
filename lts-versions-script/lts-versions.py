#!/usr/bin/env python3
"""
Generate HTML report with LTS and main SNAPSHOT versions for one or more repositories.

    lts-versions.py [output.html] [--repo <path>] [--repo <path> ...]
"""
import argparse
import io
import subprocess
import sys
import tarfile
from collections import defaultdict
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path

from jinja2 import Environment, FileSystemLoader

_SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_REPO = _SCRIPT_DIR.parent  # fallback when no --repo given


def git_text(repo, *args):
    return subprocess.run(
        ['git', '-C', str(repo), *args],
        capture_output=True, text=True, check=True,
    ).stdout


def git_bytes(repo, *args):
    return subprocess.run(
        ['git', '-C', str(repo), *args],
        capture_output=True, check=True,
    ).stdout


def get_branches(repo):
    lines = git_text(repo, 'branch', '-a', '--list', '*lts/*').splitlines()
    seen = set()
    for line in lines:
        name = line.strip().lstrip('* ').replace('remotes/origin/', '')
        if name.startswith('lts/'):
            seen.add(name)
    return sorted(seen)


def get_ref(repo, branch):
    for ref in (f'origin/{branch}', branch):
        try:
            return git_text(repo, 'rev-parse', ref).strip()
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


def _has_own_version(pom_text):
    """True if pom.xml declares its own <version> (not inside <parent> or <dependencies>)."""
    in_parent = False
    for line in pom_text.splitlines():
        if '<parent>' in line:
            in_parent = True
        elif '</parent>' in line:
            in_parent = False
        elif in_parent:
            continue
        elif any(t in line for t in ('<dependencies>', '<dependencyManagement>', '<build>', '<profiles>', '<reporting>')):
            return False
        elif '<version>' in line and '</version>' in line:
            return True
    return False


@lru_cache(maxsize=None)
def is_monorepo(repo):
    """True if top-level subdirectory pom.xml files each carry their own <version>."""
    for p in Path(repo).glob('*/pom.xml'):
        content = p.read_text(encoding='utf-8', errors='replace')
        if _has_own_version(content):
            return True
    return False


def collect_branch(repo, branch, raw_version=False):
    """Return {module: {'version': str | None, 'artifacts': set[str]}}"""
    ref = get_ref(repo, branch)
    all_paths = git_text(repo, 'ls-tree', '-r', '--name-only', ref).splitlines()

    if is_monorepo(repo):
        return _collect_monorepo(repo, ref, all_paths, raw_version)
    else:
        return _collect_product(repo, ref, all_paths, raw_version)


def _collect_monorepo(repo, ref, all_paths, raw_version):
    """Each top-level subdirectory is an independent module with its own version."""
    pom_paths = [p for p in all_paths if p.endswith('pom.xml') and '/' in p]
    if not pom_paths:
        return {}

    archive = git_bytes(repo, 'archive', ref, *pom_paths)
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


def _collect_product(repo, ref, all_paths, raw_version):
    """Single-product repo: version from root pom.xml, artifacts from all first-level sub-modules."""
    pom_paths = [p for p in all_paths if p == 'pom.xml' or
                 (p.endswith('pom.xml') and p.count('/') == 1)]
    if not pom_paths:
        return {}

    archive = git_bytes(repo, 'archive', ref, *pom_paths)
    module_name = Path(repo).name
    version = None
    artifacts = set()

    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar.getmembers():
            f = tar.extractfile(member)
            if f is None:
                continue
            content = f.read().decode('utf-8', errors='replace')

            artifact_id = extract_field(content, 'artifactId')
            group_id    = extract_field(content, 'groupId', parent_fallback=True)
            if not artifact_id:
                continue

            artifacts.add(f'{group_id}:{artifact_id}' if group_id else artifact_id)

            if member.name == 'pom.xml':
                ver = extract_field(content, 'version')
                if ver and '-SNAPSHOT' in ver:
                    version = ver if raw_version else released_version(ver)

    return {module_name: {'version': version, 'artifacts': artifacts}}


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
        '--repo', action='append', dest='repos', metavar='PATH',
        help='path to a repository root; repeat for multiple repos (default: parent of script dir)',
    )
    args = parser.parse_args()

    repos = [Path(r).resolve() for r in (args.repos or [DEFAULT_REPO])]

    all_branch_sets = [set(get_branches(r)) for r in repos]
    branches = sorted(set.union(*all_branch_sets))
    if not branches:
        print('No lts/* branches found.', file=sys.stderr)
        sys.exit(1)
    print(f'Collecting versions from branches: {", ".join(branches)}')

    all_artifacts = defaultdict(set)
    versions = {}
    for branch in branches:
        branch_data = {}
        for repo in repos:
            try:
                data = collect_branch(repo, branch)
                branch_data.update(data)
            except (subprocess.CalledProcessError, RuntimeError):
                pass
        versions[branch] = {m: d['version'] for m, d in branch_data.items()}
        for module, d in branch_data.items():
            all_artifacts[module].update(d['artifacts'])

    print('Collecting SNAPSHOT versions from main...')
    main_data = {}
    for repo in repos:
        main_data.update(collect_branch(repo, 'main', raw_version=True))
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
