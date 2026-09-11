package com.eap.platform.common;
import org.springframework.data.domain.Page;
import java.util.List;
public record HistoryPage<T>(List<T> items,long totalElements,int page,int size) {
    public static <T> HistoryPage<T> from(Page<T> p){return new HistoryPage<>(p.getContent(),p.getTotalElements(),p.getNumber()+1,p.getSize());}
}
