#!/usr/bin/env python3
"""
Generate HTML report with LTS and main versions for Java and Go repositories.

    lts-versions.py [output.html]
      --java-repos <file>       text file with Netcracker/repo lines
      --java-workspace <path>   directory where Java repos are checked out
      --go-repos <file>         text file with Netcracker/repo lines
"""
import argparse
import concurrent.futures
import io
import os
import subprocess
import sys
import tarfile
import tempfile
from collections import defaultdict
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path

from jinja2 import Environment, FileSystemLoader

_SCRIPT_DIR = Path(__file__).resolve().parent


# ── Git helpers ───────────────────────────────────────────────────────────────

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


@lru_cache(maxsize=None)
def ref_exists(repo, ref):
    try:
        git_text(repo, 'rev-parse', '--verify', ref)
        return True
    except subprocess.CalledProcessError:
        return False


def read_repos_file(path):
    return [
        line.strip() for line in Path(path).read_text(encoding='utf-8').splitlines()
        if line.strip() and not line.strip().startswith('#')
    ]


# ── Java ─────────────────────────────────────────────────────────────────────

def get_java_branches(repo):
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
    """Return value of first <tag>…</tag> outside the <parent> block."""
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
    for p in Path(repo).glob('*/pom.xml'):
        content = p.read_text(encoding='utf-8', errors='replace')
        if _has_own_version(content):
            return True
    return False


def collect_java_branch(repo, branch, raw_version=False):
    ref = get_ref(repo, branch)
    all_paths = git_text(repo, 'ls-tree', '-r', '--name-only', ref).splitlines()
    if is_monorepo(repo):
        return _collect_monorepo(repo, ref, all_paths, raw_version)
    else:
        return _collect_product(repo, ref, all_paths, raw_version)


def _collect_monorepo(repo, ref, all_paths, raw_version):
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
            is_root = len(parts) == 2
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


def collect_java(repos_file, workspace):
    org_repos = read_repos_file(repos_file)
    repos = [Path(workspace) / r.split('/')[-1] for r in org_repos]
    if not repos:
        print('No repos found in Java repos file.', file=sys.stderr)
        return None

    all_branch_sets = [set(get_java_branches(r)) for r in repos]
    branches = sorted(set.union(*all_branch_sets))
    if not branches:
        print('No lts/* branches found in Java repos.', file=sys.stderr)
        return None
    print(f'Java: collecting from branches: {", ".join(branches)}')

    all_artifacts = defaultdict(set)
    versions = {}
    for branch in branches:
        branch_data = {}
        for repo in repos:
            try:
                data = collect_java_branch(repo, branch)
                branch_data.update(data)
            except (subprocess.CalledProcessError, RuntimeError):
                pass
        versions[branch] = {m: d['version'] for m, d in branch_data.items()}
        for module, d in branch_data.items():
            all_artifacts[module].update(d['artifacts'])

    print('Java: collecting SNAPSHOT versions from main...')
    main_data = {}
    for repo in repos:
        try:
            main_data.update(collect_java_branch(repo, 'main', raw_version=True))
        except (subprocess.CalledProcessError, RuntimeError):
            pass
    main_versions = {m: d['version'] for m, d in main_data.items()}
    for module, d in main_data.items():
        all_artifacts[module].update(d['artifacts'])

    file_order = {r.split('/')[-1]: i for i, r in enumerate(org_repos)}
    modules    = sorted(all_artifacts, key=lambda m: file_order.get(m, len(file_order)))
    artifacts  = {m: sorted(all_artifacts[m]) for m in modules}
    return dict(branches=branches, modules=modules, artifacts=artifacts,
                versions=versions, main_versions=main_versions)


# ── Go ───────────────────────────────────────────────────────────────────────

def _clone_go_repo(org_repo, token, tmpdir):
    name = org_repo.split('/')[-1]
    dest = Path(tmpdir) / name
    url  = f'https://x-token-auth:{token}@github.com/{org_repo}.git'
    subprocess.run(
        ['git', 'clone', '--filter=blob:none', '--no-checkout', url, str(dest)],
        capture_output=True, check=True,
    )
    return dest


def _find_go_modules(repo, ref):
    """Return list of (subdir, module_path) for every go.mod found at ref.

    subdir is the directory containing go.mod (empty string for root).
    All go.mod files are returned without filtering.
    """
    if ref is None:
        return []
    try:
        all_paths = git_text(repo, 'ls-tree', '-r', '--name-only', ref).splitlines()
    except subprocess.CalledProcessError:
        return []
    go_mods = sorted(
        [p for p in all_paths if p == 'go.mod' or p.endswith('/go.mod')],
        key=lambda p: p.count('/'),
    )
    modules = []
    for go_mod_path in go_mods:
        subdir = go_mod_path[:-len('/go.mod')] if '/' in go_mod_path else ''
        try:
            content = git_text(repo, 'show', f'{ref}:{go_mod_path}')
        except subprocess.CalledProcessError:
            continue
        for line in content.splitlines():
            if line.startswith('module '):
                mod_path = line.split(None, 1)[1].strip()
                modules.append((subdir, mod_path))
                break
    return modules


def _latest_semver_tag(repo, ref, subdir=''):
    """Latest semver tag reachable from ref, optionally scoped to a subdir.

    For a subdir module, tags look like 'subdir/vX.Y.Z'; the returned value
    strips the prefix and returns 'vX.Y.Z'.
    """
    if not ref_exists(repo, ref):
        return None
    try:
        tags = git_text(repo, 'tag', '--merged', ref,
                        '--sort=-version:refname').splitlines()
    except subprocess.CalledProcessError:
        return None
    for tag in tags:
        tag = tag.strip()
        if subdir:
            prefix = subdir + '/v'
            if tag.startswith(prefix) and tag[len(prefix):len(prefix) + 1].isdigit():
                return tag[len(subdir) + 1:]
        else:
            if tag and tag[0] == 'v' and len(tag) > 1 and tag[1].isdigit():
                return tag
    return None


def _collect_one_go_repo(org_repo, token, tmpdir):
    """Return list of (module_path, branch_versions, main_version) for each go.mod found."""
    try:
        repo = _clone_go_repo(org_repo, token, tmpdir)
    except subprocess.CalledProcessError as e:
        print(f'  WARNING: failed to clone {org_repo}: exit code {e.returncode}', file=sys.stderr)
        return []

    try:
        repo_name = org_repo.split('/')[-1]
        branches  = get_java_branches(repo)
        main_ref  = next(
            (r for r in ('origin/main', 'origin/master') if ref_exists(repo, r)),
            None,
        )
        go_modules = _find_go_modules(repo, main_ref)
        if not go_modules:
            return []

        entries = []
        for subdir, module_path in go_modules:
            branch_versions = {}
            for branch in branches:
                tag = _latest_semver_tag(repo, f'origin/{branch}', subdir=subdir)
                if tag:
                    branch_versions[branch] = tag
            main_version = _latest_semver_tag(repo, main_ref, subdir=subdir) if main_ref else None
            entries.append((module_path, branch_versions, main_version))

        return entries
    except Exception as e:
        print(f'  WARNING: failed to collect data from {org_repo}: {e}', file=sys.stderr)
        return []


def collect_go(repos_file, token):
    org_repos = read_repos_file(repos_file)
    print(f'Go: cloning {len(org_repos)} repositories...')

    # results: {repo_name: [(module_path, branches, branch_versions, main_version), ...]}
    raw: dict[str, list] = {}
    with tempfile.TemporaryDirectory(prefix='go-repos-') as tmpdir:
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            futures = {
                pool.submit(_collect_one_go_repo, r, token, tmpdir): r
                for r in org_repos
            }
            for fut in concurrent.futures.as_completed(futures):
                org_repo = futures[fut]
                try:
                    entries = fut.result()
                except Exception as e:
                    print(f'  WARNING: unexpected error for {org_repo}: {e}', file=sys.stderr)
                    entries = []
                if entries:
                    repo_name = org_repo.split('/')[-1]
                    raw[repo_name] = entries

    # Collect all_branches from entries
    all_branches: set[str] = set()
    for org_repo in org_repos:
        repo_name = org_repo.split('/')[-1]
        if repo_name in raw:
            for _, bv, _ in raw[repo_name]:
                all_branches.update(bv.keys())

    file_order      = {r.split('/')[-1]: i for i, r in enumerate(org_repos)}
    branches_sorted = sorted(all_branches)
    repos_sorted    = sorted(raw.keys(), key=lambda r: file_order.get(r, len(file_order)))
    print(f'Go: found branches: {", ".join(branches_sorted) or "(none)"}')

    if not repos_sorted:
        return None

    # repo_modules: {repo_name: [module_path, ...]}
    # versions:     {branch: {module_path: version}}
    # main_versions:{module_path: version}
    repo_modules  = {r: [mod for mod, _, _ in raw[r]] for r in repos_sorted}
    versions      = defaultdict(dict)
    main_versions = {}
    for repo_name in repos_sorted:
        for mod_path, branch_versions, main_version in raw[repo_name]:
            for branch, ver in branch_versions.items():
                versions[branch][mod_path] = ver
            if main_version:
                main_versions[mod_path] = main_version

    return dict(branches=branches_sorted, repos=repos_sorted,
                repo_modules=repo_modules, versions=dict(versions),
                main_versions=main_versions)


# ── Export files ─────────────────────────────────────────────────────────────

def export_filename(branch):
    return branch.replace('/', '_') + '.txt'


def write_java_export_files(java_data, output_dir):
    branches     = java_data['branches']
    modules      = java_data['modules']
    artifacts    = java_data['artifacts']
    versions     = java_data['versions']
    main_versions = java_data['main_versions']

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


def write_go_export_files(go_data, output_dir):
    repos        = go_data['repos']
    repo_modules = go_data['repo_modules']
    versions     = go_data['versions']
    main_versions = go_data['main_versions']

    def write(filename, ver_map):
        lines = []
        for repo in repos:
            for mod_path in repo_modules[repo]:
                ver = ver_map.get(mod_path)
                if ver:
                    lines.append(f'{mod_path}@{ver}')
        (output_dir / filename).write_text('\n'.join(lines), encoding='utf-8')

    for branch in go_data['branches']:
        write('go_' + export_filename(branch), versions.get(branch, {}))
    write('go_main.txt', main_versions)


# ── HTML ─────────────────────────────────────────────────────────────────────

def generate_html(java_data, go_data, output_path):
    env = Environment(
        loader=FileSystemLoader(_SCRIPT_DIR),
        trim_blocks=True,
        lstrip_blocks=True,
    )
    html = env.get_template('report.html.j2').render(
        ts=datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC'),
        java=java_data,
        go=go_data,
    )
    output_path.write_text(html, encoding='utf-8')


# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        'output', nargs='?', default='versions-report.html',
        help='output HTML file (default: versions-report.html)',
    )
    parser.add_argument('--java-repos', metavar='FILE',
                        help='text file with Netcracker/repo lines for Java repos')
    parser.add_argument('--java-workspace', metavar='PATH', default='.',
                        help='directory where Java repos are checked out (default: .)')
    parser.add_argument('--go-repos', metavar='FILE',
                        help='text file with Netcracker/repo lines for Go repos')
    args = parser.parse_args()

    if not args.java_repos and not args.go_repos:
        parser.error('specify at least one of --java-repos or --go-repos')

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)

    java_data = None
    if args.java_repos:
        java_data = collect_java(args.java_repos, args.java_workspace)

    go_data = None
    if args.go_repos:
        token = os.environ.get('GH_TOKEN', '')
        if not token:
            print('WARNING: GH_TOKEN not set; Go repo cloning may fail', file=sys.stderr)
        go_data = collect_go(args.go_repos, token)

    if not java_data and not go_data:
        print('ERROR: no data collected from any repository.', file=sys.stderr)
        sys.exit(1)

    if java_data:
        write_java_export_files(java_data, output.parent)

    if go_data:
        write_go_export_files(go_data, output.parent)

    generate_html(java_data, go_data, output)
    print(f'Report written to: {output}')


if __name__ == '__main__':
    main()
