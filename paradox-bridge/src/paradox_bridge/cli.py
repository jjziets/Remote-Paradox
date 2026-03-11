"""CLI tool for generating invite codes on the Pi."""

import argparse
import os
import socket
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from paradox_bridge.auth import AuthService
from paradox_bridge.config import load_config
from paradox_bridge.database import Database
from paradox_bridge.tls import get_cert_fingerprint

_DEFAULT_CONFIG = "/etc/paradox-bridge/config.json"


def _get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def _build_invite_image(uri: str, code: str, expires_min: int) -> str:
    """Generate a printable invite page with QR code. Returns path to PNG."""
    import qrcode
    from PIL import Image, ImageDraw, ImageFont

    page_w, page_h = 800, 600
    img = Image.new("RGB", (page_w, page_h), "white")
    draw = ImageDraw.Draw(img)

    qr = qrcode.QRCode(version=None, error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=8, border=2)
    qr.add_data(uri)
    qr.make(fit=True)
    qr_img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
    qr_size = min(350, qr_img.size[0])
    qr_img = qr_img.resize((qr_size, qr_size), Image.NEAREST)

    try:
        font_title = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 28)
        font_body = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 18)
        font_code = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf", 32)
        font_small = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 14)
    except (OSError, IOError):
        font_title = ImageFont.load_default()
        font_body = font_title
        font_code = font_title
        font_small = font_title

    draw.rectangle([(0, 0), (page_w, 55)], fill="#1a1a2e")
    draw.text((20, 12), "PARADOX BRIDGE — INVITE", fill="white", font=font_title)

    qr_x = (page_w - qr_size) // 2
    qr_y = 75
    img.paste(qr_img, (qr_x, qr_y))

    text_y = qr_y + qr_size + 20
    draw.text((page_w // 2, text_y), f"Code:  {code}", fill="black", font=font_code, anchor="mt")
    text_y += 50
    draw.text((page_w // 2, text_y), f"URI:  {uri}", fill="#444444", font=font_body, anchor="mt")
    text_y += 30
    draw.text((page_w // 2, text_y), f"Expires in {expires_min} minutes  •  Single use only", fill="#888888", font=font_small, anchor="mt")

    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    draw.text((page_w // 2, page_h - 30), f"Generated {now}", fill="#aaaaaa", font=font_small, anchor="mt")

    path = tempfile.mktemp(suffix=".png", prefix="paradox_invite_")
    img.save(path, "PNG")
    return path


def main():
    parser = argparse.ArgumentParser(
        description="Generate a one-time invite code for the Paradox Bridge app"
    )
    parser.add_argument(
        "--config", default=os.environ.get("PARADOX_CONFIG", _DEFAULT_CONFIG),
        help="Path to config.json",
    )
    parser.add_argument(
        "--qr", action="store_true",
        help="Show a QR code in the terminal",
    )
    parser.add_argument(
        "--print", dest="do_print", action="store_true",
        help="Print the invite QR code on the network printer",
    )
    parser.add_argument(
        "--printer", default=None,
        help="CUPS printer name (default: system default)",
    )
    parser.add_argument(
        "--admin", default="admin",
        help="Admin username to create the invite as",
    )
    args = parser.parse_args()

    cfg = load_config(args.config)
    db = Database(cfg.db_path)
    db.init()
    auth = AuthService(db=db, config=cfg)

    admin = db.get_user(args.admin)
    if admin is None or admin["role"] != "admin":
        print(f"Error: '{args.admin}' is not an admin user.", file=sys.stderr)
        print("Set up the admin first by running the service with PARADOX_ADMIN_USER and PARADOX_ADMIN_PASS.", file=sys.stderr)
        sys.exit(1)

    code = auth.generate_invite(args.admin)
    host = _get_local_ip()
    port = cfg.api_port
    fingerprint = ""
    if cfg.tls_enabled and Path(cfg.tls_cert_path).exists():
        fingerprint = get_cert_fingerprint(cfg.tls_cert_path)
    uri = auth.build_invite_uri(code, host=host, port=port, fingerprint=fingerprint)
    expires_min = cfg.invite_expiry_seconds // 60

    print()
    print("=" * 50)
    print("  PARADOX BRIDGE — INVITE CODE")
    print("=" * 50)
    print()
    print(f"  Code:    {code}")
    print(f"  URI:     {uri}")
    print(f"  Expires: {expires_min} minutes")
    print()

    if args.qr:
        try:
            import qrcode
            qr = qrcode.QRCode(border=1)
            qr.add_data(uri)
            qr.make(fit=True)
            qr.print_ascii(invert=True)
            print()
        except ImportError:
            print("  (qrcode not installed — apt install python3-qrcode)")
            print()

    if args.do_print:
        try:
            img_path = _build_invite_image(uri, code, expires_min)
            cmd = ["lp"]
            if args.printer:
                cmd += ["-d", args.printer]
            cmd += ["-o", "fit-to-page", img_path]
            result = subprocess.run(cmd, capture_output=True, text=True)
            if result.returncode == 0:
                print(f"  Printed! {result.stdout.strip()}")
            else:
                print(f"  Print failed: {result.stderr.strip()}", file=sys.stderr)
            os.unlink(img_path)
        except ImportError:
            print("  (PIL/qrcode not installed — apt install python3-qrcode python3-pil)", file=sys.stderr)
        except Exception as exc:
            print(f"  Print error: {exc}", file=sys.stderr)
    else:
        print("  Tip: use --print to print this QR code on paper")

    print()
    print("  Share this code or scan the QR with the app.")
    print("  It can only be used ONCE.")
    print("=" * 50)
    print()

    db.close()


if __name__ == "__main__":
    main()
