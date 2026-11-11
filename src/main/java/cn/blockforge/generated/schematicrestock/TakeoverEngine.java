package cn.blockforge.generated.schematicrestock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 补料接管引擎（服务端）。
 *
 * <h3>r14 两阶段执行（用户实测「755 方块 / 29 种材料只搬了 4 种就卡死」的正解）</h3>
 * <p>旧版是<b>一种物品一条龙</b>：搬运→卡合成→再下一种。第 5 种撞上死循环，
 * 后面还没轮到搬的全饿死了。现在彻底分开：</p>
 * <ul>
 *   <li><b>阶段 A（搬运）</b>：把<b>所有</b>缺口里箱中现成的物品全部搬完。
 *       一种物品一个 tick 只走一步，没搬完就排到队尾（轮转），谁都饿不死；
 *       取料只摸 {@link ChestIndex} 里有货的那几格，成本与箱子数量无关。</li>
 *   <li><b>阶段 B（合成）</b>：剩下的缺口（<b>真正需要合成</b>的那些）才进递归规划。
 *       开工前先用配方索引过滤一遍，查无配方（去皮原木这类要工具加工的）<b>当场判掉、一微秒都不多花</b>；
 *       进了队列的任务轮转推进，同一手连续改期满 {@code SR.MAX_STEP_RETRIES} 次就跳过。</li>
 * </ul>
 * <p>这样即便阶段 B 全都算不动，玩家也<b>已经拿到</b>阶段 A 搬好的全部现成物资。</p>
 *
 * <h3>核心纪律：先模拟，后执行</h3>
 * <p>搬运是 ①{@code extractItem(slot, n, true)} 模拟拿 → ②{@link StorageTargets#find}
 * 按 A/B/C/D 优先级模拟找存放处 → 两个都成立才真实提取 + 真实放入；
 * 合成（{@link GhostCraftEngine}）是同一套纪律的递归版。全程不可能掉地上。</p>
 *
 * <h3>会话寿命（r14）</h3>
 * <p>硬上限 {@code SR.SESSION_TIMEOUT_MS}（120 秒），外加
 * <b>连续 {@code SR.STALL_EXIT_MS}（10 秒）零进展就提前收场</b> ——
 * 进展 = 「投进存放处的件数 + 消耗掉的原料件数 + 已结清的种类」之和变大。
 * 真卡死 10 秒就退出，正常的大工程有整整两分钟。</p>
 *
 * <p>其余策划约束保留：只翻标记过的箱子、算不出来的物品直接跳过不重试。</p>
 *
 * <p><b>r21 起补料幂等</b>：建任务时 {@code need = 原理图缺口 − 身上已有件数}
 * （身上口径是深查：36 格 + 副手 + 潜影盒/精妙背包内部 + 饰品栏挂包）。
 * 同一批材料搬过一次还在身上，第二次 V+M 一种都不会多拿。</p>
 *
 * <h3>r22：阶段 A 现货定罪的三条铁律（去皮原木/方解石误杀案的修正）</h3>
 * <ol>
 *   <li><b>阶段 A 零配方参与</b>：搬运队列（{@code runMove} → {@link ChestIndex#take}）
 *       任何时候都不查配方——箱里有现货就只许去拿。旧版的漏洞不在"配方拦了搬运"，而在
 *       <b>缓存冒判无货</b>：索引没记上这种（建索引撞时间闸门、料格被瞬时读失败作废），
 *       阶段 A 直接判"没货"移交阶段 B，无配方的去皮原木/方解石当场被贴成
 *       「查无工作台配方」——玩家看到的是"被配方过滤踢出搬运"。</li>
 *   <li><b>"箱里没货"只许实物盖章</b>：索引查无此货时，先做一次全箱阵物理复扫
 *       （{@link ChestIndex#sweepRecount}，每品种至多一次、槽位有封顶）。
 *       扫到货 → 缓存就地修好、排回搬运队列继续搬；实扫也零 → 才允许进阶段 B。</li>
 *   <li><b>配方闸门只拦合成</b>：{@code NO_RECIPE} 的判死点在且仅在
 *       {@code startCraftPhase}（阶段 B 门口）与 {@code GhostCraftEngine.plan} 的第一步。
 *       "箱里明明有货却一件取不出"（只读格口/拒提取容器）单独记 {@code CHEST_LOCKED}，
 *       不再冒领「查无配方」。</li>
 * </ol>
 *
 * <h3>r23：与自动建造正式接棒（"有货不拿"死锁的正解）</h3>
 * <p>旧版客户端把「建造会话还活着」当成 V+M 的免死金牌直接回绝——而卡在缺料上的施工
 * 会话<b>永远活着</b>（延后队列每 5 秒重试一轮，两小时超时前不收摊），于是
 * 「缺料 → V+M 补料 → 接着建」这个闭环在补料这一环被焊死：箱里石头原木堆成山，
 * 一件不进包，L 键缺口也永远原样挂着。现在 {@link #handle} 收到补料包先
 * {@code AutoBuildEngine.holdBuildForRestock}——服务端把 RUNNING 的施工按停记档，
 * 背包整段时间归本会话独用；会话收场（干完/超时/出错）{@code releaseBuildAfterRestock}
 * 自动续工并清空缺料重试缓存。"身上已有"照旧只认玩家背包<b>实物</b>（深口径），
 * 延后队列/幽灵缓冲一件都不算——只要箱里有货、身上没有，就硬拿。</p>
 */
public final class TakeoverEngine {

    // r21 Bug1：「身上已有件数扣不扣」的开关已删除——扣。这是用户拍板的幂等口径：
    // 需要拿取的数量 = 原理图缺口 − 玩家身上已有的数量（含精妙背包/潜影盒内部），
    // 扣完 ≤0 一种都不去箱子里拿。旧版靠 /ghostlog carried 来回切，正是重复补料的病根之一。

    // ---------------- 会话表 ----------------

    private static final AtomicLong SESSION_IDS = new AtomicLong(1);
    /** playerUUID -> 正在推进的会话。一个玩家同时只有一个会话。 */
    private static final Map<UUID, Session> SESSIONS = new LinkedHashMap<>();

    private TakeoverEngine() {}

    // ---------------- 对外入口 ----------------

    public static void handle(Object playerObj, Packets.StartTakeover p) {
        if (!(playerObj instanceof ServerPlayer sp)) return;
        UUID id = sp.getUUID();
        // r23「有货不拿」主修：建造进行中不再是按不动 V+M 的理由。收到补料包的
        // 第一件事是把该玩家的施工会话按暂停（只有 RUNNING 会被按；手动暂停的原样），
        // 本会话收场时自动续工（见 AutoBuildEngine.holdBuildForRestock/release）。
        // 两个引擎共用一只背包，顺序必须是一个搬完另一个再动——现在是显式交接棒了。
        // 注意：先在补料锁<b>外</b>拿建造锁做交接，避免与 handlePlan 的锁序互相嵌套。
        boolean held = AutoBuildEngine.holdBuildForRestock(id);
        synchronized (SESSIONS) {
            Session old = SESSIONS.get(id);
            if (old != null && !old.expired()) return; // 防连点：上一轮还在跑
            GhostCraftEngine.invalidateRecipeCache();   // 新会话：配方缓存重来（防资源包重载后骗人）
            Session ns = new Session(sp, p.entries());
            ns.buildOnHold = held;
            SESSIONS.put(id, ns);
        }
    }

    /** 客户端 HUD 用：本服务端是否已有该玩家的会话。 */
    public static boolean isBusy(UUID id) {
        synchronized (SESSIONS) {
            return SESSIONS.containsKey(id);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        synchronized (SESSIONS) {
            if (SESSIONS.isEmpty()) return;
            Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
            while (it.hasNext()) {
                Session s = it.next().getValue();
                if (s.player.isRemoved()) { it.remove(); s.wipe(); continue; }
                s.tick++;
                boolean finished;
                try {
                    finished = s.advance();
                } catch (Throwable t) {
                    finished = true;
                    s.result.error = t.getClass().getSimpleName();
                    GhostDebug.throwAt("接管会话 advance", t);
                }
                if (s.shouldReport()) PacketDistributor.sendToPlayer(s.player, s.progress());
                if (finished || s.expired()) {
                    s.abandonCraft();
                    s.flushLeftovers();
                    // 收尾再补一包，确保客户端拿到最终投料账本
                    PacketDistributor.sendToPlayer(s.player, s.progress());
                    Packets.TakeoverResult payload = s.result.toPayload(s.key, s.placedList());
                    PacketDistributor.sendToPlayer(s.player, payload);
                    it.remove();
                    // r28（发布前防漏）：正常收场也与掉线/换维度同一条纪律——就地清空会话集合，
                    // 别把"释放整箱阵引用"这件事全押在 GC 恰好没人攥着 Session 上。
                    s.wipe();
                    // r23：开场时是它按停的施工，收场必须原路续工（手动暂停的不碰）。
                    AutoBuildEngine.releaseBuildAfterRestock(s.player.getUUID());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            synchronized (SESSIONS) {
                Session s = SESSIONS.remove(sp.getUUID());
                // r28：退服这一路除了撤幽灵合成的挂账，还要把会话自己的大集合就地清空
                // （搬队列/合成队列/账本/索引都攥着 ItemStack 与 BlockPos 列表，
                //   服主长期开服、玩家频繁进出时不能只指望 GC 恰好没有别的引用攥着 Session）。
                if (s != null) { s.abandonCraft(); s.wipe(); }
            }
        }
    }

    /** 换维度/重载时别拿着旧世界的 handler。 */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            synchronized (SESSIONS) {
                Session s = SESSIONS.remove(sp.getUUID());
                if (s != null) { s.abandonCraft(); s.wipe(); }   // r28：同上，换维度也彻底清一遍
            }
        }
    }

    /** 会话走的是哪一阶段（HUD 与日志都显示，玩家一眼知道现在在搬运还是在合成）。 */
    enum Phase { MOVE, CRAFT }

    // ---------------- 会话 ----------------

    private static final class Session implements GhostCraftEngine.Host {
        final ServerPlayer player;
        final Result result = new Result();
        /** 阶段 A 的搬运队列（轮转：一步没搬完的排到队尾）。 */
        final ArrayDeque<Task> moveQueue = new ArrayDeque<>();
        /** 阶段 A 榨干现货仍然缺的那些 —— 阶段 B 的输入。 */
        final List<Task> toCraft = new ArrayList<>();
        /** 阶段 B 的合成队列（同样轮转，谁算不动都不挡住别人）。 */
        final ArrayDeque<Task> craftQueue = new ArrayDeque<>();
        final List<BlockPos> marks;
        /** 输出箱（溢出箱）标记，会话开始时定格；只进不出，绝不参与取料。 */
        final List<BlockPos> outMarks;
        final long key = SESSION_IDS.getAndIncrement();
        final long startedAt = System.currentTimeMillis();
        final long deadline = startedAt + SR.SESSION_TIMEOUT_MS;

        Phase phase = Phase.MOVE;
        /** r23：本会话开场时是否把该玩家的自动建造按了暂停（收场必须原路续回去，见 handle/onServerTick）。 */
        boolean buildOnHold;
        /** 标记箱位置索引：阶段 A 开头建一次，阶段 B 开头重建一次（箱子已被搬掉一批）。 */
        ChestIndex index;
        /** 本次会话的件数额度（按缺口动态放大，见 {@link #sessionCapFor}）。 */
        final int itemCap;

        int totalTypes;
        int doneTypes;
        long tick;
        long lastReportTick = -1;
        final List<ItemStack> limbo = new ArrayList<>(); // 极端情况：取出后没安放下，逐 tick 再试
        final Map<String, Integer> placed = new HashMap<>(); // 投料账本：品种 -> 真实投进存放处的件数
        boolean placedDirty;
        int craftSteps;
        /** 当前正在推进一手的合成任务（日志与进度用）。 */
        Task activeCraft;

        /**
         * 搬运队列还排着的件数（r7 Bug① 的按件锁定，不是整种一票否决）。
         * 阶段 B 期间它已经空了，锁定量只剩 {@link #planBookings}。
         */
        final Map<Item, Integer> reserved = new HashMap<>();
        /** 已展开的合成任务各自订走的原料（跨任务互斥；任务结清就整笔撤销）。 */
        final Map<String, Map<Item, Integer>> planBookings = new HashMap<>();

        // ---- 零进展判定（120 秒硬上限之外的快速退出） ----

        private long lastProgressAt = System.currentTimeMillis();
        private int progressMark;

        Session(ServerPlayer sp, List<Packets.Entry> entries) {
            this.player = sp;
            this.marks = MarkedChestStore.marksIn(sp, sp.level());
            this.outMarks = MarkedChestStore.outputsIn(sp, sp.level());

            // r28：一个材料箱都没标 —— 拿配置里的兜底搜索半径（restock.searchRadius）在玩家脚下
            // 现扫一圈容器方块补进本会话的取料名单（只这一次，不写回永久标记名单）。
            // 标了箱的玩家永远走不到这里，这道闸门对他们毫无影响；半径 0 = 关掉兜底。
            if (marks.isEmpty()) {
                int radius = ModConfig.restockSearchRadius();
                if (radius > 0) {
                    List<BlockPos> borrowed = borrowNearbyContainers(sp, radius);
                    if (!borrowed.isEmpty()) {
                        marks.addAll(borrowed);
                        result.autoBorrowed = borrowed.size();
                        GhostDebug.line("══ 未标记材料箱，本次兜底借用玩家半径 " + radius
                                + " 格内实测到的容器方块 " + borrowed.size() + " 个（不写回标记名单；"
                                + "建议用标记签把储物墙标成材料箱，之后就不走这条兜底了）");
                    }
                }
            }

            // ① 按物品 id 合并：投影重算偶尔给出同种多行，合成一条任务，
            //    否则会出现两个任务抢同一批库存、预留额也只记一半的情况。
            LinkedHashMap<String, Integer> merged = new LinkedHashMap<>();
            List<Packets.Entry> unknown = new ArrayList<>();   // 物品 id 认不出来：记缺口，不猜
            List<Packets.Entry> overCap = new ArrayList<>();    // 超出种类上限：挂账并告知
            int kinds = 0;
            long gapPieces = 0;
            for (Packets.Entry e : entries) {
                if (e == null || e.id() == null || e.count() <= 0) continue;
                if (resolve(e.id()) == null) { unknown.add(e); continue; }
                gapPieces += e.count();
                Integer prev = merged.get(e.id());
                if (prev == null) {
                    if (kinds >= SR.MAX_TYPES) { overCap.add(e); result.capped = true; continue; }
                    merged.put(e.id(), e.count());
                    kinds++;
                } else {
                    merged.put(e.id(), prev + e.count());       // 同一物品：件数相加，不占新名额
                }
            }
            // ② 建任务 + 记按件锁定的预留额
            // r21 Bug1（幂等补料）：need = 原理图缺口 − 玩家身上已有的件数（含副手、
            // 潜影盒/精妙背包内部、饰品栏挂包）。扣完 ≤0 就不建任务——上一次 V+M 已经
            // 搬过、东西还在背包里的材料，绝不再去箱子第二趟。身上件数由服务端拿真实
            // 背包现数，断线重连、换世界都骗不了它。
            // （客户端发包口径同步改成了"原始缺口、不扣账本"，见 ClientEvents.tryTakeover——
            //   账本记的那几件本来就在身上，这里已经按人扣过，两头各扣一遍才是旧的 Bug。）
            long netGap = 0;
            int alreadyCarried = 0;
            for (Map.Entry<String, Integer> e : merged.entrySet()) {
                String id = e.getKey();
                Item item = resolve(id);
                if (item == null) continue;                     // ①里已拦，这里纯防御
                ItemStack probe = new ItemStack(item);
                int gap = e.getValue();
                int own = StorageTargets.countCarried(sp, probe);   // 深口径：含容器内部与挂包
                int need = Math.max(0, gap - Math.min(own, gap));
                if (need <= 0) {
                    alreadyCarried++;
                    if (GhostDebug.on()) {
                        GhostDebug.line("      " + id + "：缺口 " + gap + " 件，身上已有 " + own
                                + " 件 → 一种都不取（r21 幂等口径；这条与配方无关）");
                    }
                    continue;
                }
                if (own > 0 && GhostDebug.on()) {
                    GhostDebug.line("      " + id + "：缺口 " + gap + " 件，身上已有 " + own
                            + " 件，再取 " + need + " 件");
                }
                netGap += need;
                moveQueue.add(new Task(id, probe, need));
                reserved.merge(item, need, Integer::sum);
            }
            // 件数额度按"扣完身上之后的净缺口"算：身上本来就有的不该占额度
            this.itemCap = sessionCapFor(netGap);
            for (Packets.Entry e : unknown) { result.missingTypes++; result.remaining.add(e); }
            for (Packets.Entry e : overCap) { result.missingTypes++; result.remaining.add(e); }
            this.totalTypes = moveQueue.size();
            GhostDebug.clearOnce();
            GhostDebug.line("══ 会话 #" + key + "：受理 " + moveQueue.size() + " 种 / 净缺口 "
                    + netGap + " 件（原始 " + gapPieces + " 件，已全在身上 " + alreadyCarried
                    + " 种），认不出 " + unknown.size() + " 种，超 " + SR.MAX_TYPES
                    + " 种上限 " + overCap.size() + " 种；材料箱 " + marks.size() + " 个，输出箱 "
                    + outMarks.size() + " 个");
            GhostDebug.line("      额度：件数上限 " + itemCap + " 件（= max(" + SR.MAX_ITEMS
                    + ", 净缺口 " + netGap + "×" + SR.ITEM_CAP_PER_GAP_PIECE + ")），会话寿命 "
                    + (SR.SESSION_TIMEOUT_MS / 1000L) + " 秒，连续 "
                    + (SR.STALL_EXIT_MS / 1000L) + " 秒零进展提前退出；"
                    + "预留账本（按件）" + reserved.size() + " 种 / " + reservedTotal() + " 件");
        }

        /**
         * 会话件数额度：<b>按净缺口动态算</b>（用户口径：拉高到 10000，或者按缺口动态计算上限）。
         *
         * <p>取 {@code max(SR.MAX_ITEMS, 净缺口 × ITEM_CAP_PER_GAP_PIECE)}。乘系数是因为合成
         * 按比例额外吃原料（1 个栅栏吃 4 块木板，木板又吃原木），只按缺口数封顶会在阶段 B 被掐死。
         * r21：净缺口 = 原理图缺口 − 玩家身上已有的件数。</p>
         */
        private static int sessionCapFor(long gapPieces) {
            long dyn = gapPieces * (long) SR.ITEM_CAP_PER_GAP_PIECE;
            long cap = Math.max((long) SR.MAX_ITEMS, dyn);
            return (int) Math.min(cap, Integer.MAX_VALUE / 4L);
        }

        /** 预留账本合计件数（日志用）。 */
        int reservedTotal() {
            int n = 0;
            for (int v : reserved.values()) n += v;
            return n;
        }

        /**
         * <b>r28（发布前防漏）</b>：会话收场时把它持有的全部集合就地清空。
         *
         * <p>正常收场（{@code onServerTick} 的 finished 分支）里 Session 会从 {@code SESSIONS}
         * 摘掉，此后理论上只剩这条调用栈上的局部引用；但掉线/换维度/异常路径多，
         * 长期开服最怕的就是"某个回调/挂账还攥着一份 Session"。清空之后即便真有一处漏网，
         * 挂住的也只是一个空壳对象，而不是整箱阵的 {@code ItemStack} / {@code BlockPos} 列表。
         * 位置索引（{@link ChestIndex}）里还缓存着每个料格的物品引用，一并交还。</p>
         */
        void wipe() {
            // 先把合成任务挂着的规划器对象（里面还有配料格、原料快照）松掉，再清队列本体
            for (Task t : moveQueue) t.job = null;
            for (Task t : toCraft) t.job = null;
            for (Task t : craftQueue) t.job = null;
            moveQueue.clear();
            toCraft.clear();
            craftQueue.clear();
            limbo.clear();
            placed.clear();
            reserved.clear();
            planBookings.clear();
            marks.clear();
            outMarks.clear();
            activeCraft = null;
            if (index != null) {
                index.dispose();
                index = null;
            }
        }

        /** 销掉某个品种的锁定额度（该品种已交给自己处理，或已彻底跳过）。 */
        void release(Item item, int n) {
            if (item == null || n <= 0) return;
            reserved.merge(item, -n, Integer::sum);
            if (reserved.getOrDefault(item, 0) <= 0) reserved.remove(item);
        }

        boolean expired() {
            return System.currentTimeMillis() > deadline;
        }

        /** 已完成的"工作量"指标：投料件数 + 耗料件数 + 结清种类，任一变大就算有进展。 */
        private int progressScore() {
            return result.written + result.craftConsumed + doneTypes * 1000;
        }

        /**
         * 零进展判定（r14）。
         *
         * @return true = 已经连续 {@code SR.STALL_EXIT_MS} 没有任何进展，会话该收场
         */
        private boolean stalledOut() {
            int now = progressScore();
            if (now != progressMark) {
                progressMark = now;
                lastProgressAt = System.currentTimeMillis();
                return false;
            }
            return System.currentTimeMillis() - lastProgressAt >= SR.STALL_EXIT_MS;
        }

        /** @return true 表示会话结束（结果该发出去了） */
        boolean advance() {
            if (!limbo.isEmpty()) reAbsorbLimbo();
            if (index == null) {
                buildIndex("阶段 A 开工");
                if (index.positions().isEmpty()) {   // 一个标记箱都摸不到：全部照实记缺口
                    result.noChests = true;
                    drainRemainingAsMissing("没有可用的标记箱");
                    return true;
                }
            }
            if (!hasSessionRoom()) {
                result.capped = true;
                GhostDebug.line("══ 会话 #" + key + "：已达件数额度 " + itemCap + " 件，剩下的照实记缺口");
                drainRemainingAsMissing("件数额度用尽");
                return true;
            }
            if (stalledOut()) {
                result.stalled = true;
                GhostDebug.line("══ 会话 #" + key + "：连续 " + (SR.STALL_EXIT_MS / 1000L)
                        + " 秒零进展（已投 " + result.written + " 件、合成耗料 " + result.craftConsumed
                        + " 件都没再涨），提前收场");
                drainRemainingAsMissing("零进展");
                return true;
            }

            if (phase == Phase.MOVE) return advanceMove();
            return advanceCraft();
        }

        // ==================== 阶段 A：搬运 ====================

        private boolean advanceMove() {
            Task t = moveQueue.poll();
            if (t == null) {
                // r23：衔接处被实扫捞回过现货的品种结清后，craftQueue 里可能还压着
                // 第一次 startCraftPhase 排好的合成任务——这时候 toCraft 是空的，
                // 旧版直接判"收工"，把那些任务整批遗弃（该合的没合、缺口也没记账）。
                // 先交棒阶段 B，让压队的任务照常轮转，两个队列都真空了才算完。
                if (toCraft.isEmpty() && !craftQueue.isEmpty()) {
                    phase = Phase.CRAFT;
                    GhostDebug.line("══ 阶段 A 收尾：交棒阶段 B，续跑衔接时压队的 " + craftQueue.size() + " 种合成任务");
                    return false;
                }
                if (toCraft.isEmpty()) return craftQueue.isEmpty();     // 真的一种都不剩，收工
                startCraftPhase();
                // r22：实扫捞回了现货 → 阶段没换，继续搬运，绝不能在这里当"干完了"收场
                if (phase == Phase.MOVE) return moveQueue.isEmpty() && craftQueue.isEmpty();
                return craftQueue.isEmpty();            // 全被判掉就直接结束，否则下一 tick 进阶段 B
            }
            // 这一种归自己处理了：它的锁定额度整个释放，剩下的库存随便用
            if (!t.lockReleased) {
                t.lockReleased = true;
                release(t.probe.getItem(), t.need);
            }
            runMove(t);
            if (t.moveAgain) {
                t.moveAgain = false;
                moveQueue.addLast(t);                   // 轮转：没搬完的排队尾，<b>绝不挡住别人</b>
                return false;
            }
            if (t.craftDue) {
                toCraft.add(t);                         // 现货榨干仍缺，留给阶段 B
                return false;
            }
            closeTask(t);
            return false;
        }

        /**
         * 一种物品在本 tick 内搬一步。
         *
         * <p>顺序仍然是<b>先模拟后执行</b>：① 索引模拟取 → ② {@link StorageTargets#find} 模拟找存放处
         * → ③ 两边都成立才真实提取 + 真实放入。与旧版的区别只在①：
         * 旧版在这里要把 96 箱 × 27 格全扫一遍才找到那几个料格，现在索引直接给。</p>
         */
        void runMove(Task task) {
            if (task.need <= 0) { task.done = true; return; }
            int capLeft = itemCap - result.written;
            if (capLeft <= 0) {
                result.capped = true;
                closeShort(task, GhostCraftEngine.Reason.LIMIT);
                return;
            }
            int want = (int) Math.min(task.need, Math.min((long) SR.MAX_MOVE_PER_TICK, (long) capLeft));

            // ① 索引模拟取：这一趟能摸到几件（成本 = 有货的那几格，与箱子总数无关）
            ChestIndex.Grab sim = index.take(task.probe.getItem(), want, 0, true, SR.MAX_LOCS_PER_TICK);
            if (sim.taken <= 0) {
                if (sim.outOfBudget) {
                    // 检视上限用完还没找到货：本步没搬完，排队尾，绝不挡住别人
                    task.moveAgain = true;
                    return;
                }
                // r22 铁律①/②：「箱里没货」这句话只许实物说，不许缓存说。索引没记上这一种
                // ≠ 世界没有这一种（建索引撞过时间闸门、料格被瞬时读失败作废，都会让现货
                // 从缓存里蒸发）。缓存说没货就移交阶段 B 的话，无配方的去皮原木/方解石会
                // 在阶段 B 门口被冒领成「查无工作台配方」——本轮要根治的误杀链正是这条。
                // 所以先花一次物理复扫：箱里有货 → 缓存就地修好、排回队尾继续搬；
                // 实扫也确认零 → 才允许把差额移交阶段 B。每种至多扫一次，绝不拖垮会话。
                if (!task.swept) {
                    task.swept = true;
                    int live = index.sweepRecount(task.probe.getItem(), SR.PHASE_A_SWEEP_SLOTS);
                    if (live > 0) {
                        GhostDebug.line("      " + task.id + "：索引缓存查无此货，实扫却在箱里刷出 " + live
                                + " 件 → 缓存已修好，排回队尾继续搬（阶段 A 只看货，不看配方）");
                        task.moveAgain = true;
                        return;
                    }
                    GhostDebug.line("      " + task.id + "：实扫逐格核过，标记箱里当真没有现货 → 差额 "
                            + task.need + " 件移交阶段 B（配方判定从这一步才开始参与）");
                    task.craftDue = task.need > 0;
                    task.done = !task.craftDue;
                    return;
                }
                // 已经"实扫 + 修缓存"走过一轮还是一件取不到：
                //   缓存里有货 = 容器只读/拒提取（货在眼前掏不出来）→ 单记 CHEST_LOCKED，
                //     绝不送阶段 B 冒领「查无配方」，也绝不回流队列（防死循环）；
                //   缓存里也没货 = 期间被人拿走了 → 正常移交阶段 B。
                int cached = index.count(task.probe.getItem());
                if (cached > 0) {
                    GhostDebug.line("      " + task.id + "：箱里有 " + cached + " 件现货但容器一件都吐不出"
                            + "（只读格口/拒提取）→ 单记「箱内有货但取不出」，与配方无关");
                    closeShort(task, GhostCraftEngine.Reason.CHEST_LOCKED);
                    return;
                }
                task.craftDue = task.need > 0;
                task.done = !task.craftDue;
                return;
            }
            ItemStack probeStack = sim.stacks.get(0);

            // ② 先找存放处（同样只模拟）：A 手持容器 → B Curios back → C 随身潜影盒 → D 背包
            StorageTargets.Target dst = StorageTargets.find(player, probeStack, "搬运SIM");
            if (dst == null) {
                result.inventoryFull = true;
                result.noSpaceReason = StorageTargets.reasonNone(player, probeStack);
                GhostDebug.line("      " + task.id + "：一处存放处都找不到（手持/饰品/潜影盒/背包全满），"
                        + "搬运就此收手，缺口 " + task.need + " 件照实记账");
                closeShort(task, GhostCraftEngine.Reason.NO_SPACE);
                return;
            }

            // ③ 真实提取（件数按存放处余量收敛）+ 真实放入；塞不下的进 limbo，绝不落地
            int room = Math.max(0, dst.room());
            int realWant = Math.min(sim.taken, Math.max(room, 1));
            ChestIndex.Grab real = index.take(task.probe.getItem(), realWant, 0, false, SR.MAX_LOCS_PER_TICK);
            if (real.taken <= 0) {
                // 模拟说有、真实一格都没给（同一 tick 内箱子被人动过）：索引已自愈，下一轮续
                GhostDebug.line("      " + task.id + "：模拟可取 " + sim.taken
                        + " 件但真实提取 0 件（箱子刚被动过），排到队尾下一轮续");
                task.moveAgain = true;
                return;
            }
            int moved = 0;
            for (ItemStack st : real.stacks) {
                result.moved += st.getCount();
                StorageTargets.Target here = StorageTargets.find(player, st, "搬运COMMIT");
                if (here == null) { stash(st); continue; }
                ItemStack leftover = StorageTargets.commit(here, st);
                int got = st.getCount() - leftover.getCount();
                if (got > 0) {
                    result.written += got;
                    recordPlaced(task.id, got);
                    moved += got;
                }
                if (!leftover.isEmpty()) stash(leftover);
            }
            task.need -= moved;
            if (task.need <= 0) { task.done = true; return; }
            if (moved <= 0) {
                result.inventoryFull = true;
                closeShort(task, GhostCraftEngine.Reason.NO_SPACE);
                return;
            }
            if (real.outOfBudget || moved < realWant) {
                task.moveAgain = true;      // 单 tick 件数/料格上限到了，箱里可能还有货：排队尾续
                return;
            }
            // 索引里有货的格子全榨干了还缺 → 差额交给阶段 B
            task.craftDue = true;
        }

        /** 阶段 A 收工、阶段 B 开工：重建索引 → <b>先用实物复扫把"缓存误判无货"的现货捞回搬运</b> → 剩下的才进配方闸门。 */
        private void startCraftPhase() {
            buildIndex("阶段 B 开工");
            // r23：NO_RECIPE 闸门只许在配方索引<b>健康</b>时开闸——索引构建失败时人人
            // 都"查无配方"，那是索引的病，判到谁头上都是冤案（连坐一整类的暗通道）。
            // 不健康就整批放行进合成队列，由 plan 逐种现场重建索引、现场定夺。
            boolean indexOk = GhostCraftEngine.recipeIndexHealthy(this);
            if (!indexOk) {
                GhostDebug.line("══ [!!] 配方索引此刻不可信（构建失败过）：本轮 NO_RECIPE 闸门让行，"
                        + "所有待合成品种放行给规划器逐种定夺，绝不按\"查无配方\"整批误杀");
            }
            int due = 0, noRecipe = 0, rescued = 0;
            for (Iterator<Task> it = toCraft.iterator(); it.hasNext(); ) {
                Task t = it.next();
                // r22 铁律①（第一段）：上面那次 buildIndex 本身就是一次全量实测——衔接缓存说
                // 有货，等于实物说有货。这种品种不是配方缺口，是还有货没搬完，先回炉阶段 A。
                // （盖过章的品种也可能在此复活：盖章之后玩家又往箱里补了货。）
                if (t.need > 0 && index.count(t.probe.getItem()) > 0) {
                    GhostDebug.line("      " + t.id + "：衔接索引重扫发现箱里还有现货 "
                            + index.count(t.probe.getItem()) + " 件 → 退回阶段 A 继续搬（搬运不看配方）");
                    t.craftDue = false;
                    t.swept = false;      // 缓存刚全量刷新过，下一轮 runMove 若又空手允许再实扫一次
                    moveQueue.addLast(t);
                    it.remove();
                    rescued++;
                    continue;
                }
                // r22 铁律③：配方闸门开闸前，每一种都必须已经被<b>实物</b>盖过「箱里没货」的章。
                // runMove 里没来得及实扫的（半路榨干才 craftDue 的那些）现在补一次：
                // 箱里竟还有货 → 这压根不是缺口，是缓存漏认，退回阶段 A 继续搬，配方看都不看。
                // （用户口径：阶段 A 纯搬运绝对不能被配方过滤影响，NO_RECIPE 只在阶段 B 拦截。）
                if (!t.swept) {
                    t.swept = true;
                    int live = index.sweepRecount(t.probe.getItem(), SR.PHASE_A_SWEEP_SLOTS);
                    if (live > 0) {
                        GhostDebug.line("      " + t.id + "：阶段 B 门口复核，实扫在箱里刷出 " + live
                                + " 件 → 这不是配方缺口，是搬运还没搬完；退回阶段 A（搬运不看配方）");
                        t.craftDue = false;
                        moveQueue.addLast(t);
                        it.remove();
                        rescued++;
                        continue;
                    }
                }
                // r14 第 1 点（r22 起挪到实扫复核<b>之后</b>）：去皮深色橡木原木这类根本不是
                // 工作台/石切台的产物（要玩家拿斧子去皮）。到这一步实物已经盖章「箱里没货」，
                // 一次哈希查表判掉，绝不进递归规划 —— 旧版就是让它进规划，先做最贵的全箱普查
                // 再一层层展开，把整份预算烧光，后面 25 种材料全饿死。
                // 注意这条判定<b>只影响合成队列</b>：有货的东西在上面那一步就被捞回阶段 A 了。
                // r23：再加一道保险——索引不健康时（indexOk=false）闸门直接让行，不冤枉任何品种。
                if (indexOk && !GhostCraftEngine.hasAnyRecipe(this, t.probe.getItem())) {
                    noRecipe++;
                    closeShort(t, GhostCraftEngine.Reason.NO_RECIPE);
                    closeTask(t);                 // 当场结清并计入进度，绝不留到下一 tick
                    it.remove();
                    continue;
                }
                craftQueue.addLast(t);
                it.remove();
                due++;
            }
            if (rescued > 0) {
                // 还有现货可搬：阶段 A 续命，配方闸门等真搬完了再过一遍
                phase = Phase.MOVE;
                GhostDebug.line("══ 阶段 B 暂缓开工：实扫捞回 " + rescued
                        + " 种箱里仍有现货的品种，退回阶段 A 继续搬（搬运只看 ChestIndex，不看配方）");
                return;
            }
            phase = Phase.CRAFT;
            toCraft.clear();
            GhostDebug.line("══ 阶段 A 完成：已投 " + result.written + " 件 / 已结清 " + doneTypes
                    + " 种；阶段 B 待合成 " + due + " 种，另有 " + noRecipe
                    + " 种<b>实扫确认无现货且</b>查无工作台配方当场判掉（受理合计 " + totalTypes + " 种）");
        }

        // ==================== 阶段 B：合成 ====================

        private boolean advanceCraft() {
            Task t = craftQueue.poll();
            if (t == null) return true;                    // 两个队列都空 = 会话干完了
            if (t.job == null) {
                if (!beginCraft(t)) { closeTask(t); return false; }
            }
            t.ticks++;
            activeCraft = t;
            GhostCraftEngine.Step st;
            try {
                st = GhostCraftEngine.step(this, t.job);
            } finally {
                activeCraft = null;
            }
            craftSteps++;
            if (!st.finished()) {
                craftQueue.addLast(t);                     // 轮转：把下一手的机会让给别的任务
                return false;
            }
            finishCraft(t, st);
            closeTask(t);
            return false;
        }

        /**
         * 给一个还有缺口的任务开工：规划一条合成链。
         *
         * @return true = 规划成功、已挂上 {@code t.job}；false = 这一种已经结清并记账
         */
        boolean beginCraft(Task task) {
            if (task.need <= 0) { task.done = true; return false; }
            if (!hasSessionRoom()) {
                result.capped = true;
                closeShort(task, GhostCraftEngine.Reason.LIMIT);
                return false;
            }
            result.craftTried = true;
            task.job = GhostCraftEngine.plan(this, task.id, task.probe, task.need);
            if (task.job != null && !task.job.finished()) {
                bookPlan(task);
                GhostDebug.line("      " + task.id + "：规划成功，进合成轮转队列（缺口 " + task.need + " 件）");
                return true;
            }
            GhostCraftEngine.Reason why = task.job == null
                    ? GhostCraftEngine.Reason.NO_RECIPE
                    : (task.job.failReason() == null ? GhostCraftEngine.Reason.NO_RECIPE : task.job.failReason());
            task.job = null;
            closeShort(task, why);
            return false;
        }

        /** 合成一手的收尾：还差多少记多少缺口，并按原因分类。 */
        void finishCraft(Task task, GhostCraftEngine.Step st) {
            task.need = task.job.remaining();
            task.job = null;
            unbookPlan(task);
            if (task.need > 0) {
                closeShort(task, st.fail() == null ? GhostCraftEngine.Reason.NO_MATERIAL : st.fail());
            } else {
                task.done = true;
            }
        }

        /** 一种任务彻底结清：还有差额就照实记缺口，并计入已结清种类。 */
        private void closeTask(Task task) {
            task.done = true;
            doneTypes++;
            if (GhostDebug.on()) {
                GhostDebug.line("      ✔ " + task.id + " 结清：" + (task.need <= 0 ? "缺口已补满"
                        : "还缺 " + task.need + " 件（" + (task.lastWhy == null ? "照实记账" : task.lastWhy.label()) + "）")
                        + "；进度 " + doneTypes + "/" + totalTypes + " 种，阶段 "
                        + (phase == Phase.MOVE ? "A 搬运" : "B 合成"));
            }
        }

        /** 结清但没补满：按「查无配方 / 材料不够 / 算力超限 / 放不下」分类记账（r14 第 4 点）。 */
        private void closeShort(Task task, GhostCraftEngine.Reason why) {
            task.lastWhy = why;
            unbookPlan(task);
            task.craftDue = false;
            task.done = true;
            if (task.need > 0) recordMissing(task, why);
        }

        /** 缺口的分类账：同一品种多次记录也只累加件数，不重复占名。 */
        private void recordMissing(Task task, GhostCraftEngine.Reason why) {
            result.craftFails.merge(task.id, task.need, Integer::sum);
            result.craftWhy.put(task.id, why.label() + GhostDebug.tail());
            if (why == GhostCraftEngine.Reason.NO_RECIPE) {
                result.noRecipeTypes++;
                result.noRecipeKinds.merge(task.id, task.need, Integer::sum);
            } else if (why == GhostCraftEngine.Reason.CHEST_LOCKED) {
                // r22：箱里有货却取不出，跟配方无关，绝不进「查无工作台配方」名单
                result.chestLockedKinds.merge(task.id, task.need, Integer::sum);
            } else if (why == GhostCraftEngine.Reason.STUCK || why == GhostCraftEngine.Reason.BUDGET) {
                result.stuckKinds.merge(task.id, task.need, Integer::sum);
            } else if (why == GhostCraftEngine.Reason.NO_SPACE) {
                result.noSpaceKinds.merge(task.id, task.need, Integer::sum);
            } else if (why == GhostCraftEngine.Reason.LIMIT) {
                result.cappedPieces += task.need;
            } else {
                result.noMaterial.merge(task.id, task.need, Integer::sum);
            }
            result.missingTypes++;
            result.remaining.add(new Packets.Entry(task.id, task.need));
        }

        // ---- 跨任务预订 ----

        /** 把这个计划订走的原料登记进跨任务账本（别的任务不能再订同一批货）。 */
        private void bookPlan(Task task) {
            Map<Item, Integer> b = task.job.bookings();
            if (b == null || b.isEmpty()) return;
            planBookings.put(task.id, new HashMap<>(b));
        }

        /**
         * 任务结清/放弃：撤掉它订走的额度。
         *
         * <p>真实消耗的部分已经从索引里扣过了，留着的只是「还没用上的那部分」，
         * 不撤的话后面的任务会以为货还在别人名下，白白判死。</p>
         */
        private void unbookPlan(Task task) {
            if (task == null) return;
            Map<Item, Integer> b = planBookings.remove(task.id);
            if (b != null && GhostDebug.on()) {
                GhostDebug.line("      " + task.id + "：撤销计划预订 " + b.size() + " 种原料（后续任务可动用）");
            }
        }

        // ---------------- 位置索引 ----------------

        /** 建（或重建）标记箱位置索引。这是整个会话唯一的全量扫描。 */
        private void buildIndex(String when) {
            long t0 = System.nanoTime();
            final ServerPlayer sp = this.player;
            index = ChestIndex.build(resolveChests(), pos -> resolveChest(sp, pos), SR.INDEX_TICK_MS);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            GhostDebug.line("══ [" + when + "] 标记箱位置索引：" + index.summary() + "（建索引 " + ms + "ms）");
            if (!index.complete) {
                GhostDebug.line("      [!!] 索引没扫完（撞到 " + SR.INDEX_TICK_MS
                        + "ms 时间闸门）：后面没扫到的箱子本轮视为无货，宁可少搬也绝不误判");
            }
        }

        // ---- GhostCraftEngine.Host ----

        @Override public ServerPlayer player() { return player; }
        @Override public Level level() { return player.level(); }
        @Override public ChestIndex index() { return index; }

        /**
         * 这个品种还有几件被<b>别人</b>占着（搬运队列 + 已展开的合成计划）。
         * r7 Bug① 的口径保留：按件数，绝不整种一票否决。
         */
        @Override
        public int reservedCount(Item item) {
            if (item == null) return 0;
            int n = Math.max(0, reserved.getOrDefault(item, 0));
            for (Map<Item, Integer> b : planBookings.values()) n += b.getOrDefault(item, 0);
            return n;
        }

        /** 输出箱坐标（只进不出）：压根不在索引的扫描名单里，取料自循环不可能发生。 */
        @Override public List<BlockPos> outputs() { return outMarks; }

        @Override public IItemHandler outputAt(BlockPos pos) { return resolveChest(player, pos); }

        @Override public boolean hasSessionRoom() { return result.written < itemCap; }
        @Override public long deadlineMs() { return deadline; }

        // ---- SIM 与 COMMIT 两套独立的料格预算（r7 Bug④ 的教训；r14 限的是料格而非全箱槽位） ----

        int simLocs = SR.CRAFT_SIM_LOCS;
        int commitLocs = SR.CRAFT_COMMIT_LOCS;

        @Override
        public void resetLocBudget(boolean simulate) {
            if (simulate) simLocs = SR.CRAFT_SIM_LOCS;
            else commitLocs = SR.CRAFT_COMMIT_LOCS;
        }

        @Override
        public boolean spendLocs(int n, boolean simulate) {
            if (simulate) {
                if (simLocs < n) { simLocs = 0; return false; }
                simLocs -= n;
                return true;
            }
            if (commitLocs < n) { commitLocs = 0; return false; }
            commitLocs -= n;
            return true;
        }

        /** 合成产物真实投进存放处（缺口件）：计入投料账本，L 键据此扣缺口。 */
        @Override
        public void onPlaced(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            result.written += stack.getCount();
            result.crafted += stack.getCount();
            recordPlaced(id, stack.getCount());
        }

        /** 原料真实离开箱子后的回调（件数记账；索引在 {@link ChestIndex#take} 里已经就地扣过了）。 */
        @Override
        public void onConsumed(ItemStack from) {
            if (from == null || from.isEmpty()) return;
            result.craftConsumed += from.getCount();
            result.moved += from.getCount();
        }

        private void recordPlaced(String id, int n) {
            placed.merge(id, n, Integer::sum);
            placedDirty = true;
        }

        List<Packets.Entry> placedList() {
            List<Packets.Entry> out = new ArrayList<>(placed.size());
            for (Map.Entry<String, Integer> e : placed.entrySet()) out.add(new Packets.Entry(e.getKey(), e.getValue()));
            return out;
        }

        void abandonCraft() {
            try {
                for (Task t : craftQueue) {
                    if (t.job != null) { GhostCraftEngine.abandon(this, t.job); t.job = null; }
                    unbookPlan(t);
                }
                if (activeCraft != null) {
                    if (activeCraft.job != null) {
                        GhostCraftEngine.abandon(this, activeCraft.job);
                        activeCraft.job = null;
                    }
                    unbookPlan(activeCraft);
                }
            } catch (Throwable ignored) {}
        }

        // ---- 兜底：真实插入没吃完的极端情况，先挂起，逐 tick 再找地方，全程不落地 ----

        @Override
        public void stash(ItemStack rest) {
            if (rest == null || rest.isEmpty()) return;
            StorageTargets.Target t = StorageTargets.find(player, rest, "挂账COMMIT");
            if (t != null) {
                ItemStack left = StorageTargets.commit(t, rest);
                if (left.isEmpty()) return;
                rest = left;
            }
            // 背包塞不下 → 试输出箱（有标记的话），仍然不落地
            if (insertIntoAnyOutput(rest, "宿主挂账") > 0 && rest.isEmpty()) return;
            limbo.add(rest);
        }

        /**
         * 往任意一个输出箱里塞，返回塞进去的件数（原堆就地扣减）。
         *
         * <p>输出箱<b>只进不出</b>：{@link #resolveChests()} 明确把它们排除在索引扫描名单之外，
         * 「产物进输出箱 → 下一轮又把它当原料抽走合成」的自循环不可能发生。</p>
         */
        int insertIntoAnyOutput(ItemStack st, String why) {
            if (st == null || st.isEmpty() || outMarks.isEmpty()) return 0;
            int put = 0;
            for (BlockPos pos : outMarks) {
                if (st.isEmpty()) break;
                IItemHandler h = resolveChest(player, pos);
                if (h == null) continue;
                ItemStack left = ItemHandlerHelper.insertItem(h, st.copy(), false);
                int got = st.getCount() - left.getCount();
                if (got > 0) {
                    put += got;
                    st.shrink(got);
                    GhostDebug.line("      输出箱 " + GhostDebug.pos(pos) + " 收 " + got + " 件（" + why + "）");
                }
            }
            if (put > 0) result.toOutputs += put;
            return put;
        }

        @Override
        public void noteOverflow(ItemStack placedStack, String where) {
            if (placedStack == null || placedStack.isEmpty()) return;
            // 引擎那条路（GhostCraftEngine.insertIntoOutputs）只走这里；
            // 宿主自己那条路走 insertIntoAnyOutput，已经计过，不会重算。
            if ("output".equals(where)) result.toOutputs += placedStack.getCount();
            else if ("limbo".equals(where)) result.toLimbo += placedStack.getCount();
            else result.toBackpackExtra += placedStack.getCount();
        }

        void reAbsorbLimbo() {
            Iterator<ItemStack> it = limbo.iterator();
            while (it.hasNext()) {
                ItemStack st = it.next();
                StorageTargets.Target t = StorageTargets.find(player, st, "回笼COMMIT");
                if (t == null) continue;
                ItemStack left = StorageTargets.commit(t, st);
                it.remove();
                if (!left.isEmpty()) stashLeftoverIntoChest(left);
            }
        }

        /** 会话结束前最后一次安置：背包 → 输出箱 → 标记箱 → 主物品栏空格，全程不落地。 */
        void flushLeftovers() {
            if (limbo.isEmpty()) return;
            for (ItemStack st : new ArrayList<>(limbo)) {
                StorageTargets.Target t = StorageTargets.find(player, st, "收尾COMMIT");
                if (t != null) {
                    st = StorageTargets.commit(t, st);
                    if (st.isEmpty()) continue;
                }
                if (insertIntoAnyOutput(st, "会话收尾挂账") > 0 && st.isEmpty()) continue;
                stashLeftoverIntoChest(st);
            }
            limbo.clear();
        }

        /**
         * 最后的兜底：<b>先输出箱</b>，再退回第一个摸得到的材料箱，再不行塞主物品栏空格。
         *
         * <p>材料箱排在输出箱之后是有意的：材料箱是「原料池」，把成品/余料倒回去，
         * 下一轮取料就可能拿到自己刚合出来的东西（自循环）。真没别的地方可去时才这么干，
         * 而且会在日志里留一行。</p>
         */
        private void stashLeftoverIntoChest(ItemStack st) {
            if (st == null || st.isEmpty()) return;
            if (insertIntoAnyOutput(st, "最后兜底") > 0 && st.isEmpty()) return;
            for (BlockPos pos : resolveChests()) {
                IItemHandler h = resolveChest(player, pos);
                if (h == null) continue;
                ItemStack left = ItemHandlerHelper.insertItem(h, st.copy(), false);
                if (left.isEmpty()) {
                    // 倒回材料箱了：索引得重扫这一箱，否则缓存会少认这几件
                    if (index != null) index.dirtyChest(indexOfChest(pos));
                    return;
                }
            }
            // 最后手段：直接找主物品栏空格写入（仍然不生成掉落物）
            IItemHandler main = StorageTargets.mainInventory(player);
            for (int i = 0; i < main.getSlots(); i++) {
                if (!main.getStackInSlot(i).isEmpty()) continue;
                ItemStack one = st.copy();
                one.setCount(1);
                if (main.insertItem(i, one, false).isEmpty()) {
                    st.shrink(1);
                    if (st.isEmpty()) return;
                }
            }
            // 一件都安置不了（连标记箱也满/被拆）：从「已搬」改记「跳过」，账面不虚增
            int n = st.getCount();
            result.written = Math.max(0, result.written - n);
            result.skipped += n;
            result.inventoryFull = true;
            result.lostWarn = "有 " + n + " 件物品没能安置，已按跳过记账";
        }

        private int indexOfChest(BlockPos pos) {
            if (index == null) return -1;
            return index.positions().indexOf(pos);
        }

        // ---- 工具 ----

        List<BlockPos> resolveChests() {
            List<BlockPos> out = new ArrayList<>();
            Set<BlockPos> seenPos = new HashSet<>();
            Set<IItemHandler> seenHandler = Collections.newSetFromMap(new IdentityHashMap<>());
            for (BlockPos pos : marks) {
                if (!seenPos.add(pos)) continue;
                if (outMarks.contains(pos)) continue;   // 防御：输出箱绝不进取料名单（防自循环）
                IItemHandler h = resolveChest(player, pos);
                if (h == null || h.getSlots() <= 0) continue;
                if (!seenHandler.add(h)) continue;      // 箱子双格算同一个容器，避免重复搬
                out.add(pos);
                if (out.size() >= SR.MAX_CONTAINERS) break;
            }
            return out;
        }

        /** 会话提前收场：所有还没结清的任务照实记缺口，一个都不吞。 */
        void drainRemainingAsMissing(String why) {
            List<Task> rest = new ArrayList<>();
            rest.addAll(moveQueue);
            rest.addAll(toCraft);
            rest.addAll(craftQueue);
            if (activeCraft != null) rest.add(activeCraft);
            moveQueue.clear();
            toCraft.clear();
            craftQueue.clear();
            for (Task t : rest) {
                if (t.done) continue;
                if (t.job != null) {
                    try { GhostCraftEngine.abandon(this, t.job); } catch (Throwable ignored) {}
                    t.job = null;
                }
                unbookPlan(t);
                release(t.probe.getItem(), t.need);
                t.craftDue = false;
                if (t.need > 0) {
                    if (GhostDebug.on()) GhostDebug.line("      " + t.id + " 还缺 " + t.need + " 件（" + why + "）");
                    result.unfinished.merge(t.id, t.need, Integer::sum);
                    result.missingTypes++;
                    result.remaining.add(new Packets.Entry(t.id, t.need));
                }
                t.done = true;
                doneTypes++;
            }
        }

        boolean shouldReport() {
            if (tick - lastReportTick < SR.PROGRESS_EVERY_TICKS) return false;
            lastReportTick = tick;
            return true;
        }

        Packets.TakeoverProgress progress() {
            boolean has = placedDirty;
            placedDirty = false;
            return new Packets.TakeoverProgress(key, doneTypes, totalTypes, result.written,
                    result.skipped, has, has ? placedList() : List.of(), craftSteps,
                    phase == Phase.MOVE ? 0 : 1, result.noRecipeTypes);
        }
    }

    /** 一种材料当前的搬运/合成任务。 */
    private static final class Task {
        final String id;
        final ItemStack probe;
        int need;
        boolean done;
        /** 现货榨干仍缺，等阶段 B 合成。 */
        boolean craftDue;
        /** r22：这个品种已经被<b>物理复扫</b>盖过章（实扫零 = 箱里真没货；实扫有货 = 缓存已修好）。
         *  配方闸门只许放行盖过章的任务——没盖章的必须先补实扫，有货就退回阶段 A 继续搬。 */
        boolean swept;
        /** 本步没做完，排到队尾（轮转公平性）。 */
        boolean moveAgain;
        /** 搬运队列的按件锁定是否已经销过。 */
        boolean lockReleased;
        /** 非空 = 这一种正在走合成阶段。 */
        GhostCraftEngine.Job job;
        /** 已经占用了几个 tick（日志用）。 */
        int ticks;
        /** 结清时欠账的原因（分类报告用）。 */
        GhostCraftEngine.Reason lastWhy;

        Task(String id, ItemStack probe, int need) {
            this.id = id;
            this.probe = probe;
            this.need = need;
        }
    }

    // ---------------- 结果 ----------------

    /** 内部结果对象，最后拍平成 S2C payload。 */
    private static final class Result {
        int written, skipped, missingTypes;
        /** 真实离开过箱子的件数（搬运 + 合成投料，日志与排障用）。 */
        int moved;
        int craftConsumed;
        /** 其中通过幽灵合成投进存放处的件数（{@link #written} 的子集，只为报告区分两阶段）。 */
        int crafted;
        /** 余料/超发进输出箱的件数（只进不出，不计入投料账本，所以不扣 L 键缺口）。 */
        int toOutputs;
        /** 余料塞回玩家背包的件数（没标记输出箱时的退路）。 */
        int toBackpackExtra;
        /** 连输出箱和背包都塞不下、挂进 limbo 的件数（会话结束还会再安置一轮）。 */
        int toLimbo;
        boolean inventoryFull, capped, noChests, craftTried, stalled;
        /** r28：一个材料箱都没标时，按 {@code restock.searchRadius} 兜底借用的附近容器个数。 */
        int autoBorrowed;
        String noSpaceReason, lostWarn, error;
        final List<Packets.Entry> remaining = new ArrayList<>();
        final Map<String, Integer> craftFails = new HashMap<>();
        final Map<String, String> craftWhy = new HashMap<>();
        // ---- r14 第 4 点：退出时按原因分类，玩家一眼知道是「没配方」还是「料不够」 ----
        /** 查无工作台/石切配方（去皮原木这类要工具加工的）。 */
        final Map<String, Integer> noRecipeKinds = new HashMap<>();
        /** r22：实扫确认箱里有现货、但容器一件都取不出（只读格口/拒提取）——与配方无关，单独报。 */
        final Map<String, Integer> chestLockedKinds = new HashMap<>();
        /** 材料不够 / 凑不齐原料 / 配方不安全。 */
        final Map<String, Integer> noMaterial = new HashMap<>();
        /** 存放处与输出箱都满。 */
        final Map<String, Integer> noSpaceKinds = new HashMap<>();
        /** 算力超限（同一手连续改期满上限）而被跳过的。 */
        final Map<String, Integer> stuckKinds = new HashMap<>();
        /** 会话提前收场时尚未处理的。 */
        final Map<String, Integer> unfinished = new HashMap<>();
        int noRecipeTypes;
        int cappedPieces;

        Packets.TakeoverResult toPayload(long key, List<Packets.Entry> placedList) {
            StringBuilder sb = new StringBuilder();
            if (lostWarn != null) appendNote(sb, lostWarn);
            if (error != null) appendNote(sb, "内部错误 " + error);
            if (stalled) appendNote(sb, "连续 " + (SR.STALL_EXIT_MS / 1000L) + " 秒零进展，提前退出");
            if (autoBorrowed > 0) {
                appendNote(sb, "没有标记任何材料箱，本次按兜底半径借用了附近 " + autoBorrowed
                        + " 个容器取料（建议用标记签标箱，详见配置 restock.searchRadius）");
            }
            if (!noRecipeKinds.isEmpty()) {
                appendNote(sb, "查无工作台配方 " + noRecipeKinds.size() + " 种（需工具加工，如去皮原木）："
                        + nameList(noRecipeKinds));
            }
            if (!chestLockedKinds.isEmpty()) {
                appendNote(sb, "箱里有现货但取不出 " + chestLockedKinds.size()
                        + " 种（存储格口拒绝提取，和配方无关，换普通箱子放这些料）："
                        + nameList(chestLockedKinds));
            }
            if (!noMaterial.isEmpty()) {
                appendNote(sb, "材料不够 " + noMaterial.size() + " 种：" + nameList(noMaterial));
            }
            if (!noSpaceKinds.isEmpty()) {
                appendNote(sb, "存放处已满 " + noSpaceKinds.size() + " 种：" + nameList(noSpaceKinds));
            }
            if (!stuckKinds.isEmpty()) {
                appendNote(sb, "算力超限跳过 " + stuckKinds.size() + " 种：" + nameList(stuckKinds));
            }
            if (!unfinished.isEmpty()) {
                appendNote(sb, "未处理 " + unfinished.size() + " 种：" + nameList(unfinished));
            }
            if (cappedPieces > 0) appendNote(sb, "件数额度用尽，还差 " + cappedPieces + " 件");
            if (craftTried && craftConsumed > 0) {
                appendNote(sb, "幽灵合成消耗原料 " + craftConsumed + " 件（直接搬运 " + moved + " 件）");
            }
            // 溢出与余料的去向必须在总结里看得见，否则玩家会以为东西"凭空多了/少了"
            if (toOutputs > 0 || toBackpackExtra > 0 || toLimbo > 0) {
                appendNote(sb, "超发与余料：输出箱 " + toOutputs + " 件"
                        + (toBackpackExtra > 0 ? " / 背包 " + toBackpackExtra + " 件" : "")
                        + (toLimbo > 0 ? " / 挂账 " + toLimbo + " 件" : "")
                        + "（这些不计入缺口扣减）");
            }
            List<Packets.Entry> fails = new ArrayList<>();
            for (Map.Entry<String, Integer> e : craftFails.entrySet()) fails.add(new Packets.Entry(e.getKey(), e.getValue()));

            String note = noChests ? "没有可用的标记箱（被拆/跨维度/失效）"
                    : (noSpaceReason == null ? sb.toString() : noSpaceReason + (sb.length() == 0 ? "" : " | " + sb));
            return new Packets.TakeoverResult(key, written, skipped, missingTypes,
                    inventoryFull, capped, remaining, note, placedList, fails, craftConsumed);
        }

        /** 取前 4 个品种名（带件数）拼一句人话，剩下的只报总数。 */
        private static String nameList(Map<String, Integer> m) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (n++ >= 4) { sb.append("…共 ").append(m.size()).append(" 种"); break; }
                if (sb.length() > 0) sb.append("、");
                sb.append(shortName(e.getKey())).append("×").append(e.getValue());
            }
            return sb.toString();
        }

        private static void appendNote(StringBuilder sb, String s) {
            if (s == null || s.isEmpty()) return;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(s);
        }

        private static String shortName(String id) {
            int i = id.indexOf(':');
            return i >= 0 ? id.substring(i + 1) : id;
        }
    }

    // ---------------- 静态工具 ----------------

    /**
     * 一个材料箱都没标时，在玩家周边 {@code radius} 格内找<b>真实可用</b>的容器方块
     * （<b>r28 新增，restock.searchRadius 的执行体</b>）。
     *
     * <p>与框选标记同一口径：{@code hasBlockEntity} 先滤掉绝大多数格子，再查
     * {@code Capabilities.ItemHandler.BLOCK}，拿到 handler 才算一个。输出箱（只进不出）
     * 绝不混进取料名单。撞到 20ms 时间闸门或 {@code SR.MAX_CONTAINERS} 就带着已收的收货
     * 返回 —— 这条兜底是"聊胜于无"，绝不把主线程钉死。</p>
     */
    private static List<BlockPos> borrowNearbyContainers(ServerPlayer sp, int radius) {
        List<BlockPos> found = new ArrayList<>();
        Level level = sp.level();
        BlockPos c = sp.blockPosition();
        Set<Long> outs = new HashSet<>();
        for (BlockPos o : MarkedChestStore.outputsIn(sp, level)) outs.add(o.asLong());
        long deadline = System.nanoTime() + 20_000_000L;
        int r = Math.max(1, radius);
        int y1 = Math.max(level.getMinBuildHeight(), c.getY() - r);
        int y2 = Math.min(level.getMaxBuildHeight() - 1, c.getY() + r);
        long reach2 = (long) r * r;
        for (int x = c.getX() - r; x <= c.getX() + r; x++) {
            long ddx = (long) (x - c.getX()) * (x - c.getX());
            if (ddx > reach2) continue;
            for (int z = c.getZ() - r; z <= c.getZ() + r; z++) {
                long ddz = (long) (z - c.getZ()) * (z - c.getZ());
                if (ddx + ddz > reach2) continue;
                for (int y = y1; y <= y2; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) continue;
                    if (!level.getBlockState(pos).hasBlockEntity()) continue;
                    if (outs.contains(pos.asLong())) continue;
                    try {
                        IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, (Direction) null);
                        if (h != null && h.getSlots() > 0) {
                            found.add(pos.immutable());
                            if (found.size() >= SR.MAX_CONTAINERS) return found;
                        }
                    } catch (Throwable ignored) {}
                    if ((found.size() & 7) == 0 && System.nanoTime() > deadline) return found;
                }
            }
        }
        return found;
    }

    private static IItemHandler resolveChest(ServerPlayer sp, BlockPos pos) {
        try {
            if (!sp.level().isLoaded(pos)) return null;
            return sp.level().getCapability(Capabilities.ItemHandler.BLOCK, pos, (Direction) null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Item resolve(String id) {
        try {
            Item it = (Item) BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            return (it == null || it == Items.AIR) ? null : it;
        } catch (Exception ex) {
            return null;
        }
    }
}
