package cn.blockforge.generated.schematicrestock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 反射读取 Forgematica（投影 fi.dy.masa.litematica）当前生效放置的缺料清单。
 * 调用链在调研阶段确认，全部为 public getter：
 *   DataManager.getSchematicPlacementManager() -> getAllSchematicsPlacements()
 *     -> SchematicPlacement.isEnabled()/getMaterialList()
 *       -> MaterialListBase.getMaterialsAll() -> MaterialListEntry.getStack()/getCountMissing()
 * getCountMissing() 为件数（int）。清单由投影异步统计，未就绪时本类触发
 * reCreateMaterialList() 促算并等待下一轮。
 * 反射失败时状态为 FAIL —— 界面据此把 V+M 灰掉。
 */
public final class RestockBridge {

    public enum Status { OK, READING, FAIL }

    /** 一种缺料：样本堆叠 + 还缺多少件。 */
    public record Shortfall(ItemStack stack, int missing) {}

    private static final String[] DATA_MANAGER = {
            "fi.dy.masa.litematica.data.DataManager",
            "org.thinkingstudio.forgematica.data.DataManager"
    };

    private static Status status = Status.FAIL;
    private static List<Shortfall> snapshot = List.of();
    private static long lastReadMs = 0;
    private static long lastPromoteMs = 0;
    private static volatile boolean forceRecount = false;
    private static String diag = "未初始化";

    /** 自测模式：无 Forgematica 时用假缺料验证全流程。 */
    private static boolean simulated = false;

    /**
     * 上次读取时经手的<b>材料列表对象本身</b>（MaterialListBase 实例，按身份保留引用）。
     * MaterialSyncClient 要写的就是这些活体对象——L 键界面和 HUD 渲染时都是从它们身上现取数字，
     * 写这里才能让界面同步刷新。
     */
    private static List<Object> liveLists = List.of();

    private RestockBridge() {}

    public static boolean isSimulated() { return simulated; }
    public static void setSimulated(boolean on) { simulated = on; }
    public static Status status() { return status; }
    public static List<Shortfall> snapshot() { return snapshot; }
    public static String diagnostics() { return diag; }

    /** 当前生效的材料列表对象（可能是空表：没加载原理图，或读取失败）。 */
    public static List<Object> liveLists() { return liveLists; }

    /** 缺料种数（x）。 */
    public static int types() { return snapshot.size(); }

    /** 缺料总件数（y，件数不是组数）。 */
    public static int totalMissing() {
        int t = 0;
        for (Shortfall s : snapshot) t += s.missing();
        return t;
    }

    /** 读取是否成功且非空（决定 V+M 亮/灰）。 */
    public static boolean ready() {
        return status == Status.OK && !snapshot.isEmpty() && totalMissing() > 0;
    }

    /**
     * 搬完之后请投影重算材料清单：清单是投影缓存的，物品进了背包/潜影盒之后
     * 缺口数字不会自己降。这里只下一次读取时额外调一次 reCreateMaterialList()
     * （投影内部是排度任务，约 1 秒后生效），并把读取节流清零。
     */
    public static void requestRecount() {
        forceRecount = true;
        lastReadMs = 0;
        lastPromoteMs = 0;
    }

    /** 轮询入口：客户端 tick 调用，自带节流（每 400ms 至多重扫一次，单次封顶 2s）。 */
    public static void refresh() {
        refresh(false);
    }

    /**
     * r8 Bug②：{@code force = true} 时绕开 400ms 节流，<b>立刻</b>现读一次。
     *
     * <p>V+M 发包前必须走这条路：动态校验刚把投料账本改小，投影列表也当场被改写回原始缺口，
     * 但 {@code snapshot} 还是最多 400ms 前的旧值 —— 不现读就会出现
     * 「界面显示缺 5 件、发出去的是缺 0 件」，或者反过来「按钮还是灰的，因为上次快照被扣成 0」。</p>
     *
     * <p>注意它<b>不会</b>催投影重算（{@link #requestRecount()} 才会）：重算是异步的，
     * 会把原始数字先刷回来，跟我们的账本抢口径，只适合搬完之后做收敛用。</p>
     */
    public static void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastReadMs < 400) return;
        lastReadMs = now;

        if (simulated) {
            List<Shortfall> list = new ArrayList<>();
            list.add(new Shortfall(stackOf("minecraft:oak_planks"), 137));
            list.add(new Shortfall(stackOf("minecraft:cobblestone"), 256));
            list.add(new Shortfall(stackOf("minecraft:iron_ingot"), 66));
            snapshot = list;
            status = Status.OK;
            diag = "自测模式（模拟缺料：橡木木板137/圆石256/铁锭66）";
            return;
        }

        try {
            long deadline = System.nanoTime() + SR.RESCAN_CAP_MS * 1_000_000L;
            List<Shortfall> merged = new ArrayList<>();
            List<Object> lists = new ArrayList<>();
            String detail = read(merged, lists, deadline);
            liveLists = List.copyOf(lists);
            diag = detail;
            if (!merged.isEmpty()) {
                status = Status.OK;
            } else if ("EMPTY_LIST".equals(detail) || "PARTIAL_TIMEOUT".equals(detail)) {
                status = Status.READING; // 接口在、清单未算完
            } else {
                status = Status.FAIL;
            }
            snapshot = merged;
        } catch (Throwable t) {
            status = Status.FAIL;
            snapshot = List.of();
            liveLists = List.of();
            diag = "读取失败: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** @return 诊断信息；EMPTY_LIST 表示接口可用但清单暂空。listsOut 收集经手的材料列表对象。 */
    private static String read(List<Shortfall> out, List<Object> listsOut, long deadlineNanos) throws Exception {
        boolean recount = forceRecount;
        forceRecount = false;
        Class<?> dmClass = null;
        for (String name : DATA_MANAGER) {
            try { dmClass = Class.forName(name); break; } catch (ClassNotFoundException ignored) {}
        }
        if (dmClass == null) return "未找到 Forgematica（DataManager 类不存在）";

        // L 键界面当前打开的那份清单（可能为 null）。它和下面按放置取的清单通常是同一个对象，
        // 但「上次打开的清单」在被切换过放置后可能另有一份，两个都收下才能保证界面一定同步。
        try {
            Object shown = callStatic(dmClass, "getMaterialList");
            if (shown != null) listsOut.add(shown);
        } catch (Throwable ignored) {}

        Object mgr = callStatic(dmClass, "getSchematicPlacementManager");
        if (mgr == null) return "getSchematicPlacementManager() 返回 null";

        Object placementsObj = callAny(mgr, "getAllSchematicsPlacements", "getAllPlacements");
        if (!(placementsObj instanceof List<?> placements)) return "无法取得放置列表";
        if (placements.isEmpty()) return "没有已加载的原理图放置";

        int enabled = 0;
        Map<String, Integer> acc = new LinkedHashMap<>();
        Map<String, ItemStack> sample = new LinkedHashMap<>();

        for (Object placement : placements) {
            if (System.nanoTime() > deadlineNanos) return "PARTIAL_TIMEOUT";
            Object en = callAny(placement, "isEnabled");
            if (!(en instanceof Boolean b) || !b) continue;
            enabled++;

            Object matList = callAny(placement, "getMaterialList");
            if (matList == null) continue;
            if (listsOut.size() < SR.SYNC_MAX_LISTS && !containsRef(listsOut, matList)) listsOut.add(matList);

            // 搬运成功后主动催一次重算（投影自己排度，不阻塞当前帧）
            if (recount) {
                long now = System.currentTimeMillis();
                if (now - lastPromoteMs > 5000) {
                    lastPromoteMs = now;
                    try { callAny(matList, "reCreateMaterialList"); } catch (Exception ignored) {}
                }
            }

            Object entriesObj = callAny(matList, "getMaterialsAll");
            if (!(entriesObj instanceof List<?> entries)) continue;

            if (entries.isEmpty()) {
                long now = System.currentTimeMillis();
                if (now - lastPromoteMs > 8000) {
                    lastPromoteMs = now;
                    try { callAny(matList, "reCreateMaterialList"); } catch (Exception ignored) {}
                }
                continue;
            }

            for (Object entry : entries) {
                Object miss = callAny(entry, "getCountMissing");
                if (!(miss instanceof Number n) || n.intValue() <= 0) continue;
                Object stackObj = callAny(entry, "getStack");
                if (!(stackObj instanceof ItemStack stack) || stack.isEmpty()) continue;
                String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                acc.merge(key, n.intValue(), Integer::sum);
                sample.putIfAbsent(key, stack.copyWithCount(1));
            }
        }

        if (enabled == 0) return "无启用的原理图放置";
        if (acc.isEmpty()) return "EMPTY_LIST";

        for (Map.Entry<String, Integer> e : acc.entrySet()) {
            ItemStack st = sample.get(e.getKey());
            if (st != null) out.add(new Shortfall(st, e.getValue()));
        }
        return "OK: " + enabled + " 个启用放置, " + acc.size() + " 种缺料";
    }

    // ---------------- 反射小工具（对包内开放，MaterialSyncClient 共用同一套容错口径） ----------------

    static Object callStatic(Class<?> c, String method, Object... args) throws Exception {
        Method m = findMethod(c, method, args.length);
        return m == null ? null : m.invoke(null, args);
    }

    /** 按方法名（可多个候选）调无参实例方法；一个都找不到抛 NoSuchMethodException。 */
    static Object callAny(Object target, String... methodNames) throws Exception {
        for (String name : methodNames) {
            Method m = findMethod(target.getClass(), name, 0);
            if (m != null) return m.invoke(target);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + methodNames[0]);
    }

    /** 按方法名调单参实例方法（如 setMaterialListEntries(List)）。 */
    static Object callOne(Object target, String methodName, Object arg) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == 1) return m.invoke(target, arg);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + methodName);
    }

    static Method findMethod(Class<?> c, String name, int paramCount) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
        }
        return null;
    }

    /** 按对象身份（==）判断是否已在表里，避免 equals 把两份不同的清单当同一份。 */
    static boolean containsRef(List<Object> list, Object o) {
        for (Object x : list) if (x == o) return true;
        return false;
    }

    private static ItemStack stackOf(String id) {
        Item item = (Item) BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        if (item == null || item == net.minecraft.world.item.Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item);
    }
}
