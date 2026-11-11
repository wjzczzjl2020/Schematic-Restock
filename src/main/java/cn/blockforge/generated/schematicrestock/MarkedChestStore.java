package cn.blockforge.generated.schematicrestock;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器白名单（服务端权威）：存进玩家持久 NBT，重启不丢。
 *
 * <p>两张互相独立的表：</p>
 * <ul>
 *   <li>{@code marks} —— <b>材料箱</b>：Shift+右键（或右键）标记，补料接管只从这些箱子里取料；</li>
 *   <li>{@code outputs} —— <b>输出箱 / 溢出箱</b>（r7 新增）：Shift+左键标记，
 *       只进不出，专门收合成超发的产物与没处放的余料。<b>它永远不参与取料</b>，
 *       所以不会出现「产物进输出箱 → 下一轮又把它当原料抽走」的自循环。</li>
 * </ul>
 *
 * <p>每条记录是 {@code "维度ID;坐标long"}，只记有物品容器能力的方块。
 * 同一个方块只能在一个表里：标记成输出箱会自动从材料箱里摘掉，反之亦然。</p>
 */
public final class MarkedChestStore {

    private static final String TAG_ROOT = "schematic_restock";
    private static final String TAG_MARKS = "marks";
    private static final String TAG_OUTPUTS = "outputs";

    private MarkedChestStore() {}

    public static List<BlockPos> marksIn(Player player, Level level) {
        return positionsIn(player, level, list(player, TAG_MARKS));
    }

    /** 某个名单标签（{@code marks} / {@code outputs}）；不存在就先建空的。 */
    private static ListTag list(Player player, String tag) {
        CompoundTag root = root(player);
        if (!root.contains(tag)) root.put(tag, new ListTag());
        return root.getList(tag, Tag.TAG_STRING);
    }

    /** 输出箱名单（服务端侧快捷取法）。 */
    private static ListTag outputs(Player player) {
        return list(player, TAG_OUTPUTS);
    }

    /** 输出箱（溢出箱）名单：只进不出。 */
    public static List<BlockPos> outputsIn(Player player, Level level) {
        return positionsIn(player, level, list(player, TAG_OUTPUTS));
    }

    private static List<BlockPos> positionsIn(Player player, Level level, ListTag entries) {
        String dim = level.dimension().location().toString();
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < entries.size() && out.size() < SR.MAX_CONTAINERS; i++) {
            String s = entries.getString(i);
            int sep = s.indexOf(';');
            if (sep <= 0) continue;
            if (!s.substring(0, sep).equals(dim)) continue;
            try {
                out.add(BlockPos.of(Long.parseLong(s.substring(sep + 1))));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public static int count(Player player) {
        return list(player, TAG_MARKS).size();
    }

    public static int outputCount(Player player) {
        return list(player, TAG_OUTPUTS).size();
    }

    public static void clear(Player player) {
        root(player).put(TAG_MARKS, new ListTag());
        root(player).put(TAG_OUTPUTS, new ListTag());
    }


    // ---------------- 容器判定 ----------------

    /** r24：客户端侧「这方块像不像容器」的三档结论。 */
    public enum ContainerGuess {
        /** 确定是：拿到了 {@code ItemHandler} 能力，或方块实体本身实现了原版 {@code Container}。 */
        YES,
        /** 判不准：有方块实体但客户端拿不到能力（模组储物常见：能力只在服务端注册）→ 交给服务端定夺。 */
        MAYBE,
        /** 确定不是：空气、泥土、石头这类没有方块实体、也没有能力的方块。 */
        NO
    }

    /**
     * r24：客户端预检。<b>只在「确定不是」时才拦</b>玩家右键 ——
     * 判错一次就等于把能用的材料箱踢出标记名单，代价远比「多问一次服务端」大，
     * 所以模组储物一律走 {@link ContainerGuess#MAYBE}，最后由服务端权威判定。
     */
    public static ContainerGuess guessContainer(Level level, BlockPos pos) {
        if (level == null || pos == null) return ContainerGuess.NO;
        try {
            if (hasContainer(level, pos)) return ContainerGuess.YES;
            if (level.getBlockEntity(pos) instanceof Container) return ContainerGuess.YES;
            // 到这里：没有能力、也不是原版容器。有方块实体 → 可能是模组储物，别急着说不行。
            if (level.getBlockState(pos).hasBlockEntity()) return ContainerGuess.MAYBE;
            return ContainerGuess.NO;
        } catch (Throwable t) {
            return ContainerGuess.MAYBE;    // 判定本身出异常 → 同样交给服务端
        }
    }

    /**
     * 是否有物品容器能力（补料取得到这个 handler 才搬得动，所以这就是唯一硬标准）。
     *
     * <p>能力查询对个别模组方块可能抛异常，一律当「没有能力」处理。</p>
     */
    static boolean hasContainer(Level level, BlockPos pos) {
        try {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos,
                    (net.minecraft.core.Direction) null);
            return handler != null && handler.getSlots() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- C2S 处理 ----------------

    /** 右键：材料箱标记 / 取消。与输出箱互斥（同一个方块只能是一种）。 */
    public static void handleToggle(Object playerObj, Packets.MarkToggle p) {
        ServerPlayer sp = asServer(playerObj);
        if (sp == null) return;
        BlockPos pos = BlockPos.of(p.pos());

        ListTag list = marks(sp);
        String entry = dimKey(sp) + ";" + pos.asLong();
        boolean removing = contains(list, entry);

        if (!removing) {
            // 客户端已经拦过一道（确定不是容器就不会发包），这里兜底：
            // 客户端判「也许能」（模组储物）但服务端真拿不到 item handler 的，走这条。
            if (!hasContainer(sp.level(), pos)) {
                warn(sp, "§c✗ " + nameOf(sp, pos) + " 不是可存取材料的容器，无法标记");
                return;
            }
            if (list.size() >= SR.MAX_CONTAINERS) {
                warn(sp, "§c✗ 材料箱总数已达上限 " + SR.MAX_CONTAINERS
                        + " 个，先用 /matfill clearmarks 清理");
                return;
            }
            list.add(StringTag.valueOf(entry));
            // 互斥：它原来要是被标成输出箱，这里必须摘掉，否则同一个箱子既进又出，
            // 「产物进输出箱 → 下轮又被当原料抽走」的自循环就是这么开出来的。
            boolean wasOutput = remove(outputs(sp), entry);
            ok(sp, "✔ 已标记材料箱（" + list.size() + "/" + SR.MAX_CONTAINERS + "）"
                    + (wasOutput ? " [已从输出箱移出]" : ""));
        } else {
            remove(list, entry);
            undo(sp, "已取消材料箱标记（剩 " + list.size() + " 个）");
        }
        sync(sp);
    }

    /**
     * Shift+左键：输出箱（溢出箱）标记 / 取消。
     *
     * <p>输出箱<b>只进不出</b>：补料接管的取料名单（{@code TakeoverEngine.resolveChests}）
     * 明确把它们排除在外，幽灵合成的普查也只看材料箱。它只负责收三样东西：
     * 合成超发的产物、背包塞不下的缺口件、任务收尾时的余料。</p>
     */
    public static void handleOutputToggle(Object playerObj, Packets.MarkOutputToggle p) {
        ServerPlayer sp = asServer(playerObj);
        if (sp == null) return;
        BlockPos pos = BlockPos.of(p.pos());

        ListTag list = outputs(sp);
        String entry = dimKey(sp) + ";" + pos.asLong();
        boolean removing = contains(list, entry);

        if (!removing) {
            if (!hasContainer(sp.level(), pos)) {
                warn(sp, "§c✗ " + nameOf(sp, pos) + " 不是可存取材料的容器，不能当输出箱");
                return;
            }
            if (list.size() >= SR.MAX_OUTPUTS) {
                warn(sp, "§c✗ 输出箱已达上限 " + SR.MAX_OUTPUTS + " 个，先用 /matfill clearmarks 清理");
                return;
            }
            list.add(StringTag.valueOf(entry));
            boolean wasMaterial = remove(marks(sp), entry);
            ok(sp, "✔ 已标记输出箱（" + list.size() + "/" + SR.MAX_OUTPUTS + "，只进不出）"
                    + (wasMaterial ? " [已从材料箱移出]" : ""));
        } else {
            remove(list, entry);
            undo(sp, "已取消输出箱标记（剩 " + list.size() + " 个）");
        }
        sync(sp);
    }

    /**
     * Shift+右键两下：框选批量标材料箱。
     *
     * <p><b>r24 优化 2：单次上限 16 → 64。</b>超限的处理仍然<b>整批拒绝</b>而不是偷偷截断——
     * 截断会让玩家以为「框过了」，回头缺一堆材料才发现有一半箱子没标。</p>
     */
    public static void handleBox(Object playerObj, Packets.MarkBox p) {
        ServerPlayer sp = asServer(playerObj);
        if (sp == null) return;
        BlockPos a = BlockPos.of(p.a());
        BlockPos b = BlockPos.of(p.b());

        long dx = Math.abs(a.getX() - b.getX()) + 1;
        long dy = Math.abs(a.getY() - b.getY()) + 1;
        long dz = Math.abs(a.getZ() - b.getZ()) + 1;
        if (dx * dy * dz > SR.BOX_SCAN_VOLUME_CAP) {
            warn(sp, "§c✗ 框选范围过大（上限 " + SR.BOX_SCAN_VOLUME_CAP + " 格，本次 " + (dx * dy * dz) + " 格）");
            return;
        }

        // 收集范围内所有容器方块。容量按上限预分配（r24：64 个坐标一次性加满也不扩容）。
        List<BlockPos> found = new ArrayList<>(SR.MAX_BOX_MARKS + 1);
        AABB box = AABB.encapsulatingFullBlocks(a, b);
        int minX = Mth.floor(box.minX), maxX = Mth.ceil(box.maxX);
        int minY = Mth.floor(box.minY), maxY = Mth.ceil(box.maxY);
        int minZ = Mth.floor(box.minZ), maxZ = Mth.ceil(box.maxZ);
        outer:
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (hasContainer(sp.level(), pos)) {
                        found.add(pos);
                        if (found.size() > SR.MAX_BOX_MARKS) break outer; // 只要证明超限即可停
                    }
                }
            }
        }

        if (found.size() > SR.MAX_BOX_MARKS) {
            warn(sp, "§c✗ 单次框选最多标记 " + SR.MAX_BOX_MARKS + " 个容器（本范围已经超过 "
                    + SR.MAX_BOX_MARKS + " 个，请缩小范围分批框）");
            return;
        }
        if (found.isEmpty()) {
            warn(sp, "§c✗ 框选范围内没有容器方块（两端点之间的长方体里一个箱子都没有）");
            return;
        }

        ListTag list = marks(sp);
        String dim = dimKey(sp);
        int added = 0, dup = 0;
        boolean capped = false;
        // 这里是「读一份 found → 逐条写 ListTag」的单向流程：found 建完就不再动，
        // 写入的是 NBT 的 ListTag（不是正在遍历的集合），所以 64 个坐标也不会出并发修改。
        for (BlockPos pos : found) {
            String entry = dim + ";" + pos.asLong();
            if (contains(list, entry)) { dup++; continue; }
            if (list.size() >= SR.MAX_CONTAINERS) { capped = true; break; }
            list.add(StringTag.valueOf(entry));
            added++;
        }
        if (capped) {
            warn(sp, "§c✗ 材料箱总数已达上限 " + SR.MAX_CONTAINERS + " 个，本批只收进 " + added
                    + " 个（框到 " + found.size() + " 个容器）");
        } else if (added > 0) {
            ok(sp, "✔ 框选标记 +" + added + " 个（范围内 " + found.size() + " 个容器，"
                    + dup + " 个原本已标 · 共 " + list.size() + "/" + SR.MAX_CONTAINERS + "）");
        } else {
            undo(sp, "框到的 " + found.size() + " 个容器早已全部标记，无需重复");
        }
        sync(sp);
    }

    public static void handleClear(Object playerObj) {
        ServerPlayer sp = asServer(playerObj);
        if (sp == null) return;
        clear(sp);
        undo(sp, "已清空全部标记（材料箱与输出箱一起清）");
        sync(sp);
    }

    /** 玩家进服/换维度时补发一次数量同步。 */
    public static void sync(ServerPlayer sp) {
        PacketDistributor.sendToPlayer(sp, new Packets.MarkCount(count(sp), outputCount(sp)));
    }

    // ---------------- 工具 ----------------

    private static ServerPlayer asServer(Object o) {
        return o instanceof ServerPlayer sp ? sp : null;
    }

    private static String dimKey(ServerPlayer sp) {
        return sp.level().dimension().location().toString();
    }

    private static CompoundTag root(Player player) {
        CompoundTag tag = player.getPersistentData();
        if (!tag.contains(TAG_ROOT)) tag.put(TAG_ROOT, new CompoundTag());
        return tag.getCompound(TAG_ROOT);
    }

    private static ListTag marks(Player player) {
        CompoundTag root = root(player);
        if (!root.contains(TAG_MARKS)) root.put(TAG_MARKS, new ListTag());
        return root.getList(TAG_MARKS, Tag.TAG_STRING);
    }

    private static boolean contains(ListTag list, String entry) {
        for (int i = 0; i < list.size(); i++) if (entry.equals(list.getString(i))) return true;
        return false;
    }

    /** 从名单里摘掉一项，返回是否真的摘掉了（调用方要据此提示「原本它是输出箱」）。 */
    private static boolean remove(ListTag list, String entry) {
        for (int i = 0; i < list.size(); i++) {
            if (entry.equals(list.getString(i))) { list.remove(i); return true; }
        }
        return false;
    }

    /**
     * r24：目标方块的显示名（失败就退回「该方块」，绝不因为取名字把主流程带崩）。
     * 提示里带名字，玩家才知道自己到底点的是泥土还是箱子。
     */
    private static String nameOf(ServerPlayer sp, BlockPos pos) {
        try {
            net.minecraft.world.level.block.state.BlockState st = sp.level().getBlockState(pos);
            if (st.isAir()) return "空气";
            return st.getBlock().getName().getString();
        } catch (Throwable t) {
            return "该方块";
        }
    }

    /**
     * r24：本次右键<b>什么都没做成</b>的提示 —— 屏幕中间（actionbar）红字 + 一声闷「噗」。
     *
     * <p>原先走聊天栏（{@code reject}），玩家盯着准星根本看不见，只感觉「按了没反应」。
     * 现在和「不是容器」这类判定放在同一处，一律中间红字，听得见也看得见。</p>
     */
    private static void warn(ServerPlayer sp, String msg) {
        sp.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.RED), true);
        notify_(sp, SchematicRestockMod.SND_NOOP.get(), 0.8f, 0.75f);
    }

    /** r24：标记成功 —— 中间绿字 + 上扬短叮（音量小，不吵）。 */
    private static void ok(ServerPlayer sp, String msg) {
        sp.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.GREEN), true);
        notify_(sp, SchematicRestockMod.SND_OK.get(), 0.7f, 1.25f);
    }

    /** r24：取消标记 / 无需重复操作 —— 中间灰字 + 轻「噗」（高音调，短促）。 */
    private static void undo(ServerPlayer sp, String msg) {
        sp.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.GRAY), true);
        notify_(sp, SchematicRestockMod.SND_NOOP.get(), 0.6f, 1.3f);
    }

    /**
     * 只给这一个玩家播音效（{@code playNotifySound} 走客户端定向包，不做世界广播）。
     * 个别整合包会把自定义 SoundEvent 的播放路径吃掉，出任何异常都只当没响。
     */
    private static void notify_(ServerPlayer sp, net.minecraft.sounds.SoundEvent ev, float vol, float pitch) {
        try {
            if (ev != null) sp.playNotifySound(ev, net.minecraft.sounds.SoundSource.PLAYERS, vol, pitch);
        } catch (Throwable ignored) {}
    }
}
