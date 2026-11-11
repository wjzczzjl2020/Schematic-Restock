package cn.blockforge.generated.schematicrestock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 「Forgematica 补料接管」核心版（NeoForge 1.21.1）。
 *
 * 第一步调研结论（读玩家提供的投影/Forgematica 源码，包 fi.dy.masa.litematica）：
 *  - 缺料条目：MaterialListEntry，公开 getter getStack()/getCountMissing()/getCountTotal()/getCountAvailable()，
 *    getCountMissing() 返回 int —— 是「件数」（每个方块/物品算 1），不是组数。
 *  - 缺料清单：MaterialListBase.getMaterialsAll() -> List&lt;MaterialListEntry>；getCountMissing() -> long 总件数。
 *  - 公开调用链（全部 public，一条链拿到「物品种类+缺数」，无需扫数据结构做差集）：
 *      fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager()   (static)
 *        -> .getAllSchematicsPlacements() -> List&lt;SchematicPlacement>
 *          -> SchematicPlacement.isEnabled() / getMaterialList()
 *            -> MaterialListBase.getMaterialsAll() -> List&lt;MaterialListEntry>
 *  - 清单由投影异步统计任务填充（打开材料列表或首次访问 getMaterialList() 时触发重算），
 *    本模组轮询读取，并对空清单调用 reCreateMaterialList() 促算。
 *  因此 HUD 的「约 y 件」= Σ getCountMissing()（件数）。
 */
@Mod(SR.MOD_ID)
public final class SchematicRestockMod {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SR.MOD_ID);
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, SR.MOD_ID);

    /** 材料箱标记签 */
    public static final DeferredItem<Item> MARK_WAND =
            ITEMS.register("mark_wand", MarkWandItem::new);

    /** 成功：0.4s 上扬短叮 */
    public static final DeferredHolder<SoundEvent, SoundEvent> SND_OK =
            SOUNDS.register("takeover_ok", () -> SoundEvent.createVariableRangeEvent(SR.id("takeover_ok")));
    /** 缺料：0.8s 两段下行铜铃 */
    public static final DeferredHolder<SoundEvent, SoundEvent> SND_MISSING =
            SOUNDS.register("takeover_missing", () -> SoundEvent.createVariableRangeEvent(SR.id("takeover_missing")));
    /** 灰按钮：极轻「噗」 */
    public static final DeferredHolder<SoundEvent, SoundEvent> SND_NOOP =
            SOUNDS.register("takeover_noop", () -> SoundEvent.createVariableRangeEvent(SR.id("takeover_noop")));

    public SchematicRestockMod(IEventBus modBus) {
        // r28：COMMON 配置 —— 启动即在 .minecraft/config/schematic_restock-common.toml 落盘
        ModConfig.register();
        ITEMS.register(modBus);
        SOUNDS.register(modBus);

        modBus.addListener(this::onPayloadRegister);
        modBus.addListener(this::onCreativeTab);

        NeoForge.EVENT_BUS.register(CommonEvents.class);
        NeoForge.EVENT_BUS.register(TakeoverEngine.class); // 服务端 tick 驱动的分批搬运
        // r16：服务端 tick 驱动的自动建造（只订阅 ServerTickEvent / 玩家事件，
        // 类里不引用任何客户端类型，所以可以无条件注册）
        NeoForge.EVENT_BUS.register(AutoBuildEngine.class);

        if (FMLLoader.getDist().isClient()) {
            ClientEvents.init(modBus);
        }
    }

    private void onPayloadRegister(RegisterPayloadHandlersEvent event) {
        // v3：进度/结果载荷加了会话号 + 投料账本（placed）+ 无法合成清单（craftFails），
        //     与 r2 的客户端不兼容，故抬版本号拦住旧端连入。
        // v4（r7）：新增 C2S MarkOutputToggle（Shift+左键标记输出箱），
        //     S2C MarkCount 加了输出箱数量字段 —— 与 v3 两端都不兼容，必须再抬一位。
        // v5（r16）：新增 C2S BuildPlan/BuildControl 与 S2C BuildProgress/BuildResult。
        //     抬版本是为了拦住「新客户端连旧服务端」——那种组合下按 V+U 只会石沉大海，
        //     不如在握手阶段就明确告诉玩家两端 jar 不一致。
        PayloadRegistrar registrar = event.registrar("5");
        // C2S：标记与接管请求（服务器权威处理）
        registrar.playToServer(Packets.MarkToggle.TYPE, Packets.MarkToggle.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> MarkedChestStore.handleToggle(ctx.player(), p)));
        registrar.playToServer(Packets.MarkOutputToggle.TYPE, Packets.MarkOutputToggle.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> MarkedChestStore.handleOutputToggle(ctx.player(), p)));
        registrar.playToServer(Packets.MarkBox.TYPE, Packets.MarkBox.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> MarkedChestStore.handleBox(ctx.player(), p)));
        registrar.playToServer(Packets.ClearMarks.TYPE, Packets.ClearMarks.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> MarkedChestStore.handleClear(ctx.player())));
        registrar.playToServer(Packets.StartTakeover.TYPE, Packets.StartTakeover.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> TakeoverEngine.handle(ctx.player(), p)));
        // r16：建造计划分包（客户端一 tick 一包，服务端收齐最后一包才开工）与控制包
        registrar.playToServer(Packets.BuildPlan.TYPE, Packets.BuildPlan.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> AutoBuildEngine.handlePlan(ctx.player(), p)));
        registrar.playToServer(Packets.BuildControl.TYPE, Packets.BuildControl.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> AutoBuildEngine.handleControl(ctx.player(), p)));

        // S2C：类型与编码器两边都要注册；真正的处理器只在客户端挂，
        // 专用服务端挂空处理器，避免加载客户端类。
        if (FMLLoader.getDist().isClient()) {
            ClientNetwork.registerClientHandlers(registrar);
        } else {
            ClientNetwork.registerServerSideStubs(registrar);
        }
    }

    private void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(new ItemStack(MARK_WAND.get()));
        }
    }
}
