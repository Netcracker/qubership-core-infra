# LTS Versions Report — Requirements & Design

## Requirements

### Functional

1. **Input**: the `qubership-core-java-libs` git repository with one or more
   `lts/*` branches.

2. **Scope**: for each LTS branch, inspect every top-level module directory
   (every direct child of the repo root that contains a `pom.xml`).

3. **Artifact discovery**: collect **all** `pom.xml` files recursively within
   each module at every depth.

4. **Version**: determined once per module per branch from the module root
   `pom.xml` (`<module>/pom.xml`). All versions are `X.Y.N-SNAPSHOT` with
   `N > 0`, so the released version is always `X.Y.(N-1)`.

5. **Table structure**:

   | Module | Artifact | lts/25.3 | lts/25.4 | lts/26.1 | lts/26.2 |
   |--------|----------|----------|----------|----------|----------|
   | maas-client | maas-client-parent | 11.1.5 | 11.2.7 | 11.3.4 | 12.0.3 |
   | maas-client | maas-client-bom    | 11.1.5 | 11.2.7 | 11.3.4 | 12.0.3 |

   - **Module** column: top-level directory name, one `<td rowspan="N">` per
     module block (no filtering, so rowspan is stable).
   - **Artifact** column: `artifactId` from each `pom.xml`, sorted
     alphabetically within each module block.
   - **Version columns**: one per branch, same value for all artifacts within
     a module. `—` when the module did not exist on that branch.

6. **Output**: a self-contained HTML file, no external dependencies, no JS.

7. **Automation**: GitHub Actions workflow runs nightly and on manual trigger,
   deploys to the `lts-versions/` path on the `gh-pages` branch.

---

## Design

### Language

**Python 3** (standard library only): `subprocess`, `tarfile`, `io`.

### Files

```
lts-versions-script/
├── generate-versions-report.py   # replaces generate-versions-report.sh
├── get-lts-versions.sh           # unchanged
├── DESIGN.md
```

### Git access — 2 processes per branch

```
git ls-tree -r --name-only <ref>   →  list of all file paths
git archive <ref> <pom_paths>      →  tar stream read into memory
```

`pom_paths` = paths ending with `pom.xml` that contain at least one `/`
(excludes the repo-root aggregator `pom.xml`).

### pom.xml parsing — plain text, no XML library

All `<artifactId>` and `<version>` tags in this repo appear one per line in
the form `<tag>value</tag>`. We skip lines between `<parent>` and `</parent>`
(also one tag per line) to avoid picking up the parent's values.

```python
def extract_field(text, tag):
    in_parent = False
    for line in text.splitlines():
        if '<parent>'  in line: in_parent = True
        if '</parent>' in line: in_parent = False; continue
        if in_parent: continue
        if f'<{tag}>' in line:
            return line.split(f'<{tag}>')[1].split(f'</{tag}>')[0].strip()
    return None
```

### Data structures

```python
branches: list[str]                        # sorted, e.g. ["lts/25.3", …]
modules:  list[str]                        # sorted top-level dir names

# artifacts[module] = sorted list of artifactId strings (union across branches)
artifacts: dict[str, list[str]]

# versions[branch][module] = released version string, or None
versions:  dict[str, dict[str, str | None]]
```

### Version computation

```python
def released(snapshot: str) -> str:
    base = snapshot.removesuffix('-SNAPSHOT')   # "X.Y.N"
    prefix, patch = base.rsplit('.', 1)
    return f"{prefix}.{int(patch) - 1}"
```

### HTML generation

Pure string building; no template engine needed given the simple structure.
Module cell uses `rowspan` equal to the number of artifacts in that module.
Alternating row background (`#fff` / `#f6f8fa`) resets at each module boundary.
