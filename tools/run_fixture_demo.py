"""Run the installed CLI with fixture reviews in a new temporary workspace. No API calls."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "build/install/resubmit/bin/resubmit"
CORRECT_SOURCE = """public class Solution {
    public int solution(int[] numbers, int target) {
        return count(numbers, target, 0, 0);
    }
    private int count(int[] numbers, int target, int index, int sum) {
        if (index == numbers.length) return sum == target ? 1 : 0;
        return count(numbers, target, index + 1, sum + numbers[index])
            + count(numbers, target, index + 1, sum - numbers[index]);
    }
}
"""


def main():
    if not APP.is_file():
        raise SystemExit("Run ./gradlew installDist first")
    workspace = Path(tempfile.mkdtemp(prefix="resubmit-cli-demo-"))
    local = workspace / ".resubmit"
    local.mkdir()
    # Synthetic profile skips onboarding; it is not the user's personal learning record.
    (local / "profile.json").write_text(json.dumps({
        "schemaVersion": 2, "programmingLanguage": "Java", "codingExperience": "UNSURE",
        "learningGoal": "Fixture CLI demonstration", "claimedSkillExperience": {},
        "recordedAt": "2026-10-27T00:00:00Z",
    }))
    environment = {k: v for k, v in os.environ.items() if k in
                   ("PATH", "JAVA_HOME", "HOME", "LANG", "LC_ALL", "TMPDIR")}
    environment.update(RESUBMIT_REVIEW_PROVIDER="fixture", RESUBMIT_NO_TUI="1", NO_COLOR="1",
                       TERM="dumb", COLUMNS="240", RESUBMIT_EDITOR="", EDITOR="")
    process = subprocess.Popen([str(APP)], cwd=workspace, env=environment, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    transcript = []
    changed = threading.Condition()

    def read_output():
        while True:
            char = process.stdout.read(1)
            if not char:
                break
            with changed:
                transcript.append(char)
                changed.notify_all()

    thread = threading.Thread(target=read_output, daemon=True)
    thread.start()

    def wait_for(marker):
        deadline = time.monotonic() + 30
        with changed:
            while marker not in "".join(transcript):
                remaining = deadline - time.monotonic()
                if remaining <= 0 or process.poll() is not None:
                    raise RuntimeError(f"CLI did not reach {marker!r}; workspace retained at {workspace}")
                changed.wait(min(remaining, 0.2))

    def send(value):
        process.stdin.write(value + "\n")
        process.stdin.flush()

    try:
        wait_for("번호 선택")
        for command in ("1", "5", "부호 선택 합계", "1", "1"):
            send(command)
        wait_for("제출 준비")
        solutions = list((local / "sessions").glob("*/Solution.java"))
        assert len(solutions) == 1
        # Deliberate error: both recursive branches add, matching this fixture's hint topic.
        solutions[0].write_text(CORRECT_SOURCE.replace("sum - numbers[index]", "sum + numbers[index]"))
        send("")
        wait_for("1 / 4 PASS")
        wait_for("[h] 1단계 힌트")
        for level in range(1, 4):
            send("h")
            wait_for(f"HINT {level}/3")
        solutions[0].write_text(CORRECT_SOURCE)
        send("e")
        wait_for("재제출 준비")
        send("")
        wait_for("4 / 4 PASS")
        wait_for("[Enter] 다음 문제")
        send("q")
        exit_code = process.wait(timeout=10)
        thread.join(timeout=2)
        assert exit_code == 0
        records = [json.loads(line) for line in (local / "history/attempts.jsonl").read_text().splitlines()]
        assert len({r["attemptId"] for r in records}) == 2
        assert records[-1]["tests"]["status"] == "PASSED"
        assert max(r["review"].get("hintLevel") or 0 for r in records) == 3
        assert all(r["review"].get("provider") in (None, "fixture") for r in records)
        output = ROOT / "build/cli-demo"
        output.mkdir(parents=True, exist_ok=True)
        text = "".join(transcript)
        for path in sorted({str(workspace), str(workspace.resolve())}, key=len, reverse=True):
            text = text.replace(path, "$DEMO_WORKSPACE")
        (output / "transcript.txt").write_text(text)
        summary = {"provider": "fixture", "apiCalls": 0, "exitCode": exit_code,
                   "attempts": 2, "firstPassedCount": records[0]["tests"]["passedCount"],
                   "lastPassedCount": records[-1]["tests"]["passedCount"], "testCount": 4,
                   "maxHintLevelViewed": 3, "syntheticProfile": True,
                   "scriptedEdit": True, "workspace": str(workspace)}
        (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)


if __name__ == "__main__":
    main()
