package cn.blockforge.generated.schematicrestock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务 2：安全幽灵合成引擎（服务端）。
 *
 * <p><b>绝不开 GUI、绝不留实体</b>：产物是 {@code recipe.assemble()} 在 Java 内存里算出来的 ItemStack，
 * 原料只从标记过的箱子里经 {@link IItemHandler} 取，成品交给 {@link StorageTargets}
 * 的 A→B→C→D 优先级投进背包/精妙背包/潜影盒。</p>
 *
 * <h3>r14 大图纸重构（三件事，全在这一个文件里落地）</h3>
 * <ol>
 *   <li><b>查无配方立刻收手</b>：{@link #plan} 进门先查一次配方索引（一次哈希），
 *       非工作台/石切产物的东西（去皮原木这类要拿斧子加工的）直接判
 *       {@link Reason#NO_RECIPE} —— <b>不查库存、不展开递归、一微秒都不多花</b>。
 *       旧版顺序正好相反：先做最贵的全箱普查，再一头扎进递归，预算烧光才发现没人产它。</li>
 *   <li><b>取料走位置索引</b>（{@link ChestIndex}）：库存事实由宿主在会话开始时扫一次并全程自愈维护，
 *       本文件里再也没有「把所有标记箱的所有格子过一遍」这种循环。
 *       预算限的因此是「料格」而不是「全箱槽位」，{@code SIM/COMMIT} 各一份（r7 Bug④ 的教训保留）。</li>
 *   <li><b>改期封顶</b>：预算用完不再是无限 {@code RETRY}，由 {@link #deferStep} 数着次数，
 *       连续 {@code SR.MAX_STEP_RETRIES} 次过不去就判 {@link Reason#STUCK} 跳过这一种 ——
 *       <b>一种物品算不动，绝不允许拖死整场会话</b>（用户实测的"只搬了 4 种就卡死"正主）。</li>
 * </ol>
 *
 * <h3>规划（递归解析）</h3>
 * <p>可用量 = 索引现货 − 本轮已订走 − 别的任务按件锁定的量（r7 Bug①：不是整种一票否决）；
 * 然后递归建树：原料箱子里够 → 叶子（拿取）；不够但有配方且没超深度 → 递归进去合成；
 * 超深度、没配方 → 这个候选配方作废，换下一个；全部作废 → 该物品进失败清单。</p>
 *
 * <p><b>r12 断链修复</b>（保留）：一个配料格（尤其标签配料 {@code #c:planks}）不只定一个代表物品，
 * 而是排一份有序候选清单（箱里够 &gt; 有配方能往上合成，按原料现货与词根优选 &gt; 有货但不够 &gt; 其余），
 * 逐个试到能凑成为止；「箱里只有原木」时云杉木板的递归链由
 * {@code 尝试合成中间产物 X / 发现可用原料 Y} 两条日志全程留痕。</p>
 *
 * <h3>执行（一手合成的严格顺序，SIM 全绿才 COMMIT）</h3>
 * <ol>
 *   <li><b>SIM（零世界改动）</b>：这一手要用的每格原料都 {@code extractItem(slot, n, true)} 模拟取一遍，
 *       摆进虚拟 {@code CraftingInput}，用配方自己的 {@code matches()} 复核；
 *       {@code getRemainingItems()} 只要非空（会回吐桶/载体的配方）<b>直接判这个配方不安全</b>；
 *       再 {@code assemble()} 拿到内存产物；最后 {@link StorageTargets#find} 模拟存放也必须通过。</li>
 *   <li><b>COMMIT</b>：沿同一棵树重放真实动作，每次真实提取前再模拟复核一遍；
 *       结论和 SIM 不一致（同一 tick 内箱子被动过）立刻收手。已经到手的东西一律先进任务的幽灵缓冲，
 *       交付时走安全存放，塞不进存放处的交宿主 limbo，<b>全程不可能掉地上</b>。</li>
 * </ol>
 *
 * <h3>限流与防崩（硬约束）</h3>
 * <ul>
 *   <li>{@code SR.MAX_CRAFT_PER_TICK = 1}：每 tick 最多推进一次<b>面向玩家的产出</b>（根配方一手活）；
 *       链内前置合成跟着同一次调用提交，但受 {@code SR.MAX_CHAIN_ASSEMBLIES_PER_TICK} 上限。</li>
 *   <li>{@code SR.CRAFT_MAX_DEPTH} 递归层数、{@code SR.CRAFT_SIM_LOCS / CRAFT_COMMIT_LOCS}
 *       单手料格预算、{@code SR.MAX_STEP_RETRIES} 改期次数、会话 deadline 四重封顶。</li>
 *   <li>算不出的物品（没配方 / 凑不齐料 / 超深度 / 配方不安全 / 成品放不下 / 算力超限）
 *       <b>直接跳过，永不重试</b>。</li>
 * </ul>
 */
public final class GhostCraftEngine {

    private GhostCraftEngine() {}

    /** 同一条配方只详细看一次，避免每 tick 刷日志。 */
    private static final Set<ResourceLocation> FIRST_OK = new HashSet<>();

    /**
     * 产物 DataComponents 严格判死开关（<b>默认关</b>，r7 用户口径）。
     *
     * <p>关（默认）：候选配方<b>只比物品</b>；组件与目标不一致只是排到后面，
     * {@code assemble} 的产出组件与索引登记不一致也只是写日志，不改判。
     * 上一版开着严格比对，结果 {@code assemble} 出来带默认空组件的栈一律被杀，
     * 云杉台阶/栅栏全部「配方复核未通过」。</p>
     *
     * <p>开（{@code /ghostlog strict on}，只作调试）：候选筛选阶段就剔除组件不一致的配方，
     * 复核阶段组件不一致也直接判 UNSAFE_RECIPE。</p>
     */
    private static volatile boolean strictProductComponents = SR.GHOST_STRICT_PRODUCT_COMPONENTS;

    public static void setStrictProductComponents(boolean v) { strictProductComponents = v; }

    public static boolean strictProductComponents() { return strictProductComponents; }

    /** 宽松模式下「看见但放行了」的产物组件差异次数（{@code /ghostlog stats} 用）。 */
    private static volatile int componentDiffCount;

    public static int componentDiffsObserved() { return componentDiffCount; }

    /** 结果 / 失败原因。 */
    public enum Reason {
        NONE, NO_RECIPE, NO_MATERIAL, TOO_DEEP, UNSAFE_RECIPE, NO_SPACE, BUDGET, STUCK, DEADLINE, LIMIT,
        /** r22：物理复扫确认<b>箱里明明还有货</b>，但一件都取不出来（容器只读/瞬时拒取）。
         *  这跟「查无配方」是两码事，必须分开报——冒领 NO_RECIPE 会把玩家引去加工原料，
         *  而真相是那一箱根本掏不动。 */
        CHEST_LOCKED;

        public String label() {
            switch (this) {
                case NO_RECIPE: return "没有工作台/石切配方（需工具加工的方块，如去皮原木）";
                case CHEST_LOCKED: return "箱里有现货但容器取不出来（见幽灵日志的实扫行）";
                case NO_MATERIAL: return "箱子里凑不齐原料";
                case TOO_DEEP: return "合成链超过 " + ModConfig.craftMaxDepth() + " 层";
                case UNSAFE_RECIPE: return "配方复核未通过（matches 不过 / 有回吐物 / 产出对不上）";
                case NO_SPACE: return "成品放不下（背包与输出箱都满）";
                case BUDGET: return "本 tick 预算用完";
                case STUCK: return "这一种连续 " + SR.MAX_STEP_RETRIES + " 手都算不动，已跳过（不再占用会话时间）";
                case DEADLINE: return "接管会话超时";
                case LIMIT: return "已达单次会话总额度";
                default: return "ok";
            }
        }

        /** 预算用完之外都算「这物品到此为止」：记进失败清单后不再重试。 */
        public boolean fatal() { return this != NONE && this != BUDGET; }

        /**
         * r14 第 4 点：退出时把失败分成两大类报给玩家 ——
         * <b>「查无配方」</b>（去皮原木这类，原版根本不给这条路，再多料也没用）
         * 与<b>「材料不够」</b>（凑不齐原料 / 放不下 / 算力超限）。
         */
        public boolean noRecipe() { return this == NO_RECIPE; }
    }

    /** 宿主（TakeoverEngine 的会话）要提供的服务。 */
    public interface Host {
        ServerPlayer player();
        Level level();

        /**
         * 标记箱的<b>位置索引</b>（r14）：宿主在会话开始时建一次、阶段 B 开工前重建一次。
         *
         * <p>引擎从此不再自己扫箱阵 —— 这是打穿「每手重扫 96 箱 × 27 格」死循环的根本手段。
         * 取料、普查、日志复查三种需求全部走这一份索引，且每次真实提取前仍逐格实测复核。</p>
         */
        ChestIndex index();

        /**
         * 这个品种还被排在<b>别的任务</b>名下等着用的件数（直接搬运队列 + 已展开的合成计划）。
         *
         * <p>r7 Bug① 的口径保留：按件锁定，绝不整种一票否决。r14 阶段 B 里，
         * 一个计划在展开时会把它订走的原料件数记进宿主账本（{@code planBookings}），
         * 轮转到别的任务时就不会把同一批原木订两次。</p>
         */
        int reservedCount(Item item);

        /** 输出箱（溢出箱）坐标；<b>只进不出</b>，永远不在索引范围内，防自循环。 */
        List<BlockPos> outputs();

        /** 现取输出箱的 handler（被拆/换方块返回 null）。 */
        IItemHandler outputAt(BlockPos pos);

        /**
         * 本步剩余的<b>料格</b>检视预算；扣不动返回 false（本手改期，不算失败）。
         *
         * @param simulate true = SIM 阶段的独立预算，false = COMMIT 阶段的独立预算（r7 Bug④ 的教训保留）
         */
        boolean spendLocs(int n, boolean simulate);

        /** 每手推进开始时把某一阶段的预算重新装满。 */
        void resetLocBudget(boolean simulate);

        /** 会话总件数额度是否还有余量。 */
        boolean hasSessionRoom();

        /** 会话硬 deadline（绝对毫秒；0 = 不限）。 */
        long deadlineMs();

        /** 成品真实投进存放处后回调（计入写入数 + 缺口账本）。 */
        void onPlaced(ItemStack placed);

        /** 原料真实离开箱子后回调（计入原料消耗）。 */
        void onConsumed(ItemStack from);

        /** 无处安放的东西交给宿主挂账（limbo），绝不允许掉地上。 */
        void stash(ItemStack rest);

        /**
         * 余料/超发已经真实塞进某处之后的记账（<b>不</b>计入投料账本，因此不会去扣 L 键缺口）。
         *
         * @param placed 实际放进去的那几件
         * @param where  去了哪儿：{@code "output"} = 输出箱，{@code "inventory"} = 玩家背包，
         *               {@code "limbo"} = 挂账
         */
        void noteOverflow(ItemStack placed, String where);
    }

    /** 一步推进的结果。 */
    public record Step(Reason fail, int delivered, int consumed, boolean finished) {
        static final Step RETRY = new Step(Reason.BUDGET, 0, 0, false);
    }

    // ==================== 计划树 ====================

    private enum Kind { TAKE, MAKE }

    /** 抽平后的候选配方。 */
    private static final class Candidate {
        final RecipeHolder<?> holder;
        final boolean single;                  // 石切台一类单格配方（用 SingleRecipeInput）
        final ItemStack result;
        final NonNullList<Ingredient> ingredients;
        final int perCraft;

        Candidate(RecipeHolder<?> holder, boolean single, ItemStack result, NonNullList<Ingredient> ingredients) {
            this.holder = holder;
            this.single = single;
            this.result = result;
            this.ingredients = ingredients;
            this.perCraft = Math.max(1, result.getCount());
        }

        ResourceLocation id() { return holder.id(); }
    }

    private static final class Node {
        final Item item;
        final Kind kind;
        final Candidate candidate;                            // MAKE 用
        final List<Node> children = new ArrayList<>();        // MAKE：按 ingredient 去重后的原料子节点
        final List<Ingredient> childKeys = new ArrayList<>(); // 与 children 平行

        Node(Item item, Kind kind, Candidate candidate) {
            this.item = item;
            this.kind = kind;
            this.candidate = candidate;
        }
    }

    /** 一种目标物品的幽灵合成任务（跨 tick 保有自己的幽灵缓冲）。 */
    public static final class Job {
        private final String targetId;
        private final Node root;
        private int remaining;
        private final List<ItemStack> held = new ArrayList<>(); // 已真实到手、还没交付的东西
        private Reason fail;
        private boolean finished;
        private int delivered;
        private int consumed;
        /**
         * 同一手连续改期次数（r14 第 2 点：<b>死循环的总闸门</b>）。
         *
         * <p>旧版 SIM 预算用完就 {@code return Step.RETRY}，下一 tick 从零重扫同一手，
         * 没有上限 —— 大箱阵下单手扫描量天生超过预算，这一手永远过不去，只能撞超时。
         * 现在取料走位置索引，单手几乎不可能超预算；真超了（例如某种物品散在几千格里）
         * 就到 {@link SR#MAX_STEP_RETRIES} 判死跳过，转下一种，<b>永不无限重来</b>。</p>
         */
        private int stepRetries;
        /** 本计划在规划期订走的原料件数（交给宿主记账，轮转到别的任务时不重复订同一批货）。 */
        private Map<Item, Integer> booked;

        Job(String targetId, Node root, int remaining) {
            this.targetId = targetId;
            this.root = root;
            this.remaining = Math.max(0, remaining);
        }

        public int delivered() { return delivered; }
        public int consumedAmount() { return consumed; }
        public int remaining() { return remaining; }
        public boolean finished() { return finished; }
        public Reason failReason() { return fail; }
        public String targetId() { return targetId; }

        /** 这个任务在规划期订走了哪些原料各几件（宿主据此向 {@link Host#reservedCount(Item)} 记账）。 */
        public Map<Item, Integer> bookings() { return booked == null ? Map.of() : booked; }
    }

    // ==================== 入口 ====================

    /**
     * 为「还差 {@code amount} 件 {@code target}」规划一条合成链。
     *
     * <p><b>r22：这是阶段 B 专用入口。</b>阶段 A（纯搬运）从不查配方——箱里有现货就只许去拿，
     * 「查无配方」的判定只允许发生在阶段 B 门口（{@code TakeoverEngine.startCraftPhase} 的
     * {@link #hasAnyRecipe} 闸门）与这里的进门第一步。本方法返回的 NO_RECIPE 只影响合成队列。</p>
     *
     * @return 任务；{@code job.failReason() != null} 表示这物品直接判无法合成，调用方跳过、别重试
     */
    public static Job plan(Host host, String targetId, ItemStack target, int amount) {
        if (target == null || target.isEmpty() || amount <= 0) return null;
        Job bad = new Job(targetId, null, amount);
        ChestIndex idx = host.index();
        GhostDebug.line("══ 规划幽灵合成 " + targetId + " x" + amount + "（材料箱 "
                + idx.positions().size() + " 个，深度上限 " + ModConfig.craftMaxDepth()
                // r7 新增诊断项：输出箱有几个。溢出产物/中间产物的去处直接取决于这一位，
                // 显示 0 就意味着超发那几件只能退回背包或挂账。
                + "，输出箱 " + (host.outputs() == null ? 0 : host.outputs().size()) + " 个）");
        GhostDebug.line("      目标栈：" + GhostDebug.st(target));
        try {
            // ============================================================
            // r14 第 1 点：查无配方<b>立刻</b>判死，一秒钟的库存查询都不花。
            //
            // 去皮深色橡木原木这类东西根本不是工作台/石切台的产物（要玩家拿斧子右键去皮），
            // 配方索引里查不到它 —— 旧版却仍然先做最贵的一步（全箱普查），
            // 再一层层展开候选，把整份预算白白烧在它身上（用户日志里卡死的那位就是它）。
            // 这里只查一次哈希表，命中不了立刻返回 NO_RECIPE，让调度器转下一种。
            // 按物品查（不比 DataComponents）：组件严格档是 pick() 那一层的事，
            // 「压根没有配方」与「配方组件对不上」必须分开报，玩家看到的结论完全不同。
            // ============================================================
            List<Candidate> raw = rawRecipesFor(host, target.getItem());
            if (raw.isEmpty()) {
                bad.fail = Reason.NO_RECIPE;
                bad.finished = true;
                GhostDebug.line("      [NO_RECIPE] " + targetId
                        + "：配方索引里没有任何工作台/石切配方产出它 —— 这不是材料不够，"
                        + "是原版/模组根本没给这条路（去皮原木要拿斧子加工）。"
                        + "直接跳过，<b>不查库存、不展开递归、不消耗任何预算</b>");
                GhostDebug.detail("查无配方 " + targetId + "：无工作台/石切配方（如去皮原木这类需工具加工）");
                return bad;
            }

            // 库存一律读宿主共享的位置索引（会话开始时扫一次，阶段 B 开工前重建一次）。
            // r14 之前这里是 new Planner(host)：每规划一种就把整个箱阵重扫一遍。
            Trial t = new Trial(idx, host);
            Fail fail = new Fail();
            Set<Item> onPath = new HashSet<>();
            onPath.add(target.getItem());
            Node root = build(host, t, target.getItem(), amount, 0, onPath, fail, target);
            if (root == null || root.kind != Kind.MAKE) {
                bad.fail = root == null ? fail.get() : Reason.NO_RECIPE;
                if (bad.fail == Reason.BUDGET) bad.fail = Reason.NO_MATERIAL;
                bad.finished = true;
                GhostDebug.detail("规划失败 " + targetId + "：" + bad.fail.name()
                        + "（" + bad.fail.label() + "）");
                GhostDebug.line("      规划失败详情：索引完整=" + idx.complete
                        + "，索引里的品种数=" + idx.kinds()
                        + "，候选配方 " + raw.size() + " 条全作废"
                        + "，失败原因（本轮重探得出，不再被判死表缓存顶掉）=" + bad.fail.name()
                        + "，预订账=" + t.summary());
                return bad;
            }
            Job job = new Job(targetId, root, amount);
            // 规划期订走的原料交给宿主记账：轮转到别的任务时不会把同一批原木再订一遍
            job.booked = t.snapshotBookings();
            GhostDebug.line("      规划成功，采用配方链（共 " + raw.size() + " 条候选，用了第 "
                    + t.chosenIndex() + " 条）：\n" + tree(root, amount, 0));
            GhostDebug.line("      索引库存：" + idx.summary() + "；本任务订走原料 " + t.summary());
            return job;
        } catch (Throwable e) {
            // 以前这里静默贴 UNSAFE_RECIPE，什么线索都不留 —— 现在必须留下真因
            GhostDebug.throwAt("plan(" + targetId + ") 规划阶段", e);
            GhostDebug.detail("规划 " + targetId + " 时抛异常：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()));
            bad.fail = Reason.UNSAFE_RECIPE;
            bad.finished = true;
            return bad;
        }
    }

    /**
     * 规划期的失败原因回传盒。
     *
     * <p>r7 Bug③：旧版用一张 {@code Map<Item,Reason> dead}「判死表」，而且<b>整棵递归树共用一张</b>：
     * 只要某个中间产物在某条候选配方上凑不齐（那是「按这条配方、这个数量」才成立的结论），
     * 它就被永久记进表里，同一层后面的候选配方、乃至后面的目标物品一问到它就直接吃闭门羹
     * （{@code if (dead.containsKey(want)) return null;}）。
     * 用户日志里「木棍被判死 → 栅栏/楼梯全灭」正是这条路径。
     * 现在原因只用于<b>向上传递与写日志</b>，不再作为跨分支的短路缓存；
     * 防环仍然靠 {@code onPath}（同一条链上的重入）与深度上限，两者足够。</p>
     */
    private static final class Fail {
        private Reason reason = Reason.NO_RECIPE;
        private int depth;      // 记录最深那次失败，便于日志说明「在哪一层断的」

        void set(Reason r, int depth) {
            this.reason = r;
            this.depth = depth;
        }

        Reason get() { return reason; }

        int depth() { return depth; }
    }

    /** 计划树的可读打印（排障用）。 */
    private static String tree(Node n, int amount, int indent) {
        if (n == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indent; i++) sb.append("  ");
        sb.append("· ").append(item(n.item)).append(" x").append(amount)
                .append(n.kind == Kind.TAKE ? "  ← 直接从标记箱拿" : "  ← 合成，配方=" + ghost(n.candidate));
        sb.append('\n');
        if (n.kind == Kind.MAKE && n.candidate != null) {
            for (int i = 0; i < n.children.size(); i++) {
                Ingredient key = i < n.childKeys.size() ? n.childKeys.get(i) : null;
                int per = countPerCraft(n.candidate, key);
                sb.append(tree(n.children.get(i), per * craftsFor(n.candidate, amount), indent + 1));
            }
        }
        return sb.toString();
    }

    /** 这一格配料在配方里出现几次（计划树打印用）。 */
    private static int countPerCraft(Candidate c, Ingredient key) {
        if (c == null || key == null) return 1;
        int n = 0;
        try {
            for (Ingredient g : c.ingredients) if (g == key || safeEquals(g, key)) n++;
        } catch (Throwable ignored) {}
        return Math.max(1, n);
    }

    private static int craftsFor(Candidate c, int amount) {
        return c == null ? 1 : Math.max(1, (amount + c.perCraft - 1) / c.perCraft);
    }

    /**
     * 候选自检：把每条候选配方用<b>它自己的配料样品</b>摆一遍，只看 {@code matches} 认不认。
     *
     * <p>这一步不代表真能合成（料是从配方自己的样品清单里捏的，必然对得上味口），
     * 它回答的是另一个问题——<b>我们的摆法/尺寸构造对不对</b>。
     * 自检过了而实际执行还失败，那就是原料/组件的问题；自检就不过，那是 {@code layouts()} 的 bug。</p>
     *
     * <p>上一版这条日志会一口气打 2744 行（因为候选根本没按产物过滤），
     * 现在一个产物的候选通常只有 1~3 条，超过 {@link #CAND_LOG_HEADS} 条只列前面几条。</p>
     */
    private static void probeCandidates(Host host, Item want, List<Candidate> cands) {
        if (!GhostDebug.on()) return;
        try {
            GhostDebug.line("        摆法自检（投料用配方自己的样品，只验摆法/尺寸，不代表箱子有料）：");
            int shown = 0;
            for (Candidate c : cands) {
                if (shown++ >= CAND_LOG_HEADS) {
                    GhostDebug.line("        ... 其余 " + (cands.size() - CAND_LOG_HEADS) + " 条未打印");
                    break;
                }
                Recipe<?> raw = recipeOf(c);
                if (raw == null) {
                    GhostDebug.line("        - " + ghost(c) + " 取不到配方对象");
                    continue;
                }
                List<ItemStack> grid = new ArrayList<>();
                for (Ingredient g : c.ingredients) {
                    if (c.single && !grid.isEmpty()) break;
                    if (g == null || g.isEmpty()) { grid.add(ItemStack.EMPTY); continue; }
                    ItemStack s = sample(g);
                    grid.add(s == null ? ItemStack.EMPTY : s);
                }
                List<Layout> ls = layouts(raw, c, grid);
                StringBuilder sb = new StringBuilder();
                int ok = 0;
                for (Layout L : ls) {
                    if (L.input() == null) { sb.append("「").append(L.name()).append("」=造不出来 "); continue; }
                    boolean m = matchesSafe(raw, L.input(), host.level());
                    if (m) ok++;
                    sb.append("「").append(L.name()).append("」=").append(m ? "[OK]过 " : "[NO]不过 ");
                }
                int[] d = GhostDebug.shapedDims(raw);
                GhostDebug.line("        自检 " + ghost(c) + " " + raw.getClass().getSimpleName()
                        + (c.single ? " 单格" : " 工作台")
                        + " 产物=" + resultDesc(c)
                        + " 每手产 " + c.perCraft + " 件"
                        + " 配料 " + c.ingredients.size() + " 格"
                        + (d == null ? "（无固定 pattern）" : " pattern=" + d[0] + "x" + d[1])
                        + " 投料 " + grid.size() + " 格"
                        + " -> " + (ls.isEmpty() ? "没有可试的摆法 [NO]"
                                : (ok > 0 ? "[OK] " : "[NO] ") + sb.toString().trim()));
            }
        } catch (Throwable t) {
            GhostDebug.throwAt("probeCandidates", t);
        }
    }

    /** 配料的一个代表样品（1 件，带它自己声明的组件）。 */
    private static ItemStack sample(Ingredient g) {
        try {
            ItemStack[] opts = g.getItems();
            if (opts != null) for (ItemStack st : opts) if (st != null && !st.isEmpty()) return st.copyWithCount(1);
        } catch (Throwable t) {
            GhostDebug.throwAt("取配料样品", t);
        }
        return null;
    }

    /**
     * 推进一手：最多一次面向玩家的合成产出。调用方保证每 tick 只调一次
     * （{@code SR.MAX_CRAFT_PER_TICK = 1}）。
     */
    public static Step step(Host host, Job job) {
        if (job == null) return new Step(Reason.NO_RECIPE, 0, 0, true);
        if (job.finished) return new Step(job.fail, 0, 0, true);
        if (job.root == null) {
            if (job.fail == null) job.fail = Reason.NO_RECIPE;
            abandon(host, job);
            return new Step(job.fail, 0, 0, true);
        }
        if (job.remaining <= 0) { abandon(host, job); return new Step(job.fail, 0, 0, true); }
        if (expired(host)) {
            job.fail = Reason.DEADLINE;
            GhostDebug.detail("会话超时（" + (SR.SESSION_TIMEOUT_MS / 1000L) + " 秒硬上限），"
                    + job.targetId + " 还剩 " + job.remaining + " 件没做完");
            abandon(host, job);
            return new Step(Reason.DEADLINE, 0, 0, true);
        }
        try {
            return runStep(host, job);
        } catch (Throwable e) {
            // 以前这里也是静默贴 UNSAFE_RECIPE
            GhostDebug.throwAt("step(" + job.targetId + ") 执行阶段", e);
            GhostDebug.detail("执行 " + job.targetId + " 时抛异常：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()));
            job.fail = Reason.UNSAFE_RECIPE;
            abandon(host, job);
            return new Step(Reason.UNSAFE_RECIPE, 0, 0, true);
        }
    }

    /**
     * 收尾 / 放弃：把幽灵缓冲里还攥着的真实物品交还。
     *
     * <p>r7：交还口径统一成「输出箱 → 玩家背包 → 宿主 limbo 挂账」（{@link #disposeLeftover}），
     * 一律走安全存放，<b>全程不可能掉地上</b>；这些件数不计入投料账本，也就不会去扣 L 键缺口
     * （缺口本来就没被它们满足）。</p>
     */
    public static void abandon(Host host, Job job) {
        if (job == null || job.finished) {
            if (job != null) job.finished = true;
            return;
        }
        job.finished = true;
        try {
            if (!job.held.isEmpty()) {
                int kinds = 0, pieces = 0;
                for (ItemStack st : new ArrayList<>(job.held)) {
                    if (st == null || st.isEmpty()) continue;
                    kinds++;
                    pieces += st.getCount();
                    disposeLeftover(host, st, "任务收尾余料 " + job.targetId);
                }
                if (GhostDebug.on() && kinds > 0) {
                    GhostDebug.line("      收尾交还 " + job.targetId + "：缓冲里 " + kinds + " 堆 / "
                            + pieces + " 件已按「输出箱 → 背包 → 挂账」处置，不计入缺口扣减");
                }
            }
        } catch (Throwable ignored) {
            // 交还失败也不能抛：宿主在会话结束还会再清一次 limbo
        }
        job.held.clear();
    }


    private static String itemOf(ItemStack st) {
        return st == null || st.isEmpty() ? "空" : item(st.getItem());
    }

    // ==================== 一手：先 SIM 全绿，再 COMMIT ====================

    /**
     * 推进一手。
     *
     * <p><b>r14 第 2 点：死循环的总闸门。</b>旧版 SIM/COMMIT 预算用完就无条件
     * {@code return Step.RETRY}，下一 tick 从零重扫同一手，<b>没有次数上限</b>；
     * 而每一手 SIM 都要把 96 箱 × 27 格全扫一遍，大箱阵下单手扫描量天生超过 3000 槽 ——
     * 这一手永远过不去，除了撞超时没有第二种结局（用户日志里那句无限重复的「本手改期」）。</p>
     *
     * <p>r14 两管齐下：</p>
     * <ol>
     *   <li><b>取料走位置索引</b>（{@link ChestIndex}），单手检视量从「全箱阵」降到「有货的那几格」，
     *       预算天生就够用；</li>
     *   <li><b>改期封顶</b>：同一手连续改期到 {@link SR#MAX_STEP_RETRIES} 次仍过不去，
     *       贴 {@link Reason#STUCK} 判死、跳过这一种，转下一种 ——
     *       <b>一种物品卡住，绝不能再拖死整个会话</b>。</li>
     * </ol>
     */
    private static Step runStep(Host host, Job job) {
        int yield = Math.max(1, job.root.candidate.perCraft);
        int wantThisStep = Math.min(job.remaining, yield);
        // r8 Bug①：need > 0 就<b>至少</b>要做一手。旧版这里算出过 wantThisStep == 0 的形态
        // （remaining 被"投进背包的件数"扣减，而投进输出箱的那几件不扣），
        // 于是最后一手压根没执行 —— 用户看到的"缺 5 个栅栏门只合成了 4 个"。
        if (wantThisStep <= 0) wantThisStep = 1;

        // r7 Bug④ 的教训保留：SIM 与 COMMIT 各一份预算，每手开始前各自装满，互不挤占。
        // r14：预算限的是「料格」而不是「全箱槽位」——取料已经走索引了。
        host.resetLocBudget(true);
        host.resetLocBudget(false);

        // ① SIM：纯模拟，世界一个字节都不改
        Ctx sim = new Ctx(true);
        Reason r = resolve(host, job, job.root, wantThisStep, 0, sim);
        if (r != Reason.NONE) {
            if (!r.fatal()) return deferStep(host, job, "① 模拟阶段", sim, r);
            GhostDebug.detail(job.targetId + " 模拟阶段就没走通：" + r.name() + "（" + r.label() + "）");
            GhostDebug.line("      (B) 模拟阶段没过[" + r.name() + "] " + item(job.root.item)
                    + "：这一手要 " + wantThisStep + " 件，SIM 检视 " + sim.locsUsed + "/"
                    + SR.CRAFT_SIM_LOCS + " 料格（COMMIT 还没开始）；缓冲里已备 "
                    + sim.countInBufferNoConsume(job, job.root.item) + " 件；此刻箱里可用="
                    + describeNowAvailable(host, job.root.item));
            job.fail = r;
            abandon(host, job);
            return new Step(r, 0, sim.consumed, true);
        }
        ItemStack product = sim.productOf(job, job.root.item, wantThisStep);
        if (product.isEmpty() || product.getItem() != job.root.item) {
            GhostDebug.detail(job.targetId + " 模拟阶段产出对不上：合成出的是 " + itemOf(product)
                    + "，要的是 " + item(job.root.item));
            GhostDebug.line("      (B) 模拟阶段没过[UNSAFE_RECIPE] " + item(job.root.item)
                    + "：合成产出物品与目标不一致，实得=" + GhostDebug.st(product)
                    + "，SIM 检视 " + sim.locsUsed + "/" + SR.CRAFT_SIM_LOCS + " 料格");
            job.fail = Reason.UNSAFE_RECIPE;
            abandon(host, job);
            return new Step(Reason.UNSAFE_RECIPE, 0, sim.consumed, true);
        }
        // r8 Bug①（**这一条就是"栅栏门少合成一个"的正主**）：
        // 旧版这里只问随身四级存放处（手持容器 → Curios → 随身潜影盒 → 背包），
        // 一处塞不下就直接判 NO_SPACE 收手 —— 完全没算输出箱。
        // 单件产出的配方（栅栏门、门、船）一手就投 1 件，最容易在<b>最后一手</b>赶上背包被
        // 这一趟刚搬进来的材料塞满：前 4 手都成功，第 5 手一进 SIM 就被这道闸门掐死，
        // 于是"缺 5 个只合成 4 个"。而 deliverProduct 里那段"背包满→退输出箱"成了永远走不到的死代码。
        // 现在：随身 + 输出箱<b>两边加起来</b>有一点位置就放行，只剩挂账都没有才判死。
        Room room = roomFor(host, product);
        if (room.total() <= 0) {
            GhostDebug.detail(job.targetId + " 成品放不下：模拟要放 " + GhostDebug.st(product)
                    + "，背包/精妙背包/饰品栏/潜影盒与输出箱（" + (host.outputs() == null ? 0 : host.outputs().size())
                    + " 个）一件都塞不下");
            GhostDebug.line("      (B) 模拟阶段没过[NO_SPACE] " + item(job.root.item)
                    + "：要投 " + GhostDebug.st(product) + "；随身存放处可放 " + room.backpack()
                    + " 件，输出箱可放 " + room.output() + " 件（两边全零才判死）");
            job.fail = Reason.NO_SPACE;
            abandon(host, job);
            return new Step(Reason.NO_SPACE, 0, sim.consumed, true);
        }
        // r7 Bug⑥：超发部分必须在 SIM 就看清去处，别等 COMMIT 才发现塞不下。
        int simTotal = sim.countInBufferNoConsume(job, job.root.item);
        int simOver = Math.max(0, simTotal - wantThisStep);
        String overPlan = simOver <= 0 ? "无超发"
                : ("超发 " + simOver + " 件 -> " + whereOverflowGoes(host, job.root.item, simOver));
        GhostDebug.line("      ① 模拟全绿：" + job.targetId + " 这一手要 " + wantThisStep + " 件，"
                + "需投料 " + sim.consumed + " 件，合成产物 " + GhostDebug.st(product)
                + "；SIM 检视 " + sim.locsUsed + "/" + SR.CRAFT_SIM_LOCS + " 料格；" + overPlan);
        // r8：把这一手的去处写明（缺口件优先随身、塞不下的进输出箱，绝不落地）
        GhostDebug.line("      ① 投放计划[" + job.targetId + "] 要投 " + wantThisStep + " 件："
                + "随身存放处 " + room.backpack() + " 件 / 输出箱 " + room.output() + " 件"
                + (room.backpack() < wantThisStep ? "（背包位置不够，差额转输出箱，仍算补上缺口）" : "")
                + "；本任务还差 " + job.remaining + " 件");

        // ② COMMIT：全绿之后才真实扣除与放入
        Ctx real = new Ctx(false);
        r = resolve(host, job, job.root, wantThisStep, 0, real);
        if (r == Reason.BUDGET) {
            // 真实阶段预算用完：已经到手的原料留在幽灵缓冲里，下一 tick 同一手续做（同样受改期上限约束）
            return deferStep(host, job, "② 实际执行阶段", real, r);
        }
        if (r != Reason.NONE) {
            job.fail = r.fatal() ? r : Reason.NO_MATERIAL;  // 同一 tick 内箱子被改动：收手，别硬来
            // 这是最要紧的一种分叉：模拟说得通、真实做不成。要么箱子被人动过，
            // 要么真实物品的 DataComponents 与模拟样品不同（日志里两段的"实投物品"可直接对比）。
            GhostDebug.detail(job.targetId + " ⚠ 模拟通过但实际执行失败：" + r.name()
                    + "（" + r.label() + "）—— 箱子在这半秒里被动过，或真实物品的组件与模拟样品不一致，详见日志");
            GhostDebug.line("      (C) 模拟过、实际没过[" + r.name() + "] " + item(job.root.item)
                    + "：SIM 检视 " + sim.locsUsed + "/" + SR.CRAFT_SIM_LOCS + " 料格，COMMIT 检视 "
                    + real.locsUsed + "/" + SR.CRAFT_COMMIT_LOCS + " 料格，COMMIT 已到手 "
                    + real.consumed + " 件（留在缓冲，收尾交还），箱里此刻可用="
                    + describeNowAvailable(host, job.root.item));
            abandon(host, job);
            return new Step(job.fail, 0, real.consumed, true);
        }

        Delivery d = deliverProduct(host, job, real, wantThisStep);
        // r8 Bug①：抵账看 closed（凡是离开幽灵缓冲的件数），投料账本（L 键扣减）只认 credited。
        // 旧版这里用 put（只算进随身存放处的件数）扣 remaining，进输出箱的那几件等于白合，
        // 缺口永远减不到 0，最后一手因此被跳过。
        job.delivered += d.credited();
        job.consumed += real.consumed;
        job.remaining -= d.closed();
        // r14：这一手真的推进了 —— 改期计数归零，后面再遇到预算问题可以从头数起。
        job.stepRetries = 0;
        if (job.remaining < 0) job.remaining = 0;
        GhostDebug.line("      ② 实际执行完成：" + job.targetId + " 离开缓冲 " + d.closed()
                + " 件（随身 " + d.intoPack() + " / 输出箱 " + d.intoOutput() + " / 挂账 " + d.intoLimbo()
                + "，其中计入 L 键投料账本 " + d.credited() + " 件），消耗原料 " + real.consumed
                + " 件，还差 " + job.remaining + " 件"
                + "；COMMIT 检视 " + real.locsUsed + "/" + SR.CRAFT_COMMIT_LOCS + " 料格");

        if (job.remaining <= 0) {
            GhostDebug.line("      ② 最后一手已执行：" + job.targetId + " 缺口 " + d.want()
                    + " 件已全部抵清，本任务收工");
            abandon(host, job);
            return new Step(null, d.closed(), real.consumed, true);
        }
        if (d.closed() <= 0) {          // 一手没投进任何东西：这物品到此为止，绝不重试
            GhostDebug.detail(job.targetId + " 一件都没投进去：存放处与输出箱都满了（合成倒是成功了）");
            GhostDebug.line("      (C) 合成成功但一件都没投进去[" + item(job.root.item)
                    + "]：随身 A/B/C/D 与输出箱全满，缓冲里 " + real.countInBufferNoConsume(job, job.root.item)
                    + " 件改走余料处置（输出箱 → 背包 → 挂账）；"
                    + "本任务已交付 " + job.delivered + " 件，还差 " + job.remaining + " 件");
            job.fail = Reason.NO_SPACE;
            abandon(host, job);
            return new Step(Reason.NO_SPACE, d.closed(), real.consumed, true);
        }
        return new Step(null, d.closed(), real.consumed, false);
    }

    /**
     * 「本手改期」的唯一出口（r14 第 2 点：<b>死循环总闸门</b>）。
     *
     * <p>预算用完从来不是这一种物品的错，所以旧版只是 {@code return Step.RETRY} ——
     * 问题是它<b>没有上限</b>，下一 tick 又从头模拟同一手，于是会话时间全烧在重复劳动上，
     * 后面排队的十几种材料一件都轮不到（用户实测：只搬了 4 种就卡死）。</p>
     *
     * <p>现在数着来：连续改期到第 {@link SR#MAX_STEP_RETRIES} 次仍然过不去，
     * 就贴 {@link Reason#STUCK} 判死这一种，让调度器立刻转下一种。
     * 判死前已经真实到手的原料一律走 {@link #abandon} 交还（输出箱 → 背包 → 挂账），绝不落地。</p>
     */
    private static Step deferStep(Host host, Job job, String stage, Ctx ctx, Reason r) {
        job.stepRetries++;
        if (job.stepRetries <= SR.MAX_STEP_RETRIES) {
            GhostDebug.line("      " + stage + "预算用完（本手已检视 " + ctx.locsUsed + " 料格，额度 "
                    + (ctx.simulate ? SR.CRAFT_SIM_LOCS : SR.CRAFT_COMMIT_LOCS) + "），本手改期 "
                    + job.stepRetries + "/" + SR.MAX_STEP_RETRIES + " 次 —— 不算失败，下一 tick 续做");
            return Step.RETRY;
        }
        GhostDebug.line("      " + stage + "连续 " + SR.MAX_STEP_RETRIES
                + " 次都算不动（每次检视 " + ctx.locsUsed + " 料格都撞额度上限），"
                + job.targetId + " 跳过 —— 转下一种，绝不再占用会话时间");
        GhostDebug.detail(job.targetId + " 算力超限，已跳过（这一种最多吃 "
                + SR.MAX_STEP_RETRIES + " 手，不再无限重来）");
        job.fail = Reason.STUCK;
        abandon(host, job);
        return new Step(Reason.STUCK, 0, ctx.consumed, true);
    }

    /** 一手产出的去处容量（全部是模拟，世界一个字节都不改）。 */
    private record Room(int backpack, int output) {
        int total() { return backpack + output; }
    }

    /**
     * 这一手的产物能去哪儿：<b>随身四级存放处</b> + <b>输出箱</b>，两个都量一遍。
     *
     * <p>旧版只量随身那三级，于是"背包满 + 已标记输出箱"这个组合会在模拟阶段就被判 NO_SPACE，
     * 最后一手直接蒸发。输出箱是<b>只进不出</b>的溢出箱，量容量时按各箱模拟插入求和
     * （封顶到这一手要投的件数，多算没有意义）。</p>
     */
    private static Room roomFor(Host host, ItemStack probe) {
        if (probe == null || probe.isEmpty()) return new Room(0, 0);
        int pack = 0;
        try {
            // r11 Bug1：SIM 阶段带标签调用，落点可疑（主物品栏/无处可放）时日志里会有一行「存放处扫描」
            StorageTargets.Target t = StorageTargets.find(host.player(), probe, "SIM");
            if (t != null) pack = Math.max(0, t.room());
        } catch (Throwable ignored) {}
        int out = 0;
        try {
            List<BlockPos> outs = host.outputs();
            if (outs != null) {
                for (BlockPos pos : outs) {
                    if (out >= probe.getCount()) break;
                    IItemHandler h = host.outputAt(pos);
                    if (h == null) continue;
                    out += StorageTargets.roomOf(h, probe);
                }
            }
        } catch (Throwable ignored) {}
        return new Room(pack, out);
    }

    /**
     * 日志用：此刻材料箱里这个品种还剩多少可用（扣掉按件锁定的预留）。
     *
     * <p>r14：走索引。旧版这里为了写一行日志，把整个箱阵（96 箱 × 27 格）真扫一遍 ——
     * 它出现在<b>每一次失败分支</b>上，等于"失败得越多次，扫得越狠"，是预算被白白吃光的一条暗渠。
     * 现在 {@link ChestIndex#describeLive} 只摸索引登记过的那几个料格。</p>
     */
    private static String describeNowAvailable(Host host, Item item) {
        if (!GhostDebug.on()) return "（日志已关，未复查）";
        try {
            ChestIndex idx = host.index();
            int cached = idx.count(item);
            int locked = host.reservedCount(item);
            String live = idx.describeLive(item, SR.CRAFT_SIM_LOCS);
            return live + "，其中 " + locked + " 件被别的任务按件锁定，可用 "
                    + Math.max(0, cached - locked) + " 件";
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 把这一手攒出的目标产物投进存放处。
     *
     * <p>r7 存放口径（用户定的）：</p>
     * <ul>
     *   <li><b>缺口内的件</b>：走原来的 A→B→C→D（手持容器 → Curios → 随身潜影盒 → 背包），
     *       这些才计进投料账本（L 键列表据此扣缺口）；塞不下再退输出箱，最后挂账。</li>
     *   <li><b>超出缺口的件</b>（一手 6 个台阶、缺口只剩 5 个的那种）：不进账本，
     *       当场按「输出箱 → 背包 → limbo 挂账」处置，绝不退回缓冲等收尾，也绝不落地。</li>
     * </ul>
     *
     * <p><b>r8 Bug①</b>：旧版只把「进随身存放处」的件数返回给调用方去扣 remaining，
     * 于是同一手里转进输出箱的那几件<b>不算交付</b>，缺口永远减不到 0；
     * 加上 {@code break} 提前退出（背包只塞得下一半时也直接收工），最后一手就这幺丢了。
     * 现在返回一份明细：凡是<b>离开幽灵缓冲</b>的件数都算抵账（{@code closed}），
     * 只有真正进了玩家随身容器的才计投料账本（{@code credited}）。</p>
     */
    private static Delivery deliverProduct(Host host, Job job, Ctx ctx, int want) {
        int closed = 0, credited = 0, intoPack = 0, intoOut = 0, intoLimbo = 0;
        int n = want;
        while (n > 0) {
            ItemStack one = ctx.takeHeld(job, job.root.item);
            if (one == null) break;
            if (one.getCount() > n) {          // 超发部分：立刻按溢出策略处置，不再退回缓冲
                ItemStack extra = one.split(one.getCount() - n);
                disposeLeftover(host, extra, "超发产物");
            }
            int batch = one.getCount();        // split 之后再定量：这一批确实要交付几件
            // ① 先塞随身四级存放处（能塞几件塞几件），这部分计投料账本。
            //    StorageTargets.commit 内部吃的是副本，返回没塞进去的剩余，所以逐轮把 pending 换成剩余即可。
            ItemStack pending = one;
            while (!pending.isEmpty()) {
                // r13：投放阶段也带标签 —— SIM 说能进手持包、这里却落主物品栏时，
                // 日志里两行「存放处扫描」并排一比，A 级到底哪一关没过一眼可见。
                StorageTargets.Target dst = StorageTargets.find(host.player(), pending, "投放COMMIT");
                if (dst == null) break;
                ItemStack left = StorageTargets.commit(dst, pending);
                int got = pending.getCount() - left.getCount();
                if (got <= 0) break;           // 声称放得下却一件没吃进去：别再原地重试
                host.onPlaced(pending.copyWithCount(got));  // 投料账本：只认进玩家随身容器的件数
                credited += got;
                intoPack += got;
                n -= got;
                pending = left;
            }
            // ② 背包塞不下的缺口件：转输出箱。它没进玩家手里，所以不计投料账本，
            //    但<b>确实补上了缺口</b>，必须算进 closed，否则最后一手就会被重复跳过。
            if (!pending.isEmpty()) {
                int put = insertIntoOutputs(host, pending, "缺口件（背包已满）");
                intoOut += put;
                n -= put;
            }
            // ③ 输出箱也没有（或压根没标记）：交宿主挂账，会话结束再安置一轮 —— 同样算抵账
            if (!pending.isEmpty()) {
                int rest = pending.getCount();
                ItemStack snapshot = pending.copyWithCount(rest);
                host.stash(pending);
                host.noteOverflow(snapshot, "limbo");
                intoLimbo += rest;
                n -= rest;
            }
            closed += batch;
        }
        return new Delivery(want, closed, credited, intoPack, intoOut, intoLimbo);
    }

    /**
     * 一手交付的明细（r8 Bug① 引入，替代原来那个"只算进背包件数"的 int）。
     *
     * @param want       这一手原计划交付几件
     * @param closed     离开幽灵缓冲的件数 —— <b>拿这个扣 remaining</b>
     * @param credited   进随身四级存放处的件数 —— <b>拿这个记投料账本</b>（L 键缺口据此减）
     * @param intoPack   同上（留着日志用，将来若要区分 A/B/C/D 再拆）
     * @param intoOutput 转进输出箱的件数（补了缺口但不进账本）
     * @param intoLimbo  交宿主挂账的件数（同上）
     */
    private record Delivery(int want, int closed, int credited,
                            int intoPack, int intoOutput, int intoLimbo) {}

    // ==================== r7：超发产物 / 中间产物 / 余料的入库 ====================

    /**
     * 余料处置的唯一出口：<b>输出箱 → 玩家背包 → 宿主 limbo 挂账</b>，全程不可能掉地上。
     *
     * <p>「余料」包括三种真实到手但缺口账本不认的东西：
     * ①一手合成超出缺口的超发产物；②链内中间产物（2 木板合 4 木棍，栅栏只吃掉 3 根，剩 1 根）；
     * ③任务中途判死时缓冲里剩下的东西。它们都不计入投料账本，也就不会去扣 L 键缺口。</p>
     */
    private static void disposeLeftover(Host host, ItemStack st, String why) {
        if (st == null || st.isEmpty()) return;
        int total = st.getCount();
        int out = insertIntoOutputs(host, st, why);
        if (st.isEmpty()) {
            GhostDebug.line("      余料处置[" + why + "] " + total + " 件 " + item(st.getItem())
                    + " -> 输出箱");
            return;
        }
        StorageTargets.Target t = StorageTargets.find(host.player(), st, "余料COMMIT");
        if (t != null) {
            ItemStack left = StorageTargets.commit(t, st);
            int got = st.getCount() - left.getCount();
            if (got > 0) {
                host.noteOverflow(st.copyWithCount(got), "inventory");
                GhostDebug.line("      余料处置[" + why + "] " + got + " 件 " + item(st.getItem())
                        + " -> " + t.label() + "（不计入缺口扣减）");
            }
            st = left;
            if (st.isEmpty()) return;
        }
        int n = st.getCount();
        host.stash(st);   // 输出箱和背包都塞不下：交宿主挂账（limbo），会话结束还会再安置一轮
        host.noteOverflow(st.copyWithCount(n), "limbo");
        GhostDebug.line("      余料处置[" + why + "] " + n + " 件 " + item(st.getItem())
                + " -> 挂账 limbo（输出箱与背包均无空位，绝不掉落地面）");
    }

    /**
     * 往输出箱里塞，返回塞进去的件数（原堆会被就地扣减）。
     *
     * <p>输出箱<b>只进不出</b>：它永远不在 {@link Host#chests()} 里，取料与普查都摸不到它，
     * 所以「合成产物进输出箱 → 下次合成又从输出箱拿料」这种自循环不可能发生。</p>
     */
    private static int insertIntoOutputs(Host host, ItemStack st, String why) {
        if (st == null || st.isEmpty()) return 0;
        List<BlockPos> outs = host.outputs();
        if (outs == null || outs.isEmpty()) return 0;
        int put = 0;
        for (BlockPos pos : outs) {
            if (st.isEmpty()) break;
            IItemHandler h = host.outputAt(pos);
            if (h == null) continue;
            ItemStack left = ItemHandlerHelper.insertItem(h, st.copy(), false);
            int got = st.getCount() - left.getCount();
            if (got > 0) {
                put += got;
                st.shrink(got);
                host.noteOverflow(st.copyWithCount(got), "output");
                GhostDebug.line("      输出箱 " + GhostDebug.pos(pos) + " 收 " + got + " 件 "
                        + item(st.getItem()) + " [" + why + "]");
            }
        }
        return put;
    }

    /** SIM 阶段的预报：这 {@code n} 件超发产物会去哪儿（只模拟，不真写）。 */
    private static String whereOverflowGoes(Host host, Item item, int n) {
        if (!GhostDebug.on()) return "（日志已关）";
        try {
            List<BlockPos> outs = host.outputs();
            if (outs != null && !outs.isEmpty()) {
                ItemStack probe = new ItemStack(item, n);
                for (BlockPos pos : outs) {
                    IItemHandler h = host.outputAt(pos);
                    if (h == null) continue;
                    if (StorageTargets.roomOf(h, probe) > 0) return "输出箱 " + GhostDebug.pos(pos);
                }
                return "输出箱已满 -> 背包/挂账";
            }
            return "未标记输出箱 -> 背包/挂账";
        } catch (Throwable t) {
            return "?";
        }
    }


    private static boolean expired(Host host) {
        long d = host.deadlineMs();
        return d > 0 && System.currentTimeMillis() > d;
    }

    // ==================== 递归执行：把 need 件 item 攒进缓冲 ====================

    private static Reason resolve(Host host, Job job, Node n, int need, int depth, Ctx ctx) {
        if (need <= 0) return Reason.NONE;
        if (expired(host)) return Reason.DEADLINE;
        if (!host.hasSessionRoom()) return Reason.LIMIT;

        // 只「点数」不「取走」：缓冲里的存量算抵账，但东西必须留在原地，
        // 否则 oneCraft / deliverProduct 后面再去取就取空了（这是执行契约的关键）。
        int have = ctx.countInBuffer(job, n.item);
        int gap = need - have;
        if (gap <= 0) return Reason.NONE;

        if (n.kind == Kind.TAKE) return takeFromIndex(host, job, n.item, gap, ctx);
        if (depth > ModConfig.craftMaxDepth()) return Reason.TOO_DEEP;

        Candidate c = n.candidate;
        int crafts = (gap + c.perCraft - 1) / c.perCraft;
        for (int k = 0; k < crafts; k++) {
            Reason r = oneCraft(host, job, n, c, depth, ctx);
            if (r != Reason.NONE) return r;
        }
        // 收尾复核：缓冲里现在确实有这么多件，才算这一步成立
        int now = ctx.countInBuffer(job, n.item);
        if (now >= need) return Reason.NONE;
        GhostDebug.detail(item(n.item) + " 合成后缓冲数量对不上：应有 " + need + " 件，实际 " + now
                + " 件（本手合成本身没问题，多半是余料被超发退回去了）");
        GhostDebug.line("      收尾复核不过[" + (ctx.simulate ? "模拟" : "实际") + "] " + item(n.item)
                + "：need=" + need + " 缓冲=" + now + " 本手产出=" + ctx.producedOf(n.item)
                + " 配方=" + ghost(c) + " 每手产出=" + c.perCraft);
        return Reason.UNSAFE_RECIPE;
    }

    /**
     * 从标记箱凑够 need 件（r14：走 {@link ChestIndex} 的位置索引，SIM 只模拟，COMMIT 真实提取）。
     *
     * <p><b>这是打穿性能瓶颈的那一刀。</b>旧版这里对每个配料格都要把整个箱阵（96 箱 × 27 格）
     * 从头扫到尾，单手扫描量天生超过预算 —— 于是「本手改期 → 下一 tick 从零重扫同一手」无限循环。
     * 现在只摸索引里<b>真正有这个货的那几个料格</b>：查「有没有」是一次哈希命中，取货只走有货的格子。</p>
     *
     * <p>按件留底的口径（r7 Bug①）保留：最多只能拿走「箱里现货 − 别人还要的件数」，
     * 由 {@link ChestIndex#take} 的 {@code keepBack} 参数保证拿完之后箱里至少还剩 reserved 件。</p>
     */
    private static Reason takeFromIndex(Host host, Job job, Item item, int need, Ctx ctx) {
        if (need <= 0) return Reason.NONE;
        ChestIndex idx = host.index();
        // 按件锁定：搬运队列与别的任务还要这几件，必须留在箱里；
        // 但<b>本任务自己在规划期订走的量</b>不能算成别人的 —— 否则刚订好的原木轮到自己拿时被锁住。
        int own = 0;
        try {
            Integer v = job.bookings().get(item);
            own = v == null ? 0 : v;
        } catch (Throwable t) {
            own = 0;
        }
        int reserved = Math.max(0, host.reservedCount(item) - own);
        int budgetLeft = ctx.simulate
                ? (SR.CRAFT_SIM_LOCS - ctx.locsUsed)
                : (SR.CRAFT_COMMIT_LOCS - ctx.locsUsed);
        if (budgetLeft <= 0) return Reason.BUDGET;
        if (reserved > 0 && GhostDebug.once("rsv|" + job.targetId + "|" + item(item))) {
            GhostDebug.line("      按件锁定[" + (ctx.simulate ? "模拟" : "实际") + "] " + item(item)
                    + "：搬运队列/别的任务还要 " + reserved + " 件，箱里这一种的其余部分允许拿去用");
        }

        ChestIndex.Grab g = idx.take(item, need, reserved, ctx.simulate, budgetLeft);
        ctx.locsUsed += g.locsVisited;

        // **收料必须在预算判定之前**：COMMIT 模式下 g.stacks 里的东西已经真实离开箱子了，
        // 这时候直接 return BUDGET 就等于把玩家的材料变没。先一律收进幽灵缓冲（一件都丢不了），
        // 再谈改期 —— 下一 tick 的 SIM 会透过 {@link Ctx#countInBuffer} 认出这批"到手的货"。
        for (ItemStack st : g.stacks) {
            if (ctx.simulate) {
                ctx.simReceive(st);
            } else {
                host.onConsumed(st.copyWithCount(st.getCount()));
                ctx.jobHold(job, st);
            }
        }
        if (!host.spendLocs(g.locsVisited, ctx.simulate) || g.outOfBudget) {
            // 检视预算用完：已到手的件数留在缓冲里，本手改期（由 deferStep 封顶次数）
            GhostDebug.line("      取料改期[" + (ctx.simulate ? "模拟" : "实际") + "] " + item(item)
                    + "：本趟已到手 " + g.taken + " 件，检视 " + g.locsVisited + " 个料格撞到额度 "
                    + (ctx.simulate ? SR.CRAFT_SIM_LOCS : SR.CRAFT_COMMIT_LOCS)
                    + "，不算失败（已到手的一件都不会丢）");
            return Reason.BUDGET;
        }
        int got = g.taken;
        need -= got;

        if (need > 0) {
            GhostDebug.line("      取料不足[" + (ctx.simulate ? "模拟" : "实际") + "] " + item(item)
                    + "：还差 " + need + " 件（索引登记共 " + idx.count(item) + " 件，本趟已拿 " + got
                    + " 件，检视 " + g.locsVisited + " 个料格"
                    + (g.repairs > 0 ? "，自愈 " + g.repairs + " 次（缓存与实物对不上）" : "")
                    + "）" + (reserved > 0 ? "，其中按件锁定 " + reserved + " 件没算进来" : ""));
        }
        return need > 0 ? Reason.NO_MATERIAL : Reason.NONE;
    }

    /** 一次配方的复核结论（产物只在 NONE 时有效）。 */
    private record Verdict(Reason reason, ItemStack out, String layout) {
        static Verdict fail(Reason r) { return new Verdict(r, ItemStack.EMPTY, null); }
    }

    /** 一种候选摆法：名字 + 现造的输入对象（绝不复用旧实例）。 */
    private record Layout(String name, RecipeInput input, int reqW, int reqH) {}

    /**
     * 一次配方：摆格 → 造输入 → 回吐物检查 → matches 复核 → assemble → 产物核对 → 进缓冲。
     *
     * <p>每一步失败都会把完整现场写进 {@link GhostDebug}（对应排障的三个方向），
     * 并把一句短诊断带进游戏内的失败清单。</p>
     */
    private static Reason oneCraft(Host host, Job job, Node n, Candidate c, int depth, Ctx ctx) {
        if (ctx.assembles >= SR.MAX_CHAIN_ASSEMBLIES_PER_TICK) {
            GhostDebug.line("      链内前置 assemble 到上限 " + SR.MAX_CHAIN_ASSEMBLIES_PER_TICK
                    + " 手，本步改期（" + (ctx.simulate ? "模拟" : "实际") + "），不算失败");
            return Reason.BUDGET;
        }
        // r7 Bug④：预算分两份，SIM 扫掉的槽位不再把 COMMIT 饿死
        if (!host.spendLocs(1, ctx.simulate)) { ctx.locsUsed++; return Reason.BUDGET; }
        ctx.locsUsed++;

        Recipe<?> raw = recipeOf(c);
        if (raw == null) {
            GhostDebug.detail(ghost(c) + " 取不到配方对象（RecipeHolder 已失效？）");
            return Reason.UNSAFE_RECIPE;
        }

        int slots = c.single ? 1 : Math.max(1, Math.min(9, c.ingredients.size()));
        List<ItemStack> grid = new ArrayList<>(slots);

        for (int i = 0; i < slots; i++) {
            Ingredient g = c.single ? firstIngredient(c) : at(c.ingredients, i);
            // 有序配方的空格（Ingredient.EMPTY）必须留成 ItemStack.EMPTY，不能塞任何东西
            if (g == null || g.isEmpty()) { grid.add(ItemStack.EMPTY); continue; }
            Node child = childFor(n, g);
            if (child == null) {
                refund(grid, job, ctx);
                GhostDebug.line("───── 复核不过[CHILD_MISSING] " + modeOf(ctx) + " 配方=" + ghost(c));
                GhostDebug.line("      第" + i + " 格的配料 = " + GhostDebug.ing(g) + "，但在计划树的子节点里找不到能负责它的人");
                for (int k = 0; k < n.childKeys.size(); k++) {
                    GhostDebug.line("      子节点" + k + "：键=" + GhostDebug.ing(n.childKeys.get(k))
                            + " 负责物品=" + item(n.children.get(k).item));
                }
                GhostDebug.detail(ghost(c) + " 复核不过：第" + i + " 格没有对应子节点（详见日志）");
                return Reason.UNSAFE_RECIPE;
            }

            Reason r = resolve(host, job, child, 1, depth + 1, ctx);   // 这一格要 1 件
            if (r != Reason.NONE) { refund(grid, job, ctx); return r; }
            ItemStack one = ctx.takeGridSlot(job, child.item, g);
            if (one == null) { refund(grid, job, ctx); return Reason.NO_MATERIAL; }
            grid.add(one);
        }

        Verdict v = verify(host, raw, c, grid, ctx);
        if (v.reason() != Reason.NONE) {
            refund(grid, job, ctx);
            return v.reason();
        }

        ctx.produce(job, v.out());
        ctx.assembles++;
        return Reason.NONE;
    }

    /**
     * 严格复核：多种摆法逐个试，每种都是<b>全新的 CraftingInput</b>；
     * 摆法都过不了 matches 就判 UNSAFE_RECIPE，过了再查回吐物与产出。
     */
    private static Verdict verify(Host host, Recipe<?> raw, Candidate c, List<ItemStack> grid, Ctx ctx) {
        String mode = modeOf(ctx);
        NonNullList<Ingredient> ings = c.ingredients;

        // 方向 2：逐格实测 Ingredient.test —— 一次性回答"是哪一格、因为什么过不去"
        Boolean[] tests = new Boolean[grid.size()];
        int filled = 0, pattern = 0;
        for (int i = 0; i < grid.size(); i++) {
            try {
                ItemStack ours = grid.get(i);
                if (ours != null && !ours.isEmpty()) filled++;
                Ingredient g = at(ings, i);
                if (g != null && !g.isEmpty()) pattern++;
                tests[i] = g == null ? null : g.test(ours);
            } catch (Throwable t) {
                tests[i] = null;
                GhostDebug.throwAt("Ingredient.test 槽" + i + " 配方=" + ghost(c), t);
            }
        }

        List<Layout> layouts = layouts(raw, c, grid);
        if (layouts.isEmpty()) {
            GhostDebug.dumpAttempt("NO_LAYOUT", mode, c.id(), raw, "（一种摆法都造不出来）", null,
                    0, 0, ings, grid, tests,
                    "可能原因：格子数与非空配料数对不上（非空格 " + filled + " 格）");
            GhostDebug.detail(ghost(c) + " 复核不过：摆不出任何合法输入（非空格 " + filled + "）");
            return Verdict.fail(Reason.UNSAFE_RECIPE);
        }

        String lastNote = "";
        for (Layout L : layouts) {
            if (L.input() == null) {
                GhostDebug.line("      摆法「" + L.name() + "」构造输入就失败了（见上面的异常行），跳过");
                continue;
            }

            // 方向 3：先查回吐物，再调 matches（按用户要求的顺序）。摆错的输入，回吐物只作参考。
            Object rem = remainingOf(raw, L.input());
            boolean remBad = remainingNonEmpty(rem);
            if (remBad && GhostDebug.once("rem|" + c.id() + "|" + L.name())) {
                GhostDebug.dumpRemainder(mode, c.id(), L.name(), rem);
            }

            boolean matched = matchesSafe(raw, L.input(), host.level());
            // 每种摆法一行结论，日志里能直接读出"到底哪一种摆法过了"
            if (GhostDebug.once("lay|" + c.id() + "|" + L.name() + "|" + mode)) {
                GhostDebug.line("      摆法「" + L.name() + "」matches=" + (matched ? "[OK]过" : "[NO]不过")
                        + (remBad ? "，但有回吐物" : "，无回吐") + "；" + GhostDebug.dims(L.input(), L.reqW(), L.reqH()));
            }
            if (!matched) {
                int[] d = GhostDebug.shapedDims(raw);
                lastNote = "matches=false（" + L.name() + "）";
                // 同一条配方的同一种摆法只完整 dump 一次（它每 tick 都会重来一遍）
                if (GhostDebug.once("m|" + c.id() + "|" + L.name() + "|" + mode)) {
                    GhostDebug.dumpAttempt("MATCHES_FALSE", mode, c.id(), raw, L.name(), L.input(),
                            L.reqW(), L.reqH(), ings, grid, tests,
                            "试到这一步没过，继续换下一种摆法。配方配料非空格数=" + pattern
                                    + "，我们投料非空格数=" + filled
                                    + (d == null ? "" : "，pattern 尺寸=" + d[0] + "x" + d[1]));
                }
                continue;
            }

            // 摆法对了还回吐东西 = 这个配方会额外吐容器/载体，按约定不做
            if (remBad) {
                GhostDebug.dumpAttempt("REMAINDER", mode, c.id(), raw, L.name(), L.input(),
                        L.reqW(), L.reqH(), ings, grid, tests,
                        "matches 已过（摆法 " + L.name() + "），但 getRemainingItems 非空 —— 这类配方会吐东西，幽灵合成按约定跳过");
                GhostDebug.detail(ghost(c) + " 跳过：配方有回吐物（会吐容器），详见日志");
                return Verdict.fail(Reason.UNSAFE_RECIPE);
            }

            ItemStack out = assembleSafe(raw, L.input(), host.player().registryAccess());
            ItemStack want = new ItemStack(c.result.getItem(), 1);
            // r7 第 4 项：默认<b>只比物品</b>。组件差异只记日志，绝不拦合成
            //（assemble 常带一份默认空组件补丁，与索引登记的产物比必然不等，上一版就是这么全杀）。
            boolean bad = out.isEmpty() || out.getItem() != c.result.getItem();
            if (!bad && !componentsMatch(out, c.result)) {
                componentDiffCount++;    // 日志摘要用：本轮一共"看见"了几次组件差异
                GhostDebug.dumpProduct(mode, c.id(), out, want, c.perCraft);
                String diff = GhostDebug.patchDiff(out, c.result);
                GhostDebug.line("      [OBS] 产物组件与索引登记的配方产物不一致（"
                        + (strictProductComponents ? "严格模式：判死" : "宽松模式（默认）：照常放行")
                        + "，第 " + componentDiffCount + " 次）：" + diff);
                if (strictProductComponents) {
                    GhostDebug.detail(ghost(c) + " 复核不过：产出组件对不上（" + diff + "）");
                    return Verdict.fail(Reason.UNSAFE_RECIPE);
                }
                GhostDebug.noteObserved("组件差异 " + componentDiffCount + " 次（默认放行，/ghostlog strict on 可判死）");
                bad = false;
            }
            if (bad) {
                GhostDebug.dumpProduct(mode, c.id(), out, want, c.perCraft);   // 逐项比对 ID/数量/组件
                GhostDebug.dumpAttempt("ASSEMBLE_BAD", mode, c.id(), raw, L.name(), L.input(),
                        L.reqW(), L.reqH(), ings, grid, tests,
                        out.isEmpty() ? "assemble 返回空（多半内部抛异常，见上面异常行）"
                                : "产出物品不是目标：要 " + item(c.result.getItem())
                                        + "，给出 " + (out.isEmpty() ? "空" : item(out.getItem())));
                GhostDebug.detail(ghost(c) + " 复核不过：产出对不上（要 " + item(c.result.getItem())
                        + "，给 " + (out.isEmpty() ? "空" : item(out.getItem())) + "）");
                return Verdict.fail(Reason.UNSAFE_RECIPE);
            }
            // 同一条配方只细看一次，避免每 tick 都刷满日志
            if (FIRST_OK.add(c.id())) {
                GhostDebug.line("      [OK] " + mode + " 复核通过，采用摆法「" + L.name() + "」（共试了 "
                        + layouts.size() + " 种摆法）；" + GhostDebug.dims(L.input(), L.reqW(), L.reqH()));
                GhostDebug.dumpProduct(mode, c.id(), out, want, c.perCraft);
            }
            return new Verdict(Reason.NONE, out, L.name());
        }

        int[] d = GhostDebug.shapedDims(raw);
        String hint = d == null ? "" : "；配方要求 input 尺寸必须是 " + d[0] + "x" + d[1]
                + "（ShapedRecipePattern.matches 是严格等尺寸比对，不是滑动窗口）";
        GhostDebug.detail(ghost(c) + " 复核不过：matches 全不过（试了 " + layouts.size() + " 种摆法"
                + (lastNote.isEmpty() ? "" : "，" + lastNote) + "）" + hint);
        return Verdict.fail(Reason.UNSAFE_RECIPE);
    }

    /**
     * 候选摆法，每一种都<b>新建</b>输入对象（{@code CraftingInput} 里的 {@code StackedContents}
     * 是构造时算好的，复用旧实例等于用过期缓存）。顺序：
     * ① 按配方自己的 width/height 原样摆（最正确，空格留空）；② 3x3 居中（老逻辑，兜底）；
     * ③ 压紧成 w×h（应对没有 width/height 的模组配方）。
     */
    private static List<Layout> layouts(Recipe<?> raw, Candidate c, List<ItemStack> grid) {
        List<Layout> out = new ArrayList<>();
        if (c.single) {
            RecipeInput in = buildSingle(grid);
            if (in != null) out.add(new Layout("单格(石切一类)", in, 1, 1));
            return out;
        }

        // ① 严格照配方的 width/height 摆：Ingredient.EMPTY 的槽位放 ItemStack.EMPTY
        int[] d = GhostDebug.shapedDims(raw);
        if (d != null && d[0] > 0 && d[1] > 0 && d[0] * d[1] == grid.size()) {
            List<ItemStack> exact = new ArrayList<>(grid.size());
            for (ItemStack st : grid) exact.add(st == null ? ItemStack.EMPTY : st);
            out.add(new Layout("按配方尺寸 " + d[0] + "x" + d[1], safeOf(d[0], d[1], exact), d[0], d[1]));
        }

        // ② 3x3 行主序（旧行为，保留当兜底；配方本身就是 3x3 时与①重复，不必再试）
        if (d == null || d[0] != 3 || d[1] != 3) {
            List<ItemStack> nine = new ArrayList<>(9);
            for (int i = 0; i < 9; i++) {
                ItemStack st = i < grid.size() ? grid.get(i) : null;
                nine.add(st == null ? ItemStack.EMPTY : st);
            }
            out.add(new Layout("3x3 行主序", safeOf(3, 3, nine), 3, 3));
        }

        // ③ 压紧：只拿非空物品，试遍 1..3 × 1..3 里所有能装下的形状
        List<ItemStack> dense = new ArrayList<>();
        for (ItemStack st : grid) if (st != null && !st.isEmpty()) dense.add(st);
        int n = dense.size();
        for (int w = 1; w <= 3; w++) {
            for (int h = 1; h <= 3; h++) {
                if (w * h != n) continue;
                if (d != null && (w != d[0] || h != d[1])) continue;   // 与①重复的形状不必再试
                out.add(new Layout("压紧 " + w + "x" + h, safeOf(w, h, new ArrayList<>(dense)), w, h));
            }
        }
        return out;
    }

    /** {@code CraftingInput.of} 抛异常（尺寸与列表长度不符等）时只丢掉这一种摆法，不影响别的。 */
    private static RecipeInput safeOf(int w, int h, List<ItemStack> items) {
        try {
            return CraftingInput.of(w, h, items);
        } catch (Throwable t) {
            GhostDebug.throwAt("CraftingInput.of(" + w + "," + h + ", size=" + items.size() + ")", t);
            return null;
        }
    }

    private static String modeOf(Ctx ctx) { return ctx.simulate ? "模拟阶段" : "实际执行阶段"; }

    private static String ghost(Candidate c) {
        String id = GhostDebug.rl(c == null ? null : c.id());
        int i = id.indexOf('/');
        return i > 0 ? id.substring(i + 1) : id;
    }

    private static String item(Item it) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).toString();
        } catch (Throwable t) {
            return String.valueOf(it);
        }
    }

    /**
     * 复核没过时把已经摆进格子的料退回缓冲：COMMIT 模式下这些是<b>真实到手</b>的物品，
     * 不退回来就等于凭空吞掉，比合成失败严重得多。
     */
    private static void refund(List<ItemStack> grid, Job job, Ctx ctx) {
        for (ItemStack st : grid) if (st != null && !st.isEmpty()) ctx.giveBack(job, st);
        grid.clear();
    }

    // ==================== 一步的上下文（SIM / COMMIT 共用一套代码） ====================

    private static final class Ctx {
        final boolean simulate;
        final Map<Item, Integer> simBuf = new HashMap<>();      // SIM 的虚拟存量
        final Map<Item, Integer> produced = new HashMap<>();    // 两种模式都记：本手合成产出了多少
        /** SIM 专用：这一遍彩排从<b>上一手已真实到手</b>的那批货上挪用过几件（只记账，绝不动 job.held）。 */
        final Map<Item, Integer> simHeldDrawn = new HashMap<>();
        int assembles;
        int consumed;
        /** r7 Bug④ / r14：本步实际检视的<b>料格</b>数（SIM/COMMIT 各一份，日志里看得见是谁把预算吃光的）。 */
        int locsUsed;

        Ctx(boolean simulate) { this.simulate = simulate; }

        /** 缓冲里现在有多少件 {@code item}（抵账用，取东西由调用方另行完成）。 */
        int countInBuffer(Job job, Item item) {
            // 真实到手、跨 tick 留在缓冲里的件数<b>两种模式都要算</b>。
            // r14 新增这条硬要求：改期续做（本手只拿到一部分料）之后，下一 tick 的 SIM
            // 如果只看着自己的虚拟存量，会发现"箱里已被自己掏空"而误判 NO_MATERIAL，
            // 把一整手已经到手的好材料白扔在缓冲里判死。
            int real = heldCount(job, item);
            if (!simulate) return real;
            return simBuf.getOrDefault(item, 0) + Math.max(0, real - heldDrawn(item));
        }

        /** 真实缓冲（{@code job.held}）里这个品种有几件。只读。 */
        private static int heldCount(Job job, Item item) {
            int n = 0;
            for (int i = 0; i < job.held.size(); i++) {
                ItemStack st = job.held.get(i);
                if (!st.isEmpty() && st.getItem() == item) n += st.getCount();
            }
            return n;
        }

        /** SIM 专用记账：这一遍彩排里从"已到手的那批货"上挪用过几件（绝不真去动 job.held）。 */
        int heldDrawn(Item item) {
            return simHeldDrawn.getOrDefault(item, 0);
        }

        /** 日志别名：与 {@link #countInBuffer} 一模一样（本来就只读），换个名字让调用处自解释。 */
        int countInBufferNoConsume(Job job, Item item) { return countInBuffer(job, item); }

    /** 取走刚好 1 件摆进配方格（SIM 与 COMMIT 都拿 {@code slotIngredient} 认可的样品，保证复核一致）。 */
    ItemStack takeGridSlot(Job job, Item item, Ingredient slotIngredient) {
        if (simulate) {
            int have = simBuf.getOrDefault(item, 0);
            if (have > 0) {
                simBuf.put(item, have - 1);
                return sampleFor(item, slotIngredient);
            }
            // r14：这一遍彩排没预备到这份货，但<b>上一手改期时已经真实拿到手</b>了 —— 借用它。
            // 只记 SIM 自己的挪用量，绝不真去 shrink(job.held)：那是真实物品，SIM 无权改动世界。
            int realFree = heldCount(job, item) - simHeldDrawn.getOrDefault(item, 0);
            if (realFree > 0) {
                simHeldDrawn.merge(item, 1, Integer::sum);
                return sampleFor(item, slotIngredient);
            }
            return null;
        }
        for (int i = 0; i < job.held.size(); i++) {
            ItemStack st = job.held.get(i);
            if (st.isEmpty() || st.getItem() != item) continue;
            ItemStack one = st.copyWithCount(1);
            st.shrink(1);
            if (st.isEmpty()) job.held.remove(i);
            return one;
        }
        return null;
    }

    /** 模拟阶段用的样品栈：优先拿配料自己声明的样品（带它期望的组件），拿不到再退化成裸栈。 */
    private static ItemStack sampleFor(Item item, Ingredient g) {
        try {
            if (g != null) {
                ItemStack[] opts = g.getItems();
                if (opts != null) {
                    for (ItemStack o : opts) {
                        if (o != null && !o.isEmpty() && o.getItem() == item) return o.copyWithCount(1);
                    }
                }
            }
        } catch (Throwable t) {
            GhostDebug.throwAt("取配料样品", t);
        }
        return new ItemStack(item, 1);
    }

        /** SIM 阶段：从箱子模拟取到的料记进虚拟存量（同时计投料件数，与 COMMIT 口径一致）。 */
        void simReceive(ItemStack st) {
            consumed += st.getCount();
            simBuf.merge(st.getItem(), st.getCount(), Integer::sum);
        }

        /** 退回：把已从缓冲取走的东西放回缓冲（不计入 consumed，它本来就已经计过一次）。 */
        void giveBack(Job job, ItemStack st) {
            if (simulate) simBuf.merge(st.getItem(), st.getCount(), Integer::sum);
            else mergeInto(job.held, st);
        }

        void jobHold(Job job, ItemStack st) {
            consumed += st.getCount();
            mergeInto(job.held, st);
        }

        void produce(Job job, ItemStack out) {
            produced.merge(out.getItem(), out.getCount(), Integer::sum);
            if (simulate) simBuf.merge(out.getItem(), out.getCount(), Integer::sum);
            else mergeInto(job.held, out);
        }

        int producedOf(Item item) {
            return produced.getOrDefault(item, 0);
        }

        /** 这一手可用的目标产物件数（SIM 用于存放处模拟；含上一手改期时已真实到手的件数）。 */
        ItemStack productOf(Job job, Item item, int max) {
            int n = countInBuffer(job, item);
            return n <= 0 ? ItemStack.EMPTY : new ItemStack(item, Math.min(n, max));
        }

        ItemStack takeHeld(Job job, Item item) {
            for (int i = 0; i < job.held.size(); i++) {
                ItemStack st = job.held.get(i);
                if (st.isEmpty() || st.getItem() != item) continue;
                job.held.remove(i);
                return st;
            }
            return null;
        }

        private static void mergeInto(List<ItemStack> list, ItemStack st) {
            for (int i = 0; i < list.size(); i++) {
                ItemStack x = list.get(i);
                if (!x.isEmpty() && ItemStack.isSameItemSameComponents(x, st)
                        && x.getCount() + st.getCount() <= x.getMaxStackSize()) {
                    x.grow(st.getCount());
                    return;
                }
            }
            list.add(st);
        }
    }

    // ==================== 规划期：读位置索引 + 递归建树 ====================
    //
    // r14：这里原来是一个 Planner —— 每规划一种物品就把整个箱阵（96 箱 × 27 格）重扫一遍。
    // 29 种材料 = 29 次全量普查，加上执行期每手 SIM 还要再扫，扫描量怎么给预算都不够。
    // 现在库存事实全部来自宿主共享的 ChestIndex（一次会话扫一遍 + 就地自愈），
    // 规划一个品种的库存成本降到「几次哈希查表」。

    /**
     * 计划期的预订账（试错时整份作废，用 fork/absorb 回滚）。
     *
     * <p>r7 Bug②：旧版 {@code absorb(child)} 把子账本<b>整份</b>加回父账本
     * （{@code used.merge(k, v, Integer::sum)}），可子账本是 {@code fork()} 出来的、
     * 一开始就带着父账本的全部数字 —— 于是父账本上原有的那些量被<b>重复计了一遍</b>，
     * 递归每深一层翻一倍。表现就是「箱里明明 54 块木板，规划到第三步就说只剩 0 块」。</p>
     */
    private static final class Trial {
        final ChestIndex index;
        /** 别人（搬运队列 + 其他已展开的任务）按件锁定的量快照。 */
        final Map<Item, Integer> locked = new HashMap<>();
        final Map<Item, Integer> used = new HashMap<>();
        /** 本账本订过的品种（含子账本并进来的），最后交给宿主记账。 */
        private int chosenIndex;

        Trial(ChestIndex index, Host host) {
            this.index = index;
            try {
                for (Item it : index.knownItems()) {
                    int r = Math.max(0, host.reservedCount(it));
                    if (r > 0) locked.put(it, r);
                }
            } catch (Throwable t) {
                GhostDebug.throwAt("预留账本快照", t);
            }
        }

        private Trial(ChestIndex index, Map<Item, Integer> locked, Map<Item, Integer> used) {
            this.index = index;
            this.locked.putAll(locked);
            this.used.putAll(used);
        }

        /** 可用量 = 索引现货 − 已被本轮计划订走的 − 别人按件锁定的。 */
        int avail(Item item) {
            int total = index.count(item);
            int u = used.getOrDefault(item, 0);
            int l = locked.getOrDefault(item, 0);
            return Math.max(0, total - u - l);
        }

        /** 索引现货（日志用）。 */
        int stock(Item item) {
            return index.count(item);
        }

        boolean indexComplete() {
            return index.complete;
        }

        /** 这个品种被别人锁了几件（日志用）。 */
        int lockedOf(Item item) {
            return locked.getOrDefault(item, 0);
        }

        int booked(Item item) {
            return used.getOrDefault(item, 0);
        }

        void book(Item item, int n) { used.merge(item, n, Integer::sum); }

        void noteChosen(int i) { chosenIndex = i; }

        int chosenIndex() { return chosenIndex; }

        Trial fork() {
            return new Trial(index, locked, used);
        }

        /**
         * 采纳子尝试的成果：只把<b>增量</b>并进父账本。
         *
         * <p>子账本里的数已经含着父账本那份，直接相加就是 Bug②；
         * 逐键取差值才是这次尝试真正多订的量。</p>
         */
        void absorb(Trial child) {
            if (child == null || child == this) return;
            for (Map.Entry<Item, Integer> e : child.used.entrySet()) {
                int mine = used.getOrDefault(e.getKey(), 0);
                int delta = e.getValue() - mine;
                if (delta > 0) used.merge(e.getKey(), delta, Integer::sum);
            }
        }

        /** 规划定案后交给宿主：本任务总共订走每种原料几件（跨任务互斥用）。 */
        Map<Item, Integer> snapshotBookings() {
            return new HashMap<>(used);
        }

        /** 日志摘要：订得最多的几种。 */
        String summary() {
            if (used.isEmpty()) return "（未订任何原料）";
            List<Map.Entry<Item, Integer>> es = new ArrayList<>(used.entrySet());
            es.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Map.Entry<Item, Integer> e : es) {
                if (n++ >= 6) { sb.append(" …共 ").append(es.size()).append(" 种"); break; }
                if (sb.length() > 0) sb.append(", ");
                sb.append(item(e.getKey())).append(" 订").append(e.getValue())
                        .append("/箱里").append(stock(e.getKey()))
                        .append("/锁").append(lockedOf(e.getKey()));
            }
            return sb.toString();
        }
    }


    /** 日志里最多逐个点评几条候选，剩下的只报总数（防刷屏）。 */
    private static final int CAND_LOG_HEADS = 12;

    /**
     * 递归建一棵"怎么弄到 {@code amount} 件 {@code want}"的计划树。
     *
     * @param target 根目标的原始栈（带 DataComponents），只在 {@code depth == 0} 参与产物比对；
     *               链内前置传 {@code null}，只按物品匹配
     * @param fail   失败原因回传盒（r7 Bug③：只用来报原因，<b>不再是跨分支的判死缓存</b>）
     */
    private static Node build(Host host, Trial t, Item want, int amount, int depth,
                              Set<Item> onPath, Fail fail, ItemStack target) {
        if (amount <= 0) { fail.set(Reason.NO_MATERIAL, depth); return null; }
        if (depth > ModConfig.craftMaxDepth()) { fail.set(Reason.TOO_DEEP, depth); return null; }

        if (depth > 0) {
            int have = t.avail(want);
            if (have >= amount) {           // 箱子里就有（扣掉预订与锁定还够）：叶子，直接拿
                t.book(want, amount);
                return new Node(want, Kind.TAKE, null);
            }
        }

        List<Candidate> cands = recipesFor(host, want, depth == 0 ? target : null);
        if (cands.isEmpty()) {
            fail.set(Reason.NO_RECIPE, depth);
            GhostDebug.line("      " + pad(depth) + item(want) + "：配方索引里查不到任何产出它的配方（NO_RECIPE）");
            return null;
        }
        // r7 Bug③ 的配套：候选太多时封顶，避免「不判死了、改成每条都重探」带来的组合爆炸。
        int tryMax = Math.min(cands.size(), SR.PLAN_MAX_CANDIDATES);
        GhostDebug.line("      " + pad(depth) + item(want) + "：候选配方 " + cands.size()
                + " 条（已按产物过滤），本次最多试 " + tryMax + " 条"
                + "（判死表已废除，同一中间产物在不同配方/不同需求量下会重新试）");
        if (GhostDebug.on()) probeCandidates(host, want, cands.size() > tryMax
                ? cands.subList(0, tryMax) : cands);

        Reason lastWhy = Reason.NO_RECIPE;
        int idx = 0;
        for (Candidate c : cands) {
            if (++idx > tryMax) {
                GhostDebug.line("      " + pad(depth) + "候选超过 " + SR.PLAN_MAX_CANDIDATES
                        + " 条上限，后面 " + (cands.size() - tryMax) + " 条不再试");
                break;
            }
            boolean verbose = GhostDebug.on() && idx <= CAND_LOG_HEADS;
            Map<Ingredient, Integer> perCraft = new LinkedHashMap<>();
            boolean bad = false;
            for (Ingredient g : c.ingredients) {
                if (g == null || g.isEmpty()) continue;
                if (g.hasNoItems()) { bad = true; break; }      // 拿不到样品的自定义原料：不碰
                perCraft.merge(g, 1, Integer::sum);
            }
            if (bad || perCraft.isEmpty()) {
                lastWhy = Reason.UNSAFE_RECIPE;
                if (verbose) candFail(idx, cands.size(), c, "配料里有取不到样品的自定义原料（hasNoItems）");
                continue;
            }

            int crafts = (amount + c.perCraft - 1) / c.perCraft;

            // ==== r12 断链修复：配料格 -> 「代表物品」有序清单，凑不成换下一个继续试 ====
            // 旧版一格只定一个代表物品，representative() 在整格零库存时直接退回样品清单第一个。
            // 整合包把栅栏/栅栏门/台阶的木板格做成标签（#c:planks 一类）后：
            //   箱里有木板 → have>=need 第一关就命中（所以单段稳定）；
            //   箱里只有原木 → 选中往往是别的树种木板，它没库存、它要原木也没库存，
            //   子链一失败整条候选当场作废（NO_MATERIAL 上抛），标签里「原木->云杉木板」这条活路从没被试过。
            // 现在按「箱里够 > 有配方能往上合成（词根与目标相同者优先）> 箱里有货但不够 > 其余」
            // 逐个重试，并为多段链加「尝试合成中间产物 X / 发现可用原料 Y」诊断日志。
            List<Map.Entry<Ingredient, Integer>> order = new ArrayList<>(perCraft.entrySet());
            // 箱里存量最少的格子先处理：做不出来的分支尽早暴露
            order.sort(Comparator.comparingInt((Map.Entry<Ingredient, Integer> e) -> maxAvailIn(e.getKey(), t)));

            if (verbose) {
                StringBuilder need = new StringBuilder();
                for (Map.Entry<Ingredient, Integer> e : order) {
                    if (need.length() > 0) need.append(" + ");
                    Item top = topRepresentative(host, e.getKey(), t, e.getValue() * crafts, want);
                    need.append(e.getValue() * crafts).append("x ").append(item(top))
                            .append("（箱里可用 ").append(top == null ? 0 : t.avail(top)).append("）");
                    if (top != null) {
                        int lk = t.lockedOf(top);
                        if (lk > 0) need.append("（其中 ").append(lk).append(" 件被搬运队列锁定）");
                    }
                    int alt = altCount(host, e.getKey(), t, e.getValue() * crafts);
                    if (alt > 0) need.append("［同格备选 ").append(alt).append(" 种］");
                }
                GhostDebug.line("      " + pad(depth) + "[" + idx + "/" + cands.size() + "] 试 " + ghost(c)
                        + " 每手产 " + c.perCraft + " 件，需 " + crafts + " 手：" + need);
            }

            Trial ct = t.fork();
            List<Node> kids = new ArrayList<>();
            List<Ingredient> keys = new ArrayList<>();
            boolean ok = true;
            Reason why = Reason.NO_MATERIAL;
            for (Map.Entry<Ingredient, Integer> e : order) {
                Ingredient g = e.getKey();
                int need = e.getValue() * crafts;
                List<Item> reps = representativeOptions(host, g, ct, need, want);
                if (reps.isEmpty()) { ok = false; why = Reason.UNSAFE_RECIPE; break; }
                Node child = null;
                int tried = 0;
                for (Item rep : reps) {
                    if (++tried > SR.PLAN_MAX_MEMBERS) {
                        GhostDebug.line("      " + pad(depth) + "同一配料格的候选代表物品超过 "
                                + SR.PLAN_MAX_MEMBERS + " 种上限，后面 " + (reps.size() - SR.PLAN_MAX_MEMBERS)
                                + " 种不再试（" + item(want) + " 的配料）");
                        break;
                    }
                    if (onPath.contains(rep)) { why = Reason.TOO_DEEP; continue; }   // 环路防护：换下一个候选
                    int avail = ct.avail(rep);
                    if (avail >= need) {
                        // 箱里直接够：叶子 —— r12 点名要的多段日志（发现可用原料 Y）
                        ct.book(rep, need);
                        child = new Node(rep, Kind.TAKE, null);
                        if (GhostDebug.on()) {
                            GhostDebug.line("      " + pad(depth + 1) + "发现可用原料 " + item(rep)
                                    + "：箱里现货 " + ct.stock(rep) + " 件，已订 "
                                    + (ct.booked(rep) - need) + " 件，锁定 " + ct.lockedOf(rep)
                                    + " 件，可用 " + avail + " 件 ≥ 需 " + need + " 件 → 直接拿取（叶子）");
                        }
                        break;
                    }
                    if (depth + 1 > ModConfig.craftMaxDepth()) { why = Reason.TOO_DEEP; continue; }
                    if (!recipesFor(host, rep).isEmpty()) {
                        // 不够但有配方：往下展开一条前置合成链 —— r12 点名要的多段日志（尝试合成中间产物 X）
                        if (GhostDebug.on()) {
                            GhostDebug.line("      " + pad(depth + 1) + "尝试合成中间产物 " + item(rep) + " x" + need
                                    + "（箱里现货 " + ct.stock(rep) + " 件，可用 " + avail
                                    + " 件 < 需；深度 " + (depth + 1) + "/" + ModConfig.craftMaxDepth()
                                    + "；判死表已废除，这一支失败只作废本候选，可回头换下一候选）");
                        }
                        onPath.add(rep);
                        Fail sub = new Fail();            // r7 Bug③：每条链自己的原因盒，不污染兄弟分支
                        Node node = build(host, ct, rep, need, depth + 1, onPath, sub, null);
                        onPath.remove(rep);
                        if (node != null) {
                            child = node;
                            if (GhostDebug.on()) {
                                GhostDebug.line("      " + pad(depth + 1) + "↳ " + item(rep) + " 展开成功："
                                        + describeNode(node) + "（多段链已接上）");
                            }
                            break;
                        }
                        why = sub.get();
                        if (GhostDebug.on()) {
                            GhostDebug.line("      " + pad(depth + 1) + item(rep) + " 展开失败（" + why.name()
                                    + " - " + why.label() + "）"
                                    + (tried < reps.size() && tried < SR.PLAN_MAX_MEMBERS
                                            ? "，换同一配料格的下一个候选物品重试" : "，该配料格没有下一个候选了"));
                        }
                        continue;
                    }
                    // 没配方又不够：若索引压根没扫全，给执行期一次实弹复核的机会，否则换下一个候选
                    if (!ct.indexComplete()) {
                        child = new Node(rep, Kind.TAKE, null);
                        break;
                    }
                    why = Reason.NO_MATERIAL;
                    if (GhostDebug.on()) {
                        GhostDebug.line("      " + pad(depth + 1) + item(rep) + " 不够：要 " + need
                                + " 件，箱里现货 " + ct.stock(rep) + " 件，已订 "
                                + ct.booked(rep) + " 件，被队列锁定 " + ct.lockedOf(rep)
                                + " 件，可用 " + avail + " 件，且它本身没有配方"
                                + (tried < reps.size() && tried < SR.PLAN_MAX_MEMBERS ? " → 换下一候选" : ""));
                    }
                }
                if (child == null) { ok = false; break; }
                kids.add(child);
                keys.add(g);
            }
            if (!ok) {
                lastWhy = why;
                if (verbose) candFail(idx, cands.size(), c, why.name() + " - " + why.label());
                continue;
            }

            t.absorb(ct);   // r7 Bug②：只并进增量，父账本不再被重复计一遍
            t.noteChosen(idx);
            Node make = new Node(want, Kind.MAKE, c);
            make.children.addAll(kids);
            make.childKeys.addAll(keys);
            GhostDebug.line("      " + pad(depth) + "[OK] 采纳配方 " + ghost(c) + "（第 " + idx + " 条，共试 "
                    + cands.size() + " 条）产出=" + resultDesc(c)
                    + " 类型=" + rawClassName(c) + " 采纳后预订账=" + t.summary());
            return make;
        }
        fail.set(lastWhy, depth);
        GhostDebug.line("      " + pad(depth) + "[NO] " + item(want) + " 的 " + cands.size()
                + " 条候选配方全部作废，最后原因：" + lastWhy.name() + " - " + lastWhy.label()
                + (cands.size() > CAND_LOG_HEADS
                        ? "（只点评了前 " + CAND_LOG_HEADS + " 条，其余 "
                          + (cands.size() - CAND_LOG_HEADS) + " 条未打印）" : ""));
        GhostDebug.detail(item(want) + " 全部 " + cands.size() + " 条候选配方都作废："
                + lastWhy.name() + "（" + lastWhy.label() + "）");
        return null;
    }


    private static String pad(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb.toString();
    }

    private static void candFail(int i, int total, Candidate c, String why) {
        GhostDebug.line("      [" + i + "/" + total + "] [NO] 放弃 " + ghost(c) + " ：" + why);
    }

    private static String rawClassName(Candidate c) {
        Recipe<?> r = recipeOf(c);
        return r == null ? "?" : r.getClass().getSimpleName();
    }

    /**
     * 给一个 {@link Ingredient} 排出「先试哪个具体物品去凑」的有序候选清单（r12 断链修复核心）。
     *
     * <p>标签配料（栅栏/台阶的木板格 {@code #c:planks}、木棍格 {@code #c:rods/wooden} 这类）
     * 的样品清单里排着几十个模组自己的木板/棍子。上一版只定<b>一个</b>代表物品：
     * 箱里有货时挑有货的（所以「箱里直接放木板」很稳），一件都没货就退回样品清单第一个 ——
     * 而这个「第一个」（往往是别的树种）十有八九既无库存、又要用它没有的原料去合成，
     * 子链一失败整条候选配方当场作废，上抛就是栅栏/台阶「凑不齐原料」。</p>
     *
     * <p>现在按可得性分四档排成有序清单，{@code build()} 会从头逐个试、凑不成换下一个：</p>
     * <ol>
     *   <li>箱里现成够（可用 ≥ 需求）→ 直接拿取叶子；</li>
     *   <li>有配方能往上合成 → 递归展开；档内再按<b>与目标同词根</b>优先
     *       （spruce_fence 的木板格先试 spruce_planks —— 云杉原木切出来就是它）；</li>
     *   <li>箱里有货但不够（没配方，只有普查没收全时才有救）；</li>
     *   <li>其余样品兜底（大概率失败，只为日志说清试了谁）。</li>
     * </ol>
     */
    private static List<Item> representativeOptions(Host host, Ingredient g, Trial t, int need, Item want) {
        List<Item> out = new ArrayList<>();
        try {
            ItemStack[] opts = g.getItems();
            if (opts == null || opts.length == 0) return out;
            List<Item> enough = new ArrayList<>(), craftable = new ArrayList<>(),
                    stocked = new ArrayList<>(), rest = new ArrayList<>();
            Set<Item> seen = new HashSet<>();
            for (ItemStack st : opts) {
                if (st == null || st.isEmpty()) continue;
                Item it = st.getItem();
                if (!seen.add(it)) continue;
                int have = t.avail(it);
                if (have >= need) enough.add(it);
                else if (!recipesFor(host, it).isEmpty()) craftable.add(it);
                else if (have > 0) stocked.add(it);
                else rest.add(it);
            }
            // 档内排序：先比「配方第一层原料在箱里的存量」（spruce_planks 因云杉原木存货高居首，
            // 别树种的木板直接垫底），再比与目标的词根重叠，最后维持标签原序。
            Comparator<Item> rank = Comparator
                    .comparingInt((Item it) -> supplyScore(host, it, t)).reversed()
                    .thenComparing(Comparator.comparingInt((Item it) -> tokenOverlap(want, it)).reversed());
            enough.sort(rank); craftable.sort(rank); stocked.sort(rank); rest.sort(rank);
            out.addAll(enough); out.addAll(craftable); out.addAll(stocked); out.addAll(rest);
        } catch (Throwable t2) {
            GhostDebug.throwAt("配料代表物品排序 " + item(want), t2);
        }
        if (out.isEmpty()) {
            Item old = representative(g);   // 老口径兜底：样品清单第一个
            if (old != null) out.add(old);
        }
        return out;
    }

    /**
     * 这个物品的配方原料在箱里有多少现货（只看第一层，纯排序启发，<b>不</b>替代递归验证）。
     * 分数高 = 更可能顺着现有库存一路展开到底（云杉木板 16 → 高分；橡木板 0 → 低分）。
     */
    private static int supplyScore(Host host, Item it, Trial t) {
        try {
            List<Candidate> cands = recipesFor(host, it);
            int best = 0;
            for (int ci = 0; ci < cands.size() && ci < 4; ci++) {
                Candidate c = cands.get(ci);
                boolean allStocked = true;
                int sc = 0;
                for (Ingredient g : c.ingredients) {
                    if (g == null || g.isEmpty()) continue;
                    int a = maxAvailIn(g, t);
                    if (a <= 0) allStocked = false;
                    sc += Math.min(a, 64);
                }
                if (allStocked) sc += 1000;    // 这一手的全部配料箱里都有现货
                if (sc > best) best = sc;
            }
            return best;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 这一格的第一优先代表物品（仅日志用）。 */
    private static Item topRepresentative(Host host, Ingredient g, Trial t, int need, Item want) {
        List<Item> l = representativeOptions(host, g, t, need, want);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 这一格排在第一优先之后还有几种备选（仅日志用，告诉读者失败时会换料重试）。 */
    private static int altCount(Host host, Ingredient g, Trial t, int need) {
        try {
            int n = representativeOptions(host, g, t, need, null).size();
            return Math.max(0, Math.min(n - 1, SR.PLAN_MAX_MEMBERS - 1));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 这一格所有样品在箱里的最大可用量（条目排序启发用：存量最少的格子先处理）。 */
    private static int maxAvailIn(Ingredient g, Trial t) {
        int best = 0;
        try {
            ItemStack[] opts = g.getItems();
            if (opts != null) {
                for (ItemStack st : opts) {
                    if (st == null || st.isEmpty()) continue;
                    int a = t.avail(st.getItem());
                    if (a > best) best = a;
                }
            }
        } catch (Throwable ignored) {}
        return best;
    }

    /**
     * 名字词根重叠数（spruce_fence ↔ spruce_planks 共享 "spruce" 记 1）。
     * 只用于候选档内排序偏好，<b>不做硬过滤</b>：同档别的树种照样会在换料重试时被试到。
     */
    private static int tokenOverlap(Item a, Item b) {
        try {
            String pa = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(a).getPath();
            String pb = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(b).getPath();
            int n = 0;
            for (String tk : pa.split("_")) {
                if (tk.length() >= 3 && pb.contains(tk)) n++;
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 日志用：这个子节点是「直接拿」还是「按哪条配方合成」。 */
    private static String describeNode(Node n) {
        if (n == null) return "?";
        return n.kind == Kind.TAKE ? "从标记箱直接拿" : "配方 " + ghost(n.candidate) + " 合成";
    }

    /** 老口径：只取样品清单里第一个物品（不做可得性判断，兜底用）。 */
    private static Item representative(Ingredient g) {
        try {
            ItemStack[] opts = g.getItems();
            if (opts == null || opts.length == 0) return null;
            for (ItemStack st : opts) if (st != null && !st.isEmpty()) return st.getItem();
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    // ==================== 配方索引（按产物建表，整张表只扫一次） ====================
    //
    // 上一版的致命问题就在这一节：recipesFor(host, want) 把 <b>全部</b> 工作台/石切配方
    // 无差别塞进一个 list，只按 want 当 map 的 key，却<b>从没拿配方产物跟 want 比过</b>。
    // 于是查 minecraft:spruce_fence 也返回 2744 条（香蕉树楼梯、堆肥桶全在内），
    // 后果有三个：
    //   1) 递归建树对每条垃圾配方都要试一边，2744 x 递归直接把日志配额和规划时间吃光；
    //   2) 垃圾配方失败时会往 dead 判死表里写中间产物（例如木棍），
    //      真配方再需要它时被 dead 一口回绝 —— 栅栏就是这么"合不出来"的；
    //   3) 扫描还受 CRAFT_RECIPE_SCAN_CAP 截断，原版栅栏配方可能根本没进候选表。
    // 现在：按 getAllRecipesFor(CRAFTING / STONECUTTING) 各扫一遍，产物取不到的直接丢，
    // 取到的按 产物物品 分组建索引，查询 O(1)，并且做产物 DataComponents 严格比对。

    /** 产物物品 -> 会产出它的候选配方。整表只建一次，资源包重载时作废。 */
    private static volatile Map<Item, List<Candidate>> INDEX;
    private static volatile boolean indexBuilt;
    /** r23：上一次建表是否干净完成。失败的空表绝不入缓存，也不许拿它给品种定罪。 */
    private static volatile boolean indexHealthy;

    /** 索引统计，只给日志用。r12 起把「无序 shapeless 收了几条」也单列，
     *  直接回答排查方向 2：原木切木板这类 ShapelessRecipe 到底有没有进索引。 */
    private static int idxScanned, idxKept, idxNoResult, idxOther, idxShapeless;
    private static boolean idxTruncated;

    /** 取（必要时构建）配方索引。建表失败时返回空表并留痕，绝不抛。 */
    private static Map<Item, List<Candidate>> index(Host host) {
        return index(host.level(), host.player().registryAccess());
    }

    /**
     * 同上，直接吃世界与注册表访问（{@code /ghostlog index|find} 走这条路，
     * 因此服务端控制台也能用，不需要玩家实体）。
     */
    private static Map<Item, List<Candidate>> index(Level level, HolderLookup.Provider provider) {
        if (indexBuilt) return INDEX;
        synchronized (GhostCraftEngine.class) {
            if (indexBuilt) return INDEX;
            Map<Item, List<Candidate>> map = new HashMap<>();
            boolean ok;
            try {
                buildIndex(level, provider, map);
                ok = true;
            } catch (Throwable t) {
                GhostDebug.throwAt("buildIndex 配方索引构建失败", t);
                map.clear();
                ok = false;
            }
            // r23：建表失败的半成品空表<b>绝不入缓存</b>。旧版失败也照缓存，后果是
            // 整场会话里每个品种都"查无配方"——一次瞬时异常连坐一整类可合成物，
            // 正是「无解配方引发全局误杀」的暗通道。失败就留到下次用到时重建。
            indexHealthy = ok;
            if (ok) {
                INDEX = map;
                indexBuilt = true;
            }
            GhostDebug.line("      配方索引建表" + (ok ? "完成" : "失败（空表不入缓存，下次用到时重建）")
                    + "：检视 " + idxScanned + " 条（工作台+石切），"
                    + "收 " + idxKept + " 条 / " + map.size() + " 种产物（其中无序 shapeless " + idxShapeless
                    + " 条——原木切木板这类配方就在这一档，收进来即代表索引没漏它）；"
                    + "产物取不到而丢弃 " + idxNoResult + " 条，其它原因丢 " + idxOther + " 条"
                    + (idxTruncated ? " [!!] 达到扫描上限 " + SR.CRAFT_INDEX_SCAN_CAP + "，索引可能不完整" : ""));
            // 成功走缓存表；失败走这份一次性空表（不进 INDEX，下轮自会重建）。
            return ok ? map : Map.of();
        }
    }

    /**
     * r23：配方索引此刻是否可信（顺带触发建表）。<b>阶段 B 门口的 NO_RECIPE 闸门只许在
     * 索引健康时定罪</b>——索引构建失败时查不到配方是索引的病，不是这个物品的病，
     * 拿它判 NO_RECIPE 就是冤案。不健康时闸门整体让行，任务照常进队列，由 plan 现场定夺。
     */
    public static boolean recipeIndexHealthy(Host host) {
        try {
            index(host.level(), host.player().registryAccess());
        } catch (Throwable t) {
            GhostDebug.throwAt("配方索引健康检查", t);
            return false;
        }
        return indexHealthy;
    }

    /**
     * {@code /ghostlog index [物品 id]}：把索引统计（或某个物品的候选配方）写进日志。
     *
     * @return 给人看的一句总结
     */
    public static String debugIndex(Level level, HolderLookup.Provider provider, String keyword) {
        Map<Item, List<Candidate>> idx = index(level, provider);
        StringBuilder stats = new StringBuilder();
        stats.append("配方索引：").append(idx.size()).append(" 种产物，收 ").append(idxKept)
                .append(" 条 / 检视 ").append(idxScanned).append(" 条")
                .append("；产物取不到丢 ").append(idxNoResult).append(" 条，其它丢 ").append(idxOther);
        if (idxTruncated) stats.append(" [!!] 已达上限，索引不完整");
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            GhostDebug.line("      /ghostlog " + stats);
            return stats.toString();
        }
        // 精确 id 优先，退一步做子串匹配（用户经常只记得 spruce_fence 这种短名）
        Item exact = null;
        try {
            ResourceLocation rid = ResourceLocation.tryParse(kw);
            if (rid != null) {
                Item got = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rid);
                if (got != null && got != net.minecraft.world.item.Items.AIR) exact = got;
            }
        } catch (Throwable ignored) {}

        if (exact != null) {
            List<Candidate> cs = idx.getOrDefault(exact, List.of());
            StringBuilder det = new StringBuilder();
            det.append(item(exact)).append(" 在索引里有 ").append(cs.size()).append(" 条候选：");
            for (Candidate c : cs) {
                Recipe<?> r = recipeOf(c);
                int[] d = GhostDebug.shapedDims(r);
                det.append("\n  ").append(ghost(c)).append(c.single ? " 单格" : " 工作台")
                        .append(" 产物=").append(resultDesc(c))
                        .append(d == null ? "（无 pattern）" : " pattern=" + d[0] + "x" + d[1])
                        .append(" 投料格=").append(c.ingredients.size())
                        .append(" 摆法=").append(java.util.Arrays.toString(planLayoutNames(r, c)));
            }
            // 再按"根目标"的严格产物比对走一遍，日志里能直接看到组件筛选会留下几条
            List<Candidate> strict = pick(idx.get(exact), exact, new ItemStack(exact));
            det.append("\n  按产物+DataComponents 严格筛选后剩 ").append(strict.size()).append(" 条");
            for (Candidate c : strict) det.append("\n    [OK] ").append(ghost(c));
            GhostDebug.line("      /ghostlog find " + det);
            return item(exact) + "：索引 " + cs.size() + " 条，严格筛选后 " + strict.size()
                    + " 条（明细已写日志）";
        }

        List<Item> fuzzy = new ArrayList<>();
        for (Item k : idx.keySet()) {
            if (item(k).contains(kw)) { fuzzy.add(k); if (fuzzy.size() >= 12) break; }
        }
        if (fuzzy.isEmpty()) {
            return "索引里没有产物名含 \"" + kw + "\" 的配方（共 " + idx.size() + " 种产物）";
        }
        StringBuilder det = new StringBuilder("子串匹配 \"" + kw + "\" 命中 " + fuzzy.size() + " 种产物：");
        for (Item k : fuzzy) det.append("\n  ").append(item(k)).append(" -> ")
                .append(idx.getOrDefault(k, List.of()).size()).append(" 条");
        GhostDebug.line("      /ghostlog find " + det);
        return "命中 " + fuzzy.size() + " 种产物（首个 " + item(fuzzy.get(0)) + "），明细已写日志";
    }

    /** 仅用于诊断：这条配方按我们的 layouts() 会构造出哪几种摆法。 */
    private static String[] planLayoutNames(Recipe<?> raw, Candidate c) {
        try {
            List<ItemStack> grid = new ArrayList<>();
            for (Ingredient g : c.ingredients) {
                if (c.single && !grid.isEmpty()) break;
                if (g == null || g.isEmpty()) { grid.add(ItemStack.EMPTY); continue; }
                ItemStack s = sample(g);
                grid.add(s == null ? ItemStack.EMPTY : s);
            }
            List<Layout> ls = layouts(raw, c, grid);
            String[] names = new String[ls.size()];
            for (int i = 0; i < ls.size(); i++) names[i] = ls.get(i).name();
            return names;
        } catch (Throwable t) {
            return new String[]{"读取失败"};
        }
    }


    private static void buildIndex(Level level, HolderLookup.Provider provider, Map<Item, List<Candidate>> map) {
        RecipeManager mgr = level.getRecipeManager();
        idxScanned = idxKept = idxNoResult = idxOther = idxShapeless = 0;
        idxTruncated = false;
        // 只扫这两种：熔炉/高炉/铁砧/附魔一律不碰（下一批的活），也没必要看一眼。
        // 必须分开调用：getAllRecipesFor 的泛型是 <I,T extends Recipe<I>>，
        // 用三元表达式挑 RecipeType 会把类型参数擦成交集，编译器推不出 I。
        List<RecipeHolder<CraftingRecipe>> crafting;
        try {
            crafting = mgr.getAllRecipesFor(RecipeType.CRAFTING);
        } catch (Throwable t) {
            crafting = null;
            GhostDebug.throwAt("getAllRecipesFor CRAFTING", t);
        }
        scanInto(map, crafting, provider, false, "工作台");

        if (!idxTruncated) {
            List<RecipeHolder<StonecutterRecipe>> cutting;
            try {
                cutting = mgr.getAllRecipesFor(RecipeType.STONECUTTING);
            } catch (Throwable t) {
                cutting = null;
                GhostDebug.throwAt("getAllRecipesFor STONECUTTING", t);
            }
            scanInto(map, cutting, provider, true, "石切台");
        }
    }

    /** 把一组配方扫进索引。列表为 null（该类型取不到）时只留痕，不影响别的类型。 */
    private static void scanInto(Map<Item, List<Candidate>> map, List<? extends RecipeHolder<?>> holders,
                                 HolderLookup.Provider provider, boolean stonecut, String label) {
        if (holders == null) {
            GhostDebug.line("      扫描 " + label + " 配方：取列表失败（这一类全部跳过）");
            return;
        }
        if (idxScanned >= SR.CRAFT_INDEX_SCAN_CAP) { idxTruncated = true; return; }
        GhostDebug.line("      扫描 " + label + " 配方：" + holders.size() + " 条");
        for (RecipeHolder<?> h : holders) {
            if (idxScanned++ >= SR.CRAFT_INDEX_SCAN_CAP) { idxTruncated = true; return; }
            Candidate c = toCandidate(h, provider, stonecut);
            if (c == null) continue;
            if (!stonecut && h.value() instanceof ShapelessRecipe) idxShapeless++;
            map.computeIfAbsent(c.result.getItem(), k -> new ArrayList<>()).add(c);
            idxKept++;
        }
    }

    /** 单条配方 -> 候选。返回 null = 不收（并已经计进对应统计）。 */
    private static Candidate toCandidate(RecipeHolder<?> holder, HolderLookup.Provider provider, boolean stonecut) {
        try {
            Recipe<?> recipe = holder == null ? null : holder.value();
            if (recipe == null) { idxOther++; return null; }
            if (recipe.isSpecial()) { idxOther++; return null; }   // 仅展示用的"特殊"配方
            // 产物严格提取：NeoForge 1.21.1 的唯一入口就是 getResultItem(HolderLookup.Provider)。
            // 取不到 / 空 / 空气 —— 一律丢弃，绝不"当成产物未知全部放行"，那正是上一版的错。
            ItemStack res = recipe.getResultItem(provider);
            if (res == null || res.isEmpty() || res.getItem() == net.minecraft.world.item.Items.AIR) {
                idxNoResult++;
                // 大整合包里这类配方可能成千上万，只挑前几条进日志，别把配额吃光
                if (idxNoResult <= 8) {
                    GhostDebug.line("      [!!] 产物取不到，丢弃候选 " + GhostDebug.rl(holder.id())
                            + " 类=" + recipe.getClass().getSimpleName()
                            + " getResultItem 返回=" + (res == null ? "null" : GhostDebug.st(res)));
                } else if (idxNoResult == 9) {
                    GhostDebug.line("      [!!] 产物取不到的配方已有 9 条以上，后续只计入统计不再逐条打印");
                }
                return null;
            }
            NonNullList<Ingredient> ings = ingredientsOf(recipe);
            if (ings == null || ings.isEmpty()) { idxOther++; return null; }
            if (stonecut && ings.size() != 1) { idxOther++; return null; }  // 石切必须单格输入
            return new Candidate(holder, stonecut, res, ings);
        } catch (Throwable t) {
            idxOther++;
            GhostDebug.throwAt("读配方 " + GhostDebug.rl(holder == null ? null : holder.id()), t);
            return null;
        }
    }

    /** 链内前置（只按物品匹配，不比组件）。 */
    private static List<Candidate> recipesFor(Host host, Item want) {
        return recipesFor(host, want, null);
    }

    /**
     * 索引里有没有<b>任何</b>产出这个物品的配方（只按物品，不看 DataComponents）。
     *
     * <p>r14 第 1 点的快速通道就靠它：一次哈希查表，微秒级。空表 ⇒ 这东西压根不是
     * 工作台/石切台的产物（去皮原木要玩家拿斧子右键去皮，熔炉烧的、铁砧打的同理），
     * 规划立刻判 {@link Reason#NO_RECIPE} 收场 —— <b>不查库存、不展开递归、不消耗预算</b>。
     * 这一条把「去皮深色橡木原木白烧一整份预算、然后卡死整场会话」从根上堵住了。</p>
     */
    public static boolean hasAnyRecipe(Host host, Item item) {
        return !rawRecipesFor(host, item).isEmpty();
    }

    /** 同上，返回候选清单（日志要报「几条候选」）。 */
    private static List<Candidate> rawRecipesFor(Host host, Item want) {
        try {
            List<Candidate> raw = index(host.level(), host.player().registryAccess()).get(want);
            return raw == null ? List.of() : raw;
        } catch (Throwable t) {
            GhostDebug.throwAt("查配方索引 " + item(want), t);
            return List.of();
        }
    }

    /**
     * 查"能产出 {@code want} 的配方"。
     *
     * @param target 根目标的原始 ItemStack（带 DataComponents）；{@code null} = 链内前置，只按物品比
     */
    private static List<Candidate> recipesFor(Host host, Item want, ItemStack target) {
        return pick(index(host).get(want), want, target);
    }

    /**
     * 从索引里的一组候选挑出真正可用的。
     *
     * <p><b>r7 口径（用户定）：默认只比物品，不比 DataComponents。</b>
     * 上一版按组件严格筛选，结果 {@code assemble} 出来的栈带的是配方默认的<b>空组件</b>，
     * 与投影清单里那个带组件的样品怎么比都不等 → 候选全被筛光 → 「无法合成」。
     * 现在默认全部候选都算数，只是<b>组件一致的排在前面</b>（优先拿对版本），差异一律写日志不拦人；
     * 想看严格的原样就 {@code /ghostlog strict on}：那时组件不一致的候选直接不出现在清单里。</p>
     */
    private static List<Candidate> pick(List<Candidate> raw, Item want, ItemStack target) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<Candidate> out = new ArrayList<>(raw);
        out.sort(Comparator.comparingInt(cc -> cc.single ? 1 : 0));
        if (target == null || target.isEmpty()) return out;

        List<Candidate> exact = new ArrayList<>(), loose = new ArrayList<>();
        for (Candidate c : out) {
            if (componentsMatch(c.result, target)) exact.add(c); else loose.add(c);
        }
        if (loose.isEmpty()) return out;
        if (GhostDebug.on()) {
            GhostDebug.line("      产物组件比对 " + item(want) + "：" + raw.size() + " 条候选中，"
                    + "物品+组件全一致 " + exact.size() + " 条，组件不一致 " + loose.size() + " 条（"
                    + (strictProductComponents ? "严格模式：不一致的候选将被剔除" : "宽松模式（默认）：只记日志，照常可用") + "）");
            for (Candidate c : loose) {
                if (!GhostDebug.once("comp|" + c.id())) continue;
                GhostDebug.line("        [!!] " + ghost(c) + " 产物组件与目标不同："
                        + GhostDebug.patchDiff(c.result, target));
            }
        }
        if (strictProductComponents) {
            // 调试档：老口径。全都不一致就直接查不到配方，让日志说话。
            if (exact.isEmpty()) {
                GhostDebug.detail(item(want) + " 严格模式：没有产物组件与目标一致的配方（共 "
                        + out.size() + " 条候选，差异见日志）");
                return List.of();
            }
            return exact;
        }
        if (!exact.isEmpty()) return exact;
        GhostDebug.line("      [OK] " + item(want) + " 没有产物组件完全一致的配方，"
                + "按物品匹配放行（" + out.size() + " 条）—— 这是默认口径，不算异常");
        return out;
    }

    /**
     * 两个栈的"物品 + DataComponents"是否等价（数量不算）。
     *
     * <p>用 {@link ItemStack#isSameItemSameComponents} 作主判据，任何异常退化成
     * 逐字段比补丁字符串 —— 比对本身绝不允许把玩法带崩。</p>
     */
    private static boolean componentsMatch(ItemStack a, ItemStack b) {
        try {
            if (a == null || b == null) return false;
            if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
            if (a.getItem() != b.getItem()) return false;
            return ItemStack.isSameItemSameComponents(a, b);
        } catch (Throwable t) {
            try {
                return String.valueOf(a.getComponentsPatch()).equals(String.valueOf(b.getComponentsPatch()));
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    /** 资源包重载后调用（配方表变了，旧缓存会骗人）。 */
    public static void invalidateRecipeCache() {
        INDEX = null;
        indexBuilt = false;
        indexHealthy = false;
        FIRST_OK.clear();
        GhostDebug.resetBudget();
        GhostDebug.clearOnce();      // 新会话：完整现场要能再看一遍
        GhostDebug.clearDetail();
    }

    // ==================== 小工具 ====================

    private static Ingredient firstIngredient(Candidate c) {
        try {
            for (Ingredient g : c.ingredients) if (g != null && !g.isEmpty()) return g;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Ingredient at(NonNullList<Ingredient> list, int i) {
        try {
            return i < list.size() ? list.get(i) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 找到负责某个 {@link Ingredient} 的子节点。
     *
     * <p>先按 ingredient 身份/相等匹配；匹配不上再退一步：哪个子节点的代表物品能被这个
     * ingredient 接受（{@code Ingredient.test}），就用它。后者覆盖两种真实情况——
     * ①同一配方里两个不同的自定义 ingredient 恰好解析到同一个代表物品（NeoForge 的
     * CompoundIngredient 的 equals 可能是身份比较）；②配方复用了等价但不同实例的 ingredient。</p>
     */
    private static Node childFor(Node n, Ingredient g) {
        for (int i = 0; i < n.childKeys.size(); i++) {
            Ingredient k = n.childKeys.get(i);
            if (k == g || safeEquals(k, g)) return n.children.get(i);
        }
        for (int i = 0; i < n.children.size(); i++) {
            try {
                if (g.test(new ItemStack(n.children.get(i).item))) return n.children.get(i);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean safeEquals(Ingredient a, Ingredient b) {
        try {
            return a != null && b != null && a.equals(b);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 找一种能通过配方自己 {@code matches()} 的摆法的工作已交给 {@link #layouts}（带尺寸日志）。
     * 这里只留单格输入。
     */
    private static RecipeInput buildSingle(List<ItemStack> items) {
        for (ItemStack st : items) if (st != null && !st.isEmpty()) return new SingleRecipeInput(st.copyWithCount(1));
        return null;
    }

    private static Recipe<?> recipeOf(Candidate c) {
        try {
            return c.holder.value();
        } catch (Throwable t) {
            GhostDebug.throwAt("holder.value() 配方=" + GhostDebug.rl(c == null ? null : c.id()), t);
            return null;
        }
    }

    private static ItemStack resultOf(Recipe<?> r, HolderLookup.Provider p) {
        try {
            ItemStack st = r.getResultItem(p);
            return st == null ? ItemStack.EMPTY : st;
        } catch (Throwable t) {
            GhostDebug.throwAt("getResultItem", t);
            return ItemStack.EMPTY;
        }
    }

    /** 索引里某个产物的可读描述（日志用）。 */
    private static String resultDesc(Candidate c) {
        try {
            return GhostDebug.st(c.result);
        } catch (Throwable t) {
            return "?";
        }
    }

    @SuppressWarnings("unchecked")
    private static NonNullList<Ingredient> ingredientsOf(Recipe<?> r) {
        try {
            return (NonNullList<Ingredient>) r.getIngredients();
        } catch (Throwable t) {
            GhostDebug.throwAt("getIngredients", t);
            return null;
        }
    }

    /** matches 复核；输入类型不对（例如石切配方收到 3x3）一律当不匹配，但要留痕。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean matchesSafe(Recipe<?> recipe, RecipeInput input, Level level) {
        try {
            return recipe != null && ((Recipe) recipe).matches(input, level);
        } catch (ClassCastException cce) {
            GhostDebug.throwAt("matches 抛 ClassCastException（多半 input 类型与配方不匹配） recipe="
                    + recipe.getClass().getName() + " input=" + input.getClass().getName(), cce);
            return false;
        } catch (Throwable t) {
            GhostDebug.throwAt("matches 抛异常 recipe=" + (recipe == null ? "null" : recipe.getClass().getName()), t);
            return false;
        }
    }

    /** 原始返回值直接交给日志去解释（不吞类型问题）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object remainingOf(Recipe<?> recipe, RecipeInput input) {
        try {
            return ((Recipe) recipe).getRemainingItems(input);
        } catch (Throwable t) {
            GhostDebug.throwAt("getRemainingItems 抛异常 recipe=" + recipe.getClass().getName(), t);
            return null;
        }
    }

    /** 回吐物是否非空：非空说明配方会额外吐东西（桶、载体），幽灵合成不做。异常当"有回吐"处理。 */
    private static boolean remainingNonEmpty(Object o) {
        if (o == null) return true;
        if (!(o instanceof NonNullList)) {
            GhostDebug.line("回吐物返回了意外的类型：" + o.getClass().getName() + "，按有回吐处理");
            return true;
        }
        try {
            for (Object x : (NonNullList<?>) o) if (x instanceof ItemStack st && !st.isEmpty()) return true;
            return false;
        } catch (Throwable t) {
            GhostDebug.throwAt("读回吐物列表", t);
            return true;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ItemStack assembleSafe(Recipe<?> recipe, RecipeInput input, HolderLookup.Provider p) {
        try {
            ItemStack st = ((Recipe) recipe).assemble(input, p);
            return st == null ? ItemStack.EMPTY : st;
        } catch (Throwable t) {
            GhostDebug.throwAt("assemble 抛异常 recipe=" + (recipe == null ? "null" : recipe.getClass().getName())
                    + " input=" + (input == null ? "null" : input.getClass().getName()), t);
            return ItemStack.EMPTY;
        }
    }

    /** 诊断用。 */
    public static String describe(Job job) {
        if (job == null) return "无合成任务";
        if (job.root == null) return "未成树";
        return job.root.kind == Kind.MAKE ? job.root.candidate.id().toString() : "直接拿取";
    }
}
