import os
import unittest

import privacy


class PrivacyTest(unittest.TestCase):
    def test_page_has_both_languages_and_the_contact_from_the_server(self):
        os.environ["BUJIRUN_ADMIN_EMAIL"] = "someone@example.com"
        try:
            page = privacy.page()
        finally:
            del os.environ["BUJIRUN_ADMIN_EMAIL"]
        self.assertIn("개인정보처리방침", page)
        self.assertIn("Privacy Policy", page)
        self.assertIn("mailto:someone@example.com", page)
        self.assertIn("계정 삭제", page)

    def test_no_contact_configured(self):
        os.environ.pop("BUJIRUN_ADMIN_EMAIL", None)
        self.assertNotIn("mailto:", privacy.page())


if __name__ == "__main__":
    unittest.main()
