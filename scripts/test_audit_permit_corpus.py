import datetime as dt
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("audit", Path(__file__).with_name("audit-permit-corpus.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class InventoryTest(unittest.TestCase):
    def test_outer_ingest_timestamp_wins_over_nested_file_and_status_date(self):
        root = Path("storage")
        path = root / "processed/2026/09/24/resend_20260923_120000_email_000_old_20260831_090000_email_001.docx"
        self.assertEqual(audit.source_date(path, root), (dt.date(2026, 9, 23), "ingest-filename"))

    def test_attachment_folder_wins_over_nested_original_name(self):
        root = Path("storage")
        path = root / "incoming/email-attachments/20260924/email_1_2_000_old_20260831_100000_email_001.docx"
        self.assertEqual(audit.source_date(path, root), (dt.date(2026, 9, 24), "attachment-folder"))

    def test_folder_dates(self):
        root = Path("storage")
        self.assertEqual(audit.source_date(root / "incoming/email-attachments/20260901/flight-24oct.doc", root),
                         (dt.date(2026, 9, 1), "attachment-folder"))
        self.assertEqual(audit.source_date(root / "error-format/2026/09/25/flight.docx_abcd", root),
                         (dt.date(2026, 9, 25), "status-folder"))

    def test_dedup_boundaries_lock_files_and_hashed_extensions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for state in audit.STATES:
                (root / state).mkdir()
            for name, content in [
                ("incoming/a_20260901_100000_email_000.doc", b"same"),
                ("processed/2026/09/25/a.docx_deadbeef", b"same"),
                ("error/b_20260925_100000_email_000.docx", b"other"),
                ("incoming/b_20260831_100000_email_000.docx", b"excluded"),
                ("incoming/b_20260926_100000_email_000.docx", b"excluded"),
                ("incoming/~$b_20260925_100000_email_000.docx", b"excluded")]:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            result = audit.inventory(root, dt.date(2026, 9, 1), dt.date(2026, 9, 25))
            self.assertEqual(result["physicalFiles"], 3)
            self.assertEqual(len(result["files"]), 2)
            self.assertEqual(sorted(len(f["aliases"]) for f in result["files"]), [1, 2])
            self.assertEqual(result["inventoryErrors"], [])


if __name__ == "__main__":
    unittest.main()
