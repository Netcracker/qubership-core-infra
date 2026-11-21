#!/usr/bin/env python3
"""findings.v1 for the dependency-gate adapters.

library.py and consumers.py import this module. The gate reads the files and applies
levels, the baseline, ignore rules and the verdict. Empty finding fields are omitted.
"""
import json
import re

ERROR_LINE = re.compile(r"\.go:[0-9]+|panic|error|cannot|undefined|FAIL")
LOC = re.compile(r"^(?P<file>.+?)(?::(?P<line>[0-9]+))?$")


def write_findings(path, adapter, ecosystem, revision, status, reason, checks, items, tool):
    body = {
        "name": adapter,
        "ecosystem": ecosystem,
    }
    if tool:
        body["tool"] = tool
    body["status"] = status
    body["checks"] = checks
    if reason:
        body["reason"] = reason
    document = {
        "schema": "dependency-gate-findings/v1",
        "adapter": body,
        "revision": revision,
        "findings": items,
    }
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(document) + "\n")


def split_checks(text):
    seen = []
    for part in text.split():
        if part and part not in seen:
            seen.append(part)
    return seen


def finding(check, message, package="", symbol="", note=""):
    item = {"check": check, "message": message}
    if package:
        item["package"] = package
    if symbol:
        item["symbol"] = symbol
    if note:
        item["note"] = note
    return item


def finding_from_args(check, message, args, unit=""):
    """One finding from emit's check, message and key=value arguments. Empty values are left out."""
    item = {"check": check, "message": message}
    locations = []
    for raw in args:
        key, sep, value = raw.partition("=")
        if sep != "=" or value == "" or not re.fullmatch(r"[a-z]+", key):
            continue
        if key == "loc":
            loc = location(value, unit)
            if loc:
                locations.append(loc)
        else:
            item[key] = value
    if locations:
        item["locations"] = locations
    return item


def make_finding(check, message, unit="", package="", version="", origin="", symbol="", vuln="", license_name="", note="", locs=None):
    args = []
    for key, value in (
        ("package", package),
        ("version", version),
        ("from", origin),
        ("symbol", symbol),
        ("vuln", vuln),
        ("license", license_name),
        ("note", note),
    ):
        if value:
            args.append(f"{key}={value}")
    for loc in locs or []:
        if loc:
            args.append(f"loc={loc}")
    return finding_from_args(check, message, args, unit)


def location(value, unit):
    match = LOC.fullmatch(value)
    if not match or not match.group("file"):
        return None
    loc = {"file": match.group("file")}
    if match.group("line"):
        loc["line"] = int(match.group("line"))
    if unit:
        loc["unit"] = unit
    return loc


def error_lines(text, limit=3):
    kept = []
    for line in text.splitlines():
        if ERROR_LINE.search(line):
            kept.append(line[:300])
            if len(kept) == limit:
                break
    return kept


def excerpt(text, limit=3):
    """First matching error lines, the way the shell excerpt printed them."""
    return "\n".join(error_lines(text, limit))


def new_vet(old, new):
    """go vet lines that are new by file and message, ignoring the line and column.

    A finding that only moved is not new. Equal lines are consumed before that comparison,
    so a second copy of a message that also occurs on the base is new.
    """
    def key(line):
        return re.sub(r":[0-9]+(?::[0-9]+)?:", ":", line, count=1)

    def useful(line):
        return line and not line.startswith("#") and not line.startswith("go: downloading")

    old_counts, moved = {}, {}
    for line in filter(useful, old.splitlines()):
        old_counts[line] = old_counts.get(line, 0) + 1
        moved[key(line)] = moved.get(key(line), 0) + 1
    rest = []
    for line in sorted({line for line in new.splitlines() if useful(line)}):
        if old_counts.get(line, 0) > 0:
            old_counts[line] -= 1
            moved[key(line)] = moved.get(key(line), 0) - 1
        else:
            rest.append(line)
    fresh = []
    for line in rest:
        k = key(line)
        if moved.get(k, 0) <= 0:
            fresh.append(line)
        else:
            moved[k] -= 1
    return fresh
