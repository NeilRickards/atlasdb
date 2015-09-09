package com.palantir.atlasdb.endpoint;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.google.common.base.Supplier;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.impl.InMemoryKeyValueService;
import com.palantir.atlasdb.keyvalue.partition.map.InMemoryPartitionMapService;
import com.palantir.atlasdb.keyvalue.partition.map.PartitionMapService;
import com.palantir.atlasdb.keyvalue.partition.server.EndpointServer;
import com.palantir.atlasdb.keyvalue.rdbms.PostgresKeyValueConfiguration;
import com.palantir.atlasdb.keyvalue.remoting.InsufficientConsistencyExceptionMapper;
import com.palantir.atlasdb.keyvalue.remoting.KeyAlreadyExistsExceptionMapper;
import com.palantir.atlasdb.keyvalue.remoting.RemotingKeyValueService;
import com.palantir.atlasdb.keyvalue.remoting.VersionTooOldExceptionMapper;
import com.palantir.atlasdb.keyvalue.remoting.outofband.InboxPopulatingContainerRequestFilter;
import com.palantir.common.proxy.AbstractDelegatingInvocationHandler;

import io.dropwizard.Application;
import io.dropwizard.setup.Environment;

public class PostgresEndpoint extends Application<EndpointServerConfiguration> {

    private final ObjectMapper mapper = RemotingKeyValueService.kvsMapper();

    public static void main(String[] args) throws Exception {
        new PostgresEndpoint().run(args);
    }

    private PostgresEndpoint() {
    }

    @Override
    public void run(EndpointServerConfiguration configuration,
            Environment environment) throws Exception {
//        final SimpleServerFactory serverConfig = new SimpleServerFactory();
//        configuration.setServerFactory(serverConfig);
//        final HttpConnectorFactory connectorConfig = (HttpConnectorFactory) serverConfig.getConnector();

        PostgresKeyValueConfiguration config = mapper.treeToValue(configuration.extraConfig.get("postgresConfig"), PostgresKeyValueConfiguration.class);
//        PostgresKeyValueService kvs = PostgresKeyValueService.create(config);
        KeyValueService kvs = new InMemoryKeyValueService(false);
        kvs.initializeFromFreshInstance();
//        final InKvsPartitionMapService pms = InKvsPartitionMapService.create(kvs);
        final PartitionMapService pms = InMemoryPartitionMapService.createEmpty();

        final EndpointServer server = new EndpointServer(RemotingKeyValueService.createServerSide(kvs, new Supplier<Long>() {
            @Override
            public Long get() {
                return pms.getMapVersion();
            }
        }), pms);

        // Wrap server with two proxies.
        // Otherwise Jersey will not handle properly a single
        // object that implements two annotated interfaces.

        KeyValueService kvsProxy = (KeyValueService) Proxy.newProxyInstance(KeyValueService.class.getClassLoader(), new Class<?>[] { KeyValueService.class }, new AbstractDelegatingInvocationHandler() {
            @Override
            public Object getDelegate() {
                return server;
            }

            @Override
            public Object invoke(Object proxy, Method method, Object[] args)
                    throws Throwable {
                if (method.getName().equals("putMetadataForTable")) {
//                    String base64 = new String((byte[]) args[1]);
//                    args[1] = PtBytes.decodeBase64(base64.substring(1, base64.length() - 1));
                }
                return super.invoke(proxy, method, args);
            }
        });
        PartitionMapService pmsProxy = (PartitionMapService) Proxy.newProxyInstance(PartitionMapService.class.getClassLoader(), new Class<?>[] { PartitionMapService.class }, new AbstractDelegatingInvocationHandler() {
            @Override
            public Object getDelegate() {
                return server;
            }
        });

        environment.jersey().register(new InboxPopulatingContainerRequestFilter(mapper));
        environment.jersey().register(KeyAlreadyExistsExceptionMapper.instance());
        environment.jersey().register(InsufficientConsistencyExceptionMapper.instance());
        environment.jersey().register(VersionTooOldExceptionMapper.instance());
        environment.getObjectMapper().registerModule(RemotingKeyValueService.kvsModule());
        environment.getObjectMapper().registerModule(new GuavaModule());
        environment.jersey().register(kvsProxy);
        environment.jersey().register(pmsProxy);
    }

}