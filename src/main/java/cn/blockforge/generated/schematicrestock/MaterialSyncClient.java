package cn.blockforge.generated.schematicrestock;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务 1：让 Forgematica（投影）的 L 键材料列表与材料 HUD 同步扣减「我们已投进背包的数量」。
 *
 * <h3>为什么数字不会自己降（源码实锤）</h3>
 * <p>{@code scheduler/tasks/TaskCountBlocksPlacement.java} 的 {@code countAtPosition()}
 * 是<b>逐格比对「原理图世界」与「客户端世界」</b>：那一格还是空气或方块不对就
 * {@code countsMissing + 1}。它跟玩家身上有几个材料毫无关系。唯一读物品的地方是
 * {@code materials/MaterialListUtils.java#updateAvailableCounts()}，它只写
 * {@code countAvailable}，而且只数主物品栏 36 格——我们的东西在精妙背包/潜影盒里，数不到。
 * 所以 L 键列表（{@code gui/GuiMaterialList} → {@code WidgetMaterialListEntry} 第 223/275 行
 * 显示的就是 {@code entry.getCountMissing()}）和 HUD（{@code MaterialListHudRenderer} 第 127 行）
 * 都照旧显示全缺。</p>
 *
 * <h3>做法：缺口账本 + 实时校验 + 保留条目对象引用</h3>
 * <ol>
 *   <li><b>缺口账本</b>：服务端每真实投进存放处一批，就按品种累计下发
 *       （{@code TakeoverProgress.placedSnapshot} / {@code TakeoverResult.placed}）。
 *       客户端按会话号增量合并进账本，<b>绝不扫背包</b>（用户明确要求，避开性能与状态冲突）。</li>
 *   <li><b>写回</b>：只用投影的<b>公开 API</b>——用 {@code MaterialListEntry} 的公开五参构造器
 *       造出只改 missing 的新条目，再调 {@code MaterialListBase.setMaterialListEntries(List)}。
 *       它顺带 {@code refreshPreFilteredList()} + {@code updateCounts()} 重算总数，
 *       并回调 {@code completionListener.onTaskCompleted()}，正开着的 L 键界面当场重建行；
 *       HUD 每 2 秒读同一份 {@code getMaterialsAll()}，跟着变。
 *       {@code countMissing} 是 {@code private final}，因此<b>不做 final 字段反射</b>，没有 JIT 内联隐患。</li>
 *   <li><b>保留条目对象引用</b>：我们造出来的条目连同「扣减前的原始缺口」记在
 *       {@link IdentityHashMap} 里。下一轮：认得的对象且数值仍等于我们写下去的值 → 用原始值当基准
 *       （不会二次扣）；认得但数值被外部改过 → 认现值为新基准（不跟投影争）；
 *       不认得的对象 → 投影重算出来的新条目，现值就是基准。投影重算、玩家手动补方块、换原理图，都自然收敛。</li>
 *   <li><b>实时校验</b>：每个品种的扣减量 {@code min(该条目基准缺口, 账本余量)}，永不扣成负数；
 *       同一件物品跨多份列表按顺序贪心分配，不会重复扣。</li>
 * </ol>
 *
 * <h3>容错与节流（用户硬要求）</h3>
 * <p>{@link #tick()} 整体 {@code catch (Throwable)}：抛异常（映射名不同、构造器签名变了、
 * 列表对象结构不对……）就 {@code disabled = true} 永久<b>静默</b>关闭，只在 {@code /matfill status}
 * 的诊断行里留一句话，绝不抛进主循环、绝不刷屏。节流 {@code SR.SYNC_THROTTLE_MS}（500ms）一次，
 * 且只在数值真的需要变化时才写回（幂等，不空转打扰投影）。</p>
 */
public final class MaterialSyncClient {

    /** 我们写下去的条目：基准缺口（扣减前）+ 当时扣掉的件数。 */
    private record Baseline(int originalMissing, int applied) {}

    /** 条目对象身份 -> 基准。认得 = 我们写的；不认得 = 投影的新对象。 */
    private static final Map<Object, Baseline> patched = new IdentityHashMap<>();

    /** 品种 ID -> 累计「真实投进存放处」件数（跨会话累计：东西一直在背包里）。 */
    private static final Map<String, Integer> credit = new HashMap<>();

    /**
     * r8 Bug②：上一次校验时看到的<b>世界原始缺口</b>（品种 -> 未扣账本的 baseline 合计）。
     *
     * <p>玩家把投进背包的材料<b>砌到墙上</b>时，那些件数也不在背包里了——这跟"拿走了"是两回事。
     * 两次校验之间原始缺口掉了 D 件，说明有 D 件是被用掉的，这部分账本<b>不能清</b>，
     * 否则界面会把已经砌好的方块又算成缺料，下一趟 V+M 就重复搬货。</p>
     */
    private static final Map<String, Integer> rawMissingSeen = new HashMap<>();

    /** r8 Bug②：最近一次动态校验清掉的件数与品种数（HUD / {@code /matfill status} 播报用）。 */
    private static int lastClearedPieces;
    private static int lastClearedKinds;
    private static long lastValidateMs;
    /** r8 Bug②：最近一次校验的人话结论，没清过时为空串（V+M 发包时在聊天栏播一行）。 */
    private static String lastNote = "";

    /** 当前会话号 + 本会话每种已见过的累计值，用来把服务端累计增量并入总账本。 */
    private static long sessionKey;
    private static final Map<String, Integer> sessionSeen = new HashMap<>();

    /**
     * r16：自动建造<b>反向</b>扣账本。建造计划号 + 每种物品已经扣过的累计件数。
     *
     * <p>服务端进度包里的 {@code consumed} 是<b>本会话累计</b>消耗件数，
     * 与 {@code placedSnapshot} 同一套增量口径：只把「比上次多出来的那部分」从账本里扣掉，
     * 计划号一变就换基线，重复包 / 漏包都不会把账扣乱。</p>
     */
    private static long buildPlanKey;
    private static final Map<String, Integer> buildSeen = new HashMap<>();
    /** r16：本份建造计划累计从账本里扣掉的件数 / 种数（{@code /matfill status} 用）。 */
    private static int buildDeductedPieces;
    private static int buildDeductedTypes;

    private static boolean disabled;      // 抛过异常就永久静默关闭
    private static boolean everRan;       // 至少成功跑过一轮
    private static long lastSyncMs;
    private static String diag = "未开始";
    private static int lastChanged;       // 上次写回改动的条目数

    private MaterialSyncClient() {}

    // ---------------- 对外：账本喂数 ----------------

    /**
     * 服务端投料账本到达（会话内累计值）。
     *
     * @param key      会话号；换号说明是新会话，本会话累计基线清零，但总账本保留
     * @param snapshot 每品种「本会话真实投进存放处的累计件数」
     */
    public static void onPlacedSnapshot(long key, List<Packets.Entry> snapshot) {
        if (disabled || snapshot == null) return;
        try {
            if (key != sessionKey) {
                sessionKey = key;
                sessionSeen.clear();
            }
            for (Packets.Entry e : snapshot) {
                if (e == null || e.id() == null || e.count() < 0) continue;
                int prev = sessionSeen.getOrDefault(e.id(), 0);
                if (e.count() > prev) credit.merge(e.id(), e.count() - prev, Integer::sum);
                if (e.count() >= prev) sessionSeen.put(e.id(), e.count());
            }
            diag = "账本 " + credit.size() + " 种 / " + ledgerItems() + " 件";
        } catch (Throwable t) {
            shutDown(t);
        }
    }

    /**
     * r16（用户要点第七）：自动建造<b>吃掉</b>的料，要从投料账本里<b>倒扣</b>回去。
     *
     * <p>账本记的是「本模组真实投进玩家存放处、而投影 L 键还不知道的件数」。
     * 建房子把这些件数从背包搬进了<b>墙里</b>，它们就不再是「躺在身上待用」的料了：</p>
     * <ul>
     *   <li>不扣：投影重算后世界原始缺口降了 {@code N}，账本还记着 {@code N}，
     *       显示缺口 = 原始 − 账本 会<b>凭空少一截</b>，下一次 V+M 就直接少补这么多料；</li>
     *   <li>扣了：原始缺口降 {@code N}（世界多墙了 {@code N} 块）、账本也降 {@code N}（身上少了 {@code N} 件），
     *       两边正好对消——显示缺口保持不变，正是「料从背包进了墙、需求总量没变」的真值。</li>
     * </ul>
     *
     * <p>口径：服务端进度包带的是<b>本计划累计</b>消耗件数，这里按 {@link #buildSeen}
     * 取增量再扣，重复包不会扣两次，计划号一变自动换基线。扣到 0 就抹掉这一种，
     * 绝不留负数（玩家自己带来的料不在账本上，扣不动是正常的）。</p>
     */
    public static void onBuildConsumed(long planKey, List<Packets.Entry> consumed) {
        if (disabled || consumed == null || consumed.isEmpty()) return;
        try {
            if (planKey != buildPlanKey) {
                buildPlanKey = planKey;
                buildSeen.clear();
                buildDeductedPieces = 0;
                buildDeductedTypes = 0;
            }
            for (Packets.Entry e : consumed) {
                if (e == null || e.id() == null || e.count() < 0) continue;
                int prev = buildSeen.getOrDefault(e.id(), 0);
                if (e.count() <= prev) continue;
                int delta = e.count() - prev;
                buildSeen.put(e.id(), e.count());
                Integer booked = credit.get(e.id());
                if (booked == null) continue;                       // 玩家自带的料：账上本来就没记
                int cut = Math.min(booked, delta);
                if (cut <= 0) continue;
                int left = booked - cut;
                if (left <= 0) credit.remove(e.id());
                else credit.put(e.id(), left);
                buildDeductedPieces += cut;
                buildDeductedTypes = credit.size();
            }
            diag = "账本 " + credit.size() + " 种 / " + ledgerItems() + " 件（建造已倒扣 "
                    + buildDeductedPieces + " 件）";
        } catch (Throwable t) {
            shutDown(t);
        }
    }

    /** r16：本份建造计划已经从账本里倒扣的件数。 */
    public static int buildDeductedPieces() { return buildDeductedPieces; }

    /** r16：建造已经倒扣的件数 / 种数（种数是扣完之后的账面剩余种类，仅诊断用）。 */
    public static String buildDeductedText() {
        return buildDeductedPieces <= 0 ? ""
                : "建造已倒扣 " + buildDeductedPieces + " 件";
    }

    /**
     * r8 Bug②：<b>会话启动前的强制动态校验</b> —— 不许只信静态的投料账本。
     *
     * <p>现场问题：模组把材料投进背包（L 键缺口照此扣减），玩家随后把那几件拿走
     * （塞回箱子、扔掉、给别人），再按 V+M 时账本仍记着「已经投过了」，于是缺口显示为 0，
     * 模组认为不缺料、一件都不去拿。</p>
     *
     * <p>口径（用户定的）：<b>有效缺口 = 当前世界原始缺口 − 账本里还真实存在的件数</b>。
     * 本方法逐品种拿背包实况去核账，核不上的一部分当场清零，让缺口自己涨回来：</p>
     * <ul>
     *   <li>{@code booked} = 账本记了几件；{@code real} = 现在背包里（含随身容器内部）真有几件；
     *       {@code placed} = 自上次校验以来世界原始缺口降了几件（＝被砌上墙了）。</li>
     *   <li>允许保留的额度 = {@code min(booked, real + placed)}：拿走了就掉，砌上墙的不冤枉。</li>
     *   <li>背包里一件都找不到 → 这个品种的 {@code credit} 直接清零（正是用户要的行为）。</li>
     * </ul>
     *
     * <p>容错：整段 {@code catch (Throwable)}，读不到玩家背包或容器就<b>什么都不改</b>
     * （宁可不扣也不能冤枉玩家），绝不让 V+M 因为校验而失灵；也不会因此熔断投影同步
     * （熔断只留给投影 API 本身出问题）。</p>
     *
     * @return 本次清掉的件数（0 = 账本与背包实况对得上，没动过）
     */
    public static int validateCredits() {
        if (disabled || credit.isEmpty()) return 0;
        try {
            Map<String, Integer> real = countCarriedEverywhere();
            rawReadFailed = false;
            Map<String, Integer> raw = rawMissingByItem();
            // 原始缺口读不到（没开投影清单、反射失败）：这趟<b>什么都不清</b>。
            // 分不清"玩家拿走了"和"已经砌上墙"的时候，宁可下一趟再补，也不要冤枉玩家、重复搬货。
            if (rawReadFailed || raw.isEmpty()) {
                diag = rawReadFailed ? "动态校验跳过：原始缺口读不到" : "动态校验跳过：没有生效的原理图清单";
                lastClearedPieces = 0;
                lastClearedKinds = 0;
                return 0;
            }

            int clearedPieces = 0, clearedKinds = 0, keptPlaced = 0;
            List<String> gone = new ArrayList<>();
            for (Map.Entry<String, Integer> e : new ArrayList<>(credit.entrySet())) {
                String id = e.getKey();
                int booked = e.getValue();
                if (booked <= 0) { credit.remove(id); continue; }
                int have = real.getOrDefault(id, 0);
                // 两次校验之间世界原始缺口掉了几件 = 这几件被砌上墙了（不是被拿走了）
                int prev = rawMissingSeen.getOrDefault(id, -1);
                int now = raw.getOrDefault(id, 0);
                int placed = prev < 0 ? 0 : Math.max(0, prev - now);
                int allowed = Math.min(booked, have + placed);
                if (allowed > have) keptPlaced += allowed - have;
                if (allowed <= 0) {
                    credit.remove(id);
                    clearedPieces += booked;
                    clearedKinds++;
                    if (gone.size() < 8) gone.add(shortName(id) + "×" + booked);
                } else if (allowed < booked) {
                    credit.put(id, allowed);
                    clearedPieces += booked - allowed;
                    clearedKinds++;
                    if (gone.size() < 8) gone.add(shortName(id) + "×" + (booked - allowed));
                }
                rawMissingSeen.put(id, now);
            }
            for (Map.Entry<String, Integer> e : raw.entrySet()) {
                rawMissingSeen.putIfAbsent(e.getKey(), e.getValue());
            }
            lastClearedPieces = clearedPieces;
            lastClearedKinds = clearedKinds;
            if (clearedPieces > 0) {
                lastValidateMs = System.currentTimeMillis();
                diag = "动态校验：清掉 " + clearedPieces + " 件投料账本（" + clearedKinds + " 种）";
                // r28：这句要同时进 HUD/聊天栏（客户端类，直接走 translatable）
                lastNote = Txt.s("note.validated", clearedKinds, clearedPieces,
                        keptPlaced > 0 ? Txt.s("note.validated_kept", keptPlaced) : "",
                        String.join("、", gone));
            }
            return clearedPieces;
        } catch (Throwable t) {
            // 校验本身出任何问题都不改账、不熔断、不抛：V+M 照旧发起
            diag = "动态校验跳过：" + t.getClass().getSimpleName();
            return 0;
        }
    }

    /** 最近一次动态校验清掉的件数（0 = 没清过 / 账本本来就对得上）。 */
    public static int lastClearedPieces() { return lastClearedPieces; }

    /** r28：最近一次动态校验清掉的<b>品种数</b>（聊天栏那句本地化文案的第二个参数）。 */
    public static int lastClearedKinds() { return lastClearedKinds; }

    /**
     * r8 Bug②：把<b>当前世界原始缺口</b>记成校验基线（一次投料会话收尾时调一次）。
     *
     * <p>没有基线的那次校验只能当"没有历史"看：{@code placed} 恒为 0，于是「已经砌上墙」的件数
     * 会被当成「被拿走了」而清掉账本，下一趟重复搬货。会话结束时记一次锚点，
     * 之后玩家是砌墙还是拿走，下一次校验都分得清。</p>
     */
    public static void seedRawBaseline() {
        if (disabled) return;
        try {
            rawReadFailed = false;
            Map<String, Integer> raw = rawMissingByItem();
            if (rawReadFailed || raw.isEmpty()) return;
            for (Map.Entry<String, Integer> e : raw.entrySet()) rawMissingSeen.put(e.getKey(), e.getValue());
        } catch (Throwable ignored) {
            // 埋基线失败不影响玩法：最坏情况就是下一次校验少一点判别力
        }
    }

    /** 最近一次动态校验的时刻（0 = 从没校验过），HUD 决定那行显示多久。 */
    public static long lastValidateMs() { return lastValidateMs; }

    /** 最近一次动态校验的人话结论（没清过时为空串），V+M 发包时在聊天栏播一行。 */
    public static String lastValidateNote() { return lastNote; }

    /**
     * 立刻跑一轮写回（绕开 500ms 节流）。V+M 发包前必须先跑一次：
     * 校验把账本改小之后，投影列表得当场把缺口显示回来，界面和发包才是同一个数。
     *
     * @return 本轮改写的条目行数
     */
    public static int syncNow() {
        if (disabled) return 0;
        try {
            lastSyncMs = System.currentTimeMillis();
            runPass();
            everRan = true;
            return lastChanged;
        } catch (Throwable t) {
            shutDown(t);
            return 0;
        }
    }

    /**
     * r21 Bug1：V+M 发包用的<b>世界原始缺口</b>（按 baseline 口径当场算，<b>不扣投料账本</b>）。
     *
     * <p>为什么不再扣账本：账本记的件数此刻就躺在玩家背包里，服务端建任务时还要按
     * 背包实况再扣一次身上已有件数（{@code TakeoverEngine.Session}）——两头都扣就是
     * <b>双重扣减</b>，会凭空少搬一整批。取多少只由服务端照玩家身上的实况定一次；
     * 账本从此只管 L 键列表的显示。</p>
     *
     * <p>为什么不用 {@code RestockBridge.snapshot()} 现成的数：那份快照最长可能陈旧 400ms，
     * 这里直接按同一份 baseline 口径当场算，发的数是现读的。</p>
     *
     * @return 每种方块还差几件；原始缺口读不到时返回空表（调用方退回老口径）
     */
    public static List<Packets.Entry> rawShortfall() {
        shortfallTrusted = false;
        if (disabled) return List.of();
        try {
            rawReadFailed = false;
            Map<String, Integer> raw = rawMissingByItem();
            // 一份清单都没摸着（没加载原理图、/matfill simulate 自测、反射失败）也算不可信，
            // 让调用方退回快照口径，别把"读不到"当成"当真不缺料"。
            if (rawReadFailed || raw.isEmpty() || RestockBridge.liveLists().isEmpty()) return List.of();
            shortfallTrusted = true;   // 走到这里 = 原始缺口读通了（空表=当真一件都不缺）
            List<Packets.Entry> out = new ArrayList<>(raw.size());
            for (Map.Entry<String, Integer> e : raw.entrySet()) {
                if (e.getValue() > 0) out.add(new Packets.Entry(e.getKey(), e.getValue()));
            }
            return out;
        } catch (Throwable t) {
            shortfallTrusted = false;
            return List.of();
        }
    }

    /** 上一次 {@link #rawShortfall()} 是不是"可信的空"（true = 空表代表真的不缺料）。 */
    public static boolean shortfallTrusted() { return shortfallTrusted; }

    private static boolean shortfallTrusted;

    /** 背包实况：玩家主物品栏（含快捷栏、副手）+ 其中容器物品内部，按物品 id 汇总件数。 */
    private static Map<String, Integer> countCarriedEverywhere() {
        Map<String, Integer> out = new HashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return out;
        var inv = mc.player.getInventory();
        int slots = inv.getContainerSize();
        for (int i = 0; i < slots; i++) addCarried(out, inv.getItem(i), 1);
        // 我们投放时会用 Curios 饰品栏挂包（存放处优先级 B），核账时也得数进去，
        // 否则"东西还在背挂里"会被当成"已被拿走"，白白清掉账本、下一趟重复搬货。
        // curioContainers 自己保证不抛（没装 Curios 就是空表）。
        for (CuriosBridge.Hit hit : CuriosBridge.curioContainers(mc.player)) {
            try {
                net.neoforged.neoforge.items.IItemHandler back = hit.handler();
                if (back == null) continue;
                for (int s = 0; s < back.getSlots(); s++) addCarried(out, back.getStackInSlot(s), 2);
            } catch (Throwable ignored) {
                // 某个饰品容器在客户端给不出内容：数到外层为止，绝不因此改错账
            }
        }
        return out;
    }

    /**
     * 记一件物品堆：物品本身先入账；要是它是个容器（潜影盒/精妙背包），
     * 再往里数一层（深度封顶 3，防止嵌套容器把客户端拖住）。
     */
    private static void addCarried(Map<String, Integer> out, ItemStack st, int depth) {
        if (st == null || st.isEmpty()) return;
        out.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
        if (depth >= 3 || out.size() > 4096 || !StorageTargets.isContainerItem(st)) return;
        try {
            net.neoforged.neoforge.items.IItemHandler inner =
                    st.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ITEM);
            if (inner == null) return;
            for (int s = 0; s < inner.getSlots(); s++) addCarried(out, inner.getStackInSlot(s), depth + 1);
        } catch (Throwable ignored) {
            // 某个容器模组在客户端给不出 handler：数到外层为止就行，绝不因此改错账
        }
    }

    /**
     * 当前<b>世界原始缺口</b>（未扣我们账本的口径）：按品种把各条目的 baseline 加起来。
     *
     * <p>baseline 的取法与 {@link #syncOneList} 一致：认得的对象且数值仍等于我们写下去的值 →
     * 用原始值；其余（投影重算的新对象、或外部改过值）→ 认现值。</p>
     */
    private static Map<String, Integer> rawMissingByItem() {
        Map<String, Integer> out = new HashMap<>();
        try {
            for (Object list : RestockBridge.liveLists()) {
                // 倍率 != 1 的放置：投影那一列显示的是 multiplier × countTotal，我们的扣减不落进界面，
                // 拿它的 baseline 去算有效缺口会「显示的」和「发给服务端的」两个数打架。
                // 这种清单一律当"原始缺口不可信"：本轮既不校验也不改写，退回老口径（按快照发）。
                if (multiplier(list) != 1) {
                    rawReadFailed = true;
                    continue;
                }
                Object raw = RestockBridge.callAny(list, "getMaterialsAll", "getMaterials");
                if (!(raw instanceof List<?> entries) || entries.isEmpty()) continue;
                if (entries.size() > SR.SYNC_MAX_ENTRIES) {
                    // 反常大小的清单：syncOneList 也不会去动它，那这里的缺口数字同样不可信
                    rawReadFailed = true;
                    continue;
                }
                for (Object entry : entries) {
                    if (entry == null) continue;
                    String id = itemIdOf(entry);
                    if (id == null) continue;
                    int live = missingOf(entry);
                    Baseline b = patched.get(entry);
                    int baseline = (b != null && live == b.originalMissing() - b.applied())
                            ? b.originalMissing() : live;
                    out.merge(id, Math.max(0, baseline), Integer::sum);
                }
            }
        } catch (Throwable t) {
            // 读不到投影清单：返回已读到的部分（调用方按"原始缺口未知"处理，宁可不清账）
            rawReadFailed = true;
        }
        return out;
    }

    /** 上一次 {@link #rawMissingByItem()} 的原始缺口可不可信（异常/倍率清单 → 不可信）。 */
    private static boolean rawReadFailed;

    private static String shortName(String id) {
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    /** 客户端 tick 入口，内部 500ms 节流；任何异常都静默熔断。 */
    public static void tick() {
        if (disabled) return;
        long now = System.currentTimeMillis();
        if (now - lastSyncMs < SR.SYNC_THROTTLE_MS) return;
        lastSyncMs = now;
        try {
            runPass();
            everRan = true;
        } catch (Throwable t) {
            shutDown(t);
        }
    }

    /** /matfill unsync：还原成投影原始缺口并清空账本。@return 给用户看的一句话 */
    public static String restore() {
        if (disabled) return "同步已静默关闭，无需还原";
        try {
            int lists = 0;
            for (Object list : RestockBridge.liveLists()) {
                if (restoreList(list)) lists++;
            }
            credit.clear();
            sessionSeen.clear();
            patched.clear();
            rawMissingSeen.clear();      // r8：原始缺口基线也得跟着作废
            lastClearedPieces = 0;
            lastClearedKinds = 0;
            lastNote = "";
            lastChanged = 0;
            diag = "账本已清空";
            return "已还原 " + lists + " 份列表的缺口显示";
        } catch (Throwable t) {
            shutDown(t);
            return "还原失败，同步已静默关闭";
        }
    }

    /** 世界切换/退服：身份表里全是旧世界的对象，必须清。 */
    public static void resetForNewWorld() {
        patched.clear();
        credit.clear();
        sessionSeen.clear();
        buildSeen.clear();               // r16：建造扣账本的累计基线同理作废
        buildPlanKey = 0L;
        buildDeductedPieces = 0;
        buildDeductedTypes = 0;
        rawMissingSeen.clear();          // r8：换世界后原始缺口基线全是旧世界的数
        lastClearedPieces = 0;
        lastClearedKinds = 0;
        lastNote = "";
        lastChanged = 0;
        if (!disabled) diag = "等待缺料账本";
    }

    public static boolean disabled() { return disabled; }
    public static boolean everRan() { return everRan; }
    public static String diagnostics() { return diag; }
    public static int ledgerTypes() { return credit.size(); }
    public static int lastChangedEntries() { return lastChanged; }

    public static int ledgerItems() {
        int n = 0;
        for (int v : credit.values()) n += v;
        return n;
    }

    /** 诊断行（/matfill status）。 */
    public static String describe() {
        if (disabled) return "熔断：" + diag;
        String api = "未发现";
        try {
            List<Object> l = RestockBridge.liveLists();
            if (!l.isEmpty() && RestockBridge.findMethod(l.get(0).getClass(), "setMaterialListEntries", 1) != null) {
                api = "setMaterialListEntries";
            }
        } catch (Throwable ignored) {}
        String v = lastClearedPieces > 0
                ? "，动态校验已清 " + lastClearedPieces + " 件/" + lastClearedKinds + " 种（"
                        + ((System.currentTimeMillis() - lastValidateMs) / 1000L) + " 秒前）"
                : "，动态校验：账本与背包实况一致";
        return "账本API=" + api + ", 记账=" + credit.size() + "种/" + ledgerItems() + "件, 基准对象="
                + patched.size() + ", 上次改动=" + lastChanged + "行" + v + ", " + diag;
    }

    // ---------------- 主流程 ----------------

    private static void runPass() throws Exception {
        List<Object> lists = RestockBridge.liveLists();
        if (lists.isEmpty()) {
            patched.clear();
            return;
        }
        // r23：显示扣减池 = max(投料账本, 随身实物)。旧版只认账本——玩家自己塞进背包的料、
        // 或账本被动态校验误清过的料，L 键一律不认，出现「包里明明够了、清单仍显示全缺」。
        // 现在每趟现数一次随身实物（36 格 + 副手 + 容器内部，与动态校验同一支笔），
        // 背包里真有几件就按几件扣——显示口径与 V+M 的服务端口径（缺口−身上实物）彻底一致。
        // 注意这只改<b>显示</b>：发出去的仍是原始缺口（rawShortfall），绝不双重扣。
        Map<String, Integer> pool = new HashMap<>(credit);
        try {
            for (Map.Entry<String, Integer> c : countCarriedEverywhere().entrySet()) {
                int have = c.getValue() == null ? 0 : c.getValue();
                if (have > 0) pool.merge(c.getKey(), have, Math::max);
            }
        } catch (Throwable t) {
            // 数不了随身实物就退回纯账本口径，显示保守一点无妨，绝不让同步崩
            diag = "随身实物并入显示扣减失败（退回纯账本）：" + t.getClass().getSimpleName();
        }
        // 贪心分配，同一物品跨列表不重复扣
        // 基准表整轮共用一份：逐份列表各自 clear 会把前面那几份的原始缺口忘掉（多放置时必现）
        Map<Object, Baseline> next = new IdentityHashMap<>();
        int changed = 0;
        for (Object list : lists) changed += syncOneList(list, pool, next);
        patched.clear();
        patched.putAll(next);
        lastChanged = changed;
        if (changed > 0) {
            diag = "已同步 " + changed + " 行（账本 " + credit.size() + " 种 / " + ledgerItems() + " 件）";
        }
    }

    /**
     * @param next 整轮共享的基准表（本轮活着的、仍需记账的条目都往里放）
     * @return 这份列表实际改写的条目行数
     */
    private static int syncOneList(Object listObj, Map<String, Integer> pool,
                                   Map<Object, Baseline> next) throws Exception {
        Object raw = RestockBridge.callAny(listObj, "getMaterialsAll", "getMaterials");
        if (!(raw instanceof List<?> entries) || entries.isEmpty()) return 0;
        if (entries.size() > SR.SYNC_MAX_ENTRIES) return 0; // 反常大小的列表：不伺候

        // 投影在倍率 != 1 时那一列显示的是 multiplier * countTotal，扣 missing 不体现在界面上，
        // 这种情况干脆不动它的内存（白写还会引起界面重建）。
        if (multiplier(listObj) != 1) return 0;

        List<Object> out = new ArrayList<>(entries.size());
        int changed = 0;

        for (Object entry : entries) {
            if (entry == null) { out.add(entry); continue; }
            String id = itemIdOf(entry);
            Baseline b = patched.get(entry);
            int live = missingOf(entry);
            int baseline;
            if (b != null && live == b.originalMissing() - b.applied()) {
                baseline = b.originalMissing();     // 还是我们上次写下去的样子
            } else {
                baseline = live;                    // 投影重算的新对象，或外部改过值：认现值
            }

            int take = 0;
            if (id != null && baseline > 0) {
                int avail = pool.getOrDefault(id, 0);
                take = Math.min(baseline, avail);
                if (take > 0) pool.put(id, avail - take);
            }
            int want = Math.max(0, baseline - take);

            Object target = entry;
            if (want != live) {
                Object rebuilt = rebuildEntry(entry, want);
                if (rebuilt == null) {          // 造不出来就放弃这一行的扣减，绝不硬改
                    take = 0;
                } else {
                    target = rebuilt;
                    changed++;
                }
            }
            // 每条都记基准（take 可能是 0）：这样多份列表之间的贪心顺序就算变了也不会重复扣
            next.put(target, new Baseline(baseline, take));
            out.add(target);
        }

        if (changed <= 0) return 0;             // 没有任何数值变化：不写，不打扰投影
        RestockBridge.callOne(listObj, "setMaterialListEntries", out);
        return changed;
    }

    /** 还原一份列表到投影原始缺口（撤掉我们的账本影响）。@return 是否动过 */
    private static boolean restoreList(Object listObj) throws Exception {
        Object raw = RestockBridge.callAny(listObj, "getMaterialsAll", "getMaterials");
        if (!(raw instanceof List<?> entries) || entries.isEmpty()) return false;
        List<Object> out = new ArrayList<>(entries.size());
        boolean touched = false;
        for (Object entry : entries) {
            Baseline b = entry == null ? null : patched.get(entry);
            if (b != null && b.applied() > 0) {
                Object rebuilt = rebuildEntry(entry, b.originalMissing());
                if (rebuilt != null) { out.add(rebuilt); touched = true; continue; }
            }
            out.add(entry);
        }
        if (!touched) return false;
        RestockBridge.callOne(listObj, "setMaterialListEntries", out);
        return true;
    }

    // ---------------- 反射小件（只碰 public 成员；失败抛出，由 tick 静默熔断） ----------------

    private static int missingOf(Object entry) throws Exception {
        Object v = RestockBridge.callAny(entry, "getCountMissing");
        return v instanceof Number n ? Math.max(0, n.intValue()) : 0;
    }

    private static String itemIdOf(Object entry) {
        try {
            Object s = RestockBridge.callAny(entry, "getStack");
            if (!(s instanceof ItemStack st) || st.isEmpty()) return null;
            return BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int multiplier(Object listObj) {
        try {
            Object v = RestockBridge.callAny(listObj, "getMultiplier");
            return v instanceof Number n ? n.intValue() : 1;
        } catch (Throwable t) {
            return 1; // 老版本没这个方法：按 1 处理
        }
    }

    /**
     * 用投影的公开构造器 {@code MaterialListEntry(ItemStack, int total, int missing,
     * int mismatched, int available)} 造一条只改了 missing 的新条目。
     * 拿不到构造器就返回 null —— 调用方保留原条目，等于这一行不扣（宁可不显示，不可改坏）。
     */
    private static Object rebuildEntry(Object entry, int newMissing) {
        try {
            Object stack = RestockBridge.callAny(entry, "getStack");
            Object total = RestockBridge.callAny(entry, "getCountTotal");
            Object mismatched = RestockBridge.callAny(entry, "getCountMismatched");
            Object available = RestockBridge.callAny(entry, "getCountAvailable");
            if (!(stack instanceof ItemStack) || !(total instanceof Number)
                    || !(mismatched instanceof Number) || !(available instanceof Number)) return null;

            Constructor<?> ctor = findEntryCtor(entry.getClass());
            if (ctor == null) return null;
            return ctor.newInstance(stack, ((Number) total).intValue(), newMissing,
                    ((Number) mismatched).intValue(), ((Number) available).intValue());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Constructor<?> findEntryCtor(Class<?> c) {
        for (Constructor<?> k : c.getConstructors()) {
            Class<?>[] p = k.getParameterTypes();
            if (p.length == 5 && ItemStack.class.isAssignableFrom(p[0])
                    && p[1] == int.class && p[2] == int.class && p[3] == int.class && p[4] == int.class) {
                return k;
            }
        }
        return null;
    }

    /** 静默熔断：只留一行诊断，不抛、不刷屏、此后不再碰投影。 */
    private static void shutDown(Throwable t) {
        disabled = true;
        String msg = t.getMessage();
        diag = "同步已静默关闭: " + t.getClass().getSimpleName()
                + (msg == null || msg.isEmpty() ? "" : (" / " + clip(msg)));
        credit.clear();
        sessionSeen.clear();
        patched.clear();
        rawMissingSeen.clear();
        buildSeen.clear();               // r16
        buildPlanKey = 0L;
    }

    private static String clip(String s) {
        return s.length() <= 90 ? s : s.substring(0, 90) + "…";
    }
}
