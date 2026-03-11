"""Tests for configuration loading and first-run setup."""

import json
from pathlib import Path

import pytest

from paradox_bridge.config import AppConfig, load_config, generate_default_config


class TestGenerateDefaultConfig:
    def test_creates_config_file(self, tmp_config_dir):
        path = Path(tmp_config_dir) / "config.json"
        generate_default_config(str(path))
        assert path.exists()

    def test_generated_config_has_jwt_secret(self, tmp_config_dir):
        path = Path(tmp_config_dir) / "config.json"
        generate_default_config(str(path))
        with open(path) as f:
            data = json.load(f)
        assert "jwt_secret" in data
        assert len(data["jwt_secret"]) >= 32

    def test_generated_jwt_secret_is_unique(self, tmp_config_dir):
        p1 = Path(tmp_config_dir) / "c1.json"
        p2 = Path(tmp_config_dir) / "c2.json"
        generate_default_config(str(p1))
        generate_default_config(str(p2))
        with open(p1) as f:
            s1 = json.load(f)["jwt_secret"]
        with open(p2) as f:
            s2 = json.load(f)["jwt_secret"]
        assert s1 != s2

    def test_generated_config_has_defaults(self, tmp_config_dir):
        path = Path(tmp_config_dir) / "config.json"
        generate_default_config(str(path))
        with open(path) as f:
            data = json.load(f)
        assert data["serial_port"] == "/dev/serial0"
        assert data["serial_baud"] == 9600
        assert data["api_port"] == 8080
        assert data["api_host"] == "0.0.0.0"
        assert data["jwt_expiry_hours"] == 720
        assert data["panel_pc_password"] == "0000"
        assert data["invite_expiry_seconds"] == 900


class TestLoadConfig:
    def test_load_existing_config(self, tmp_config_dir):
        path = Path(tmp_config_dir) / "config.json"
        generate_default_config(str(path))
        cfg = load_config(str(path))
        assert isinstance(cfg, AppConfig)
        assert cfg.serial_port == "/dev/serial0"
        assert cfg.api_port == 8080

    def test_load_creates_config_if_missing(self, tmp_config_dir):
        path = Path(tmp_config_dir) / "config.json"
        assert not path.exists()
        cfg = load_config(str(path))
        assert isinstance(cfg, AppConfig)
        assert path.exists()

    def test_custom_values_preserved(self, tmp_config_dir):
        path = Path(tmp_config_dir) / "config.json"
        with open(path, "w") as f:
            json.dump({
                "serial_port": "/dev/ttyUSB0",
                "serial_baud": 9600,
                "api_port": 9090,
                "api_host": "0.0.0.0",
                "jwt_secret": "mysecret",
                "jwt_expiry_hours": 48,
                "panel_pc_password": "1234",
                "invite_expiry_seconds": 600,
            }, f)
        cfg = load_config(str(path))
        assert cfg.serial_port == "/dev/ttyUSB0"
        assert cfg.api_port == 9090
        assert cfg.jwt_secret == "mysecret"
        assert cfg.jwt_expiry_hours == 48
        assert cfg.panel_pc_password == "1234"


class TestAppConfig:
    def test_db_path_derived_from_config_dir(self, tmp_config_dir):
        path = Path(tmp_config_dir) / "config.json"
        cfg = load_config(str(path))
        assert cfg.db_path == str(Path(tmp_config_dir) / "paradox-bridge.db")
