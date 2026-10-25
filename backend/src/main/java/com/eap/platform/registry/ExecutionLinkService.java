package com.eap.platform.registry;

import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Two authenticated reports of a relationship; never a delegation grant or a new task. */
@Service
public class ExecutionLinkService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AgentExecutionObservationService observations;
    private static final Set<String> FIELDS=Set.of("schemaVersion","producerId","framework","frameworkVersion","side",
            "linkId","relation","sourceStream","sourceRun","targetStream","targetRun","artifactDigest");
    public ExecutionLinkService(JdbcTemplate db,ObjectMapper json,AgentExecutionObservationService observations) {
        this.db=db;this.json=json;this.observations=observations;
    }
    public record Link(long id,String linkId,String relation,String sourceStream,String sourceRun,long sourceAgentId,
                       String sourceRuntimeKey,String targetStream,String targetRun,Long targetAgentId,String targetRuntimeKey,
                       String artifactDigest,Long artifactSize,String artifactLanguage,long offeredMs,Long confirmedMs,
                       String state,String evidenceKind,boolean executionPermissionGranted) { }
    public record Receipt(String linkId,String state,boolean duplicate,boolean executionPermissionGranted) { }
    public record Page(List<Link> items,boolean hasMore,long nextAfterId) { }

    @Transactional
    public Receipt report(RuntimeConnectionService.Access access,JsonNode body) {
        check(body!=null&&body.isObject()&&body.toString().length()<=8192);
        body.fieldNames().forEachRemaining(k->check(FIELDS.contains(k)));
        check(body.path("schemaVersion").isIntegralNumber()&&body.path("schemaVersion").canConvertToInt()&&body.path("schemaVersion").intValue()==1);
        String producer=uuid(body,"producerId"),framework=text(body,"framework","[A-Za-z0-9_.:/@+-]{1,64}"),version=text(body,"frameworkVersion","[A-Za-z0-9_.:/@+-]{1,32}");
        String side=text(body,"side","SOURCE|TARGET"),id=uuid(body,"linkId"),relation=text(body,"relation","DELEGATE|CODE_HANDOFF");
        String source=text(body,"sourceStream","[a-f0-9]{64}"),target=text(body,"targetStream","[a-f0-9]{64}"),sourceRun=uuid(body,"sourceRun"),targetRun=uuid(body,"targetRun");
        check(!source.equals(target));
        String digest=relation.equals("CODE_HANDOFF")?text(body,"artifactDigest","[a-f0-9]{64}"):null;
        if(digest==null)check(!body.has("artifactDigest"));
        // Revalidates the current credential generation under the existing lease -> runtime locks.
        String own=observations.lockStream(access,producer,framework,version);
        if(!own.equals(side.equals("SOURCE")?source:target))conflict();
        requireEvent(own,side.equals("SOURCE")?sourceRun:targetRun,"RUN_STARTED",null);
        JsonNode artifact=digest==null?null:requireEvent(own,side.equals("SOURCE")?sourceRun:targetRun,"ARTIFACT_REGISTERED",digest);
        // Serialize the bounded metadata writes; do not acquire another runtime's identity lock.
        db.queryForObject("select id from execution_link_guard where id=1 for update",Long.class);
        var found=query("select * from execution_links where link_id=?",id);
        if(!found.isEmpty()) {
            Link old=found.getFirst();
            if(!old.relation.equals(relation)||!old.sourceStream.equals(source)||!old.sourceRun.equals(sourceRun)
                    ||!old.targetStream.equals(target)||!old.targetRun.equals(targetRun)||!Objects.equals(old.artifactDigest,digest))conflict();
            if(side.equals("SOURCE"))return receipt(old,true);
            if(old.sourceRuntimeKey.equals(access.instanceKey()))conflict();
            if(artifact!=null&&(!Objects.equals(old.artifactSize,artifact.path("sizeBytes").longValue())
                    ||!Objects.equals(old.artifactLanguage,artifact.path("language").asText())))conflict();
            if(old.confirmedMs!=null)return receipt(old,true);
            db.update("update execution_links set target_agent_id=?,target_runtime_key=?,confirmed_ms=? where link_id=? and confirmed_ms is null",
                    access.agentId(),access.instanceKey(),Instant.now().toEpochMilli(),id);
            return new Receipt(id,"CONFIRMED",false,false);
        }
        if(!side.equals("SOURCE"))conflict();
        check(db.queryForObject("select count(*) from execution_links where source_stream=?",Long.class,source)<64);
        db.update("insert into execution_links(link_id,relation_type,source_stream,source_run,source_agent_id,source_runtime_key,target_stream,target_run,artifact_digest,artifact_size,artifact_language,offered_ms) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                id,relation,source,sourceRun,access.agentId(),access.instanceKey(),target,targetRun,digest,
                artifact==null?null:artifact.path("sizeBytes").longValue(),artifact==null?null:artifact.path("language").asText(),Instant.now().toEpochMilli());
        return new Receipt(id,"PENDING_CONFIRMATION",false,false);
    }
    private Receipt receipt(Link link,boolean duplicate){return new Receipt(link.linkId,link.state,duplicate,false);}
    private JsonNode requireEvent(String stream,String run,String kind,String digest) {
        var rows=db.queryForList("select payload_json from agent_execution_events where stream_key=? and run_id=? and event_kind=? order by producer_sequence limit 4097",String.class,stream,run,kind);
        for(String row:rows)try {
            JsonNode event=json.readTree(row);
            if(digest==null||digest.equals(event.path("artifactDigest").asText()))return event;
        }catch(java.io.IOException e){throw new IllegalStateException("执行观测读取失败");}
        throw new ConflictException("关联所需的原执行或代码登记尚未接收，请先投递原事件；不要重执行业务");
    }
    @Transactional(readOnly=true)
    public Page page(String stream,long afterId,int limit) {
        check(stream!=null&&stream.matches("[a-f0-9]{64}")&&afterId>=0&&limit>=1&&limit<=100);
        var rows=query("select * from execution_links where id>? and (source_stream=? or target_stream=?) order by id limit ?",afterId,stream,stream,limit+1);
        var page=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        return new Page(page,rows.size()>limit,page.isEmpty()?afterId:page.getLast().id);
    }
    private List<Link> query(String sql,Object... args) {
        return db.query(sql,(r,n)-> {
            Long confirmed=r.getObject("confirmed_ms")==null?null:r.getLong("confirmed_ms");
            return new Link(r.getLong("id"),r.getString("link_id"),r.getString("relation_type"),r.getString("source_stream"),r.getString("source_run"),r.getLong("source_agent_id"),r.getString("source_runtime_key"),
                    r.getString("target_stream"),r.getString("target_run"),r.getObject("target_agent_id")==null?null:r.getLong("target_agent_id"),r.getString("target_runtime_key"),r.getString("artifact_digest"),
                    r.getObject("artifact_size")==null?null:r.getLong("artifact_size"),r.getString("artifact_language"),r.getLong("offered_ms"),confirmed,
                    confirmed==null?"PENDING_CONFIRMATION":"CONFIRMED",confirmed==null?"SOURCE_PRODUCER_REPORT":"TWO_AUTHENTICATED_PRODUCER_REPORTS",false);
        },args);
    }
    private static String text(JsonNode b,String k,String pattern){JsonNode n=b.path(k);check(n.isTextual()&&n.asText().matches(pattern));return n.asText();}
    private static String uuid(JsonNode b,String k){String s=text(b,k,"[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}");check(!s.replace("-","").matches("0+"));return s;}
    private static void check(boolean valid){if(!valid)throw new BusinessException("跨实例关联格式无效或超过限制");}
    private static void conflict(){throw new ConflictException("跨实例关联身份、原编号或内容不匹配，请核对原记录");}
}
