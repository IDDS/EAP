package com.eap.platform.ax;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Component
@ConfigurationProperties(prefix="eap.ax")
public class AxProperties {
 private boolean enabled;
 private boolean instanceIdentityEnabled;
 private String legacyRunnerImage="";
 private String target="ax-server.ax-system.svc:8080", atespace="eap-ax", runnerImage="";
 public boolean isEnabled(){return enabled;}
 public void setEnabled(boolean v){enabled=v;}
 public String getTarget(){return target;}
 public void setTarget(String v){target=v;}
 public String getAtespace(){return atespace;}
 public void setAtespace(String v){atespace=v;}
 public String getRunnerImage(){return runnerImage;}
 public void setRunnerImage(String v){runnerImage=v;}
 public boolean isInstanceIdentityEnabled(){return instanceIdentityEnabled;}
 public void setInstanceIdentityEnabled(boolean v){instanceIdentityEnabled=v;}
 public String getLegacyRunnerImage(){return legacyRunnerImage.isBlank()?runnerImage:legacyRunnerImage;}
 public void setLegacyRunnerImage(String v){legacyRunnerImage=v==null?"":v;}
 public boolean legacyFrozen(){return legacyRunnerImage.matches("[a-z0-9.:/_-]+@sha256:[a-f0-9]{64}");}
 public boolean ready(){return enabled && runnerImage.matches("[a-z0-9.:/_-]+@sha256:[a-f0-9]{64}");}
}
