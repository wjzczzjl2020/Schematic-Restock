package cn.blockforge.generated.schematicrestock;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 自定义网络载荷。字符串/整数读写只用 ByteBuf 的基础方法，避开环境里不认识的 API。 */
public final class Packets {

    private Packets() {}

    static void writeString(ByteBuf buf, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(b.length);
        buf.writeBytes(b);
    }

    static String readString(ByteBuf buf) {
        int len = buf.readInt();
        if (len < 0 || len > 65536) return "";
        byte[] b = new byte[len];
        buf.readBytes(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** 一种材料的请求/剩余条目（物品 ID + 件数）。 */
    public record Entry(String id, int count) {
        public static final StreamCodec<ByteBuf, Entry> CODEC = new StreamCodec<>() {
            @Override public Entry decode(ByteBuf buf) { return new Entry(readString(buf), buf.readInt()); }
            @Override public void encode(ByteBuf buf, Entry e) { writeString(buf, e.id()); buf.writeInt(e.count()); }
        };
    }

    // ---------- C2S：标记材料箱（单箱切换） ----------
    public record MarkToggle(long pos) implements CustomPacketPayload {
        public static final Type<MarkToggle> TYPE = new Type<>(SR.id("mark_toggle"));
        public static final StreamCodec<ByteBuf, MarkToggle> CODEC = new StreamCodec<>() {
            @Override public MarkToggle decode(ByteBuf buf) { return new MarkToggle(buf.readLong()); }
            @Override public void encode(ByteBuf buf, MarkToggle p) { buf.writeLong(p.pos()); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------- C2S：Shift+左键标记输出箱（溢出箱，只进不出） ----------
    public record MarkOutputToggle(long pos) implements CustomPacketPayload {
        public static final Type<MarkOutputToggle> TYPE = new Type<>(SR.id("mark_output_toggle"));
        public static final StreamCodec<ByteBuf, MarkOutputToggle> CODEC = new StreamCodec<>() {
            @Override public MarkOutputToggle decode(ByteBuf buf) { return new MarkOutputToggle(buf.readLong()); }
            @Override public void encode(ByteBuf buf, MarkOutputToggle p) { buf.writeLong(p.pos()); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------- C2S：框选批量标记（两个对角） ----------
    public record MarkBox(long a, long b) implements CustomPacketPayload {
        public static final Type<MarkBox> TYPE = new Type<>(SR.id("mark_box"));
        public static final StreamCodec<ByteBuf, MarkBox> CODEC = new StreamCodec<>() {
            @Override public MarkBox decode(ByteBuf buf) { return new MarkBox(buf.readLong(), buf.readLong()); }
            @Override public void encode(ByteBuf buf, MarkBox p) { buf.writeLong(p.a()); buf.writeLong(p.b()); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------- C2S：清空标记 ----------
    public record ClearMarks() implements CustomPacketPayload {
        public static final Type<ClearMarks> TYPE = new Type<>(SR.id("clear_marks"));
        public static final StreamCodec<ByteBuf, ClearMarks> CODEC = new StreamCodec<>() {
            @Override public ClearMarks decode(ByteBuf buf) { return new ClearMarks(); }
            @Override public void encode(ByteBuf buf, ClearMarks p) { }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------- C2S：开始补料接管（缺料清单） ----------
    public record StartTakeover(List<Entry> entries) implements CustomPacketPayload {
        public static final Type<StartTakeover> TYPE = new Type<>(SR.id("start_takeover"));
        public static final StreamCodec<ByteBuf, StartTakeover> CODEC = new StreamCodec<>() {
            @Override public StartTakeover decode(ByteBuf buf) {
                int n = clampCount(buf.readInt());
                List<Entry> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(Entry.CODEC.decode(buf));
                return new StartTakeover(list);
            }
            @Override public void encode(ByteBuf buf, StartTakeover p) {
                buf.writeInt(p.entries().size());
                for (Entry e : p.entries()) Entry.CODEC.encode(buf, e);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------- S2C：已标记箱数量同步 ----------
    // v4：加了输出箱数量。旧客户端只读第一个 int，字段顺序变了会错位，故同步抬协议版本拦住旧端。
    public record MarkCount(int count, int outputs) implements CustomPacketPayload {
        public static final Type<MarkCount> TYPE = new Type<>(SR.id("mark_count"));
        public static final StreamCodec<ByteBuf, MarkCount> CODEC = new StreamCodec<>() {
            @Override public MarkCount decode(ByteBuf buf) { return new MarkCount(buf.readInt(), buf.readInt()); }
            @Override public void encode(ByteBuf buf, MarkCount p) {
                buf.writeInt(p.count());
                buf.writeInt(p.outputs());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------- S2C：补料结果总结（硬数） ----------
    // sessionKey: 会话号，客户端据此区分新旧会话的投料累计（v3 新增）
    // placed: 本会话真实投进背包/容器的每品种累计件数 —— 「缺口账本」的唯一数据来源（v3 新增）
    // craftFails: 幽灵合成算不出/放不下的物品与原因（v3 新增）
    public record TakeoverResult(long sessionKey, int written, int skipped, int missingTypes,
                                 boolean inventoryFull, boolean capped,
                                 List<Entry> remaining, String note,
                                 List<Entry> placed, List<Entry> craftFails, int craftConsumed)
            implements CustomPacketPayload {
        public static final Type<TakeoverResult> TYPE = new Type<>(SR.id("takeover_result"));
        public static final StreamCodec<ByteBuf, TakeoverResult> CODEC = new StreamCodec<>() {
            @Override public TakeoverResult decode(ByteBuf buf) {
                long key = buf.readLong();
                int w = buf.readInt();
                int s = buf.readInt();
                int m = buf.readInt();
                boolean f = buf.readBoolean();
                boolean c = buf.readBoolean();
                int n = clampCount(buf.readInt());
                List<Entry> rest = new ArrayList<>(n);
                for (int i = 0; i < n; i++) rest.add(Entry.CODEC.decode(buf));
                String noteStr = readString(buf);
                int pn = clampCount(buf.readInt());
                List<Entry> placedList = new ArrayList<>(pn);
                for (int i = 0; i < pn; i++) placedList.add(Entry.CODEC.decode(buf));
                int cn = clampCount(buf.readInt());
                List<Entry> fails = new ArrayList<>(cn);
                for (int i = 0; i < cn; i++) fails.add(Entry.CODEC.decode(buf));
                int consumed = buf.readInt();
                return new TakeoverResult(key, w, s, m, f, c, rest, noteStr, placedList, fails, consumed);
            }
            @Override public void encode(ByteBuf buf, TakeoverResult p) {
                buf.writeLong(p.sessionKey());
                buf.writeInt(p.written()); buf.writeInt(p.skipped()); buf.writeInt(p.missingTypes());
                buf.writeBoolean(p.inventoryFull()); buf.writeBoolean(p.capped());
                buf.writeInt(p.remaining().size());
                for (Entry e : p.remaining()) Entry.CODEC.encode(buf, e);
                writeString(buf, p.note() == null ? "" : p.note());
                buf.writeInt(p.placed().size());
                for (Entry e : p.placed()) Entry.CODEC.encode(buf, e);
                buf.writeInt(p.craftFails().size());
                for (Entry e : p.craftFails()) Entry.CODEC.encode(buf, e);
                buf.writeInt(p.craftConsumed());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------- S2C：搬运进度（服务端按 tick 推进，节流下发） ----------
    // placedSnapshot 非空时携带「本会话累计真实投料」的每品种快照，客户端据此增量喂缺口账本。
    // r14 新增两位：phase（0 = 阶段 A 搬运，1 = 阶段 B 合成）、noRecipe（已判定「查无工作台配方」的种类数）。
    public record TakeoverProgress(long sessionKey, int typesDone, int typesTotal, int moved, int skipped,
                                   boolean hasPlacedSnapshot, List<Entry> placedSnapshot, int craftSteps,
                                   int phase, int noRecipeTypes)
            implements CustomPacketPayload {
        public static final Type<TakeoverProgress> TYPE = new Type<>(SR.id("takeover_progress"));
        public static final StreamCodec<ByteBuf, TakeoverProgress> CODEC = new StreamCodec<>() {
            @Override public TakeoverProgress decode(ByteBuf buf) {
                long key = buf.readLong();
                int done = buf.readInt();
                int total = buf.readInt();
                int moved = buf.readInt();
                int skipped = buf.readInt();
                boolean has = buf.readBoolean();
                int n = has ? clampCount(buf.readInt()) : 0;
                List<Entry> snap = new ArrayList<>(n);
                for (int i = 0; i < n; i++) snap.add(Entry.CODEC.decode(buf));
                int steps = buf.readInt();
                int phase = buf.readInt();
                int noRecipe = buf.readInt();
                return new TakeoverProgress(key, done, total, moved, skipped, has, snap, steps, phase, noRecipe);
            }
            @Override public void encode(ByteBuf buf, TakeoverProgress p) {
                buf.writeLong(p.sessionKey());
                buf.writeInt(p.typesDone()); buf.writeInt(p.typesTotal());
                buf.writeInt(p.moved()); buf.writeInt(p.skipped());
                buf.writeBoolean(p.hasPlacedSnapshot());
                if (p.hasPlacedSnapshot()) {
                    buf.writeInt(p.placedSnapshot().size());
                    for (Entry e : p.placedSnapshot()) Entry.CODEC.encode(buf, e);
                }
                buf.writeInt(p.craftSteps());
                buf.writeInt(p.phase());
                buf.writeInt(p.noRecipeTypes());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static int clampCount(int n) {
        return n < 0 ? 0 : Math.min(n, SR.MAX_TYPES * 4);
    }

    // ==================================================================
    // r16 自动建造
    // ==================================================================

    /**
     * C2S：建造计划（<b>分帧上传</b>）。
     *
     * <p>为什么要分帧：一张 20 万方块的大图纸如果打成一个包，直接撞自定义载荷的大小上限；
     * 客户端按 {@link SR#BUILD_CHUNK} 一条一包地在 tick 里往外发，服务端攒完再开工。</p>
     *
     * <p>形态表（palette）只在 {@code first=true} 的那一包里出现，之后每包只有
     * 「世界坐标 long + 形态下标 short」。形态字符串的格式是
     * {@code minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]}，
     * 即<b>原理图里烘好了朝向的完整 BlockState</b>——服务端原样还原，不做任何朝向推算。</p>
     */
    public record BuildPlan(long planId, int seq, boolean first, boolean last, int total, int policy,
                            List<String> palette, List<Long> positions, List<Integer> states)
            implements CustomPacketPayload {
        public static final Type<BuildPlan> TYPE = new Type<>(SR.id("build_plan"));
        public static final StreamCodec<ByteBuf, BuildPlan> CODEC = new StreamCodec<>() {
            @Override public BuildPlan decode(ByteBuf buf) {
                long id = buf.readLong();
                int seq = buf.readInt();
                boolean first = buf.readBoolean();
                boolean last = buf.readBoolean();
                int total = buf.readInt();
                int policy = buf.readByte();
                List<String> pal = List.of();
                if (first) {
                    int pn = clamp(buf.readInt(), SR.BUILD_MAX_PALETTE);
                    pal = new ArrayList<>(pn);
                    for (int i = 0; i < pn; i++) pal.add(readString(buf));
                }
                int n = clamp(buf.readInt(), SR.BUILD_CHUNK * 2);
                List<Long> pl = new ArrayList<>(n);
                for (int i = 0; i < n; i++) pl.add(buf.readLong());
                List<Integer> sl = new ArrayList<>(n);
                for (int i = 0; i < n; i++) sl.add(buf.readUnsignedShort());
                return new BuildPlan(id, seq, first, last, total, policy, pal, pl, sl);
            }
            @Override public void encode(ByteBuf buf, BuildPlan p) {
                buf.writeLong(p.planId());
                buf.writeInt(p.seq());
                buf.writeBoolean(p.first());
                buf.writeBoolean(p.last());
                buf.writeInt(p.total());
                buf.writeByte(p.policy());
                if (p.first()) {
                    buf.writeInt(p.palette().size());
                    for (String s : p.palette()) writeString(buf, s);
                }
                int n = Math.min(p.positions().size(), p.states().size());
                buf.writeInt(n);
                for (int i = 0; i < n; i++) buf.writeLong(p.positions().get(i));
                for (int i = 0; i < n; i++) buf.writeShort(p.states().get(i) & 0xFFFF);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S：建造控制。op：1=暂停 2=继续 3=取消（放好的方块不回滚）。 */
    public record BuildControl(int op) implements CustomPacketPayload {
        public static final Type<BuildControl> TYPE = new Type<>(SR.id("build_control"));
        public static final StreamCodec<ByteBuf, BuildControl> CODEC = new StreamCodec<>() {
            @Override public BuildControl decode(ByteBuf buf) { return new BuildControl(buf.readByte()); }
            @Override public void encode(ByteBuf buf, BuildControl p) { buf.writeByte(p.op()); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * S2C：建造进度。
     *
     * @param state     0=空闲 1=进行中 2=已暂停 3=完成 4=已取消 5=出错
     * @param queued    服务端<b>实际收下</b>的条目数（客户端上传总数可能因为形态非法被剔掉，
     *                  HUD 的分母要用这个数，不能用自己扫出来的那个）
     * @param consumed  非空时携带「本会话累计消耗件数」，客户端据此<b>反向扣减投料账本</b>
     */
    public record BuildProgress(long planId, int state, int placed, int skipped, int blocked,
                                int missing, int deferred, int queued,
                                boolean hasConsumed, List<Entry> consumed)
            implements CustomPacketPayload {
        public static final Type<BuildProgress> TYPE = new Type<>(SR.id("build_progress"));
        public static final StreamCodec<ByteBuf, BuildProgress> CODEC = new StreamCodec<>() {
            @Override public BuildProgress decode(ByteBuf buf) {
                long id = buf.readLong();
                int state = buf.readInt();
                int placed = buf.readInt();
                int skipped = buf.readInt();
                int blocked = buf.readInt();
                int missing = buf.readInt();
                int deferred = buf.readInt();
                int queued = buf.readInt();
                boolean has = buf.readBoolean();
                int n = has ? clamp(buf.readInt(), 2048) : 0;
                List<Entry> c = new ArrayList<>(n);
                for (int i = 0; i < n; i++) c.add(Entry.CODEC.decode(buf));
                return new BuildProgress(id, state, placed, skipped, blocked, missing, deferred, queued, has, c);
            }
            @Override public void encode(ByteBuf buf, BuildProgress p) {
                buf.writeLong(p.planId());
                buf.writeInt(p.state()); buf.writeInt(p.placed()); buf.writeInt(p.skipped());
                buf.writeInt(p.blocked()); buf.writeInt(p.missing()); buf.writeInt(p.deferred());
                buf.writeInt(p.queued());
                buf.writeBoolean(p.hasConsumed());
                if (p.hasConsumed()) {
                    buf.writeInt(p.consumed().size());
                    for (Entry e : p.consumed()) Entry.CODEC.encode(buf, e);
                }
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C：建造收尾总结。 */
    public record BuildResult(long planId, int state, int placed, int fluidPlaced, int bePlaced,
                              int skipped, int blocked, int noItem, int badState,
                              int missingPieces, List<Entry> missingList, String note)
            implements CustomPacketPayload {
        public static final Type<BuildResult> TYPE = new Type<>(SR.id("build_result"));
        public static final StreamCodec<ByteBuf, BuildResult> CODEC = new StreamCodec<>() {
            @Override public BuildResult decode(ByteBuf buf) {
                long id = buf.readLong();
                int state = buf.readInt();
                int placed = buf.readInt();
                int fluid = buf.readInt();
                int be = buf.readInt();
                int skipped = buf.readInt();
                int blocked = buf.readInt();
                int noItem = buf.readInt();
                int bad = buf.readInt();
                int miss = buf.readInt();
                int n = clamp(buf.readInt(), 2048);
                List<Entry> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(Entry.CODEC.decode(buf));
                return new BuildResult(id, state, placed, fluid, be, skipped, blocked, noItem, bad,
                        miss, list, readString(buf));
            }
            @Override public void encode(ByteBuf buf, BuildResult p) {
                buf.writeLong(p.planId());
                buf.writeInt(p.state()); buf.writeInt(p.placed()); buf.writeInt(p.fluidPlaced());
                buf.writeInt(p.bePlaced()); buf.writeInt(p.skipped()); buf.writeInt(p.blocked());
                buf.writeInt(p.noItem()); buf.writeInt(p.badState()); buf.writeInt(p.missingPieces());
                buf.writeInt(p.missingList().size());
                for (Entry e : p.missingList()) Entry.CODEC.encode(buf, e);
                writeString(buf, p.note() == null ? "" : p.note());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static int clamp(int n, int max) {
        return n < 0 ? 0 : Math.min(n, max);
    }
}
