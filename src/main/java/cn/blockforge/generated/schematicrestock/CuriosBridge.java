package cn.blockforge.generated.schematicrestock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Curios 饰品栏桥接（任务 2 优先级 B，软依赖）。
 *
 * <p>Curios 只在编译期存在（build.gradle 里是 compileOnly），运行期没装也能正常跑：
 * {@link #curioContainers(Player)} 先问 {@link ModList} 有没有 "curios"，没有就返回空表；
 * 真正引用 Curios 类的代码全放在 {@link Inner} 里，只有确认装了才会被 JVM 加载，
 * 没装时不会抛 NoClassDefFoundError。</p>
 *
 * <p>返回的是饰品槽里那些容器物品（精妙背包一类）<b>内部</b>的物品处理器，
 * 不是饰品栏本身那几个格子。"back" 槽的容器排在前面，其余槽位的容器跟在后面。</p>
 *
 * <p><b>r11 Bug1</b>：旧版扫描失败时静默返回空表，用户只看到"材料进了快捷栏"，
 * 完全不知道饰品栏这一级是没找到槽位、还是找到了但拿不到内部 IItemHandler。
 * 现在每次扫描都会把结论写进 {@link #describeLastScan()}，
 * 由 {@link StorageTargets} 在 SIM 阶段的「存放处扫描」日志里转抄出来。</p>
 */
public final class CuriosBridge {

    private static final String MOD_ID = "curios";

    /** 一次饰品栏扫描的收获：容器内部的 handler + 给人看的槽位标签 + 是否 back 槽。 */
    public record Hit(IItemHandler handler, String label, boolean back) {}

    /** 最近一次扫描的一句话结论（给日志与 /matfill status 用）。 */
    private static volatile String LAST_SCAN = "还没扫过饰品栏";
    /** 最近一次整体抛异常（版本对不上/接口改名）的记号，null = 没抛过。 */
    private static volatile String LAST_ERROR = null;

    private CuriosBridge() {}

    /** 装了 Curios 吗（结果缓存，启动后不变）。 */
    public static boolean loaded() {
        return Loaded.FLAG;
    }

    /**
     * 饰品栏里可当存放处的容器（back 槽优先），拿不到就返回空表，绝不抛异常。
     * 无论成败，{@link #describeLastScan()} 都会更新成这一轮的扫描结论。
     */
    public static List<Hit> curioContainers(Player player) {
        if (!Loaded.FLAG || player == null) {
            return List.of();
        }
        try {
            return Inner.scan(player);
        } catch (Throwable t) {
            // 版本对不上/接口改名：跳过优先级 B，不影响主流程，但把原因记下来
            LAST_ERROR = t.getClass().getSimpleName() + ": " + t.getMessage();
            LAST_SCAN = "扫描饰品栏抛异常: " + LAST_ERROR;
            return List.of();
        }
    }

    /** 最近一次扫描的一句话结论（不含异常记号）。 */
    public static String describeLastScan() {
        return LAST_SCAN;
    }

    /** 诊断文案用：异常 + 最近一次扫描结论。 */
    public static String describeLastFailure() {
        String s = (LAST_ERROR == null ? "ok" : LAST_ERROR) + " | 近况: " + LAST_SCAN;
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** 单独一个类，保证 ModList 只在需要时才被加载。 */
    private static final class Loaded {
        static final boolean FLAG = isLoaded();

        private static boolean isLoaded() {
            try {
                return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /** 只有 Curios 在时才被加载。 */
    private static final class Inner {

        static List<Hit> scan(Player player) {
            List<Hit> backHits = new ArrayList<>();
            List<Hit> otherHits = new ArrayList<>();
            List<String> capProblems = new ArrayList<>();   // "物品@槽位 取不到能力"的明细
            List<String> slotKeys = new ArrayList<>();

            var handlerOpt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player);
            if (handlerOpt.isEmpty()) {
                LAST_SCAN = "getCuriosInventory 返回空（该实体没有饰品栏?）";
                return List.of();
            }
            Map<String, top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler> curios =
                    handlerOpt.get().getCurios();

            boolean hasBack = false;
            int wornTotal = 0;
            int containerCount = 0;

            for (Map.Entry<String, top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler> e
                    : curios.entrySet()) {
                String key = e.getKey();
                boolean back = isBackSlot(key);
                if (back) hasBack = true;
                slotKeys.add(shortKey(key));
                var stacks = e.getValue().getStacks();
                if (stacks == null) continue;
                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack worn;
                    try {
                        worn = stacks.getStackInSlot(i);
                    } catch (Throwable t) {
                        capProblems.add("槽" + shortKey(key) + "#" + i + " 读堆抛" + t.getClass().getSimpleName());
                        continue;
                    }
                    if (worn.isEmpty()) continue;
                    wornTotal++;
                    if (!StorageTargets.isContainerItem(worn)) continue;
                    containerCount++;
                    IItemHandler inner = null;
                    String err = null;
                    try {
                        inner = worn.getCapability(Capabilities.ItemHandler.ITEM);
                    } catch (Throwable t) {
                        err = t.getClass().getSimpleName();
                    }
                    if (inner == null || inner.getSlots() <= 0) {
                        // ★ 这就是"戴了精妙背包但材料进快捷栏"最常见的一环：
                        //   该模组没给物品注册 NeoForge 物品侧 IItemHandler 能力，我们摸不到内部格子。
                        capProblems.add("饰品[" + idOf(worn) + "]@" + shortKey(key) + "#" + i
                                + (err != null ? " 取内部IItemHandler抛" + err : " 未注册物品侧IItemHandler能力"));
                        continue;
                    }
                    String label = back ? "饰品栏背挂(" + shortKey(key) + "#" + i + ")"
                                        : "饰品栏(" + shortKey(key) + "#" + i + ")";
                    (back ? backHits : otherHits).add(new Hit(inner, label, back));
                }
            }

            List<Hit> hits = new ArrayList<>(backHits);
            hits.addAll(otherHits);
            LAST_SCAN = buildSummary(hits.size(), hasBack, wornTotal, containerCount, slotKeys, capProblems);
            return hits;
        }

        /** 拼一句话结论：正常路径与每一种"为什么没命中"都要能一眼看出。 */
        private static String buildSummary(int got, boolean hasBack, int wornTotal, int containerCount,
                                           List<String> slotKeys, List<String> capProblems) {
            StringBuilder sb = new StringBuilder();
            if (got > 0) sb.append("可用饰品容器").append(got).append("个(back优先)");
            else if (wornTotal == 0) sb.append("饰品栏一格都没装");
            else if (containerCount == 0) sb.append("戴着").append(wornTotal).append("件但没有容器类物品");
            else sb.append("找到").append(containerCount).append("个容器类饰品却都没能力");
            if (!hasBack) sb.append(" | 没有名为back的槽位,现有=").append(slotKeys);
            for (String p : capProblems) sb.append(" | ").append(p);
            String s = sb.toString();
            return s.length() > 220 ? s.substring(0, 220) + "…" : s;
        }

        /** "back" / "curios:back" 都算。 */
        private static boolean isBackSlot(String key) {
            if (key == null) return false;
            int sep = key.indexOf(':');
            String path = sep >= 0 ? key.substring(sep + 1) : key;
            return path.equalsIgnoreCase("back");
        }

        private static String shortKey(String key) {
            if (key == null) return "?";
            int sep = key.indexOf(':');
            return sep >= 0 ? key.substring(sep + 1) : key;
        }

        private static String idOf(ItemStack st) {
            try {
                return BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
            } catch (Throwable t) {
                return "?";
            }
        }
    }
}
