package com.eap.platform.optimization;

import com.eap.platform.agent.AgentService;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.registry.RuntimeConnectionService;
import com.eap.platform.registry.RuntimeStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

/** Durable prospective contracts. There is deliberately no dispatcher or receipt-write API. */
@Service
public class TeamEvolutionService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AgentService agents;
    private final AgentRuntimeInstanceRepository runtimes;
    private final RuntimeConnectionService connections;
    @Value("${eap.team-evolution.contracts-enabled:false}") boolean enabled;
    @Value("${eap.team-evolution.budget-enabled:false}") boolean budgetEnabled;

    public TeamEvolutionService(JdbcTemplate db, ObjectMapper json, AgentService agents,
                                AgentRuntimeInstanceRepository runtimes, RuntimeConnectionService connections) {
        this.db = db; this.json = json; this.agents = agents; this.runtimes = runtimes; this.connections = connections;
    }
    public record Candidate(String id, String parentId, int generation, String changedRole) {}
    public record Attempt(String disposition, String candidateId, String reasonCode) {}
    public record Slot(String sampleId, String role, int ordinal, String operationKey, String traceId, String state) {}
    public record Evaluation(String id, String candidateId, String state, String split, String arm, List<Slot> slots) {}
    public record Summary(long id, String protocolDigest, String datasetDigest, String validationDigest,
                          String baselineId, int attempts, int maxAttempts, int maxCandidates, int maxGeneration,
                          int minimumQualityBps, String executionState, List<Candidate> candidates, List<Evaluation> evaluations) {}

    @Value("${eap.team-evolution.slot-execution-enabled:false}") boolean slotExecutionEnabled;
    @Value("${eap.team-evolution.scoring-enabled:false}") boolean scoringEnabled;
    @Value("${eap.team-evolution.holdout-enabled:false}") boolean holdoutEnabled;
    @Value("${eap.team-evolution.model-proposals-enabled:false}") boolean modelProposalsEnabled;
    public Map<String,Object> status() {
        return Map.of("modelProposalAvailable",enabled&&budgetEnabled&&modelProposalsEnabled,"contractsEnabled", enabled, "budgetAdmissionAvailable", enabled && budgetEnabled, "executionAvailable", false,
                "slotExecutionAvailable", enabled && budgetEnabled && slotExecutionEnabled, "platformScoringAvailable",enabled&&scoringEnabled,"independentHoldoutAvailable",enabled&&budgetEnabled&&slotExecutionEnabled&&scoringEnabled&&holdoutEnabled,"receiptIngestionAvailable", false, "budgetAllocated", false, "protocol", "EAP_TEAM_CONTRACT_V1");
    }
    private void enabled() { if (!enabled) throw new BusinessException("TEAM_CONTRACTS_DISABLED"); }
    public boolean contractsEnabled() { return enabled; }
    private String text(Map<String,Object> row, String key) { return (String)row.get(key); }
    private int integer(Map<String,Object> row, String key) { return ((Number)row.get(key)).intValue(); }
    JsonNode tree(String text) { try { return json.readTree(text); } catch (Exception e) { throw new BusinessException("TEAM_RECORD_UNREADABLE"); } }
    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode(); var keys = new TreeSet<String>(); node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(k -> result.set(k, sorted(node.get(k)))); return result;
        }
        if (node.isArray()) { var result = json.createArrayNode(); node.forEach(n -> result.add(sorted(n))); return result; }
        return node;
    }
    String encode(Object value) { try { return json.writeValueAsString(sorted(json.valueToTree(value))); } catch (Exception e) { throw new BusinessException("TEAM_JSON_REJECTED"); } }
    String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch (BusinessException e) { throw e; } catch (Exception e) { throw new IllegalStateException("TEAM_DIGEST_FAILED"); }
    }
    void fields(JsonNode node, String... names) {
        if (node == null || !node.isObject()) throw new BusinessException("TEAM_FIELDS_REJECTED");
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of(names))) throw new BusinessException("TEAM_FIELDS_REJECTED");
    }
    String string(JsonNode node, String key, int maximum) {
        var v = node.path(key);
        if (!v.isTextual() || v.asText().isBlank() || v.asText().getBytes(StandardCharsets.UTF_8).length > maximum)
            throw new BusinessException("TEAM_TEXT_REJECTED");
        return v.asText();
    }
    String key(JsonNode node, String field) {
        String key = string(node, field, 64);
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")) throw new BusinessException("TEAM_KEY_REJECTED");
        return key.toLowerCase(Locale.ROOT);
    }
    int bounded(JsonNode node, String field, int low, int high) {
        var v = node.path(field);
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < low || v.asInt() > high)
            throw new BusinessException("TEAM_LIMIT_REJECTED");
        return v.asInt();
    }
    String principal(String actor) {
        if (actor == null || actor.isBlank() || actor.getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new BusinessException("TEAM_PRINCIPAL_REJECTED");
        return hash(actor);
    }
    Map<String,Object> contract(long id, boolean lock) {
        var rows = db.queryForList("select * from team_evolution_contracts where id=?" + (lock ? " for update" : ""), id);
        if (rows.isEmpty()) throw new BusinessException("TEAM_CONTRACT_NOT_FOUND");
        var row = rows.getFirst();
        for (JsonNode role : tree(text(row, "protocol_json")).path("roles")) agents.get(role.path("agentId").asLong());
        return row;
    }
    void owner(Map<String,Object> row, String actor) {
        if (!principal(actor).equals(row.get("principal_digest"))) throw new ConflictException("TEAM_ORIGINAL_PRINCIPAL_REQUIRED");
    }
    void rootOpen(long id) {
        if(db.queryForObject("select count(*) from team_evolution_closures where contract_id=?",Integer.class,id)!=0)throw new ConflictException("TEAM_ROOT_CLOSED");
    }
    void evaluationOpen(long root,Evaluation evaluation) {
        rootOpen(root);
        if("VALIDATION".equals(evaluation.split())&&"CANDIDATE".equals(evaluation.arm())){searchOpen(root);return;}
        if(!holdoutEnabled||!"HOLDOUT".equals(evaluation.split()))throw new BusinessException("TEAM_HOLDOUT_DISABLED");
        var rows=db.queryForList("select * from team_evolution_holdouts where contract_id=?",root);
        if(rows.size()!=1)throw new BusinessException("TEAM_ORIGINAL_HOLDOUT_REQUIRED");
        var h=rows.getFirst();
        String column=switch(evaluation.arm()){case "BASELINE"->"baseline_evaluation_id";case "SELECTED"->"selected_evaluation_id";default->throw new BusinessException("TEAM_ORIGINAL_HOLDOUT_REQUIRED");};
        var frozen=db.queryForList("select result_json from team_evolution_frontiers where contract_id=?",String.class,root);
        if(!evaluation.id().equals(h.get(column))||frozen.size()!=1||!hash(tree(frozen.getFirst())).equals(h.get("frontier_digest")))throw new BusinessException("TEAM_ORIGINAL_HOLDOUT_REQUIRED");
    }
    void searchOpen(long id) {
        rootOpen(id);
        if(db.queryForObject("select count(*) from team_evolution_frontiers where contract_id=?",Integer.class,id)!=0)throw new ConflictException("TEAM_VALIDATION_FRONTIER_SEALED");
    }

    @Transactional
    public Summary submit(JsonNode request, String actor) {
        enabled();
        fields(request, "requestKey", "roles", "samples", "publicSamplesConfirmed", "maxCandidates", "maxAttempts", "maxGeneration", "minimumQualityBps", "maxTokens");
        String requestKey = key(request, "requestKey"), principal = principal(actor), submission = hash(List.of(principal, requestKey)), fingerprint = hash(request);
        db.queryForObject("select id from team_evolution_admission where id=1 for update", Long.class);
        var prior = db.queryForList("select id,fingerprint from team_evolution_contracts where submission_key=?", submission);
        if (!prior.isEmpty()) {
            if (!fingerprint.equals(prior.getFirst().get("fingerprint"))) throw new ConflictException("TEAM_SUBMISSION_CONFLICT");
            return get(((Number)prior.getFirst().get("id")).longValue());
        }
        int maxCandidates = bounded(request, "maxCandidates", 1, 32), maxAttempts = bounded(request, "maxAttempts", 1, 128);
        int maxGeneration = bounded(request, "maxGeneration", 0, 8), quality = bounded(request, "minimumQualityBps", 0, 10000), maxTokens = bounded(request, "maxTokens", 1, 256);
        if (!request.path("publicSamplesConfirmed").isBoolean() || !request.path("publicSamplesConfirmed").asBoolean()) throw new BusinessException("TEAM_PUBLIC_SAMPLES_REQUIRED");
        JsonNode samples = request.path("samples"); validateSamples(samples);
        JsonNode roles = request.path("roles");
        if (!roles.isArray() || roles.size() != 2) throw new BusinessException("TEAM_TWO_ROLES_REQUIRED");
        var bindings = json.createArrayNode(); var prompts = json.createObjectNode(); var names = new HashSet<String>(); var instances = new HashSet<String>(); var agentIds = new HashSet<Long>();
        for (JsonNode role : roles) {
            fields(role, "role", "agentId", "instanceKey");
            String name = key(role, "role"), instance = string(role, "instanceKey", 128);
            if (!role.path("agentId").isIntegralNumber() || !role.path("agentId").canConvertToLong() || role.path("agentId").asLong() < 1) throw new BusinessException("TEAM_AGENT_REJECTED");
            long agent = role.path("agentId").asLong();
            if (!names.add(name) || !instances.add(instance.toLowerCase(Locale.ROOT)) || !agentIds.add(agent)) throw new BusinessException("TEAM_ROLE_IDENTITY_DUPLICATED");
            if (!"ACTIVE".equals(agents.get(agent).lifecycleStatus().name())) throw new BusinessException("TEAM_AGENT_DISABLED");
            var runtime = runtimes.findByInstanceKey(instance).orElseThrow(() -> new BusinessException("TEAM_RUNTIME_UNCONFIRMED"));
            if (runtime.getAgentId() != agent || runtime.getImportedActorUid() != null || !"SUBSTRATE".equals(runtime.getProtocol())
                    || runtime.getStatus() == RuntimeStatus.OFFLINE || !runtime.supportsMachineIdentity() || !connections.executable(runtime)) throw new BusinessException("TEAM_RUNTIME_UNCONFIRMED");
            var deployments = db.queryForList("select * from substrate_agent_deployments where agent_id=? and status='READY' and source_type='RUNNER' and concat(atespace,'/',template_name)=? order by version desc", agent, runtime.getBoundActorTemplate());
            if (deployments.isEmpty()) throw new BusinessException("TEAM_DEPLOYMENT_UNCONFIRMED");
            var deployment = deployments.getFirst(); String image = text(deployment, "image_ref");
            if (image == null || !image.matches(".+@sha256:[0-9a-f]{64}")) throw new BusinessException("TEAM_IMAGE_UNCONFIRMED");
            var creation = db.queryForList("select actor_uid,revoked,deployment_key from platform_runtime_creations where instance_key=?", instance);
            if (creation.size() != 1 || !Boolean.FALSE.equals(creation.getFirst().get("revoked")) || !Objects.equals(creation.getFirst().get("actor_uid"), runtime.getBoundActorUid())
                    || !Objects.equals(creation.getFirst().get("deployment_key"), deployment.get("deployment_key"))) throw new BusinessException("TEAM_CREATION_UNCONFIRMED");
            JsonNode config = tree(text(deployment, "config_json"));
            String prompt = string(config, "systemPrompt", 8000), model = string(config, "model", 64);
            prompts.put(name, prompt);
            bindings.add(json.valueToTree(Map.ofEntries(Map.entry("role", name), Map.entry("agentId", agent), Map.entry("runtimeId", runtime.getId()),
                    Map.entry("instanceKey", runtime.getInstanceKey()), Map.entry("endpoint", runtime.getEndpoint()), Map.entry("actorUid", runtime.getBoundActorUid()),
                    Map.entry("actorTemplate", runtime.getBoundActorTemplate()), Map.entry("imageRef", image), Map.entry("deploymentVersion", deployment.get("version")),
                    Map.entry("deploymentKey", deployment.get("deployment_key")), Map.entry("configDigest", hash(config)), Map.entry("model", model))));
        }
        var validation = json.createArrayNode(); samples.forEach(s -> { if ("VALIDATION".equals(s.path("split").asText())) validation.add(s); });
        String datasetDigest = hash(samples), validationDigest = hash(validation);
        var protocol = json.createObjectNode().put("protocol", "EAP_TEAM_CONTRACT_V1").put("scorer", "EXACT_TEXT_V1")
                .put("handoff", "SERIAL_PREVIOUS_OUTPUT_V1").put("tools", "DISABLED").put("context", "EMPTY")
                .put("maxTokens", maxTokens).put("principalDigest", principal).put("datasetDigest", datasetDigest)
                .put("liveIdentityState", "NOT_CHECKED").put("executionState", "DISABLED_NO_EXECUTOR");
        protocol.set("roles", bindings); String protocolDigest = hash(protocol);
        var baseline = json.createObjectNode(); baseline.set("rolePrompts", prompts);
        String baselineId = hash(List.of(protocolDigest, validationDigest, baseline));
        db.update("insert into team_evolution_contracts(submission_key,fingerprint,principal_digest,protocol_digest,dataset_digest,validation_digest,protocol_json,dataset_json,baseline_id,max_candidates,max_attempts,max_generation,minimum_quality_bps,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                submission, fingerprint, principal, protocolDigest, datasetDigest, validationDigest, encode(protocol), encode(samples), baselineId, maxCandidates, maxAttempts, maxGeneration, quality);
        long id = db.queryForObject("select id from team_evolution_contracts where submission_key=?", Long.class, submission);
        db.update("insert into team_evolution_candidates(contract_id,candidate_id,generation,configuration_json,created_at) values(?,?,0,?,CURRENT_TIMESTAMP)", id, baselineId, encode(baseline));
        return get(id);
    }
    private void validateSamples(JsonNode samples) {
        if (!samples.isArray() || samples.size() < 3 || samples.size() > 16) throw new BusinessException("TEAM_SAMPLE_COUNT_REJECTED");
        var ids = new HashSet<String>(); var inputs = new HashSet<String>(); var groups = new HashMap<String,String>(); var counts = new HashMap<String,Integer>();
        for (JsonNode sample : samples) {
            fields(sample, "id", "groupId", "split", "input", "expected");
            String id = key(sample, "id"), group = key(sample, "groupId"), split = string(sample, "split", 16);
            if (!Set.of("TRAIN", "VALIDATION", "HOLDOUT").contains(split)) throw new BusinessException("TEAM_SPLIT_REJECTED");
            String input = Normalizer.normalize(string(sample, "input", 2000), Normalizer.Form.NFKC).strip().replaceAll("(?U)\\s+", " ");
            string(sample, "expected", 2000);
            if (!ids.add(id) || !inputs.add(input) || groups.containsKey(group) && !groups.get(group).equals(split)) throw new BusinessException("TEAM_SAMPLE_OVERLAP");
            groups.put(group, split); counts.merge(split, 1, Integer::sum);
        }
        if (!counts.keySet().equals(Set.of("TRAIN", "VALIDATION", "HOLDOUT")) || counts.get("TRAIN") > 3 || counts.get("VALIDATION") > 3 || counts.get("HOLDOUT") > 10) throw new BusinessException("TEAM_SAMPLE_SPLITS_REJECTED");
    }
    @Transactional(readOnly=true)
    public Summary get(long id) {
        var row = contract(id, false);
        var candidates = db.queryForList("select candidate_id,parent_id,generation,changed_role from team_evolution_candidates where contract_id=? order by candidate_id", id).stream()
                .map(c -> new Candidate(text(c,"candidate_id"),text(c,"parent_id"),integer(c,"generation"),text(c,"changed_role"))).toList();
        var evaluations = db.queryForList("select id from team_evolution_evaluations where contract_id=? order by id",id).stream().map(e -> evaluation(id,text(e,"id"))).toList();
        return new Summary(id,text(row,"protocol_digest"),text(row,"dataset_digest"),text(row,"validation_digest"),text(row,"baseline_id"),integer(row,"attempts"),integer(row,"max_attempts"),integer(row,"max_candidates"),integer(row,"max_generation"),integer(row,"minimum_quality_bps"),enabled&&budgetEnabled&&slotExecutionEnabled?(scoringEnabled?"SLOT_ADAPTER_PLATFORM_SCORING":"SLOT_ADAPTER_UNSCORED"):"DISABLED_NO_EXECUTOR",candidates,evaluations);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> content(long id) {
        var row = contract(id, false);
        return Map.of("protocol",tree(text(row,"protocol_json")),"samples",tree(text(row,"dataset_json")),"candidates",
                db.queryForList("select candidate_id,configuration_json from team_evolution_candidates where contract_id=? order by candidate_id", id).stream()
                        .map(c -> Map.of("id",c.get("candidate_id"),"configuration",tree(text(c,"configuration_json")))).toList());
    }
    @Transactional
    public Attempt propose(long id, JsonNode request, String actor) {
        enabled(); fields(request,"requestKey","parentId","configuration");
        String operation = key(request,"requestKey"), fingerprint = hash(request); var root = contract(id,true); owner(root,actor);
        var old = db.queryForList("select * from team_evolution_attempts where contract_id=? and operation_key=?", id,operation);
        if (!old.isEmpty()) { if (!fingerprint.equals(old.getFirst().get("fingerprint"))) throw new ConflictException("TEAM_ATTEMPT_CONFLICT"); return attempt(old.getFirst()); }
        searchOpen(id);
        if (integer(root,"attempts") >= integer(root,"max_attempts")) throw new BusinessException("TEAM_ATTEMPT_LIMIT_REACHED");
        String candidateId = null, reason = null;
        try {
            String parentId = string(request,"parentId",64).toLowerCase(Locale.ROOT);
            var parents = db.queryForList("select * from team_evolution_candidates where contract_id=? and candidate_id=?",id,parentId);
            if (parents.isEmpty()) throw new BusinessException("TEAM_PARENT_UNCONFIRMED");
            JsonNode configuration = request.path("configuration"); fields(configuration,"rolePrompts");
            JsonNode prompts = configuration.path("rolePrompts"), original = tree(text(parents.getFirst(),"configuration_json")).path("rolePrompts");
            var roles = new ArrayList<String>(); original.fieldNames().forEachRemaining(roles::add); fields(prompts,roles.toArray(String[]::new));
            var changed = new ArrayList<String>();
            for (String role : roles) { string(prompts,role,8000); if (!prompts.get(role).equals(original.get(role))) changed.add(role); }
            if (changed.size() != 1) throw new BusinessException("TEAM_SINGLE_ROLE_CHANGE_REQUIRED");
            int generation = integer(parents.getFirst(),"generation") + 1;
            if (generation > integer(root,"max_generation")) throw new BusinessException("TEAM_GENERATION_LIMIT_REACHED");
            candidateId = hash(List.of(root.get("protocol_digest"),root.get("validation_digest"),configuration));
            if (db.queryForObject("select count(*) from team_evolution_candidates where contract_id=? and candidate_id=?",Long.class,id,candidateId) != 0) throw new BusinessException("TEAM_CANDIDATE_ALREADY_REGISTERED");
            if (db.queryForObject("select count(*) from team_evolution_candidates where contract_id=?",Long.class,id) >= integer(root,"max_candidates")) throw new BusinessException("TEAM_CANDIDATE_LIMIT_REACHED");
            db.update("insert into team_evolution_candidates(contract_id,candidate_id,parent_id,generation,changed_role,configuration_json,created_at) values(?,?,?,?,?,?,CURRENT_TIMESTAMP)",id,candidateId,parentId,generation,changed.getFirst(),encode(configuration));
        } catch (BusinessException e) { reason = e.getMessage(); candidateId = null; }
        String disposition = reason == null ? "ACCEPTED" : "REJECTED";
        db.update("insert into team_evolution_attempts(contract_id,operation_key,fingerprint,candidate_id,disposition,reason_code,created_at) values(?,?,?,?,?,?,CURRENT_TIMESTAMP)",id,operation,fingerprint,candidateId,disposition,reason);
        db.update("update team_evolution_contracts set attempts=attempts+1 where id=?",id);
        return new Attempt(disposition,candidateId,reason);
    }
    private Attempt attempt(Map<String,Object> row) { return new Attempt(text(row,"disposition"),text(row,"candidate_id"),text(row,"reason_code")); }

    @Transactional
    public Evaluation enroll(long id, JsonNode request, String actor) {
        enabled(); fields(request,"requestKey","candidateId","split");
        String operation = key(request,"requestKey"), cid = string(request,"candidateId",64).toLowerCase(Locale.ROOT), fingerprint = hash(request);
        var root = contract(id,true); owner(root,actor);
        var old = db.queryForList("select id,fingerprint from team_evolution_evaluations where contract_id=? and operation_key=?",id,operation);
        if (!old.isEmpty()) { if (!fingerprint.equals(old.getFirst().get("fingerprint"))) throw new ConflictException("TEAM_EVALUATION_CONFLICT"); return evaluation(id,text(old.getFirst(),"id")); }
        searchOpen(id);
        if (!"VALIDATION".equals(request.path("split").asText())) throw new BusinessException("TEAM_ONLY_VALIDATION_ENROLLMENT");
        if (db.queryForObject("select count(*) from team_evolution_candidates where contract_id=? and candidate_id=?",Long.class,id,cid) != 1) throw new BusinessException("TEAM_CANDIDATE_UNCONFIRMED");
        if (db.queryForObject("select count(*) from team_evolution_evaluations where contract_id=? and candidate_id=?",Long.class,id,cid) != 0) throw new ConflictException("TEAM_ORIGINAL_EVALUATION_REQUIRED");
        String evaluationId = UUID.randomUUID().toString();
        db.update("insert into team_evolution_evaluations(id,contract_id,candidate_id,operation_key,fingerprint,state,created_at) values(?,?,?,?,?,'NOT_DISPATCHED',CURRENT_TIMESTAMP)",evaluationId,id,cid,operation,fingerprint);
        for (JsonNode sample : tree(text(root,"dataset_json"))) if ("VALIDATION".equals(sample.path("split").asText())) {
            int ordinal = 0;
            for (JsonNode role : tree(text(root,"protocol_json")).path("roles")) {
                String task = "team-eval:" + UUID.randomUUID(); String trace = UUID.randomUUID().toString().replace("-","");
                db.update("insert into team_evolution_slots(evaluation_id,sample_id,role_name,role_ordinal,operation_key,trace_id,state) values(?,?,?,?,?,?,'NOT_DISPATCHED')",evaluationId,sample.path("id").asText(),role.path("role").asText(),ordinal++,task,trace);
            }
        }
        return evaluation(id,evaluationId);
    }
    @Transactional(readOnly=true)
    public Evaluation evaluation(long contractId, String id) {
        id = id.toLowerCase(Locale.ROOT);
        contract(contractId,false);
        var rows = db.queryForList("select * from team_evolution_evaluations where contract_id=? and id=?",contractId,id);
        if (rows.isEmpty()) throw new BusinessException("TEAM_EVALUATION_NOT_FOUND");
        var row = rows.getFirst();
        var slots = db.queryForList("select * from team_evolution_slots where evaluation_id=? order by sample_id,role_ordinal",id).stream()
                .map(s -> new Slot(text(s,"sample_id"),text(s,"role_name"),integer(s,"role_ordinal"),text(s,"operation_key"),text(s,"trace_id"),text(s,"state"))).toList();
        return new Evaluation(id,text(row,"candidate_id"),text(row,"state"),text(row,"split_name"),text(row,"arm"),slots);
    }
}
