#!/usr/bin/env python3
"""Black-box acceptance tests. Only run in a disposable GitHub Actions job.

No MockMvc, repository/service calls, or production databases. The source database
is synthetic, dumped with pg_dump and restored with pg_restore before migration.
All application operations use a running packaged JAR through a loopback socket.
PostgreSQL reads independently verify persistence. Secrets are redacted in evidence.
"""
from __future__ import annotations
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

OUT = Path(os.environ.get('RUNNER_TEMP', '/tmp')) / 'live-api-evidence'
BASE = 'http://127.0.0.1:18080'
SEED, LEGACY, TARGET = 'ca_live_source_seed', 'ca_live_legacy', 'ca_live_target'
ADMIN = 'live_smoke_admin'
PASSWORD = secrets.token_urlsafe(24)
DB_PASSWORD = os.environ.get('DB_PASSWORD', '')
RECORDS: list[dict] = []
IDS: dict[str, str] = {}
TOKENS: dict[str, str] = {}
FILES = {
    2001: '\u0648\u062b\u064a\u0642\u0629 \u0642\u062f\u064a\u0645\u0629 - \u0627\u0644\u0645\u0634\u0631\u0648\u0639 \u0627\u0644\u0623\u0648\u0644\nLegacy payload one.\n'.encode(),
    2002: '\u0645\u0631\u0641\u0642 \u0627\u0644\u0645\u0634\u0631\u0648\u0639 \u0627\u0644\u062b\u0627\u0646\u064a\nLegacy payload two.\n'.encode(),
}


def scrub(value):
    if isinstance(value, dict):
        return {k: ('[REDACTED]' if any(s in k.lower() for s in
                ('password', 'token', 'secret', 'authorization')) else scrub(v))
                for k, v in value.items()}
    if isinstance(value, list):
        return [scrub(v) for v in value]
    return value


def record(name, passed, **details):
    item = {'name': name, 'passed': bool(passed), **scrub(details)}
    RECORDS.append(item)
    print(('PASS ' if passed else 'FAIL ') + name, flush=True)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'live-api-results.json').write_text(json.dumps({
        'utc': datetime.now(timezone.utc).isoformat(),
        'commit': os.environ.get('GITHUB_SHA'),
        'transport': 'real HTTP socket to packaged Spring Boot JAR',
        'source': 'synthetic pg_dump restored into a separate PostgreSQL database',
        'checks': RECORDS, 'ids': IDS,
        'passed': sum(x['passed'] for x in RECORDS),
        'failed': sum(not x['passed'] for x in RECORDS),
    }, ensure_ascii=False, indent=2), encoding='utf-8')


def check(name, condition, observed=None):
    record(name, condition, observed=observed)
    if not condition:
        raise AssertionError(name)


def sql(database, statement):
    env = {**os.environ, 'PGPASSWORD': DB_PASSWORD, 'PGCLIENTENCODING': 'UTF8'}
    result = subprocess.run(['psql', '-X', '-h', '127.0.0.1', '-p', '5432',
        '-U', 'archive_user', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At', '-c', statement],
        env=env, text=True, capture_output=True, timeout=30, check=True)
    return result.stdout.strip()


def rows(statement):
    return json.loads(sql(TARGET, f'SELECT COALESCE(json_agg(r), \'[]\'::json) FROM ({statement}) r'))


def q(value):
    return "'" + str(value).replace("'", "''") + "'"


def request(name, method, path, body=None, expected=200, actor='admin', raw=None, content_type=None):
    headers = {}
    if actor in TOKENS:
        headers['Authorization'] = 'Bearer ' + TOKENS[actor]
    if body is not None:
        raw = json.dumps(body, ensure_ascii=False).encode('utf-8')
        content_type = 'application/json'
    if content_type:
        headers['Content-Type'] = content_type
    req = urllib.request.Request(BASE + path, data=raw, headers=headers, method=method)
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            status, data, mime = response.status, response.read(), response.headers.get('Content-Type', '')
    except urllib.error.HTTPError as error:
        status, data, mime = error.code, error.read(), error.headers.get('Content-Type', '')
    parsed = json.loads(data) if data and 'json' in mime else None
    allowed = (expected,) if isinstance(expected, int) else expected
    record(name, status in allowed, method=method, path=path,
        request=body, expected_status=list(allowed), actual_status=status,
        response=parsed if parsed is not None else {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()},
        elapsed_ms=round((time.monotonic() - started) * 1000, 1))
    if status not in allowed:
        raise AssertionError(f'{name}: expected {allowed}, got {status}')
    return parsed if parsed is not None else data


def scenario(name, function):
    before = len(RECORDS)
    try:
        function()
    except Exception as error:
        if len(RECORDS) == before or RECORDS[-1]['passed']:
            record(name, False, error=str(error))
        print(f'SCENARIO {name}: {type(error).__name__}: {error}', flush=True)


def login(username, actor):
    data = request('login ' + actor, 'POST', '/api/v1/auth/login',
                   {'username': username, 'password': PASSWORD}, actor=None)['data']
    TOKENS[actor] = data['token']


def create_org(kind, parent=None, label=None, field='parentId'):
    label = label or kind
    body = {'name': '\u0645\u0624\u0633\u0633\u0629 \u0627\u062e\u062a\u0628\u0627\u0631 ' + label,
            'code': 'LIVE-' + label, 'type': kind}
    if parent:
        body[field] = parent
    data = request('create organization ' + label, 'POST', '/api/v1/organizations', body, 201)['data']
    stored = rows(f'SELECT id,parent_id,type FROM core.organizations WHERE id={q(data["id"])}::uuid')[0]
    check('persist organization ' + label, stored['type'] == kind and stored['parent_id'] == parent, stored)
    return data['id']


def organization_cases():
    parent = None
    for kind in ('COMPANY', 'FACTORY', 'LINE', 'DEPARTMENT'):
        IDS[kind] = create_org(kind, parent)
        parent = IDS[kind]
    path = request('read complete organization path', 'GET', f'/api/v1/organizations/{parent}/path')['data']
    check('organization path order', [x['type'] for x in path] == ['COMPANY','FACTORY','LINE','DEPARTMENT'], path)
    tree = request('read organization tree', 'GET', '/api/v1/organizations/tree')['data']
    def flatten(items):
        return [i['id'] for i in items] + [x for i in items for x in flatten(i.get('children', []))]
    check('all four organization types visible in tree', all(IDS[k] in flatten(tree)
          for k in ('COMPANY','FACTORY','LINE','DEPARTMENT')))
    for kind in ('FACTORY', 'LINE', 'DEPARTMENT'):
        request('reject root ' + kind, 'POST', '/api/v1/organizations',
                {'name': 'Invalid root', 'code': 'NO-PARENT-' + kind, 'type': kind}, 400)
    request('reject wrong parent type', 'POST', '/api/v1/organizations',
            {'name':'Wrong parent','code':'WRONG-PARENT','type':'DEPARTMENT','parentId':IDS['COMPANY']}, 400)
    request('reject duplicate organization code', 'POST', '/api/v1/organizations',
            {'name':'Duplicate','code':'LIVE-COMPANY','type':'COMPANY'}, 409)
    for kind in ('COMPANY','FACTORY','LINE','DEPARTMENT'):
        data = request('select organizations ' + kind, 'GET', '/api/v1/organizations/all?type=' + kind)['data']
        check('type selection returns correct ' + kind, any(x['id'] == IDS[kind] for x in data)
              and all(x['type'] == kind for x in data))
    # Contract compatibility: a supplied parent_id must not be silently discarded.
    for kind, parent_kind in [('FACTORY','COMPANY'),('LINE','FACTORY'),('DEPARTMENT','LINE')]:
        scenario('snake parent field ' + kind, lambda k=kind,p=parent_kind:
                 create_org(k, IDS[p], 'SNAKE-' + k, 'parent_id'))


def user_cases():
    role_list = request('read role dictionary', 'GET', '/api/v1/roles/all')['data']
    role_ids = {r['code']:r['id'] for r in role_list}
    levels = request('read confidentiality dictionary', 'GET', '/api/v1/confidentiality-levels/all')['data']
    IDS['clearance'] = max(levels, key=lambda x: x['levelOrder'])['id']
    for actor, role in [('entry','central_data_entry'),('reader','read_only')]:
        body = {'username':'live_' + actor,'password':PASSWORD,'fullName':'Live ' + actor,
                'organizationId':IDS['DEPARTMENT'],'maxConfidentialityLevelId':IDS['clearance'],
                'roleIds':[role_ids[role]]}
        data = request('create user ' + actor, 'POST', '/api/v1/users', body, (200,201))['data']
        IDS[actor] = data['id']
        check('persist user ' + actor, sql(TARGET, f'SELECT count(*) FROM core.users WHERE id={q(data["id"])}::uuid') == '1')
        login('live_' + actor, actor)
        me = request('read own profile ' + actor, 'GET', '/api/v1/auth/me', actor=actor)['data']
        check('new user can authenticate ' + actor, me['username'] == 'live_' + actor, me)
    request('duplicate username', 'POST', '/api/v1/users',
            {'username':'live_entry','password':PASSWORD,'fullName':'Duplicate'}, 409)
    request('invalid user cannot login', 'POST', '/api/v1/auth/login',
            {'username':'absent_user','password':PASSWORD}, 401, actor=None)


def project_body(number, organization=None, complete=False):
    return {'organizationId':organization or IDS['DEPARTMENT'], 'projectNumber':number,
            'name':'\u0645\u0634\u0631\u0648\u0639 \u062a\u062c\u0631\u064a\u0628\u064a ' + number, 'year':2026,
            'confidentialityLevelId':IDS['clearance'],
            'dataCompletionStatus':'COMPLETE' if complete else 'INCOMPLETE'}


def project_cases():
    # Project creation works with each supported organization type, not only COMPANY.
    for kind in ('COMPANY','FACTORY','LINE','DEPARTMENT'):
        body = project_body('LIVE-P-' + kind, IDS[kind])
        data = request('create project under ' + kind, 'POST', '/api/v1/projects', body, 201, actor='entry')['data']
        pid = data['id']
        IDS['project_' + kind] = pid
        fetched = request('read project under ' + kind, 'GET', '/api/v1/projects/' + pid, actor='entry')['data']
        check('project organization persisted ' + kind, fetched['organizationId'] == IDS[kind])
        request('edit project ' + kind, 'PATCH', '/api/v1/projects/' + pid,
                {'name':'\u0645\u0634\u0631\u0648\u0639 \u0645\u0639\u062f\u0644 ' + kind}, actor='entry')
        if kind == 'DEPARTMENT':
            request('read-only user cannot delete project', 'DELETE', '/api/v1/projects/' + pid, expected=403, actor='reader')
        request('delete standalone draft ' + kind, 'DELETE', '/api/v1/projects/' + pid, actor='entry')
        request('deleted draft GET returns 404 ' + kind, 'GET', '/api/v1/projects/' + pid, expected=404)
        check('deleted draft absent in database ' + kind,
              sql(TARGET, f'SELECT count(*) FROM project.projects WHERE id={q(pid)}::uuid') == '0')
        audit = rows(f"SELECT action,entity_id FROM audit.audit_logs WHERE entity_id={q(pid)}::uuid AND action='DELETE'")
        check('draft deletion retained in audit ' + kind, bool(audit), audit)
    request('read-only user cannot create project', 'POST', '/api/v1/projects', project_body('DENIED'), 403, actor='reader')

    pid = request('create review workflow project', 'POST', '/api/v1/projects', project_body('REVIEW', complete=True), 201, actor='entry')['data']['id']
    IDS['review_project'] = pid
    request('submit real project for review', 'PATCH', f'/api/v1/projects/{pid}/submit-for-review', actor='entry')
    request('cannot delete under-review project', 'DELETE', '/api/v1/projects/' + pid, expected=422, actor='entry')
    request('approve real project', 'PATCH', f'/api/v1/projects/{pid}/approve')
    request('cannot delete approved project', 'DELETE', '/api/v1/projects/' + pid, expected=422)
    request('archive real project', 'PATCH', f'/api/v1/projects/{pid}/archive')
    request('cannot delete archived project', 'DELETE', '/api/v1/projects/' + pid, expected=422)
    check('archived project retained after refused deletes',
          sql(TARGET, f'SELECT status FROM project.projects WHERE id={q(pid)}::uuid') == 'ARCHIVED')


def file_and_member_cases():
    pid = request('create member deletion guard project', 'POST', '/api/v1/projects', project_body('MEMBER'), 201)['data']['id']
    member = request('add project member', 'POST', f'/api/v1/projects/{pid}/members',
                     {'person_name':'\u0639\u0636\u0648 \u062a\u062c\u0631\u064a\u0628\u064a','role_name':'Tester'}, 201)['data']['id']
    request('cannot delete project with member', 'DELETE', '/api/v1/projects/' + pid, expected=422)
    request('remove project member', 'DELETE', f'/api/v1/projects/{pid}/members/{member}', expected=(200,204))
    request('delete project after member removal', 'DELETE', '/api/v1/projects/' + pid)
    pid = request('create file deletion guard project', 'POST', '/api/v1/projects', project_body('FILE'), 201)['data']['id']
    categories = request('read file categories', 'GET', '/api/v1/file-categories/all')['data']
    category = next(x['id'] for x in categories if x['code'] == 'DOCUMENTS')
    payload = b'Actual multipart upload and byte-for-byte download.\n'
    boundary = 'live-' + secrets.token_hex(16)
    metadata = json.dumps({'categoryId':category}).encode()
    multipart = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n'.encode() + metadata
        + f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="live.txt"\r\nContent-Type: text/plain\r\n\r\n'.encode() + payload
        + f'\r\n--{boundary}--\r\n'.encode())
    fid = request('upload real file', 'POST', f'/api/v1/projects/{pid}/files/upload', expected=201,
                  raw=multipart, content_type='multipart/form-data; boundary=' + boundary)['data']['id']
    downloaded = request('download real uploaded file', 'GET', f'/api/v1/files/{fid}/download')
    check('manual upload download bytes identical', downloaded == payload)
    request('cannot delete project with active file', 'DELETE', '/api/v1/projects/' + pid, expected=422)
    request('soft-delete project file', 'DELETE', f'/api/v1/files/{fid}?reason=live-smoke', expected=(200,204))
    check('deleted file history retained', sql(TARGET, f'SELECT count(*) FROM project.files WHERE id={q(fid)}::uuid') == '1')
    request('soft-deleted file not downloadable', 'GET', f'/api/v1/files/{fid}/download', expected=404)
    request('restore project file', 'POST', f'/api/v1/files/{fid}/restore')
    check('restored file content preserved', request('download restored file', 'GET', f'/api/v1/files/{fid}/download') == payload)


def fingerprint():
    return {table:sql(LEGACY, f"SELECT md5(COALESCE(string_agg(row_to_json(t)::text,'' ORDER BY {key}),'')) FROM public.{table} t")
            for table,key in [('tbl_side','idside'),('tbl_project','idproject'),('tbl_files','idfile')]}


def run_batch(source_id, number):
    batch = request('create migration batch ' + number, 'POST', f'/api/v1/migration-sources/{source_id}/batches',
                    {'batchNumber':number,'notes':'live black-box test'}, 201)['data']['id']
    request('start migration batch ' + number, 'POST', f'/api/v1/migration-batches/{batch}/run', {'migration_config':{}}, 202)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        data = request('poll migration batch ' + number, 'GET', f'/api/v1/migration-batches/{batch}')['data']
        if data['status'] not in ('PLANNED','RUNNING'):
            check('migration batch succeeds ' + number, data['status'] == 'SUCCESS', data)
            return data
        time.sleep(0.5)
    raise AssertionError('Migration did not reach a terminal state within 90 seconds')


def migration_cases():
    before = fingerprint()
    source = request('create real legacy migration source', 'POST', '/api/v1/migration-sources',
        {'name':'Restored synthetic legacy database','sourceType':'LEGACY_DB','officeName':'LIVE-OFFICE',
         'databaseName':LEGACY,'host':'127.0.0.1','port':5432,'username':'archive_user','password':DB_PASSWORD}, 201)['data']
    IDS['migration_source'] = source['id']
    check('migration source does not expose password', all('password' not in k.lower() for k in source))
    check('legacy connection password encrypted at rest', sql(TARGET,
        f'SELECT (password_encrypted IS NOT NULL AND password_encrypted <> {q(DB_PASSWORD)}) FROM import.migration_sources WHERE id={q(source["id"])}::uuid') == 't')
    run_batch(source['id'], 'LIVE-FIRST')
    migrated = rows("SELECT id,name,year,status,source_reference,missing_fields FROM project.projects WHERE source_reference LIKE 'LIVE-OFFICE:tbl_project:%' ORDER BY source_reference")
    check('both legacy projects migrated', len(migrated) == 2, migrated)
    for old_id, file_id in [(1001,2001),(1002,2002)]:
        row = next(x for x in migrated if x['source_reference'] == f'LIVE-OFFICE:tbl_project:{old_id}')
        pid = row['id']
        IDS['migrated_' + str(old_id)] = pid
        data = request('open migrated project ' + str(old_id), 'GET', '/api/v1/projects/' + pid)['data']
        check('migrated project editable status ' + str(old_id), data['status'] == 'IMPORTED' and data['sourceType'] == 'MIGRATION')
        listing = request('list migrated files ' + str(old_id), 'GET', f'/api/v1/projects/{pid}/files')['data']['data']
        check('one file attached to legacy project ' + str(old_id), len(listing) == 1, listing)
        downloaded = request('download migrated binary ' + str(file_id), 'GET', f'/api/v1/files/{listing[0]["id"]}/download')
        check('legacy binary preserved ' + str(file_id), downloaded == FILES[file_id],
              {'expected_sha256':hashlib.sha256(FILES[file_id]).hexdigest(),'actual_sha256':hashlib.sha256(downloaded).hexdigest()})
        request('imported project cannot be deleted ' + str(old_id), 'DELETE', '/api/v1/projects/' + pid, expected=422)
    missing = next(x for x in migrated if x['source_reference'].endswith(':1002'))
    check('legacy missing year tracked', 'year' in (missing['missing_fields'] or ''), missing)
    report = request('read incomplete migration report', 'GET', '/api/v1/projects/incomplete-report')['data']
    check('incomplete project visible in report', missing['id'] in json.dumps(report), report)
    request('correct migrated project over HTTP', 'PATCH', '/api/v1/projects/' + missing['id'],
            {'name':'\u0645\u0634\u0631\u0648\u0639 \u0642\u062f\u064a\u0645 \u0645\u0635\u062d\u062d','year':2020,'dataCompletionStatus':'COMPLETE'})
    request('submit corrected legacy project', 'PATCH', f'/api/v1/projects/{missing["id"]}/submit-for-review')
    run_batch(source['id'], 'LIVE-REPEAT')
    check('repeat migration does not duplicate projects', sql(TARGET,
        "SELECT count(*) FROM project.projects WHERE source_reference LIKE 'LIVE-OFFICE:tbl_project:%'") == '2')
    check('repeat migration does not duplicate files', sql(TARGET,
        "SELECT count(*) FROM project.files WHERE source_reference LIKE 'LIVE-OFFICE:tbl_files:%'") == '2')
    check('legacy database unchanged by migration', before == fingerprint(), {'before':before,'after':fingerprint()})


def prepare():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('SMOKE_DISPOSABLE_ENV') != '1':
        raise SystemExit('Refusing to run: requires explicit disposable GitHub Actions environment')
    if os.environ.get('DB_USERNAME') != 'archive_user' or not DB_PASSWORD:
        raise SystemExit('Refusing unexpected database configuration')
    OUT.mkdir(parents=True, exist_ok=True)
    # Never DROP an existing database: collision must stop the test before any write.
    for database in (SEED, LEGACY, TARGET):
        sql('postgres', 'CREATE DATABASE ' + database)
    schema = '''
    CREATE TABLE public.tbl_side(idside smallint PRIMARY KEY,name varchar(50),status boolean);
    CREATE TABLE public.tbl_project(idproject integer PRIMARY KEY,idside smallint,name varchar(100),
      number varchar(50),code varchar(50),year varchar(4),admin varchar(50),detils text,note text);
    CREATE TABLE public.tbl_files(idfile integer PRIMARY KEY,filename text,filesuffix varchar(10),
      idproject integer,detils varchar(50),filebinary text);
    INSERT INTO public.tbl_side VALUES (11,'Legacy office one',true),(12,'Legacy office two',true);
    INSERT INTO public.tbl_project VALUES
      (1001,11,'Legacy research project','L-1001','LC-1','2021','Office one','Preserve these details','Original note'),
      (1002,12,NULL,NULL,NULL,'bad',NULL,NULL,'Incomplete legacy row');
    '''
    for fid,payload in FILES.items():
        schema += f"INSERT INTO public.tbl_files VALUES ({fid},'legacy-{fid}.txt','txt',{fid-1000},'Legacy text',{q(base64.b64encode(payload).decode())});\n"
    sql(SEED, schema)
    env = {**os.environ,'PGPASSWORD':DB_PASSWORD}
    backup = OUT / 'synthetic-legacy.dump'
    subprocess.run(['pg_dump','-h','127.0.0.1','-U','archive_user','-d',SEED,'-Fc','-f',str(backup)], env=env, check=True, timeout=30)
    subprocess.run(['pg_restore','-h','127.0.0.1','-U','archive_user','--exit-on-error','-d',LEGACY,str(backup)], env=env, check=True, timeout=30)
    check('old database backup restored', sql(LEGACY,'SELECT count(*) FROM public.tbl_project') == '2',
          {'source_database':LEGACY,'target_database':TARGET,'projects':2,'files':2,
           'dump_sha256':hashlib.sha256(backup.read_bytes()).hexdigest()})


def main():
    prepare()
    env = {**os.environ,
        'DB_URL':f'jdbc:postgresql://127.0.0.1:5432/{TARGET}',
        'LEGACY_DB_URL':f'jdbc:postgresql://127.0.0.1:5432/{LEGACY}',
        'LEGACY_DB_PASSWORD':DB_PASSWORD,
        'BOOTSTRAP_ADMIN_ENABLED':'true','BOOTSTRAP_ADMIN_USERNAME':ADMIN,
        'BOOTSTRAP_ADMIN_PASSWORD':PASSWORD,'BOOTSTRAP_ADMIN_EMAIL':'live-admin@example.test',
        'LEGACY_MIGRATION_ENABLED':'true','LEGACY_MIGRATION_SCHEDULE_ENABLED':'false',
        'MINIO_BUCKET':'archive-live-smoke','JWT_SECRET':base64.b64encode(secrets.token_bytes(48)).decode(),
        'SETTINGS_ENCRYPTION_KEY':base64.b64encode(secrets.token_bytes(32)).decode(),
        'SERVER_PORT':'18080','SERVER_ADDRESS':'127.0.0.1',
        'SPRING_JPA_SHOW_SQL':'false','SPRING_JPA_PROPERTIES_HIBERNATE_FORMAT_SQL':'false'}
    with (OUT / 'application.log').open('w') as log:
        process = subprocess.Popen(['java','-jar','target/Archive-0.0.1-SNAPSHOT.jar',
                                    '--server.address=127.0.0.1'], env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            for attempt in range(120):
                if process.poll() is not None:
                    raise AssertionError('Packaged application exited before readiness')
                try:
                    with urllib.request.urlopen(BASE + '/actuator/health/readiness', timeout=2) as response:
                        if response.status == 200:
                            record('packaged application ready over real HTTP', True)
                            break
                except (urllib.error.URLError, TimeoutError):
                    pass
                time.sleep(1)
            else:
                raise AssertionError('Packaged application readiness timeout')
            login(ADMIN, 'admin')
            scenario('organizations', organization_cases)
            scenario('users', user_cases)
            scenario('projects and deletion', project_cases)
            scenario('files and members', file_and_member_cases)
            scenario('restored legacy migration', migration_cases)
        except Exception as error:
            record('live application scenario', False, error=str(error))
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
    failures = sum(not x['passed'] for x in RECORDS)
    summary = f'Live HTTP checks: {len(RECORDS)}, passed: {len(RECORDS)-failures}, failed: {failures}'
    print(summary, flush=True)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write('\n## Packaged application / restored legacy database\n\n' + summary + '\n')
    raise SystemExit(1 if failures else 0)


if __name__ == '__main__':
    main()
