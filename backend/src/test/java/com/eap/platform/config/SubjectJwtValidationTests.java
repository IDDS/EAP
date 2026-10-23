package com.eap.platform.config;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import static org.assertj.core.api.Assertions.*;
class SubjectJwtValidationTests {
 @Test void signedRoleOnlyTokenIsRejectedWhileSubjectTokenIsAccepted()throws Exception{
  RSAKey key=new RSAKeyGenerator(2048).keyID("ephemeral-test").generate();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/jwks",e->{byte[] json=new JWKSet(key.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,json.length);e.getResponseBody().write(json);e.close();});server.start();
  try{
   var p=new SecurityProperties();p.setMode(SecurityProperties.Mode.JWT);p.setIssuerUri("https://idp.example/eap");p.setAudience("eap-api");p.setJwkSetUri("http://127.0.0.1:"+server.getAddress().getPort()+"/jwks");
   var decoder=new SecurityConfig(p).jwtDecoder();
   assertThatThrownBy(()->decoder.decode(token(key,null))).isInstanceOf(JwtException.class);
   assertThatThrownBy(()->decoder.decode(token(key," "))).isInstanceOf(JwtException.class);
   assertThat(decoder.decode(token(key,"operator-identity")).getSubject()).isEqualTo("operator-identity");
  }finally{server.stop(0);}
 }
 private String token(RSAKey key,String subject)throws Exception{
  var claims=new JWTClaimsSet.Builder().issuer("https://idp.example/eap").audience("eap-api").issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(120))).claim("roles",java.util.List.of("OPERATOR"));
  if(subject!=null)claims.subject(subject);
  var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims.build());jwt.sign(new RSASSASigner(key));return jwt.serialize();
 }
}
