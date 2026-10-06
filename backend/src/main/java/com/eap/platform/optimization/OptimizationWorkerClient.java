package com.eap.platform.optimization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class OptimizationWorkerClient {
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public OptimizationWorkerClient(ObjectMapper json){this.json=json;}
    public boolean available(){
        try{
            var request=HttpRequest.newBuilder(URI.create("http://prompt-optimizer.eap.svc:8091/health")).timeout(Duration.ofSeconds(3)).GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()){byte[] data=body.readNBytes(4097);if(response.statusCode()!=200||data.length>4096)return false;var receipt=json.readTree(data);return "UP".equals(receipt.path("status").asText())&&OptimizationService.ENGINE_VERSION.equals(receipt.path("engineVersion").asText());}
        }catch(Exception e){return false;}
    }
    public boolean start(OptimizationDtos.Claim claim){
        try{
            var request=HttpRequest.newBuilder(URI.create("http://prompt-optimizer.eap.svc:8091/run")).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json").header("X-EAP-Optimization-Claim",claim.token()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("runId",claim.id())))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()){
                byte[] data=body.readNBytes(4097);if(response.statusCode()!=202||data.length>4096)return false;
                var receipt=json.readTree(data);return receipt.path("accepted").asBoolean()&&receipt.path("runId").asLong()==claim.id()&&OptimizationService.ENGINE_VERSION.equals(receipt.path("engineVersion").asText());
            }
        }catch(Exception e){return false;}
    }
}
