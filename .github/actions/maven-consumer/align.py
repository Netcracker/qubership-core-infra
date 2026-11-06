#!/usr/bin/env python3
"""Point a consumer at the library revision installed into a local Maven repository.

Reads maven-metadata-local.xml under the repository (artifacts this build installed) and rewrites
matching dependency, parent and plugin versions in the consumer. A version that is already the
installed one is left as text. ${property} is updated at the property definition.

Exit 0 when the consumer references the build, 3 when it does not.
"""

import re
import sys
from pathlib import Path

REGION = re.compile(r"<(dependency|parent|plugin)\b[^>]*>.*?</\1>", re.S)
GAV = re.compile(
    r"<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>\s*<version>([^<]+)</version>",
    re.S,
)
PROP = re.compile(r"\$\{([^}]+)}")


def installed(repo: Path) -> dict[tuple[str, str], str]:
    found: dict[tuple[str, str], str] = {}
    for meta in repo.rglob("maven-metadata-local.xml"):
        version_dir = meta.parent
        artifact = version_dir.parent.name
        group = ".".join(version_dir.parent.parent.relative_to(repo).parts)
        found[(group, artifact)] = version_dir.name
    return found


def rewrite(text: str, gavs: dict[tuple[str, str], str]) -> tuple[str, bool, list[str]]:
    used = False
    notes: list[str] = []
    props: dict[str, str] = {}

    def region(match: re.Match[str]) -> str:
        nonlocal used

        def gav(g: re.Match[str]) -> str:
            nonlocal used
            group, artifact, version = (p.strip() for p in g.groups())
            want = gavs.get((group, artifact))
            if want is None:
                return g.group(0)
            used = True
            ga = f"{group}:{artifact}"
            if version == want:
                notes.append(f"{ga} {version} (already)")
                return g.group(0)
            prop = PROP.fullmatch(version)
            if prop:
                props[prop.group(1)] = want
                notes.append(f"{ga} ${{{prop.group(1)}}} -> {want}")
                return g.group(0)
            notes.append(f"{ga} {version} -> {want}")
            return g.group(0).replace(f"<version>{g.group(3)}</version>", f"<version>{want}</version>", 1)

        return GAV.sub(gav, match.group(0))

    text = REGION.sub(region, text)
    for name, value in props.items():
        text, n = re.subn(
            rf"(<{re.escape(name)}>)[^<]*(</{re.escape(name)}>)",
            rf"\g<1>{value}\2",
            text,
            count=1,
        )
        if n == 0:
            sys.exit(f"version property {name} is not defined in this pom")
        notes.append(f"${{{name}}} -> {value}")
    return text, used, notes


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit("usage: align.py <consumer-dir> <maven-repo>")
    consumer, repo = Path(sys.argv[1]), Path(sys.argv[2])
    gavs = installed(repo)
    if not gavs:
        sys.exit("the library build installed nothing")
    print(f"align: installed library has {len(gavs)} artifact(s)", file=sys.stderr)
    used = False
    for pom in sorted(consumer.rglob("pom.xml")):
        text = pom.read_text(encoding="utf-8")
        updated, hit, notes = rewrite(text, gavs)
        used = used or hit
        if notes:
            rel = pom.relative_to(consumer).as_posix()
            print(f"align: {rel}", file=sys.stderr)
            for line in notes:
                print(f"align:   {line}", file=sys.stderr)
        if updated != text:
            pom.write_text(updated, encoding="utf-8")
    if not used:
        print("align: no dependency, parent or plugin matches the installed library", file=sys.stderr)
        sys.exit(3)


if __name__ == "__main__":
    main()
