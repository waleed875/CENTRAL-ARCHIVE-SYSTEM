#!/usr/bin/env python3
"""Ordinary invalid-value acceptance tests, ONLY for the disposable CI fixture.

Uses the existing loopback packaged-JAR runner. No public hosts, exploitation
payloads, production data, or unbounded requests. Records actual HTTP responses.
"""
import json
import re
import uuid
from pathlib import Path
from urllib.parse import urlencode
import live_api_smoke as app

ABSENT = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
ROOT = '/api/v1'
COVERED = set()
OPERATIONS = set()
READ_WITHOUT_ARGUMENTS = set()
SEQUENCE = 0


def next_code():
    global SEQUENCE
    SEQUENCE += 1
    return 'INPUT-' + str(SEQUENCE)


def verify(name, method, path, body=None, expected=(400,404,409,422), table=None, raw=None, content_type=None, operation=None):
    if operation:
        COVERED.add(operation)
    def snapshot():
        return app.sql(app.TARGET, "SELECT md5(COALESCE(string_agg(row_to_json(t)::text,'' ORDER BY id),'')) FROM " + table + ' t')
    before = snapshot() if table else None
    app.scenario('invalid value: ' + name, lambda: app.request('invalid value: ' + name, method, path,
                    body=body, expected=expected, raw=raw, content_type=content_type))
    if table:
        app.record('rejected write leaves data unchanged: ' + name, before == snapshot(), table=table)


def contract_cases():
    spec = app.request('read runtime API input contract', 'GET', '/v3/api-docs')
    (app.OUT / 'runtime-api-contract.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2),encoding='utf-8')
    def schema(value):
        while '$ref' in value:
            value = spec['components']['schemas'][value['$ref'].split('/')[-1]]
        return value
    for path, item in spec.get('paths',{}).items():
        if not path.startswith(ROOT):
            continue
        for verb, operation in item.items():
            if verb not in ('get','post','put','patch','delete'):
                continue
            key = verb.upper() + ' ' + path
            OPERATIONS.add(key)
            parameters = item.get('parameters',[]) + operation.get('parameters',[])
            actual = re.sub(r'\{[^}]+\}', ABSENT, path.replace('{type}', 'Project'))
            contents = operation.get('requestBody',{}).get('content',{})
            body_schema = contents.get('application/json',{}).get('schema')
            if body_schema:
                verify('incomplete JSON '+key,verb.upper(),actual,raw=b'{',content_type='application/json',expected=400,operation=key)
                if operation.get('requestBody',{}).get('required'):
                    verify('null request '+key,verb.upper(),actual,raw=b'null',content_type='application/json',expected=400,operation=key)
            for p in parameters:
                definition = schema(p.get('schema',{}))
                if p.get('in') == 'path' and definition.get('format') == 'uuid':
                    bad = path.replace('{'+p['name']+'}', 'incorrect-id').replace('{type}', 'Project')
                    bad = re.sub(r'\{[^}]+\}', ABSENT, bad)
                    form = 'multipart/form-data' in contents
                    verify('incorrect identifier '+key,verb.upper(),bad,body={} if body_schema else None,
                           raw=b'--empty--\r\n' if form else None,
                           content_type='multipart/form-data; boundary=empty' if form else None,expected=400,operation=key)
                    break
            if verb == 'get':
                for p in parameters:
                    if p.get('in')!='query':
                        continue
                    definition=schema(p.get('schema',{}))
                    bad=None
                    if definition.get('format')=='uuid': bad='incorrect-id'
                    elif definition.get('type') in ('integer','number'): bad='abc'
                    elif definition.get('type')=='boolean': bad='maybe'
                    elif definition.get('format') in ('date','date-time'): bad='incorrect-date'
                    elif definition.get('enum'): bad='UNKNOWN_VALUE'
                    if bad is not None:
                        verify('query '+p['name']+' '+key,'GET',actual+'?'+urlencode({p['name']:bad}),expected=400,operation=key)
                    if p['name'] in ('page','size','limit'):
                        for bad in ('0','-1','999999999999999999999'):
                            verify('range '+p['name']+'='+bad+' '+key,'GET',actual+'?'+urlencode({p['name']:bad}),expected=400,operation=key)
            # Read-only argumentless functions have no invalid value to supply.
            if key not in COVERED and verb=='get' and '{' not in path and not parameters:
                READ_WITHOUT_ARGUMENTS.add(key)
                app.scenario('read '+key,lambda u=actual: app.request('read '+u,'GET',u))
    # Logout has no input body. A missing session must not count as a successful logout.
    app.scenario('logout without session', lambda: app.request('logout without session','POST',
                 ROOT+'/auth/logout',expected=401,actor=None))


def dictionary_cases():
    resources=[
        ('organizations','core.organizations',{'name':'Input organization','code':next_code(),'type':'COMPANY'}),
        ('roles','core.roles',{'name':'Input role','code':next_code()}),
        ('confidentiality-levels','core.confidentiality_levels',{'name':'Input clearance','code':next_code(),'levelOrder':700}),
        ('project-types','project.project_types',{'name':'Input project type','code':next_code()}),
        ('file-categories','project.file_categories',{'name':'Input category','code':next_code()})]
    for resource,table,good in resources:
        if resource == 'roles':
            role_rows = app.request('reuse supported role fixture','GET',ROOT+'/roles/all')['data']
            data = next(row for row in role_rows if row['code']=='read_only')
            good['code'] = 'read_only'
        else:
            data=app.request('create validation fixture '+resource,'POST',ROOT+'/'+resource,good,expected=(200,201))['data']
        app.IDS['input_'+resource]=data['id']
        for field in ('name','code'):
            for bad in (None,'','   ','x'*1000):
                verify(resource+' create '+field+' '+repr(bad)[:20],'POST',ROOT+'/'+resource,
                       {**good,'code':next_code(),field:bad},table=table)
        for bad in ('','   ','x'*1000):
            verify(resource+' update name '+repr(bad)[:20],'PATCH',ROOT+'/'+resource+'/'+data['id'],{'name':bad},table=table)
        verify(resource+' missing update','PATCH',ROOT+'/'+resource+'/'+ABSENT,{'name':'Valid name'},expected=404,table=table)
        verify(resource+' missing delete','DELETE',ROOT+'/'+resource+'/'+ABSENT,expected=404,table=table)
    category=app.IDS['input_file-categories']
    good={'name':'Input file type','code':next_code(),'categoryId':category,'defaultExtension':'txt'}
    data=app.request('create validation file type','POST',ROOT+'/file-types',good,expected=(200,201))['data']
    for change in ({'categoryId':ABSENT},{'categoryId':None},{'name':' '},{'code':' '},{'defaultExtension':'x'*1000}):
        verify('file type '+str(change)[:40],'POST',ROOT+'/file-types',{**good,'code':next_code(),**change},table='project.file_types')
    verify('blank file type update','PATCH',ROOT+'/file-types/'+data['id'],{'name':' '},table='project.file_types')
    verify('invalid organization type','POST',ROOT+'/organizations',{'name':'Invalid','code':next_code(),'type':'UNKNOWN'},table='core.organizations')
    verify('negative clearance order','POST',ROOT+'/confidentiality-levels',{'name':'Invalid','code':next_code(),'levelOrder':-1},table='core.confidentiality_levels')


def user_cases():
    good={'username':'input_account','password':app.PASSWORD,'fullName':'Input Account',
          'organizationId':app.IDS['DEPARTMENT'],'maxConfidentialityLevelId':app.IDS['clearance']}
    for resource in ('users','roles'):
        verify('invalid sort '+resource,'GET',ROOT+'/'+resource+'?sort=not_a_property',expected=400)
        verify('invalid sort order '+resource,'GET',ROOT+'/'+resource+'?order=SIDEWAYS',expected=400)
    uid=app.request('create validation user','POST',ROOT+'/users',good,expected=(200,201))['data']['id']
    # Full name and email are optional account-creation fields. The administration UI
    # intentionally asks only for username/password; the backend derives a display name
    # from username when fullName is absent or blank. Verify this compatibility contract
    # positively rather than treating those valid requests as malformed input.
    for label, value in [('omitted', None), ('empty', ''), ('whitespace', '  ')]:
        body = {**good, 'username': next_code().lower()}
        if value is None:
            body.pop('fullName', None)
        else:
            body['fullName'] = value
        created = app.request('optional user fullName '+label, 'POST', ROOT+'/users',
                              body, expected=(200,201))
        created_id = created['data']['id']
        stored_name = app.sql(TARGET,
            'SELECT full_name FROM core.users WHERE id=' + app.q(created_id) + '::uuid')
        app.record('optional user fullName defaults to username '+label,
                   stored_name == body['username'], observed=stored_name)

    for field,bads in [('username',[None,'','  ','x'*51]),('password',[None,'','short','x'*101]),
                       ('fullName',['x'*201]),('email',['not-an-email','a@']),
                       ('organizationId',[ABSENT]),('maxConfidentialityLevelId',[ABSENT]),('roleIds',[[ABSENT],[None]])]:
        for bad in bads:
            verify('user '+field+' '+repr(bad)[:20],'POST',ROOT+'/users',
                   {**good,'username':next_code().lower(),field:bad},table='core.users')
    for change in ({'fullName':''},{'fullName':' '},{'fullName':'x'*201},{'email':'invalid-email'},{'organizationId':ABSENT},{'status':'UNKNOWN'}):
        verify('user update '+str(change)[:40],'PATCH',ROOT+'/users/'+uid,change,table='core.users')
    verify('unknown role assignment','POST',ROOT+'/users/'+uid+'/roles?roleId='+ABSENT,expected=404)
    verify('wrong password change','PATCH',ROOT+'/auth/change-password',{'currentPassword':'incorrect','newPassword':'short'},expected=(400,422),table='core.users')
    verify('empty refresh','POST',ROOT+'/auth/refresh',{'token':''},expected=(400,401))


def project_cases():
    good=app.project_body('INPUT-PROJECT',complete=True)
    for field,bads in [('name',[None,'','  ','x'*251]),('projectNumber',[None,'',' ','x'*51]),
        ('year',[None,1899,2101,'abc']),('organizationId',[None,ABSENT]),('projectTypeId',[ABSENT]),
        ('confidentialityLevelId',[ABSENT]),('dataCompletionStatus',['UNKNOWN']),('members',[[None]])]:
        for bad in bads:
            verify('project '+field+' '+repr(bad)[:20],'POST',ROOT+'/projects',
                {**good,'projectNumber':next_code(),field:bad},expected=(400,403,404,409,422),table='project.projects')
    pid=app.request('create validation draft','POST',ROOT+'/projects',app.project_body(next_code()),201)['data']['id']
    app.IDS['input_draft']=pid
    for change in ({'name':' '},{'projectNumber':''},{'year':2101},{'organizationId':ABSENT}):
        verify('draft update '+str(change),'PATCH',f'{ROOT}/projects/{pid}',change,table='project.projects')
    for action in ('submit-for-review','approve','archive'):
        verify('incomplete draft '+action,'PATCH',f'{ROOT}/projects/{pid}/{action}',expected=422,table='project.projects')
    verify('empty correction note','PATCH',f'{ROOT}/projects/{pid}/request-correction',{'review_notes':' '},expected=400,table='project.projects')
    verify('empty rejection reason','PATCH',f'{ROOT}/projects/{pid}/reject',{'rejection_reason':''},expected=400,table='project.projects')
    verify('empty member','POST',f'{ROOT}/projects/{pid}/members',{'person_name':' '},expected=400)
    verify('empty export','POST',ROOT+'/export/packages',{'projectIds':[]},expected=400)
    verify('null export item','POST',ROOT+'/export/packages',{'projectIds':[None]},expected=400)
    verify('nonexistent export project','POST',ROOT+'/export/packages',{'projectIds':[ABSENT]},expected=404)
    archived=app.IDS['review_project']
    verify('edit archived project','PATCH',f'{ROOT}/projects/{archived}',{'name':'Should not change'},expected=422,table='project.projects')
    verify('delete archived project','DELETE',f'{ROOT}/projects/{archived}',expected=422,table='project.projects')


def upload_cases():
    pid=app.IDS['input_draft']; category=app.IDS['input_file-categories']
    for label,filename,payload,metadata in [
        ('empty','empty.txt',b'',{'categoryId':category}),
        ('unknown category','sample.txt',b'ordinary text',{'categoryId':ABSENT}),
        ('missing category','sample.txt',b'ordinary text',{}),
        ('missing metadata','sample.txt',b'ordinary text',None),
        ('wrong file extension','sample.pdf',b'ordinary text, not a PDF',{'categoryId':category})]:
        boundary='input-'+uuid.uuid4().hex
        parts=b''
        if metadata is not None:
            parts+=f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n'.encode()+json.dumps(metadata).encode()+b'\r\n'
        parts+=f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: text/plain\r\n\r\n'.encode()+payload+f'\r\n--{boundary}--\r\n'.encode()
        verify('file upload '+label,'POST',f'{ROOT}/projects/{pid}/files/upload',raw=parts,
               content_type='multipart/form-data; boundary='+boundary,table='project.files')
    verify('wrong upload media type','POST',f'{ROOT}/projects/{pid}/files/upload',body={},expected=415,table='project.files')
    COVERED.add('POST '+ROOT+'/packages/receive')
    verify('receive package with missing upload','POST',ROOT+'/packages/receive',raw=b'',content_type='multipart/form-data; boundary=empty',expected=(400,415))


def migration_cases():
    sid=app.IDS['migration_source']
    for method,path,body in [('GET',f'/migration-sources/{ABSENT}',None),
        ('PATCH',f'/migration-sources/{ABSENT}',{'name':'Valid'}),('DELETE',f'/migration-sources/{ABSENT}',None),
        ('GET',f'/migration-sources/{ABSENT}/batches',None),
        ('POST',f'/migration-sources/{ABSENT}/batches',{'batchNumber':'Valid'}),
        ('GET',f'/migration-batches/{ABSENT}',None),('PATCH',f'/migration-batches/{ABSENT}',{'notes':'Valid'}),
        ('POST',f'/migration-batches/{ABSENT}/run',{'migration_config':{}}),
        ('POST',f'/migration-batches/{ABSENT}/complete',{'status':'SUCCESS'})]:
        verify('missing migration record '+method+path,method,ROOT+path,body,expected=404)
    good={'name':'Input source','officeName':next_code(),'sourceType':'LEGACY_DB'}
    for change in ({'officeName':' '},{'name':' '},{'sourceType':'UNKNOWN'},{'port':0},{'port':65536},{'host':'127.0.0.1'}):
        verify('migration source '+str(change),'POST',ROOT+'/migration-sources',{**good,'officeName':next_code(),**change},expected=400,table='import.migration_sources')
    verify('erase configured source database','PATCH',ROOT+'/migration-sources/'+sid,{'databaseName':''},expected=400,table='import.migration_sources')
    batch=app.request('create validation migration batch','POST',f'{ROOT}/migration-sources/{sid}/batches',{'batchNumber':next_code()},201)['data']['id']
    for body in ({},{'migration_config':None}):
        verify('missing migration configuration','POST',f'{ROOT}/migration-batches/{batch}/run',body,expected=400,table='import.migration_batches')
    verify('invalid completion state','POST',f'{ROOT}/migration-batches/{batch}/complete',{'status':'RUNNING'},expected=400,table='import.migration_batches')


def run_validation():
    start=len(app.RECORDS)
    for name,fn in [('API contract',contract_cases),('reference data',dictionary_cases),('users',user_cases),
                    ('projects',project_cases),('uploads',upload_cases),('migration',migration_cases)]:
        app.scenario('input validation '+name,fn)
    result={'operations':sorted(OPERATIONS),'operations_probed':sorted(COVERED),
            'argumentless_reads':sorted(READ_WITHOUT_ARGUMENTS),
            'session_only_check':['POST '+ROOT+'/auth/logout'],
            'without_generated_input_probe':sorted(OPERATIONS-COVERED),
            'note':'Input probes do not imply exhaustive business-rule coverage. Argumentless reads have no invalid input.',
            'checks':app.RECORDS[start:]}
    (app.OUT/'input-validation-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')


if __name__=='__main__':
    original=app.migration_cases
    def final_scenarios():
        app.scenario('positive restored migration',original)
        run_validation()
    app.migration_cases=final_scenarios
    app.main()
