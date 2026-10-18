import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from io import StringIO
from contextlib import redirect_stdout
from unittest.mock import patch
import nekoctl
from neko_diagnostics import collect_bundle, redact_text, system_read

class FakeClient:
    prefix = ['adb', '-s', 'explicit-device']
    package = 'com.nb4a.plus.debug'
    timeout = 120
    def __init__(self): self.calls = []
    def request(self, method, params=None):
        self.calls.append((method, params))
        result = {'savedRevision': 'unchanged'} if method == 'status' else {'text': 'safe'}
        return {'apiVersion': 1, 'ok': True, 'result': result}

def fake_system(client, args, **kwargs):
    if args[:3] == ['shell', 'pm', 'list']:
        return 'package:com.nb4a.plus.debug uid:12345', None
    return 'Exception: example failure\nAuthorization: Bearer hidden-credential\n at Test.call(Test.kt:20)', None

class DiagnosticTests(unittest.TestCase):
    def test_redaction_removes_multiline_credentials_but_retains_stack(self):
        text = redact_text('password="test-secret"\nhttps://example.test/?token=abc\nAuthorization: Bearer auth-secret\n at Test.call(Test.kt:9)')
        for secret in ('test-secret', 'example.test', 'auth-secret'): self.assertNotIn(secret, text)
        self.assertIn('Test.kt:9', text)

    @patch('neko_diagnostics.subprocess.run', return_value=subprocess.CompletedProcess([], 0, b'Exception: actual log entry', b''))
    def test_diagnostic_text_is_not_transport_failure(self, run):
        text, error = system_read(FakeClient(), ['logcat', '-d'])
        self.assertEqual('Exception: actual log entry', text)
        self.assertIsNone(error)
        self.assertEqual(['adb', '-s', 'explicit-device'], run.call_args.args[0][:3])

    @patch('neko_diagnostics.system_read', side_effect=fake_system)
    def test_bundle_has_manifest_hashes_scoped_logcat_and_never_mutates(self, read):
        client = FakeClient()
        with tempfile.TemporaryDirectory() as d:
            f = Path(d)/'bundle.zip'
            result = collect_bundle(client, f)
            self.assertTrue(result['complete'])
            with zipfile.ZipFile(f) as z:
                manifest = json.loads(z.read('manifest.json'))
                self.assertFalse(manifest['configurationChangedDuringCollection'])
                self.assertNotIn(b'hidden-credential', z.read('logcat.txt'))
                self.assertIn(b'Test.kt:20', z.read('logcat.txt'))
                import hashlib
                for entry in manifest['entries']:
                    self.assertEqual(entry['sha256'], hashlib.sha256(z.read(entry['file'])).hexdigest())
        self.assertTrue(any('--uid=12345' in c.args[1] for c in read.call_args_list))
        self.assertTrue(all(not any(x in m for x in ('vpn.', 'apply', 'import', 'rollback', 'clear')) for m, _ in client.calls))
        self.assertTrue(all(p.get('includeSecrets') is False for m, p in client.calls if m in ('logs.read', 'config.export', 'config.render')))
        self.assertEqual(120, client.timeout)

    @patch('neko_diagnostics.system_read', side_effect=fake_system)
    def test_dead_provider_keeps_os_evidence_and_is_not_retried(self, _read):
        client = FakeClient()
        with patch.object(client, 'request', side_effect=RuntimeError('private failure message')) as request:
            with tempfile.TemporaryDirectory() as d:
                f = Path(d)/'partial.zip'
                result = collect_bundle(client, f)
                self.assertFalse(result['complete'])
                self.assertEqual(1, request.call_count)
                with zipfile.ZipFile(f) as z:
                    self.assertIn('exit-info.txt', z.namelist())
                    self.assertNotIn(b'private failure message', z.read('manifest.json'))
        self.assertEqual(120, client.timeout)

    def test_existing_destination_is_never_overwritten_or_collected(self):
        client = FakeClient()
        with tempfile.TemporaryDirectory() as d:
            f = Path(d)/'exists.zip';f.write_text('retain me')
            with self.assertRaises(FileExistsError): collect_bundle(client, f)
            self.assertEqual('retain me', f.read_text())
            self.assertEqual([], client.calls)

    @patch('nekoctl.Client.request')
    def test_logs_follow_advances_cursor_and_stops_cleanly(self, request):
        observed = []
        replies = [{'apiVersion':1,'ok':True,'result':{'text':'first','cursor':'generation:20'}},
                   {'apiVersion':1,'ok':True,'result':{'text':'next','cursor':'generation:30'}}]
        def respond(method, params):
            observed.append((method, dict(params)))
            return replies[len(observed)-1]
        request.side_effect = respond
        with patch('nekoctl.time.sleep', side_effect=[None, KeyboardInterrupt()]):
            with redirect_stdout(StringIO()) as output:
                self.assertEqual(0, nekoctl.main(['--serial','known','logs','--source','events','--follow']))
        lines = [json.loads(x) for x in output.getvalue().splitlines()]
        self.assertEqual(2, len(lines))
        self.assertEqual('generation:30', lines[-1]['result']['cursor'])
        self.assertNotIn('cursor', observed[0][1])
        self.assertEqual('generation:20', observed[1][1]['cursor'])
        self.assertEqual('logs.read', request.call_args.args[0])
        self.assertFalse(request.call_args.args[1]['includeSecrets'])

    @patch('neko_diagnostics.system_read', side_effect=fake_system)
    def test_explicit_secret_bundle_forwards_opt_in(self, _read):
        client = FakeClient()
        with tempfile.TemporaryDirectory() as d:
            f = Path(d)/'secret.zip';collect_bundle(client, f, include_secrets=True)
            with zipfile.ZipFile(f) as z:
                self.assertTrue(json.loads(z.read('manifest.json'))['includeSecrets'])
                self.assertIn(b'hidden-credential', z.read('logcat.txt'))
        self.assertTrue(all(p['includeSecrets'] for m, p in client.calls if m in ('logs.read', 'config.export', 'config.render')))
