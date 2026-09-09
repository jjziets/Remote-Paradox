"""Start the loopback API; nginx terminates TLS using the existing Pi certificate."""
import os

import uvicorn

from paradox_bridge.config import load_config


if __name__ == "__main__":
    config = load_config(os.environ.get("PARADOX_CONFIG", "/etc/paradox-bridge/config.json"))
    uvicorn.run("paradox_bridge.main:app", host=config.api_host, port=config.api_port)
