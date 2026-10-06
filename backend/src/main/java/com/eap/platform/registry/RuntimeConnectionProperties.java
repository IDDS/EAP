package com.eap.platform.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix="eap.runtime.integration")
public class RuntimeConnectionProperties {
    private String signingKey="";
    private String deliveryPrivateKey="";
    private String deliveryPublicKey="";
    private int leaseSeconds=900;
    public String getDeliveryPrivateKey(){return deliveryPrivateKey;}
    public void setDeliveryPrivateKey(String value){deliveryPrivateKey=value==null?"":value;}
    public String getDeliveryPublicKey(){return deliveryPublicKey;}
    public void setDeliveryPublicKey(String value){deliveryPublicKey=value==null?"":value;}
    public int getLeaseSeconds(){return leaseSeconds;}
    public void setLeaseSeconds(int value){if(value<60||value>3600)throw new IllegalArgumentException("实例凭证期限须为60至3600秒");leaseSeconds=value;}
    public boolean deliveryReady(){return ready()&&!deliveryPrivateKey.isBlank()&&!deliveryPublicKey.isBlank();}
    public String getSigningKey(){return signingKey;}
    public void setSigningKey(String value){signingKey=value==null?"":value;}
    public boolean ready(){return signingKey.length()>=32;}
}
