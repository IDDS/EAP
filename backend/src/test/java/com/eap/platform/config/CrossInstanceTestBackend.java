package com.eap.platform.config;

import com.eap.platform.EapApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

/** Child JVM used only by the opt-in isolated MySQL multi-instance test. */
public final class CrossInstanceTestBackend {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"true".equals(System.getenv("EAP_SESSION_CLUSTER_TEST"))) {
            throw new IllegalStateException("Isolated multi-instance test only");
        }
        var context = (ServletWebServerApplicationContext) SpringApplication.run(EapApplication.class,
                "--spring.profiles.active=test", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.flyway.enabled=true", "--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/eap_it",
                "--spring.datasource.username=root", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver", "--eap.security.mode=JWT",
                "--eap.security.issuer-uri=https://idp.invalid.example/realm/eap",
                "--eap.security.jwk-set-uri=http://127.0.0.1:" + Integer.parseInt(args[0]) + "/jwks",
                "--eap.security.audience=eap-test", "--eap.security.roles-claim=roles");
        Files.writeString(Path.of(args[1]), Integer.toString(context.getWebServer().getPort()));
    }
}
