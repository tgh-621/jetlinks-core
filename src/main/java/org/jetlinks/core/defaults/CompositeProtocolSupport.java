package org.jetlinks.core.defaults;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.device.*;
import org.jetlinks.core.message.codec.DeviceMessageCodec;
import org.jetlinks.core.message.codec.Transport;
import org.jetlinks.core.message.interceptor.DeviceMessageSenderInterceptor;
import org.jetlinks.core.metadata.*;
import org.jetlinks.core.server.ClientConnection;
import org.jetlinks.core.server.DeviceGatewayContext;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Getter
@Setter
public class CompositeProtocolSupport implements ProtocolSupport {

    private String id;

    private String name;

    private String description;

    private DeviceMetadataCodec metadataCodec;

    @Getter(AccessLevel.PRIVATE)
    private final Map<String, Supplier<Mono<ConfigMetadata>>> configMetadata = new ConcurrentHashMap<>();

    @Getter(AccessLevel.PRIVATE)
    private final Map<String, Supplier<Mono<DeviceMetadata>>> defaultDeviceMetadata = new ConcurrentHashMap<>();

    @Getter(AccessLevel.PRIVATE)
    private final Map<String, Supplier<Mono<DeviceMessageCodec>>> messageCodecSupports = new ConcurrentHashMap<>();

    @Getter(AccessLevel.PRIVATE)
    private Map<String, ExpandsConfigMetadataSupplier> expandsConfigSupplier = new ConcurrentHashMap<>();


    @Getter(AccessLevel.PRIVATE)
    @Setter(AccessLevel.PRIVATE)
    private DeviceMessageSenderInterceptor deviceMessageSenderInterceptor;

    @Getter(AccessLevel.PRIVATE)
    private Map<String, Authenticator> authenticators = new ConcurrentHashMap<>();

    private DeviceStateChecker deviceStateChecker;

    private volatile boolean disposed;

    private Disposable.Composite composite = Disposables.composite();

    private Mono<ConfigMetadata> initConfigMetadata = Mono.empty();

    private List<DeviceMetadataCodec> metadataCodecs = new ArrayList<>();

    private List<Consumer<Map<String, Object>>> doOnInit = new CopyOnWriteArrayList<>();

    private Function<DeviceOperator, Mono<Void>> onDeviceRegister;
    private Function<DeviceOperator, Mono<Void>> onDeviceUnRegister;
    private Function<DeviceOperator, Mono<Void>> onDeviceMetadataChanged;

    private Function<DeviceProductOperator, Mono<Void>> onProductRegister;
    private Function<DeviceProductOperator, Mono<Void>> onProductUnRegister;
    private Function<DeviceProductOperator, Mono<Void>> onProductMetadataChanged;

    private Map<String, BiFunction<ClientConnection, DeviceGatewayContext, Mono<Void>>> connectionHandlers = new ConcurrentHashMap<>();

    private Map<String, Flux<Feature>> features = new ConcurrentHashMap<>();

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        composite.dispose();
        configMetadata.clear();
        defaultDeviceMetadata.clear();
        messageCodecSupports.clear();
        expandsConfigSupplier.clear();
    }

    public void setInitConfigMetadata(ConfigMetadata metadata) {
        initConfigMetadata = Mono.just(metadata);
    }

    @Override
    public void init(Map<String, Object> configuration) {
        for (Consumer<Map<String, Object>> mapConsumer : doOnInit) {
            mapConsumer.accept(configuration);
        }
    }

    public CompositeProtocolSupport doOnDispose(Disposable disposable) {
        composite.add(disposable);
        return this;
    }

    public CompositeProtocolSupport doOnInit(Consumer<Map<String, Object>> runnable) {
        doOnInit.add(runnable);
        return this;
    }

    public void addMessageCodecSupport(Transport transport, Supplier<Mono<DeviceMessageCodec>> supplier) {
        messageCodecSupports.put(transport.getId(), supplier);
    }

    public void addMessageCodecSupport(Transport transport, DeviceMessageCodec codec) {
        messageCodecSupports.put(transport.getId(), () -> Mono.just(codec));
    }

    public void addMessageCodecSupport(DeviceMessageCodec codec) {
        addMessageCodecSupport(codec.getSupportTransport(), codec);
    }

    public void addAuthenticator(Transport transport, Authenticator authenticator) {
        authenticators.put(transport.getId(), authenticator);
    }

    public void addDefaultMetadata(Transport transport, Mono<DeviceMetadata> metadata) {
        defaultDeviceMetadata.put(transport.getId(), () -> metadata);
    }

    public void addDefaultMetadata(Transport transport, DeviceMetadata metadata) {
        defaultDeviceMetadata.put(transport.getId(), () -> Mono.just(metadata));
    }

    @Override
    public Mono<DeviceMessageSenderInterceptor> getSenderInterceptor() {
        return Mono.justOrEmpty(deviceMessageSenderInterceptor)
                   .defaultIfEmpty(DeviceMessageSenderInterceptor.DO_NOTING);
    }

    public synchronized void addMessageSenderInterceptor(DeviceMessageSenderInterceptor interceptor) {
        if (this.deviceMessageSenderInterceptor == null) {
            this.deviceMessageSenderInterceptor = interceptor;
        } else {
            CompositeDeviceMessageSenderInterceptor composite;
            if (!(this.deviceMessageSenderInterceptor instanceof CompositeDeviceMessageSenderInterceptor)) {
                composite = new CompositeDeviceMessageSenderInterceptor();
                composite.addInterceptor(this.deviceMessageSenderInterceptor);
            } else {
                composite = ((CompositeDeviceMessageSenderInterceptor) this.deviceMessageSenderInterceptor);
            }
            composite.addInterceptor(interceptor);
            this.deviceMessageSenderInterceptor = composite;
        }
    }

    public void addConfigMetadata(Transport transport, Supplier<Mono<ConfigMetadata>> metadata) {
        configMetadata.put(transport.getId(), metadata);
    }

    public void addConfigMetadata(Transport transport, ConfigMetadata metadata) {
        configMetadata.put(transport.getId(), () -> Mono.just(metadata));
    }


    public void setExpandsConfigMetadata(Transport transport,
                                         ExpandsConfigMetadataSupplier supplier) {
        expandsConfigSupplier.put(transport.getId(), supplier);
    }


    @Override
    public Flux<ConfigMetadata> getMetadataExpandsConfig(Transport transport,
                                                         DeviceMetadataType metadataType,
                                                         String metadataId,
                                                         String dataTypeId) {

        return Optional
                .ofNullable(expandsConfigSupplier.get(transport.getId()))
                .map(supplier -> supplier.getConfigMetadata(metadataType, metadataId, dataTypeId))
                .orElse(Flux.empty());
    }

    @Override
    public Mono<DeviceMetadata> getDefaultMetadata(Transport transport) {
        return Optional
                .ofNullable(defaultDeviceMetadata.get(transport.getId()))
                .map(Supplier::get)
                .orElse(Mono.empty());
    }

    @Override
    public Flux<Transport> getSupportedTransport() {
        return Flux.fromIterable(messageCodecSupports.values())
                   .flatMap(Supplier::get)
                   .map(DeviceMessageCodec::getSupportTransport)
                   .distinct(Transport::getId);
    }

    @Nonnull
    @Override
    public Mono<? extends DeviceMessageCodec> getMessageCodec(Transport transport) {
        return messageCodecSupports.getOrDefault(transport.getId(), Mono::empty).get();
    }

    @Nonnull
    @Override
    public DeviceMetadataCodec getMetadataCodec() {
        return metadataCodec;
    }

    public Flux<DeviceMetadataCodec> getMetadataCodecs() {
        return Flux.merge(Flux.just(metadataCodec), Flux.fromIterable(metadataCodecs));
    }

    public void addDeviceMetadataCodec(DeviceMetadataCodec codec) {
        metadataCodecs.add(codec);
    }

    @Nonnull
    @Override
    public Mono<AuthenticationResponse> authenticate(@Nonnull AuthenticationRequest request,
                                                     @Nonnull DeviceOperator deviceOperation) {
        return Mono.justOrEmpty(authenticators.get(request.getTransport().getId()))
                   .flatMap(at -> at
                           .authenticate(request, deviceOperation)
                           .defaultIfEmpty(AuthenticationResponse.error(400, "无法获取认证结果")))
                   .switchIfEmpty(Mono.error(() -> new UnsupportedOperationException("不支持的认证请求:" + request)));
    }

    @Nonnull
    @Override
    public Mono<AuthenticationResponse> authenticate(@Nonnull AuthenticationRequest request,
                                                     @Nonnull DeviceRegistry registry) {
        return Mono.justOrEmpty(authenticators.get(request.getTransport().getId()))
                   .flatMap(at -> at
                           .authenticate(request, registry)
                           .defaultIfEmpty(AuthenticationResponse.error(400, "无法获取认证结果")))
                   .switchIfEmpty(Mono.error(() -> new UnsupportedOperationException("不支持的认证请求:" + request)));
    }

    @Override
    public Mono<ConfigMetadata> getConfigMetadata(Transport transport) {
        return configMetadata.getOrDefault(transport.getId(), Mono::empty).get();
    }

    public Mono<ConfigMetadata> getInitConfigMetadata() {
        return initConfigMetadata;
    }

    @Nonnull
    @Override
    public Mono<DeviceStateChecker> getStateChecker() {
        return Mono.justOrEmpty(deviceStateChecker);
    }

    public CompositeProtocolSupport doOnDeviceRegister(Function<DeviceOperator, Mono<Void>> executor) {
        this.onDeviceRegister = executor;
        return this;
    }

    public CompositeProtocolSupport doOnDeviceUnRegister(Function<DeviceOperator, Mono<Void>> executor) {
        this.onDeviceUnRegister = executor;
        return this;
    }

    public CompositeProtocolSupport doOnProductRegister(Function<DeviceProductOperator, Mono<Void>> executor) {
        this.onProductRegister = executor;
        return this;
    }

    public CompositeProtocolSupport doOnProductUnRegister(Function<DeviceProductOperator, Mono<Void>> executor) {
        this.onProductUnRegister = executor;
        return this;
    }

    public CompositeProtocolSupport doOnProductMetadataChanged(Function<DeviceProductOperator, Mono<Void>> executor) {
        this.onProductMetadataChanged = executor;
        return this;
    }

    public CompositeProtocolSupport doOnDeviceMetadataChanged(Function<DeviceOperator, Mono<Void>> executor) {
        this.onDeviceMetadataChanged = executor;
        return this;
    }

    public void doOnClientConnect(Transport transport,
                                  BiFunction<ClientConnection, DeviceGatewayContext, Mono<Void>> handler) {
        connectionHandlers.put(transport.getId(), handler);
    }

    @Override
    public Mono<Void> onDeviceRegister(DeviceOperator operator) {
        return onDeviceRegister != null ? onDeviceRegister.apply(operator) : Mono.empty();
    }

    @Override
    public Mono<Void> onDeviceUnRegister(DeviceOperator operator) {
        return onDeviceUnRegister != null ? onDeviceUnRegister.apply(operator) : Mono.empty();
    }

    @Override
    public Mono<Void> onProductRegister(DeviceProductOperator operator) {
        return onProductRegister != null ? onProductRegister.apply(operator) : Mono.empty();
    }

    @Override
    public Mono<Void> onProductUnRegister(DeviceProductOperator operator) {
        return onProductUnRegister != null ? onProductUnRegister.apply(operator) : Mono.empty();
    }

    @Override
    public Mono<Void> onDeviceMetadataChanged(DeviceOperator operator) {
        return onDeviceMetadataChanged != null ? onDeviceMetadataChanged.apply(operator) : Mono.empty();
    }

    @Override
    public Mono<Void> onProductMetadataChanged(DeviceProductOperator operator) {
        return onProductMetadataChanged != null ? onProductMetadataChanged.apply(operator) : Mono.empty();
    }

    @Override
    public Mono<Void> onClientConnect(Transport transport,
                                      ClientConnection connection,
                                      DeviceGatewayContext context) {
        BiFunction<ClientConnection, DeviceGatewayContext, Mono<Void>> function = connectionHandlers.get(transport.getId());
        if (function == null) {
            return Mono.empty();
        }
        return function.apply(connection, context);
    }

    public void addFeature(Transport transport, Feature... features) {
        addFeature(transport, Flux.just(features));
    }

    public void addFeature(Transport transport, Iterable<Feature> features) {
        addFeature(transport, Flux.fromIterable(features));
    }

    public void addFeature(Transport transport, Flux<Feature> features) {
        this.features.put(transport.getId(), features);
    }

    @Override
    public Flux<Feature> getFeatures(Transport transport) {
        return features.getOrDefault(transport.getId(), Flux.empty());
    }
}
