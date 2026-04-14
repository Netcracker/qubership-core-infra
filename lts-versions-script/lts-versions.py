#!/usr/bin/env python3
"""
LTS versions for qubership-core-java-libs.

HTML report (all branches):
    lts-versions.py [output.html]

Single-branch text output:
    lts-versions.py --branch lts/26.2
    lts-versions.py --branch lts/26.2 --format properties
    lts-versions.py --branch lts/26.2 --format csv
"""
import argparse
import io
import subprocess
import sys
import tarfile
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent  # overridden by --repo


# ---------------------------------------------------------------------------
# Git helpers
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# Data collection
# ---------------------------------------------------------------------------

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


def extract_field(text, tag):
    """Return value of first <tag>…</tag> outside the <parent> block."""
    in_parent = False
    open_tag, close_tag = f'<{tag}>', f'</{tag}>'
    for line in text.splitlines():
        if '<parent>'  in line: in_parent = True
        if '</parent>' in line: in_parent = False; continue
        if in_parent:           continue
        if open_tag in line and close_tag in line:
            return line.split(open_tag)[1].split(close_tag)[0].strip()
    return None


def extract_group_id(text):
    """Return the project's own groupId, or the parent's groupId if absent/variable."""
    in_parent = False
    parent_group = None
    open_tag, close_tag = '<groupId>', '</groupId>'
    stop_tags = ('<dependencies>', '<build>', '<profiles>', '<reporting>')
    for line in text.splitlines():
        stripped = line.strip()
        if '<parent>'  in stripped: in_parent = True
        if '</parent>' in stripped: in_parent = False; continue
        if in_parent:
            if open_tag in stripped and close_tag in stripped:
                parent_group = stripped.split(open_tag)[1].split(close_tag)[0].strip()
            continue
        if any(t in stripped for t in stop_tags):
            break
        if open_tag in stripped and close_tag in stripped:
            val = stripped.split(open_tag)[1].split(close_tag)[0].strip()
            if '${' not in val:
                return val
    return parent_group


def released_version(snapshot):
    base = snapshot.removesuffix('-SNAPSHOT')
    prefix, patch = base.rsplit('.', 1)
    return f'{prefix}.{int(patch) - 1}'


def collect_branch(branch):
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
            group_id    = extract_group_id(content)
            if not artifact_id:
                continue

            artifact = f'{group_id}:{artifact_id}' if group_id else artifact_id

            if module not in data:
                data[module] = {'version': None, 'artifacts': set()}
            data[module]['artifacts'].add(artifact)

            if is_root:
                version = extract_field(content, 'version')
                if version and '-SNAPSHOT' in version:
                    data[module]['version'] = released_version(version)

    return data


# ---------------------------------------------------------------------------
# HTML output
# ---------------------------------------------------------------------------

def export_filename(branch):
    return branch.replace('/', '_') + '.txt'


def write_export_files(branches, modules, artifacts, versions, output_dir):
    for branch in branches:
        lines = []
        for module in modules:
            ver = versions.get(branch, {}).get(module)
            if not ver:
                continue
            for artifact in artifacts[module]:
                lines.append(f'{artifact}:{ver}')
        (output_dir / export_filename(branch)).write_text('\n'.join(lines), encoding='utf-8')


def generate_html(branches, modules, artifacts, versions, output_path):
    ts = datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')

    branch_headers = '\n'.join(
        f'        <th class="ver">{b}<br>'
        f'<a class="dl" href="{export_filename(b)}" download>&#8595; export</a></th>'
        for b in branches
    )

    rows = []
    for module in modules:
        module_artifacts = artifacts[module]
        rowspan = len(module_artifacts)
        for i, artifact in enumerate(module_artifacts):
            cells = []
            if i == 0:
                cells.append(
                    f'        <td class="mod" rowspan="{rowspan}">{module}</td>'
                )
            cells.append(f'        <td class="art">{artifact}</td>')
            for branch in branches:
                ver = versions.get(branch, {}).get(module)
                if ver:
                    cells.append(f'        <td class="ver"><span>{ver}</span></td>')
                else:
                    cells.append('        <td class="miss">—</td>')
            row_class = ' class="ms"' if i == 0 else ''
            rows.append(
                f'      <tr{row_class}>\n' + '\n'.join(cells) + '\n      </tr>'
            )

    rows_html = '\n'.join(rows)
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Qubership Core Java Libs — LTS Versions</title>
  <style>
    * {{ box-sizing: border-box; margin: 0; padding: 0; }}
    body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
           font-size: 14px; background: #f6f8fa; color: #24292f; }}
    header {{ background: #24292f; color: #fff; padding: 16px 24px;
             display: flex; align-items: center; justify-content: space-between; }}
    header h1 {{ font-size: 18px; font-weight: 600; }}
    header span {{ font-size: 12px; opacity: 0.6; }}
    .container {{ padding: 24px; overflow-x: auto; }}
    table {{ border-collapse: collapse; width: 100%; background: #fff;
            border: 1px solid #d0d7de; border-radius: 6px; overflow: hidden; }}
    th {{ background: #f6f8fa; border-bottom: 2px solid #d0d7de; padding: 10px 16px;
         text-align: left; font-weight: 600; white-space: nowrap;
         position: sticky; top: 0; z-index: 1; }}
    th.ver {{ text-align: center; }}
    td {{ padding: 7px 16px; border-bottom: 1px solid #eaeef2; }}
    tr.ms td {{ border-top: 2px solid #d0d7de; }}
    td.mod {{ font-weight: 600; border-right: 1px solid #d0d7de;
              vertical-align: top; white-space: nowrap; }}
    td.art {{ font-family: 'SFMono-Regular', Consolas, monospace; font-size: 13px; }}
    td.ver {{ text-align: center; font-family: 'SFMono-Regular', Consolas, monospace;
              font-size: 13px; }}
    td.ver span {{ background: #ddf4ff; color: #0969da; border-radius: 12px;
                   padding: 2px 10px; display: inline-block; }}
    td.miss {{ text-align: center; color: #8c959f; }}
    a.dl {{ display: inline-block; margin-top: 4px; font-size: 11px; padding: 2px 8px;
            background: #0969da; color: #fff; border-radius: 4px; text-decoration: none; }}
    a.dl:hover {{ background: #0550ae; }}
  </style>
</head>
<body>
<header>
  <h1>Qubership Core Java Libs — LTS Versions</h1>
  <span>Generated: {ts}</span>
</header>
<div class="container">
  <table>
    <thead>
      <tr>
        <th>Module</th>
        <th>Artifact</th>
{branch_headers}
      </tr>
    </thead>
    <tbody>
{rows_html}
    </tbody>
  </table>
</div>
</body>
</html>"""

    output_path.write_text(html, encoding='utf-8')


# ---------------------------------------------------------------------------
# Text output
# ---------------------------------------------------------------------------

def print_table(rows):
    w = 40
    print(f"{'MODULE':<{w}}  {'ARTIFACT':<{w}}  VERSION")
    print(f"{'-'*w}  {'-'*w}  -------")
    for module, artifact, version in rows:
        print(f"{module:<{w}}  {artifact:<{w}}  {version}")


def print_properties(rows):
    for _, artifact, version in rows:
        print(f"{artifact.replace('-', '.')}.version={version}")


def print_csv(rows):
    print("module,artifact,version")
    for row in rows:
        print(','.join(row))


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description='LTS versions for qubership-core-java-libs.',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        'output', nargs='?', default='versions-report.html',
        help='output HTML file (default: versions-report.html)',
    )
    parser.add_argument('--branch', help='branch for single-branch text output')
    parser.add_argument(
        '--format', choices=['table', 'properties', 'csv'], default='table',
        help='text format when --branch is used (default: table)',
    )
    parser.add_argument(
        '--repo', default=None,
        help='path to the monorepo root (default: two directories above this script)',
    )
    args = parser.parse_args()

    if args.repo:
        global REPO_ROOT
        REPO_ROOT = Path(args.repo).resolve()

    if args.branch:
        data = collect_branch(args.branch)
        if not data:
            print(f'No modules found on branch {args.branch!r}', file=sys.stderr)
            sys.exit(1)
        rows = [
            (module, artifact, data[module]['version'])
            for module in sorted(data)
            for artifact in sorted(data[module]['artifacts'])
            if data[module]['version']
        ]
        {'table': print_table, 'properties': print_properties, 'csv': print_csv}[args.format](rows)

    else:
        if args.format != 'table':
            print('Warning: --format is ignored without --branch', file=sys.stderr)
        branches = get_branches()
        if not branches:
            print('No lts/* branches found.', file=sys.stderr)
            sys.exit(1)
        print(f'Collecting versions from branches: {", ".join(branches)}')

        all_artifacts = {}
        versions = {}
        for branch in branches:
            data = collect_branch(branch)
            versions[branch] = {m: d['version'] for m, d in data.items()}
            for module, d in data.items():
                if module not in all_artifacts:
                    all_artifacts[module] = set()
                all_artifacts[module].update(d['artifacts'])

        modules   = sorted(all_artifacts)
        artifacts = {m: sorted(all_artifacts[m]) for m in modules}
        output    = Path(args.output)
        write_export_files(branches, modules, artifacts, versions, output.parent)
        generate_html(branches, modules, artifacts, versions, output)
        print(f'Report written to: {output}')


if __name__ == '__main__':
    main()
