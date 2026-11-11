package cn.blockforge.generated.schematicrestock;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * S2C 载荷的注册。客户端挂真正的接收处理器；专用服务端只挂空处理器占位
 * （类型与编码器两边都必须注册，否则服务端发不出包），不引用任何客户端类。
 */
public final class ClientNetwork {

    private ClientNetwork() {}

    public static void registerClientHandlers(PayloadRegistrar registrar) {
        registrar.playToClient(Packets.MarkCount.TYPE, Packets.MarkCount.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientEvents.onMarkCount(p.count(), p.outputs())));
        registrar.playToClient(Packets.TakeoverResult.TYPE, Packets.TakeoverResult.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientEvents.onResult(p)));
        registrar.playToClient(Packets.TakeoverProgress.TYPE, Packets.TakeoverProgress.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientEvents.onProgress(p)));
        // r16：建造进度 / 收尾总结。进度包只更新几个计数，收尾包才播报，都不重算清单。
        registrar.playToClient(Packets.BuildProgress.TYPE, Packets.BuildProgress.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> BuildBridge.onProgress(p)));
        registrar.playToClient(Packets.BuildResult.TYPE, Packets.BuildResult.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> BuildBridge.onResult(p)));
    }

    /**
     * 专用服务端上的占位注册：S2C 载荷的<b>类型与编码器</b>两边都得有，否则服务端发不出去；
     * 处理器在服务端永远不会被调用，所以这里只给空实现，绝不引用任何客户端类。
     */
    public static void registerServerSideStubs(PayloadRegistrar registrar) {
        registrar.playToClient(Packets.MarkCount.TYPE, Packets.MarkCount.CODEC, (p, ctx) -> { });
        registrar.playToClient(Packets.TakeoverResult.TYPE, Packets.TakeoverResult.CODEC, (p, ctx) -> { });
        registrar.playToClient(Packets.TakeoverProgress.TYPE, Packets.TakeoverProgress.CODEC, (p, ctx) -> { });
        registrar.playToClient(Packets.BuildProgress.TYPE, Packets.BuildProgress.CODEC, (p, ctx) -> { });
        registrar.playToClient(Packets.BuildResult.TYPE, Packets.BuildResult.CODEC, (p, ctx) -> { });
    }
}
