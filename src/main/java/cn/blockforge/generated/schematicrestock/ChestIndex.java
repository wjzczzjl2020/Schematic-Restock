package cn.blockforge.generated.schematicrestock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 标记箱的<b>位置索引缓存</b>（r14 大图纸重构的地基）。
 *
 * <h3>它消灭的是什么</h3>
 * <p>旧版每一手合成、每一个配料格都要把「96 个箱子 × 27 格 = 2592 槽」从头扫一遍。
 * 一次 SIM 里链上每个配料各扫一轮，单手扫描量天生超过预算（用户日志里的 3001/3000），
 * 于是「本手改期 → 下一 tick 从零重扫同一手」变成<b>必然</b>死循环，直到会话超时。</p>
 *
 * <p>索引把这笔钱一次付清：会话开始时全量扫一遍，记下「每种物品在第几个箱子的第几格有几件」，
 * 之后取料只摸真正有货的那几格。查「箱里到底有没有这个」从 O(全箱阵) 变成一次哈希命中。</p>
 *
 * <h3>一致性与自愈</h3>
 * <p>缓存会过期（玩家自己拿东西、别的模组改箱子、箱子被拆）。索引<b>从不盲信自己</b>：
 * 每次动手前照旧 {@code getStackInSlot} 实测 + {@code extractItem(..., true)} 复核，
 * 任何一次对不上就把这条记录作废并按需重扫那一个箱子（{@link #rescanChest}）。
 * 所以索引只会让我们<b>更早</b>发现没货，绝不会凭空造出货来 —— 世界永远是唯一事实来源。</p>
 */
public final class ChestIndex {

    /** 现取 handler 的回调（箱子被拆 / 区块卸载返回 null）。 */
    public interface Resolver {
        IItemHandler resolve(BlockPos pos);
    }

    /** 一条料格记录：第 {@code chest} 个箱子的第 {@code slot} 格，最近一次实测有 {@code count} 件。 */
    public static final class Loc {
        final Item item;
        final int chest;
        final int slot;
        int count;
        /** 这条记录已作废（拿光 / 实物对不上）；就地保留对象、遍历时跳过，避免边扫边删。 */
        boolean dead;

        Loc(Item item, int chest, int slot, int count) {
            this.item = item;
            this.chest = chest;
            this.slot = slot;
            this.count = count;
        }

        public Item item() { return item; }
        public int chestIndex() { return chest; }
        public int slotIndex() { return slot; }
        public int lastKnownCount() { return count; }
        public boolean isDead() { return dead; }
    }

    /** 一次取料的返回。 */
    public static final class Grab {
        /** 拿到手的堆：simulate=true 时是模拟副本（件数真实、对象独立），false 时是真实提取出来的栈。 */
        public final List<ItemStack> stacks = new ArrayList<>();
        public int taken;
        /** 本次检视了几个料格（预算就按这个数扣，而不是按全箱槽位数）。 */
        public int locsVisited;
        /** 缓存与实物不符、当场自愈了几次（日志用）。 */
        public int repairs;
        /**
         * true = 料格检视上限用完、还差货没拿。
         * <b>这不是失败</b>：调用方按「本手改期」处理，并由 {@code SR.MAX_STEP_RETRIES} 封顶。
         */
        public boolean outOfBudget;
        /** true = 还没凑够 {@code max}。 */
        public boolean shortOf() { return !outOfBudget && taken < wanted; }
        /** 调用方要的件数（回填，日志用）。 */
        public int wanted;
    }

    private final List<BlockPos> chests;
    private final Resolver resolver;
    private final IItemHandler[] handlers;
    private final boolean[] chestDead;
    private final Map<Item, ArrayList<Loc>> byItem = new HashMap<>();
    private final Map<Item, Integer> counts = new HashMap<>();

    /** 建索引时扫过的槽位总数（日志用；这是唯一一次全量扫描）。 */
    public int slotsScanned;
    public int chestsOk;
    public int chestsFail;
    /** true = 全部箱子扫完；false = 撞到时间闸门，{@link #count} 可能偏低，调用方须留实弹复核的余地。 */
    public boolean complete;
    public long buildMs;
    /** 自愈合计次数（日志：缓存错过几处，一眼可见）。 */
    public int repairs;

    private ChestIndex(List<BlockPos> chests, Resolver resolver) {
        this.chests = chests == null ? List.of() : chests;
        this.resolver = resolver;
        int n = Math.max(1, this.chests.size());
        this.handlers = new IItemHandler[n];
        this.chestDead = new boolean[n];
    }

    /**
     * 全量建索引（一次会话一次；阶段 B 开工前再重建一次，因为阶段 A 已经把箱子搬空了一批）。
     *
     * @param tickBudgetMs 单次构建允许花的时间；超了就带着 {@code complete=false} 返回 ——
     *                     宁可少认几个箱子，也绝不把主线程钉死
     */
    public static ChestIndex build(List<BlockPos> chests, Resolver resolver, long tickBudgetMs) {
        ChestIndex idx = new ChestIndex(chests, resolver);
        long t0 = System.nanoTime();
        long deadline = t0 + Math.max(1L, tickBudgetMs) * 1_000_000L;
        try {
            idx.complete = true;
            for (int i = 0; i < idx.chests.size(); i++) {
                if ((i & 7) == 0 && System.nanoTime() > deadline) { idx.complete = false; break; }
                idx.scanChest(i);
            }
        } catch (Throwable t) {
            GhostDebug.throwAt("标记箱索引构建", t);
            idx.byItem.clear();
            idx.counts.clear();
            idx.complete = false;
        }
        idx.buildMs = (System.nanoTime() - t0) / 1_000_000L;
        return idx;
    }

    private void scanChest(int i) {
        IItemHandler h = handler(i);
        if (h == null) { chestsFail++; return; }
        chestsOk++;
        int slots = safeSlots(h);
        for (int s = 0; s < slots; s++) {
            slotsScanned++;
            ItemStack st;
            try {
                st = h.getStackInSlot(s);
            } catch (Throwable t) {
                continue;
            }
            if (st == null || st.isEmpty()) continue;
            Loc loc = new Loc(st.getItem(), i, s, st.getCount());
            byItem.computeIfAbsent(loc.item, k -> new ArrayList<>(4)).add(loc);
            counts.merge(loc.item, loc.count, Integer::sum);
        }
    }

    /** 只重扫一个箱子（缓存与实物对不上时的自愈手段，代价只有一箱，不是全箱阵）。 */
    public void rescanChest(int i) {
        forgetChest(i);
        if (i < 0 || i >= handlers.length) return;
        chestDead[i] = false;
        handlers[i] = null;
        scanChest(i);
        repairs++;
    }

    /**
     * r22 阶段 A 物理复核（「索引没货 ≠ 世界没货」的裁判）：逐箱逐格实测重扫，
     * 按实物把缓存刷回来（顺带修好所有品种，不只问的那一种）。
     *
     * <p>为什么必须有这一手：{@link #build} 有 {@code INDEX_TICK_MS} 时间闸门，扫到一半
     * 就带着 {@code complete=false} 收工，后面的箱子整轮当无货；料格记录也会因瞬时
     * 读失败被 {@code drop()} 作废。任何一种情况都可能把<b>箱里明明有的货</b>判成 0 件——
     * 阶段 A 末尾这份「无货」清单直通阶段 B 的配方闸门，去皮原木/方解石就是这么被
     * 冒领成「查无工作台配方」的。缓存只许让我们更快，不许让我们误判。</p>
     *
     * <p>成本封顶：累计检视 {@code slotCap} 个格子就停手（没扫到的箱保留旧记录），
     * 只在「索引查无此货」的路径上一次会话每种至多一次，主线程不会被钉死。</p>
     *
     * @return 刷新后缓存里这个品种的件数（0 = 实扫过的位置里当真没有货）
     */
    public int sweepRecount(Item item, int slotCap) {
        if (item == null) return 0;
        int visited = 0;
        for (int i = 0; i < chests.size(); i++) {
            if (isChestDead(i)) continue;
            IItemHandler h = handler(i);
            if (h == null) continue;
            int slots;
            try {
                slots = h.getSlots();
            } catch (Throwable t) {
                continue;
            }
            if (visited + slots > slotCap) break;   // 封顶：这一箱及以后的都保留旧记录
            visited += slots;
            rescanChest(i);
        }
        repairs++;
        return Math.max(0, counts.getOrDefault(item, 0));
    }

    /** 取 handler（缓存；摸不到就打死亡标记，不再反复解析）。 */
    IItemHandler handler(int i) {
        if (i < 0 || i >= chests.size()) return null;
        if (chestDead[i]) return null;
        IItemHandler h = handlers[i];
        if (h != null) return h;
        try {
            h = resolver.resolve(chests.get(i));
        } catch (Throwable t) {
            h = null;
        }
        if (h == null || safeSlots(h) <= 0) {
            chestDead[i] = true;
            return null;
        }
        handlers[i] = h;
        return h;
    }

    private static int safeSlots(IItemHandler h) {
        try {
            return h.getSlots();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 作废某个箱子的全部记录（重扫之前调）。 */
    private void forgetChest(int i) {
        if (i < 0) return;
        for (Map.Entry<Item, ArrayList<Loc>> e : byItem.entrySet()) {
            Iterator<Loc> it = e.getValue().iterator();
            int removed = 0;
            while (it.hasNext()) {
                Loc l = it.next();
                if (l.chest == i && !l.dead) {
                    removed += Math.max(0, l.count);
                    l.dead = true;
                    it.remove();
                }
            }
            if (removed > 0) counts.merge(e.getKey(), -removed, Integer::sum);
        }
    }

    /** 这个品种的料格清单（只读，内部结构；调用方不得修改）。 */
    List<Loc> locsRaw(Item item) {
        ArrayList<Loc> l = item == null ? null : byItem.get(item);
        return l == null ? List.of() : l;
    }

    /** 索引里这个品种当前还剩几件（随真实提取下调，随自愈纠正）。 */
    public int count(Item item) {
        if (item == null) return 0;
        return Math.max(0, counts.getOrDefault(item, 0));
    }

    public int kinds() { return counts.size(); }

    /** 索引里出现过的品种（规划期取预留快照用；只读，不得修改）。 */
    public java.util.Set<Item> knownItems() { return byItem.keySet(); }

    public int totalPieces() {
        int n = 0;
        for (int v : counts.values()) n += Math.max(0, v);
        return n;
    }

    public List<BlockPos> positions() { return chests; }

    /** 日志用一句总结。 */
    public String summary() {
        return kinds() + " 种 / " + totalPieces() + " 件，扫过 " + slotsScanned + " 格，"
                + "可用箱 " + chestsOk + " 个 / 摸不到 " + chestsFail + " 个，用时 " + buildMs + "ms，"
                + "索引完整=" + complete + (repairs > 0 ? "，已自愈 " + repairs + " 次" : "");
    }

    /**
     * 从索引里凑 {@code max} 件 {@code item}。这是 r14 唯一的取料入口（搬运与合成共用）。
     *
     * @param keepBack  至少要替「别人」（队列里尚未搬运的品种、别的任务已订走的量）在箱里留几件；0 = 不用留
     * @param simulate  true = 只做 {@code extractItem(..., true)}，世界一个字节都不改
     * @param locLimit  本次最多检视几个料格；用完仍差货 → {@link Grab#outOfBudget}=true（本手改期，不算失败）
     */
    public Grab take(Item item, int max, int keepBack, boolean simulate, int locLimit) {
        Grab g = new Grab();
        g.wanted = max;
        if (item == null || max <= 0) return g;
        ArrayList<Loc> list = byItem.get(item);
        if (list == null || list.isEmpty()) return g;
        int need = max;
        int keep = Math.max(0, keepBack);

        for (int i = 0; i < list.size() && need > 0; i++) {
            Loc loc = list.get(i);
            if (loc.dead) continue;
            if (++g.locsVisited > locLimit) { g.outOfBudget = true; return g; }

            IItemHandler h = handler(loc.chest);
            if (h == null) { drop(item, loc); continue; }

            ItemStack inSlot;
            try {
                inSlot = h.getStackInSlot(loc.slot);
            } catch (Throwable t) {
                drop(item, loc);
                continue;
            }
            // ① 缓存与实物对不上：以实物为准，这条记录作废。索引错了不会导致多拿，只会少拿，
            //    而少拿会被上层当成「没货」——方向上绝对安全。
            // r22：判据写成 st.is(item)（只比物品）。字节码核实 1.21.1 的 ItemStack.isSameItem
            // 本就是这个语义，这里改成显式写法与 GhostCraftEngine.pick 的 r7 宽松口径、
            // StorageTargets.countCarried 的 r21 口径对齐，不留"看起来比组件"的误读空间。
            if (inSlot == null || inSlot.isEmpty() || !inSlot.is(item)) {
                drop(item, loc);
                g.repairs++;
                repairs++;
                continue;
            }
            if (inSlot.getCount() != loc.count) {
                counts.merge(item, loc.count - inSlot.getCount(), Integer::sum);
                loc.count = inSlot.getCount();
            }

            // ② 按件留底：本格最多只能给出「现存件数 − 还要替别人留的件数」
            int usable = Math.max(0, inSlot.getCount() - keep);
            if (usable <= 0) {
                keep = Math.max(0, keep - inSlot.getCount());   // 整格都留给排队的人
                continue;
            }
            int grab = Math.min(need, usable);
            ItemStack sim;
            try {
                sim = h.extractItem(loc.slot, grab, true);
            } catch (Throwable t) {
                drop(item, loc);
                continue;
            }
            if (sim == null || sim.isEmpty() || !sim.is(item)) {
                keep = Math.max(0, keep - inSlot.getCount());
                continue;
            }
            int canTake = sim.getCount();
            keep = Math.max(0, keep - (inSlot.getCount() - canTake));

            if (simulate) {
                g.stacks.add(sim.copyWithCount(canTake));
                g.taken += canTake;
                need -= canTake;
                continue;
            }

            ItemStack real;
            try {
                real = h.extractItem(loc.slot, canTake, false);
            } catch (Throwable t) {
                drop(item, loc);
                continue;
            }
            if (real == null || real.isEmpty()) {
                // 箱子反悔（同一 tick 内被人改动）：记录作废，实物交给下一次重扫补
                drop(item, loc);
                g.repairs++;
                repairs++;
                continue;
            }
            int moved = real.getCount();
            g.stacks.add(real);
            g.taken += moved;
            need -= moved;
            // ③ 就地更新缓存：这一格少了 moved 件，拿光就摘掉
            counts.merge(item, -moved, Integer::sum);
            loc.count -= moved;
            if (loc.count <= 0) drop(item, loc);
        }
        return g;
    }

    /** 作废一条料格记录（同时把它账面上的件数从 count 里摘掉）。 */
    private void drop(Item item, Loc loc) {
        if (loc.dead) return;
        loc.dead = true;
        if (loc.count > 0) counts.merge(item, -loc.count, Integer::sum);
        loc.count = 0;
    }

    /** 真实往材料箱里塞过东西之后（兜底回塞）通知索引：这个箱子重扫。 */
    public void dirtyChest(int chestIndex) {
        if (chestIndex < 0 || chestIndex >= chests.size()) return;
        rescanChest(chestIndex);
    }

    public boolean isChestDead(int chestIndex) {
        return chestIndex >= 0 && chestIndex < chestDead.length && chestDead[chestIndex];
    }

    public BlockPos posOf(int chestIndex) {
        return chestIndex >= 0 && chestIndex < chests.size() ? chests.get(chestIndex) : null;
    }

    /**
     * <b>r28（发布前防漏）</b>：会话收场时释放索引持有的全部引用。
     *
     * <p>索引里 {@code handlers[]} 是<b>方块实体能力</b>的活引用 —— 攥着一个 handler
     * 就等于攥着它的方块实体，进而可能连带整段区块不被卸载。会话结束（正常/掉线/换维度）
     * 后这份缓存一秒钟都不该多留，这里把料格记录、件数表和 handler 数组一并清干净。
     * 清完的索引不可再用（再摸就是空表），调用方也不该再用 —— 会话对象本身紧跟着就出表了。</p>
     */
    public void dispose() {
        for (ArrayList<Loc> locs : byItem.values()) locs.clear();
        byItem.clear();
        counts.clear();
        for (int i = 0; i < handlers.length; i++) handlers[i] = null;
    }

    /**
     * 日志用：此刻实物还剩几件（<b>不信缓存</b>，真去摸一遍有料的那几格）。
     *
     * <p>只在排障路径上调用，正常流程一律走 {@link #count(Item)}。</p>
     */
    public String describeLive(Item item, int cap) {
        if (item == null) return "（空物品）";
        int total = 0, visited = 0;
        for (Loc l : locsRaw(item)) {
            if (l.dead) continue;
            if (++visited > cap) return "（料格检视超 " + cap + "，未复查）缓存计 " + count(item) + " 件";
            IItemHandler h = handler(l.chest);
            if (h == null) continue;
            try {
                ItemStack st = h.getStackInSlot(l.slot);
                if (st != null && st.is(item)) total += st.getCount();
            } catch (Throwable ignored) {}
        }
        return total + " 件（缓存计 " + count(item) + " 件）";
    }
}
