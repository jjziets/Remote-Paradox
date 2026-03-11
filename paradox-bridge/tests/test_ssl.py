"""Tests for self-signed TLS certificate generation and fingerprinting."""

import ssl
from pathlib import Path

import pytest

from paradox_bridge.tls import generate_self_signed_cert, get_cert_fingerprint


class TestGenerateSelfSignedCert:
    def test_creates_cert_and_key_files(self, tmp_path):
        cert_path = tmp_path / "server.crt"
        key_path = tmp_path / "server.key"
        generate_self_signed_cert(str(cert_path), str(key_path), hostname="remote-paradox")
        assert cert_path.exists()
        assert key_path.exists()

    def test_cert_is_valid_pem(self, tmp_path):
        cert_path = tmp_path / "server.crt"
        key_path = tmp_path / "server.key"
        generate_self_signed_cert(str(cert_path), str(key_path), hostname="remote-paradox")
        content = cert_path.read_text()
        assert "-----BEGIN CERTIFICATE-----" in content
        assert "-----END CERTIFICATE-----" in content

    def test_key_is_valid_pem(self, tmp_path):
        cert_path = tmp_path / "server.crt"
        key_path = tmp_path / "server.key"
        generate_self_signed_cert(str(cert_path), str(key_path), hostname="remote-paradox")
        content = key_path.read_text()
        assert "-----BEGIN" in content
        assert "PRIVATE KEY" in content

    def test_cert_matches_key(self, tmp_path):
        cert_path = tmp_path / "server.crt"
        key_path = tmp_path / "server.key"
        generate_self_signed_cert(str(cert_path), str(key_path), hostname="remote-paradox")
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.load_cert_chain(str(cert_path), str(key_path))

    def test_creates_parent_directories(self, tmp_path):
        cert_path = tmp_path / "certs" / "sub" / "server.crt"
        key_path = tmp_path / "certs" / "sub" / "server.key"
        generate_self_signed_cert(str(cert_path), str(key_path), hostname="remote-paradox")
        assert cert_path.exists()

    def test_includes_san_for_hostname(self, tmp_path):
        """The cert should include the hostname as a SAN (Subject Alternative Name)."""
        cert_path = tmp_path / "server.crt"
        key_path = tmp_path / "server.key"
        generate_self_signed_cert(str(cert_path), str(key_path), hostname="remote-paradox")
        from cryptography import x509
        from cryptography.x509.oid import ExtensionOID

        cert_pem = cert_path.read_bytes()
        cert = x509.load_pem_x509_certificate(cert_pem)
        san = cert.extensions.get_extension_for_oid(ExtensionOID.SUBJECT_ALTERNATIVE_NAME)
        dns_names = san.value.get_values_for_type(x509.DNSName)
        assert "remote-paradox" in dns_names

    def test_includes_ip_san(self, tmp_path):
        """When an IP is provided, the cert should include it as an IP SAN."""
        cert_path = tmp_path / "server.crt"
        key_path = tmp_path / "server.key"
        generate_self_signed_cert(
            str(cert_path), str(key_path),
            hostname="remote-paradox",
            ip_addresses=["192.168.50.32"],
        )
        from cryptography import x509
        from cryptography.x509.oid import ExtensionOID
        import ipaddress

        cert_pem = cert_path.read_bytes()
        cert = x509.load_pem_x509_certificate(cert_pem)
        san = cert.extensions.get_extension_for_oid(ExtensionOID.SUBJECT_ALTERNATIVE_NAME)
        ips = san.value.get_values_for_type(x509.IPAddress)
        assert ipaddress.IPv4Address("192.168.50.32") in ips


class TestGetCertFingerprint:
    def test_returns_sha256_hex(self, tmp_path):
        cert_path = tmp_path / "server.crt"
        key_path = tmp_path / "server.key"
        generate_self_signed_cert(str(cert_path), str(key_path), hostname="test")
        fp = get_cert_fingerprint(str(cert_path))
        assert isinstance(fp, str)
        assert len(fp) == 64  # SHA-256 = 32 bytes = 64 hex chars
        assert all(c in "0123456789abcdef" for c in fp)

    def test_fingerprint_is_stable(self, tmp_path):
        cert_path = tmp_path / "server.crt"
        key_path = tmp_path / "server.key"
        generate_self_signed_cert(str(cert_path), str(key_path), hostname="test")
        fp1 = get_cert_fingerprint(str(cert_path))
        fp2 = get_cert_fingerprint(str(cert_path))
        assert fp1 == fp2

    def test_different_certs_have_different_fingerprints(self, tmp_path):
        c1 = tmp_path / "a.crt"
        k1 = tmp_path / "a.key"
        c2 = tmp_path / "b.crt"
        k2 = tmp_path / "b.key"
        generate_self_signed_cert(str(c1), str(k1), hostname="a")
        generate_self_signed_cert(str(c2), str(k2), hostname="b")
        assert get_cert_fingerprint(str(c1)) != get_cert_fingerprint(str(c2))
