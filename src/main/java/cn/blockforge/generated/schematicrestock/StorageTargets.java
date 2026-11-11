package cn.blockforge.generated.schematicrestock;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerOffhandInvWrapper;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * 存放处解析（任务 2）：给定一堆想放下的物品，按固定优先级找出<b>真实可用</b>的容器，
 * 全程只做模拟（simulate=true），拿到确认后才由调用方执行真实插入。
 *
 * <p>优先级（严格按策划顺序，命中即返回，低级绝不允许掩盖高级）：</p>
 * <ol>
 *   <li>A 主手 / 副手里的容器物品（精妙背包、潜影盒一类）；</li>
 *   <li>B Curios 饰品栏里的容器物品（软依赖，没装 Curios 直接跳过）。
 *       "back" 槽位优先，其余饰品槽的容器排在其后（有的整合包把背包登记成别的槽名）；</li>
 *   <li>C 玩家主物品栏里的潜影盒/背包（1.21.1 里容器内容存在 {@code minecraft:container}
 *       数据组件，NeoForge 已为全部潜影盒注册物品内 IItemHandler，等价于旧版读
 *       BlockEntityTag.Items 的 Items 列表）；</li>
 *   <li>D 玩家主物品栏本身。r11 起内部再排序：<b>主栏 9-35 先填满，快捷栏 0-8 垫底</b>
 *       （原 PlayerMainInvWrapper 从第 0 格开始扫，材料一多就把快捷栏塞满）。</li>
 * </ol>
 *
 * <p>为什么用 IItemHandler 而不是直接塞 ItemStack：容器的模拟插入会遵守
 * {@code isItemValid}（精妙背包的过滤升级会拒收），也不会把东西掉到地上。</p>
 *
 * <p><b>r11 Bug1</b>：用户反馈"装备了精妙背包，材料却进了快捷栏"。两条改进——
 * ① B 级（饰品栏）拿不到内部 IItemHandler 时不再静默跳过，扫描结论会写进幽灵日志；
 * ② 带 {@code stageTag} 的调用（SIM 模拟阶段）会打一行「存放处扫描」，
 * 逐级写明 A/B/C/D 各自过没过、为什么。</p>
 *
 * <p><b>r13 A 级加固</b>（用户反馈 r12 起"手持精妙背包/潜影盒收不到材料"；经逐行比对，
 * r12 没有改动本文件，A 级代码与 r11 字节级一致，因此这里做的是<b>只增不改</b>的韧性加固，
 * r11 能正常工作的判定路径一律保留）：</p>
 * <ol>
 *   <li><b>三连取能力</b>：{@code stack.getCapability(Capabilities.ItemHandler.ITEM)} →
 *       拿不到（null / 0 槽）再走 {@code ItemCapability} 直查通道（与 ItemStack 缓存路径
 *       不是同一条代码）→ 还不行且物品带 {@code minecraft:container} 组件（潜影盒一类），
 *       就用 {@link ComponentBackedHandler} 直接读写组件兜底。任何一步抛异常都只跳过本格，
 *       绝不把异常抛穿出去拖垮整次扫描；</li>
 *   <li><b>容量试探隔离</b>：旧版 roomOf 不在 try/catch 里，容器 handler 的 insertItem 一抛
 *       异常整次 find 直接崩（调用方接不住就会把成品退到别的处置链，症状正是"进不了包"）。
 *       现在 A/B/C 每级各自吞异常；</li>
 *   <li><b>容器词根放宽</b>：只放宽 isContainerItem 的 ID 启发式（还要求真拿得到 handler），
 *       命中不了能力照旧跳过，不改变任何已能工作物品的行为。</li>
 * </ol>
 */
public final class StorageTargets {

    /** 一个可用的存放处。handler 是对真实容器的活引用，commit 时直接写进去。 */
    public record Target(IItemHandler handler, String label, int room) {}

    private StorageTargets() {}

    /** 找第一个至少能放下 1 件 {@code probe} 的去处（静默版，COMMIT 路径用）。 */
    public static Target find(Player player, ItemStack probe) {
        return find(player, probe, null);
    }

    /**
     * 找第一个至少能放下 1 件 {@code probe} 的去处。
     *
     * @param stageTag 调用方所处阶段（"SIM" / "搬运SIM" / "投放COMMIT" / "余料COMMIT" /
     *                 "挂账COMMIT" / "回笼COMMIT" / "收尾COMMIT" ...）：
     *                 调试日志开着时，会在"可疑结局"（无处可放 / 落到主物品栏）时
     *                 写一行逐级剖析的「存放处扫描」，同一种结论一个会话只打一次。
     *                 r13 起 COMMIT 路径也带标签 —— SIM 说能进包、COMMIT 却落主物品栏的
     *                 "不一致"从此在日志里左右两行直接对得上。
     */
    public static Target find(Player player, ItemStack probe, String stageTag) {
        if (player == null || probe == null || probe.isEmpty()) return null;

        StringBuilder why = (stageTag != null && GhostDebug.on()) ? new StringBuilder(160) : null;

        // --- 优先级 A：主手 -> 副手里的容器 ---
        Target t = fromHeldContainer(player.getMainHandItem(), probe, "主手容器", why);
        if (t == null) t = fromHeldContainer(player.getOffhandItem(), probe, "副手容器", why);

        // --- 优先级 B：Curios 饰品栏的容器（软依赖；back 槽优先） ---
        if (t == null) t = curiosContainers(player, probe, why);

        // --- 优先级 C：主物品栏里的潜影盒 / 背包 ---
        if (t == null) t = carriedContainers(player, probe, why);

        // --- 优先级 D：主物品栏本身（r11：主栏 9-35 先填，快捷栏垫底） ---
        if (t == null) t = mainSlotsTarget(player, probe, why);

        if (why != null) reportScan(stageTag, probe, t, why.toString());
        return t;
    }

    /** 玩家主物品栏（36 格）的 handler，诊断和兜底用（原生顺序，快捷栏在前）。 */
    public static IItemHandler mainInventory(Player player) {
        return new PlayerMainInvWrapper(player.getInventory());
    }

    /**
     * 「玩家身上总共有几件」的深口径（r21 Bug1，服务端建任务前用来扣缺口）：
     * <ul>
     *   <li>主物品栏 36 格（含快捷栏）＋ 副手；</li>
     *   <li>这些格子里凡是装着东西的<b>容器物品</b>（潜影盒、木桶、装了料的箱子方块……），
     *       打开再数一层：优先读 {@code minecraft:container} 组件，读不到才问
     *       {@link Capabilities.ItemHandler#ITEM} 能力（精妙背包这类不写组件的走这条路）；
     *       两条路<b>二选一</b>，绝不重复数；</li>
     *   <li>Curios 饰品栏挂着的容器（背挂里的精妙背包）内部也数进去——软依赖，没装就是空。</li>
     * </ul>
     *
     * <p>嵌套深度封顶 3 层、单容器格子封顶 1024：再深的套娃宁可少数也不拖服务端。
     * 全程吞异常：容器读不出来就数到外层为止，绝不因此炸会话。</p>
     *
     * <p>与客户端 {@code MaterialSyncClient.countCarriedEverywhere} 口径一致：
     * 按<b>物品</b>匹配（同 id 即算），不比组件——投影缺口本来就是按物品记的。</p>
     */
    public static int countCarried(Player player, ItemStack probe) {
        if (player == null || probe == null || probe.isEmpty()) return 0;
        Item target = probe.getItem();
        int n = 0;
        try {
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                n += countInStack(inv.getItem(i), target, 0);
            }
            n += countInStack(player.getOffhandItem(), target, 0);
            for (CuriosBridge.Hit hit : CuriosBridge.curioContainers(player)) {
                IItemHandler back = hit == null ? null : hit.handler();
                if (back == null) continue;
                int cap = Math.min(back.getSlots(), 1024);
                for (int s = 0; s < cap; s++) {
                    n += countInStack(back.getStackInSlot(s), target, 1);
                }
            }
        } catch (Throwable ignored) {
            // 读不动就只报已经数到的，缺口最多少扣几件，不会错拿别人的量
        }
        return n;
    }

    /** 数一个堆：本身算 target 的件数；是容器就再往里数（深度/格子双封顶）。 */
    private static int countInStack(ItemStack st, Item target, int depth) {
        if (st == null || st.isEmpty()) return 0;
        int n = st.is(target) ? st.getCount() : 0;
        if (depth >= 3 || !isContainerItem(st)) return n;
        try {
            ItemContainerContents c = st.get(DataComponents.CONTAINER);
            if (c != null) {                                   // 原版路线：内容就在组件里
                for (ItemStack inner : c.nonEmptyItems()) n += countInStack(inner, target, depth + 1);
                return n;
            }
            IItemHandler inner = st.getCapability(Capabilities.ItemHandler.ITEM);   // 模组背包路线
            if (inner != null) {
                int cap = Math.min(inner.getSlots(), 1024);
                for (int s = 0; s < cap; s++) n += countInStack(inner.getStackInSlot(s), target, depth + 1);
            }
        } catch (Throwable ignored) {
            // 这一格数不出来：外层那件本身已经入账，里面的当没有
        }
        return n;
    }

    /**
     * 模拟插入，返回这个容器还能收几件（0 表示放不下，包括过滤升级拒收的情况）。
     * 用 copy() 是因为部分实现的 insertItem 会动到传入的堆。
     */
    public static int roomOf(IItemHandler handler, ItemStack probe) {
        if (handler == null || handler.getSlots() <= 0 || probe == null || probe.isEmpty()) return 0;
        ItemStack left = ItemHandlerHelper.insertItem(handler, probe.copy(), true);
        return probe.getCount() - left.getCount();
    }

    /** 真实插入，返回没塞进去的剩余（正常应为空堆）。 */
    public static ItemStack commit(Target target, ItemStack stack) {
        return ItemHandlerHelper.insertItem(target.handler(), stack.copy(), false);
    }

    // ==================================================================
    // r16 自动建造：从玩家身上<b>取</b>方块物品
    // ==================================================================
    //
    // 这一路与上面「投」的那一源出同源、方向相反，但<b>代码是另写一份的</b>：
    // 用户明确要求 r13 修好的 A 级（手持容器收料）链路不许再动一行，
    // 所以这里不复用 fromHeldContainer()，而是新开 openContainerHandler()。
    // 顺位保持一致：主手容器 → 副手容器 → Curios 饰品容器 → 随身潜影盒/背包 → 主物品栏 → 副手整件。

    /** 强容器词根：走到"验不了货"这一步时，只有这些名字的容器才宁可不用（防把满包当方块放出去）。 */
    private static final String[] STRONG_CONTAINER_ROOTS = {"shulker_box", "backpack", "bundle", "knapsack"};

    /**
     * 这个物品堆<b>有没有可能装着别的东西</b>（潜影盒、精妙背包、装了东西的木箱…）。
     *
     * <p>判定（保守优先，宁可这块料不用，也绝不把玩家的家当砌进墙里）：
     * <ol>
     *   <li>带 {@code minecraft:container} 组件且组件非空 → <b>装着东西</b>；</li>
     *   <li>拿得到内部 IItemHandler → 逐格看，有一格非空就是<b>装着东西</b>，全空就是干净容器（可用）；</li>
     *   <li>没有组件也没有 handler，但 ID 里有 {@code shulker_box}/{@code backpack} 这类强容器词根
     *       → 内容看不见，<b>当它装着东西</b>（模组背包自己存数据的就归这一档）；</li>
     *   <li>其余（名字里带 chest/box 的普通方块，例如箱子本体、唱片机）→ 可用。</li>
     * </ol>
     */
    public static boolean holdsForeignItems(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        // ① 数据组件路线：原版潜影盒/木桶/箱子都用它存内容
        try {
            ItemContainerContents c = stack.get(DataComponents.CONTAINER);
            if (c != null) {
                if (!ItemContainerContents.EMPTY.equals(c)) return true;   // 组件里真有东西
                return false;                                              // 组件在、但是空的 → 干净容器
            }
        } catch (Throwable ignored) {}
        // ② 能力路线：直接数内部格子（精妙背包这类不写 CONTAINER 组件的走这里）
        boolean inspected = false;
        try {
            IItemHandler inner = stack.getCapability(Capabilities.ItemHandler.ITEM);
            if (inner == null) inner = Capabilities.ItemHandler.ITEM.getCapability(stack, null);
            if (inner != null && inner.getSlots() > 0) {
                inspected = true;
                int cap = Math.min(inner.getSlots(), 1024);
                for (int i = 0; i < cap; i++) {
                    ItemStack st = inner.getStackInSlot(i);
                    if (st != null && !st.isEmpty()) return true;
                }
            }
        } catch (Throwable ignored) {
            inspected = false;
        }
        if (inspected) return false;                       // 看得见的空容器：可以用
        // ③ 看不见的：只有强容器词根才拦，别把「ender_chest / jukebox」这类误伤
        String path;
        try {
            path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        } catch (Throwable ignored) {
            return true;                                   // 连 ID 都拿不到：不敢用
        }
        if (path == null) return true;
        for (String root : STRONG_CONTAINER_ROOTS) {
            if (path.contains(root)) return true;
        }
        return false;
    }

    /**
     * 取料来源表（每个建造会话一份，每 tick 刷一次）。
     *
     * <p>为什么不缓存跨 tick：玩家随时可能换手拿包、摘掉饰品；每 tick 重连一遍
     * 来源（十来个 handler、几十次 {@code getStackInSlot}）比出错便宜得多。</p>
     */
    public static final class Feeder {
        private final List<IItemHandler> handlers = new ArrayList<>(8);
        private final List<String> labels = new ArrayList<>(8);
        private final PlayerMainInvWrapper mainWrapper;
        private Player owner;
        private long stamp = Long.MIN_VALUE;
        private int lastScanSlots;
        private int lastSkippedFilled;

        public Feeder(Player player) {
            mainWrapper = new PlayerMainInvWrapper(player.getInventory());
        }

        /** 每 tick 开工前刷一次来源表（手持物可能换了、饰品可能摘了）。 */
        public void refresh(Player player, long tick) {
            if (player != owner || tick != stamp) {
                owner = player;
                stamp = tick;
                handlers.clear();
                labels.clear();
                addSource(player.getMainHandItem(), "主手容器");
                addSource(player.getOffhandItem(), "副手容器");
                if (CuriosBridge.loaded()) {
                    for (CuriosBridge.Hit h : CuriosBridge.curioContainers(player)) {
                        if (h.handler() != null) { handlers.add(h.handler()); labels.add(h.label()); }
                    }
                }
                // 主物品栏里揣着的潜影盒/背包（C 级）
                int containers = 0;
                for (int i = 0; i < mainWrapper.getSlots() && containers < 16; i++) {
                    ItemStack st;
                    try {
                        st = mainWrapper.getStackInSlot(i);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (st.isEmpty() || !isContainerItem(st)) continue;
                    containers++;
                    addSource(st, "随身容器(第" + i + "格)");
                }
                // 主物品栏本身（D 级，主栏先/快捷栏垫底）+ 副手那一整件
                // （PlayerMainInvWrapper 只包 0-35 格，副手在 40 格，得单独给）
                handlers.add(new BackFirstMainInv(player.getInventory()));
                labels.add("主物品栏");
                handlers.add(new PlayerOffhandInvWrapper(player.getInventory()));
                labels.add("副手");
            }
        }

        private void addSource(ItemStack holder, String label) {
            if (holder == null || holder.isEmpty() || !isContainerItem(holder)) return;
            IItemHandler inner = openContainerHandler(holder);
            if (inner == null) return;
            // 去重：同一个 handler 排两次会重复取料
            for (IItemHandler h : handlers) if (h == inner) return;
            handlers.add(inner);
            labels.add(label);
        }

        /** 上一次 {@link #take} 检视了多少个槽位（诊断用）。 */
        public int lastScanSlots() { return lastScanSlots; }

        /** 上一次 {@link #take} 因为「装着东西」跳过的候选堆数（诊断用）。 */
        public int lastSkippedFilled() { return lastSkippedFilled; }

        /**
         * 从玩家身上取 {@code n} 件 {@code want}。
         *
         * <p>硬规矩两条：① 候选堆必须就是这个物品（只比 item，不比组件——和 r9 之后
         * 「组件差异只记日志不误伤」同一口径）；② <b>装着别的东西的容器堆一律跳过</b>
         * （{@link #holdsForeignItems}）——宁可这块料不用，也不能把玩家的满背包当方块砌上墙。</p>
         *
         * <p>顺序就是投放顺序的镜像：主手容器 → 副手容器 → 饰品容器 → 随身潜影盒 → 主物品栏
         * （主栏先、快捷栏垫底）。每格一次 {@code getStackInSlot} + 一次 item 比较，
         * 千格大背包也就微秒级，真正的兜底是引擎那边的时间闸门。</p>
         *
         * @param slotCap 本轮最多检视多少个槽位（防极端容器；超了就按「取到多少算多少」回）
         * @return 实际取到的件数（0 = 身上确实没有）
         */
        public int take(Item want, int n, int slotCap) {
            lastScanSlots = 0;
            lastSkippedFilled = 0;
            if (want == null || n <= 0 || handlers.isEmpty()) return 0;
            int need = n;

            for (int s = 0; s < handlers.size() && need > 0; s++) {
                IItemHandler h = handlers.get(s);
                int slots;
                try {
                    slots = h.getSlots();
                } catch (Throwable t) {
                    continue;
                }
                for (int i = 0; i < slots && need > 0; i++) {
                    if (++lastScanSlots > slotCap) return n - need;
                    ItemStack st;
                    try {
                        st = h.getStackInSlot(i);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (st.isEmpty() || !st.is(want)) continue;
                    if (holdsForeignItems(st)) { lastSkippedFilled++; continue; }
                    ItemStack pulled;
                    try {
                        pulled = h.extractItem(i, need, false);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (pulled.isEmpty() || !pulled.is(want)) continue;
                    need -= pulled.getCount();
                }
            }
            return n - need;
        }


        /** 来源表快照（诊断/日志）。 */
        public String describe() {
            StringBuilder sb = new StringBuilder(64);
            for (int i = 0; i < labels.size(); i++) {
                if (i > 0) sb.append('>');
                sb.append(labels.get(i));
                if (i >= 7) { sb.append("…"); break; }
            }
            return sb.length() == 0 ? "（身上一个可用存放处都没有）" : sb.toString();
        }
    }

    /**
     * 只干一件事：想办法拿到这个容器堆内部的 IItemHandler（三连取，任何一步抛异常都只当没有）。
     *
     * <p>和 A 级投放那三条路线<b>同思路、不同代码</b>，为的是投放链路一行不动。</p>
     */
    private static IItemHandler openContainerHandler(ItemStack holder) {
        if (holder == null || holder.isEmpty()) return null;
        try {
            IItemHandler h = holder.getCapability(Capabilities.ItemHandler.ITEM);
            if (h != null && h.getSlots() > 0) return h;
        } catch (Throwable ignored) {}
        try {
            IItemHandler h = Capabilities.ItemHandler.ITEM.getCapability(holder, null);
            if (h != null && h.getSlots() > 0) return h;
        } catch (Throwable ignored) {}
        return ComponentBackedHandler.tryOpen(holder);
    }


    /** 背包彻底放不下时给玩家看的说法。 */
    public static String reasonNone(Player player, ItemStack probe) {
        if (player == null || probe == null || probe.isEmpty()) return "无物可放";
        if (CuriosBridge.loaded()) return "背包/手持容器/饰品栏背包全部已满（饰品栏近况: "
                + CuriosBridge.describeLastScan() + "）";
        return "背包与手持容器已满（未装 Curios，已跳过饰品栏）";
    }

    // ---------------- 内部 ----------------

    /** 追加一段剖析文本（why 为 null 时什么都不做）。 */
    private static void append(StringBuilder why, String s) {
        if (why == null) return;
        if (why.length() > 0) why.append(" ; ");
        why.append(s);
    }

    /**
     * 如果 {@code holder} 是容器物品（精妙背包/潜影盒），拿它内部的 handler 模拟一次。
     * 每一道关卡（空/非容器/能力拿不到/满）都单独记入 why，绝不混成一句"跳过"。
     *
     * <p>r13：能力三连取（无上下文 → 带玩家上下文 → 数据组件自研 handler），任何一步的
     * 异常与 null 都只让本候选失效，不会抛穿 find()。</p>
     */
    private static Target fromHeldContainer(ItemStack holder, ItemStack probe, String label,
                                            StringBuilder why) {
        if (holder == null || holder.isEmpty()) { append(why, label + "=空手/空副手"); return null; }
        if (!isContainerItem(holder)) { append(why, label + "=非容器(" + path(holder) + ")"); return null; }

        IItemHandler inner = null;
        String route = null;
        // 路线 1：标准物品能力（r10/r11/r12 一直在用的原路径，原样保留）
        try {
            inner = holder.getCapability(Capabilities.ItemHandler.ITEM);
        } catch (Throwable t) {
            append(why, label + "(" + path(holder) + ")能力一抛" + t.getClass().getSimpleName());
            inner = null;
        }
        if (inner != null && inner.getSlots() > 0) route = "能力";
        // 路线 2：NeoForge 官方兜底口 —— ItemCapability.get(stack, context)。
        // 1.21.1 上 ITEM 能力的 context 形参是 Void，只能传 null；留着这一路是因为它走的是
        // 能力注册的直查通道，与 ItemStack.getCapability 的缓存路径不是同一条代码，
        // 个别模组在缓存失效时会在这里给出 handler。
        if (inner == null || inner.getSlots() <= 0) {
            try {
                IItemHandler alt = Capabilities.ItemHandler.ITEM.getCapability(holder, null);
                if (alt != null && alt.getSlots() > 0) { inner = alt; route = "能力(直查)"; }
            } catch (Throwable t) {
                append(why, label + "(" + path(holder) + ")能力二抛" + t.getClass().getSimpleName());
            }
        }
        // 路线 3：潜影盒这类内容存在 minecraft:container 组件里的容器，能力路线全断时直接读写组件
        if (inner == null || inner.getSlots() <= 0) {
            ComponentBackedHandler comp = ComponentBackedHandler.tryOpen(holder);
            if (comp != null) { inner = comp; route = "组件"; }
        }
        if (inner == null || inner.getSlots() <= 0) {
            append(why, label + "(" + path(holder) + ")是容器但三条路线都拿不到内部IItemHandler");
            return null;
        }

        int room;
        try {
            room = roomOf(inner, probe);
        } catch (Throwable t) {
            append(why, label + "(" + path(holder) + ")容量试探抛" + t.getClass().getSimpleName());
            return null;
        }
        if (room <= 0) { append(why, label + "(" + path(holder) + ")已满或拒收"); return null; }
        append(why, label + "(" + path(holder) + ")可放" + room + "件[" + route + "]");
        return new Target(inner, label, room);
    }

    /**
     * B 级：Curios 饰品栏。back 槽容器优先，其它饰品槽（有些包把背包登记成别的名字）其次。
     * 拿不到东西时 CuriosBridge 会把近况写进 {@code describeLastScan()}，这里转抄进剖析。
     */
    private static Target curiosContainers(Player player, ItemStack probe, StringBuilder why) {
        if (!CuriosBridge.loaded()) { append(why, "B 跳过(未装Curios)"); return null; }
        List<CuriosBridge.Hit> hits = CuriosBridge.curioContainers(player);
        append(why, "B 饰品栏[" + CuriosBridge.describeLastScan() + "]");
        for (CuriosBridge.Hit h : hits) {
            int room;
            try {
                room = roomOf(h.handler(), probe);
            } catch (Throwable t) {
                append(why, "B " + h.label() + "容量试探抛" + t.getClass().getSimpleName());
                continue;
            }
            if (room > 0) {
                Target t = new Target(h.handler(), h.label(), room);
                append(why, "B 命中" + h.label() + "可放" + room + "件");
                return t;
            }
        }
        if (!hits.isEmpty()) append(why, "B 的饰品容器都放不下" + path(probe));
        return null;
    }

    /** C 级：主物品栏 36 格里揣着的潜影盒/背包。 */
    private static Target carriedContainers(Player player, ItemStack probe, StringBuilder why) {
        IItemHandler main = new PlayerMainInvWrapper(player.getInventory());
        int containers = 0;
        for (int i = 0; i < main.getSlots(); i++) {
            ItemStack st;
            try {
                st = main.getStackInSlot(i);
            } catch (Throwable t) {
                continue;
            }
            if (st.isEmpty() || !isContainerItem(st)) continue;
            containers++;
            Target in = fromHeldContainer(st, probe, "随身潜影盒/背包(第" + i + "格)", null);
            if (in != null) { append(why, "C 命中" + in.label() + "可放" + in.room() + "件"); return in; }
        }
        append(why, containers == 0 ? "C 主物品栏没有容器物品" : "C 的" + containers + "个随身容器都放不下");
        return null;
    }

    /** D 级：主物品栏本身。r11 起先填主栏 9-35，快捷栏 0-8 垫底。 */
    private static Target mainSlotsTarget(Player player, ItemStack probe, StringBuilder why) {
        IItemHandler backFirst = new BackFirstMainInv(player.getInventory());
        int room = roomOf(backFirst, probe);
        append(why, "D 主物品栏(主栏先/快捷栏垫底)可放" + room + "件");
        return room > 0 ? new Target(backFirst, "主物品栏", room) : null;
    }

    /**
     * 「存放处扫描」日志：只在可疑结局时打印 —— 无处可放，或者装了这个东西本该没落到的
     * 主物品栏。同一种结论（同一阶段、同一落点、同一物品）一个接管会话只打一次，
     * 会话开始时 {@link GhostDebug#clearOnce()} 会清空记录。
     */
    private static void reportScan(String stage, ItemStack probe, Target t, String why) {
        try {
            boolean suspicious = t == null || "主物品栏".equals(t.label());
            if (!suspicious) return;
            String outcome = t == null ? "无处可放" : t.label();
            if (!GhostDebug.once("storage:" + stage + ":" + outcome + ":" + path(probe))) return;
            GhostDebug.line("      存放处扫描[" + stage + "] " + path(probe) + " x" + probe.getCount()
                    + " -> 「" + outcome + "」: " + why);
        } catch (Throwable ignored) {}
    }

    private static String path(ItemStack st) {
        try {
            return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 常见容器物品的 ID 词根（命中任何一条只表示"值得探一次能力"，真把关还在拿 handler）。 */
    private static final String[] CONTAINER_ROOTS = {
            "shulker_box", "backpack", "pack", "bag", "sack", "satchel", "pouch",
            "case", "basket", "box", "chest", "container"
    };

    /**
     * 判断一个物品堆是不是「能装东西的容器」。三种线索任一命中即可：
     * 带 {@code minecraft:container} 组件（潜影盒与多数模组箱袋）、
     * 物品 ID 以 shulker_box 结尾、物品 ID 含常见容器词根（r13 放宽：backpack 之外
     * 再认 pack/bag/sack/pouch/case/basket/box/chest/container，覆盖 back_pack、
     * knapsack 这类改名）。真正的把关还在下一步：三条路线都拿不到 handler 就当不是容器。
     */
    public static boolean isContainerItem(ItemStack st) {
        if (st == null || st.isEmpty()) return false;
        try {
            if (st.has(DataComponents.CONTAINER)) return true;
        } catch (Throwable ignored) {}
        String path;
        try {
            path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        } catch (Throwable ignored) {
            return false;
        }
        if (path == null) return false;
        if (path.endsWith("shulker_box")) return true;
        for (String root : CONTAINER_ROOTS) {
            if (path.contains(root)) return true;
        }
        return false;
    }

    /**
     * D 级包装（r11）：同一份主物品栏 36 格，但按「主栏 9-35 在前、快捷栏 0-8 垫底」暴露。
     *
     * <p>{@link ItemHandlerHelper#insertItem} 是从第 0 格顺序扫的，所以只要按这个
     * 呈现顺序装，材料就会先进主栏、后落快捷栏 —— 用户投诉的"塞满快捷栏"正是旧版
     * 直接拿 {@link PlayerMainInvWrapper} 从第 0 格扫造成的。</p>
     */
    private static final class BackFirstMainInv implements IItemHandler {
        private final PlayerMainInvWrapper inner;
        private final int[] order;

        BackFirstMainInv(Inventory inv) {
            inner = new PlayerMainInvWrapper(inv);
            int n = inner.getSlots();
            order = new int[n];
            int k = 0;
            for (int i = 9; i < n; i++) order[k++] = i;   // 主栏优先
            for (int i = 0; i < 9 && k < n; i++) order[k++] = i; // 快捷栏垫底
        }

        private int map(int slot) {
            return (slot >= 0 && slot < order.length) ? order[slot] : slot;
        }

        @Override public int getSlots() { return inner.getSlots(); }
        @Override public ItemStack getStackInSlot(int slot) { return inner.getStackInSlot(map(slot)); }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return inner.insertItem(map(slot), stack, simulate);
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return inner.extractItem(map(slot), amount, simulate);
        }
        @Override public int getSlotLimit(int slot) { return inner.getSlotLimit(map(slot)); }
        @Override public boolean isItemValid(int slot, ItemStack stack) {
            return inner.isItemValid(map(slot), stack);
        }
    }

    /**
     * 组件路线的迷你 handler（r13 兜底）：内容就存在 {@code minecraft:container} 数据组件里的
     * 容器（潜影盒及一切用该组件的模组容器），在 NeoForge 的物品能力路线整体失灵时
     * （注册缺失 / 返回 null / 抛异常）直接读写组件本身，A/C 级照样进得了包。
     *
     * <p>只在能力一、二都拿不到时启用，绝不覆盖正常 handler；全程防御式写法，
     * 打开失败（无组件/结构异常）返回 null 让调用方按"不是容器"处理。</p>
     */
    private static final class ComponentBackedHandler implements IItemHandler {
        private static final int SLOTS = 27;   // ItemContainerContents 的容量上限

        private final ItemStack holder;
        private final NonNullList<ItemStack> slots;

        private ComponentBackedHandler(ItemStack holder, NonNullList<ItemStack> slots) {
            this.holder = holder;
            this.slots = slots;
        }

        static ComponentBackedHandler tryOpen(ItemStack holder) {
            try {
                if (holder == null || holder.isEmpty()) return null;
                ItemContainerContents contents = holder.get(DataComponents.CONTAINER);
                if (contents == null) return null;
                NonNullList<ItemStack> list = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
                contents.copyInto(list);
                return new ComponentBackedHandler(holder, list);
            } catch (Throwable t) {
                return null;
            }
        }

        private void writeBack() {
            holder.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
        }

        @Override public int getSlots() { return SLOTS; }

        @Override public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= SLOTS) return ItemStack.EMPTY;
            return slots.get(slot);
        }

        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
            if (slot < 0 || slot >= SLOTS) return stack;
            if (!isItemValid(slot, stack)) return stack;
            ItemStack cur = slots.get(slot);
            int limit = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
            ItemStack rest = stack.copy();
            if (cur.isEmpty()) {
                int take = Math.min(rest.getCount(), limit);
                if (take <= 0) return rest;
                ItemStack moved = rest.split(take);
                if (!simulate) {
                    slots.set(slot, moved);
                    writeBack();
                }
                return rest;
            }
            if (ItemStack.isSameItemSameComponents(cur, stack)) {
                int space = limit - cur.getCount();
                if (space > 0) {
                    int take = Math.min(rest.getCount(), space);
                    rest.shrink(take);
                    if (!simulate) {
                        cur.grow(take);
                        writeBack();
                    }
                    return rest;
                }
            }
            return rest;
        }

        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (amount <= 0 || slot < 0 || slot >= SLOTS) return ItemStack.EMPTY;
            ItemStack cur = slots.get(slot);
            if (cur.isEmpty()) return ItemStack.EMPTY;
            int n = Math.min(amount, cur.getCount());
            ItemStack out = cur.copyWithCount(n);
            if (!simulate) {
                cur.shrink(n);
                if (cur.isEmpty()) slots.set(slot, ItemStack.EMPTY);
                writeBack();
            }
            return out;
        }

        @Override public int getSlotLimit(int slot) { return 64; }

        @Override public boolean isItemValid(int slot, ItemStack stack) {
            // 原版规则：潜影盒不能套潜影盒（防无限嵌套）
            if (stack == null || stack.isEmpty()) return false;
            return !(isShulker(holder) && isShulker(stack));
        }

        private static boolean isShulker(ItemStack st) {
            try {
                return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().endsWith("shulker_box");
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
