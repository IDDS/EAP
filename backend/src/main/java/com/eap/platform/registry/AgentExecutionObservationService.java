package com.eap.platform.registry;

import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.eap.platform.config.UnauthorizedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Append-only producer reports, not execution grants or proof of a tool's business effect. */
@Service
public class AgentExecutionObservationService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AgentRuntimeInstanceRepository runtimes;
    private final RuntimeConnectionRepository leases;
    private final RuntimeConnectionService connections;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entities;
    private static final int MAX_EVENTS = 4096;
    private static final Set<String> COMMON = Set.of("eventId", "sequence", "kind", "runId", "traceId", "occurredAt", "processId");
    private static final Map<String, Set<String>> EXTRA = Map.of(
            "RUN_STARTED", Set.of("definitionKey", "parentRunId"),
            "RUN_FINISHED", Set.of("outcome"),
            "TOOL_STARTED", Set.of("toolCallId", "definitionKey", "artifactDigest"),
            "TOOL_FINISHED", Set.of("toolCallId", "outcome"),
            "ARTIFACT_REGISTERED", Set.of("artifactDigest", "sizeBytes", "language"));

    public AgentExecutionObservationService(JdbcTemplate db, ObjectMapper json, AgentRuntimeInstanceRepository runtimes,
            RuntimeConnectionRepository leases, RuntimeConnectionService connections) {
        this.db=db; this.json=json; this.runtimes=runtimes; this.leases=leases; this.connections=connections;
    }
    public record Receipt(String streamKey, int received, int recorded, int duplicates) { }
    public record Event(long id, Long agentId, String runtimeInstanceKey, String streamKey, JsonNode event, Instant receivedAt) { }
    public record EventPage(List<Event> items, boolean hasMore, long nextAfterId) { }
    public record Run(String runId, String parentRunId, String definitionKey, String state, boolean missingParent) { }
    public record Graph(String streamKey, List<Run> runs, List<JsonNode> tools, List<JsonNode> artifacts,
            List<String> gaps, boolean complete, String evidenceKind) { }

    @Transactional
    public Receipt ingest(RuntimeConnectionService.Access access, JsonNode body) {
        check(body != null && body.isObject() && body.toString().getBytes(StandardCharsets.UTF_8).length<=131072);
        fields(body, Set.of("schemaVersion","producerId","framework","frameworkVersion","events"));
        check(body.path("schemaVersion").isIntegralNumber() && body.path("schemaVersion").canConvertToInt() && body.path("schemaVersion").intValue()==1);
        String producer=uuid(body,"producerId"), framework=slug(body,"framework",64), version=slug(body,"frameworkVersion",32);
        JsonNode input=body.path("events");check(input.isArray() && !input.isEmpty() && input.size()<=64);
        String stream=lockStream(access,producer,framework,version);
        var existing=streamEvents(stream);var staged=new ArrayList<ObjectNode>();
        var byId=new HashMap<String,JsonNode>();var bySequence=new HashMap<Long,String>();var bySlot=new HashMap<String,String>();
        for(var e:existing) index(e.event(),byId,bySequence,bySlot);
        int duplicates=0;
        for(var value:input) {
            ObjectNode event=normalize(value,access,framework,version);
            String id=event.path("eventId").asText();JsonNode old=byId.get(id);
            // JSON reparsing can turn a small LongNode into IntNode; compare canonical wire values.
            if(old!=null) { if(!old.toString().equals(event.toString())) conflict();duplicates++;continue; }
            long seq=event.path("sequence").longValue();String slot=slot(event);
            if(bySequence.containsKey(seq)||bySlot.containsKey(slot)) conflict();
            index(event,byId,bySequence,bySlot);staged.add(event);
        }
        check(existing.size()+staged.size()<=MAX_EVENTS);
        validateRelations(byId.values());
        for(var event:staged) db.update("insert into agent_execution_events(stream_key,event_id,producer_sequence,event_digest,slot_key,agent_id,runtime_instance_key,producer_id,event_kind,run_id,payload_json,received_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                stream,event.path("eventId").asText(),event.path("sequence").longValue(),hash(event.toString()),slot(event),
                access.agentId(),access.instanceKey(),producer,event.path("kind").asText(),event.path("runId").asText(),event.toString(),Timestamp.from(Instant.now()));
        return new Receipt(stream,input.size(),staged.size(),duplicates);
    }
    /** Called only inside an ingestion transaction; all stream writers use lease -> runtime locks. */
    String lockStream(RuntimeConnectionService.Access access,String producer,String framework,String version) {
        // Both event and status writes share the same identity lock and producer binding.
        var lease=leases.lockForToolAccess(access.instanceKey()).orElseThrow(()->new UnauthorizedException("实例身份不可用"));
        entities.refresh(lease, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var runtime=runtimes.lockForScopedTools(access.instanceKey()).orElseThrow(()->new UnauthorizedException("实例身份不可用"));
        entities.refresh(runtime, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!Objects.equals(runtime.agentId,access.agentId()) || runtime.status==RuntimeStatus.OFFLINE
                || !Objects.equals(lease.generation,access.generation()) || lease.verifiedAt==null
                || lease.expiresAt==null || !lease.expiresAt.isAfter(Instant.now())
                || lease.automaticDelivery && (lease.identityExpiresAt==null || !lease.identityExpiresAt.isAfter(Instant.now()))) throw new UnauthorizedException("实例身份已失效");
        String stream=hash(access.instanceKey()+"|"+access.generation()+"|"+producer);
        var original=db.queryForList("select stream_key from agent_execution_events where runtime_instance_key=? and producer_id=? limit 1",String.class,access.instanceKey(),producer);
        if(!original.isEmpty()&&!original.getFirst().equals(stream)) conflict();
        var first=streamEvents(stream);
        if(!first.isEmpty() && (!first.getFirst().event().path("framework").asText().equals(framework)
                || !first.getFirst().event().path("frameworkVersion").asText().equals(version))) conflict();
        var reports=db.queryForList("select stream_key,framework,framework_version from execution_delivery_reports where runtime_instance_key=? and producer_id=?",access.instanceKey(),producer);
        if(!reports.isEmpty()) {
            var report=reports.getFirst();
            if(!stream.equals(report.get("stream_key")) || !framework.equals(report.get("framework")) || !version.equals(report.get("framework_version")))conflict();
        }
        return stream;
    }
    private ObjectNode normalize(JsonNode value, RuntimeConnectionService.Access access, String framework, String version) {
        check(value.isObject());String kind=value.path("kind").asText();check(EXTRA.containsKey(kind));
        var allowed=new HashSet<>(COMMON);allowed.addAll(EXTRA.get(kind));fields(value,allowed);
        var event=json.createObjectNode();event.put("eventId",uuid(value,"eventId"));
        check(value.path("sequence").isIntegralNumber() && value.path("sequence").canConvertToLong());
        long sequence=value.path("sequence").longValue();check(sequence>0 && sequence<=9007199254740991L);event.put("sequence",sequence);
        event.put("kind",kind);String run=uuid(value,"runId");event.put("runId",run);
        String raw=value.path("traceId").asText();check(raw.matches("[a-f0-9]{32}") && !raw.matches("0+"));
        event.put("traceId",connections.resolveTrace(access,raw));
        try { Instant at=Instant.parse(value.path("occurredAt").asText());check(!at.isAfter(Instant.now().plusSeconds(300)) && !at.isBefore(Instant.parse("2020-01-01T00:00:00Z")));event.put("occurredAt",at.toString()); }
        catch (BusinessException e) { throw e; } catch(Exception e) { throw invalid(); }
        check(value.path("processId").isIntegralNumber() && value.path("processId").canConvertToInt() && value.path("processId").intValue()>0);
        event.put("processId",value.path("processId").intValue());event.put("framework",framework);event.put("frameworkVersion",version);
        if(kind.equals("RUN_STARTED") || kind.equals("TOOL_STARTED")) event.put("definitionKey",slug(value,"definitionKey",64));
        if(kind.equals("RUN_STARTED") && value.has("parentRunId")) {String parent=uuid(value,"parentRunId");check(!parent.equals(run));event.put("parentRunId",parent);}
        if(kind.startsWith("TOOL_")) event.put("toolCallId",uuid(value,"toolCallId"));
        if(kind.endsWith("FINISHED")) {String outcome=value.path("outcome").asText();check(Set.of("SUCCEEDED","FAILED","CANCELLED").contains(outcome));event.put("outcome",outcome);}
        if(kind.equals("ARTIFACT_REGISTERED") || value.has("artifactDigest")) {String digest=value.path("artifactDigest").asText();check(digest.matches("[a-f0-9]{64}"));event.put("artifactDigest",digest);}
        if(kind.equals("ARTIFACT_REGISTERED")) {
            check(value.path("sizeBytes").isIntegralNumber() && value.path("sizeBytes").canConvertToInt());int size=value.path("sizeBytes").intValue();check(size>=0 && size<=1048576);event.put("sizeBytes",size);
            String language=value.path("language").asText();check(Set.of("PYTHON","JAVASCRIPT","SHELL","OTHER").contains(language));event.put("language",language);
        }
        return event;
    }
    private static void validateRelations(Collection<? extends JsonNode> events) {
        var parents=new HashMap<String,String>();var traces=new HashMap<String,String>();var toolOwners=new HashMap<String,String>();
        for(var e:events) {
            String run=e.path("runId").asText(),trace=e.path("traceId").asText();
            if(traces.putIfAbsent(run,trace)!=null && !traces.get(run).equals(trace)) conflict();
            if(e.path("kind").asText().equals("RUN_STARTED") && e.has("parentRunId")) parents.put(run,e.path("parentRunId").asText());
            if(e.has("toolCallId")) {String id=e.path("toolCallId").asText();if(toolOwners.putIfAbsent(id,run)!=null && !toolOwners.get(id).equals(run)) conflict();}
        }
        for(String child:parents.keySet()) {
            var seen=new HashSet<String>();String current=child;
            while(current!=null) {if(!seen.add(current)) conflict();current=parents.get(current);}
            String parent=parents.get(child);if(traces.containsKey(parent) && !traces.get(child).equals(traces.get(parent))) conflict();
        }
    }
    private static void index(JsonNode e, Map<String,JsonNode> ids, Map<Long,String> sequences, Map<String,String> slots) {
        String id=e.path("eventId").asText();ids.put(id,e);sequences.put(e.path("sequence").longValue(),id);slots.put(slot(e),id);
    }
    private static String slot(JsonNode e) {
        String kind=e.path("kind").asText();return hash(kind+"|"+e.path("runId").asText()+"|"+e.path("toolCallId").asText()+"|"+(kind.equals("ARTIFACT_REGISTERED")?e.path("artifactDigest").asText():""));
    }
    @Transactional(readOnly=true)
    public EventPage events(Long agentId,String streamKey,long afterId,int limit) {
        check(afterId>=0 && limit>=1 && limit<=500);if(streamKey!=null)check(streamKey.matches("[a-f0-9]{64}"));
        var args=new ArrayList<Object>();String sql="select * from agent_execution_events where id>?";args.add(afterId);
        if(agentId!=null){check(agentId>0);sql+=" and agent_id=?";args.add(agentId);}
        if(streamKey!=null){sql+=" and stream_key=?";args.add(streamKey);}sql+=" order by id limit ?";args.add(limit+1);
        var rows=query(sql,args.toArray());boolean more=rows.size()>limit;var page=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        return new EventPage(page,more,page.isEmpty()?afterId:page.getLast().id());
    }
    private List<Event> query(String sql,Object... args) {
        return db.query(sql,(r,n)-> {try {return new Event(r.getLong("id"),r.getLong("agent_id"),r.getString("runtime_instance_key"),r.getString("stream_key"),json.readTree(r.getString("payload_json")),r.getTimestamp("received_at").toInstant());} catch(Exception e){throw new IllegalStateException("执行观测读取失败");}},args);
    }
    private List<Event> streamEvents(String stream) {return query("select * from agent_execution_events where stream_key=? order by producer_sequence limit ?",stream,MAX_EVENTS+1);}
    @Transactional(readOnly=true)
    public Graph graph(String stream) {
        check(stream.matches("[a-f0-9]{64}"));var rows=streamEvents(stream);check(rows.size()<=MAX_EVENTS);
        var starts=new LinkedHashMap<String,JsonNode>();var ends=new HashMap<String,JsonNode>();var tools=new LinkedHashMap<String,ObjectNode>();var artifacts=new ArrayList<JsonNode>();var gaps=new LinkedHashSet<String>();
        long expected=1;
        for(var row:rows) {
            var e=row.event();long seq=e.path("sequence").longValue();if(seq!=expected) gaps.add("MISSING_SEQUENCE");expected=seq+1;
            String run=e.path("runId").asText();String kind=e.path("kind").asText();
            if(kind.equals("RUN_STARTED")) starts.put(run,e);
            else if(kind.equals("RUN_FINISHED")) ends.put(run,e);
            else if(kind.equals("ARTIFACT_REGISTERED")) artifacts.add(e);
            else {String id=e.path("toolCallId").asText();var tool=tools.computeIfAbsent(id,k->json.createObjectNode().put("toolCallId",id).put("runId",run));tool.set(kind.equals("TOOL_STARTED")?"start":"finish",e);}
        }
        var runs=new ArrayList<Run>();var all=new LinkedHashSet<>(starts.keySet());all.addAll(ends.keySet());for(var e:rows)all.add(e.event().path("runId").asText());
        for(String id:all) {
            var start=starts.get(id);var end=ends.get(id);String parent=start!=null&&start.has("parentRunId")?start.path("parentRunId").asText():null;
            boolean missing=parent!=null&&!starts.containsKey(parent);if(missing)gaps.add("MISSING_PARENT");
            String state=start==null||end==null?"INCOMPLETE":end.path("outcome").asText();if(state.equals("INCOMPLETE"))gaps.add("INCOMPLETE_RUN");
            if(start!=null&&end!=null&&start.path("sequence").longValue()>=end.path("sequence").longValue()){state="INCONSISTENT";gaps.add("RUN_ORDER_CONFLICT");}
            runs.add(new Run(id,parent,start==null?null:start.path("definitionKey").asText(),state,missing));
        }
        for(var tool:tools.values()) {
            boolean pair=tool.has("start")&&tool.has("finish");String state=pair?tool.path("finish").path("outcome").asText():"INCOMPLETE";
            if(!pair)gaps.add("INCOMPLETE_TOOL");
            if(pair&&tool.path("start").path("sequence").longValue()>=tool.path("finish").path("sequence").longValue()){state="INCONSISTENT";gaps.add("TOOL_ORDER_CONFLICT");}
            String digest=tool.path("start").path("artifactDigest").asText();if(!digest.isEmpty()&&artifacts.stream().noneMatch(a->a.path("artifactDigest").asText().equals(digest)))gaps.add("MISSING_ARTIFACT");
            tool.put("state",state);
        }
        return new Graph(stream,List.copyOf(runs),List.copyOf(tools.values()),List.copyOf(artifacts),List.copyOf(gaps),!rows.isEmpty()&&gaps.isEmpty(),"AUTHENTICATED_PRODUCER_REPORT");
    }
    private static void fields(JsonNode value,Set<String> allowed) {value.fieldNames().forEachRemaining(k->check(allowed.contains(k)));}
    private static String uuid(JsonNode n,String key) {String s=n.path(key).asText();check(s.matches("[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}")&&!s.replace("-","").matches("0+"));return s;}
    private static String slug(JsonNode n,String key,int length) {String s=n.path(key).asText();check(n.path(key).isTextual()&&s.matches("[A-Za-z0-9_.:/@+-]{1,"+length+"}"));return s;}
    private static void check(boolean ok) {if(!ok)throw invalid();}
    private static BusinessException invalid(){return new BusinessException("执行观测格式无效或超过有界限制");}
    private static void conflict(){throw new ConflictException("执行观测标识、序号或关系冲突；请保留原事件核对");}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
