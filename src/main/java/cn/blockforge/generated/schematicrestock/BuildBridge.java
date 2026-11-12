package cn.blockforge.generated.schematicrestock;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动建造的<b>客户端</b>半边：从 Forgematica（投影）把「哪些坐标要放什么方块」读出来，
 * 编成形态表（palette）+ 坐标流，分帧上传给服务端；之后只管收进度、画 HUD。
 *
 * <h3>读的就是投影 L 键缺口那一份数据（源码实锤）</h3>
 * <p>{@code scheduler/tasks/TaskCountBlocksPlacement.java} 的 {@code countAtPosition()} 原文：</p>
 * <pre>
 * BlockState stateSchematic = this.schematicWorld.getBlockState(pos);   // 目标（含朝向）
 * BlockState stateClient   = this.clientWorld.getBlockState(pos);      // 现状
 * if (stateSchematic.isAir() == false) { countsTotal++; if (stateClient.isAir()) countsMissing++; … }
 * </pre>
 * <p>两点关键：</p>
 * <ol>
 *   <li>两个世界用的是<b>同一个 pos</b> —— 说明原理图世界的坐标就是<b>世界坐标</b>
 *       （投影把放置原点、旋转、镜像全烘进去了）。所以我们不用自己算任何变换，
 *       {@code SchematicWorldHandler.getSchematicWorld().getBlockState(世界坐标)} 拿到的
 *       就是玩家屏幕上那份半透明幽灵在该处的真实形态，楼梯朝向天然正确；</li>
 *   <li>坐标范围来自 {@code placement.getSubRegionBoxes(PLACEMENT_ENABLED)}，
 *       源码里它返回的 Box 已经是变换后的<b>绝对世界坐标</b>
 *       （{@code boxOriginAbsolute = 变换(区域原点) + placement.origin}）。
 *       投影统计材料清单遍历的就是这一份 Box，所以我们和 L 键缺口<b>不可能对不上</b>。</li>
 * </ol>
 *
 * <h3>形态为什么走文本而不是数字 id</h3>
 * <p>用原版 {@code /setblock} 那套写法：{@code minecraft:oak_stairs[facing=north,half=bottom,
 * shape=straight,waterlogged=false]}。理由三条：① 方块形态的<b>数字 id</b> 客户端/服务端
 * 不保证一致（模组加载顺序一变就整体错位，错一位就是一墙错误方块），文本 id 永远指得准；
 * ② 一张图纸去重后的形态数通常只有几十到几百条，坐标流每格只用 8+2 字节；
 * ③ 日志里能人眼读懂到底在放什么。服务端每种形态只解析一次。</p>
 *
 * <h3>只读不改</h3>
 * <p>本类<b>不碰世界、不碰玩家背包</b>，纯读取 + 发包；真正的放置一律发生在
 * {@link AutoBuildEngine}（服务端）。投影没装、没加载图纸、反射失败——一律只回一句诊断，不抛。</p>
 */
public final class BuildBridge {

    /** V+U：开始 / 暂停 / 继续 自动建造。 */
    public static final KeyMapping KEY_BUILD = new KeyMapping(
            "key.schematic_restock.build", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U,
            "key.categories.schematic_restock");
    /** V+Y：取消自动建造（已经放下去的方块不回滚）。 */
    public static final KeyMapping KEY_BUILD_CANCEL = new KeyMapping(
            "key.schematic_restock.build_cancel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y,
            "key.categories.schematic_restock");

    /** 会话阶段（与服务端 AutoBuildEngine 的 state 编码一致）。 */
    public static final int ST_IDLE = 0, ST_RUNNING = 1, ST_PAUSED = 2,
            ST_DONE = 3, ST_CANCELLED = 4, ST_ERROR = 5;

    private static final String[] DATA_MANAGER = {
            "fi.dy.masa.litematica.data.DataManager",
            "org.thinkingstudio.forgematica.data.DataManager"
    };
    private static final String[] WORLD_HANDLER = {
            "fi.dy.masa.litematica.world.SchematicWorldHandler",
            "org.thinkingstudio.forgematica.world.SchematicWorldHandler"
    };
    private static final String[] SUB_REGION_PLACEMENT = {
            "fi.dy.masa.litematica.schematic.placement.SubRegionPlacement",
            "org.thinkingstudio.forgematica.schematic.placement.SubRegionPlacement"
    };

    /** 扫描时最多<b>检视</b>多少个坐标（外接盒里大片空气时，收不到货也得有个封顶）。 */
    private static final long MAX_INSPECT = 4_000_000L;

    // ---------------- 客户端状态 ----------------

    private static int state = ST_IDLE;
    private static long planId;
    private static String note = "";
    private static long noteUntil;
    private static long lastProgressMs;
    /** 服务端实际收下的条目数（HUD 的分母；客户端扫到的数可能被服务端剔掉一部分）。 */
    private static int queued;
    private static int placed;
    private static int skipped;
    private static int blocked;
    private static int missing;
    private static int deferred;
    /** r23：施工中催投影重算 L 键缺口的节流戳（见 onProgress）。 */
    private static long lastRecountMs;
    /**
     * r27：上一次催投影重算时服务端已放下的块数。
     * 只有它涨了才值得再催一次 —— 不涨还照样催，就是「卡在缺料里还不停显示已开始任务」的刷屏来源。
     */
    private static int placedAtLastRecount;
    /** r27：当前是不是「一格都没在放、纯等补料/等走近」的静默状态（HUD 提示用）。 */
    private static boolean recountIdle;
    /** 收尾之后过一会儿再锚一次原始缺口基线（投影重算是异步的）。 */
    private static long reanchorAt;

    /** 上传队列：一份大图纸可能有几十包，按 tick 往外发，绝不一次灌满网络。 */
    private static final List<Packets.BuildPlan> outbox = new ArrayList<>();
    private static boolean uploading;
    /** r25：本轮已发出的包数（诊断/日志用）。 */
    private static int chunksSent;
    /** r25：本轮计划的形态表序列化后大概多少字节（首包里最占地方的就是它）。 */
    private static int paletteBytes;
    /** r25：本轮上传的粗算总量（字节）：坐标流每格 8+2 字节 + 形态表。 */
    private static long uploadBytes;


    /**
     * 覆盖策略：0=只填空气（配置里的 {@code autobuild.replacePolicy} 说了算）1=含原版可替换 2=不对就覆盖。
     *
     * <p><b>r32：这两个值绝不能再写成"静态字段初值直接读配置"</b>。
     * 上一版就是 {@code private static int replacePolicy = ModConfig.replacePolicyCode();} ——
     * 本类在模组构造器阶段（{@code ClientEvents.init} 一摸到它）就完成静态初始化，
     * 而 {@code Type.COMMON} 的 TOML 要到所有构造器跑完才加载，
     * 于是 {@code get()} 直接抛 {@code IllegalStateException: Cannot get config value before config is loaded.}，
     * 玩家看到的就是"启动即崩溃"。</p>
     *
     * <p>现在的分工：这里只存<b>命令的临时覆盖</b>（{@code null} = 没人覆盖，去现读配置文件），
     * 读取一律走 {@link #replacePolicy()} / {@link #placeFluids()} —— 用到时才问，
     * 那时候配置早就加载好了。语义和 {@code /matfill build replace …} 原本写的那样一致：
     * "配置定默认、命令做临时覆盖（下一份计划生效），不改文件"。</p>
     */
    private static Integer replacePolicyOverride;
    /** 流体开关的命令临时覆盖（{@code null} = 跟随 {@code autobuild.placeFluids}）。 */
    private static Boolean placeFluidsOverride;

    private BuildBridge() {}

    public static void init(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent e) -> {
            e.register(KEY_BUILD);
            e.register(KEY_BUILD_CANCEL);
        });
    }

    // ---------------- 对外：V+U / V+Y ----------------

    /**
     * V+U 三态机：
     * <ul>
     *   <li>空闲 / 上一轮已结束 → 现扫原理图、建计划并上传（服务端收齐最后一包就开工）；</li>
     *   <li>计划还在分帧上传 → 只报进度，不发控制包；</li>
     *   <li>进行中 → 发暂停；</li>
     *   <li>已暂停 → 发继续。</li>
     * </ul>
     */
    public static void toggle(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        long now = System.currentTimeMillis();

        // <b>r25：这一档必须排在 RUNNING 判定之前。</b>
        //
        // <p>大图纸是分帧上传的，而 {@link #beginUpload} 一把 {@code state} 就置成了
        // ST_RUNNING（为了 HUD 能显示进度）。于是旧版在上传途中按 V+U 会走进
        // "发暂停包"那条分支：服务端的会话还停在收包阶段（根本没 RUNNING），
        // 只能回一句"当前没有进行中的建造"，客户端把这记 ST_ERROR 显示成"建造出错"。
        // 现在上传途中这一按只是问一句还剩几包，要中止请用 V+Y。</p>
        if (uploading) {
            say(mc, Txt.s("chat.build.uploading", outbox.size()), ChatFormatting.GRAY);
            return;
        }
        if (state == ST_RUNNING) {
            PacketDistributor.sendToServer(new Packets.BuildControl(1));
            say(mc, Txt.s("chat.build.paused"), ChatFormatting.YELLOW);
            return;
        }
        if (state == ST_PAUSED) {
            // r23：被补料按停的会话——补料收场会自动续工，这一按不用发续工包（发了也只会被服务端挡下）
            if (ClientEvents.isTakeoverRunning()) {
                say(mc, Txt.s("chat.build.hold_restock"), ChatFormatting.YELLOW);
                return;
            }
            PacketDistributor.sendToServer(new Packets.BuildControl(2));
            say(mc, Txt.s("chat.build.resume"), ChatFormatting.AQUA);
            return;
        }
        if (ClientEvents.isTakeoverRunning()) {
            say(mc, Txt.s("chat.build.takeover_busy"), ChatFormatting.RED);
            return;
        }

        Plan p;
        try {
            p = collect(mc);
        } catch (Throwable t) {
            state = ST_ERROR;
            note = Txt.s("chat.build.read_fail", t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + clip(t.getMessage())));
            noteUntil = now + 8000;
            GhostDebug.throwAt("建造计划扫描", t);
            // note 存裸文本（HUD 的「建造出错：」行直接用它），聊天栏自己加 [建造] 标签
            say(mc, Txt.s("chat.build.note", note), ChatFormatting.RED);
            return;
        }
        int cells = p.solids.size() + p.fluidsTail.size();
        if (cells == 0) {
            state = ST_IDLE;
            note = p.diag.isEmpty() ? Txt.s("chat.build.empty") : p.diag;
            noteUntil = now + 5000;
            say(mc, Txt.s("chat.build.note", note), ChatFormatting.YELLOW);
            return;
        }
        int packets = Math.max(1, (cells + SR.BUILD_CHUNK - 1) / SR.BUILD_CHUNK);
        beginUpload(p);
        // r25：把分包数说明白。大图纸是分帧上传的，服务端要收齐最后一包才开工——
        // 玩家看得见"几包 / 大概几秒"，就不会以为卡住了去连按 V+U。
        say(mc, Txt.s("chat.build.planned", cells, p.palette.size(),
                        p.fluidCount > 0 ? Txt.s("chat.build.fluids_tail", p.fluidCount) : "",
                        p.truncated ? Txt.s("chat.build.truncated") : "",
                        packets,
                        Math.max(1, (packets + SR.BUILD_CHUNKS_PER_TICK - 1) / SR.BUILD_CHUNKS_PER_TICK)),
                ChatFormatting.AQUA);
        GhostDebug.line("build: 计划 " + cells + " 格，形态 " + p.palette.size()
                + " 条，流体 " + p.fluidCount + "，策略=" + policyName() + (p.truncated ? "，截断" : ""));
    }

    /** V+Y：取消。已经放下去的方块<b>不回滚</b>（回滚要挖方块，比不滚更容易出事故）。 */
    public static void cancel(Minecraft mc) {
        if (mc.player == null) return;
        boolean wasUploading = uploading;
        boolean live = state == ST_RUNNING || state == ST_PAUSED || wasUploading;
        outbox.clear();
        uploading = false;
        // r25：上传途中取消也得给服务端递一句话，否则服务端还攥着半份计划在等后面的包
        // （它不会报错，只是白占一个会话位，到静默超时才自己清）。服务端对没收齐的会话
        // 是静默丢弃，不会再回一记 ST_ERROR 把这里刚说的"已取消"改成"建造出错"。
        if (state == ST_RUNNING || state == ST_PAUSED || wasUploading) {
            PacketDistributor.sendToServer(new Packets.BuildControl(3));
        }
        if (live) {
            state = ST_CANCELLED;
            note = wasUploading ? Txt.s("note.build.cancel_upload")
                    : Txt.s("note.build.cancelled", placed);
            noteUntil = System.currentTimeMillis() + 5000;
            say(mc, wasUploading ? Txt.s("chat.build.cancel_upload")
                    : Txt.s("chat.build.cancelled"), ChatFormatting.YELLOW);
            // 取消也得让 L 键缺口回到真值：刚放下去的那些格投影还不知道
            RestockBridge.requestRecount();
            MaterialSyncClient.syncNow();
            reanchorAt = System.currentTimeMillis() + 2500L;
        } else {
            say(mc, Txt.s("chat.build.none_active"), ChatFormatting.GRAY);
        }
    }

    /** {@code /matfill build replace …}：只记一份内存覆盖，下一份计划生效，不写配置文件。 */
    public static void setReplacePolicy(int p) { replacePolicyOverride = Math.max(0, Math.min(2, p)); }

    /** 当前生效的覆盖策略：命令覆盖优先，否则现读 {@code autobuild.replacePolicy}。 */
    public static int replacePolicy() {
        Integer o = replacePolicyOverride;
        return o != null ? o : ModConfig.replacePolicyCode();
    }

    /** {@code /matfill build fluids …}。 */
    public static void setPlaceFluids(boolean v) { placeFluidsOverride = v; }

    /** 当前生效的流体开关：命令覆盖优先，否则现读 {@code autobuild.placeFluids}。 */
    public static boolean placeFluids() {
        Boolean o = placeFluidsOverride;
        return o != null ? o : ModConfig.placeFluidsAllowed();
    }

    /** 命令覆盖有没有盖在配置文件上面（诊断行里说明"这一项现在是谁说了算"）。 */
    public static boolean policyOverridden() { return replacePolicyOverride != null; }

    public static String policyName() {
        return switch (replacePolicy()) {
            case SR.BUILD_REPLACE_SOFT -> Txt.s("policy.soft");
            case SR.BUILD_REPLACE_MISMATCH -> Txt.s("policy.mismatch");
            default -> Txt.s("policy.air");
        };
    }

    // ---------------- 客户端 tick：分帧上传 + 看门狗 ----------------

    public static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();

        // r25：一 tick 发 {@link SR#BUILD_CHUNKS_PER_TICK} 包（旧版 1 包）。
        // 大图纸的上传窗口从好几秒压到一秒出头，HUD 上"上传中"不再像卡住。
        for (int sent = 0; uploading && sent < SR.BUILD_CHUNKS_PER_TICK && !outbox.isEmpty(); sent++) {
            Packets.BuildPlan chunk = outbox.remove(0);
            PacketDistributor.sendToServer(chunk);
            chunksSent++;
            if (chunk.last()) {
                uploading = false;
                lastProgressMs = now;   // 状态等服务端进度包来确认
                GhostDebug.line("build: 上传完毕，共发 " + chunksSent + " 包 / 约 "
                        + (uploadBytes / 1024.0) + " KB（首包形态表 " + paletteBytes
                        + " B），等服务端收口开工");
            }
        }

        // 看门狗：服务端长时间不回进度，就认为会话没了。
        // r25：上传期间不看这个表 —— 服务端收到 last 包之前本来一个进度包都不会发，
        // 几十包的图纸撞上掉帧就可能被误判成"服务端没有回应"。
        if (!uploading && (state == ST_RUNNING || state == ST_PAUSED)
                && now - lastProgressMs > SR.BUILD_WATCHDOG_MS) {
            state = ST_ERROR;
            note = Txt.s("note.build.no_reply");
            noteUntil = now + 8000;
        }
        // 收尾之后再锚一次原始缺口（投影重算要 1 秒上下）
        if (reanchorAt != 0 && now >= reanchorAt) {
            reanchorAt = 0;
            RestockBridge.refresh(true);
            MaterialSyncClient.seedRawBaseline();
        }
        if (noteUntil != 0 && now > noteUntil) {
            noteUntil = 0;
            if (state == ST_DONE || state == ST_CANCELLED || state == ST_ERROR) {
                state = ST_IDLE;
                note = "";
            }
        }
    }

    // ---------------- 服务端回包 ----------------

    public static void onProgress(Packets.BuildProgress p) {
        long nowMs = System.currentTimeMillis();
        lastProgressMs = nowMs;
        planId = p.planId();
        // 收尾状态一旦确定就不让后续进度包把它抹平
        if (state != ST_DONE && state != ST_CANCELLED && state != ST_ERROR) state = p.state();
        placed = p.placed();
        skipped = p.skipped();
        blocked = p.blocked();
        missing = p.missing();
        deferred = p.deferred();
        queued = p.queued();
        if (p.hasConsumed()) MaterialSyncClient.onBuildConsumed(p.planId(), p.consumed());
        if (p.state() == ST_DONE || p.state() == ST_ERROR) reanchorAt = nowMs + 2500L;
        if (placed != placedAtLastRecount) recountIdle = false;   // r27：又放下料了，"等补料"的提示立刻收回去
        // r23：施工中每隔几秒催投影重算一次 L 键清单。旧版只在收尾才重算：墙都砌上去
        // 几百块，缺口数字却一动不动——L 键"显示全部缺少"，下一次 V+M 发的还是陈旧虚高的
        // 缺口，动态校验还会把「已经进墙」的投料账本误判成「被拿走了」整批清掉。
        // requestRecount 本身很轻（下次读取顺带 reCreateMaterialList，投影内部排度），
        // 这里再按 5 秒节流，绝不每包都催。
        if ((p.state() == ST_RUNNING || (p.state() == ST_PAUSED && ClientEvents.isTakeoverRunning()))
                && nowMs - lastRecountMs > 5000L) {
            // <b>r27：只有真的又放下了一格才催投影重算。</b>
            //
            // <p>投影每接一次「重算材料清单」的请求就起一个任务，起任务会在聊天栏播一句提示。
            // 旧版这里只看时间不看进展：一种料箱里确实没有的时候，施工会话每 5 秒催一次，
            // 玩家就看着同一句话没完没了地刷 —— 配上永不收场的缺料延后，看起来正是
            // 「卡在原地、还不停显示已开始任务」。现在数字不涨就不催，服务端那边的
            // 零进展闸门（{@link SR#BUILD_STALL_MS}）会自己把这一轮收场并说清缺什么。</p>
            if (placed == placedAtLastRecount) {
                recountIdle = true;
            } else {
                recountIdle = false;
                placedAtLastRecount = placed;
                lastRecountMs = nowMs;
                RestockBridge.requestRecount();
            }
        }
    }

    public static void onResult(Packets.BuildResult r) {
        planId = r.planId();
        state = r.state();
        placed = r.placed();
        skipped = r.skipped();
        blocked = r.blocked();
        queued = Math.max(queued, r.placed() + r.skipped() + r.blocked() + r.missingPieces());
        uploading = false;
        noteUntil = System.currentTimeMillis() + 8000;
        note = Txt.s(state == ST_CANCELLED ? "note.build.cancelled_n" : "note.build.finished_n", r.placed());

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // r28：这条总结走 translatable + 风格分量拼接（整句一个键，语序按语言走）
        mc.player.displayClientMessage(
                Txt.c("chat.build.result",
                        Txt.s(state == ST_CANCELLED ? "res.build.cancelled"
                                : state == ST_ERROR ? "res.build.interrupted" : "res.build.finished"),
                        r.placed(),
                        tail("res.build.fluids", r.fluidPlaced(), " · "),
                        tail("res.build.already", r.skipped(), " · "),
                        tail("res.build.nonempty", r.blocked(), " · "),
                        tail("res.build.missing", r.missingPieces(), " · "),
                        tail("res.build.noitem", r.noItem(), " · "),
                        tail("res.build.badstate", r.badState(), " · "))
                        .withStyle(r.missingPieces() > 0 || r.badState() > 0
                                ? ChatFormatting.YELLOW : ChatFormatting.GREEN),
                false);
        if (r.bePlaced() > 0) {
            mc.player.displayClientMessage(Txt.c("chat.build.be_note", r.bePlaced())
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        int shown = 0;
        for (Packets.Entry e : r.missingList()) {
            if (shown++ >= 8) {
                mc.player.displayClientMessage(Txt.c("chat.build.more_items")
                        .withStyle(ChatFormatting.GRAY), false);
                break;
            }
            mc.player.displayClientMessage(Txt.c("chat.build.missing_item", displayOf(e.id()), e.count())
                    .withStyle(ChatFormatting.GRAY), false);
        }
        if (r.note() != null && !r.note().isEmpty()) {
            // 服务端拼的自由文本 note 不走 translatable（见类注释），这里只本地化前缀标签
            mc.player.displayClientMessage(Txt.c("chat.build.note", r.note())
                    .withStyle(ChatFormatting.DARK_AQUA), true);
        }
        if (r.placed() > 0) mc.player.playSound(SchematicRestockMod.SND_OK.get(), 1.0f, 1.0f);

        // 第七点：建下去的方块已经改掉了世界现状 → 催投影重算 L 键缺口，
        // 同时投料账本在 onBuildConsumed 里已经按消耗扣过了。
        if (r.placed() > 0 || r.fluidPlaced() > 0) {
            RestockBridge.requestRecount();
            MaterialSyncClient.syncNow();
            reanchorAt = System.currentTimeMillis() + 2500L;
        }
        GhostDebug.line("build: 收尾 放=" + r.placed() + " 流体=" + r.fluidPlaced() + " BE=" + r.bePlaced()
                + " 已对=" + r.skipped() + " 非空=" + r.blocked() + " 缺料=" + r.missingPieces()
                + " 无物品=" + r.noItem() + " 坏形态=" + r.badState() + " | " + r.note());
    }

    private static String displayOf(String id) {
        net.minecraft.world.item.Item item = (net.minecraft.world.item.Item) BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse(id));
        if (item == null || item == net.minecraft.world.item.Items.AIR) return id;
        return Component.translatable(item.getDescriptionId()).getString();
    }

    private static String clip(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    // ---------------- HUD ----------------

    /** HUD 要不要为建造这一档画东西。 */
    public static boolean hudActive() {
        return uploading || state != ST_IDLE;
    }

    /** HUD 的建造行。 */
    public static String hudLine() {
        if (uploading) return Txt.s("hud.build.uploading", outbox.size());
        return switch (state) {
            case ST_RUNNING -> Txt.s("hud.build.running", placed, queued, missing)
                    + (deferred > 0 ? Txt.s("hud.build.deferred", deferred) : "")
                    // r27：一格都放不下去时说明白在等什么，别让人对着静止的数字干等
                    + (recountIdle && missing > 0 ? Txt.s("hud.build.wait_restock") : "");
            // r23：补料按停的暂停和玩家自己按的暂停说清楚——前者收场后自动续工，别多按。
            case ST_PAUSED -> Txt.s("hud.build.paused", placed, queued,
                    ClientEvents.isTakeoverRunning() ? Txt.s("hud.build.paused_restock")
                                                     : Txt.s("hud.build.paused_manual"));
            case ST_DONE -> Txt.s("hud.build.done", placed);
            case ST_CANCELLED -> Txt.s("hud.build.cancelled", placed);
            case ST_ERROR -> Txt.s("hud.build.error", note.isEmpty() ? Txt.s("hud.build.unknown") : note);
            default -> Txt.s("hud.build.idle");
        };
    }

    /** {@code " · 键 = 0 时给空串"}：结果行的一节。 */
    private static String tail(String key, int n, String sep) {
        if (n <= 0) return "";
        return sep + Txt.s(key, n);
    }

    /** HUD 进度条：{@code {已完成, 总数}}；总数 0 = 不画条。 */
    public static int[] hudBar() {
        if (state == ST_IDLE || state == ST_ERROR) return new int[]{0, 0};
        return new int[]{placed, queued};
    }

    public static int hudState() { return state; }

    public static boolean isBusy() {
        return uploading || state == ST_RUNNING || state == ST_PAUSED;
    }

    /** r23：计划还在分帧上传（服务端连会话都没建，V+M 的"按停让路"无从谈起，只有这一步要拦）。 */
    public static boolean isUploading() { return uploading; }

    /** 诊断行（{@code /matfill status}）。 */
    public static String describe() {
        return "状态=" + switch (state) {
            case ST_RUNNING -> "进行中";
            case ST_PAUSED -> "已暂停";
            case ST_DONE -> "已完成";
            case ST_CANCELLED -> "已取消";
            case ST_ERROR -> "出错";
            default -> "空闲";
        } + "（" + placed + "/" + queued + " 块，已对 " + skipped + "，非空 " + blocked
                + "，缺料 " + missing + "，延后 " + deferred + "）"
                + " | 覆盖=" + policyName() + (policyOverridden() ? "（命令覆盖）" : "（配置文件）")
                + " | 流体=" + (placeFluids() ? "放（排最后）" : "不放")
                + (uploading ? " | 上传中：已发 " + chunksSent + " 包，剩 " + outbox.size()
                             + " 包（一 tick 一包，服务端收齐才开工）" : "")
                + (note.isEmpty() ? "" : " | " + note);
    }

    public static void resetForNewWorld() {
        outbox.clear();
        uploading = false;
        chunksSent = 0;
        state = ST_IDLE;
        queued = placed = skipped = blocked = missing = deferred = 0;
        placedAtLastRecount = 0;
        recountIdle = false;
        note = "";
        noteUntil = 0;
        reanchorAt = 0;
    }

    private static void say(Minecraft mc, String s, ChatFormatting c) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(s).withStyle(c), true);
    }

    // ---------------- 扫描原理图，产出建造计划 ----------------

    /** 一份待上传的计划。 */
    private static final class Plan {
        final List<String> palette = new ArrayList<>();
        final Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        final List<long[]> solids = new ArrayList<>();
        final List<long[]> fluidsTail = new ArrayList<>();
        /** 拼好的上传序列：先固体（自下往上），后流体（用户拍板：流体排最后）。 */
        final List<long[]> entries = new ArrayList<>();
        int fluidCount;
        int inspected;
        /**
         * r25：形态表目前占多少字节（按每字符 1 字节估——方块 id 与属性名全是 ASCII）。
         * 整张表都塞在<b>首包</b>里，而原版自定义载荷有 1 MiB 硬上限，撞上去是抛异常断连接，
         * 不是少放几个方块。所以这里自己封顶：超预算只是少收几格并在聊天栏写明截断。
         */
        int paletteBytes;
        boolean paletteFull;
        boolean truncated;
        String diag = "";

        void add(String key, long pos, int idx, boolean fluid) {
            (fluid ? fluidsTail : solids).add(new long[]{pos, idx});
            if (fluid) fluidCount++;
        }
    }

    private static void beginUpload(Plan p) {
        p.entries.addAll(p.solids);
        p.entries.addAll(p.fluidsTail);
        outbox.clear();
        long id = System.nanoTime();
        planId = id;
        int size = p.entries.size();
        // r32：整份计划用同一个策略（分包期间配置不会变，但抓一次更省心，也免得每包现读）
        final int policy = replacePolicy();
        int chunks = Math.max(1, (size + SR.BUILD_CHUNK - 1) / SR.BUILD_CHUNK);
        for (int c = 0; c < chunks; c++) {
            int from = c * SR.BUILD_CHUNK;
            int to = Math.min(size, from + SR.BUILD_CHUNK);
            List<Long> pos = new ArrayList<>(to - from);
            List<Integer> pal = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                pos.add(p.entries.get(i)[0]);
                pal.add((int) p.entries.get(i)[1]);
            }
            outbox.add(new Packets.BuildPlan(id, c, c == 0, c == chunks - 1,
                    size, policy, c == 0 ? List.copyOf(p.palette) : List.of(), pos, pal));
        }
        uploading = true;
        state = ST_RUNNING;
        lastProgressMs = System.currentTimeMillis();
        placed = skipped = blocked = missing = deferred = 0;
        placedAtLastRecount = 0;
        recountIdle = false;
        lastRecountMs = 0;
        queued = size;
        chunksSent = 0;
        paletteBytes = p.paletteBytes;
        uploadBytes = (long) size * 10 + p.paletteBytes;
        note = "";
        noteUntil = 0;
        GhostDebug.line("build: 开始分帧上传 plan=" + id + " 条目=" + size + " 形态="
                + p.palette.size() + " 条/" + p.paletteBytes + " B 共 " + chunks + " 包（一 tick 一包）");
    }

    /**
     * 遍历所有<b>启用</b>的原理图放置，按投影自己的口径把「要放的方块」收下来。
     *
     * <p>扫描顺序：每个 Box 都 <b>y 由低到高</b>（自下往上建，跟手工建造一样，
     * 楼梯/墙/火把下面先有支撑，不会出现一墙悬空的火把）；流体单独收，
     * 拼在整份计划的<b>最后</b>（用户拍板：放，但排最后，死水不乱流）。</p>
     *
     * <p>四道闸门：收集上限 {@link SR#BUILD_MAX_PLAN}、检视上限 {@code MAX_INSPECT}、
     * 时间上限 {@link SR#BUILD_SCAN_CAP_MS}、形态表上限 {@link SR#BUILD_MAX_PALETTE}。
     * 任何一道撞了都只是<b>少收一点</b>（HUD 与聊天栏会写明截断），不会报错。</p>
     */
    private static Plan collect(Minecraft mc) throws Exception {
        Plan p = new Plan();
        long deadline = System.currentTimeMillis() + SR.BUILD_SCAN_CAP_MS;

        Object world = schematicWorld();
        if (world == null) {
            p.diag = "读不到投影的原理图世界（Forgematica 没装？原理图没加载？）";
            return p;
        }
        Method getBlockState = RestockBridge.findMethod(world.getClass(), "getBlockState", 1);
        if (getBlockState == null) {
            p.diag = "原理图世界没有 getBlockState(BlockPos)";
            return p;
        }

        Class<?> dm = null;
        for (String n : DATA_MANAGER) {
            try { dm = Class.forName(n); break; } catch (ClassNotFoundException ignored) {}
        }
        if (dm == null) { p.diag = "未找到 Forgematica（DataManager 类不存在）"; return p; }

        Object mgr = RestockBridge.callStatic(dm, "getSchematicPlacementManager");
        if (mgr == null) { p.diag = "getSchematicPlacementManager() 返回 null"; return p; }
        Object placementsObj = RestockBridge.callAny(mgr, "getAllSchematicsPlacements", "getAllPlacements");
        if (!(placementsObj instanceof List<?> placements) || placements.isEmpty()) {
            p.diag = "没有已加载的原理图放置（先加载 .litematic 再按 V+U）";
            return p;
        }

        int enabled = 0;
        boolean stop = false;
        for (Object placement : placements) {
            if (stop) break;
            if (!(RestockBridge.callAny(placement, "isEnabled") instanceof Boolean b) || !b) continue;
            enabled++;
            for (Object box : regionBoxes(placement)) {
                int[] a = boxCorner(box, "getPos1");
                int[] c = boxCorner(box, "getPos2");
                if (a == null || c == null) continue;
                int x1 = Math.min(a[0], c[0]), x2 = Math.max(a[0], c[0]);
                int y1 = Math.min(a[1], c[1]), y2 = Math.max(a[1], c[1]);
                int z1 = Math.min(a[2], c[2]), z2 = Math.max(a[2], c[2]);

                scanY:
                for (int y = y1; y <= y2; y++) {            // y 外层 = 自下往上
                    for (int z = z1; z <= z2; z++) {
                        for (int x = x1; x <= x2; x++) {
                            if (p.solids.size() + p.fluidsTail.size() >= SR.BUILD_MAX_PLAN) {
                                p.truncated = true;
                                stop = true;
                                break scanY;
                            }
                            if (++p.inspected > MAX_INSPECT) {
                                p.truncated = true;
                                p.diag = "检视坐标数已达上限 " + MAX_INSPECT + "，本批只收了 "
                                        + (p.solids.size() + p.fluidsTail.size()) + " 格";
                                stop = true;
                                break scanY;
                            }
                            BlockPos pos = new BlockPos(x, y, z);
                            BlockState want;
                            try {
                                want = (BlockState) getBlockState.invoke(world, pos);
                            } catch (Throwable t) {
                                continue;
                            }
                            if (want == null || want.isAir()) continue;
                            boolean fluid = isFluidState(want);
                            if (fluid && !placeFluids()) continue;

                            // 现状过滤：客户端这一带没加载完时 getBlockState 给的是空气，
                            // 那就一并交过去——服务端会拿真实世界再判一遍（覆盖策略由服务端把关）。
                            BlockState have = mc.level.hasChunkAt(pos) ? mc.level.getBlockState(pos) : null;
                            if (have != null) {
                                if (have == want) continue;         // 已经是对的方块（投影也不算缺）
                                if (!mayReplace(have)) continue;    // 覆盖策略不放行
                            }

                            String key = encode(want);
                            Integer idx = p.paletteIndex.get(key);
                            if (idx == null) {
                                // r25：形态表两道闸——条数上限、字节预算。撞线只丢这一格，
                                // 绝不把超限的首包发出去（自定义载荷超 1 MiB 是直接断连接的）。
                                if (p.palette.size() >= SR.BUILD_MAX_PALETTE
                                        || p.paletteBytes + key.length() + 4 > SR.BUILD_MAX_PALETTE_BYTES) {
                                    p.truncated = true;
                                    p.paletteFull = true;
                                    continue;                       // 形态表满：这一格不要了
                                }
                                idx = p.palette.size();
                                p.palette.add(key);
                                p.paletteBytes += key.length() + 4;  // +4：长度前缀 + 分隔
                                p.paletteIndex.put(key, idx);
                            }
                            p.add(key, pos.asLong(), idx, fluid);
                        }
                    }
                    if ((p.inspected & 0x3FFF) == 0 && System.currentTimeMillis() > deadline) {
                        p.truncated = true;
                        p.diag = "扫描超时（" + SR.BUILD_SCAN_CAP_MS + "ms），本批只收了 "
                                + (p.solids.size() + p.fluidsTail.size()) + " 格";
                        stop = true;
                        break scanY;
                    }
                }
            }
        }

        if (enabled == 0 && p.diag.isEmpty()) p.diag = "没有启用的原理图放置";
        if (p.diag.isEmpty() && p.paletteFull) {
            p.diag = "形态表已达上限（" + p.palette.size() + " 条 / " + p.paletteBytes + " B），"
                    + "其余用不到的形态的格子本轮没收";
        }
        if (p.diag.isEmpty() && p.truncated) {
            p.diag = "计划太大，已截断（上限 " + SR.BUILD_MAX_PLAN + " 格 / 检视 " + p.inspected + " 格）";
        }
        if (p.diag.isEmpty() && p.solids.isEmpty() && p.fluidsTail.isEmpty()) {
            p.diag = "启用的原理图里没有需要补的格子（现状全都对了，或者覆盖策略挡住了）";
        }
        return p;
    }

    /** 水/熔岩这类「方块本体就是流体」的形态（含模组流体，它们基本都继承 LiquidBlock）。 */
    private static boolean isFluidState(BlockState s) {
        if (s.getBlock() instanceof LiquidBlock) return true;
        var fl = s.getFluidState();
        return !fl.isEmpty() && fl.createLegacyBlock() == s;
    }

    /** 只填空气 / 允许原版判定可替换（草、雪、水） / 只要不对就换。 */
    private static boolean mayReplace(BlockState have) {
        return switch (replacePolicy()) {
            case SR.BUILD_REPLACE_SOFT -> have.isAir() || have.canBeReplaced();
            case SR.BUILD_REPLACE_MISMATCH -> true;
            default -> have.isAir();
        };
    }

    /** {@code blockid[prop=value,...]}；属性按声明顺序写，去重稳定。 */
    private static String encode(BlockState s) {
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        var values = s.getValues();
        if (values.isEmpty()) return id;
        StringBuilder sb = new StringBuilder(id.length() + 32).append(id).append('[');
        for (Map.Entry<Property<?>, Comparable<?>> e : values.entrySet()) {
            sb.append(e.getKey().getName()).append('=').append(nameOf(e.getKey(), e.getValue())).append(',');
        }
        sb.setLength(sb.length() - 1);
        return sb.append(']').toString();
    }

    /** 属性值的序列化名（枚举拿的是 {@code facing=north} 那套小写名，整型就是数字）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String nameOf(Property prop, Comparable v) {
        try {
            return prop.getName(v);
        } catch (Throwable t) {
            return String.valueOf(v);
        }
    }

    // ---------------- 反射小件（全 public getter；任何一环拿不到就只回一句诊断） ----------------

    private static Object schematicWorld() {
        for (String n : WORLD_HANDLER) {
            try {
                Class<?> c = Class.forName(n);
                Method m = RestockBridge.findMethod(c, "getSchematicWorld", 0);
                if (m != null) {
                    m.setAccessible(true);
                    Object w = m.invoke(null);
                    if (w != null) return w;
                }
                Object inst = c.getDeclaredField("INSTANCE").get(null);
                Method g = RestockBridge.findMethod(c, "getWorld", 0);
                if (inst != null && g != null) return g.invoke(inst);
            } catch (ClassNotFoundException ignored) {
                // 试下一个包名
            } catch (Throwable t) {
                GhostDebug.line("      建造：原理图世界取不到(" + n + ") " + t.getClass().getSimpleName());
            }
        }
        return null;
    }

    /**
     * 放置的区域集合（<b>世界坐标</b>）。三条路线取第一条走得通的：
     * ① {@code getSubRegionBoxes(PLACEMENT_ENABLED)} —— 投影统计材料清单用的就是它，最准，
     *    而且尊重「单独关掉某个子区域」；
     * ② {@code getAllSubRegionBoxes()}（部分版本公开了无参版）；
     * ③ {@code getEclosingBox()}（投影自己的拼写：整个放置的外接盒）。
     */
    @SuppressWarnings("unchecked")
    private static Collection<Object> regionBoxes(Object placement) {
        Object enumVal = requiredEnabled();
        if (enumVal != null) {
            try {
                Method m = RestockBridge.findMethod(placement.getClass(), "getSubRegionBoxes", 1);
                if (m != null) {
                    Object map = m.invoke(placement, enumVal);
                    if (map instanceof Map<?, ?> mm && !mm.isEmpty()) {
                        return new ArrayList<>((Collection<Object>) mm.values());
                    }
                }
            } catch (Throwable ignored) {}
        }
        try {
            Object o = RestockBridge.callAny(placement, "getAllSubRegionBoxes");
            if (o instanceof Map<?, ?> mm && !mm.isEmpty()) return new ArrayList<>((Collection<Object>) mm.values());
        } catch (Throwable ignored) {}
        try {
            Object box = RestockBridge.callAny(placement, "getEclosingBox", "getEnclosingBox");
            if (box != null) return List.of(box);
        } catch (Throwable ignored) {}
        return List.of();
    }

    private static Object requiredEnabled;
    private static boolean enumProbed;

    /** {@code SubRegionPlacement$RequiredEnabled.PLACEMENT_ENABLED}（两个包名都试）。 */
    private static Object requiredEnabled() {
        if (enumProbed) return requiredEnabled;
        enumProbed = true;
        for (String n : SUB_REGION_PLACEMENT) {
            try {
                Class<?> e = Class.forName(n + "$RequiredEnabled");
                Object[] constants = e.getEnumConstants();
                if (constants == null) continue;
                for (Object constant : constants) {
                    if ("PLACEMENT_ENABLED".equals(((Enum<?>) constant).name())) {
                        requiredEnabled = constant;
                        return requiredEnabled;
                    }
                }
            } catch (Throwable ignored) {
                // 试下一个包名
            }
        }
        return null;
    }

    /** Box 的某个角（{@code getPos1/getPos2}）→ {x,y,z}；拿不到返回 null。 */
    private static int[] boxCorner(Object box, String getter) {
        try {
            Method m = RestockBridge.findMethod(box.getClass(), getter, 0);
            if (m == null) return null;
            Object o = m.invoke(box);
            if (o == null) return null;
            if (o instanceof BlockPos bp) return new int[]{bp.getX(), bp.getY(), bp.getZ()};
            Object x = RestockBridge.callAny(o, "getX");
            Object y = RestockBridge.callAny(o, "getY");
            Object z = RestockBridge.callAny(o, "getZ");
            if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
                return new int[]{nx.intValue(), ny.intValue(), nz.intValue()};
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
