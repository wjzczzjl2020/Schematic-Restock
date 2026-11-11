package cn.blockforge.generated.schematicrestock;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端全部表现层：V+M 键、HUD（缺料读取状态 + 亮/灰「按钮」+ 进度）、
 * 青蓝描边闪烁、进游戏命令。接管本体在服务端跑，这里只发起与播报。
 */
public final class ClientEvents {

    public static final KeyMapping KEY_TAKEOVER = new KeyMapping(
            "key.schematic_restock.takeover", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M,
            "key.categories.schematic_restock");

    // ---- 状态机 ----
    public enum Mode { IDLE, RUNNING }
    private static Mode mode = Mode.IDLE;
    private static long lastEndMs = 0;      // 退出后再触发冷却 3s
    private static long runningSince = 0;
    private static long exitAt = 0; // 缺料结局的 1.5s 自动退出延时
    private static long noopMsgUntil = 0;
    private static String noopMsg = "";

    // 缺料快照（HUD 显示 + 发给服务端）
    private static int srTypes = 0;
    private static int srItems = 0;

    // 结果播报
    private static long resultUntil = 0;
    private static String resultLine = "";
    private static String resultNote = "";
    private static final List<String> resultMissing = new ArrayList<>();

    // 服务端推进来的搬运进度
    private static int progDone = 0;
    private static int progTotal = 0;
    private static int progMoved = 0;
    private static int progSkipped = 0;
    private static int progCraft = 0;   // 已推进的幽灵合成手数
    /** r14：会话阶段。0 = 阶段 A 搬运现成物资，1 = 阶段 B 递归合成（HUD 上直接写出来）。 */
    private static int progPhase = 0;
    /** r14：已经判定「查无工作台配方」的种类数（去皮原木这类）。 */
    private static int progNoRecipe = 0;
    private static long lastProgressMs = 0;

    // 描边闪烁：方块 -> 结束毫秒。
    // r24：一次框选最多要塞 64 个坐标，而这份列表正被渲染循环逐条遍历画线框 ——
    // ArrayList 在「遍历中增删」下就是 ConcurrentModificationException 的现场，
    // 换成写时复制列表：迭代器拿的是不可变快照，任何时刻加坐标都掀不翻正在跑的遍历。
    private static final List<long[]> flashes = new java.util.concurrent.CopyOnWriteArrayList<>();

    private ClientEvents() {}

    public static void init(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(KEY_TAKEOVER));
        BuildBridge.init(modBus);   // r16：V+U 开始/暂停、V+Y 取消（同一套抢键机制）
        NeoForge.EVENT_BUS.register(ClientEvents.class);
        TakeoverKeyHandler.init(); // InputEvent.Key 抢键（任务 1）
    }

    /** 服务端补料接管是否还在跑（r16：自动建造开工前要避开它，两者都要动背包）。 */
    public static boolean isTakeoverRunning() {
        return mode == Mode.RUNNING;
    }

    /** 服务端 MarkCount 到达时调用（客户端侧也闪一下当前手持标记的箱子由本地处理）。 */
    public static void onMarkCount(int count, int outputs) {
        markCount = count;
        outputCount = outputs;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            // r7：两类箱子的数量一起报，免得用户分不清刚才那一下标的是材料箱还是输出箱
            mc.player.displayClientMessage(
                    Txt.c("hud.marks", count, outputs).withStyle(ChatFormatting.AQUA), true);
        }
    }

    /** 供 payload 处理器调用：服务端搬运进度（每 ~4 tick 一次）。 */
    public static void onProgress(Packets.TakeoverProgress p) {
        progDone = p.typesDone();
        progTotal = p.typesTotal();
        progMoved = p.moved();
        progSkipped = p.skipped();
        progCraft = p.craftSteps();
        progPhase = p.phase();
        progNoRecipe = p.noRecipeTypes();
        lastProgressMs = System.currentTimeMillis();
        // 缺口账本喂数：服务端真实投进存放处的累计件数（会话号变了会自动换基线）
        if (p.hasPlacedSnapshot()) MaterialSyncClient.onPlacedSnapshot(p.sessionKey(), p.placedSnapshot());
        if (mode != Mode.RUNNING) {
            mode = Mode.RUNNING;
            runningSince = lastProgressMs;
        }
    }

    /** 供 payload 处理器调用：接管结果。 */
    public static void onResult(Packets.TakeoverResult r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // 最终投料账本（累计值）：进度包若有遗漏，这里按会话累计补齐，客户端增量合并不会重复扣
        MaterialSyncClient.onPlacedSnapshot(r.sessionKey(), r.placed());

        long nowMs = System.currentTimeMillis();
        boolean hadMissing = r.missingTypes() > 0 || !r.remaining().isEmpty();
        if (hadMissing) {
            // 缺料：提示音响过之后，再等 1.5 秒把控制权交还玩家
            exitAt = nowMs + SR.MISSING_EXIT_MS;
        } else {
            mode = Mode.IDLE;
            lastEndMs = nowMs;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(Txt.s("res.take.written", r.written())).append(" | ")
                .append(Txt.s("res.take.skipped", r.skipped())).append(" | ")
                .append(Txt.s("res.take.gap", r.missingTypes()));
        if (r.craftConsumed() > 0) sb.append(" | ").append(Txt.s("res.take.craft_consumed", r.craftConsumed()));
        if (!r.craftFails().isEmpty()) sb.append(" | ").append(Txt.s("res.take.craft_fails", r.craftFails().size()));
        if (r.inventoryFull()) sb.append(" | ").append(Txt.s("res.take.inv_full"));
        if (r.capped()) sb.append(" | ").append(Txt.s("res.take.capped", SR.MAX_TYPES));
        resultLine = sb.toString();
        resultNote = r.note() == null ? "" : r.note();
        resultMissing.clear();
        for (Packets.Entry e : r.remaining()) {
            resultMissing.add(Txt.s("res.take.missing_item", displayOf(e.id()), e.count()));
        }
        for (Packets.Entry e : r.craftFails()) {
            resultMissing.add(Txt.s("res.take.craftfail_item", displayOf(e.id()), e.count()));
        }
        resultUntil = System.currentTimeMillis() + (resultMissing.isEmpty() ? 2500 : 5000);
        progMoved = r.written();
        progSkipped = r.skipped();

        // 结局音 + 缺料清单走聊天栏
        if (resultMissing.isEmpty()) {
            mc.player.playSound(SchematicRestockMod.SND_OK.get(), 1.0f, 1.0f);
            mc.player.displayClientMessage(Txt.c("chat.takeover_line", resultLine)
                    .withStyle(ChatFormatting.GREEN), false);
        } else {
            mc.player.playSound(SchematicRestockMod.SND_MISSING.get(), 1.0f, 1.0f);
            mc.player.displayClientMessage(Txt.c("chat.takeover_line", resultLine)
                    .withStyle(ChatFormatting.YELLOW), false);
            for (String line : resultMissing) {
                mc.player.displayClientMessage(Component.literal(line).withStyle(ChatFormatting.GRAY), false);
            }
        }
        if (!resultNote.isEmpty()) {
            // note 是服务端拼的自由文本（见 TakeoverEngine.Result.toPayload），前缀标签本地化，正文照原样
            mc.player.displayClientMessage(Txt.c("chat.takeover_line", resultNote)
                    .withStyle(ChatFormatting.DARK_GRAY), true);
        }
        // 真搬到了东西才催投影重算清单：投影的材料列表是缓存的，不重算的话我们连一份干净的
        // 「世界原始缺口」基准都拿不到。重算会把数字刷回原值，MaterialSyncClient 在 500ms 内
        // 按账本重新扣好——这一闪是有意的：以投影自己的统计为准，我们的账本只做增量。
        if (r.written() > 0) RestockBridge.requestRecount();
        // r8 Bug②：会话收尾记一次「世界原始缺口」锚点。之后玩家把材料砌上墙，投影重算会让原始缺口下降，
        // 下一次校验靠这个锚点分清"砌上墙了"（账本保留）和"被拿走了"（账本清零）。
        if (r.written() > 0) MaterialSyncClient.seedRawBaseline();
        if (!hadMissing) exitAt = 0;
    }

    private static String displayOf(String id) {
        Item item = (Item) net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse(id));
        if (item == null || item == net.minecraft.world.item.Items.AIR) return id;
        return Component.translatable(item.getDescriptionId()).getString();
    }

    private static Object lastWorldKey;   // 世界/玩家身份，变了就清缺口账本的条目基准

    /**
     * <b>r28（发布前防漏）：客户端全部静态状态的一次彻底清空。</b>
     *
     * <p>两个调用点：① {@link #onClientLogout} —— 退服 / 退回标题画面<b>当场</b>执行，
     * 不再拖到"下一次进世界才想起来清"（旧版 {@code mc.level == null} 时 {@link #onClientTick}
     * 直接 return，退服后的这段时间里旧世界的账本条目、施工计划、描边坐标全挂在静态字段上）；
     * ② {@link #onClientTick} 的世界身份变化 —— 覆盖换维度 / 重进同一存档这类
     * "level 对象换了但没经历退服"的路径。</p>
     */
    private static void resetAllClientState() {
        MaterialSyncClient.resetForNewWorld();   // 旧世界投影清单对象的引用、缺口账本、扣账基线
        BuildBridge.resetForNewWorld();          // r16：旧世界的施工计划 outbox / 进度 / 状态机
        // 接管会话与结果播报：全是旧世界的数，留着只会骗 HUD
        mode = Mode.IDLE;
        exitAt = 0;
        runningSince = 0;
        lastProgressMs = 0;
        progDone = progTotal = progMoved = progSkipped = progCraft = progPhase = progNoRecipe = 0;
        resultUntil = 0;
        resultLine = "";
        resultNote = "";
        resultMissing.clear();
        noopMsgUntil = 0;
        noopMsg = "";
        srTypes = 0;
        srItems = 0;
        markCount = -1;                          // 服务端会在重进时 sync() 回来，这里先收白牌
        outputCount = -1;
        flashes.clear();                         // 描边坐标属于旧世界
        boxAnchor = null;                        // r24：旧世界的框选 A 点对新世界就是一句「起点已记下」的谎
        wandPressLatched = false;
        wandLeftPressLatched = false;
        lastOutputMarkPos = 0L;
        lastOutputMarkMs = 0L;
    }

    /**
     * 退服 / 断开连接：客户端静态缓存<b>立刻</b>清空。
     * 服务端那一份对应 {@code PlayerEvent.PlayerLoggedOutEvent}
     * （TakeoverEngine / AutoBuildEngine 各自处理，两头加起来才是"彻底"）。
     */
    @SubscribeEvent
    public static void onClientLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) {
        lastWorldKey = null;
        resetAllClientState();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // 换世界/重进服：账本里记的都是旧世界的条目对象，必须清干净
        Object worldKey = mc.level;
        if (lastWorldKey != worldKey) {
            lastWorldKey = worldKey;
            resetAllClientState();
        }

        // r24 优化 1：标记签的「一次按下只算一次」闩锁 —— 鼠标键真的抬起才放行。
        // 不做这件事的话，按住右键每 4 tick 一次的原版重复交互会把同一个箱子来回翻好几次。
        if (wandPressLatched && !mc.options.keyUse.isDown()) wandPressLatched = false;
        if (wandLeftPressLatched && !mc.options.keyAttack.isDown()) wandLeftPressLatched = false;

        RestockBridge.refresh();
        srTypes = RestockBridge.types();
        srItems = RestockBridge.totalMissing();

        // 任务 1：把「我们投进背包的件数」扣进投影 L 键列表与 HUD（内部 500ms 节流 + 异常静默熔断）
        MaterialSyncClient.tick();

        if (mode == Mode.RUNNING && exitAt != 0 && System.currentTimeMillis() >= exitAt) {
            mode = Mode.IDLE;
            lastEndMs = System.currentTimeMillis();
            exitAt = 0;
        }

        // 卡死保护：服务端长时间不回进度就解除 RUNNING
        if (mode == Mode.RUNNING) {
            long quiet = System.currentTimeMillis() - Math.max(runningSince, lastProgressMs);
            if (quiet > SR.CLIENT_WATCHDOG_MS) {
                mode = Mode.IDLE;
                lastEndMs = System.currentTimeMillis();
            }
        }

        // r21 Bug2：单按 M / U / Y（没按住 V）= **纯放行**。以前这里会响一声"噗"、弹一行
        // 「请按住 V」，还会跑一遍背包动态校验——玩家按 M 是想开小地图，这些动静既吵人
        // 又拖 tick。现在只把自己排上的点击吃掉（防止残留点击在别的时机冒出来），
        // 别的一律不做：不发声、不提示、不扫描、不发起接管。按键用法由 HUD 常驻提示教。
        while (KEY_TAKEOVER.consumeClick()) { /* 静默放行 */ }
        while (BuildBridge.KEY_BUILD.consumeClick()) { /* 静默放行 */ }
        while (BuildBridge.KEY_BUILD_CANCEL.consumeClick()) { /* 静默放行 */ }

        BuildBridge.tick(mc);   // r16：分帧上传 + 看门狗 + 延迟重锚基线
    }

    /**
     * 组合键（按住 V + 我们的某条键）在 {@code InputEvent.Key} 里命中。
     * r16：三条组合键共用同一套抢键，按「命中了哪条映射」分派。
     */
    public static void onComboKey(Minecraft mc, net.minecraft.client.KeyMapping key) {
        if (key == BuildBridge.KEY_BUILD) BuildBridge.toggle(mc);
        else if (key == BuildBridge.KEY_BUILD_CANCEL) BuildBridge.cancel(mc);
        else tryTakeover(mc);
    }

    /**
     * V+M 组合：M 是主键，V 需按住（在配置里可改 M；V 写死为组合前缀）。
     * r21：只有抢键命中才会进来（必然是 V+M），单按 M 的兜底入口已删——放行。
     */
    private static void tryTakeover(Minecraft mc) {
        long now = System.currentTimeMillis();

        // ---- r8 Bug②：发起会话前的强制动态校验（必须在「点亮」判定之前）----
        // 玩家把模组投进背包的东西拿走（塞回箱子、扔掉）之后，静态投料账本还记着那几件，
        // 缺口被扣成 0 —— 于是 V+M 既不亮、按下去也"认为不缺料、一件都不去拿"。
        // 口径：有效缺口 = 当前世界原始缺口 − 账本里<b>还真实存在</b>于背包的件数。
        // 先校验、再当场写回列表、再现读一次快照，点亮判定和发包清单才可能是同一个数。
        int cleared = MaterialSyncClient.validateCredits();
        if (cleared > 0) {
            MaterialSyncClient.syncNow();          // 绕开 500ms 节流，把涨回来的缺口显示回去
            RestockBridge.refresh(true);           // 立刻现读快照（不催投影重算，避免跟账本抢口径）
            if (mc.player != null) {
                mc.player.displayClientMessage(Txt.c("chat.takeover_line",
                                MaterialSyncClient.lastValidateNote())
                        .withStyle(ChatFormatting.YELLOW), false);
            }
        }

        // 「点亮」条件：读取成功且 x/y 非 0、不在冷却、未在运行
        if (!RestockBridge.ready()) {
            playNoop(mc);
            setNoop(cleared > 0 ? Txt.s("noop.still_enough")
                                : Txt.s("noop.not_found"));
            return;
        }
        if (mode == Mode.RUNNING) {
            setNoop(Txt.s("noop.running", progDone, progTotal));
            return;
        }
        // r23「有货不拿」主修②：建造进行中<b>不再拒绝</b> V+M。旧版这里直接 return——
        // 而卡在缺料上的施工会话永远 RUNNING（延后重试要 5 秒一轮、会话两小时才收场），
        // 于是「缺料 → V+M → 接着建」的闭环在补料这一环被自己焊死：箱里堆成山，一件不进包。
        // 现在服务端收到 StartTakeover 会自动把施工按停让路，补料收场再自动续工
        // （AutoBuildEngine.holdBuildForRestock / releaseBuildAfterRestock）。
        // 唯一还要拦的窗口：计划正在分帧上传——服务端连会话都还没建，暂停无从谈起。
        if (BuildBridge.isUploading()) {
            playNoop(mc);
            setNoop(Txt.s("noop.uploading"));
            return;
        }
        if (now - lastEndMs < SR.COOLDOWN_MS) {
            long left = (SR.COOLDOWN_MS - (now - lastEndMs) + 999) / 1000;
            setNoop(Txt.s("noop.cooldown", left));
            return;
        }

        // ---- r21 Bug1：发包口径 = 投影的<b>原始缺口</b>（不扣投料账本）----
        // 身上已有的件数改由服务端拿真实背包统一扣（见 TakeoverEngine.Session）。
        // 为什么不能沿用 effectiveShortfall()：那是「原始缺口 − 账本」，而账本记的件数
        // 此刻就躺在背包里——服务端再按背包扣一遍就是**双重扣减**，会凭空少搬一整批。
        // 客户端账本从此只管 L 键列表显示，取多少由服务端照着玩家身上的实况定，
        // 断线重连、来回换世界、创造塞料都骗不了它——补料从此幂等。
        List<Packets.Entry> entries = new ArrayList<>();
        int sent = 0;
        if (cleared > 0) {
            RestockBridge.requestRecount();        // 搬完之前顺手催一次重算，让显示继续跟账本收敛
        }
        List<Packets.Entry> live = MaterialSyncClient.rawShortfall();
        if (MaterialSyncClient.shortfallTrusted() && !live.isEmpty()) {
            for (Packets.Entry e : live) {
                if (sent >= SR.MAX_TYPES) break;
                entries.add(e);
                sent++;
            }
        } else if (MaterialSyncClient.shortfallTrusted()) {
            // 原始缺口当真为空：不动钥匙，直接告诉玩家
            playNoop(mc);
            setNoop(cleared > 0 ? Txt.s("noop.enough_validated", cleared) : Txt.s("noop.enough"));
            return;
        } else {
            // 原始缺口不可信（投影没桥接、倍率清单、/matfill simulate 自测）：退回老口径用快照
            for (RestockBridge.Shortfall s : RestockBridge.snapshot()) {
                if (sent >= SR.MAX_TYPES) break;
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.stack().getItem()).toString();
                entries.add(new Packets.Entry(id, s.missing()));
                sent++;
            }
        }
        mode = Mode.RUNNING;
        runningSince = System.currentTimeMillis();
        lastProgressMs = runningSince;
        progDone = 0;
        progTotal = sent;
        progMoved = 0;
        progSkipped = 0;
        PacketDistributor.sendToServer(new Packets.StartTakeover(entries));
        mc.player.displayClientMessage(Txt.c(BuildBridge.isBusy() ? "chat.take_started_hold"
                : "chat.take_started", sent).withStyle(ChatFormatting.AQUA), true);
    }

    private static void playNoop(Minecraft mc) {
        if (mc.player != null) mc.player.playSound(SchematicRestockMod.SND_NOOP.get(), 0.6f, 1.0f);
    }

    private static void setNoop(String msg) {
        noopMsg = msg;
        noopMsgUntil = System.currentTimeMillis() + 2500;
        // r11：HUD 隐藏（没加载原理图）时提示没人看得见，改推一份到动作栏，按键不至于无声无息
        if (!hudVisible()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.displayClientMessage(Component.literal(msg), true);
        }
    }

    // ---------------- 标记箱：右键 / Shift+右键交互 ----------------

    /**
     * r24 优化 1：<b>一次按下只算一次动作</b>。
     *
     * <p>按住右键时原版并不消停 —— {@code Minecraft#startUseItem} 一开头就把
     * {@code rightClickDelay} 设成 4，于是按住不放约每 4 tick（200ms）就重发一次
     * {@code RightClickBlock}。标记是「开关」语义，照单全收的话按住两秒能把同一个箱子
     * 来回翻七八次，还伴着一串提示音。现在首次按下处理一次，同一次按下的后续事件一律静默吃掉，
     * 直到 {@link #onClientTick} 看见按键真的抬起才复位（左键同理）。</p>
     */
    private static boolean wandPressLatched = false;
    private static boolean wandLeftPressLatched = false;

    /**
     * 由 CommonEvents 在右键方块时调用（客户端，主手是标记签，原版交互已在两端被取消）。
     * 返回 true 表示这次右键已被本模组消费。
     *
     * <p>r24 优化 1：动手之前先判「目标是不是容器」。
     * 客户端<b>确定</b>不是容器（泥土、石头、空气这类没方块实体的）→ 屏幕中间红字提示 + 一声闷响，
     * 一个包都不发；判不准（有方块实体、但客户端拿不到 {@code ItemHandler} 能力，
     * 模组储物的常态）照旧发给服务端，由服务端权威定夺。
     * 之所以只拦「确定不是」：判错一次的代价是把能用的材料箱永久踢出标记名单，
     * 比多发一个包严重得多。</p>
     */
    public static boolean handleWandClick(BlockPos pos, boolean shiftDown) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        if (wandPressLatched) return true;          // 同一次按下的重复事件：吃掉，不做第二遍
        wandPressLatched = true;

        if (shiftDown) {
            // 框选：第一次记 A 点，第二次发 A+B。两端点<b>不要求</b>是容器 ——
            // 框一堵储物墙时角点常常落在石头/空气上；上限和「范围内有没有箱子」由服务端红字回话。
            if (boxAnchor == null) {
                boxAnchor = pos.immutable();
                mc.player.displayClientMessage(Txt.c("chat.wand_anchor", SR.MAX_BOX_MARKS)
                        .withStyle(ChatFormatting.AQUA), true);
                mc.player.playSound(SchematicRestockMod.SND_NOOP.get(), 0.5f, 1.6f);
            } else {
                PacketDistributor.sendToServer(new Packets.MarkBox(boxAnchor.asLong(), pos.asLong()));
                flashBox(mc.level, boxAnchor, pos);
                boxAnchor = null;
            }
            return true;
        }

        // ---- 单点标记 / 取消材料箱 ----
        if (MarkedChestStore.guessContainer(mc.level, pos) == MarkedChestStore.ContainerGuess.NO) {
            mc.player.displayClientMessage(Txt.c("chat.wand_not_container")
                    .withStyle(ChatFormatting.RED), true);
            mc.player.playSound(SchematicRestockMod.SND_NOOP.get(), 0.7f, 0.75f);
            return true;
        }
        PacketDistributor.sendToServer(new Packets.MarkToggle(pos.asLong()));
        // 闪一下当前这格（✔/取消 与对应音效由服务端那侧播，两头各响一次会变成结巴）
        if (MarkedChestStore.hasContainer(mc.level, pos)) addFlash(pos);
        return true;
    }

    private static int markCount = -1;

    /** r7：已标记的输出箱（溢出箱）数量，-1 = 服务端还没告诉过我。 */
    private static int outputCount = -1;

    /**
     * r7：Shift+左键标记输出箱的节流。两道闸门：
     * 本次按下只算一次（{@link #wandLeftPressLatched}），
     * 外加「同一个坐标 {@link SR#MARK_CLICK_DEBOUNCE_MS} 毫秒内只算一次」兜住跨帧抖动。
     */
    private static long lastOutputMarkPos = 0L;
    private static long lastOutputMarkMs = 0L;

    /**
     * 手持标记签 Shift+左键 → 发标记/取消输出箱包（客户端消费这次左键，不挖方块）。
     * 目标同样必须是容器，判定口径与右键完全一致。
     *
     * @return true = 这次左键已被消费
     */
    public static boolean handleWandLeftClick(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;

        if (wandLeftPressLatched) return true;      // 同一次按下的重复事件
        wandLeftPressLatched = true;

        long now = System.currentTimeMillis();
        long key = pos.asLong();
        if (key == lastOutputMarkPos && now - lastOutputMarkMs < SR.MARK_CLICK_DEBOUNCE_MS) {
            return true;    // 抖动：吃掉事件但不重复发包
        }
        lastOutputMarkPos = key;
        lastOutputMarkMs = now;

        if (MarkedChestStore.guessContainer(mc.level, pos) == MarkedChestStore.ContainerGuess.NO) {
            mc.player.displayClientMessage(Txt.c("chat.wand_not_container_output")
                    .withStyle(ChatFormatting.RED), true);
            mc.player.playSound(SchematicRestockMod.SND_NOOP.get(), 0.7f, 0.75f);
            return true;
        }

        PacketDistributor.sendToServer(new Packets.MarkOutputToggle(key));
        if (MarkedChestStore.hasContainer(mc.level, pos)) addFlash(pos);
        return true;
    }

    private static BlockPos boxAnchor = null;

    /**
     * 把框内<b>真的</b>是容器的方块全部描边（r24：一次最多 {@link SR#MAX_BOX_MARKS} 个，
     * 旧版只闪两个角，框 64 个箱子时完全看不出框到了哪）。
     *
     * <p>成本靠两道便宜闸门压住：先量体积（与服务端同一个
     * {@link SR#BOX_SCAN_VOLUME_CAP}，超了干脆不闪），再对每格只读一次
     * {@code BlockState.hasBlockEntity()} 这个布尔值 —— 一整面墙里绝大多数格子在这一步就过去了，
     * 只有真带方块实体的格子才去查能力。这一步纯粹是本地视觉反馈，名单由服务端算。</p>
     */
    private static void flashBox(net.minecraft.world.level.Level level, BlockPos a, BlockPos b) {
        addFlash(a);
        addFlash(b);
        long dx = Math.abs(a.getX() - b.getX()) + 1L;
        long dy = Math.abs(a.getY() - b.getY()) + 1L;
        long dz = Math.abs(a.getZ() - b.getZ()) + 1L;
        if (dx * dy * dz > SR.BOX_SCAN_VOLUME_CAP) return;

        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        int found = 0;
        outer:
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).hasBlockEntity()) continue;
                    if (MarkedChestStore.hasContainer(level, pos)) {
                        addFlash(pos);
                        if (++found >= SR.MAX_BOX_MARKS) break outer;
                    }
                }
            }
        }
    }

    /**
     * 登记一次描边。
     *
     * <p><b>r24 优化 2 的硬要求</b>：一次框选最多要塞 {@code MAX_BOX_MARKS=64} 个坐标，
     * 而这份列表同时被渲染线程逐条遍历画线框。集合本体见字段声明（写时复制），
     * 裁剪改成 {@code while} 一次裁到线内 —— 旧写法每加一个只删一个，
     * 一次加 64 个就会把列表堆到几百条，还会在遍历中途改结构。</p>
     */
    public static void addFlash(BlockPos pos) {
        flashes.add(new long[]{pos.getX(), pos.getY(), pos.getZ(),
                System.currentTimeMillis() + SR.FLASH_MS});
        while (flashes.size() > SR.FLASH_CAP) {
            if (flashes.isEmpty()) break;
            flashes.remove(0);
        }
    }

    // ---------------- HUD ----------------

    /**
     * r11 体验优化 2：HUD 只在「加载了 Forgematica 原理图且成功读到材料缺口」时出现。
     * 三种放行情形：真实缺料可读（ready）、自测模式（/matfill simulate 注入的假缺料）、
     * 接管正在进行（RUNNING，防止搬运途中投影重算导致 HUD 一闪一闪）。
     * 原理图没加载 / 接口读不到时 HUD <b>彻底隐藏</b>，不再常驻右上角。
     */
    private static boolean hudVisible() {
        return RestockBridge.isSimulated() || RestockBridge.ready() || mode == Mode.RUNNING
                || BuildBridge.hudActive();   // r16：建造进行中即使投影那边读不到缺口也要显示进度
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post e) {
        if (!hudVisible()) return;   // 没加载原理图 / 没读到缺口：什么都不画
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        GuiGraphics g = e.getGuiGraphics();
        // r11 体验优化 2：改到屏幕左上角（原来是右上角 sw-250，和小地图严重冲突）
        int x = 4, y = 4, w = 240;

        boolean ready = RestockBridge.ready();
        boolean running = mode == Mode.RUNNING;

        String line1;
        ChatFormatting c1;
        if (RestockBridge.isSimulated()) {
            line1 = Txt.s("hud.simulated", srTypes, srItems);
            c1 = ChatFormatting.LIGHT_PURPLE;
        } else if (ready) {
            line1 = Txt.s("hud.read", srTypes, srItems);
            c1 = ChatFormatting.AQUA;
        } else if (RestockBridge.status() == RestockBridge.Status.READING) {
            line1 = Txt.s("hud.reading");
            c1 = ChatFormatting.YELLOW;
        } else {
            line1 = Txt.s("hud.none");
            c1 = ChatFormatting.GRAY;
        }

        boolean cooled = System.currentTimeMillis() - lastEndMs < SR.COOLDOWN_MS;
        String line2;
        ChatFormatting c2;
        if (running) {
            // r14：把当前走的是哪一阶段写明 —— 阶段 A 只搬现成的，阶段 B 才开始算配方，
            // 玩家看到「阶段 B」就知道现成物资已经全部到手了。
            line2 = Txt.s("hud.running",
                    progPhase == 0 ? Txt.s("hud.phase_a") : Txt.s("hud.phase_b"),
                    progDone, progTotal, progMoved)
                    + (progCraft > 0 ? " · " + Txt.s("hud.craft", progCraft) : "")
                    + (progNoRecipe > 0 ? " · " + Txt.s("hud.norec", progNoRecipe) : "");
            c2 = ChatFormatting.AQUA;
        } else if (!ready) { line2 = Txt.s("hud.key_gray"); c2 = ChatFormatting.DARK_GRAY; }
        else if (cooled) { line2 = Txt.s("hud.key_cool"); c2 = ChatFormatting.DARK_GRAY; }
        else { line2 = Txt.s("hud.key_ready"); c2 = ChatFormatting.WHITE; }

        // r16：自动建造那一档（V+U/V+Y）占 1~2 行。空闲时它就是按键提示 ——
        // 反正整个面板只在「读到原理图缺口 / 有会话在跑」时才出现，不会白占地方。
        String line3 = BuildBridge.hudLine();
        int[] bar = BuildBridge.hudBar();

        int h = 28 + (noopMsgUntil > System.currentTimeMillis() ? 10 : 0)
                + (running && progSkipped > 0 ? 10 : 0)
                + (MaterialSyncClient.ledgerTypes() > 0 ? 10 : 0)
                + (MaterialSyncClient.lastClearedPieces() > 0
                        && System.currentTimeMillis() - MaterialSyncClient.lastValidateMs() < 12_000L ? 10 : 0)
                + (!resultNote.isEmpty() && resultUntil > System.currentTimeMillis() ? 10 : 0)
                + (resultUntil > System.currentTimeMillis() ? 12 + resultMissing.size() * 10 : 0)
                + 10 + (bar[1] > 0 ? 6 : 0);
        g.fill(x - 4, y - 4, x + w, y + h, 0xB0101418);
        g.drawString(mc.font, line1, x, y, c1.getColor());
        g.drawString(mc.font, line2, x, y + 10, c2.getColor());
        int yy = y + 20;
        // r16：建造那一行紧跟在按键行下面（空闲时就是提示，跑起来时是「建造进度 x/y」）
        ChatFormatting c3 = switch (BuildBridge.hudState()) {
            case BuildBridge.ST_RUNNING -> ChatFormatting.GREEN;
            case BuildBridge.ST_PAUSED -> ChatFormatting.YELLOW;
            case BuildBridge.ST_ERROR -> ChatFormatting.RED;
            case BuildBridge.ST_DONE -> ChatFormatting.AQUA;
            case BuildBridge.ST_CANCELLED -> ChatFormatting.GRAY;
            default -> ChatFormatting.DARK_GRAY;
        };
        g.drawString(mc.font, clip(line3, 46), x, yy, c3.getColor());
        yy += 10;
        if (bar[1] > 0) {
            int barW = w - 4;
            int filled = (int) (barW * (double) Math.min(bar[0], bar[1]) / bar[1]);
            g.fill(x, yy, x + barW, yy + 3, 0xFF20242A);
            g.fill(x, yy, x + filled, yy + 3,
                    bar[0] >= bar[1] ? 0xFF60E080 : 0xFFE0C040);
            yy += 6;
        }
        if (running) {
            int barW = w - 4;
            int filled = progTotal <= 0 ? 0 : (int) (barW * (double) progDone / progTotal);
            g.fill(x, yy, x + barW, yy + 3, 0xFF20242A);
            g.fill(x, yy, x + filled, yy + 3, 0xFF40C8E0);
            yy += 6;
            if (progSkipped > 0) {
                g.drawString(mc.font, Txt.s("hud.inv_skipped", progSkipped), x, yy,
                        ChatFormatting.RED.getColor());
                yy += 10;
            }
        }
        if (markCount >= 0) {
            g.drawString(mc.font, Txt.s("hud.marks_count", markCount, SR.MAX_CONTAINERS), x, yy,
                    markCount == 0 ? ChatFormatting.RED.getColor() : ChatFormatting.DARK_AQUA.getColor());
            yy += 10;
        }
        if (outputCount >= 0) {
            // r7：输出箱是「只进不出」的溢出箱，没标就是合成超发时只能退回背包/挂账
            g.drawString(mc.font, outputCount == 0
                            ? Txt.s("hud.outputs_none", outputCount, SR.MAX_OUTPUTS)
                            : Txt.s("hud.outputs", outputCount, SR.MAX_OUTPUTS),
                    x, yy, outputCount == 0 ? ChatFormatting.GRAY.getColor() : ChatFormatting.GOLD.getColor());
            yy += 10;
        }
        if (MaterialSyncClient.ledgerTypes() > 0) {
            String s = MaterialSyncClient.disabled()
                    ? Txt.s("hud.ledger_off")
                    : Txt.s("hud.ledger", MaterialSyncClient.ledgerTypes(),
                            MaterialSyncClient.ledgerItems());
            g.drawString(mc.font, s, x, yy,
                    MaterialSyncClient.disabled() ? ChatFormatting.RED.getColor() : ChatFormatting.GREEN.getColor());
            yy += 10;
        }
        // r8 Bug②：刚做过动态校验（拿走了东西、缺口涨回来）就在 HUD 上说明 12 秒，
        // 否则玩家只看到缺口数字往上涨，会以为模组抽风。
        long sinceValidate = System.currentTimeMillis() - MaterialSyncClient.lastValidateMs();
        if (MaterialSyncClient.lastClearedPieces() > 0 && sinceValidate < 12_000L) {
            String s = Txt.s("hud.validate", MaterialSyncClient.lastClearedPieces());
            g.drawString(mc.font, clip(s, 44), x, yy, ChatFormatting.LIGHT_PURPLE.getColor());
            yy += 10;
        }
        if (noopMsgUntil > System.currentTimeMillis()) {
            g.drawString(mc.font, noopMsg, x, yy, ChatFormatting.GRAY.getColor());
            yy += 10;
        }
        if (resultUntil > System.currentTimeMillis()) {
            g.drawString(mc.font, resultLine, x, yy, ChatFormatting.WHITE.getColor());
            yy += 10;
            if (!resultNote.isEmpty()) {
                g.drawString(mc.font, clip(resultNote, 44), x, yy, ChatFormatting.DARK_GRAY.getColor());
                yy += 10;
            }
            int shown = 0;
            for (String line : resultMissing) {
                if (shown++ >= 6) { g.drawString(mc.font, "  …", x, yy, ChatFormatting.GRAY.getColor()); break; }
                g.drawString(mc.font, line, x, yy, ChatFormatting.GRAY.getColor());
                yy += 10;
            }
        }
    }

    /** HUD 行宽有限，长文案截断。 */
    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 青蓝描边闪 0.3 秒。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        long now = System.currentTimeMillis();
        flashes.removeIf(f -> f[3] < now);
        if (flashes.isEmpty() || e.getCamera() == null) return;

        Vec3 cam = e.getCamera().getPosition();
        var pose = e.getPoseStack();
        pose.pushPose();
        var buffer = Minecraft.getInstance().renderBuffers().bufferSource()
                .getBuffer(RenderType.LINES);
        float t = 1f;
        for (long[] f : flashes) {
            double left = (f[3] - now) / (double) SR.FLASH_MS;
            float a = 0.9f * (float) Mth.clamp(left, 0, 1) * t;
            AABB box = new AABB(f[0] - cam.x, f[1] - cam.y, f[2] - cam.z,
                    f[0] + 1 - cam.x, f[1] + 1 - cam.y, f[2] + 1 - cam.z);
            LevelRenderer.renderLineBox(pose, buffer, box, 0.25f, 0.85f, 1.0f, a);
        }
        pose.popPose();
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch(RenderType.LINES);
    }

    // ---------------- /matfill 自测命令（纯客户端） ----------------

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent e) {
        var root = net.minecraft.commands.Commands.literal("matfill");

        root.then(net.minecraft.commands.Commands.literal("simulate")
                .executes(ctx -> {
                    RestockBridge.setSimulated(true);
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.matfill.simulate"), false);
                    return 1;
                }));
        root.then(net.minecraft.commands.Commands.literal("real")
                .executes(ctx -> {
                    RestockBridge.setSimulated(false);
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.matfill.real"), false);
                    return 1;
                }));
        root.then(net.minecraft.commands.Commands.literal("status")
                .executes(ctx -> {
                    // r28：命令反馈走 translatable；括号里的 describe() 一族是诊断自由文本，保持原样
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.matfill.bridge",
                            RestockBridge.status(), RestockBridge.diagnostics(),
                            RestockBridge.isSimulated()), false);
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.matfill.keys",
                            KEY_TAKEOVER.getTranslatedKeyMessage().getString(),
                            TakeoverKeyHandler.cancelAvailable() ? Txt.s("cmd.matfill.cancel_event")
                                                                 : Txt.s("cmd.matfill.cancel_swallow"),
                            TakeoverKeyHandler.swallowDegraded() ? " " + Txt.s("cmd.matfill.degraded") : "",
                            CuriosBridge.loaded() ? Txt.s("cmd.matfill.curios_yes",
                                    CuriosBridge.describeLastFailure()) : Txt.s("cmd.matfill.curios_no")), false);
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.matfill.sync",
                            MaterialSyncClient.describe()), false);
                    ctx.getSource().sendSuccess(() -> Txt.c(markCount < 0 ? "cmd.matfill.marks_pending"
                                    : "cmd.matfill.marks",
                            Math.max(markCount, 0), SR.MAX_CONTAINERS,
                            Math.max(outputCount, 0), SR.MAX_OUTPUTS), false);
                    // r16：建造这一档的状态另起两行（客户端一份、服务端一份，方便对照）
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.matfill.build",
                            BuildBridge.describe()), false);
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.matfill.build_server",
                            AutoBuildEngine.describe(
                                    Minecraft.getInstance().player == null
                                            ? null : Minecraft.getInstance().player.getUUID()),
                            (int) ModConfig.buildDistance(), ModConfig.buildBlocksPerTick()), false);
                    return 1;
                }));
        root.then(net.minecraft.commands.Commands.literal("clearmarks")
                .executes(ctx -> {
                    PacketDistributor.sendToServer(new Packets.ClearMarks());
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.matfill.clearmarks"), false);
                    return 1;
                }));
        root.then(net.minecraft.commands.Commands.literal("unsync")
                .executes(ctx -> {
                    String s = MaterialSyncClient.restore();
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.matfill.unsync", s), false);
                    return 1;
                }));

        // ---- r16：/matfill build … 自动建造的开关与手动触发 ----
        var build = net.minecraft.commands.Commands.literal("build")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.matfill.build", BuildBridge.describe()), false);
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.matfill.build_help"), false);
                    return 1;
                });
        build.then(net.minecraft.commands.Commands.literal("start")
                .executes(ctx -> {
                    BuildBridge.toggle(Minecraft.getInstance());
                    return 1;
                }));
        build.then(net.minecraft.commands.Commands.literal("cancel")
                .executes(ctx -> {
                    BuildBridge.cancel(Minecraft.getInstance());
                    return 1;
                }));
        var replace = net.minecraft.commands.Commands.literal("replace")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.matfill.policy_now",
                            BuildBridge.policyName()), false);
                    return 1;
                });
        replace.then(net.minecraft.commands.Commands.literal("air")
                .executes(ctx -> setPolicy(ctx.getSource(), 0)));
        replace.then(net.minecraft.commands.Commands.literal("soft")
                .executes(ctx -> setPolicy(ctx.getSource(), 1)));
        replace.then(net.minecraft.commands.Commands.literal("all")
                .executes(ctx -> setPolicy(ctx.getSource(), 2)));
        build.then(replace);
        var fluids = net.minecraft.commands.Commands.literal("fluids")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Txt.c("cmd.matfill.fluids_now",
                            BuildBridge.placeFluids() ? Txt.s("cmd.matfill.fluids_on_desc")
                                                      : Txt.s("cmd.matfill.fluids_off_desc")), false);
                    return 1;
                });
        fluids.then(net.minecraft.commands.Commands.literal("on")
                .executes(ctx -> setFluids(ctx.getSource(), true)));
        fluids.then(net.minecraft.commands.Commands.literal("off")
                .executes(ctx -> setFluids(ctx.getSource(), false)));
        build.then(fluids);
        root.then(build);

        e.getDispatcher().register(root);
    }

    /** {@code /matfill build replace <air|soft|all>}：改覆盖策略（下一份计划生效）。 */
    private static int setPolicy(net.minecraft.commands.CommandSourceStack src, int p) {
        BuildBridge.setReplacePolicy(p);
        boolean live = src.getEntity() != null && BuildBridge.isBusy();
        src.sendSuccess(() -> Txt.c("cmd.matfill.policy_set", BuildBridge.policyName(),
                live ? Txt.s("cmd.matfill.policy_next_old") : Txt.s("cmd.matfill.policy_next")), false);
        return 1;
    }

    /** {@code /matfill build fluids <on|off>}。 */
    private static int setFluids(net.minecraft.commands.CommandSourceStack src, boolean on) {
        BuildBridge.setPlaceFluids(on);
        src.sendSuccess(() -> Txt.c(on ? "cmd.matfill.fluids_set_on" : "cmd.matfill.fluids_set_off")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }
}
