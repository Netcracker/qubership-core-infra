#!/usr/bin/env python3
"""Run the mandatory linter against a Git checkout or directory."""

import argparse
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

RULES = Path("/etc/platform-ci-toolkit/base-liners")
SUPER_LINTER = "/action/lib/linter.sh"
REQUIRED_VALIDATORS = Path("/etc/platform-ci-toolkit/required-validators")
EXCLUDED_DIRS = {".go", ".venv", "node_modules", "vendor", "target", "dist"}


def tracked_files(workspace):
    data = subprocess.check_output(["git", "ls-tree", "-rz", "--name-only", "HEAD"], cwd=workspace)
    paths = {Path(os.fsdecode(name)) for name in data.split(b"\0") if name}
    return {
        path for path in paths
        if not EXCLUDED_DIRS.intersection(path.parts[:-1])
        and (workspace / path).is_file()
        and not (workspace / path).is_symlink()
    }


def chart_roots(files):
    charts = {path.parent for path in files if path.name == "Chart.yaml"}
    # Do not discover vendored dependencies as standalone projects.
    return sorted(
        chart for chart in charts
        if not any(chart.is_relative_to(parent / "charts") for parent in chart.parents
                   if parent in charts)
    )


def lint_environment(workspace, scope, charts):
    env = {
        name: value for name, value in os.environ.items()
        if not name.startswith("FIX_") and name != "GITHUB_WORKSPACE"
    }
    for flag in REQUIRED_VALIDATORS.read_text().splitlines():
        if env.setdefault(flag, "true") != "true":
            raise ValueError(f"{flag} is required and must be true")

    excluded_dirs = "|".join(re.escape(name) for name in sorted(EXCLUDED_DIRS))
    excludes = [rf"(^|/)({excluded_dirs})/"]
    excludes.extend(
        rf"^{re.escape(chart.as_posix() + '/' if chart.parts else '')}(templates|charts)/"
        for chart in charts
    )
    env.update({
        "RUN_LOCAL": "true",
        "DEFAULT_WORKSPACE": str(workspace),
        # Super-Linter validates this ref even in full-scan mode.
        "DEFAULT_BRANCH": "HEAD",
        "VALIDATE_ALL_CODEBASE": "true",
        "USE_FIND_ALGORITHM": "false",
        "STRIP_DEFAULT_WORKSPACE_FOR_REGEX": "true",
        "FILTER_REGEX_INCLUDE": rf"^{re.escape(scope.as_posix())}/" if scope.parts else ".*",
        "FILTER_REGEX_EXCLUDE": "|".join(excludes),
        "DISABLE_ERRORS": "false",
        "LINTER_RULES_PATH": os.path.relpath(RULES, workspace),
        "GO_CONFIG_FILE": ".golangci.yml",
        "JAVA_FILE_NAME": ".checkstyle.xml",
        "PYTHON_RUFF_CONFIG_FILE": ".ruff.toml",
        "DOCKERFILE_HADOLINT_FILE_NAME": ".hadolint.yaml",
        "YAML_CONFIG_FILE": ".yaml-lint.yml",
    })
    return env

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--path", type=Path, required=True)
    args = parser.parse_args()
    directory = args.path.resolve()
    if not directory.is_dir():
        parser.error(f"expected a directory: {directory}")

    try:
        workspace = Path(subprocess.check_output(
            ["git", "rev-parse", "--show-toplevel"], cwd=directory, text=True,
        ).strip()).resolve()
        files = tracked_files(workspace)
    except (OSError, subprocess.CalledProcessError) as error:
        parser.error(f"could not read Git checkout at {directory}: {error}")

    scope = directory.relative_to(workspace)
    charts = chart_roots(files)
    try:
        env = lint_environment(workspace, scope, charts)
    except ValueError as error:
        parser.error(str(error))
    jobs = [(Path("."), [SUPER_LINTER])]
    jobs.extend(
        (chart, ["helm", "lint", "--strict", "."])
        for chart in charts
        if chart.is_relative_to(scope) or scope.is_relative_to(chart)
    )
    failed = False
    with tempfile.TemporaryDirectory(prefix="mandatory-lint-") as scratch:
        env["TOOLKIT_LINTER_OUTPUT_DIR"] = str(Path(scratch) / "super-linter")
        for project, command in jobs:
            print(f"[mandatory] {command[0]} ({project})", flush=True)
            try:
                failed |= subprocess.run(command, cwd=workspace / project, env=env).returncode != 0
            except OSError as error:
                print(f"[mandatory] {error}", file=sys.stderr)
                failed = True
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
