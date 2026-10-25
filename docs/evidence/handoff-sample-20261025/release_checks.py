import json
import pipeline as m

def discovery():
 previous=m.load('discovery-before.json') if (m.OUT/'discovery-before.json').exists() else None
 out={}
 for table in ['discovery_endpoints','discovered_agents','discovery_observations','discovery_identity_links']:
  old=previous[table] if previous else None
  columns=old['columns'] if old else [r.split(b'\t')[0].decode() for r in m.sql('show columns from '+table).splitlines()]
  if table=='discovery_endpoints':columns=[c for c in columns if c not in ['last_heartbeat_at','last_seen_at','updated_at','queue_depth','queue_bytes','last_error','last_scan_at']]
  upper=old['maxId'] if old else int(m.sql('select coalesce(max(id),0) from '+table).strip())
  rows=sorted(m.sql('select '+','.join(columns)+' from '+table+' where id<='+str(upper)).splitlines())
  out[table]={'columns':columns,'maxId':upper,'rows':len(rows),'sha256':m.sha(b'\n'.join(rows))}
 return out

def collector():
 status=m.run(['systemctl','show','eap-ebpf-observer','-p','ActiveState','-p','MainPID','-p','NRestarts','-p','ControlGroup']).decode()
 assert 'ActiveState=active' in status and 'NRestarts=0' in status
 from pathlib import Path
 paths=['/usr/local/lib/eap-discovery-agent/eap_ebpf_observer.py','/usr/local/lib/eap-discovery-agent/eap-connect.bt','/usr/local/lib/eap-discovery-agent/launch.py','/etc/systemd/system/eap-ebpf-observer.service','/etc/eap-discovery-agent/ebpf.json']
 return {'status':status,'enabled':m.run(['systemctl','is-enabled','eap-ebpf-observer']).decode().strip(),'files':{p:m.sha(Path(p).read_bytes()) for p in paths},'secretUid':m.kget('secret','eap-ebpf-observer')['metadata']['uid']}
