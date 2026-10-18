import json
import os
from pathlib import Path
import sys
import subprocess
import unittest
from unittest.mock import patch
import nekoctl


class ClientTests(unittest.TestCase):
    def test_requires_explicit_device(self):
        with self.assertRaises(nekoctl.TransportError):
            nekoctl.Client(None)

    def test_package_cannot_inject_shell(self):
        with self.assertRaises(nekoctl.TransportError):
            nekoctl.Client("emulator-5580", "com.nb4a;reboot")

    @patch("nekoctl.uuid.uuid4", return_value="11111111-1111-1111-1111-111111111111")
    @patch("nekoctl.subprocess.run")
    def test_streams_secrets_only_on_stdin(self, run, _uuid):
        run.side_effect = [subprocess.CompletedProcess([], 0, b"", b""),
                           subprocess.CompletedProcess([], 0, b"Bundle[{result=11111111-1111-1111-1111-111111111111}]", b""),
                           subprocess.CompletedProcess([], 0, b'{"ok":true,"apiVersion":1,"result":{}}', b""),
                           subprocess.CompletedProcess([], 0, b"Deleted 1 rows", b"")]
        self.assertTrue(nekoctl.Client("emulator-5580").request("config.plan", {"password": "test secret"})["ok"])
        self.assertIn(b"test secret", run.call_args_list[0].kwargs["input"])
        self.assertEqual(run.call_args_list[0].args[0][3:7], ["shell", "-T", "content", "write"])
        for call in run.call_args_list:
            self.assertNotIn("test secret", " ".join(call.args[0]))
            self.assertEqual(call.args[0][1:3], ["-s", "emulator-5580"])

    @patch("nekoctl.subprocess.run")
    def test_json_data_is_not_treated_as_transport_diagnostic(self, run):
        envelope = {"ok": True, "apiVersion": 1, "result": {
            "name": "Exception: ADB_CONTROL_DISABLED ADB_SHELL_REQUIRED Error while accessing provider"}}
        raw = json.dumps(envelope).encode("utf-8")
        run.return_value = subprocess.CompletedProcess([], 0, raw, b"")
        self.assertEqual(nekoctl.Client("emulator-5580").run("exec-out", json_response=True), raw)

    def test_redirected_cli_json_is_utf8_with_ascii_default(self):
        code = ("import nekoctl; nekoctl.Client.request=lambda *a, **k: "
                "{'ok':True,'apiVersion':1,'result':{'name':'\\u041f\\u0440\\u0438\\u0432\\u0435\\u0442 \\U0001f408'}}; "
                "raise SystemExit(nekoctl.main(['--serial','emulator-5580','status']))")
        result = subprocess.run([sys.executable, "-c", code], cwd=Path(__file__).parent,
                                env=dict(os.environ, PYTHONIOENCODING="ascii"), capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8"))
        self.assertEqual(json.loads(result.stdout.decode("utf-8"))["result"]["name"], "\u041f\u0440\u0438\u0432\u0435\u0442 \U0001f408")

    @patch("nekoctl.subprocess.run", return_value=subprocess.CompletedProcess([], 0, b"java.lang.SecurityException: ADB_CONTROL_DISABLED", b""))
    def test_content_zero_exit_is_not_success(self, _run):
        with self.assertRaisesRegex(nekoctl.TransportError,"disabled"):
            nekoctl.Client("emulator-5580").request("status")

    @patch("nekoctl.subprocess.run", side_effect=subprocess.TimeoutExpired("adb", 1))
    def test_timeout_never_retries_write(self, run):
        with self.assertRaisesRegex(nekoctl.TransportError,"outcome is unknown"):
            nekoctl.Client("emulator-5580").request("config.apply")
        self.assertEqual(run.call_count,1)

    @patch("nekoctl.subprocess.run")
    def test_oversized_request_is_local_error(self, run):
        with self.assertRaises(nekoctl.TransportError):
            nekoctl.Client("emulator-5580").request("config.plan",{"text":"x"*1048576})
        run.assert_not_called()

if __name__ == "__main__":
    unittest.main()
