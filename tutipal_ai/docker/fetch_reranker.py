"""PT-86 Docker build stage only: download pinned Hugging Face files and verify sha256.

Usage: python fetch_reranker.py <repo> <revision> <dest_dir> <remote_path>=<local_name>=<sha256> ...
Any size/hash mismatch or HTTP error exits non-zero, so the image build fails instead of shipping a wrong model.
"""
import hashlib
import sys
import urllib.request
from pathlib import Path

CHUNK = 1024 * 1024


def fetch(repo: str, revision: str, remote: str, target: Path, expected: str) -> None:
    url = f"https://huggingface.co/{repo}/resolve/{revision}/{remote}"
    digest = hashlib.sha256()
    partial = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(url, timeout=60) as response, partial.open("wb") as out:
        while block := response.read(CHUNK):
            digest.update(block)
            out.write(block)
    actual = digest.hexdigest()
    if actual != expected:
        partial.unlink()
        raise SystemExit(f"sha256 mismatch for {remote}: expected {expected}, got {actual}")
    partial.rename(target)
    print(f"verified {remote} -> {target.name} sha256={actual}")


def main(argv: list[str]) -> None:
    if len(argv) < 4:
        raise SystemExit(__doc__)
    repo, revision, dest = argv[0], argv[1], Path(argv[2])
    if len(revision) != 40:
        raise SystemExit("revision must be a full 40-character commit hash")
    dest.mkdir(parents=True, exist_ok=True)
    for spec in argv[3:]:
        remote, local, expected = spec.split("=")
        fetch(repo, revision, remote, dest / local, expected.lower())


if __name__ == "__main__":
    main(sys.argv[1:])
