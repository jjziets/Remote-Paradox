# install/

Everything needed to build a **Remote Paradox** node on a fresh Raspberry Pi.

- **[INSTALL.md](INSTALL.md)** — step-by-step from-scratch build guide.
- **[config.json.example](config.json.example)** — bridge config template (copy to `/etc/paradox-bridge/config.json` and fill in your own secrets).
- **[systemd/](systemd/)** — unit templates for the core services
  (`paradox-bridge`, `paradox-ble`, `paradox-state-recorder`, `paradox-updater` + timer).
  The watchdog, zram, Wi-Fi watchdog, boot-fsck, and boot-repair units are created
  by the scripts in [`../paradox-bridge/deploy/`](../paradox-bridge/deploy/).

> These files are **placeholders only** — no real credentials, hosts, or keys.
> Keep your filled-in `.env`, `config.json`, and TLS keys **out of git**.
