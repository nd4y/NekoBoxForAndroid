#!/usr/bin/env python3
"""NekoBox ADB Control API v1 client. Python 3.9+, standard library only."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid


class TransportError(Exception):
    pass


class Client:
    def __init__(self, serial, package="com.nb4a.plus.debug", adb="adb", timeout=120):
        if not serial:
            raise TransportError("Select a device explicitly with --serial or ANDROID_SERIAL")
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package):
            raise TransportError("Invalid Android package name")
        self.prefix = [adb, "-s", serial]
        self.authority = "content://" + package + ".control"
        self.timeout = timeout

    def run(self, *args, payload=None, json_response=False):
        try:
            result = subprocess.run(self.prefix + list(args), input=payload, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=self.timeout, check=False)
        except subprocess.TimeoutExpired as exc:
            raise TransportError("ADB timed out; outcome is unknown. Inspect status before retrying a write") from exc
        except OSError as exc:
            raise TransportError("Cannot run adb; check --adb and the Android SDK installation") from exc
        # Error-looking words inside valid API JSON are user data, not adb diagnostics.
        if json_response and result.returncode == 0:
            try:
                envelope = json.loads(result.stdout)
                if (isinstance(envelope, dict) and envelope.get("apiVersion") == 1
                        and isinstance(envelope.get("ok"), bool)):
                    return result.stdout
            except (ValueError, UnicodeError):
                pass
        # Android's content utility can report Java exceptions with exit code zero.
        combined = result.stdout + result.stderr
        if b"ADB_CONTROL_DISABLED" in combined:
            raise TransportError("ADB Control API is disabled. Enable it in NekoBox Settings on the selected device")
        if b"ADB_SHELL_REQUIRED" in combined:
            raise TransportError("The provider only accepts the Android shell UID (2000)")
        if result.returncode or b"Error while accessing provider" in combined or b"Exception:" in combined:
            raise TransportError("ADB/content operation failed; check device authorization, package and API availability")
        return result.stdout

    def request(self, method, params=None):
        request_id = str(uuid.uuid4())
        payload = json.dumps({"apiVersion": 1, "method": method, "params": params or {}},
                             ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(payload) > 1048576:
            raise TransportError("Request exceeds the 1 MiB API limit")
        self.run("shell", "-T", "content", "write", "--uri", self.authority + "/requests/" + request_id, payload=payload)
        acknowledgement = self.run("shell", "content", "call", "--uri", self.authority,
                                   "--method", "execute", "--arg", request_id)
        if ("result=" + request_id).encode() not in acknowledgement:
            raise TransportError("No execution receipt; inspect status before retrying a write")
        raw = self.run("exec-out", "content", "read", "--uri", self.authority + "/responses/" + request_id, json_response=True)
        try:
            response = json.loads(raw)
            if response.get("apiVersion") != 1 or not isinstance(response.get("ok"), bool):
                raise ValueError("Invalid envelope")
        except (ValueError, AttributeError) as exc:
            raise TransportError("Invalid API response; inspect status before retrying a write") from exc
        try:
            self.run("shell", "content", "delete", "--uri", self.authority + "/responses/" + request_id)
        except TransportError:
            pass  # Private response expires after ten minutes; do not hide the completed result.
        return response


def load_patch(path):
    try:
        raw = sys.stdin.buffer.read(1048577) if path == "-" else Path(path).read_bytes()
        if len(raw) > 1048576:
            raise ValueError("Too large")
        patch = json.loads(raw.decode("utf-8-sig"))
        if not isinstance(patch, dict):
            raise ValueError("Expected object")
        return patch
    except (OSError, UnicodeError, ValueError) as exc:
        raise TransportError("Cannot read patch: expected a UTF-8 JSON object, at most 1 MiB") from exc


def parser():
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"), help="Required target serial; no automatic device selection")
    root.add_argument("--package", default="com.nb4a.plus.debug", help="Installed fork package (default: debug)")
    root.add_argument("--adb", default=shutil.which("adb") or "adb", help="Path to adb")
    root.add_argument("--timeout", type=int, default=120)
    root.add_argument("--output", help="Write JSON to a new private file instead of stdout")
    sub = root.add_subparsers(dest="command", required=True)
    for command in ("status", "capabilities", "checkpoints", "profiles", "groups", "rules", "settings"):
        sub.add_parser(command)
    export = sub.add_parser("export", help="Export configuration, secrets redacted unless explicitly requested")
    export.add_argument("--include-secrets", action="store_true")
    for command in ("plan", "apply"):
        p = sub.add_parser(command)
        p.add_argument("patch", help="JSON patch file, or - for stdin")
        if command == "apply":
            p.add_argument("--revision", required=True, help="Revision returned by plan/export")
    rollback = sub.add_parser("rollback")
    rollback.add_argument("checkpoint")
    rollback.add_argument("--revision", required=True)
    vpn = sub.add_parser("vpn")
    vpn.add_argument("action", choices=("start", "stop", "restart"))
    return root


def main(argv=None):
    # Keep redirected JSON interoperable on Windows, independently of the console code page.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    args = parser().parse_args(argv)
    try:
        client = Client(args.serial, args.package, args.adb, args.timeout)
        params = {}
        method = args.command
        collection = method if method in ("profiles", "groups", "rules", "settings") else None
        if collection:
            method = "config.export"
        if method == "export":
            method = "config.export"
            params = {"includeSecrets": args.include_secrets}
        elif method in ("plan", "apply"):
            params = {"patch": load_patch(args.patch)}
            if method == "apply":
                params["expectedRevision"] = args.revision
            method = "config." + method
        elif method == "rollback":
            method = "config.rollback"
            params = {"checkpoint": args.checkpoint, "expectedRevision": args.revision}
        elif method == "checkpoints":
            method = "checkpoints.list"
        elif method == "vpn":
            method = "vpn." + args.action
        response = client.request(method, params)
        if collection and response["ok"]:
            result = response["result"]
            response["result"] = {"revision": result["revision"], collection: result["config"][collection]}
        data = json.dumps(response, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            # Refuse overwrites, including symlinks; honor the user's normal Windows ACL inheritance.
            fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as output:
                output.write(data)
        else:
            sys.stdout.write(data)
        return 0 if response["ok"] else 2
    except (TransportError, OSError) as exc:
        # Never echo requests, configuration fragments or command lines containing credentials.
        error = str(exc) if isinstance(exc, TransportError) else "Cannot create output file (existing files are not overwritten)"
        sys.stderr.write(json.dumps({"ok": False, "error": {"code": "CLIENT_ERROR", "message": error}}) + "\n")
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
