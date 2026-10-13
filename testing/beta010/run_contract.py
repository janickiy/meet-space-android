#!/usr/bin/env python3
"""Sanitized REST contract proof against marked beta010 localhost fixture only."""
import argparse
import http.cookiejar
import json
from pathlib import Path
import secrets
import ssl
import re
import urllib.error
import urllib.request
import uuid

MARKER='meetspace-disposable-beta010'
DEFAULT_HOME=Path(__file__).resolve().parents[2]/'.audit/beta010-fixture'

class Actor:
    def __init__(self, origin, context):
        self.origin=origin;self.token=''
        self.jar=http.cookiejar.CookieJar()
        self.opener=urllib.request.build_opener(urllib.request.HTTPSHandler(context=context),urllib.request.HTTPCookieProcessor(self.jar))
    def request(self, path, method='GET', body=None, auth=True):
        headers={'Accept':'application/json'}
        if body is not None:headers['Content-Type']='application/json'
        if auth and self.token:headers['Authorization']='Bearer '+self.token
        request=urllib.request.Request(self.origin+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
        try:
            with self.opener.open(request,timeout=20) as response:
                return response.status,json.load(response),response.headers
        except urllib.error.HTTPError as error:
            with error:return error.code,{},error.headers

def run(home):
    if (home/'fixture-marker').read_text().strip()!=MARKER:raise RuntimeError('Marked beta010 fixture required')
    properties=dict(line.split('=',1) for line in (home/'live.properties').read_text().splitlines() if '=' in line)
    origin=properties['origin']
    if origin!='https://127.0.0.1:18410':raise RuntimeError('Only isolated localhost permitted')
    context=ssl.create_default_context(cafile=properties['caPath'])
    actors=[];checks=[]
    def checked(name, value):
        if not value:raise RuntimeError(name)
        checks.append({'name':name,'passed':True})
    def create(name):
        actor=Actor(origin,context)
        email='contract-beta010-'+secrets.token_hex(10)+'@example.test';password=secrets.token_urlsafe(24)
        status,result,headers=actor.request('/api/v1/auth/register','POST',{'email':email,'password':password,'displayName':name},auth=False)
        checked('registration_201_'+str(len(actors)),status==201 and result.get('status')=='success' and result['user']['email']==email)
        checked('registration_without_session_'+str(len(actors)), 'accessToken' not in result and headers.get('Set-Cookie') is None)
        status,_,_=actor.request('/api/v1/auth/register','POST',{'email':email,'password':password,'displayName':name},auth=False)
        checked('duplicate_409_'+str(len(actors)),status==409)
        status,login,_=actor.request('/api/v1/auth/login','POST',{'email':email,'password':password},auth=False)
        checked('login_200_'+str(len(actors)),status==200 and login['user']['id']==result['user']['id'])
        actor.token=login['accessToken'];actor.user=result['user']['id'];actors.append(actor)
        status,me,_=actor.request('/api/v1/auth/me')
        checked('me_identity_'+str(len(actors)),status==200 and me['user']['id']==actor.user)
        status,cap,_=actor.request('/api/v1/capabilities')
        checked('capabilities_'+str(len(actors)),status==200 and 'recordingModes' in cap['capabilities'])
        return actor
    call=None;caller_client=str(uuid.uuid4())
    try:
        caller=create('😀'*100);callee=create('Real fixture callee');outside=create('Real fixture outsider')
        status,refresh,_=caller.request('/api/v1/auth/refresh','POST',{},auth=False)
        checked('cookie_refresh_without_bearer',status==200 and refresh['user']['id']==caller.user)
        caller.token=refresh['accessToken']
        request={'targetUserId':callee.user,'clientRequestId':str(uuid.uuid4()),'clientId':caller_client}
        status,body,_=caller.request('/api/v1/calls','POST',request)
        checked('call_ringing_created',status==201 and body['item']['status']=='ringing')
        call=body['item'];call_id=call['id']
        status,replay,_=caller.request('/api/v1/calls','POST',request)
        checked('call_idempotent_replay',status==200 and replay['item']['id']==call_id)
        status,active,_=callee.request('/api/v1/calls/active')
        checked('callee_incoming_active',status==200 and active['item']['id']==call_id)
        status,_,_=outside.request('/api/v1/calls/'+call_id)
        checked('outsider_cannot_read_call',status==404)
        status,_,_=caller.request('/api/v1/calls/'+call_id+'/accept','POST',{'clientId':caller_client})
        checked('caller_cannot_accept',status==403)
        callee_client=str(uuid.uuid4())
        status,accept,_=callee.request('/api/v1/calls/'+call_id+'/accept','POST',{'clientId':callee_client})
        checked('callee_accept_creates_conference',status==200 and accept['item']['status']=='accepted' and bool(accept['item']['conferenceId']))
        conference=accept['item']['conferenceId']
        status,_,_=caller.request('/api/v1/conferences/'+conference+'/ws-ticket','POST',{'clientId':str(uuid.uuid4())})
        checked('wrong_endpoint_cannot_get_media_ticket',status==403)
        status,ticket,_=caller.request('/api/v1/conferences/'+conference+'/ws-ticket','POST',{'clientId':caller_client})
        checked('owner_endpoint_gets_media_ticket',status==201 and bool(ticket.get('ticket')))
        status,ice,_=caller.request('/api/v1/webrtc/config')
        checked('actual_turn_relay_configuration',status==200 and ice.get('iceTransportPolicy')=='relay' and bool(ice.get('iceServers')))
        status,end,_=caller.request('/api/v1/calls/'+call_id+'/end','POST',{'clientId':caller_client})
        checked('caller_end_confirmed',status==200 and end['item']['status']=='ended')
        status,active,_=callee.request('/api/v1/calls/active')
        checked('callee_active_slot_released',status==200 and active.get('item') is None)
        status,history,_=callee.request('/api/v1/conversations/'+call['conversationId']+'/messages')
        checked('actual_history_call_message',status==200 and any(item.get('call',{}).get('id')==call_id
            and item['call']['status']=='ended' for item in history['items']))
        for action,actor in [('decline',callee),('cancel',caller)]:
            request['clientRequestId']=str(uuid.uuid4())
            status,body,_=caller.request('/api/v1/calls','POST',request)
            checked('ringing_'+action,status==201)
            status,end,_=actor.request('/api/v1/calls/'+body['item']['id']+'/'+action,'POST',{} if action=='decline' else {'clientId':caller_client})
            checked('actual_'+action,status==200 and end['item']['status']==('declined' if action=='decline' else 'cancelled'))
        status,_,_=caller.request('/api/v1/auth/logout','POST',{})
        checked('logout_server_confirmation',status==200)
        status,_,_=caller.request('/api/v1/auth/me')
        checked('revoked_token_rejected',status==401)
        result={'fixture':'beta010','passed':True,'checks':checks,'checkCount':len(checks)}
        (home/'rest-contract-result.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps({'passed':True,'checkCount':len(checks),'fixture':'beta010'}))
    finally:
        if call and actors:
            actors[0].request('/api/v1/calls/'+call['id']+'/end','POST',{'clientId':caller_client})
        for actor in actors:
            actor.request('/api/v1/auth/logout','POST',{})

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--home',type=Path,default=DEFAULT_HOME)
    try:run(parser.parse_args().home.resolve())
    except Exception as error:
        tag=str(error) if isinstance(error,RuntimeError) and re.fullmatch(r'[a-z0-9_]+',str(error)) else 'fixture_check'
        print('Beta010 REST contract failed:',type(error).__name__,tag)
        raise SystemExit(1)
