#!/usr/bin/env python3
"""
Generate HTML report with open PRs for Netcracker repositories
tagged with topics: qubership-core.

Usage:
    pr-report.py [output.html] [--token TOKEN] [--state open|closed|all] [--workers N]
"""
import argparse
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path

import requests
from jinja2 import Environment, FileSystemLoader

ORG = "Netcracker"
TOPICS = ["qubership-core"]
API_BASE = "https://api.github.com"

FAIL_CONCLUSIONS = {"failure", "cancelled", "timed_out", "action_required"}
BOTS = {"renovate[bot]", "renovate-bot", "NetcrackerCLPLCI"}


def classify_lang(topics):
    if "go" in topics:
        return "go"
    if "java" in topics:
        return "java"
    return "other"


def classify_repo_type(topics):
    if "lib" in topics:
        return "lib"
    if "cloud-core" in topics:
        return "service"
    return "other"

RETRY_DELAYS = [0.5, 1, 2]


def get_headers(token):
    return {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }


def api_get(token, url, params=None):
    for attempt, delay in enumerate(RETRY_DELAYS):
        resp = requests.get(url, headers=get_headers(token), params=params)
        if resp.status_code in (429, 403):
            wait = int(resp.headers.get("Retry-After", delay))
            print(f"    Rate limited, retrying in {wait}s...")
            time.sleep(wait)
            continue
        resp.raise_for_status()
        return resp
    resp.raise_for_status()


def load_exclusions(path):
    p = Path(path)
    if not p.exists():
        return set()
    return {line.strip() for line in p.read_text(encoding="utf-8").splitlines() if line.strip() and not line.startswith("#")}


def get_repos(token):
    query = f"org:{ORG} " + " ".join(f"topic:{t}" for t in TOPICS)
    repos = []
    page = 1
    while True:
        data = api_get(token, f"{API_BASE}/search/repositories", {"q": query, "per_page": 100, "page": page}).json()
        repos.extend(data["items"])
        if len(repos) >= data["total_count"] or not data["items"]:
            break
        page += 1
    return repos


def get_prs(token, repo_full_name, state):
    prs = []
    page = 1
    while True:
        data = api_get(token, f"{API_BASE}/repos/{repo_full_name}/pulls",
                       {"state": state, "per_page": 100, "page": page, "sort": "updated", "direction": "desc"}).json()
        if not data:
            break
        prs.extend(data)
        if len(data) < 100:
            break
        page += 1
    return prs


def get_checks(token, repo_full_name, sha):
    runs = []
    page = 1
    while True:
        data = api_get(token, f"{API_BASE}/repos/{repo_full_name}/commits/{sha}/check-runs",
                       {"per_page": 100, "page": page}).json()
        runs.extend(data.get("check_runs", []))
        if len(runs) >= data.get("total_count", 0) or not data.get("check_runs"):
            break
        page += 1

    if not runs:
        return {"status": "none", "passed": 0, "failed": 0, "pending": 0, "total": 0, "failed_runs": []}

    passed = pending = 0
    failed_runs = []
    for run in runs:
        if run["status"] != "completed":
            pending += 1
        elif run["conclusion"] in FAIL_CONCLUSIONS:
            failed_runs.append({"name": run["name"], "url": run["html_url"]})
        else:
            passed += 1

    if failed_runs:
        status = "failure"
    elif pending:
        status = "pending"
    else:
        status = "success"

    return {"status": status, "passed": passed, "failed": len(failed_runs), "pending": pending, "total": len(runs), "failed_runs": failed_runs}


def parse_pr(repo, pr, checks):
    return {
        "repo": repo["name"],
        "repo_url": repo["html_url"],
        "number": pr["number"],
        "title": pr["title"],
        "url": pr["html_url"],
        "checks_url": pr["html_url"] + "/checks",
        "author": pr["user"]["login"],
        "author_url": pr["user"]["html_url"],
        "draft": pr.get("draft", False),
        "state": pr["state"],
        "labels": [label["name"] for label in pr.get("labels", [])],
        "created_at": pr["created_at"][:10],
        "updated_at": pr["updated_at"][:10],
        "base": pr["base"]["ref"],
        "checks": checks,
        "is_bot": pr["user"]["login"] in BOTS,
        "lang": classify_lang(repo.get("topics", [])),
        "repo_type": classify_repo_type(repo.get("topics", [])),
    }


def fetch_repo_prs(token, repo, state):
    print(f"  Fetching PRs for {repo['full_name']}...")
    prs = get_prs(token, repo["full_name"], state)
    print(f"    {len(prs)} PRs found in {repo['name']}")
    return repo, prs


def fetch_pr_checks(token, repo, pr):
    sha = pr["head"]["sha"]
    print(f"    Fetching checks for {repo['name']}#{pr['number']} ({sha[:7]})...")
    checks = get_checks(token, repo["full_name"], sha)
    return repo, pr, checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", nargs="?", default="pr-report.html", help="Output HTML file")
    parser.add_argument("--token", default=os.environ.get("GITHUB_TOKEN"), help="GitHub token")
    parser.add_argument("--exclude", default=str(Path(__file__).resolve().parent / "exclude-repos.txt"), help="File with excluded repo names")
    parser.add_argument("--workers", type=int, default=8, help="Number of parallel workers")
    args = parser.parse_args()

    if not args.token:
        print("Error: GitHub token required (--token or GITHUB_TOKEN env var)", file=sys.stderr)
        sys.exit(1)

    exclusions = load_exclusions(args.exclude)
    if exclusions:
        print(f"Exclusions loaded ({len(exclusions)} repos)")

    print(f"Fetching repositories in {ORG} with topics: {', '.join(TOPICS)}...")
    repos = get_repos(args.token)
    repos = [r for r in repos if r["name"] not in exclusions]
    print(f"Found {len(repos)} repositories (after exclusions)")

    # Fetch PRs for all repos in parallel
    repo_prs = []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(fetch_repo_prs, args.token, repo, "open"): repo for repo in repos}
        for future in as_completed(futures):
            repo, prs = future.result()
            if prs:
                repo_prs.append((repo, prs))

    total_prs = sum(len(prs) for _, prs in repo_prs)
    print(f"Total PRs to process: {total_prs}, fetching checks...")

    # Fetch checks for all PRs in parallel
    rows_unordered = []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {
            pool.submit(fetch_pr_checks, args.token, repo, pr): (repo, pr)
            for repo, prs in repo_prs
            for pr in prs
        }
        for future in as_completed(futures):
            repo, pr, checks = future.result()
            rows_unordered.append(parse_pr(repo, pr, checks))

    rows = sorted(rows_unordered, key=lambda r: (r["repo"], -r["number"]))
    print(f"Total PRs: {len(rows)}")

    script_dir = Path(__file__).resolve().parent
    env = Environment(loader=FileSystemLoader(str(script_dir)), autoescape=True)
    template = env.get_template("report.html.j2")

    generated_at = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    html = template.render(rows=rows, generated_at=generated_at, org=ORG, topics=TOPICS)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(html, encoding="utf-8")
    print(f"Report written to {output_path}")


if __name__ == "__main__":
    main()
