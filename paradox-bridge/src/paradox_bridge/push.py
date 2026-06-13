"""Firebase Cloud Messaging push for real-time alerts (alarm trigger, panel
disconnect, disk full) via the FCM HTTP v1 API.

Deliberately avoids the firebase-admin SDK (which drags in gRPC / Firestore /
Storage) to keep the always-on bridge light on the 512 MB Pi. Instead it signs a
short-lived JWT with the service-account key using python-jose (already a
dependency), exchanges it for an OAuth token, and POSTs to FCM via stdlib
urllib. No new dependencies, no pip install on the Pi.

Option A (per-Pi): this bridge sends directly. ``send_alert()`` is the single
swappable seam — to move to a central relay later, replace its body with an
authenticated HTTP call and nothing else changes.

Sends run on a background thread so a blocking network call never delays the PAI
status callback (speed matters for the alarm-trigger path).
"""
from __future__ import annotations

import json
import logging
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

logger = logging.getLogger("push")

CRED_PATH = os.environ.get(
    "PARADOX_FCM_CREDENTIALS", "/etc/paradox-bridge/firebase-service-account.json"
)
TOKENS_PATH = os.environ.get(
    "PARADOX_FCM_TOKENS", "/var/lib/paradox-bridge/push_tokens.json"
)
_TOKEN_URL = "https://oauth2.googleapis.com/token"
_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

_sa: dict | None = None
_init_attempted = False
_access_token: str | None = None
_access_exp = 0.0
_lock = threading.Lock()


def _load_sa() -> dict | None:
    """Load the service-account JSON once. Returns None if missing/invalid
    (pushes then become no-ops)."""
    global _sa, _init_attempted
    if _sa is not None:
        return _sa
    if _init_attempted:
        return None
    _init_attempted = True
    if not os.path.exists(CRED_PATH):
        logger.warning("FCM disabled: no service account at %s", CRED_PATH)
        return None
    try:
        with open(CRED_PATH) as f:
            _sa = json.load(f)
        logger.info("FCM (HTTP v1) ready for project %s", _sa.get("project_id"))
    except Exception as e:  # noqa: BLE001
        logger.warning("FCM init failed: %s", e)
        _sa = None
    return _sa


def _get_access_token() -> str | None:
    """Return a cached OAuth access token, refreshing via a signed JWT grant."""
    global _access_token, _access_exp
    now = time.time()
    if _access_token and now < _access_exp - 60:
        return _access_token
    sa = _load_sa()
    if not sa:
        return None
    try:
        from jose import jwt as jose_jwt

        iat = int(now)
        assertion = jose_jwt.encode(
            {
                "iss": sa["client_email"],
                "scope": _SCOPE,
                "aud": _TOKEN_URL,
                "iat": iat,
                "exp": iat + 3600,
            },
            sa["private_key"],
            algorithm="RS256",
        )
        body = urllib.parse.urlencode(
            {
                "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                "assertion": assertion,
            }
        ).encode()
        req = urllib.request.Request(
            _TOKEN_URL, data=body,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
        with urllib.request.urlopen(req, timeout=10) as r:
            data = json.loads(r.read())
        _access_token = data["access_token"]
        _access_exp = now + float(data.get("expires_in", 3600))
        return _access_token
    except Exception as e:  # noqa: BLE001
        logger.warning("FCM token exchange failed: %s", e)
        return None


# ── token registry (token -> owner label) ───────────────────────────────
def _load_tokens() -> dict:
    try:
        with open(TOKENS_PATH) as f:
            return json.load(f)
    except Exception:  # noqa: BLE001
        return {}


def _save_tokens(tokens: dict) -> None:
    os.makedirs(os.path.dirname(TOKENS_PATH), exist_ok=True)
    tmp = TOKENS_PATH + ".tmp"
    with open(tmp, "w") as f:
        json.dump(tokens, f)
    os.replace(tmp, TOKENS_PATH)


def register_token(token: str, owner: str = "") -> int:
    if not token:
        return 0
    with _lock:
        tokens = _load_tokens()
        tokens[token] = owner
        _save_tokens(tokens)
        n = len(tokens)
    logger.info("Registered push token for %s (%d total)", owner or "?", n)
    return n


def token_count() -> int:
    with _lock:
        return len(_load_tokens())


def _send_one(token: str, project_id: str, access_token: str, title: str,
              body: str, critical: bool, data: dict | None):
    """POST one FCM HTTP v1 message. Returns (ok, http_status)."""
    payload = {k: str(v) for k, v in (data or {}).items()}
    payload.update({"title": title, "body": body, "critical": "1" if critical else "0"})
    message = {
        "message": {
            "token": token,
            "notification": {"title": title, "body": body},
            "data": payload,
            "android": {
                "priority": "high",
                "notification": {
                    "channel_id": "alarm_critical" if critical else "alarm_alerts",
                    "sound": "default",
                    "default_vibrate_timings": True,
                    "notification_priority": "PRIORITY_MAX",
                    "visibility": "PUBLIC",
                },
            },
        }
    }
    url = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
    req = urllib.request.Request(
        url, data=json.dumps(message).encode(),
        headers={"Authorization": f"Bearer {access_token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10):
            return True, 200
    except urllib.error.HTTPError as e:
        logger.warning("FCM send HTTP %s: %s", e.code, e.read().decode(errors="replace")[:200])
        return False, e.code
    except Exception as e:  # noqa: BLE001
        logger.warning("FCM send error: %s", e)
        return False, 0


def _send_blocking(title: str, body: str, critical: bool, data: dict | None) -> None:
    sa = _load_sa()
    if not sa:
        return
    access_token = _get_access_token()
    if not access_token:
        return
    project_id = sa.get("project_id")
    with _lock:
        tokens = list(_load_tokens().keys())
    if not tokens:
        logger.info("No push tokens registered; '%s' not sent", title)
        return
    sent, stale = 0, []
    for tok in tokens:
        ok, status_code = _send_one(tok, project_id, access_token, title, body, critical, data)
        if ok:
            sent += 1
        elif status_code in (400, 403, 404):  # invalid / unregistered token
            stale.append(tok)
    if stale:
        with _lock:
            tokens_map = _load_tokens()
            for t in stale:
                tokens_map.pop(t, None)
            _save_tokens(tokens_map)
        logger.info("Pruned %d stale push tokens", len(stale))
    logger.info("Push '%s' delivered to %d/%d devices", title, sent, len(tokens))


def send_alert(title: str, body: str, *, critical: bool = False, data: dict | None = None) -> None:
    """Fire-and-forget push to all registered devices.

    The ONLY place that talks to FCM — swap this body for a relay call to make
    the system multi-tenant without touching detection or the apps.
    """
    threading.Thread(
        target=_send_blocking, args=(title, body, critical, data), daemon=True
    ).start()
