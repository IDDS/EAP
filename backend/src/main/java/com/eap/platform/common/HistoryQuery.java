package com.eap.platform.common;

import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import jakarta.persistence.criteria.Predicate;

/** Shared bounded query contract; timestamps use [from, to). */
public class HistoryQuery {
    @Min(1) @Max(1000000) public int page = 1;
    @Min(1) @Max(100) public int size = 10;
    @Size(max=200) public String keyword;
    @Size(max=32) public String status;
    @Pattern(regexp="asc|desc") public String order = "desc";
    @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) public Instant from;
    @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) public Instant to;
    public int getPage(){return page;} public void setPage(int v){page=v;}
    public int getSize(){return size;} public void setSize(int v){size=v;}
    public String getKeyword(){return keyword;} public void setKeyword(String v){keyword=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getOrder(){return order;} public void setOrder(String v){order=v;}
    public Instant getFrom(){return from;} public void setFrom(Instant v){from=v;}
    public Instant getTo(){return to;} public void setTo(Instant v){to=v;}
    protected String timeField() { return "createdAt"; }
    public PageRequest pageable() {
        if(from!=null && to!=null && !from.isBefore(to)) throw new BusinessException("开始时间必须早于结束时间");
        return PageRequest.of(page-1,size,Sort.by(Sort.Direction.fromString(order),timeField(),"id"));
    }
    public <T> Specification<T> specification(String statusField, Object statusValue, String... fields) {
        return (root,query,cb)->{
            var terms=new ArrayList<Predicate>();
            if(statusValue!=null) terms.add(cb.equal(root.get(statusField),statusValue));
            if(from!=null) terms.add(cb.greaterThanOrEqualTo(root.get(timeField()),from));
            if(to!=null) terms.add(cb.lessThan(root.get(timeField()),to));
            if(keyword!=null && !keyword.isBlank()) {
                String escaped=keyword.trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_");
                var matches=new ArrayList<Predicate>();
                for(String field:fields) matches.add(cb.like(cb.lower(root.get(field)),"%"+escaped+"%",'!'));
                terms.add(cb.or(matches.toArray(Predicate[]::new)));
            }
            return cb.and(terms.toArray(Predicate[]::new));
        };
    }
}
