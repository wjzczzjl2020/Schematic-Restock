package cn.blockforge.generated.schematicrestock;

import net.minecraft.network.chat.Component;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 通用事件：手持标记签右键方块 -&gt; 走标记流程。
 *
 * <p>r24：右键一律<b>两端同时取消</b>，箱子/工作台等界面绝不弹出来；
 * 目标不是容器时屏幕中间给一条红字提示，且什么都不做。</p>
 *
 * <p>标记签的三种点法（r7）：
 * <ul>
 *   <li><b>右键</b>容器：标记 / 取消<b>材料箱</b>（补料只从这里取料）；</li>
 *   <li><b>Shift+右键</b>两下：框选一片区域，批量标成材料箱（r24：上限 16 → 64）；</li>
 *   <li><b>Shift+左键</b>容器：标记 / 取消<b>输出箱（溢出箱）</b>，只进不出，
 *       专门吃合成超发的产物和背包塞不下的东西。</li>
 * </ul>
 */
public final class CommonEvents {

    /**
     * <b>r24 优化 1：手持标记签右键，箱子界面必须打不开。</b>
     *
     * <p>旧版只取消了<b>客户端</b>那一次事件，所以界面照样弹 —— 原因在
     * {@code MultiPlayerGameMode#useItemOn}：它走 {@code startPrediction(level, action)}，
     * 这个 action 先跑 {@code performUseItemOn}（我们的取消只能在<b>这里</b>压掉本地表现），
     * 跑完之后<b>无条件</b>返回 {@code ServerboundUseItemOnPacket}，
     * {@code startPrediction} 拿到包就 {@code connection.send(...)}。
     * 于是包照发到服务端 → 服务端跑原版 {@code useItemOn} → {@code openMenu} → 界面弹出。
     * <b>结论：服务端这一侧也必须取消</b>，客户端的取消只是防本地预测错位。</p>
     *
     * <p>三个细节：</p>
     * <ol>
     *   <li>闸门看的是<b>主手</b>的堆（不是本次事件的 {@code e.getItemStack()}）：
     *       原版一次右键会把手逐一试一遍（{@code Minecraft#startUseItem} 里遍历
     *       {@code InteractionHand.values()}），只看主手才能把副手那一次也拦掉；
     *       返回 {@code SUCCESS}（{@code consumesAction()} 为真）会让那个循环提前 return，
     *       实际上副手那次不会发生。</li>
     *   <li>标记动作只在<b>客户端 + 主手</b>那次做（服务端等我们的自定义包，副手那次不重复发包）。</li>
     *   <li>「是不是容器」的判定分两层：客户端能<b>确定</b>不是容器（泥土、石头这类）就当场拦下、
     *       屏幕中间提示、什么都不做；判不准的一律发给服务端，由服务端拿
     *       {@code Capabilities.ItemHandler.BLOCK} 权威定夺（模组储物箱的能力常常只在服务端有）。</li>
     * </ol>
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        // 主手不是标记签 → 这是普通右键，原样放行（该开箱子开箱子）
        if (!e.getEntity().getItemInHand(InteractionHand.MAIN_HAND)
                .is(SchematicRestockMod.MARK_WAND.get())) return;

        // 两侧都取消：这才真正阻断 GUI
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);   // consumesAction → 原版不再试副手、不再继续处理
        e.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
        e.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);

        if (!e.getLevel().isClientSide) return;                   // 服务端只拦不改，标记等客户端的包
        if (e.getHand() != InteractionHand.MAIN_HAND) return;     // 主手那次已经处理过

        ClientEvents.handleWandClick(e.getPos(), e.getEntity().isShiftKeyDown());
    }

    /**
     * r7：手持标记签 <b>Shift+左键</b> 容器 → 标记/取消输出箱。
     *
     * <p>三个坑都得堵住：
     * <ol>
     *   <li>不按 Shift 的左键一律放行，玩家还得用它拆箱子；</li>
     *   <li>按住左键时游戏每 tick 都会发一次 {@code CLIENT_HOLD} 事件，
     *       节流交给 {@link ClientEvents#handleWandLeftClick}，否则一次点击能翻十几遍标记；</li>
     *   <li>服务端这一侧也判掉：万一客户端的取消没生效（模组间事件顺序），
     *       不能让服务端真把方块挖掉。</li>
     * </ol>
     */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock e) {
        ItemStack held = e.getItemStack();
        if (!held.is(SchematicRestockMod.MARK_WAND.get())) return;
        if (!e.getEntity().isShiftKeyDown()) return;
        // 松手/中止这两类动作不参与标记，否则一次点击会触发两回
        if (e.getAction() == PlayerInteractEvent.LeftClickBlock.Action.STOP
                || e.getAction() == PlayerInteractEvent.LeftClickBlock.Action.ABORT) return;

        e.setCanceled(true);                 // 阻止挖方块
        e.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
        e.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);

        if (e.getLevel().isClientSide) {     // 发包只在客户端做，服务端等包
            ClientEvents.handleWandLeftClick(e.getPos());
        }
    }

    /** 玩家（重）入服务端世界时，把已有的标记箱数量补发给客户端。 */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp && !e.getLevel().isClientSide) {
            MarkedChestStore.sync(sp);
        }
    }

    /**
     * {@code /ghostlog on|off} —— 幽灵合成排障日志开关（服务端，写进 logs/latest.log，
     * 前缀 {@code matfill/ghost}）。默认开着，看完可以关掉，免得日志太大。
     */
    @SubscribeEvent
    public static void onCommands(net.neoforged.neoforge.event.RegisterCommandsEvent e) {
        var root = Commands.literal("ghostlog")
                .then(Commands.literal("on").executes(ctx -> {
                    GhostDebug.setOn(true);
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.ghostlog.on"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    GhostDebug.setOn(false);
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.ghostlog.off"), false);
                    return 1;
                }))
                .then(Commands.literal("last").executes(ctx -> {
                    String d = GhostDebug.lastDetail();
                    ctx.getSource().sendSuccess(() -> d.isEmpty()
                            ? Txt.c("cmd.ghostlog.last_none")
                            : Txt.c("cmd.ghostlog.last", d), false);
                    return 1;
                }))
                // /ghostlog index：配方索引的统计。控制台也能用（不需要玩家实体）
                .then(Commands.literal("index").executes(ctx ->
                        ghostDump(ctx.getSource(), null)))
                // /ghostlog find <物品 id 或关键词>：只看这个产物的候选配方（产物过滤对不对，一眼看穿）
                .then(Commands.literal("find")
                        .then(Commands.argument("keyword",
                                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> ghostDump(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "keyword")))))
                // /ghostlog strict on|off：产物 DataComponents 不一致是否直接判死
                .then(Commands.literal("strict")
                        .then(Commands.literal("on").executes(ctx -> {
                            GhostCraftEngine.setStrictProductComponents(true);
                            ctx.getSource().sendSuccess(() -> Txt.c("cmd.ghostlog.strict_on"), false);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            GhostCraftEngine.setStrictProductComponents(false);
                            ctx.getSource().sendSuccess(() -> Txt.c("cmd.ghostlog.strict_off"), false);
                            return 1;
                        }))
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Txt.c(
                                    "cmd.ghostlog.strict_state",
                                    Txt.s(GhostCraftEngine.strictProductComponents()
                                            ? "cmd.ghostlog.state_on" : "cmd.ghostlog.state_off")), false);
                            return 1;
                        }))
                // r21 Bug1：/ghostlog carried 子命令已删——缺口必按身上已有件数抵扣，不再提供开关
                // /ghostlog reset：作废配方索引（改了数据包、加了模组之后用）
                .then(Commands.literal("reset").executes(ctx -> {
                    GhostCraftEngine.invalidateRecipeCache();
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.ghostlog.reset"), false);
                    return 1;
                }));
        e.getDispatcher().register(root);
    }

    /** {@code /ghostlog index} 与 {@code /ghostlog find <关键词>} 的共同执行体。 */
    private static int ghostDump(net.minecraft.commands.CommandSourceStack src, String keyword) {
        try {
            String s = GhostCraftEngine.debugIndex(src.getLevel(), src.registryAccess(), keyword);
            src.sendSuccess(() -> Component.literal(s), false);
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Txt.c("cmd.ghostlog.error", t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage())));
            return 0;
        }
    }

    static void register() {
        NeoForge.EVENT_BUS.register(CommonEvents.class);
    }
}
