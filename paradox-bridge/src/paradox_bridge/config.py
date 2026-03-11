"""Configuration loading and first-run setup."""

import json
import secrets
from dataclasses import dataclass
from pathlib import Path


_DEFAULTS = {
    "serial_port": "/dev/serial0",
    "serial_baud": 9600,
    "api_port": 8080,
    "api_host": "0.0.0.0",
    "jwt_expiry_hours": 720,  # 30 days
    "panel_pc_password": "0000",
    "invite_expiry_seconds": 900,  # 15 min
    "tls_cert_path": "",
    "tls_key_path": "",
    "demo_mode": False,
}


@dataclass
class AppConfig:
    serial_port: str
    serial_baud: int
    api_port: int
    api_host: str
    jwt_secret: str
    jwt_expiry_hours: int
    panel_pc_password: str
    invite_expiry_seconds: int
    config_path: str
    tls_cert_path: str = ""
    tls_key_path: str = ""
    demo_mode: bool = False

    @property
    def db_path(self) -> str:
        return str(Path(self.config_path).parent / "paradox-bridge.db")

    @property
    def tls_enabled(self) -> bool:
        return bool(self.tls_cert_path and self.tls_key_path)


def generate_default_config(path: str) -> None:
    config_dir = Path(path).parent
    config_dir.mkdir(parents=True, exist_ok=True)
    cert_path = str(config_dir / "server.crt")
    key_path = str(config_dir / "server.key")
    data = {
        **_DEFAULTS,
        "jwt_secret": secrets.token_hex(32),
        "tls_cert_path": cert_path,
        "tls_key_path": key_path,
    }
    with open(path, "w") as f:
        json.dump(data, f, indent=2)


def load_config(path: str) -> AppConfig:
    p = Path(path)
    if not p.exists():
        generate_default_config(path)
    with open(p) as f:
        data = json.load(f)
    return AppConfig(
        serial_port=data["serial_port"],
        serial_baud=data["serial_baud"],
        api_port=data["api_port"],
        api_host=data["api_host"],
        jwt_secret=data["jwt_secret"],
        jwt_expiry_hours=data["jwt_expiry_hours"],
        panel_pc_password=data["panel_pc_password"],
        invite_expiry_seconds=data["invite_expiry_seconds"],
        config_path=path,
        tls_cert_path=data.get("tls_cert_path", ""),
        tls_key_path=data.get("tls_key_path", ""),
        demo_mode=data.get("demo_mode", False),
    )
