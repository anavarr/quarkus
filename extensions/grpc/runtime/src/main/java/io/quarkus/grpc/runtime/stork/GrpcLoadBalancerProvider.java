package io.quarkus.grpc.runtime.stork;

import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.quarkus.grpc.runtime.stork.StorkMeasuringCollector.STORK_MEASURE_TIME;
import static io.quarkus.grpc.runtime.stork.StorkMeasuringCollector.STORK_SERVICE_INSTANCE;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jboss.logging.Logger;

import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import io.smallrye.stork.Stork;
import io.smallrye.stork.api.Service;
import io.smallrye.stork.api.ServiceInstance;

public class GrpcLoadBalancerProvider extends LoadBalancerProvider {
    private static final Logger log = Logger.getLogger(GrpcLoadBalancerProvider.class);

    private final boolean requestConnections;

    /**
     * @param requestConnections if true, the load balancer will proactively request connections from available channels.
     *        This leads to better load balancing at the cost of keeping active connections.
     */
    public GrpcLoadBalancerProvider(boolean requestConnections) {
        this.requestConnections = requestConnections;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int getPriority() {
        return 4;
    }

    @Override
    public String getPolicyName() {
        return Stork.STORK;
    }

    @Override
    public NameResolver.ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
        String serviceName;
        try {
            serviceName = JsonUtil.getString(rawConfig, "service-name");
        } catch (RuntimeException e) {
            log.error("Failed to parse Stork configuration: " + rawConfig, e);
            return NameResolver.ConfigOrError.fromError(Status.INTERNAL);
        }
        if (serviceName == null) {
            log.error("No 'service-name' defined in the Stork for gRPC configuration: " + rawConfig);
            return NameResolver.ConfigOrError.fromError(Status.INTERNAL);
        }
        return NameResolver.ConfigOrError
                .fromConfig(new StorkLoadBalancerConfig(serviceName));
    }

    @Override
    public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
        return new LoadBalancer() {

            final Map<AddressesKey, Subchannel> subchannelsByEndpoint = new HashMap<>();
            final Map<AddressesKey, ServiceInstance> serviceInstanceByEndpoint = new HashMap<>();
            final Map<AddressesKey, ConnectivityState> stateByEndpoint = new HashMap<>();
            final Map<ServiceInstance, Subchannel> subChannels = new TreeMap<>(
                    Comparator.comparingLong(ServiceInstance::getId));
            final Set<ServiceInstance> activeSubchannels = new HashSet<>();

            String serviceName;

            @Override
            public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
                List<EquivalentAddressGroup> addresses = resolvedAddresses.getAddresses();

                Object loadBalancerConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
                if (!(loadBalancerConfig instanceof StorkLoadBalancerConfig)) {
                    throw new IllegalStateException(
                            "invalid configuration for a Stork Load Balancer : " + loadBalancerConfig);
                }

                StorkLoadBalancerConfig config = (StorkLoadBalancerConfig) loadBalancerConfig;
                serviceName = config.serviceName;

                Set<AddressesKey> desiredEndpoints = new HashSet<>();
                Map<ServiceInstance, Subchannel> desiredSubChannels = new TreeMap<>(
                        Comparator.comparingLong(ServiceInstance::getId));

                for (EquivalentAddressGroup addressGroup : addresses) {
                    ServiceInstance serviceInstance = addressGroup.getAttributes()
                            .get(GrpcStorkServiceDiscovery.SERVICE_INSTANCE);
                    if (serviceInstance == null) {
                        log.warn("Ignoring gRPC Stork address group without a service instance");
                        continue;
                    }

                    AddressesKey endpointKey = AddressesKey.from(addressGroup);
                    desiredEndpoints.add(endpointKey);

                    Subchannel existing = subchannelsByEndpoint.get(endpointKey);
                    if (existing != null) {
                        ServiceInstance old = serviceInstanceByEndpoint.put(endpointKey, serviceInstance);
                        if (old != null) {
                            boolean wasActive = activeSubchannels.remove(old);
                            if (wasActive) {
                                activeSubchannels.add(serviceInstance);
                            }
                        }
                        existing.updateAddresses(List.of(addressGroup));
                        desiredSubChannels.put(serviceInstance, existing);
                    } else {
                        Subchannel subchannel = createSubchannel(endpointKey, addressGroup, serviceInstance);
                        subchannelsByEndpoint.put(endpointKey, subchannel);
                        serviceInstanceByEndpoint.put(endpointKey, serviceInstance);
                        stateByEndpoint.put(endpointKey, ConnectivityState.CONNECTING);
                        desiredSubChannels.put(serviceInstance, subchannel);
                    }
                }

                List<AddressesKey> removed = new ArrayList<>();
                for (AddressesKey key : subchannelsByEndpoint.keySet()) {
                    if (!desiredEndpoints.contains(key)) {
                        removed.add(key);
                    }
                }
                for (AddressesKey key : removed) {
                    Subchannel sub = subchannelsByEndpoint.remove(key);
                    ServiceInstance si = serviceInstanceByEndpoint.remove(key);
                    stateByEndpoint.remove(key);
                    if (si != null) {
                        activeSubchannels.remove(si);
                    }
                    if (sub != null) {
                        sub.shutdown();
                    }
                }

                subChannels.clear();
                subChannels.putAll(desiredSubChannels);
                updateBalancingState();
            }

            private Subchannel createSubchannel(AddressesKey endpointKey, EquivalentAddressGroup addressGroup,
                    ServiceInstance serviceInstance) {
                CreateSubchannelArgs args = CreateSubchannelArgs.newBuilder()
                        .setAddresses(addressGroup)
                        .setAttributes(addressGroup.getAttributes())
                        .build();

                Subchannel subchannel = helper.createSubchannel(args);
                subchannel.start(new SubchannelStateListener() {
                    @Override
                    public void onSubchannelState(ConnectivityStateInfo stateInfo) {
                        if (subchannelsByEndpoint.get(endpointKey) != subchannel) {
                            return;
                        }
                        if (stateInfo.getState() == ConnectivityState.SHUTDOWN) {
                            return;
                        }

                        ConnectivityState newState = stateInfo.getState();
                        stateByEndpoint.put(endpointKey, newState);
                        ServiceInstance currentInstance = serviceInstanceByEndpoint.get(endpointKey);

                        if (newState == ConnectivityState.READY) {
                            if (currentInstance != null) {
                                activeSubchannels.add(currentInstance);
                            }
                        } else {
                            if (currentInstance != null) {
                                activeSubchannels.remove(currentInstance);
                            }
                        }

                        if (newState == TRANSIENT_FAILURE) {
                            Status status = stateInfo.getStatus();
                            log.error("gRPC Sub Channel failed", status == null ? null : status.getCause());
                            helper.refreshNameResolution();
                        } else if (newState == IDLE) {
                            helper.refreshNameResolution();
                            if (requestConnections) {
                                subchannel.requestConnection();
                            }
                        }

                        log.debugf("subchannel changed state to %s for %s", newState,
                                currentInstance != null ? currentInstance.getId() : "unknown");

                        updateBalancingState();
                    }
                });
                if (requestConnections) {
                    subchannel.requestConnection();
                }
                return subchannel;
            }

            @Override
            public void handleNameResolutionError(Status error) {
                log.errorf("Name resolution failed for service '%s'", serviceName);
                if (activeSubchannels.isEmpty()) {
                    helper.updateBalancingState(TRANSIENT_FAILURE,
                            new GrpcLoadBalancerProvider.ErrorPicker(error));
                }
            }

            @Override
            public void shutdown() {
                log.debugf("Shutting down load balancer for service '%s'", serviceName);
                for (Subchannel subchannel : subchannelsByEndpoint.values()) {
                    subchannel.shutdown();
                }
                subchannelsByEndpoint.clear();
                serviceInstanceByEndpoint.clear();
                stateByEndpoint.clear();
                subChannels.clear();
                activeSubchannels.clear();
            }

            private void updateBalancingState() {
                if (subChannels.isEmpty()) {
                    helper.updateBalancingState(TRANSIENT_FAILURE,
                            new GrpcLoadBalancerProvider.ErrorPicker(
                                    Status.UNAVAILABLE.withDescription("No Stork service instances available")));
                    return;
                }

                ConnectivityState aggregateState;
                if (!activeSubchannels.isEmpty()) {
                    aggregateState = ConnectivityState.READY;
                } else {
                    boolean connectingOrIdle = false;
                    for (ConnectivityState s : stateByEndpoint.values()) {
                        if (s == ConnectivityState.CONNECTING || s == IDLE) {
                            connectingOrIdle = true;
                            break;
                        }
                    }
                    aggregateState = connectingOrIdle ? ConnectivityState.CONNECTING : TRANSIENT_FAILURE;
                }

                if (aggregateState == TRANSIENT_FAILURE) {
                    helper.updateBalancingState(aggregateState, new GrpcLoadBalancerProvider.ErrorPicker(Status.UNAVAILABLE));
                } else {
                    Map<ServiceInstance, Subchannel> copy = new TreeMap<>(
                            Comparator.comparingLong(ServiceInstance::getId));
                    copy.putAll(subChannels);
                    helper.updateBalancingState(aggregateState, new StorkSubchannelPicker(
                            copy, serviceName, new HashSet<>(activeSubchannels)));
                }
            }
        };
    }

    static class StorkLoadBalancerConfig {
        final String serviceName;

        StorkLoadBalancerConfig(String serviceName) {
            this.serviceName = serviceName;
        }
    }

    static final class AddressesKey {
        private final List<SocketAddress> addresses;

        private AddressesKey(List<SocketAddress> addresses) {
            this.addresses = addresses;
        }

        static AddressesKey from(EquivalentAddressGroup addressGroup) {
            return new AddressesKey(List.copyOf(addressGroup.getAddresses()));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AddressesKey)) {
                return false;
            }
            AddressesKey that = (AddressesKey) o;
            return addresses.equals(that.addresses);
        }

        @Override
        public int hashCode() {
            return addresses.hashCode();
        }
    }

    static class StorkSubchannelPicker extends LoadBalancer.SubchannelPicker {
        private final Map<ServiceInstance, LoadBalancer.Subchannel> subChannels;
        private final String serviceName;
        private final Set<ServiceInstance> activeServiceInstances;

        StorkSubchannelPicker(Map<ServiceInstance, LoadBalancer.Subchannel> subChannels,
                String serviceName, Set<ServiceInstance> activeServiceInstances) {
            this.subChannels = subChannels;
            this.serviceName = serviceName;
            this.activeServiceInstances = activeServiceInstances;
        }

        @Override
        public LoadBalancer.PickResult pickSubchannel(LoadBalancer.PickSubchannelArgs args) {
            Boolean measureTime = STORK_MEASURE_TIME.get();
            measureTime = measureTime != null && measureTime;
            ServiceInstance serviceInstance = pickServerInstance(measureTime);
            LoadBalancer.Subchannel subchannel = subChannels.get(serviceInstance);

            if (serviceInstance.gatherStatistics() && STORK_SERVICE_INSTANCE.get() != null) {
                STORK_SERVICE_INSTANCE.get().set(serviceInstance);
                return LoadBalancer.PickResult.withSubchannel(subchannel);
            } else {
                return LoadBalancer.PickResult.withSubchannel(subchannel);
            }
        }

        private ServiceInstance pickServerInstance(boolean measureTime) {
            Service service = Stork.getInstance().getService(serviceName);

            Set<ServiceInstance> toChooseFrom = this.activeServiceInstances;
            if (activeServiceInstances.isEmpty()) {
                toChooseFrom = subChannels.keySet();
                log.debugf("no active service instances, using all subChannels: %s", toChooseFrom);
            }
            return service.selectInstanceAndRecordStart(toChooseFrom, measureTime);
        }
    }

    static class ErrorPicker extends LoadBalancer.SubchannelPicker {
        private final Status status;

        ErrorPicker(Status status) {
            this.status = status;
        }

        @Override
        public LoadBalancer.PickResult pickSubchannel(LoadBalancer.PickSubchannelArgs args) {
            return LoadBalancer.PickResult.withError(status);
        }
    }
}
