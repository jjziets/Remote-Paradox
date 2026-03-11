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
    public_host: str = ""
    public_port: int = 0

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
    merged = {**_DEFAULTS, **data}
    if "jwt_secret" not in merged:
        merged["jwt_secret"] = secrets.token_hex(32)
    return AppConfig(
        serial_port=merged["serial_port"],
        serial_baud=merged["serial_baud"],
        api_port=merged["api_port"],
        api_host=merged["api_host"],
        jwt_secret=merged["jwt_secret"],
        jwt_expiry_hours=merged["jwt_expiry_hours"],
        panel_pc_password=merged["panel_pc_password"],
        invite_expiry_seconds=merged["invite_expiry_seconds"],
        config_path=path,
        tls_cert_path=merged.get("tls_cert_path", ""),
        tls_key_path=merged.get("tls_key_path", ""),
        demo_mode=merged.get("demo_mode", False),
        public_host=merged.get("public_host", ""),
        public_port=merged.get("public_port", 0),
    )
