import os
import tempfile
from pathlib import Path

import pytest


@pytest.fixture()
def tmp_db(tmp_path: Path):
    """Return a path to a temporary SQLite database file."""
    return str(tmp_path / "test.db")


@pytest.fixture()
def tmp_config_dir(tmp_path: Path):
    """Return a temporary directory for config files."""
    cfg = tmp_path / "config"
    cfg.mkdir()
    return str(cfg)
