"""Bounded manual evaluation. No network activity unless --execute-approved is supplied."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
MODEL = "gemini-3.8-flash"
ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
DATASET = ROOT / "src/test/resources/evaluation/cases.json"
BASELINE = ROOT / "src/test/resources/evaluation/runs/2026-10-27-gemini-3.8-flash"
RUBRIC = {
    "executionConsistency": "No statement contradicts actual compilation, execution or expected/actual values.",
    "citationValidity": "Every cited ID exists in the current request; no AST evidence means not applicable.",
    "evidenceInterpretation": "Claims are supported by code or execution, not merely an existing ID.",
    "earlyDisclosure": "For failing submissions, evidence and hint 1 must not give the corrected token, condition or complete solution. Raw javac diagnostics are allowed as evidence; restating their fix in hint 1 fails. Borderline disclosures are uncertain, not pass.",
    "falseWarning": "On passing submissions, do not invent a defect; optional simplification is not a defect warning.",
    "resubmissionComparison": "For dependent cases, explicitly connect old/current code and test changes; partial-fix must retain the mixed failure. Unknown changes must remain unknown.",
    "caseRequirements": "Also check each case's required and forbidden statements. Keep separate per-axis labels: pass, fail, uncertain, not_applicable.",
}


def read_json(path):
    return json.loads(path.read_text())


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute-approved", action="store_true")
    parser.add_argument("--output", default="reports/evaluation/2026-10-27-repeated")
    args = parser.parse_args()
    dataset = read_json(DATASET)
    cases = dataset["cases"]
    assert dataset["version"] == 2 and len(cases) == 8
    by_id = {case["id"]: case for case in cases}
    assert len(by_id) == 8
    templates = {}
    for case in cases:
        templates[case["id"]] = read_json(ROOT / f"build/evaluation-preview/{case['id']}.request.json")
        template = templates[case["id"]]
        assert template["model"] == MODEL and template["store"] is False
        assert "재제출 비교:" in template["system_instruction"]
        assert case["source"] in template["input"]
        for criterion in case["required"] + case["forbidden"]:
            assert criterion not in template["input"]
    instructions = {
        "v3": read_json(BASELINE / "missing-last.request.json")["system_instruction"],
        "v4": templates["missing-last"]["system_instruction"],
    }
    assert "재제출 비교:" not in instructions["v3"]
    assert all(t["system_instruction"] == instructions["v4"] for t in templates.values())
    order = [(trial, version) for trial in range(1, 4)
             for version in (("v3", "v4") if trial != 2 else ("v4", "v3"))]
    plan = {
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "runnerSha256": digest(Path(__file__)), "datasetSha256": digest(DATASET),
        "datasetVersion": 2, "model": MODEL, "maxCalls": 48, "maxOutputTokens": 2048,
        "retryCount": 0, "timeoutSeconds": 30, "rubric": RUBRIC,
        "runOrder": [f"{version}-trial-{trial}" for trial, version in order],
        "templateSha256": {cid: digest(ROOT / f"build/evaluation-preview/{cid}.request.json") for cid in by_id},
        "instructionSha256": {v: hashlib.sha256(s.encode()).hexdigest() for v, s in instructions.items()},
        "limits": "Same small sum problem; three trials are not population-level reliability. Dependent cases use each version's first response, so their context differs. No independent human grader.",
    }
    if not args.execute_approved:
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return
    config = {}
    env_file = ROOT / ".env"
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            name, value = line.removeprefix("export ").split("=", 1)
            if name.strip() == "GEMINI_API_KEY":
                tokens = shlex.split(value, comments=True)
                config[name.strip()] = tokens[0] if tokens else ""
    key = os.environ.get("GEMINI_API_KEY") or config.get("GEMINI_API_KEY")
    assert key, "Missing Gemini key"
    output = ROOT / args.output
    output.mkdir(parents=True, exist_ok=False)  # Never overwrite or resume paid runs implicitly.
    write_json(output / "plan.json", plan)
    write_json(output / "cases.json", dataset)
    calls = 0
    for trial, version in order:
        run = output / f"{version}-trial-{trial}"
        run.mkdir()
        manifest = {**plan, "promptVersion": f"review-{version}", "trial": trial,
                    "orchestration": "Python runner, Kotlin-generated request preview; scripted resubmission, not full CLI demo.", "calls": []}
        prior = {}
        for case in cases:
            cid = case["id"]
            item = {"caseId": cid, "sourceSha256": hashlib.sha256(case["source"].encode()).hexdigest()}
            manifest["calls"].append(item)
            body = json.loads(json.dumps(templates[cid]))
            body["system_instruction"] = instructions[version]
            body["generation_config"] = {"max_output_tokens": 2048}
            previous = case["previousCaseId"]
            if previous:
                if previous not in prior:
                    item.update(status="SKIPPED", reason="Previous valid response unavailable")
                    write_json(run / "manifest.json", manifest)
                    continue
                review = prior[previous]
                old = by_id[previous]
                hint = review["hints"][0]
                write_json(run / f"{cid}.hint-viewed.json", {"previousCaseId": previous, "hintLevel": 1,
                           "hint": hint, "presentation": "Scripted simulated learner; no human learning claim"})
                print(f"HINT {version}/{trial}/{cid}: {hint}", flush=True)
                context = ("직전 제출 결과 (현재 판정을 대체하지 않는 참고 정보):\n"
                           f"attemptId: evaluation-{previous}\ncompile: {old['expectedCompilation']}\n"
                           f"tests: FAILED {old['expectedPassedCount']}/{old['expectedTestCount']}\n"
                           "hintViewed: true, hintLevel: 1\nprior review evidence: " + "; ".join(review["evidence"]) +
                           "\nprior hints shown to learner: " + hint + "\n직전 제출 코드:\n```java\n" + old["source"] + "\n```\n")
                position = body["input"].find("정적 분석 근거 (")
                if position < 0:
                    position = body["input"].find("제출 코드:")
                assert position >= 0
                body["input"] = body["input"][:position] + context + body["input"][position:]
            encoded = json.dumps(body, ensure_ascii=False).encode()
            assert key.encode() not in encoded and b"/Users/" not in encoded
            write_json(run / f"{cid}.request.json", body)
            item["requestBytes"] = len(encoded)
            write_json(run / "manifest.json", manifest)
            assert calls < 48
            calls += 1
            started = time.monotonic()
            request = urllib.request.Request(ENDPOINT, data=encoded,
                       headers={"Content-Type": "application/json", "x-goog-api-key": key}, method="POST")
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    raw = response.read()
                    item["httpStatus"] = response.status
            except urllib.error.HTTPError as error:
                raw = error.read()
                item["httpStatus"] = error.code
            except Exception as error:
                raw = b""
                item.update(status="TRANSPORT_ERROR", errorType=type(error).__name__)
            item["elapsedMs"] = round((time.monotonic() - started) * 1000)
            raw = raw.replace(key.encode(), b"[REDACTED]")
            (run / f"{cid}.response.json").write_bytes(raw)
            if raw:
                try:
                    response = json.loads(raw)
                    item.update(usage=response.get("usage"), responseStatus=response.get("status"))
                    if item.get("httpStatus") != 200:
                        item["status"] = "HTTP_ERROR"
                    else:
                        text = "".join(c["text"] for step in response.get("steps", []) if step.get("type") == "model_output"
                                       for c in step.get("content", []) if c.get("type") == "text")
                        review = json.loads(text)
                        write_json(run / f"{cid}.review.json", review)
                        for field in ("relatedSkills", "evidence", "hints", "citedEvidenceIds"):
                            assert isinstance(review[field], list) and all(isinstance(x, str) for x in review[field])
                        assert len(review["hints"]) == 3 and all(review["hints"])
                        assert review["relatedSkills"] and review["evidence"]
                        ids = set(re.findall(r"^- (ev-[^:]+):", body["input"], re.MULTILINE))
                        assert set(review["citedEvidenceIds"]) <= ids
                        assert response.get("status") == "completed"
                        item["status"] = "RECEIVED"
                        prior[cid] = review
                except Exception as error:
                    item.update(status="PARSE_OR_VALIDATION_ERROR", errorType=type(error).__name__)
            write_json(run / "manifest.json", manifest)
            print(json.dumps({"call": calls, "run": run.name, **item}, ensure_ascii=False), flush=True)
    print(f"FINISHED calls={calls}, output={output}", flush=True)


if __name__ == "__main__":
    main()
