import unittest

from fastapi.testclient import TestClient

from main import app


class SecurityHeadersTests(unittest.TestCase):
    def test_referrer_policy_no_referrer_is_present(self) -> None:
        response = TestClient(app).get("/openapi.json")
        self.assertEqual(response.headers.get("Referrer-Policy"), "no-referrer")

    def test_content_security_policy_baseline_is_present(self) -> None:
        response = TestClient(app).get("/openapi.json")
        csp = response.headers.get("Content-Security-Policy", "")
        self.assertIn("default-src 'self'", csp)
        self.assertIn("script-src 'self'", csp)
        self.assertIn("object-src 'none'", csp)
        self.assertIn("base-uri 'self'", csp)
        self.assertIn("connect-src *", csp)
        self.assertIn("img-src * data:", csp)
        self.assertIn("media-src * blob:", csp)


if __name__ == "__main__":
    unittest.main()
