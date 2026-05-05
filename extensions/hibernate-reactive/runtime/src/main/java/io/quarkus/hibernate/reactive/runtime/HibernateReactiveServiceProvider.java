package io.quarkus.hibernate.reactive.runtime;

import java.util.concurrent.ConcurrentHashMap;

import io.vertx.core.internal.VertxBootstrap;
import io.vertx.core.spi.VertxServiceProvider;
import io.vertx.core.spi.context.storage.ContextLocal;

public class HibernateReactiveServiceProvider<T> implements VertxServiceProvider {

    @SuppressWarnings("unchecked")
    static final ContextLocal<ConcurrentHashMap> SESSIONS_LOCAL = ContextLocal.registerLocal(ConcurrentHashMap.class);

    @SuppressWarnings("unchecked")
    static final ContextLocal<ConcurrentHashMap> STATELESS_SESSIONS_LOCAL = ContextLocal
            .registerLocal(ConcurrentHashMap.class);

    @Override
    public void init(VertxBootstrap builder) {
        // ContextLocal registration happens via the static fields above.
    }
}
