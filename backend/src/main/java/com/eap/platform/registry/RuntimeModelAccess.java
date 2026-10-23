package com.eap.platform.registry;

import java.util.function.Supplier;

/** Server-only synchronous Gateway context, populated after machine authentication. */
public final class RuntimeModelAccess {
    private static final ThreadLocal<RuntimeConnectionService.Access> CURRENT=new ThreadLocal<>();
    private RuntimeModelAccess() {}
    public static RuntimeConnectionService.Access current(){return CURRENT.get();}
    public static <T> T call(RuntimeConnectionService.Access access,Supplier<T> work) {
        var prior=CURRENT.get();CURRENT.set(access);
        try{return work.get();}finally{if(prior==null)CURRENT.remove();else CURRENT.set(prior);}
    }
}
