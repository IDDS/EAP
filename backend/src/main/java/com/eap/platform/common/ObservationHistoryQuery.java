package com.eap.platform.common;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.domain.Specification;
public class ObservationHistoryQuery extends EventHistoryQuery {
    @Size(max=128) private String traceId;
    public String getTraceId(){return traceId;}
    public void setTraceId(String value){traceId=value;}
    public <T> Specification<T> observationContext(){
        return this.<T>context(true).and((root,q,cb)->traceId==null || traceId.isBlank()?cb.conjunction():cb.equal(root.get("traceId"),traceId));
    }
    public <T extends Enum<T>> T enumStatus(Class<T> type){
        if(status==null || status.isBlank())return null;
        try{return Enum.valueOf(type,status);}catch(IllegalArgumentException ex){throw new BusinessException("无效的筛选状态");}
    }
}
