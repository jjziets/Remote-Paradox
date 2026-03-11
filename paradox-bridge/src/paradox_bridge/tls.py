"""Self-signed TLS certificate generation and fingerprinting."""

import datetime
import hashlib
import ipaddress
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID


def generate_self_signed_cert(
    cert_path: str,
    key_path: str,
    hostname: str = "remote-paradox",
    ip_addresses: list[str] | None = None,
    days_valid: int = 3650,
) -> None:
    """Generate a self-signed ECDSA cert + key pair.

    Uses P-256 (SECP256R1) for small cert size and fast TLS handshakes
    on the Pi Zero 2 W.
    """
    Path(cert_path).parent.mkdir(parents=True, exist_ok=True)
    Path(key_path).parent.mkdir(parents=True, exist_ok=True)

    key = ec.generate_private_key(ec.SECP256R1())

    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, hostname),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Remote Paradox"),
    ])

    san_entries: list[x509.GeneralName] = [
        x509.DNSName(hostname),
        x509.DNSName("localhost"),
        x509.IPAddress(ipaddress.IPv4Address("127.0.0.1")),
    ]
    for ip_str in ip_addresses or []:
        san_entries.append(x509.IPAddress(ipaddress.ip_address(ip_str)))

    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now)
        .not_valid_after(now + datetime.timedelta(days=days_valid))
        .add_extension(
            x509.SubjectAlternativeName(san_entries),
            critical=False,
        )
        .add_extension(
            x509.BasicConstraints(ca=False, path_length=None),
            critical=True,
        )
        .sign(key, hashes.SHA256())
    )

    with open(key_path, "wb") as f:
        f.write(key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        ))

    with open(cert_path, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))


def get_cert_fingerprint(cert_path: str) -> str:
    """Return the lowercase hex SHA-256 fingerprint of a PEM certificate."""
    cert_pem = Path(cert_path).read_bytes()
    cert = x509.load_pem_x509_certificate(cert_pem)
    digest = cert.fingerprint(hashes.SHA256())
    return digest.hex()
