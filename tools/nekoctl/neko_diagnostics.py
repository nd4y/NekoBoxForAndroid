"""Bounded read-only diagnostic collection; no log clearing or network/config changes."""
import hashlib
import json
import os
import re
import subprocess
import time
import zipfile
from pathlib import Path


def redact_text(text):
    text = re.sub(r'(?i)(?:https?|socks5?|vless|vmess|trojan)://[^\s"<>]+', '<redacted>', text)
    text = re.sub(r'(?im)(authorization[\s"\':=]+)(?:bearer|basic)?[^\r\n,}]+', r'\1<redacted>', text)
    text = re.sub(r'(?i)((?:password|passwd|token|secret|private[_-]?key|preshared[_-]?key|setup[_-]?key|uuid)["\']?\s*[:=]\s*)(?:"[^"\r\n]*"|\'[^\'\r\n]*\'|[^\s,}]+)', r'\1<redacted>', text)
    return re.sub(r'(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b', '<redacted>', text)


def system_read(client, args, max_bytes=262144):
    """Do not mistake Exception: in actual log content for an adb transport diagnostic."""
    try:
        p = subprocess.run(client.prefix + args, capture_output=True, timeout=min(client.timeout, 20), check=False)
    except (subprocess.TimeoutExpired, OSError):
        return None, 'TRANSPORT_UNAVAILABLE'
    if p.returncode:
        return None, 'ADB_READ_FAILED'
    return p.stdout[-max_bytes:].decode('utf-8', errors='replace'), None


def collect_bundle(client, destination, include_secrets=False, logcat_lines=1000):
    """Each failed component is recorded; a dead/old provider must not hide OS evidence."""
    if not 1 <= logcat_lines <= 5000:
        raise ValueError('logcat-lines must be 1..5000')
    destination = Path(destination)
    # Refuse overwrite BEFORE doing expensive reads. O_EXCL again closes the creation race below.
    if destination.exists() or destination.is_symlink():
        raise FileExistsError()
    started = time.time()
    entries, files = [], {}
    provider_unavailable = False

    def add(name, data=None, error=None, redacted=True):
        if error:
            entries.append({'file': name, 'ok': False, 'error': error})
            return
        raw = data.encode('utf-8') if isinstance(data, str) else data
        files[name] = raw
        entries.append({'file': name, 'ok': True, 'bytes': len(raw),
                        'sha256': hashlib.sha256(raw).hexdigest(), 'redacted': redacted})

    def api(name, method, params=None):
        nonlocal provider_unavailable
        if provider_unavailable:
            add(name, error='SKIPPED_PROVIDER_UNAVAILABLE')
            return None
        try:
            response = client.request(method, params)
        except Exception:
            provider_unavailable = True
            add(name, error='API_UNAVAILABLE')
            return None
        if not response.get('ok'):
            code = response.get('error', {}).get('code', 'API_ERROR')
            add(name, error=code if re.fullmatch('[A-Z_]+', str(code)) else 'API_ERROR')
            return None
        add(name, json.dumps(response, ensure_ascii=False, indent=2) + '\n', redacted=not include_secrets)
        return response['result']

    # Collect scoped OS evidence first, even if starting a provider later fails or hangs.
    package, error = system_read(client, ['shell', 'pm', 'list', 'packages', '-U', client.package])
    uid = None
    if package:
        match = re.search(re.escape('package:' + client.package) + r' uid:(\d+)\b', package)
        uid = match.group(1) if match else None
    for name, command in [('exit-info.txt', ['shell', 'dumpsys', 'activity', 'exit-info', client.package]),
                          ('memory.txt', ['shell', 'dumpsys', 'meminfo', client.package])]:
        data, error = system_read(client, command)
        add(name, data if include_secrets or data is None else redact_text(data), error, not include_secrets)
    if uid is None:
        add('logcat.txt', error='APP_UID_UNAVAILABLE')
    else:
        data, error = system_read(client, ['logcat', '-d', '-b', 'all', '--uid=' + uid, '-t', str(logcat_lines)])
        add('logcat.txt', data if include_secrets or data is None else redact_text(data), error, not include_secrets)

    # Bound API retries/timeouts for a broken app. No method here changes configuration or VPN.
    original_timeout = client.timeout
    client.timeout = min(original_timeout, 20)
    try:
        # A thread snapshot does not acquire configuration locks. Capture it before status,
        # so a blocked database cannot hide the Java stacks needed to diagnose that blockage.
        api('snapshot.json', 'diagnostics.snapshot')
        before = api('status-before.json', 'status')
        capabilities = api('capabilities.json', 'capabilities')
        api('config.json', 'config.export', {'full': True, 'includeSecrets': include_secrets})
        api('generated.json', 'config.render', {'includeSecrets': include_secrets})
        for source in ('core', 'previous', 'events', 'crash-bg', 'crash-main'):
            api('logs/' + source + '.json', 'logs.read', {'source': source, 'maxBytes': 131072, 'includeSecrets': include_secrets})
        for source in ('crash-native-bg', 'crash-native-main'):
            if source in (capabilities or {}).get('logSources', []):
                api('logs/' + source + '.json', 'logs.read', {'source': source, 'maxBytes': 131072, 'includeSecrets': include_secrets})
        after = api('status-after.json', 'status')
    finally:
        client.timeout = original_timeout
    manifest = {'schema': 'nekobox.diagnostic-bundle', 'schemaVersion': 1, 'package': client.package,
                'startedUnix': started, 'finishedUnix': time.time(), 'includeSecrets': include_secrets,
                'complete': all(x['ok'] for x in entries), 'entries': entries,
                'savedRevisionBefore': (before or {}).get('savedRevision'),
                'savedRevisionAfter': (after or {}).get('savedRevision'),
                'configurationChangedDuringCollection': None if not before or not after else before.get('savedRevision') != after.get('savedRevision'),
                'notes': ['Read-only collection; no VPN restart or log clearing.',
                          'OS text redaction is best effort. Routing/addresses can remain; keep the bundle private.',
                          'Empty log sources and unavailable legacy/provider components are reported explicitly.']}
    files['manifest.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'wb') as output:
            with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
                for name, data in files.items():
                    archive.writestr(name, data)
    except BaseException:
        destination.unlink(missing_ok=True)  # Only the file exclusively created by this invocation.
        raise
    return {'file': str(destination), 'complete': manifest['complete'],
            'files': len(files), 'failedComponents': sum(not x['ok'] for x in entries),
            'includeSecrets': include_secrets}
