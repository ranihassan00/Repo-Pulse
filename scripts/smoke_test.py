"""Opt-in live smoke test. Uses public GitHub data and no credentials."""
import json
import sys
import time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

BASE = "http://localhost:8080"


def request(path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = Request(BASE + path, data=data, headers={"Content-Type": "application/json"})
    try:
        with urlopen(req, timeout=210) as response:
            return json.load(response)
    except HTTPError as error:
        # The API exposes sanitized errors; include them in CI diagnostics.
        print(f"API {error.code}: {error.read().decode()}", file=sys.stderr)
        raise


def ready():
    for _ in range(60):
        try:
            if request("/actuator/health")["status"] == "UP":
                return
        except (OSError, ValueError):
            pass
        time.sleep(2)
    raise RuntimeError("Backend did not become ready")


def observed(repo_id):
    summary = request(f"/api/repositories/{repo_id}")
    return {
        "id": repo_id,
        "coverage": summary["coverage"],
        "contributors": summary["sampledContributorCount"],
        "hotspots": request(f"/api/repositories/{repo_id}/hotspots"),
        "prs": request(f"/api/repositories/{repo_id}/pull-requests"),
    }


if __name__ == "__main__":
    mode, output = sys.argv[1:]
    ready()
    if mode == "import":
        payload = {"owner": "octocat", "name": "Hello-World"}
        first = request("/api/repositories/import", payload)
        before = observed(first["id"])
        second = request("/api/repositories/import", payload)
        after = observed(second["id"])
        assert first["id"] == second["id"], "Reimport changed repository identity"
        # GitHub PR metadata can change between requests; commit metrics should remain
        # stable only when the captured head is unchanged.
        if before["coverage"]["headSha"] == after["coverage"]["headSha"]:
            assert before["hotspots"]["items"] == after["hotspots"]["items"]
            assert before["contributors"] == after["contributors"]
        assert after["coverage"]["commitCount"] > 0
        assert after["hotspots"]["items"]
        Path(output).write_text(json.dumps(after), encoding="utf-8")
        print("Live import and duplicate-safe reimport passed")
    elif mode == "verify":
        expected = json.loads(Path(output).read_text(encoding="utf-8"))
        assert observed(expected["id"]) == expected, "Snapshot changed across restart"
        print("Persisted repository, contributors, PRs, and hotspots survived restart")
    else:
        raise SystemExit("Usage: smoke_test.py import|verify snapshot.json")
