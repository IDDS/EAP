package com.eap.platform.monitoring;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.deployment.SubstrateDeploymentRepository;
import com.eap.platform.substrate.SubstrateProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Fixed, namespace-scoped queries only. No arbitrary PromQL, URLs or credentials accepted from clients. */
@Service
public class SubstrateMonitoringService {
    private final SubstrateMonitoringProperties properties;
    private final SubstrateProperties substrate;
    private final com.eap.platform.ax.AxProperties axProperties;
    private final com.eap.platform.ax.AxService ax;
    private final SubstrateDeploymentRepository deployments;
    private final AgentRepository agents;
    private final ObjectMapper mapper;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<String, Snapshot> cache = new HashMap<>();
    public SubstrateMonitoringService(SubstrateMonitoringProperties properties, SubstrateProperties substrate,
            SubstrateDeploymentRepository deployments, AgentRepository agents, ObjectMapper mapper, com.eap.platform.ax.AxProperties axProperties, com.eap.platform.ax.AxService ax) {
        this.axProperties=axProperties;this.ax=ax;
        this.properties=properties;this.substrate=substrate;this.deployments=deployments;this.agents=agents;this.mapper=mapper;
    }
    public record Point(double at, Double value) {}
    public record Series(Map<String,String> labels, List<Point> points) {}
    public record Metric(String status, List<Series> series) {}
    public record Deployment(Long agentId, String agentName, int version, String template, String status) {}
    public record Snapshot(String status, String scope, Instant queriedAt, int windowMinutes,
                           Map<String,Metric> metrics, List<Deployment> deployments, boolean deploymentsTruncated, String backend, List<com.eap.platform.ax.AxService.View> axTasks) {}

    public Snapshot snapshot(int minutes) {return snapshot(minutes,"RUNTIME");}
    public synchronized Snapshot snapshot(int minutes,String backend) {
        if(!Set.of("RUNTIME","AX").contains(backend))throw new BusinessException("监控后端仅支持 RUNTIME 或 AX");
        String cacheKey=backend+":"+minutes;
        if (!Set.of(15,60,360,1440).contains(minutes)) throw new BusinessException("时间范围仅支持 15、60、360、1440 分钟");
        Snapshot old=cache.get(cacheKey);
        if (old!=null && old.queriedAt().plusSeconds(15).isAfter(Instant.now())) return old;
        String scope="AX".equals(backend)?axProperties.getAtespace():substrate.getDeployAtespace();
        var axTasks="AX".equals(backend)?ax.list():List.<com.eap.platform.ax.AxService.View>of();
        if (scope==null || !scope.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new BusinessException("监控命名空间配置无效");
        var rows="AX".equals(backend)?List.<SubstrateDeploymentRepository.MonitorDeployment>of():deployments.monitorDeployments(scope,PageRequest.of(0,101));
        Map<Long,String> names=new HashMap<>();
        agents.findAllById(rows.stream().map(SubstrateDeploymentRepository.MonitorDeployment::getAgentId).distinct().toList())
                .forEach(a->names.put(a.getId(),a.getName()));
        var versions=rows.stream().limit(100).map(d->new Deployment(d.getAgentId(),names.getOrDefault(d.getAgentId(),"Agent "+d.getAgentId()),d.getVersion(),d.getTemplateName(),d.getStatus())).toList();
        Instant now=Instant.now();
        if(properties.getPrometheusUrl().isBlank()) return new Snapshot("DISABLED",scope,now,minutes,Map.of(),versions,rows.size()>100,backend,axTasks);
        var queries=queries(scope,minutes);
        Map<String,CompletableFuture<Metric>> futures=new LinkedHashMap<>();
        queries.forEach((key,query)->futures.put(key,CompletableFuture.supplyAsync(()->fetch(query,key.endsWith("Trend"),minutes,now),pool)));
        Map<String,Metric> metrics=new LinkedHashMap<>();
        futures.forEach((key,future)->metrics.put(key,future.join()));
        long errors=metrics.values().stream().filter(m->m.status().equals("ERROR")).count();
        String status=errors==metrics.size()?"UNAVAILABLE":errors>0?"PARTIAL":"CONNECTED";
        var result=new Snapshot(status,scope,now,minutes,metrics,versions,rows.size()>100,backend,axTasks);
        cache.put(cacheKey,result);return result;
    }
    static Map<String,String> queries(String scope,int minutes) {
        String template="ate_template_atespace=\""+scope+"\"";
        String worker="ate_workerpool_namespace=\""+scope+"\"";
        Map<String,String> q=new LinkedHashMap<>();
        // API replicas report the same global pool gauges: max deduplicates each pool/state.
        q.put("workers","sum by (ate_worker_state) (max by (ate_workerpool_name,ate_worker_state) (ate_workerpool_workers{"+worker+"}))");
        q.put("memoryByTemplate","sum by (ate_template_name) (ate_actor_stats_memory_usage_bytes{"+template+"})");
        q.put("sampledActors","sum by (ate_template_name) (ate_actor_stats_sampled_actors{"+template+"})");
        q.put("crashes","sum by (ate_failure_reason) (increase(ate_actor_crashes_total{"+template+"}["+minutes+"m]))");
        q.put("schedulerFailures","sum(increase(ate_scheduler_assignment_duration_seconds_count{"+worker+",ate_scheduler_outcome!=\"assigned\"}["+minutes+"m]))");
        q.put("memoryTrend","sum(ate_actor_stats_memory_usage_bytes{"+template+"})");
        q.put("cpuTrend","sum(rate(ate_actor_stats_cpu_time_seconds_total{"+template+"}[5m]))");
        q.put("restoreTrend",p95("ate_actor_restore_duration_seconds",template+",ate_snapshot_phase=\"total\""));
        q.put("checkpointTrend",p95("ate_actor_checkpoint_duration_seconds",template+",ate_snapshot_phase=\"total\""));
        q.put("routeTrend",p95("atenet_router_route_duration_seconds",template));
        return q;
    }
    private static String p95(String metric,String labels) { return "histogram_quantile(0.95,sum by (le) (rate("+metric+"_bucket{"+labels+"}[5m])))"; }
    private Metric fetch(String query,boolean range,int minutes,Instant now) {
        HttpURLConnection connection=null;
        try {
            URI base=URI.create(properties.getPrometheusUrl());
            if (!Set.of("http","https").contains(base.getScheme()) || base.getHost()==null || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null) throw new IllegalArgumentException();
            String params="query="+URLEncoder.encode(query,StandardCharsets.UTF_8);
            if(range) params+="&start="+(now.getEpochSecond()-minutes*60L)+"&end="+now.getEpochSecond()+"&step="+Math.max(15,minutes*60/120);
            String url=base.toString().replaceAll("/+$","")+"/api/v1/"+(range?"query_range":"query")+"?"+params;
            connection=(HttpURLConnection)URI.create(url).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(1000);connection.setReadTimeout(2500);
            if(connection.getResponseCode()!=200) throw new IllegalStateException();
            byte[] bytes;
            try(var in=connection.getInputStream()){ bytes=in.readNBytes(524289); }
            if(bytes.length>524288) throw new IllegalStateException();
            return parse(mapper.readTree(bytes),range);
        } catch(Exception ignored) { return new Metric("ERROR",List.of()); }
        finally {if(connection!=null)connection.disconnect();}
    }
    static Metric parse(JsonNode root,boolean range) {
        if(root==null || !root.path("status").asText().equals("success") || !root.path("data").path("result").isArray()
                || !root.path("data").path("resultType").asText().equals(range?"matrix":"vector")) return new Metric("ERROR",List.of());
        var results=root.path("data").path("result");
        if(results.size()>200) return new Metric("ERROR",List.of());
        List<Series> series=new ArrayList<>();
        for(var item:results) {
            Map<String,String> labels=new LinkedHashMap<>();
            for(String key:List.of("ate_worker_state","ate_template_name","ate_failure_reason"))
                if(item.path("metric").has(key)) labels.put(key,item.path("metric").path(key).asText());
            List<Point> points=new ArrayList<>();
            if(range) {
                if(!item.path("values").isArray() || item.path("values").size()>130) return new Metric("ERROR",List.of());
                for(var value:item.path("values")) points.add(point(value));
            } else points.add(point(item.path("value")));
            series.add(new Series(labels,points));
        }
        boolean present=series.stream().flatMap(s->s.points().stream()).anyMatch(p->p.value()!=null);
        return new Metric(present?"OK":"EMPTY",series);
    }
    private static Point point(JsonNode value) {
        if(!value.isArray() || value.size()!=2 || !value.get(0).isNumber()) throw new IllegalArgumentException();
        double at=value.get(0).asDouble(),number;
        try{number=Double.parseDouble(value.get(1).asText());}catch(NumberFormatException ex){number=Double.NaN;}
        return new Point(at,Double.isFinite(number)?number:null);
    }
    @PreDestroy public void close(){pool.shutdownNow();}
}
