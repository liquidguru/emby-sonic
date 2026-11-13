import unittest

from starlette.requests import Request

from api.routes import webapp
from config import settings


def _request(peer: str, forwarded: str | None = None) -> Request:
    headers = []
    if forwarded is not None:
        headers.append((b"x-forwarded-for", forwarded.encode()))
    return Request({"type": "http", "headers": headers, "client": (peer, 50000)})


class ClientIpTests(unittest.TestCase):
    """
    The login rate limit keys on _client_ip. X-Forwarded-For is client-written,
    so it must only count when a configured proxy sent the request; otherwise a
    fake address per attempt dodges the lockout.
    """

    def setUp(self) -> None:
        self._trusted = settings.trusted_proxies

    def tearDown(self) -> None:
        settings.trusted_proxies = self._trusted

    def test_header_ignored_when_no_proxy_is_trusted(self) -> None:
        settings.trusted_proxies = ""
        self.assertEqual(webapp._client_ip(_request("203.0.113.9", "1.2.3.4")), "203.0.113.9")

    def test_header_ignored_from_an_untrusted_peer(self) -> None:
        settings.trusted_proxies = "10.0.0.5"
        self.assertEqual(webapp._client_ip(_request("203.0.113.9", "1.2.3.4")), "203.0.113.9")

    def test_trusted_proxy_reports_the_real_client(self) -> None:
        settings.trusted_proxies = "10.0.0.5"
        self.assertEqual(webapp._client_ip(_request("10.0.0.5", "198.51.100.7")), "198.51.100.7")

    def test_spoofed_left_entry_is_not_believed(self) -> None:
        # The client sent "1.2.3.4"; the proxy appended the address it really saw.
        settings.trusted_proxies = "10.0.0.5"
        request = _request("10.0.0.5", "1.2.3.4, 198.51.100.7")
        self.assertEqual(webapp._client_ip(request), "198.51.100.7")

    def test_chained_trusted_proxies_and_cidr(self) -> None:
        settings.trusted_proxies = "10.0.0.0/24, 172.17.0.1"
        request = _request("172.17.0.1", "1.2.3.4, 198.51.100.7, 10.0.0.8")
        self.assertEqual(webapp._client_ip(request), "198.51.100.7")

    def test_invalid_entries_are_skipped(self) -> None:
        settings.trusted_proxies = "not-an-ip, 10.0.0.5"
        self.assertEqual(webapp._client_ip(_request("10.0.0.5", "198.51.100.7")), "198.51.100.7")


if __name__ == "__main__":
    unittest.main()
