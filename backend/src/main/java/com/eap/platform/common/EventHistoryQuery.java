package com.eap.platform.common;

import jakarta.validation.constraints.*;
import org.springframework.data.jpa.domain.Specification;
import java.util.ArrayList;
import jakarta.persistence.criteria.Predicate;

/** Event time, not ingestion time, determines history windows. */
public class EventHistoryQuery extends HistoryQuery {
    @Positive private Long agentId;
    @Size(max=128) private String endpointKey;
    @Size(max=128) private String eventKey;
    @Size(max=16) private String currency;
    public Long getAgentId(){return agentId;} public void setAgentId(Long value){agentId=value;}
    public String getEndpointKey(){return endpointKey;} public void setEndpointKey(String value){endpointKey=value;}
    public String getEventKey(){return eventKey;} public void setEventKey(String value){eventKey=value;}
    public String getCurrency(){return currency;} public void setCurrency(String value){currency=value;}
    @Override protected String timeField(){return "observedAt";}
    public <T> Specification<T> context(boolean directAgent) {
        return (root,query,cb)->{
            var terms=new ArrayList<Predicate>();
            if(directAgent && agentId!=null) terms.add(cb.equal(root.get("agentId"),agentId));
            if(endpointKey!=null && !endpointKey.isBlank()) terms.add(cb.equal(root.get("endpointKey"),endpointKey));
            if(eventKey!=null && !eventKey.isBlank()) terms.add(cb.equal(root.get("eventKey"),eventKey));
            return cb.and(terms.toArray(Predicate[]::new));
        };
    }
}
