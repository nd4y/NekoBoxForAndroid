#!/usr/bin/env python3
"""Destructive-to-synthetic-fixtures-only API acceptance test. Emulator target required."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
import time
from nekoctl import Client


def run(client):
    checks=[]
    def ok(method,params=None):
        reply=client.request(method,params)
        assert reply['ok'], (method,reply.get('error'))
        return reply['result']
    def rejected(code,method,params):
        reply=client.request(method,params)
        assert not reply['ok'] and reply['error']['code']==code, (method,reply)
        checks.append(code)
    def patch(*ops): return {'operations':list(ops)}
    def apply(change,revision): return ok('config.apply',{'patch':change,'expectedRevision':revision})
    baseline=ok('config.export',{'includeSecrets':True})
    assert ok('status')['state']=='Stopped', 'Stop the emulator test VPN first'
    config=baseline['config']; initial=baseline['revision']
    gid=max([g['id'] for g in config['groups']]+[0])+1
    pid=max([p['id'] for p in config['profiles']]+[0])+1
    rid=max([r['id'] for r in config['rules']]+[0])+1
    password='synthetic-test-only-'+'x'*131072
    create=patch(
        {'op':'group.create','id':gid,'values':{'name':'ADB acceptance fixtures'}},
        {'op':'profile.create','id':pid,'groupId':gid,'protocol':'socks','values':{'name':'ADB synthetic SOCKS','serverAddress':'127.0.0.1','serverPort':9,'password':password}},
        {'op':'profile.create','id':pid+1,'groupId':gid,'protocol':'config','values':{'name':'ADB direct test','type':1,'config':'{"type":"direct"}'}},
        {'op':'rule.create','id':rid,'values':{'name':'ADB synthetic rule','enabled':True,'domains':'domain:adb-test.invalid','outbound':-1}},
        {'op':'settings.patch','values':{'remoteDns':'https://1.1.1.1/dns-query'}})
    planned=ok('config.plan',{'patch':create})
    assert planned['revision']==initial
    assert ok('config.export',{'includeSecrets':True})==baseline, 'Plan mutated stored configuration'
    checks.append('plan_is_read_only_and_streams_128KiB')
    committed=apply(create,initial); revision=committed['revision']
    try:
        assert revision==planned['candidateRevision'], 'Planned revision differs from saved configuration'
        checks.append('plan_apply_revision_matches')
        rejected('REVISION_CONFLICT','config.apply',{'patch':create,'expectedRevision':initial})
        change=patch({'op':'profile.update','id':pid,'values':{'name':'ADB renamed'}})
        updated=apply(change,revision)
        raw=ok('config.export',{'includeSecrets':True})
        entity=next(p for p in raw['config']['profiles'] if p['id']==pid)
        assert entity['values']['password']==password and entity['groupId']==gid
        redacted=ok('config.export')
        assert password not in json.dumps(redacted)
        checks.append('partial_update_preserves_secret_ids_order_and_redaction')
        restored=ok('config.rollback',{'checkpoint':updated['checkpoint'],'expectedRevision':updated['revision']})
        assert restored['revision']==revision
        checks.append('checkpoint_rollback')
        def compete(name):
            return client.request('config.apply',{'patch':patch({'op':'profile.update','id':pid,'values':{'name':name}}),'expectedRevision':revision})
        with ThreadPoolExecutor(max_workers=2) as pool:
            replies=list(pool.map(compete,['ADB competitor one','ADB competitor two']))
        winners=[r['result'] for r in replies if r['ok']]
        losers=[r['error']['code'] for r in replies if not r['ok']]
        assert len(winners)==1 and losers==['REVISION_CONFLICT'], replies
        ok('config.rollback',{'checkpoint':winners[0]['checkpoint'],'expectedRevision':winners[0]['revision']})
        checks.append('concurrent_compare_and_swap')
        rejected('MISSING_PROFILE','config.plan',{'patch':patch({'op':'profile.create','groupId':gid,'protocol':'chain','values':{'proxies':[987654321]}})})
        rejected('CHAIN_CYCLE','config.plan',{'patch':patch({'op':'profile.create','id':pid+2,'groupId':gid,'protocol':'chain','values':{'proxies':[pid+2]}})})
        rejected('UNKNOWN_FIELD','config.plan',{'patch':patch({'op':'profile.update','id':pid,'values':{'passwrod':'synthetic'}})})
        rejected('REDACTED_INPUT','config.plan',{'patch':patch({'op':'profile.update','id':pid,'values':{'password':'<redacted>'}})})
        rejected('INVALID_FIELD','config.plan',{'patch':patch({'op':'profile.update','id':pid,'values':{'serverPort':4294967296}})})
        rejected('INVALID_CONFIG','config.plan',{'patch':patch({'op':'profile.update','id':pid+1,'values':{'type':0,'config':'{"outbounds":[{"type":"does-not-exist"}]}'}})})
        rejected('MISSING_DETOUR','config.plan',{'patch':patch({'op':'profile.update','id':pid+1,'values':{'type':0,'config':'{"outbounds":[{"type":"direct","tag":"d","detour":"absent"}]}'}})})
        assert ok('status')['savedRevision']==revision
        assert ok('status')['state']=='Stopped'
        checks.append('invalid_candidates_leave_database_and_vpn_unchanged')
    finally:
        current=ok('status')['savedRevision']
        # Only revert if our fixture revision is still current. Never overwrite an unexpected outside edit.
        if current==revision:
            restored=ok('config.rollback',{'checkpoint':committed['checkpoint'],'expectedRevision':current})
            assert restored['revision']==initial
        else:
            raise AssertionError('Unexpected revision; fixtures retained for inspection, no forced rollback')
    assert ok('config.export',{'includeSecrets':True})==baseline
    checks.append('original_configuration_restored')
    return {'ok':True,'checks':checks,'count':len(checks),'initialRevision':initial,'finished':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',required=True);p.add_argument('--adb',default='adb');p.add_argument('--package',default='com.nb4a.plus.debug');p.add_argument('--report')
    args=p.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+',args.serial):p.error('Only emulator-* serials are permitted')
    result=run(Client(args.serial,args.package,args.adb))
    text=json.dumps(result,indent=2)
    if args.report:Path(args.report).write_text(text+'\n',encoding='utf-8')
    print(text)

if __name__=='__main__':main()
