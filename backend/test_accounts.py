"""python -m unittest test_accounts   (from backend/; needs fastapi + httpx for the API tests)"""

import os
import tempfile
import unittest

_tmp = tempfile.TemporaryDirectory()
os.environ["BJ_DATA_DIR"] = _tmp.name
os.environ["BUJIRUN_ADMIN_EMAIL"] = "Admin@Example.com"

import accounts  # noqa: E402

accounts.PBKDF2_ITERATIONS = 1_000  # tests don't need slow hashing


class AccountsTest(unittest.TestCase):
    def setUp(self):
        accounts.db_path().unlink(missing_ok=True)
        accounts.init_db()

    def test_signup_and_login(self):
        u = accounts.create_user(" Runner@Example.com ", "달리는  사람", "password1")
        self.assertEqual(u["email"], "runner@example.com")
        self.assertEqual(u["nickname"], "달리는 사람")
        self.assertEqual(accounts.verify_login("RUNNER@example.com", "password1")["id"], u["id"])

    def test_duplicate_email_rejected(self):
        accounts.create_user("a@b.co", "첫번째", "password1")
        with self.assertRaisesRegex(accounts.AccountError, "이미 가입된 이메일"):
            accounts.create_user("A@B.CO", "두번째", "password2")

    def test_duplicate_nickname_rejected_case_insensitively(self):
        accounts.create_user("a@b.co", "Runner", "password1")
        with self.assertRaisesRegex(accounts.AccountError, "닉네임"):
            accounts.create_user("c@d.co", "runner", "password2")

    def test_input_rules(self):
        for email, nick, pw in [("admin", "관리자", "password1"), ("a@b.co", "a", "password1"), ("a@b.co", "닉네임", "short")]:
            with self.assertRaises(accounts.AccountError):
                accounts.create_user(email, nick, pw)

    def test_wrong_password_and_unknown_email_look_the_same(self):
        accounts.create_user("a@b.co", "러너", "password1")
        with self.assertRaises(accounts.AccountError) as wrong:
            accounts.verify_login("a@b.co", "nope-nope")
        with self.assertRaises(accounts.AccountError) as unknown:
            accounts.verify_login("x@y.co", "password1")
        self.assertEqual(str(wrong.exception), str(unknown.exception))

    def test_admin_flag(self):
        admin = accounts.create_user("ADMIN@example.com", "관리자", "password1")
        other = accounts.create_user("a@b.co", "러너", "password1")
        self.assertTrue(accounts.public(admin)["is_admin"])
        self.assertFalse(accounts.public(other)["is_admin"])
        self.assertNotIn("password_hash", accounts.public(admin))

    def test_sessions(self):
        u = accounts.create_user("a@b.co", "러너", "password1")
        token = accounts.create_session(u["id"])
        self.assertEqual(accounts.user_by_token(token)["id"], u["id"])
        accounts.end_session(token)
        self.assertIsNone(accounts.user_by_token(token))

    def test_change_nickname(self):
        a = accounts.create_user("a@x.com", "달리기왕", "password1")
        accounts.create_user("b@x.com", "산책러", "password1")
        self.assertEqual(accounts.change_nickname(a["id"], " 새이름 ")["nickname"], "새이름")
        with self.assertRaises(accounts.AccountError):
            accounts.change_nickname(a["id"], "산책러")
        with self.assertRaises(accounts.AccountError):
            accounts.change_nickname(a["id"], "a")
        # Its own name again is fine.
        self.assertEqual(accounts.change_nickname(a["id"], "새이름")["nickname"], "새이름")

    def test_google_sign_in(self):
        accounts.GOOGLE_CLIENT_ID = "cid"
        try:
            good = {"aud": "cid", "iss": "accounts.google.com", "email": "G@x.com", "email_verified": "true", "name": "설프로"}
            claims = accounts.verify_google_token("t", fetch=lambda url: good)
            u = accounts.google_user(claims)
            self.assertEqual(u["email"], "g@x.com")
            self.assertEqual(u["nickname"], "설프로")
            self.assertFalse(accounts.has_password(u))
            # The same Google account again is the same user; a password login can't get in.
            self.assertEqual(accounts.google_user(claims)["id"], u["id"])
            with self.assertRaises(accounts.AccountError):
                accounts.verify_login("g@x.com", "")
            # Another Google account with the same name gets a number.
            other = accounts.google_user({**good, "email": "h@x.com"})
            self.assertEqual(other["nickname"], "설프로2")
            # Wrong audience or unverified email: refused.
            for bad in ({**good, "aud": "other"}, {**good, "email_verified": "false"}):
                with self.assertRaises(accounts.AccountError):
                    accounts.verify_google_token("t", fetch=lambda url, b=bad: b)
            accounts.delete_by_session(u, None)
            self.assertIsNone(accounts.user_by_token(accounts.create_session(other["id"])) and None)
        finally:
            accounts.GOOGLE_CLIENT_ID = ""

    def test_delete_and_reset(self):
        u = accounts.create_user("a@b.co", "러너", "password1")
        token = accounts.create_session(u["id"])
        self.assertTrue(accounts.admin_reset_password("a@b.co", "newpassword"))
        self.assertIsNone(accounts.user_by_token(token))  # reset signs out everywhere
        accounts.verify_login("a@b.co", "newpassword")
        accounts.delete_user("a@b.co", "newpassword")
        with self.assertRaises(accounts.AccountError):
            accounts.verify_login("a@b.co", "newpassword")
        # The email and nickname are free again.
        accounts.create_user("a@b.co", "러너", "password1")


class ApiTest(unittest.TestCase):
    def setUp(self):
        accounts.db_path().unlink(missing_ok=True)
        accounts.init_db()
        try:
            from fastapi.testclient import TestClient
        except (ImportError, RuntimeError) as e:
            # No HTTP test client installed (newer Starlette raises RuntimeError asking for
            # httpx2). The server doesn't need one to run; skip rather than fail.
            self.skipTest(str(e))
        import server
        self.client = TestClient(server.app)

    def test_flow(self):
        r = self.client.post("/auth/signup", json={"email": "a@b.co", "nickname": "러너", "password": "password1"})
        self.assertEqual(r.status_code, 200)
        token = r.json()["token"]
        self.assertEqual(self.client.get("/auth/me", headers={"Authorization": token}).json()["nickname"], "러너")

        dup = self.client.post("/auth/signup", json={"email": "a@b.co", "nickname": "다른이름", "password": "password1"})
        self.assertEqual(dup.status_code, 400)
        self.assertIn("이미 가입된 이메일", dup.json()["detail"])

        self.assertEqual(self.client.post("/auth/login", json={"email": "a@b.co", "password": "bad-password"}).status_code, 400)
        self.assertEqual(self.client.post("/auth/login", json={"email": "a@b.co", "password": "password1"}).status_code, 200)

        self.client.post("/auth/logout", headers={"Authorization": token})
        self.assertEqual(self.client.get("/auth/me", headers={"Authorization": token}).status_code, 401)


if __name__ == "__main__":
    unittest.main()
