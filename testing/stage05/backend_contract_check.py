"""Disposable actual Gin/PostgreSQL checks. Never prints auth or API bodies."""
import os
import http.cookiejar
import json
import pathlib
import ssl
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(os.environ.get('MEETSPACE_STAGE05_HOME', pathlib.Path(__file__).resolve().parents[2]/'.audit/stage05-fixture')).resolve()
PROPS = dict(line.split('=', 1) for line in (ROOT/'live.properties').read_text().splitlines() if '=' in line)
CONTEXT = ssl.create_default_context(cafile=PROPS['caPath'])
CHECKS = []

class Client:
    def __init__(self, email, password):
        self.token = None
        self.opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=CONTEXT), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        body = self.call('POST', '/api/v1/auth/login', {'email': email, 'password': password}, 200, 'real Argon2/JWT login')
        self.token = body['accessToken']
        self.id = body['user']['id']

    def call(self, method, path, body=None, expected=200, name='API check', authorized=True):
        headers = {'Content-Type': 'application/json'}
        if authorized and self.token: headers['Authorization'] = 'Bearer '+self.token
        req = urllib.request.Request(PROPS['origin']+path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            response = self.opener.open(req, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        status = response.code
        if status != expected: raise AssertionError(name+' unexpected HTTP status '+str(status))
        raw = response.read(1024*1024)
        result = json.loads(raw) if raw else {}
        CHECKS.append({'check': name, 'httpStatus': status, 'passed': True})
        return result

def ensure(condition, name):
    if not condition: raise AssertionError(name)
    CHECKS.append({'check':name,'passed':True})

def main():
    owner = Client(PROPS['email'], PROPS['password'])
    other = Client(PROPS['secondEmail'], PROPS['secondPassword'])
    owner.call('GET','/api/v1/auth/me', name='actual user repository read')
    owner.call('GET','/api/v1/capabilities', name='actual platform capabilities')
    owner.call('GET','/api/v1/me/conferences', expected=401, name='timeline rejects absent Bearer', authorized=False)
    regular = owner.call('POST','/api/v1/conferences', {'title':'Disposable regular meeting','waitingRoomEnabled':False}, 201, 'create regular meeting')['item']
    rid = regular['id']
    self_member = owner.call('GET','/api/v1/conferences/'+rid+'/participants/me', name='created owner membership')['item']
    ensure(self_member['status']=='left' and self_member['admissionState']=='admitted','owner starts registered admitted/left')
    other.call('GET','/api/v1/conferences/'+rid, expected=403, name='nonmember cannot read detail')
    other.call('GET','/api/v1/conferences/'+rid+'/participants/me', expected=403, name='missing membership is 403')
    other.call('POST','/api/v1/conferences/'+rid+'/start', expected=403, name='nonowner cannot start')
    owner.call('PUT','/api/v1/conferences/'+rid+'/schedule', {'scheduledAt':'2030-10-11T12:00:00Z'}, 409, 'created meeting cannot be rescheduled')
    owner.call('GET','/api/v1/conferences/'+rid+'/history', expected=409, name='open meeting has no closed history')
    schedule = owner.call('POST','/api/v1/conferences', {'title':'Disposable scheduled meeting','scheduledAt':'2030-10-11T12:30:00+03:00','plannedDurationMin':45,'waitingRoomEnabled':True}, 201, 'create scheduled meeting with explicit timezone')['item']
    sid = schedule['id']
    ensure(schedule['scheduledAt']=='2030-10-11T09:30:00Z','schedule normalized to UTC')
    other.call('PUT','/api/v1/conferences/'+sid+'/schedule', {'scheduledAt':'2030-10-12T12:00:00Z'}, 403, 'nonowner cannot reschedule')
    updated = owner.call('PUT','/api/v1/conferences/'+sid+'/schedule', {'scheduledAt':'2030-10-12T12:00:00Z','plannedDurationMin':60}, name='owner updates scheduled date and duration')['item']
    ensure(updated['plannedDurationMin']==60,'updated duration returned')
    owner.call('PUT','/api/v1/conferences/'+sid+'/schedule', {'scheduledAt':'2030-10-12T12:00:00Z','plannedDurationMin':1441}, 422, 'duration upper bound enforced')
    owner.call('PUT','/api/v1/conferences/'+sid+'/schedule', {'scheduledAt':'2020-01-01T00:00:00Z'}, 422, 'past schedule rejected')
    invite = schedule['inviteCode']
    member = other.call('POST','/api/v1/conference-invites/'+invite+'/join', {}, name='real invited scheduled membership')['item']
    ensure(member['admissionState']=='admitted' and member['status']=='left','scheduled invite registers admitted/left without media claim')
    owner.call('POST','/api/v1/conferences/'+sid+'/start', name='owner starts scheduled meeting')
    other.call('POST','/api/v1/conferences/'+sid+'/finish', expected=403, name='participant cannot finish')
    owner.call('POST','/api/v1/conferences/'+sid+'/cancel', expected=409, name='active meeting cannot cancel')
    joined = other.call('POST','/api/v1/conferences/'+sid+'/join', {}, name='admitted active membership joins')['item']
    ensure(joined['status']=='joined','active membership joined state')
    owner.call('POST','/api/v1/conferences/'+sid+'/join', {}, name='owner joins active membership')
    owner.call('POST','/api/v1/conferences/'+sid+'/finish', name='owner finishes active meeting')
    history = other.call('GET','/api/v1/conferences/'+sid+'/history', name='participant reads finished history')['item']
    ensure(history['participantCount']==2 and len(history['participants'])==2,'history participant count is real repository aggregate')
    ensure(history['durationSec'] is not None and history['durationSec']>=0,'history elapsed duration nonnegative')
    owner.call('POST','/api/v1/conferences/'+sid+'/start', expected=409, name='finished meeting cannot restart')
    owner.call('POST','/api/v1/conferences/'+rid+'/cancel', name='owner cancels created meeting')
    cancelled = owner.call('GET','/api/v1/conferences/'+rid+'/history', name='cancelled history readable')['item']
    ensure(cancelled['durationSec'] is None,'unstarted cancelled duration stays absent')
    for view in ('upcoming','active','past'):
        page = owner.call('GET','/api/v1/me/conferences?'+urllib.parse.urlencode({'view':view,'scope':'owned','limit':1}), name='real '+view+' timeline')
        ensure(all(not m['inviteCode'] and not m['inviteUrl'] for m in page['items']),view+' timeline excludes invite secrets')
    owner.call('GET','/api/v1/me/conferences?limit=101', expected=422, name='timeline limit bound')
    owner.call('GET','/api/v1/me/conferences?cursor=invalid', expected=422, name='malformed cursor rejected')
    owner.call('PATCH','/api/v1/auth/me', {'displayName':'Disposable revised name'}, name='actual profile name update')
    owner.call('PATCH','/api/v1/auth/me', {'displayName':'Name','email':'change@example.test'}, 400, 'unknown profile field rejected')
    owner.call('PATCH','/api/v1/auth/me', {'displayName':'\u0000'}, 422, 'profile control character rejected')
    month_ids=[]
    for title,instant in [('month-last-microsecond','2030-04-01T03:59:59.999999Z'),('month-next-start','2030-04-01T04:00:00Z')]:
        result=owner.call('POST','/api/v1/conferences',{'title':title,'scheduledAt':instant},201,'create calendar boundary fixture')
        month_ids.append(result['item']['id'])
    query={'view':'upcoming','scope':'owned','limit':100,'from':'2030-03-01T05:00:00Z','to':'2030-04-01T03:59:59.999999Z'}
    month=owner.call('GET','/api/v1/me/conferences?'+urllib.parse.urlencode(query),name='actual PostgreSQL inclusive local-month bounds')
    ids={m['id'] for m in month['items']}
    ensure(month_ids[0] in ids and month_ids[1] not in ids,'microsecond bound includes last local instant and excludes next month')
    # Cursor traversal uses independent real IDs, never parses/recreates the opaque cursor.
    seen=set(); cursor=None
    for _ in range(20):
        query={'view':'upcoming','scope':'owned','limit':1}
        if cursor: query['cursor']=cursor
        page=owner.call('GET','/api/v1/me/conferences?'+urllib.parse.urlencode(query),name='real cursor traversal')
        current={m['id'] for m in page['items']}
        ensure(not seen.intersection(current),'pagination avoids repeated IDs'); seen.update(current)
        next_cursor=page['nextCursor']
        ensure(not cursor or cursor!=next_cursor,'cursor progresses')
        cursor=next_cursor
        if not cursor: break
    ensure(set(month_ids).issubset(seen),'cursor traverses both remaining scheduled fixtures')
    # The unused actor session is revoked through real durable session middleware.
    other.call('POST','/api/v1/auth/logout', {}, name='real session logout')
    other.call('GET','/api/v1/me/conferences', expected=401, name='revoked access cannot read timeline')
    result={'backendCommit':'6ecd8b648a2aa94fc9bcc844d57784423e2c6f69','environment':'isolated localhost actual Gin + PostgreSQL 14 + validated HTTPS','checks':CHECKS,'passed':len(CHECKS),'productionMutations':False,'usesBusinessMocks':False,'mediaStackExercised':False}
    (ROOT/'backend-contract-result.json').write_text(json.dumps(result,indent=2))
    print('Actual staging backend contract checks passed:',len(CHECKS))

if __name__=='__main__':
    try: main()
    except Exception as error:
        print('Staging contract check failed:',type(error).__name__,str(error) if isinstance(error,AssertionError) else 'private diagnostic suppressed')
        raise SystemExit(1)
