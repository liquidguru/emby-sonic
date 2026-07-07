import unittest

from fastapi.testclient import TestClient

from main import app


class SecurityHeadersTests(unittest.TestCase):
    def test_referrer_policy_no_referrer_is_present(self) -> None:
        response = TestClient(app).get("/openapi.json")
        self.assertEqual(response.headers.get("Referrer-Policy"), "no-referrer")


if __name__ == "__main__":
    unittest.main()
