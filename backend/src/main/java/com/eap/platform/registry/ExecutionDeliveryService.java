package com.eap.platform.registry;

import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Last authenticated producer snapshot, never a live queue or an execution grant. */
@Service
public class ExecutionDeliveryService {
    static final Set<String> STATES = Set.of("EMPTY", "ACKNOWLEDGED", "PENDING", "SENDING", "RETRY_WAIT", "BATCH_LIMIT",
            "RETRY_EXHAUSTED", "BLOCKED_AUTH", "BLOCKED_CONFLICT", "BLOCKED_REQUEST", "BLOCKED_RECEIPT",
            "BLOCKED_TRANSPORT", "CANCELLED", "RETRYABLE_NETWORK", "RETRYABLE_SERVER", "RETRYABLE_THROTTLED");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AgentExecutionObservationService observations;
    public ExecutionDeliveryService(JdbcTemplate db, ObjectMapper json, AgentExecutionObservationService observations) {
        this.db=db; this.json=json; this.observations=observations;
    }
    public record Receipt(String streamKey, long reportSequence, boolean duplicate) { }
    public record Delivery(long id, Long agentId, String runtimeInstanceKey, String streamKey, String framework,
            String frameworkVersion, long reportSequence, int total, int acknowledged, int pending, long attempts,
            String state, Instant reportedAt, boolean stale, long receivedEvents, String credentialState) { }
    public record Page(List<Delivery> items, boolean hasMore, long nextAfterId, Instant asOf, String evidenceKind) { }

    @Transactional
    public Receipt report(RuntimeConnectionService.Access access, JsonNode body) {
        check(body!=null && body.isObject() && body.toString().getBytes(StandardCharsets.UTF_8).length<=4096);
        var allowed=Set.of("schemaVersion","producerId","framework","frameworkVersion","reportSequence","total","acknowledged","pending","attempts","state");
        body.fieldNames().forEachRemaining(k->check(allowed.contains(k)));
        check(number(body,"schemaVersion",1,1)==1);
        String producer=text(body,"producerId","[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}");
        check(!producer.replace("-","").matches("0+"));
        String framework=text(body,"framework","[A-Za-z0-9_.:/@+-]{1,64}"), version=text(body,"frameworkVersion","[A-Za-z0-9_.:/@+-]{1,32}");
        long sequence=number(body,"reportSequence",1,9007199254740991L), attempts=number(body,"attempts",0,9007199254740991L);
        int total=(int)number(body,"total",0,4096), ack=(int)number(body,"acknowledged",0,4096), pending=(int)number(body,"pending",0,4096);
        String state=body.path("state").asText();check(STATES.contains(state) && total==ack+pending);
        check(total==0 ? state.equals("EMPTY") : pending==0 ? state.equals("ACKNOWLEDGED") : !Set.of("EMPTY","ACKNOWLEDGED").contains(state));
        var canonical=json.createObjectNode().put("reportSequence",sequence).put("total",total).put("acknowledged",ack)
                .put("pending",pending).put("attempts",attempts).put("state",state);
        String digest=hash(canonical.toString());
        String stream=observations.lockStream(access,producer,framework,version);
        var rows=db.queryForList("select report_sequence,report_digest,total_events,acknowledged_events,delivery_attempts from execution_delivery_reports where stream_key=?",stream);
        if(!rows.isEmpty()) {
            var old=rows.getFirst();long previous=((Number)old.get("report_sequence")).longValue();
            if(sequence==previous && digest.equals(old.get("report_digest")))return new Receipt(stream,sequence,true);
            if(sequence<=previous || total<((Number)old.get("total_events")).intValue()
                    || ack<((Number)old.get("acknowledged_events")).intValue() || attempts<((Number)old.get("delivery_attempts")).longValue())conflict();
        }
        // A producer may lack an acknowledgement after a lost response. Never equate its pending
        // count with missing server rows. Conversely, it cannot claim acknowledgements we lack.
        long received=db.queryForObject("select count(*) from agent_execution_events where stream_key=?",Long.class,stream);
        check(ack<=received);
        if(rows.isEmpty()) db.update("insert into execution_delivery_reports(stream_key,agent_id,runtime_instance_key,identity_generation,producer_id,framework,framework_version,report_sequence,report_digest,total_events,acknowledged_events,pending_events,delivery_attempts,delivery_state,received_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                stream,access.agentId(),access.instanceKey(),access.generation(),producer,framework,version,sequence,digest,total,ack,pending,attempts,state,Timestamp.from(Instant.now()));
        else db.update("update execution_delivery_reports set report_sequence=?,report_digest=?,total_events=?,acknowledged_events=?,pending_events=?,delivery_attempts=?,delivery_state=?,received_at=? where stream_key=?",
                sequence,digest,total,ack,pending,attempts,state,Timestamp.from(Instant.now()),stream);
        return new Receipt(stream,sequence,false);
    }

    @Transactional(readOnly=true)
    public Page page(Long agentId,String stream,long afterId,int limit) {
        check(afterId>=0 && limit>=1 && limit<=100);
        var args=new ArrayList<Object>();String where=" where d.id>?";args.add(afterId);
        if(agentId!=null){check(agentId>0);where+=" and d.agent_id=?";args.add(agentId);}
        if(stream!=null){check(stream.matches("[a-f0-9]{64}"));where+=" and d.stream_key=?";args.add(stream);}
        args.add(limit+1);Instant now=Instant.now();
        var rows=db.query("select d.*,c.generation,c.expires_at,c.verified_at,c.automatic_delivery,c.identity_expires_at,r.status as runtime_status,"+
                "(select count(*) from agent_execution_events e where e.stream_key=d.stream_key) as received_events "+
                "from execution_delivery_reports d left join runtime_connections c on c.instance_key=d.runtime_instance_key "+
                "left join agent_runtime_instances r on r.instance_key=d.runtime_instance_key"+where+" order by d.id limit ?",(r,n)->{
            String generation=r.getString("generation"),credential="AVAILABLE";
            // These lease Instants are written by Hibernate using UTC. The report's
            // received_at below is written by JDBC and keeps its connection calendar.
            var utc=Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            Timestamp expires=r.getTimestamp("expires_at",utc),identity=r.getTimestamp("identity_expires_at",utc);
            if(generation==null)credential="REVOKED_OR_UNAVAILABLE";
            else if(!generation.equals(r.getString("identity_generation")))credential="GENERATION_CHANGED";
            else if(expires==null || !expires.toInstant().isAfter(now) || r.getBoolean("automatic_delivery") && (identity==null || !identity.toInstant().isAfter(now)))credential="EXPIRED";
            else if(r.getTimestamp("verified_at")==null || r.getString("runtime_status")==null || r.getString("runtime_status").equals("OFFLINE"))credential="UNAVAILABLE";
            Instant at=r.getTimestamp("received_at").toInstant();
            return new Delivery(r.getLong("id"),r.getLong("agent_id"),r.getString("runtime_instance_key"),r.getString("stream_key"),r.getString("framework"),r.getString("framework_version"),
                    r.getLong("report_sequence"),r.getInt("total_events"),r.getInt("acknowledged_events"),r.getInt("pending_events"),r.getLong("delivery_attempts"),r.getString("delivery_state"),at,at.isBefore(now.minusSeconds(300)),r.getLong("received_events"),credential);
        },args.toArray());
        var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        return new Page(items,rows.size()>limit,items.isEmpty()?afterId:items.getLast().id(),now,"AUTHENTICATED_PRODUCER_SNAPSHOT");
    }
    private static String text(JsonNode body,String key,String pattern){var n=body.path(key);check(n.isTextual()&&n.textValue().matches(pattern));return n.textValue();}
    private static long number(JsonNode body,String key,long min,long max){var n=body.path(key);check(n.isIntegralNumber()&&n.canConvertToLong()&&n.longValue()>=min&&n.longValue()<=max);return n.longValue();}
    private static void check(boolean valid){if(!valid)throw new BusinessException("投递状态格式无效或计数不一致");}
    private static void conflict(){throw new ConflictException("投递报告过时、计数回退或原编号内容改变，请保留原日志核对");}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
