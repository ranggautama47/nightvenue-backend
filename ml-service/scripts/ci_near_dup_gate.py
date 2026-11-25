"""CI near-duplicate gate wrapping scripts.check_chat_leakage."""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

APPROVED_PAIR = ("tr-0723", "va-0861")
_PAIR_RE = re.compile(r"^NEAR_DUPLICATE\s+train=(\S+)\s+val=(\S+)\s+cosine=\S+$")


def parse_pairs(stdout: str) -> set[tuple[str, str]]:
    """Parse NEAR_DUPLICATE lines from check_chat_leakage output."""
    pairs: set[tuple[str, str]] = set()
    for raw_line in stdout.splitlines():
        line = raw_line.strip()
        if not line.startswith("NEAR_DUPLICATE"):
            continue
        match = _PAIR_RE.match(line)
        if match is None:
            raise ValueError(f"Unparseable NEAR_DUPLICATE line: {line}")
        pairs.add((match.group(1), match.group(2)))
    return pairs


def decide(exit_code: int, pairs: set[tuple[str, str]]) -> int:
    """Return 0 if clean or only the approved exception; non-zero otherwise."""
    if exit_code == 0 and not pairs:
        return 0
    if exit_code == 1 and pairs == {APPROVED_PAIR}:
        print(
            "APPROVED NEAR-DUPLICATE EXCEPTION: "
            f"{APPROVED_PAIR[0]} / {APPROVED_PAIR[1]} (OUT_OF_SCOPE)"
        )
        return 0
    return 1


def main() -> int:
    ml_service_dir = Path(__file__).resolve().parent.parent
    env = dict(os.environ)
    env["PYTHONPATH"] = str(ml_service_dir)
    cmd = [
        sys.executable,
        "-m",
        "scripts.check_chat_leakage",
        "--train",
        "data/chat/train_v2.jsonl",
        "--val",
        "data/chat/val_accept_b2.jsonl",
        "--near-dup",
    ]
    proc = subprocess.run(
        cmd,
        cwd=str(ml_service_dir),
        env=env,
        capture_output=True,
        text=True,
    )
    if proc.stdout:
        print(proc.stdout, end="")
    if proc.stderr:
        print(proc.stderr, end="", file=sys.stderr)

    try:
        pairs = parse_pairs(proc.stdout)
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    return decide(proc.returncode, pairs)


if __name__ == "__main__":
    raise SystemExit(main())
