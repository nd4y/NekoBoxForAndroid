#!/usr/bin/env python3
"""Exercise explicit VPN lifecycle and saved/running revisions on an emulator only."""
import argparse
import json
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET
from nekoctl import Client


def run(client):
    def ok(method, params=None):
        reply = client.request(method, params)
        assert reply['ok'], (method, reply.get('error'))
        return reply['result']

    def wait_for(predicate, description):
        until = time.monotonic() + 45
        while time.monotonic() < until:
            state = ok('status')
            if predicate(state):
                return state
            time.sleep(0.5)
        raise AssertionError('Timed out: ' + description)

    def consent_in_emulator_ui():
        package = client.authority.removeprefix('content://').removesuffix('.control')
        client.run('shell', 'am', 'start', '-n', package + '/io.nekohasekai.sagernet.ui.MainActivity')
        for target in ('Connect', 'OK'):
            found = False
            for _ in range(5):
                client.run('shell', 'uiautomator', 'dump', '/sdcard/nb4a-adb-control.xml')
                root = ET.fromstring(client.run('exec-out', 'cat', '/sdcard/nb4a-adb-control.xml'))
                for node in root.iter('node'):
                    matches = node.get('content-desc') == target if target == 'Connect' else (node.get('text') == 'OK' and node.get('package') == 'com.android.vpndialogs')
                    if matches:
                        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                        client.run('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                        found = True
                        break
                if found:
                    break
            assert found, 'Expected emulator VPN consent UI control: ' + target

    initial = ok('config.export', {'includeSecrets': True})
    assert ok('status')['state'] == 'Stopped', 'Stop the emulator test VPN first'
    group = initial['config']['groups'][0]['id']
    pid = max([p['id'] for p in initial['config']['profiles']] + [0]) + 1
    changes = {'operations': [
        {'op': 'profile.create', 'id': pid, 'groupId': group, 'protocol': 'config',
         'values': {'name': 'ADB lifecycle direct fixture', 'type': 1, 'config': '{"type":"direct"}'}},
        {'op': 'selection.set', 'id': pid},
    ]}
    checkpoints = []
    report = {'ok': False, 'checks': []}
    first = ok('config.apply', {'patch': changes, 'expectedRevision': initial['revision']})
    checkpoints.append(first)
    try:
        client.run('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        reply = client.request('vpn.start')
        if not reply['ok'] and reply['error']['code'] == 'VPN_PERMISSION_REQUIRED':
            consent_in_emulator_ui()
            report['checks'].append('first_vpn_consent_granted_in_emulator_ui')
        else:
            assert reply['ok'], reply
        connected = wait_for(lambda s: s['state'] == 'Connected', 'VPN start')
        assert connected['savedRevision'] == connected['runningRevision'] == first['revision'], connected
        assert connected['runningProfile'] == pid and not connected['restartRequired']
        report['checks'].append('explicit_start_running_revision_matches')
        change = {'operations': [{'op': 'profile.update', 'id': pid, 'values': {'name': 'ADB lifecycle changed fixture'}}]}
        second = ok('config.apply', {'patch': change, 'expectedRevision': first['revision']})
        checkpoints.append(second)
        dirty = ok('status')
        assert dirty['state'] == 'Connected' and dirty['runningRevision'] == first['revision']
        assert dirty['savedRevision'] == second['revision'] and dirty['restartRequired']
        assert second['restartRequired']
        report['checks'].append('apply_preserves_live_connection_and_marks_revision_dirty')
        ok('vpn.restart')
        clean = wait_for(lambda s: s['state'] == 'Connected' and s['runningRevision'] == second['revision'], 'VPN restart')
        assert not clean['restartRequired']
        report['checks'].append('explicit_restart_loads_saved_revision')
        ok('vpn.stop')
        stopped = wait_for(lambda s: s['state'] == 'Stopped', 'VPN stop')
        assert stopped['runningRevision'] is None
        report['checks'].append('explicit_stop_clears_running_revision')
    finally:
        ok('vpn.stop')
        wait_for(lambda s: s['state'] == 'Stopped', 'cleanup stop')
        for checkpoint in reversed(checkpoints):
            current = ok('status')['savedRevision']
            assert current == checkpoint['revision'], 'Outside edit detected; refusing forced rollback'
            ok('config.rollback', {'checkpoint': checkpoint['checkpoint'], 'expectedRevision': current})
    assert ok('config.export', {'includeSecrets': True}) == initial
    report['checks'].append('original_configuration_restored')
    report['ok'] = True
    report['initialRevision'] = initial['revision']
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--package', default='com.nb4a.plus.debug')
    parser.add_argument('--report')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        parser.error('Only emulator-* serials are permitted')
    result = run(Client(args.serial, args.package, args.adb))
    text = json.dumps(result, indent=2)
    if args.report:
        Path(args.report).write_text(text+'\n', encoding='utf-8')
    print(text)


if __name__ == '__main__':
    main()
