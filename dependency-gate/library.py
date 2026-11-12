#!/usr/bin/env python3
"""Go library adapter: family-split and apidiff-exposed.

    library.py

Reads CONFIG, MODULES, REPO_DIR, BASE_REF, HEAD_REF and OUT_DIR.
APIDIFF overrides the apidiff binary; the default is $(go env GOPATH)/bin/apidiff.

Writes go-library-head.json and, when BASE_REF is set, go-library-base.json.
Exit 0 means the findings were written. Exit 2 means the adapter could not run;
the head file says why. An empty base checks only the head revision.
apidiff reports on head only. Levels, the baseline and the verdict belong to the gate.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from findings import excerpt, make_finding, write_findings

MAJOR = re.compile(r"/v[0-9]+$")
IMPORT_LINE = re.compile(r"^[^/]+\.[^/]+/")
OWN_SKIP = re.compile(r"/internal(/|$)|/mocks?(/|$)")
CHANGE_TOP = re.compile(r"^\(?\*?([A-Za-z0-9_]+)")
MINOR = re.compile(r"^(v[0-9]+\.[0-9]+)")


class AdapterFailure(Exception):
    pass


def log(message):
    print(f"go-dep-check: {message}", file=sys.stderr)


def run(cmd, cwd=None):
    try:
        return subprocess.run(
            cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8", errors="replace",
        )
    except FileNotFoundError:
        raise AdapterFailure(f"{cmd[0]} is not installed")


def go_env(name):
    proc = run(["go", "env", name])
    if proc.returncode != 0:
        raise AdapterFailure(f"go env {name} failed: {excerpt(proc.stderr)}")
    return proc.stdout.strip()


def go_text(directory, args):
    """Stdout of a go command. A non-zero exit still returns what go printed."""
    proc = run(["go", *args], cwd=directory)
    return proc.stdout.replace("\r", "")


def module_dir(root, module):
    path = Path(root)
    if module not in ("", "."):
        for part in module.split("/"):
            if part and part != ".":
                path = path / part
    return path


def module_prefix(module):
    if module in ("", "."):
        return ""
    return module.strip("/") + "/"


def clean_module(module):
    module = module.strip()
    if module.endswith("/"):
        module = module.rstrip("/")
    return module or "."


def load_config():
    if "CONFIG" not in os.environ:
        log("CONFIG is not set: module families are not checked")
        return {}
    raw = os.environ.get("CONFIG") or "{}"
    try:
        config = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise AdapterFailure(f"cannot read CONFIG: {exc}")
    if not isinstance(config, dict):
        raise AdapterFailure("cannot read CONFIG")
    return config


def load_modules():
    raw = os.environ.get("MODULES")
    if raw is None or raw.strip() == "":
        return ["."]
    try:
        modules = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise AdapterFailure(f"MODULES is not a JSON array: {exc}")
    if not isinstance(modules, list) or any(not isinstance(item, str) for item in modules):
        raise AdapterFailure("MODULES is not a JSON array")
    return [clean_module(item) for item in modules] or ["."]


def apidiff_path():
    override = os.environ.get("APIDIFF")
    if override:
        path = override
    else:
        gopath = go_env("GOPATH").split(os.pathsep)[0]
        path = str(Path(gopath) / "bin" / "apidiff")
    if shutil.which(path) is None:
        raise AdapterFailure(f"apidiff is not installed: {path}")
    return path


def go_families(config):
    compiled = []
    for fam in config.get("families") or []:
        if not isinstance(fam, dict) or fam.get("ecosystem") != "go":
            continue
        key = fam.get("key")
        if key is None:
            key = "version"
        try:
            pattern = re.compile(fam.get("match") or "")
        except re.error as exc:
            raise AdapterFailure(f"cannot read family {fam.get('name', '')}: {exc}")
        compiled.append({
            "name": fam.get("name") or "",
            "key": key,
            "note": fam.get("note") or "",
            "pattern": pattern,
        })
    return compiled


def parse_modules(text):
    mods = []
    lines = [line.replace("\r", "") for line in text.splitlines() if line.strip()]
    for line in sorted(lines):
        parts = line.split()
        mods.append((parts[0], parts[1] if len(parts) > 1 else ""))
    return mods


def module_versions(directory):
    proc = run(["go", "list", "-m", "-f", "{{.Path}} {{.Version}}", "all"], cwd=directory)
    if proc.returncode != 0:
        raise AdapterFailure("go list -m all fails: " + excerpt(proc.stderr))
    return parse_modules(proc.stdout)


def family_key(version, key):
    if key == "minor":
        match = MINOR.match(version)
        return match.group(1) if match else version
    if key == "stable":
        if version.startswith("v0."):
            return None
        return version
    return version


def family_findings(mods, families, prefix, unit):
    if not families:
        return [], False
    found = []
    for fam in families:
        matched = [(path, ver) for path, ver in mods if fam["pattern"].search(path)]
        if not matched:
            continue
        keys = []
        for _, ver in matched:
            value = family_key(ver, fam["key"])
            if value is None or value in keys:
                continue
            keys.append(value)
        if len(keys) > 1:
            listed = ", ".join(f"{path}@{ver}" for path, ver in matched)
            found.append(make_finding(
                "family-split",
                "versions diverge (Renovate does not update indirect dependencies by default): " + listed,
                unit,
                package=fam["name"],
                note=fam["note"],
                locs=[prefix + "go.mod"],
            ))
    return found, True


def strip_major(path):
    return MAJOR.sub("", path)


def version_pairs(old, new):
    """Same-module version changes, then a removed module paired with an added /vN module."""
    old_map = {path: ver for path, ver in old}
    new_map = {path: ver for path, ver in new}
    changed = [(path, ver, new_map[path]) for path, ver in old if path in new_map and new_map[path] != ver]
    removed = [(path, ver) for path, ver in old if path not in new_map]
    added = [(path, ver) for path, ver in new if path not in old_map]
    major = []
    for path, ver in removed + [(path, ver) for path, ver, _ in changed]:
        base = strip_major(path)
        for added_path, added_ver in added:
            if strip_major(added_path) == base:
                major.append((path, ver, added_ver, added_path))
    pairs = [(path, old_ver, new_ver, path) for path, old_ver, new_ver in changed]
    pairs.extend(major)
    return pairs


def imports(directory, own):
    text = go_text(directory, [
        "list", "-f",
        '{{join .Imports "\\n"}}\n{{join .TestImports "\\n"}}\n{{join .XTestImports "\\n"}}',
        "./...",
    ])
    found = set()
    for line in text.splitlines():
        if not IMPORT_LINE.match(line):
            continue
        if line == own or line.startswith(own + "/"):
            continue
        found.add(line)
    return sorted(found)


def packages_of(imported, module):
    pattern = re.compile("^" + re.escape(module) + r"(?:/|$)")
    return [pkg for pkg in imported if pattern.match(pkg)]


def modloc(directory, module, prefix):
    gomod = module_dir(directory, ".") / "go.mod"
    if module:
        pattern = re.compile(r"^\s*(?:require\s+)?" + re.escape(module) + r"\s+v")
    else:
        pattern = re.compile(r"^go\s")
    try:
        lines = gomod.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return prefix + "go.mod"
    for number, line in enumerate(lines, start=1):
        if pattern.search(line):
            return f"{prefix}go.mod:{number}"
    return prefix + "go.mod"


def clip_err(text):
    return text[:300].replace("\n", " ").replace("\r", " ")


def apidiff_module(ad, old_dir, new_dir, old_imports, new_imports, pair, work, unit, prefix, findings):
    mo, vo, vn, mn = pair
    new_set = set(new_imports)
    records = []
    for opkg in packages_of(old_imports, mo):
        npkg = mn + opkg[len(mo):]
        if npkg not in new_set:
            continue
        owner = go_text(old_dir, ["list", "-f", "{{.Module.Path}}", opkg]).strip()
        if owner and owner != mo:
            continue
        slot = Path(tempfile.mkdtemp(dir=work))
        old_err = run([ad, "-w", str(slot / "old"), opkg], cwd=old_dir)
        err = old_err.stderr
        if old_err.returncode == 0:
            new_err = run([ad, "-w", str(slot / "new"), npkg], cwd=new_dir)
            err += new_err.stderr
            failed = new_err.returncode != 0
        else:
            failed = True
        if failed:
            findings.append(make_finding(
                "apidiff-exposed",
                "apidiff cannot compare the package, check its API by hand",
                unit,
                package=mn,
                version=vn,
                origin=vo,
                symbol=npkg,
                note=clip_err(err),
                locs=[modloc(new_dir, mn, prefix)],
            ))
            continue
        compared = run([ad, "-incompatible", str(slot / "old"), str(slot / "new")])
        changes = [line[2:] for line in compared.stdout.replace("\r", "").splitlines() if line.startswith("- ")]
        if changes:
            records.append((mo, vo, vn, mn, npkg, changes))
    return records


def iter_go_files(directory, skip_vendor):
    root = Path(directory)
    for dirpath, dirnames, filenames in os.walk(root):
        rel_dir = Path(dirpath).relative_to(root).as_posix()
        if rel_dir == ".":
            rel_dir = ""
        if rel_dir == ".git" or rel_dir.startswith(".git/"):
            dirnames[:] = []
            continue
        if skip_vendor and (rel_dir == "vendor" or rel_dir.startswith("vendor/")):
            dirnames[:] = []
            continue
        for name in filenames:
            if name.endswith(".go"):
                rel = f"{rel_dir}/{name}" if rel_dir else name
                yield Path(dirpath) / name, rel


def import_names(directory, npkg, name):
    names = {name}
    pattern = re.compile(r'^\s*(?:import\s+)?([A-Za-z_][A-Za-z0-9_]*)\s+"' + re.escape(npkg) + '"')
    for path, _ in iter_go_files(directory, skip_vendor=False):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for line in text.splitlines():
            match = pattern.match(line)
            if match:
                names.add(match.group(1))
    return "|".join(sorted(names))


def ident_pattern(ids):
    parts = [re.escape(part) for part in ids.split("|") if part]
    return "(?:" + "|".join(parts) + ")"


def exposed_hit(directory, ownpkgs, npkg, ids):
    pattern = re.compile(r"(?:^|[^A-Za-z0-9_.])" + ident_pattern(ids) + r"\.[A-Z]")
    for ownpkg in ownpkgs:
        imported = go_text(directory, ["list", "-f", '{{join .Imports " "}}', ownpkg])
        if npkg not in imported.split():
            continue
        doc = run(["go", "doc", "-all", ownpkg], cwd=directory)
        for line in doc.stdout.splitlines():
            if pattern.search(line):
                return ownpkg + ": " + re.sub(r"^\s+", "", line)
    return ""


def referenced_symbols(directory, ids):
    pattern = re.compile(
        r"(?:^|[^A-Za-z0-9_.])(" + ident_pattern(ids) + r"\.[A-Z][A-Za-z0-9_]*)"
    )
    found = set()
    for path, rel in iter_go_files(directory, skip_vendor=True):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for number, line in enumerate(text.splitlines(), start=1):
            for match in pattern.finditer(line):
                symbol = match.group(1).rsplit(".", 1)[-1]
                found.add(f"{symbol} {rel}:{number}")
    return sorted(found)


def change_top(change):
    match = CHANGE_TOP.match(change)
    if match:
        return match.group(1)
    return change.split(" ", 1)[0]


def own_packages(directory):
    text = go_text(directory, ["list", "-f", "{{if not .Standard}}{{.ImportPath}}{{end}}", "./..."])
    return [line for line in text.splitlines() if line and not OWN_SKIP.search(line)]


def apidiff_findings(directory, records, unit, prefix):
    found = []
    ownpkgs = own_packages(directory)
    for mo, vo, vn, mn, npkg, changes in records:
        name = go_text(directory, ["list", "-f", "{{.Name}}", npkg]).strip()
        if not name:
            continue
        ids = import_names(directory, npkg, name)
        hit = exposed_hit(directory, ownpkgs, npkg, ids)
        if not hit:
            continue
        note = f"types appear in our exported API ({hit})"
        if mo != mn:
            note = f"from {mo}; {note}"
        uses = {}
        for item in referenced_symbols(directory, ids):
            symbol, _, place = item.partition(" ")
            uses.setdefault(symbol, []).append(place)
        groups = {}
        for change in changes:
            groups.setdefault(change_top(change), []).append(change)
        unused = []
        for top in sorted(groups):
            places = uses.get(top) or []
            if places:
                shown = "; ".join(groups[top][:3])[:400]
                found.append(make_finding(
                    "apidiff-exposed",
                    f"{len(groups[top])} incompatible change(s): {shown}",
                    unit,
                    package=mn,
                    version=vn,
                    origin=vo,
                    symbol=f"{name}.{top}",
                    note=note,
                    locs=[prefix + place for place in places[:5]],
                ))
            else:
                unused.extend(groups[top])
        if unused:
            shown = "; ".join(unused[:3])[:400]
            found.append(make_finding(
                "apidiff-exposed",
                f"{len(unused)} incompatible change(s) in symbols our code does not reference as {name}.<Symbol>, e.g. {shown}",
                unit,
                package=mn,
                version=vn,
                origin=vo,
                symbol=f"{name}.*",
                note=note,
                locs=[modloc(directory, mn, prefix)],
            ))
    return found


def compare_modules(ad, old_dir, new_dir, old_mods, new_mods, work, unit, prefix):
    own = go_text(new_dir, ["list", "-m"]).strip()
    old_imports = imports(old_dir, own)
    new_imports = imports(new_dir, own)
    failures = []
    records = []
    for pair in version_pairs(old_mods, new_mods):
        records.extend(apidiff_module(
            ad, old_dir, new_dir, old_imports, new_imports, pair, work, unit, prefix, failures,
        ))
    if records:
        failures.extend(apidiff_findings(new_dir, records, unit, prefix))
    return failures


def manifests_unchanged(repo, base, head, prefix):
    proc = run(["git", "-C", str(repo), "diff", "--quiet", base, head, "--", prefix + "go.mod", prefix + "go.sum"])
    return proc.returncode == 0


def warn_behind(repo, base, head):
    base_rev = run(["git", "-C", str(repo), "merge-base", base, head])
    if base_rev.returncode != 0 or not base_rev.stdout.strip():
        return
    count = run(["git", "-C", str(repo), "rev-list", "--count", f"{base_rev.stdout.strip()}..{base}"])
    if count.returncode != 0:
        return
    try:
        behind = int(count.stdout.strip() or "0")
    except ValueError:
        return
    if behind > 0:
        log(f"head is {behind} commit(s) behind base: the diff may revert changes from base (CI checks the PR merge commit, where this cannot happen)")


def add_worktree(repo, dest, rev):
    proc = run(["git", "-C", str(repo), "worktree", "add", "-q", "--detach", str(dest), rev])
    if proc.returncode != 0:
        raise AdapterFailure(f"cannot check out {rev}")


def cleanup(ctx):
    repo = ctx.get("repo")
    work = ctx.get("work")
    if repo is not None and work is not None:
        for name in ("old", "new"):
            subprocess.run(
                ["git", "-C", str(repo), "worktree", "remove", "-f", str(work / name)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
    if work is not None:
        shutil.rmtree(work, ignore_errors=True)


def out_dir():
    raw = os.environ.get("OUT_DIR") or ""
    if raw:
        return Path(raw)
    root = os.environ.get("RUNNER_TEMP") or tempfile.gettempdir()
    return Path(root) / "dependency-gate-findings"


def summarize(items):
    counts = {}
    for item in items:
        counts[item["check"]] = counts.get(item["check"], 0) + 1
    parts = [f"{name} {counts[name]}" for name in sorted(counts)]
    log(f"{len(items)} finding(s) on head: {', '.join(parts)}")


def library(ctx):
    repo = Path(os.environ.get("REPO_DIR") or ".").resolve()
    if not repo.is_dir():
        raise AdapterFailure(f"repository not found: {repo}")
    ctx["repo"] = repo
    destination = out_dir()
    destination.mkdir(parents=True, exist_ok=True)
    destination = destination.resolve()
    ctx["out"] = destination / "go-library-head.json"
    ctx["tool"] = f"go-dep-check ({go_env('GOVERSION')})"
    ad = apidiff_path()
    config = load_config()
    modules = load_modules()
    families = go_families(config)
    base = os.environ.get("BASE_REF") or ""
    head = os.environ.get("HEAD_REF") or "HEAD"
    work = Path(tempfile.mkdtemp())
    ctx["work"] = work
    new_tree = work / "new"
    old_tree = work / "old"
    add_worktree(repo, new_tree, head)
    if base:
        add_worktree(repo, old_tree, base)
        warn_behind(repo, base, head)
    head_items = []
    base_items = []
    ran = []
    checked = 0
    for module in modules:
        prefix = module_prefix(module)
        unit = module
        if base and manifests_unchanged(repo, base, head, prefix):
            log(f"{module}: go.mod/go.sum unchanged")
            continue
        if not (module_dir(new_tree, module) / "go.mod").is_file():
            log(f"{module}: no go.mod at {head}")
            continue
        checked += 1
        log(f"{module}: checking")
        new_dir = module_dir(new_tree, module)
        new_mods = module_versions(new_dir)
        found, did = family_findings(new_mods, families, prefix, unit)
        if did and "family-split" not in ran:
            ran.append("family-split")
        head_items.extend(found)
        if base and (module_dir(old_tree, module) / "go.mod").is_file():
            old_dir = module_dir(old_tree, module)
            old_mods = module_versions(old_dir)
            found, did = family_findings(old_mods, families, prefix, unit)
            if did and "family-split" not in ran:
                ran.append("family-split")
            base_items.extend(found)
            if "apidiff-exposed" not in ran:
                ran.append("apidiff-exposed")
            head_items.extend(compare_modules(ad, old_dir, new_dir, old_mods, new_mods, work, unit, prefix))
    tool = ctx["tool"]
    if checked == 0:
        write_findings(ctx["out"], "go-library", "go", "head", "skipped", "go.mod/go.sum unchanged", [], [], tool)
    else:
        write_findings(ctx["out"], "go-library", "go", "head", "ok", "", ran, head_items, tool)
        if base:
            write_findings(destination / "go-library-base.json", "go-library", "go", "base", "ok", "", ran, base_items, tool)
    ctx["written"] = True
    summarize(head_items)


def write_error(ctx):
    path = ctx.get("out")
    if path is None:
        return
    try:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        write_findings(
            path, "go-library", "go", "head", "error",
            ctx.get("reason") or "library.py exited with code 2", [], [], ctx.get("tool") or "go-dep-check",
        )
    except OSError as exc:
        log(f"cannot write {path}: {exc}")


def main():
    ctx = {"written": False, "reason": "", "out": None, "tool": "go-dep-check", "repo": None, "work": None}
    try:
        library(ctx)
    except AdapterFailure as exc:
        ctx["reason"] = str(exc)
    except KeyboardInterrupt:
        ctx["reason"] = "interrupted"
    except Exception as exc:
        ctx["reason"] = f"library.py exited: {exc}"
    finally:
        cleanup(ctx)
    if not ctx["written"]:
        write_error(ctx)
        if ctx["reason"]:
            log(ctx["reason"])
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
