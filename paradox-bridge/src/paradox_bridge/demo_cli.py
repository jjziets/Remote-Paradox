"""CLI tool for testing in demo mode via SSH.

Commands mirror PAI control topics. Usage (on the Pi or Mac):

    paradox-demo list                  — show all zones & partitions
    paradox-demo status                — show partition state (PAI booleans)
    paradox-demo open  <zone_id>       — open (trigger) a zone sensor
    paradox-demo close <zone_id>       — close a zone sensor
    paradox-demo bypass <zone_id>      — bypass a zone
    paradox-demo unbypass <zone_id>    — un-bypass a zone
    paradox-demo panic [--type TYPE]   — send panic (emergency|fire|medical)
    paradox-demo history [--limit N]   — show event history
"""

import argparse
import json
import os
import sys

import urllib.request
import urllib.error


def _api_base() -> str:
    port = os.environ.get("PARADOX_API_PORT", "8080")
    return f"http://127.0.0.1:{port}"


def _request(method: str, path: str, body: dict | None = None, token: str | None = None) -> dict:
    url = f"{_api_base()}{path}"
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(url, data=data, method=method)
    if body:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        detail = e.read().decode()
        print(f"HTTP {e.code}: {detail}", file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"Connection error: {e.reason}", file=sys.stderr)
        print("Is the paradox-bridge service running?", file=sys.stderr)
        sys.exit(1)


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


def cmd_list(_args: argparse.Namespace) -> None:
    data = _request("GET", "/alarm/status")
    if not data.get("connected"):
        print("Alarm not connected.")
        return
    for part in data["partitions"]:
        print(f"\n{'='*55}")
        print(f"  {part['name']} (partition {part['id']})  —  {part['mode'].upper()}")
        ready = "READY" if part.get("ready", True) else "NOT READY"
        print(f"  {ready}")
        print(f"{'='*55}")
        for z in part["zones"]:
            flags = []
            if z["open"]:
                flags.append("OPEN")
            if z.get("bypassed"):
                flags.append("BYP")
            if z.get("alarm"):
                flags.append("ALARM")
            if z.get("was_in_alarm"):
                flags.append("WAS_ALARM")
            flag_str = f"  [{', '.join(flags)}]" if flags else ""
            print(f"  {z['id']:>3}  {z['name']:<25}{flag_str}")
    print()


def cmd_status(_args: argparse.Namespace) -> None:
    data = _request("GET", "/alarm/status")
    if not data.get("connected"):
        print("Alarm not connected.")
        return
    for part in data["partitions"]:
        mode = part["mode"].upper()
        armed = part["armed"]
        ready = part.get("ready", True)
        entry = part.get("entry_delay", False)
        open_count = sum(1 for z in part["zones"] if z["open"])
        bp_count = sum(1 for z in part["zones"] if z.get("bypassed"))
        alarm_count = sum(1 for z in part["zones"] if z.get("alarm"))
        total = len(part["zones"])
        flags = []
        if armed:
            flags.append("arm")
        if entry:
            flags.append("entry_delay")
        if part["mode"] == "arming":
            flags.append("exit_delay")
        if part["mode"] == "triggered":
            flags.append("audible_alarm")
        if not ready:
            flags.append("NOT_READY")
        flag_str = ", ".join(flags) if flags else "—"
        print(f"  {part['name']:<12} {mode:<14} flags=[{flag_str}]")
        print(f"               zones: {total} total, {open_count} open, {bp_count} bypassed, {alarm_count} alarm")


def cmd_open(args: argparse.Namespace) -> None:
    token = _get_admin_token()
    if not token:
        print("Cannot get admin token. Set PARADOX_ADMIN_PASS.", file=sys.stderr)
        sys.exit(1)
    result = _request("POST", "/alarm/zone-toggle", {"zone_id": args.zone_id, "open": True}, token=token)
    print(f"Zone {args.zone_id}: {result.get('action', 'done')}")


def cmd_close(args: argparse.Namespace) -> None:
    token = _get_admin_token()
    if not token:
        print("Cannot get admin token. Set PARADOX_ADMIN_PASS.", file=sys.stderr)
        sys.exit(1)
    result = _request("POST", "/alarm/zone-toggle", {"zone_id": args.zone_id, "open": False}, token=token)
    print(f"Zone {args.zone_id}: {result.get('action', 'done')}")


def cmd_bypass(args: argparse.Namespace) -> None:
    token = _get_admin_token()
    if not token:
        print("Cannot get admin token. Set PARADOX_ADMIN_PASS.", file=sys.stderr)
        sys.exit(1)
    _request("POST", "/alarm/bypass", {"zone_id": args.zone_id, "bypass": True}, token=token)
    print(f"Zone {args.zone_id}: bypassed")


def cmd_unbypass(args: argparse.Namespace) -> None:
    token = _get_admin_token()
    if not token:
        print("Cannot get admin token. Set PARADOX_ADMIN_PASS.", file=sys.stderr)
        sys.exit(1)
    _request("POST", "/alarm/bypass", {"zone_id": args.zone_id, "bypass": False}, token=token)
    print(f"Zone {args.zone_id}: un-bypassed")


def cmd_panic(args: argparse.Namespace) -> None:
    token = _get_admin_token()
    if not token:
        print("Cannot get admin token. Set PARADOX_ADMIN_PASS.", file=sys.stderr)
        sys.exit(1)
    result = _request("POST", "/alarm/panic", {
        "partition_id": args.partition_id,
        "panic_type": args.type,
    }, token=token)
    ok = result.get("success", False)
    print(f"Panic ({args.type}): {'sent' if ok else 'FAILED'}")


def cmd_history(args: argparse.Namespace) -> None:
    token = _get_admin_token()
    if not token:
        print("Cannot get admin token. Set PARADOX_ADMIN_PASS.", file=sys.stderr)
        sys.exit(1)
    url = f"/alarm/history?limit={args.limit}"
    data = _request("GET", url, token=token)
    events = data.get("events", [])
    if not events:
        print("No events yet.")
        return
    print(f"\n{'Timestamp':<28} {'Label':<25} {'Property':<16} {'Value'}")
    print("-" * 80)
    for ev in events:
        ts = ev["timestamp"][:19].replace("T", " ")
        print(f"  {ts:<26} {ev['label']:<25} {ev['property']:<16} {ev['value']}")
    print()


def main():
    parser = argparse.ArgumentParser(
        description="Paradox Bridge demo — simulate alarm panel via SSH",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("list", help="List all zones across partitions")
    sub.add_parser("status", help="Show partition state with PAI flags")

    p_open = sub.add_parser("open", help="Open (trigger) a zone sensor")
    p_open.add_argument("zone_id", type=int)

    p_close = sub.add_parser("close", help="Close a zone sensor")
    p_close.add_argument("zone_id", type=int)

    p_bypass = sub.add_parser("bypass", help="Bypass a zone")
    p_bypass.add_argument("zone_id", type=int)

    p_unbypass = sub.add_parser("unbypass", help="Un-bypass a zone")
    p_unbypass.add_argument("zone_id", type=int)

    p_panic = sub.add_parser("panic", help="Send panic alarm")
    p_panic.add_argument("--type", choices=["emergency", "fire", "medical"], default="emergency")
    p_panic.add_argument("--partition-id", type=int, default=1)

    p_history = sub.add_parser("history", help="Show event history")
    p_history.add_argument("--limit", type=int, default=20)

    args = parser.parse_args()

    dispatch = {
        "list": cmd_list,
        "status": cmd_status,
        "open": cmd_open,
        "close": cmd_close,
        "bypass": cmd_bypass,
        "unbypass": cmd_unbypass,
        "panic": cmd_panic,
        "history": cmd_history,
    }

    func = dispatch.get(args.command)
    if func:
        func(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
