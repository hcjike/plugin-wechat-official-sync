package com.hcjike.wechatofficialsync;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultNameResolver;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.resolver.NameResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Supplier;

/**
 * 连接期强制校验目标 IP 的地址解析器组，用于下载图片的 {@code WebClient}。
 *
 * <p>{@link SsrfGuard} 的预检发生在发起请求前，但 DNS 结果可能随时间变化（DNS rebinding），
 * 攻击者可让预检时解析到公网 IP、真正连接时解析到内网 IP。本解析器在 Netty <b>实际建立连接前</b>
 * 对解析到的每一个 IP 再次校验，命中受限网段且不在白名单即让解析失败，从而使连接无法建立，堵住
 * 预检与连接之间的时间差（TOCTOU）。</p>
 *
 * <p>白名单（{@link SsrfPolicy}）由 {@code policySupplier} 提供，与预检共享同一份全局配置：命中
 * 白名单主机的解析结果不过滤，保证内网部署的 Halo 能下载自己的图片。</p>
 *
 * <p>委托 {@link DefaultNameResolver}（与 reactor-netty 默认一致的 JDK 解析）完成真正的域名解析，
 * 本类只负责过滤结果，不改变解析行为。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
final class SsrfSafeAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final Supplier<SsrfPolicy> policySupplier;

    SsrfSafeAddressResolverGroup(Supplier<SsrfPolicy> policySupplier) {
        this.policySupplier = policySupplier;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        NameResolver<InetAddress> filtered =
            new SsrfSafeNameResolver(executor, new DefaultNameResolver(executor), policySupplier);
        return new InetSocketAddressResolver(executor, filtered);
    }

    /**
     * 包装底层 {@link NameResolver}，在解析结果返回给连接流程前过滤受限 IP。
     */
    private static final class SsrfSafeNameResolver implements NameResolver<InetAddress> {

        private final EventExecutor executor;

        private final NameResolver<InetAddress> delegate;

        private final Supplier<SsrfPolicy> policySupplier;

        SsrfSafeNameResolver(EventExecutor executor, NameResolver<InetAddress> delegate,
            Supplier<SsrfPolicy> policySupplier) {
            this.executor = executor;
            this.delegate = delegate;
            this.policySupplier = policySupplier;
        }

        private SsrfPolicy policy() {
            SsrfPolicy policy = policySupplier.get();
            return policy == null ? SsrfPolicy.EMPTY : policy;
        }

        @Override
        public Future<InetAddress> resolve(String inetHost) {
            return resolve(inetHost, executor.newPromise());
        }

        @Override
        public Promise<InetAddress> resolve(String inetHost, Promise<InetAddress> promise) {
            Promise<InetAddress> inner = executor.newPromise();
            delegate.resolve(inetHost, inner);
            SsrfPolicy policy = policy();
            inner.addListener((FutureListener<InetAddress>) future -> {
                if (!future.isSuccess()) {
                    promise.tryFailure(future.cause());
                    return;
                }
                InetAddress address = future.getNow();
                if (isBlocked(inetHost, address, policy)) {
                    promise.tryFailure(blocked(inetHost, address));
                } else {
                    promise.trySuccess(address);
                }
            });
            return promise;
        }

        @Override
        public Future<List<InetAddress>> resolveAll(String inetHost) {
            return resolveAll(inetHost, executor.newPromise());
        }

        @Override
        public Promise<List<InetAddress>> resolveAll(String inetHost, Promise<List<InetAddress>> promise) {
            Promise<List<InetAddress>> inner = executor.newPromise();
            delegate.resolveAll(inetHost, inner);
            SsrfPolicy policy = policy();
            inner.addListener((FutureListener<List<InetAddress>>) future -> {
                if (!future.isSuccess()) {
                    promise.tryFailure(future.cause());
                    return;
                }
                List<InetAddress> addresses = future.getNow();
                for (InetAddress address : addresses) {
                    if (isBlocked(inetHost, address, policy)) {
                        promise.tryFailure(blocked(inetHost, address));
                        return;
                    }
                }
                promise.trySuccess(addresses);
            });
            return promise;
        }

        @Override
        public void close() {
            delegate.close();
        }

        /** 白名单主机直接放行；否则对受限且不在白名单 CIDR 内的 IP 予以拒绝。 */
        private static boolean isBlocked(String host, InetAddress address, SsrfPolicy policy) {
            if (policy.isHostAllowed(host)) {
                return false;
            }
            return SsrfGuard.isBlocked(address, policy);
        }

        private static IllegalArgumentException blocked(String host, InetAddress address) {
            String ip = address == null ? "?" : address.getHostAddress();
            return new IllegalArgumentException(
                "已拒绝连接：目标「" + host + "」解析到受限网络地址（" + ip + "）且不在白名单中");
        }
    }
}
