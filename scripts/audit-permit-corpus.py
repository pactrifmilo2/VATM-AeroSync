"""Read-only inventory of local Word permits; no database or source-file writes.

python scripts/audit-permit-corpus.py --storage D:/vatm-storage --start 2026-09-01 \
    --end 2026-09-25 --output .tmp/permit-audit-25092026
Then run PermitCorpusAuditTest with -Dpermit.audit.manifest=<output>/manifest.json
and -Dpermit.audit.output=<output>. The test exports the workbook via Apache POI.
"""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re

STATES = ("incoming", "processed", "error", "quarantine", "error-format")
WORD = re.compile(r"\.docx?(?:_[a-f0-9]+)?$", re.I)


def source_date(path, root):
    """Prefer the OUTER ingest name; never confuse flight dates with receipt dates."""
    relative = path.relative_to(root)
    parts = relative.parts
    if len(parts) > 2 and parts[:2] == ("incoming", "email-attachments"):
        try:
            return dt.datetime.strptime(parts[2], "%Y%m%d").date(), "attachment-folder"
        except ValueError:
            pass
    match = re.search(r"_(20\d{6})_\d{6}_email_", path.name)
    if match:
        try:
            return dt.datetime.strptime(match[1], "%Y%m%d").date(), "ingest-filename"
        except ValueError:
            pass
    if len(parts) > 3 and parts[0] in STATES:
        try:
            return dt.date(int(parts[1]), int(parts[2]), int(parts[3])), "status-folder"
        except ValueError:
            pass
    return dt.datetime.fromtimestamp(path.stat().st_mtime).date(), "mtime-fallback"


def inventory(root, start, end):
    unique = {}
    errors = []
    count = 0
    for state in STATES:
        folder = root / state
        if not folder.is_dir():
            errors.append({"path": str(folder), "error": "Storage state folder missing"})
            continue
        for path in sorted(folder.rglob("*")):
            if not path.is_file() or path.name.startswith("~$") or not WORD.search(path.name):
                continue
            try:
                day, basis = source_date(path, root)
                if not start <= day <= end:
                    continue
                with path.open("rb") as stream:
                    digest = hashlib.file_digest(stream, "sha256").hexdigest()
                alias = {"path": str(path.resolve()), "date": day.isoformat(), "dateBasis": basis,
                         "state": state}
                row = unique.setdefault(digest, {"sha256": digest, "path": alias["path"], "aliases": []})
                row["aliases"].append(alias)
                count += 1
            except OSError as exc:
                errors.append({"path": str(path), "error": str(exc)})
    return {"start": start.isoformat(), "end": end.isoformat(), "root": str(root.resolve()),
            "snapshot": dt.datetime.now().astimezone().isoformat(), "physicalFiles": count,
            "files": list(unique.values()), "inventoryErrors": errors}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--storage", required=True, type=Path)
    ap.add_argument("--start", required=True, type=dt.date.fromisoformat)
    ap.add_argument("--end", required=True, type=dt.date.fromisoformat)
    ap.add_argument("--output", required=True, type=Path)
    args = ap.parse_args()
    if not args.storage.is_dir() or args.start > args.end:
        ap.error("Existing storage root and start <= end required")
    if args.output.resolve().is_relative_to(args.storage.resolve()):
        ap.error("Output must be outside source storage")
    result = inventory(args.storage, args.start, args.end)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "manifest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "files"}, ensure_ascii=False))
    print("Unique SHA-256:", len(result["files"]))


if __name__ == "__main__":
    main()
