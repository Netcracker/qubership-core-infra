#!/usr/bin/env python3
"""Steps of the dependency-gate workflow that are not a single command.

    ci.py maven-plan       CONFIG              -> GITHUB_OUTPUT consumers
    ci.py maven-install    SETTINGS, RAW_SUFFIX, repo/  -> $RUNNER_TEMP/m2-head.tgz
    ci.py adapter-error    OUT, FILE, REASON, ADAPTER, ECOSYSTEM
    ci.py detect           REPO_DIR, CONSUMERS
    ci.py evaluate         REPO_DIR, FINDINGS_DIR, EXPECT, OUT_DIR, COMMENT, COMMENT_NOTE, GATE_REF, DRY_RUN

detect and evaluate run the gate binary built beside them. adapter-error writes its
findings file through findings.py.
"""
import os
import re
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LISTS = ROOT / ".github" / "repos.json"
POM_SNAPSHOT = re.compile(r"([0-9]+\.[0-9]+\.[0-9]+)-SNAPSHOT")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__.strip())
    command = sys.argv[1]
    commands = {
        "maven-plan": maven_plan,
        "maven-install": maven_install,
        "adapter-error": adapter_error,
        "detect": detect,
        "evaluate": evaluate,
    }
    if command not in commands:
        sys.exit(f"unknown command {command}")
    commands[command]()


def maven_plan():
    import consumers
    consumers.plan_maven()


def maven_install():
    suffix = branch_suffix(os.environ.get("RAW_SUFFIX", ""))
    repo = Path(os.environ.get("REPO", "repo"))
    changed = 0
    for pom in repo.rglob("pom.xml"):
        text = pom.read_text(encoding="utf-8")
        rewritten, count = POM_SNAPSHOT.subn(rf"\1-{suffix}-SNAPSHOT", text)
        if count:
            pom.write_text(rewritten, encoding="utf-8")
            changed += count
    if changed == 0:
        fail(f"no X.Y.Z-SNAPSHOT version to isolate on this branch")
    dest = Path(os.environ["RUNNER_TEMP"]) / "m2-head"
    archive = Path(str(dest) + ".tgz")
    if dest.exists():
        shutil.rmtree(dest)
    if archive.exists():
        archive.unlink()
    dest.mkdir(parents=True)
    subprocess.run(
        [
            "mvn", "-B", "-ntp",
            "-s", os.environ["SETTINGS"],
            f"-Dmaven.repo.local={dest}",
            "-f", str(repo / "pom.xml"),
            "-DskipTests", "install",
        ],
        check=True,
    )
    pack_installed(dest, archive)


def branch_suffix(raw):
    """Same shape as the old shell: non-alphanumerics become '-', one leading and one trailing '-' drop, 50 characters."""
    cleaned = re.sub(r"[^a-zA-Z0-9]", "-", raw)
    cleaned = re.sub(r"-+", "-", cleaned)
    if cleaned.startswith("-"):
        cleaned = cleaned[1:]
    cleaned = cleaned[:50]
    if cleaned.endswith("-"):
        cleaned = cleaned[:-1]
    if not cleaned:
        fail(f"head branch {raw!r} holds no alphanumeric characters")
    return cleaned


def pack_installed(dest, archive):
    dirs = sorted({p.parent.relative_to(dest).as_posix() for p in dest.rglob("maven-metadata-local.xml")})
    if not dirs:
        fail("install produced no maven-metadata-local.xml")
    with tarfile.open(archive, "w:gz") as tar:
        for rel in dirs:
            tar.add(dest / rel, arcname=rel)


def adapter_error():
    import findings
    out = Path(os.environ["OUT"])
    if out.is_dir() and any(out.glob("*-head.json")):
        return
    out.mkdir(parents=True, exist_ok=True)
    findings.write_findings(
        out / os.environ["FILE"],
        os.environ["ADAPTER"],
        os.environ["ECOSYSTEM"],
        "head",
        "error",
        os.environ["REASON"],
        [],
        [],
        "",
    )


def detect():
    args = [
        "detect",
        "-repo", os.environ.get("REPO_DIR", "."),
        "-consumer-lists", str(LISTS),
    ]
    consumers = os.environ.get("CONSUMERS", "")
    if consumers:
        args += ["-consumers", consumers]
    run_gate(args)


def evaluate():
    out = os.environ.get("OUT_DIR") or str(Path(os.environ["RUNNER_TEMP"]) / "dependency-gate")
    args = ["evaluate", "-repo", os.environ.get("REPO_DIR", "."), "-out", out, "-consumer-lists", str(LISTS)]
    ref = os.environ.get("GATE_REF", "")
    if ref:
        args += ["-gate", f"qubership-core-infra@{ref}"]
    findings = os.environ.get("FINDINGS_DIR", "")
    if findings and Path(findings).is_dir():
        args += ["-findings", findings]
    for line in os.environ.get("EXPECT", "").splitlines():
        line = line.strip()
        if line:
            args += ["-expect", line]
    if os.environ.get("COMMENT") == "true":
        args.append("-comment")
    if os.environ.get("COMMENT_NOTE") == "true":
        args.append("-comment-note")
    if os.environ.get("DRY_RUN") == "true":
        args.append("-dry-run")
    run_gate(args)


def run_gate(args):
    binary = Path(os.environ["RUNNER_TEMP"]) / "dependency-gate-bin" / "dependency-gate"
    os.execv(str(binary), [str(binary), *args])


def fail(message):
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
