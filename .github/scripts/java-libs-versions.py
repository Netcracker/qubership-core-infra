#!/usr/bin/env python3
"""Versions of a qubership-core-java-libs build, and the rewrite that points a consumer at them.

  collect <java-libs-dir>
      Walks the poms. modules: every module's own groupId:artifactId and its version.
      boms: third-party BOMs (scope import) that the repository imports at one version.
      Prints {"modules", "boms"} as JSON.

  installed <maven-repo>
      modules: artifacts this build installed (maven-metadata-local.xml). boms is empty:
      a third-party BOM is not in the local install, so a consumer must keep resolving it.
      Same JSON.

  apply [--strict] [--installed <maven-repo> | <versions.json>] <pom-or-dir>...
      Rewrites parent, dependency and plugin versions in place, or the property they use.
      --installed reads the versions from a local repository instead of a JSON file.
      --strict is the dependency gate: exit 3 when nothing references the build, and a
      missing property fails the rewrite. Without it a missing property is a warning.

test-apps and the gate both call apply through .github/actions/use-java-libs.
The gate passes --strict --installed, so foreign BOMs stay untouched.
"""

import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PROP = re.compile(r"\$\{([^}]+)}")
BLOCK = re.compile(r"<(parent|dependency|plugin)\b[^>]*>(.*?)</\1>", re.S)


def text(el, path):
    found = el.find(path, NS)
    return found.text.strip() if found is not None and found.text else None


def load(path):
    root = ET.parse(path).getroot()
    parent = root.find("m:parent", NS)
    props = {}
    for p in root.findall("m:properties/*", NS):
        props[p.tag.split("}", 1)[1]] = (p.text or "").strip()
    return {
        "path": path,
        "groupId": text(root, "m:groupId"),
        "artifactId": text(root, "m:artifactId"),
        "version": text(root, "m:version"),
        "parent": None if parent is None else {
            "groupId": text(parent, "m:groupId"),
            "artifactId": text(parent, "m:artifactId"),
            "version": text(parent, "m:version"),
            "relativePath": text(parent, "m:relativePath"),
        },
        "props": props,
    }


def collect(repo):
    """Module versions, resolved through the parent chain, and single-version BOM imports."""
    poms = {}
    for base, dirs, files in os.walk(repo):
        dirs[:] = [d for d in dirs if d not in (".git", "target", "node_modules")]
        if "pom.xml" not in files:
            continue
        path = os.path.normpath(os.path.join(base, "pom.xml"))
        try:
            poms[path] = load(path)
        except ET.ParseError:
            continue
    by_ga = {}
    for pom in poms.values():
        gid = pom["groupId"] or (pom["parent"] or {}).get("groupId")
        by_ga[f"{gid}:{pom['artifactId']}"] = pom

    def parent_of(pom):
        parent = pom["parent"]
        if not parent:
            return None
        rel = parent["relativePath"]
        if rel is None:
            rel = "../pom.xml"
        if rel:
            cand = os.path.normpath(os.path.join(os.path.dirname(pom["path"]), rel))
            if os.path.isdir(cand):
                cand = os.path.join(cand, "pom.xml")
            found = poms.get(cand)
            if found and found["artifactId"] == parent["artifactId"]:
                return found
        return by_ga.get(f"{parent['groupId']}:{parent['artifactId']}")

    cache = {}

    def context(pom):
        key = pom["path"]
        if key in cache:
            return cache[key]
        cache[key] = {}
        parent = parent_of(pom)
        ctx = dict(context(parent)) if parent else {}
        ctx.update(pom["props"])
        raw_version = pom["version"] or (pom["parent"] or {}).get("version")
        if pom["parent"]:
            ctx["project.parent.version"] = pom["parent"]["version"]
            ctx["parent.version"] = pom["parent"]["version"]
        ctx["project.version"] = raw_version
        ctx["version"] = raw_version
        cache[key] = ctx
        return ctx

    def resolve(value, ctx):
        for _ in range(10):
            new = PROP.sub(lambda m: ctx.get(m.group(1), m.group(0)), value)
            if new == value:
                break
            value = new
        return value

    modules = {}
    for ga, pom in sorted(by_ga.items()):
        ctx = context(pom)
        version = resolve(ctx["project.version"] or "", ctx)
        if version and not PROP.search(version):
            modules[ga] = version

    seen = {}
    for pom in poms.values():
        ctx = context(pom)
        root = ET.parse(pom["path"]).getroot()
        for dep in root.findall("m:dependencyManagement/m:dependencies/m:dependency", NS):
            if text(dep, "m:scope") != "import":
                continue
            ga = f"{resolve(text(dep, 'm:groupId') or '', ctx)}:{resolve(text(dep, 'm:artifactId') or '', ctx)}"
            version = resolve(text(dep, "m:version") or "", ctx)
            if ga in modules or not version or PROP.search(version) or PROP.search(ga):
                continue
            seen.setdefault(ga, set()).add(version)
    boms = {ga: versions.pop() for ga, versions in sorted(seen.items()) if len(versions) == 1}
    return {"modules": modules, "boms": boms}


def from_repo(repo):
    """Artifacts installed into a local Maven repository. Third-party BOMs are not among them."""
    modules = {}
    root = Path(repo)
    for meta in root.rglob("maven-metadata-local.xml"):
        version_dir = meta.parent
        artifact = version_dir.parent.name
        group = ".".join(version_dir.parent.parent.relative_to(root).parts)
        modules[f"{group}:{artifact}"] = version_dir.name
    return {"modules": modules, "boms": {}}


def apply_file(path, versions, strict):
    """Rewrite one pom. Returns whether it references the build."""
    with open(path, encoding="utf-8") as f:
        original = f.read()
    src = original
    declared = {}
    block = re.search(r"<properties>(.*?)</properties>", src, re.S)
    if block:
        for name, value in re.findall(r"<([\w.\-]+)>\s*(.*?)\s*</\1>", block.group(1), re.S):
            declared[name] = value

    def expand(value):
        for _ in range(10):
            new = PROP.sub(lambda m: declared.get(m.group(1), m.group(0)), value)
            if new == value:
                break
            value = new
        return value

    props = {}
    changes = []
    used = False

    def fix_block(m):
        nonlocal used
        body = m.group(2)
        g = re.search(r"<groupId>\s*(.*?)\s*</groupId>", body)
        a = re.search(r"<artifactId>\s*(.*?)\s*</artifactId>", body)
        v = re.search(r"<version>\s*(.*?)\s*</version>", body)
        if not (g and a and v):
            return m.group(0)
        ga = f"{expand(g.group(1))}:{expand(a.group(1))}"
        wanted = versions["modules"].get(ga)
        if not wanted and m.group(1) == "dependency" and re.search(r"<scope>\s*import\s*</scope>", body):
            wanted = versions["boms"].get(ga)
        if not wanted:
            return m.group(0)
        used = True
        current = v.group(1)
        prop = re.fullmatch(r"\$\{([^}]+)\}", current)
        if prop:
            name = prop.group(1)
            while re.fullmatch(r"\$\{([^}]+)\}", declared.get(name, "")):
                name = declared[name][2:-1]
            if name not in ("project.version", "version"):
                previous = props.get(name)
                if previous is not None and previous != wanted:
                    print(f"::warning file={path}::property {name} is wanted as {previous} and as {wanted}",
                          file=sys.stderr)
                props[name] = wanted
            return m.group(0)
        if current != wanted:
            changes.append(f"{ga} {current} -> {wanted}")
        elif strict:
            changes.append(f"{ga} {current} (already)")
        body = body[:v.start(1)] + wanted + body[v.end(1):]
        return f"<{m.group(1)}>{body}</{m.group(1)}>"

    src = BLOCK.sub(fix_block, src)
    for name, wanted in props.items():
        pattern = re.compile(rf"(<{re.escape(name)}>)\s*(.*?)\s*(</{re.escape(name)}>)")
        found = pattern.search(src)
        if not found:
            message = f"property {name} is used for a java-libs artifact but not defined in this pom"
            if strict:
                sys.exit(f"{path}: {message}")
            print(f"::warning file={path}::{message}", file=sys.stderr)
            continue
        if found.group(2) != wanted:
            changes.append(f"property {name} {found.group(2)} -> {wanted}")
        elif strict:
            changes.append(f"property {name} {found.group(2)} (already)")
        src = src[:found.start()] + f"{found.group(1)}{wanted}{found.group(3)}" + src[found.end():]
    if src != original:
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(src)
    for change in changes:
        print(f"{path}: {change}")
    return used


def poms_of(path):
    file = Path(path)
    if file.is_file():
        return [file]
    return sorted(p for p in file.rglob("pom.xml") if "target" not in p.parts and ".git" not in p.parts)


def emit(data):
    json.dump(data, sys.stdout, indent=2, sort_keys=True)
    print()


def apply_paths(versions, paths, strict):
    used = False
    for path in paths:
        for pom in poms_of(path):
            used = apply_file(pom, versions, strict) or used
    if strict and not used:
        print("no dependency, parent or plugin matches the installed library", file=sys.stderr)
        return 3
    return 0


def main(argv):
    if len(argv) == 2 and argv[0] == "collect":
        emit(collect(argv[1]))
        return 0
    if len(argv) == 2 and argv[0] == "installed":
        emit(from_repo(argv[1]))
        return 0
    if argv and argv[0] == "apply":
        args = argv[1:]
        strict = False
        repo = None
        while args and args[0].startswith("--"):
            if args[0] == "--strict":
                strict = True
                args = args[1:]
            elif args[0] == "--installed" and len(args) >= 2:
                repo = args[1]
                args = args[2:]
            else:
                sys.exit(__doc__)
        if repo is not None:
            versions = from_repo(repo)
            if not versions["modules"]:
                sys.exit("the library build installed nothing")
            print(f"installed library has {len(versions['modules'])} artifact(s)")
        elif args:
            with open(args[0], encoding="utf-8") as f:
                versions = json.load(f)
            versions.setdefault("modules", {})
            versions.setdefault("boms", {})
            args = args[1:]
        else:
            sys.exit(__doc__)
        if not args:
            sys.exit(__doc__)
        return apply_paths(versions, args, strict)
    sys.exit(__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
