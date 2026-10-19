import json
import unittest
from contextlib import redirect_stdout
from io import StringIO
from unittest.mock import patch
import nekoctl

class NetBirdCommands(unittest.TestCase):
    def test_diagnose_follow_starts_once_and_polls_read_only(self):
        replies = [
            {"ok": True, "result": {"state": "running", "results": []}},
            {"ok": True, "result": {"state": "completed", "results": [{"state": "no_reply"}]}},
        ]
        with patch.object(nekoctl.Client, "request", side_effect=replies) as request, patch.object(nekoctl.time, "sleep"), redirect_stdout(StringIO()) as out:
            self.assertEqual(0, nekoctl.main(["--serial", "emulator-5580", "netbird", "diagnose", "--follow"]))
        self.assertEqual(["netbird.diagnostics.start", "netbird.diagnostics.get"], [call.args[0] for call in request.call_args_list])
        self.assertEqual("no_reply", json.loads(out.getvalue())["result"]["results"][0]["state"])

    def test_peers_does_not_trigger_probes(self):
        with patch.object(nekoctl.Client, "request", return_value={"ok": True, "result": {"peers": [{"ip": "192.0.2.1"}]}}) as request, redirect_stdout(StringIO()) as out:
            self.assertEqual(0, nekoctl.main(["--serial", "emulator-5580", "netbird", "peers"]))
        request.assert_called_once_with("netbird.status", {})
        self.assertEqual([{"ip": "192.0.2.1"}], json.loads(out.getvalue())["result"])
