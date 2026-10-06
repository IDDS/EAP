package com.eap.platform.monitoring;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.deployment.SubstrateDeploymentRepository;
import com.eap.platform.substrate.SubstrateProperties;
import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SubstrateMonitoringServiceTests {
    final ObjectMapper mapper=new ObjectMapper();
    SubstrateMonitoringService service;
    HttpServer server;
    final SubstrateMonitoringProperties properties=new SubstrateMonitoringProperties();
    final AtomicInteger requests=new AtomicInteger();
    @BeforeEach void init() throws Exception {
        var deployments=mock(SubstrateDeploymentRepository.class);var agents=mock(AgentRepository.class);
        when(deployments.monitorDeployments(anyString(),any())).thenReturn(List.of());when(agents.findAllById(any())).thenReturn(List.of());
        service=new SubstrateMonitoringService(properties,new SubstrateProperties(),deployments,agents,mapper,new com.eap.platform.ax.AxProperties(),mock(com.eap.platform.ax.AxService.class));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    }
    void serve(boolean fail,boolean malformed) {
        server.createContext("/api/v1/",exchange->{requests.incrementAndGet();
            String query=URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8);
            assertTrue(query.contains("eap-agents"));
            boolean range=exchange.getRequestURI().getPath().endsWith("query_range");
            String result=range?"{\"resultType\":\"matrix\",\"result\":[{\"metric\":{},\"values\":[[100,\"NaN\"],[200,\"0.25\"]]}]}":"{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"ate_worker_state\":\"idle\"},\"value\":[200,\"0\"]}]}";
            byte[] bytes=(malformed?"bad-json":"{\"status\":\"success\",\"data\":"+result+"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail?503:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        server.start();properties.setPrometheusUrl("http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterEach void close(){service.close();server.stop(0);}
    @Test void fixedScopedQueriesDeduplicateReplicaGauges(){
        var q=SubstrateMonitoringService.queries("eap-agents",60);
        assertTrue(q.get("workers").contains("max by (ate_workerpool_name,ate_worker_state)"));
        assertTrue(q.get("restoreTrend").contains("ate_snapshot_phase=\"total\""));
        assertEquals(10,q.size());assertTrue(q.values().stream().allMatch(v->v.contains("eap-agents")));
    }
    @Test void successfulQueriesAreBoundedCachedAndDoNotTurnNaNIntoZero(){
        serve(false,false);var first=service.snapshot(60);assertEquals("CONNECTED",first.status());assertEquals(10,requests.get());
        assertNull(first.metrics().get("cpuTrend").series().getFirst().points().getFirst().value());
        assertEquals(0.0,first.metrics().get("workers").series().getFirst().points().getFirst().value());
        assertSame(first,service.snapshot(60));assertEquals(10,requests.get());
    }
    @Test void disabledMonitoringNeverContactsSource(){assertEquals("DISABLED",service.snapshot(60).status());assertEquals(0,requests.get());}
    @Test void rejectsArbitraryWindowBeforeQuery(){serve(false,false);assertThrows(BusinessException.class,()->service.snapshot(999));assertEquals(0,requests.get());}
    @Test void sourceFailureHasNoFakeZeros(){serve(true,false);var result=service.snapshot(15);assertEquals("UNAVAILABLE",result.status());assertTrue(result.metrics().values().stream().allMatch(m->m.status().equals("ERROR")&&m.series().isEmpty()));}
    @Test void malformedPayloadDoesNotExposeResponse(){serve(false,true);assertEquals("UNAVAILABLE",service.snapshot(60).status());}
    @Test void axScopeIsAllowlistedAndCacheIsSeparateFromNative() {
        assertEquals("eap-agents",service.snapshot(60,"RUNTIME").scope());
        assertEquals("eap-ax",service.snapshot(60,"AX").scope());
        assertTrue(SubstrateMonitoringService.queries("eap-ax",60).values().stream().allMatch(q->q.contains("eap-ax")));
        assertThrows(BusinessException.class,()->service.snapshot(60,"other-namespace"));
        assertEquals(0,requests.get());
    }
    @Test void emptyAndNonfiniteResultsAreDistinctFromErrors() throws Exception {
        assertEquals("EMPTY",SubstrateMonitoringService.parse(mapper.readTree("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}"),false).status());
        assertEquals("ERROR",SubstrateMonitoringService.parse(mapper.readTree("{\"status\":\"error\"}"),false).status());
    }
}
