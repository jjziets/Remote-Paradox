"""CLI tool for toggling zones in demo mode via SSH.

Usage (on the Pi):
    paradox-demo list               — show all zones
    paradox-demo open  <zone_id>    — trigger (open) a zone
    paradox-demo close <zone_id>    — close a zone
    paradox-demo bypass <zone_id>   — bypass a zone
    paradox-demo unbypass <zone_id> — un-bypass a zone
    paradox-demo history [--limit N]— show zone event history
    paradox-demo status             — show arm state per partition
"""

import argparse
import json
import os
import sys

import urllib.request
import urllib.error
import ssl


def _api_base() -> str:
    port = os.environ.get("PARADOX_API_PORT", "8080")
    return f"https://127.0.0.1:{port}"


def _ssl_context() -> ssl.SSLContext:
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def _request(method: str, path: str, body: dict | None = None) -> dict:
    url = f"{_api_base()}{path}"
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(url, data=data, method=method)
    if body:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, context=_ssl_context()) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        detail = e.read().decode()
        print(f"HTTP {e.code}: {detail}", file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"Connection error: {e.reason}", file=sys.stderr)
        print("Is the paradox-bridge service running?", file=sys.stderr)
        sys.exit(1)


def cmd_list(_args: argparse.Namespace) -> None:
    data = _request("GET", "/alarm/status")
    if not data.get("connected"):
        print("Alarm not connected.")
        return
    for part in data["partitions"]:
        print(f"\n{'='*50}")
        print(f"  {part['name']} (partition {part['id']})  —  {part['mode'].upper()}")
        print(f"{'='*50}")
        for z in part["zones"]:
            flags = []
            if z["open"]:
                flags.append("OPEN")
            if z.get("bypassed"):
                flags.append("BYPASSED")
            flag_str = f"  [{', '.join(flags)}]" if flags else ""
            print(f"  {z['id']:>3}  {z['name']:<25}{flag_str}")
    print()


def cmd_open(args: argparse.Namespace) -> None:
    result = _request("POST", "/alarm/zone-toggle", {"zone_id": args.zone_id, "open": True})
    print(f"Zone {args.zone_id}: {result.get('action', 'done')}")


def cmd_close(args: argparse.Namespace) -> None:
    result = _request("POST", "/alarm/zone-toggle", {"zone_id": args.zone_id, "open": False})
    print(f"Zone {args.zone_id}: {result.get('action', 'done')}")


def cmd_bypass(args: argparse.Namespace) -> None:
    _request("POST", "/alarm/zone-toggle", {"zone_id": args.zone_id, "open": False})
    print(f"Note: bypass requires auth — use the API directly or the app.")
    print(f"For quick debug, use 'open'/'close' to simulate zone state.")


def cmd_history(args: argparse.Namespace) -> None:
    token = _get_admin_token()
    if not token:
        print("Cannot get admin token. Set PARADOX_ADMIN_PASS env var.", file=sys.stderr)
        sys.exit(1)
    url = f"{_api_base()}/alarm/history?limit={args.limit}"
    req = urllib.request.Request(url, method="GET")
    req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, context=_ssl_context()) as resp:
            data = json.loads(resp.read())
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code}: {e.read().decode()}", file=sys.stderr)
        sys.exit(1)
    events = data.get("events", [])
    if not events:
        print("No zone events yet.")
        return
    print(f"\n{'Timestamp':<28} {'Zone':<25} {'Event':<10} {'Partition'}")
    print("-" * 80)
    for ev in events:
        ts = ev["timestamp"][:19].replace("T", " ")
        print(f"  {ts:<26} {ev['zone_name']:<25} {ev['event']:<10} {ev['partition_id']}")
    print()


def cmd_status(_args: argparse.Namespace) -> None:
    data = _request("GET", "/alarm/status")
    if not data.get("connected"):
        print("Alarm not connected.")
        return
    for part in data["partitions"]:
        armed_str = f"ARMED ({part['mode'].upper()})" if part["armed"] else "DISARMED"
        open_count = sum(1 for z in part["zones"] if z["open"])
        bypassed_count = sum(1 for z in part["zones"] if z.get("bypassed"))
        total = len(part["zones"])
        print(f"  {part['name']:<12} {armed_str:<20} {open_count}/{total} open, {bypassed_count} bypassed")


def _get_admin_token() -> str | None:
    admin_user = os.environ.get("PARADOX_ADMIN_USER", "admin")
    admin_pass = os.environ.get("PARADOX_ADMIN_PASS")
    if not admin_pass:
        return None
    try:
        result = _request("POST", "/auth/login", {"username": admin_user, "password": admin_pass})
        return result.get("token")
    except SystemExit:
        return None


def main():
    parser = argparse.ArgumentParser(
        description="Paradox Bridge demo mode — toggle zones via SSH",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("list", help="List all zones across partitions")
    sub.add_parser("status", help="Show arm state per partition")

    p_open = sub.add_parser("open", help="Open (trigger) a zone")
    p_open.add_argument("zone_id", type=int)

    p_close = sub.add_parser("close", help="Close a zone")
    p_close.add_argument("zone_id", type=int)

    p_bypass = sub.add_parser("bypass", help="Bypass a zone (note)")
    p_bypass.add_argument("zone_id", type=int)

    p_unbypass = sub.add_parser("unbypass", help="Un-bypass a zone (note)")
    p_unbypass.add_argument("zone_id", type=int)

    p_history = sub.add_parser("history", help="Show zone event history")
    p_history.add_argument("--limit", type=int, default=20)

    args = parser.parse_args()

    dispatch = {
        "list": cmd_list,
        "status": cmd_status,
        "open": cmd_open,
        "close": cmd_close,
        "bypass": cmd_bypass,
        "unbypass": cmd_unbypass,
        "history": cmd_history,
    }

    func = dispatch.get(args.command)
    if func:
        func(args)
    else:
        parser.print_help()


def cmd_unbypass(args: argparse.Namespace) -> None:
    print(f"Note: unbypass requires auth — use the API directly or the app.")


if __name__ == "__main__":
    main()
