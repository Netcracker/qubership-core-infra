#!/usr/bin/env python3
"""Reference consumers of a library change. Go and Maven share this file.

    consumers.py plan go
    consumers.py plan maven
    consumers.py check go <consumer_dir> <lib_base_dir> <lib_head_dir> <out_dir> <name> [tests]
    consumers.py check maven <revision> <consumer_dir> <maven_repo|skip> <out_dir> <name>

plan go reads CONFIG, MODULES, REPO_DIR and BASE_REF. It prints a JSON array of
{repo, module, tests, lib}. An empty array explains itself on stderr as plan-reason.
plan maven reads CONFIG and writes the Maven services to GITHUB_OUTPUT.

check writes findings.v1. A failing build is a finding and exits 0. Exit 2 means the
check could not run; the head file says why. Go restores the consumer module afterwards.
Maven reads SETTINGS (settings.xml). MAVEN_USERNAME and MAVEN_TOKEN belong to Maven.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from findings import error_lines, finding, new_vet, split_checks, write_findings

US = "\x1f"


def main():
    args = sys.argv[1:]
    if len(args) < 2 or args[0] not in ("plan", "check") or args[1] not in ("go", "maven"):
        sys.exit(__doc__.strip())
    command, ecosystem = args[0], args[1]
    if command == "plan" and ecosystem == "go":
        plan_go()
    elif command == "plan" and ecosystem == "maven":
        plan_maven()
    elif command == "check" and ecosystem == "go":
        sys.exit(check_go(args[2:]))
    else:
        sys.exit(check_maven(args[2:]))


def github_output(name, value):
    path = os.environ.get("GITHUB_OUTPUT")
    line = f"{name}={value}\n"
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(line)
    else:
        sys.stdout.write(line)


# ── plan ─────────────────────────────────────────────────────────────────────

def plan_maven():
    config = json.loads(os.environ.get("CONFIG") or "{}")
    consumers = config.get("consumers", None)
    if consumers is False or not isinstance(consumers, dict):
        services = []
    else:
        services = (consumers.get("maven") or {}).get("services") or []
        if not isinstance(services, list):
            services = []
    github_output("consumers", json.dumps(services, separators=(",", ":")))


def plan_go():
    try:
        config = json.loads(os.environ.get("CONFIG") or "")
    except json.JSONDecodeError:
        print("plan: CONFIG is not JSON", file=sys.stderr)
        sys.exit(2)
    if config.get("consumers") is False:
        print("[]")
        return
    try:
        modules = json.loads(os.environ.get("MODULES") or "")
    except json.JSONDecodeError:
        modules = None
    if not isinstance(modules, list):
        print("plan: MODULES is not a JSON array", file=sys.stderr)
        sys.exit(2)
    repo = Path(os.environ.get("REPO_DIR") or ".").resolve()
    if not repo.is_dir():
        sys.exit(2)
    base_ref = os.environ.get("BASE_REF") or ""
    work = Path(tempfile.mkdtemp())
    base_tree = None
    try:
        if base_ref:
            base_tree = work / "base"
            if subprocess.run(
                ["git", "-C", str(repo), "worktree", "add", "-q", "--detach", str(base_tree), base_ref],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            ).returncode != 0:
                print(f"::warning::plan: cannot check out {base_ref}, planning every consumer", file=sys.stderr)
                base_tree = None
        services = config.get("consumers") or {}
        services = services.get("go") or {} if isinstance(services, dict) else {}
        listed = (services.get("services") or []) + (services.get("libs") or [])
        chosen = []
        considered = False
        unchanged = False
        for lib in modules:
            changed, module = list_changed(repo, base_ref, base_tree, lib, work)
            if not changed:
                unchanged = True
                continue
            considered = True
            if not module:
                module, err = go_list(repo / lib, ["list", "-m"])
                if err:
                    continue
                module = module.strip()
            for consumer in listed:
                if not isinstance(consumer, dict) or not consumer.get("repo"):
                    continue
                if not sum_names_module(consumer, module):
                    continue
                chosen.append({**consumer, "lib": lib})
        if not chosen:
            if considered:
                print("plan-reason: no reference consumer depends on this module", file=sys.stderr)
            elif unchanged:
                print("plan-reason: non-test build list unchanged", file=sys.stderr)
        text = json.dumps(chosen, separators=(",", ":"))
        print(text)
        github_output_line("consumers", text)
        reason = ""
        if not chosen and considered:
            reason = "no reference consumer depends on this module"
        elif not chosen and unchanged:
            reason = "non-test build list unchanged"
        if reason:
            github_output_line("skip-reason", reason)
    finally:
        if base_tree and base_tree.exists():
            subprocess.run(
                ["git", "-C", str(repo), "worktree", "remove", "-f", str(base_tree)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
        shutil.rmtree(work, ignore_errors=True)


def github_output_line(name, value):
    """Write a step output without also printing it. plan go prints the array itself."""
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(f"{name}={value}\n")


def go_list(directory, args):
    env = os.environ.copy()
    env["GOTOOLCHAIN"] = "local"
    env["GOFLAGS"] = "-mod=readonly"
    proc = subprocess.run(
        ["go", *args], cwd=directory, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace",
    )
    if proc.returncode != 0:
        return "", proc.stderr
    return proc.stdout.replace("\r", ""), ""


def list_changed(repo, base_ref, base_tree, lib, work):
    """True when this module's consumers should be considered. The module path may be empty."""
    if not base_ref or base_tree is None:
        return True, ""
    if not (base_tree / lib / "go.mod").is_file():
        print(f"plan: {lib}: no module on {base_ref}", file=sys.stderr)
        return True, ""
    head = build_list(repo / lib, work / "head.list")
    if head is None:
        print(f"::warning::plan: cannot read the non-test build list of {lib}, checking its consumers", file=sys.stderr)
        return True, ""
    if build_list(base_tree / lib, work / "base.list") is None:
        print(f"::warning::plan: cannot read the non-test build list of {lib} at {base_ref}, checking its consumers", file=sys.stderr)
        return True, head[0]
    _, head_lines = head
    base_lines = (work / "base.list").read_text(encoding="utf-8").splitlines()
    if head_lines == base_lines:
        print(f"plan: {lib}: non-test build list unchanged", file=sys.stderr)
        return False, ""
    print(f"plan: {lib}: non-test build list changed", file=sys.stderr)
    return True, head[0]


def build_list(directory, out):
    """(module, sorted lines) or None. Lines are the go/toolchain directives and the non-test dependencies."""
    mod, err = go_list(directory, ["list", "-m"])
    if err:
        return None
    mod = mod.strip()
    raw, err = go_list(directory, ["list", "-e", "-json", "./..."])
    if err:
        return None
    packages = decode_json_stream(raw)
    if any(pkg.get("Error") is not None for pkg in packages):
        print(f"plan: {directory}: a package does not list", file=sys.stderr)
        return None
    visible = visible_packages(packages, mod)
    deps = ""
    if visible:
        deps, err = go_list(directory, [
            "list", "-deps",
            "-f", "{{if .Module}}{{.Module.Path}} {{.Module.Version}}{{with .Module.Replace}} => {{.Path}}{{if .Version}} {{.Version}}{{end}}{{end}}{{end}}",
            *visible,
        ])
        if err:
            return None
    lines = []
    gomod = (directory / "go.mod").read_text(encoding="utf-8", errors="replace").replace("\r", "")
    for line in gomod.splitlines():
        if re.match(r"^(go|toolchain)\s", line):
            lines.append(re.sub(r"\s+", " ", line).rstrip())
    for line in deps.splitlines():
        line = re.sub(r"\s+", " ", line).strip()
        if line and line.split(" ", 1)[0] != mod:
            lines.append(line)
    lines = sorted(set(lines))
    out.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    return mod, lines


def decode_json_stream(text):
    decoder = json.JSONDecoder()
    index = 0
    values = []
    while index < len(text):
        while index < len(text) and text[index].isspace():
            index += 1
        if index >= len(text):
            break
        value, index = decoder.raw_decode(text, index)
        values.append(value)
    return values


def visible_packages(packages, mod):
    """Packages reachable from non-test code. A test-only import, and everything behind it, is outside."""
    pkgs = []
    seen = set()
    for pkg in packages:
        if (pkg.get("Module") or {}).get("Path") != mod:
            continue
        path = pkg.get("ImportPath")
        if not path or path in seen:
            continue
        seen.add(path)

        def inside(name):
            return name == mod or name.startswith(mod + "/")

        pkgs.append({
            "path": path,
            "imports": [name for name in pkg.get("Imports") or [] if inside(name)],
            "tests": [name for name in (pkg.get("TestImports") or []) + (pkg.get("XTestImports") or []) if inside(name)],
        })
    visible = [pkg["path"] for pkg in pkgs]

    def step(current):
        current_set = set(current)
        reached = set()
        for pkg in pkgs:
            if pkg["path"] in current_set:
                reached.update(pkg["imports"])
        kept = []
        for pkg in pkgs:
            imported = any(pkg["path"] in other["imports"] or pkg["path"] in other["tests"] for other in pkgs)
            if not imported or pkg["path"] in reached:
                kept.append(pkg["path"])
        return kept

    while True:
        nxt = step(visible)
        if sorted(nxt) == sorted(visible):
            return nxt
        visible = nxt


def sum_names_module(consumer, module):
    repo = consumer["repo"]
    module_dir = consumer.get("module") or "."
    rel = f"{module_dir}/go.sum"
    if rel.startswith("./"):
        rel = rel[2:]
    url = f"https://raw.githubusercontent.com/{repo}/HEAD/{rel}"
    ok, text = fetch(url)
    if not ok:
        label = rel[:-len("/go.sum")] if rel.endswith("/go.sum") else rel
        print(f"::warning::cannot read go.sum of {repo}/{label}, checking it anyway", file=sys.stderr)
        return True
    return any(line.split(" ", 1)[0] == module for line in text.splitlines() if line.strip())


def fetch(url):
    # CURL, when set, is the program the shell used. It may be a shell script.
    # Linux executes it directly. Windows bash is WSL: it reads the script at /mnt/<drive>
    # and sees PLAN_CURL only when that name is listed in WSLENV.
    curl = os.environ.get("CURL") or "curl"
    env = None
    if os.name == "nt" and os.environ.get("CURL"):
        path = curl.replace("\\", "/")
        if len(path) >= 2 and path[1] == ":":
            path = "/mnt/" + path[0].lower() + path[2:]
        command = ["bash", path, "-fsSL", "--retry", "3", url]
        env = os.environ.copy()
        current = env.get("WSLENV") or ""
        env["WSLENV"] = f"{current}:PLAN_CURL" if current else "PLAN_CURL"
    else:
        command = [curl, "-fsSL", "--retry", "3", url]
    proc = subprocess.run(
        command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", errors="replace",
    )
    return proc.returncode == 0, proc.stdout.replace("\r", "")


# ── check ────────────────────────────────────────────────────────────────────

def check_maven(args):
    if len(args) != 5:
        print("check maven: want revision, consumer dir, maven repo, out dir, name", file=sys.stderr)
        return 2
    revision, consumer, repo, out, name = args
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    file = out / f"maven-consumers-{name.replace('/', '_')}-{revision}.json"
    tool = "maven-consumer"
    settings = os.environ.get("SETTINGS") or ""
    if not settings or not Path(settings).is_file():
        print("maven-consumer: SETTINGS is not a settings.xml", file=sys.stderr)
        return 2
    if revision != "head":
        print("maven-consumer: revision must be head", file=sys.stderr)
        return 2
    if repo in ("", "skip"):
        print(f"maven-consumer: head: skipped, {name} does not reference the installed library", file=sys.stderr)
        write_findings(file, "maven-consumers", "maven", "head", "skipped", f"{name} does not use this library build", [], [], tool)
        return 0
    if not Path(repo).is_dir():
        print(f"maven-consumer: maven repository not found: {repo}", file=sys.stderr)
        return 2
    print(f"maven-consumer: {revision}: {name}", file=sys.stderr)
    print(f"maven-consumer: {revision}: mvn verify -DskipITs", file=sys.stderr)
    proc = subprocess.run(
        ["mvn", "-B", "-ntp", "-s", settings, f"-Dmaven.repo.local={repo}", "-f", str(Path(consumer) / "pom.xml"), "verify", "-DskipITs"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
    )
    if proc.returncode == 0:
        print(f"maven-consumer: {revision}: built", file=sys.stderr)
        write_findings(file, "maven-consumers", "maven", "head", "ok", "", split_checks("consumer-build consumer-test"), [], tool)
        return 0
    log = proc.stdout
    print(f"::group::{revision} maven log", file=sys.stderr)
    print("\n".join(log.splitlines()[-40:]), file=sys.stderr)
    print("::endgroup::", file=sys.stderr)
    if re.search(r"There are test failures|maven-surefire-plugin", log):
        print(f"maven-consumer: {revision}: tests failed", file=sys.stderr)
        detail = first_match(log, r"Tests run:|There are test failures")
        item = finding("consumer-test", f"tests fail: {detail}", package=name)
    else:
        print(f"maven-consumer: {revision}: build failed", file=sys.stderr)
        detail = first_match(log, r"ERROR|FAILURE")
        item = finding("consumer-build", f"build fails: {detail}", package=name)
    write_findings(file, "maven-consumers", "maven", "head", "ok", "", split_checks("consumer-build consumer-test"), [item], tool)
    return 0


def first_match(text, pattern):
    found = re.search(pattern, text)
    if not found:
        return ""
    line = text[found.start():].splitlines()[0]
    return line[:300]


def check_go(args):
    if len(args) not in (5, 6):
        print("check go: want consumer dir, lib base, lib head, out dir, name, [tests]", file=sys.stderr)
        return 2
    consumer, lib_base, lib_head, out, name = args[:5]
    tests = args[5] if len(args) == 6 else "false"
    if name.endswith("/."):
        name = name[:-2]
    consumer = Path(consumer).resolve()
    lib_base = Path(lib_base).resolve()
    lib_head = Path(lib_head).resolve()
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    out = out.resolve()
    stem = out / f"go-consumers-{name.replace('/', '_')}"
    version = subprocess.run(["go", "env", "GOVERSION"], stdout=subprocess.PIPE, text=True, encoding="utf-8").stdout.strip()
    tool = f"go-dep-check consumer-check ({version})"
    state = {"written": False, "reason": ""}

    def die(reason):
        state["reason"] = reason
        raise SystemExit(2)

    def skip(why):
        write_findings(f"{stem}-head.json", "go-consumers", "go", "head", "skipped", f"{name}: {why}", [], [], tool)
        state["written"] = True
        raise SystemExit(0)

    code = 0
    try:
        listed = subprocess.run(
            ["go", "list", "-m"], cwd=lib_head,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8",
        )
        if not (lib_head / "go.mod").is_file() or listed.returncode != 0:
            die(f"no library module in {args[2]}")
        module = listed.stdout.strip()
        if tests == "true" and shutil.which("gotestsum") is None:
            die("gotestsum is not installed")
        if not sum_file_has(consumer / "go.sum", module):
            skip(f"not a consumer of {module}")
        deps = subprocess.run(
            ["go", "list", "-deps", "-test", "-f", "{{with .Module}}{{.Path}}{{end}}", "./..."],
            cwd=consumer, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace",
        )
        if deps.returncode != 0:
            lines = [line[:300] for line in deps.stderr.splitlines() if not line.startswith("go: downloading")][:3]
            die("go list failed: " + "; ".join(lines))
        if module not in deps.stdout.splitlines():
            skip(f"not a consumer of {module} (only in go.sum)")
        direct = subprocess.run(
            ["go", "list", "-m", "-f", "{{if not .Indirect}}{{.Path}}{{end}}", module],
            cwd=consumer, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8",
        )
        kind = "direct" if direct.stdout.strip() else "transitive"
        tmp = Path(tempfile.mkdtemp())
        try:
            base, head = check_revision(consumer, module, lib_base), check_revision(consumer, module, lib_head)
            head_tests, base_tests = [], []
            ran_head = ran_base = False
            if tests == "true" and head["rc"] == "0" and not head["test"]:
                head_events = run_tests(consumer, tmp / "h", "./...", [])
                if head_events is None:
                    die("gotestsum wrote no test results, see the job log")
                ran_head = True
                head_tests = test_findings(head_events)
                failed = [(pkg, test) for check, pkg, test, _ in head_tests if check == "consumer-test"]
                if failed and base["rc"] == "0" and use_library(consumer, module, lib_base):
                    names = sorted({test for _, test in failed})
                    packages = " ".join(sorted({pkg for pkg, _ in failed}))
                    extra = [] if "" in names else ["-run", f"^({'|'.join(names)})$"]
                    base_events = run_tests(consumer, tmp / "b", packages, extra)
                    if base_events is not None:
                        ran_base = True
                        base_tests = test_findings(base_events)
            note = mvs_note(kind, base["mvs"], head["mvs"])
            head_items = report(head, head_tests, "this change", name, note)
            base_items = report(base, base_tests, "the library at base", name, note)
            vet = new_vet(base["vet"], head["vet"])
            if vet:
                head_items.append(finding(
                    "consumer-build",
                    "new go vet findings with this change: " + excerpt_build("\n".join(vet)),
                    package=name, symbol="vet", note=note,
                ))
            head_checks = "consumer-build" + (" consumer-test consumer-test-flaky" if ran_head else "")
            base_checks = "consumer-build" + (" consumer-test consumer-test-flaky" if ran_base else "")
            write_findings(f"{stem}-head.json", "go-consumers", "go", "head", "ok", "", split_checks(head_checks), head_items, tool)
            write_findings(f"{stem}-base.json", "go-consumers", "go", "base", "ok", "", split_checks(base_checks), base_items, tool)
            state["written"] = True
            print(f"{name}: {kind} consumer, {len(head_items)} finding(s) on head, {len(base_items)} on base", file=sys.stderr)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    except SystemExit as exc:
        code = int(exc.code)
    finally:
        restore_consumer(consumer)
    if not state["written"]:
        write_findings(
            f"{stem}-head.json", "go-consumers", "go", "head", "error",
            f"{name}: {state['reason'] or 'consumer check exited'}", [], [], tool,
        )
        return 2
    return code


def sum_file_has(path, module):
    if not path.is_file():
        return False
    return any(line.split(" ", 1)[0] == module for line in path.read_text(encoding="utf-8", errors="replace").splitlines() if line.strip())


def restore_consumer(consumer):
    subprocess.run(["git", "-C", str(consumer), "checkout", "-q", "--", "."], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["git", "-C", str(consumer), "clean", "-fdq", "--", "."], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def use_library(consumer, module, lib):
    restore_consumer(consumer)
    edit = subprocess.run(
        ["go", "mod", "edit", "-replace", f"{module}={lib.as_posix()}"],
        cwd=consumer, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
    )
    if edit.returncode != 0:
        return False
    tidy = subprocess.run(
        ["go", "mod", "tidy"],
        cwd=consumer, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
    )
    return tidy.returncode == 0


def check_revision(consumer, module, lib):
    restore_consumer(consumer)
    edit = subprocess.run(
        ["go", "mod", "edit", "-replace", f"{module}={lib.as_posix()}"],
        cwd=consumer, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
    )
    tidy = subprocess.run(
        ["go", "mod", "tidy"],
        cwd=consumer, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
    )
    result = {"rc": "tidy", "build": (edit.stdout or "") + (tidy.stdout or ""), "vet": "", "test": "", "mvs": []}
    if edit.returncode != 0 or tidy.returncode != 0:
        return result
    diff = subprocess.run(
        ["git", "diff", "-U0", "go.mod"],
        cwd=consumer, stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace",
    )
    mvs = []
    for line in diff.stdout.splitlines():
        if re.match(r"^[+-]\s*[a-z]", line) and module not in line:
            mvs.append(line)
    built = subprocess.run(
        ["go", "build", "-o", os.devnull, "./..."],
        cwd=consumer, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
    )
    vetted = subprocess.run(
        ["go", "vet", "./..."],
        cwd=consumer, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
    )
    tested = subprocess.run(
        ["go", "test", "-count=1", "-run", "^$", "./..."],
        cwd=consumer, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
    )
    test_lines = [
        line for line in tested.stdout.splitlines()
        if not re.match(r"^(ok|\?|go: downloading)\s", line) and line != "FAIL"
    ]
    result.update(rc=str(built.returncode), build=built.stdout, vet=vetted.stdout, test="\n".join(test_lines), mvs=mvs)
    return result


def run_tests(consumer, prefix, packages, extra):
    json_path = Path(str(prefix) + ".json")
    subprocess.run(
        ["gotestsum", "--jsonfile", str(json_path), "--rerun-fails=2", "--packages", packages, "--", "-count=1", "-timeout", "15m", *extra],
        cwd=consumer,
    )
    if not json_path.is_file() or json_path.stat().st_size == 0:
        return None
    events = []
    for line in json_path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line:
            events.append(json.loads(line))
    return events


def test_findings(events):
    by_package = {}
    for event in events:
        package = event.get("Package")
        if package:
            by_package.setdefault(package, []).append(event)
    lines = []
    for package in sorted(by_package):
        group = by_package[package]
        attempts = {}
        for event in group:
            test = event.get("Test") or ""
            if not test or "/" in test or event.get("Action") not in ("pass", "fail"):
                continue
            attempts.setdefault(test, []).append(event)
        failed = sorted(test for test, evs in attempts.items() if any(ev.get("Action") == "fail" for ev in evs))
        if failed:
            for test in failed:
                last = attempts[test][-1].get("Action")
                check = "consumer-test" if last == "fail" else "consumer-test-flaky"
                related = [
                    event for event in group
                    if (event.get("Test") or "") == test or (event.get("Test") or "").startswith(test + "/")
                ]
                lines.append((check, package, test, excerpt_test(related)))
        elif any(event.get("Action") == "fail" for event in group):
            lines.append(("consumer-test", package, "", excerpt_test([event for event in group if not event.get("Test")])))
    return lines


def excerpt_test(events):
    kept = []
    drop = re.compile(r"^\s*(=== |--- )|^\s*\S+\.go:[0-9]+:\s*$")
    keep = re.compile(r"\.go:[0-9]+|panic|[Ee]rror|timed out|expected|actual")
    for event in events:
        text = event.get("Output") or ""
        if not text or drop.search(text):
            continue
        if not keep.search(text):
            continue
        text = re.sub(r"/[^\s:]+/", "", text)
        text = re.sub(r"\s+", " ", text).strip()[:300]
        if text and text not in kept:
            kept.append(text)
        if len(kept) == 4:
            break
    return "; ".join(kept)


def excerpt_build(text):
    return "; ".join(re.sub(r"^[^\s]*/", "", line) for line in error_lines(text))


def mvs_note(kind, base_lines, head_lines):
    added = sorted(set(head_lines) - set(base_lines))
    added = [" ".join(line.split()) for line in added][:8]
    note = f"{kind} consumer"
    if added:
        note += "; MVS also changes: " + "; ".join(added)
    return note


def report(revision, tests, what, name, note):
    items = []
    if revision["rc"] != "0":
        items.append(finding(
            "consumer-build", f"does not build with {what}: {excerpt_build(revision['build'])}",
            package=name, symbol="build", note=note,
        ))
    if revision["test"]:
        items.append(finding(
            "consumer-build",
            f"tests do not compile or test binaries fail to start with {what}: {excerpt_build(revision['test'])}",
            package=name, symbol="tests", note=note,
        ))
    for check, package, test, text in tests:
        label = test or "the package"
        if check == "consumer-test":
            message = f"{label} fails with {what}"
        else:
            message = f"{label} failed and passed on a rerun with {what}"
        if text:
            message += f": {text}"
        symbol = f"{package}.{test}" if test else package
        items.append(finding(check, message, package=name, symbol=symbol, note=note))
    return items


if __name__ == "__main__":
    main()
