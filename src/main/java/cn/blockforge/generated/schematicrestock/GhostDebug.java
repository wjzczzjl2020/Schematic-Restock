package cn.blockforge.generated.schematicrestock;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 幽灵合成排障日志（服务端 → {@code logs/latest.log}，前缀固定 {@code matfill/ghost}）。
 *
 * <p>存在的唯一目的：当一次配方复核被判 {@code UNSAFE_RECIPE} 时，日志里必须能<b>一眼看出</b>
 * 是哪一步没过、以及为什么没过。为此每次失败都会打出四组硬事实：</p>
 * <ol>
 *   <li><b>摆法</b>：我们请求的 width/height、{@code CraftingInput.of} 实际得到的 width/height
 *       （它会裁掉空行空列！）、实例 {@code identityHashCode}（证明每次都是新对象，没有复用）；</li>
 *   <li><b>逐格对照</b>：槽位序号 + 行列、我们投进去的物品/数量/组件补丁、该格配方要的 Ingredient
 *       （普通还是自定义、是否 simple、样品清单）、以及 {@code Ingredient.test} 的真实返回值；</li>
 *   <li><b>回吐物</b>：{@code getRemainingItems} 的原始返回（在调 {@code matches} 之前就算好并打印）；</li>
 *   <li><b>产出</b>：{@code assemble} 出来的 ItemStack 与目标物品的逐项比对（ID / 数量 / 组件补丁）。</li>
 * </ol>
 *
 * <p>任何异常都吞在这里面，绝不让排障代码本身把游戏搞崩。开关：{@code /ghostlog on|off}。</p>
 */
public final class GhostDebug {

    private static final Logger LOG = LoggerFactory.getLogger("matfill/ghost");

    private static volatile boolean on = SR.GHOST_DEBUG_DEFAULT;
    private static int linesUsed;
    private static boolean budgetWarned;
    private static volatile String lastDetail = "";
    private static final java.util.Set<String> SEEN = new java.util.HashSet<>();

    private GhostDebug() {}

    // ==================== 开关 / 诊断摘要 ====================

    public static boolean on() { return on; }

    public static void setOn(boolean v) {
        on = v;
        LOG.info("幽灵合成调试日志已{}（开关：/ghostlog on|off）", v ? "打开" : "关闭");
    }

    /** 会话开始时清配额（配方缓存也是同一处失效）。 */
    public static void resetBudget() {
        linesUsed = 0;
        budgetWarned = false;
    }

    /** 给聊天/失败清单用的一句短诊断（不含前缀）。 */
    public static String lastDetail() { return lastDetail; }

    public static void clearDetail() { lastDetail = ""; }

    /**
     * 拼到失败原因后面的一小句诊断（带前导分隔，空诊断时返回空串）。
     * 让"matches 不过 / 有回吐物 / 产出对不上"这句话在游戏里就能分出到底是哪一种。
     */
    public static String tail() {
        try {
            String s = lastDetail;
            if (s == null || s.isEmpty()) return "";
            if (s.length() > 150) s = s.substring(0, 150) + "…";
            return " ｜ " + s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 记下一句短诊断，同时写日志。 */
    public static void detail(String s) {
        lastDetail = s;
        line("诊断 → " + s);
    }

    // ==================== r7 新增：观察项累计（不影响玩法，只给 /ghostlog stats 看） ====================

    /** 观察项：类别 -> 次数。默认放行但记过一笔的东西（组件差异、被裁掉的空行空列等）。 */
    private static final Map<String, Integer> OBSERVED = new java.util.LinkedHashMap<>();

    /**
     * 记一笔「观察项」：这件事本模组没有据此改判，只是看着了。
     *
     * <p>用途是回答玩家的疑问「到底是没做还是做了不管」：日志里出现观察项，
     * 说明比对确实跑了、差异确实存在，只是按 r7 的口径（只比物品）放行。</p>
     */
    public static void noteObserved(String kind) {
        if (kind == null) return;
        try {
            synchronized (OBSERVED) {
                OBSERVED.merge(kind.startsWith("观察:") ? kind : "观察:" + kind, 1, Integer::sum);
            }
        } catch (Throwable ignored) {}
    }

    /** {@code /ghostlog stats} 用：把观察项拼成一句可打印的摘要。 */
    public static String observedSummary() {
        try {
            synchronized (OBSERVED) {
                if (OBSERVED.isEmpty()) return "（本轮没有放行过任何差异）";
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Integer> e : OBSERVED.entrySet()) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(e.getKey()).append(" x").append(e.getValue());
                }
                return sb.toString();
            }
        } catch (Throwable t) {
            return "读取失败:" + t.getClass().getSimpleName();
        }
    }

    public static void clearObserved() {
        try {
            synchronized (OBSERVED) { OBSERVED.clear(); }
        } catch (Throwable ignored) {}
    }

    /** 方块坐标的紧凑写法（日志里指到具体是哪个箱子）。 */
    public static String pos(net.minecraft.core.BlockPos p) {
        if (p == null) return "?";
        return "[" + p.getX() + "," + p.getY() + "," + p.getZ() + "]";
    }

    // ==================== 基础输出 ====================

    /** 同一个键只放行第一次（防止同一条失败原因每 tick 刷满日志）。 */
    public static boolean once(String key) {
        if (!on) return false;
        try {
            return SEEN.add(key);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void clearOnce() {
        try {
            SEEN.clear();
        } catch (Throwable ignored) {}
    }

    public static void line(String s) {
        if (!on) return;
        if (linesUsed >= SR.GHOST_DEBUG_MAX_LINES) {
            if (!budgetWarned) {
                budgetWarned = true;
                LOG.info("调试日志达到本次会话上限 {} 行，后续只写诊断摘要（可用 /ghostlog off 完全静音）",
                        SR.GHOST_DEBUG_MAX_LINES);
            }
            return;
        }
        linesUsed++;
        try {
            LOG.info(safe(s));
        } catch (Throwable ignored) {
            // 日志框架本身出问题也不能带崩玩法
        }
    }

    /**
     * 日志字符安全网。
     *
     * <p>上一版日志里出现的 {@code =?过} 就是这件事的现场：日志文件的落盘编码是 JVM
     * 默认编码（Windows 简体中文环境常为 GBK），GBK 里<b>没有</b> {@code ✔ ✘ ⚠} 这类
     * 装饰符号，log4j 就把它们写成 {@code ?}，于是 {@code =✔过} 变成 {@code =?过}，
     * 完全看不出是过了还是没过。中文本身没问题（GBK 有），所以只处理符号。</p>
     *
     * <p>做法：ASCII 和常见中日韩文字原样保留；已知装饰符号换成等价的纯 ASCII 标记；
     * 其它非 ASCII、非 CJK 的字符（emoji、生僻符号）统一换成 {@code ^}，
     * 这样日志里永远不会再出现来源不明的 {@code ?}。</p>
     */
    public static String safe(String s) {
        if (s == null) return "";
        try {
            int n = s.length();
            StringBuilder sb = new StringBuilder(n + 16);
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                if (c < 0x80) { sb.append(c); continue; }
                String m = MAP.get(c);
                if (m != null) { sb.append(m); continue; }
                if (isCjk(c)) { sb.append(c); continue; }
                sb.append('^');
            }
            return sb.toString();
        } catch (Throwable t) {
            return s;   // 安全网自己出问题时退回原文，绝不因此丢日志
        }
    }

    /** CJK 文字与中文标点（GBK / UTF-8 两边都稳），原样输出。 */
    private static boolean isCjk(char c) {
        return (c >= 0x3000 && c <= 0x303F)      // 、。〈〉《》等中文标点
                || (c >= 0x4E00 && c <= 0x9FFF)  // 汉字基本区
                || (c >= 0x3040 && c <= 0x30FF)  // 平假名/片假名
                || (c >= 0xFF01 && c <= 0xFF60); // 全角字符与全角标点
    }

    private static final Map<Character, String> MAP = new HashMap<>();

    static {
        MAP.put('✔', "[OK]");   MAP.put('✓', "[OK]");
        MAP.put('✘', "[NO]");   MAP.put('✗', "[NO]");
        MAP.put('⚠', "[!!]");   MAP.put('❗', "[!!]");
        MAP.put('★', "*");      MAP.put('☆', "-");
        MAP.put('●', "-");      MAP.put('○', "-");
        MAP.put('•', "-");      MAP.put('▪', "-");
        MAP.put('▸', ">");      MAP.put('◂', "<");
        MAP.put('═', "=");      MAP.put('─', "-");
        MAP.put('│', "|");      MAP.put('｜', "|");
        MAP.put('┌', "+");      MAP.put('┐', "+");
        MAP.put('└', "+");      MAP.put('┘', "+");
        MAP.put('├', "+");      MAP.put('┤', "+");
        MAP.put('┬', "+");      MAP.put('┴', "+");
        MAP.put('┼', "+");
        MAP.put('→', "->");     MAP.put('←', "<-");
        MAP.put('↑', "^");      MAP.put('↓', "v");
        MAP.put('↔', "<->");    MAP.put('⇒', "=>");
        MAP.put('…', "..");     MAP.put('⋯', "..");
        MAP.put('≠', "!=");     MAP.put('≈', "~=");
        MAP.put('≤', "<=");     MAP.put('≥', ">=");
        MAP.put('×', "x");      MAP.put('÷', "/");
        MAP.put('①', "(1)");    MAP.put('②', "(2)");   MAP.put('③', "(3)");
        MAP.put('④', "(4)");    MAP.put('⑤', "(5)");   MAP.put('⑥', "(6)");
        MAP.put('　', " ");     // 全角空格除外（上面 isCjk 会放行，这里显式换掉更整齐）
    }

    /** 打异常：类名 + message + 最上面几帧（本模组包内的帧优先留着好看）。 */
    public static void throwAt(String where, Throwable t) {
        if (t == null) return;
        StringBuilder sb = new StringBuilder("异常 @").append(where).append(" → ")
                .append(t.getClass().getName());
        try {
            String m = t.getMessage();
            if (m != null) sb.append(" : ").append(m.replace('\n', ' '));
        } catch (Throwable ignored) {}
        try {
            StackTraceElement[] fr = t.getStackTrace();
            int shown = 0;
            for (StackTraceElement e : fr) {
                if (shown >= 6) break;
                sb.append("\n      at ").append(e);
                shown++;
            }
            if (fr.length > shown) sb.append("\n      … 还有 ").append(fr.length - shown).append(" 帧");
        } catch (Throwable ignored) {}
        line(sb.toString());
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            line("  caused by " + cause.getClass().getName()
                    + (cause.getMessage() == null ? "" : " : " + cause.getMessage().replace('\n', ' ')));
        }
    }

    // ==================== 格式化小工具 ====================

    public static String rl(ResourceLocation id) {
        return id == null ? "?" : id.toString();
    }

    /** 一行 ItemStack：物品 ID + 数量 + 组件补丁（决定 Ingredient.test 是否会因组件翻车）。 */
    public static String st(ItemStack s) {
        if (s == null) return "null";
        if (s.isEmpty()) return "空槽";
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(BuiltInRegistries.ITEM.getKey(s.getItem()));
        } catch (Throwable t) {
            sb.append('?').append(s.getItem());
        }
        sb.append(" x").append(s.getCount());
        try {
            DataComponentPatch p = s.getComponentsPatch();
            sb.append("  组件补丁=").append(p == null || p.isEmpty() ? "（无，纯原版默认）" : p.toString());
        } catch (Throwable t) {
            sb.append("  组件补丁=读取失败:").append(t.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /** 一行 Ingredient：类型（普通/自定义/空）、是否 simple、样品清单。 */
    public static String ing(Ingredient g) {
        if (g == null) return "null";
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(g.isEmpty() ? "EMPTY" : (g.isCustom() ? "自定义" : "普通"));
            sb.append(g.isSimple() ? "/simple" : "/complex");
        } catch (Throwable t) {
            sb.append("读取失败:").append(t.getClass().getSimpleName());
        }
        try {
            if (g.isCustom()) {
                ICustomIngredient ci = g.getCustomIngredient();
                if (ci == null) {
                    sb.append(" impl=null");
                } else {
                    sb.append(" impl=").append(ci.getClass().getName()).append(" type=").append(ci.getType());
                }
            }
        } catch (Throwable t) {
            sb.append(" impl读取失败:").append(t.getClass().getSimpleName());
        }
        try {
            ItemStack[] its = g.getItems();
            int n = its == null ? 0 : its.length;
            sb.append(" 样品数=").append(n).append(" [");
            int show = Math.min(4, n);
            for (int i = 0; i < show; i++) {
                if (i > 0) sb.append(", ");
                sb.append(BuiltInRegistries.ITEM.getKey(its[i].getItem()));
            }
            if (n > show) sb.append(", …");
            sb.append(']');
        } catch (Throwable t) {
            sb.append(" 样品读取失败:").append(t.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /** 两个栈的组件补丁差异（用于回答"是不是 DataComponents 导致的 test 失败"）。 */
    public static String patchDiff(ItemStack ours, ItemStack wants) {
        try {
            if (ours == null || wants == null || ours.isEmpty() || wants.isEmpty()) return "（无样品可比）";
            DataComponentPatch a = ours.getComponentsPatch();
            DataComponentPatch b = wants.getComponentsPatch();
            String sa = a == null || a.isEmpty() ? "（无）" : a.toString();
            String sb2 = b == null || b.isEmpty() ? "（无）" : b.toString();
            boolean same = sa.equals(sb2);
            return (same ? "组件补丁一致 " : "⚠ 组件补丁不同 ") + "我们=" + sa + " | 配方样品=" + sb2;
        } catch (Throwable t) {
            return "组件补丁比较失败:" + t.getClass().getSimpleName();
        }
    }

    /** 配方的 pattern 尺寸（只有 ShapedRecipe 有；shapeless / 石切返回 null）。 */
    public static int[] shapedDims(Recipe<?> r) {
        try {
            if (r instanceof ShapedRecipe sr) return new int[]{sr.getWidth(), sr.getHeight()};
        } catch (Throwable ignored) {}
        return null;
    }

    /** {@code CraftingInput.of} 会不会裁掉空行空列，这里直接摆事实。 */
    public static String dims(RecipeInput in, int reqW, int reqH) {
        StringBuilder sb = new StringBuilder();
        sb.append("请求 ").append(reqW).append('x').append(reqH);
        if (in instanceof CraftingInput ci) {
            sb.append(" → 实际 ").append(ci.width()).append('x').append(ci.height());
            if (ci.width() != reqW || ci.height() != reqH) {
                sb.append(" ⚠ 被 CraftingInput.of 裁过（空行/空列被削掉）");
            }
            sb.append("，实例@").append(Integer.toHexString(System.identityHashCode(ci)));
            sb.append("，非空格 ").append(ci.ingredientCount()).append('/').append(ci.size());
        } else if (in != null) {
            sb.append(" → ").append(in.getClass().getSimpleName())
                    .append("@").append(Integer.toHexString(System.identityHashCode(in)))
                    .append("，size=").append(in.size());
        } else {
            sb.append(" → null（没摆出来）");
        }
        return sb.toString();
    }

    // ==================== 大段 dump ====================

    /**
     * 一次复核的完整现场。任何一步失败都调它，日志里就能直接看出是谁把这一手卡住的。
     *
     * @param phase    失败阶段标签（MATCHES_FALSE / REMAINDER / ASSEMBLE_EMPTY / RESULT_MISMATCH …）
     * @param ings     配方的 getIngredients()
     * @param grid     我们按同一索引摆出来的物品（与 ings 等长，含空槽）
     * @param tests    每个索引上 {@code Ingredient.test} 的实测结果（null = 没测/测不了）
     */
    public static void dumpAttempt(String phase, String mode, ResourceLocation recipeId, Recipe<?> raw,
                                   String layout, RecipeInput in, int reqW, int reqH,
                                   NonNullList<Ingredient> ings, java.util.List<ItemStack> grid,
                                   Boolean[] tests, String note) {
        if (!on) return;
        StringBuilder head = new StringBuilder();
        head.append("───── 复核不过[").append(phase).append("] ")
                .append(mode).append(" 配方=").append(rl(recipeId))
                .append(" 类=").append(raw == null ? "?" : raw.getClass().getSimpleName())
                .append(" 摆法=").append(layout);
        line(head.toString());
        line("      尺寸: " + dims(in, reqW, reqH));
        int[] d = shapedDims(raw);
        line("      配方 pattern 尺寸=" + (d == null ? "（非有序配方，无固定尺寸）" : d[0] + "x" + d[1])
                + "；getIngredients().size()=" + (ings == null ? -1 : ings.size())
                + "；ShapedRecipePattern.matches 要求 input 尺寸与 pattern 完全相等（不是滑动窗口）");
        line("      我们投料 " + (grid == null ? 0 : grid.size()) + " 格，逐格对照：");
        if (grid != null) {
            for (int i = 0; i < grid.size(); i++) {
                ItemStack ours = grid.get(i);
                Ingredient g = (ings == null || i >= ings.size()) ? null : ings.get(i);
                Boolean t = (tests == null || i >= tests.length) ? null : tests[i];
                int col = reqW > 0 ? i % reqW : i;
                int row = reqW > 0 ? i / reqW : 0;
                line("        槽" + i + "(列" + col + ",行" + row + ") 我们=" + st(ours)
                        + (t == null ? "" : (t ? "  test=✔通过" : "  test=✘不过"))
                        + "\n          配方要=" + ing(g));
                if (Boolean.FALSE.equals(t) && g != null) {
                    try {
                        ItemStack[] its = g.getItems();
                        if (its != null && its.length > 0) {
                            line("          " + patchDiff(ours, its[0])
                                    + "；物品是否在其中=" + containsItem(ours, its));
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        if (note != null && !note.isEmpty()) line("      " + note);
    }

    private static String containsItem(ItemStack ours, ItemStack[] samples) {
        try {
            if (ours == null || ours.isEmpty()) return "（我们这格是空的）";
            for (ItemStack s : samples) {
                if (s != null && !s.isEmpty() && s.getItem() == ours.getItem()) return "是";
            }
            return "⚠ 否（物品本身就不在配方允许的清单里）";
        } catch (Throwable t) {
            return "比较失败";
        }
    }

    /** 回吐物：把 getRemainingItems 的原始返回逐项打出来（在调 matches 之前）。 */
    public static void dumpRemainder(String mode, ResourceLocation recipeId, String layout, Object raw) {
        if (!on) return;
        StringBuilder sb = new StringBuilder("回吐物检查[" ).append(mode).append("] 配方=").append(rl(recipeId))
                .append(" 摆法=").append(layout).append(" → ");
        try {
            if (raw == null) {
                line(sb.append("返回 null（当无回吐处理）").toString());
                return;
            }
            if (!(raw instanceof NonNullList<?> list)) {
                line(sb.append("返回类型不是 NonNullList，而是 ").append(raw.getClass().getName())
                        .append("（当无回吐处理）").toString());
                return;
            }
            int n = 0;
            StringBuilder items = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                Object o = list.get(i);
                if (o instanceof ItemStack st && !st.isEmpty()) {
                    if (n > 0) items.append("; ");
                    items.append("槽").append(i).append('=').append(st(st));
                    n++;
                }
            }
            if (n == 0) line(sb.append("全空（无回吐）✔").toString());
            else line(sb.append("⚠ 非空 ").append(n).append(" 项: ").append(items).toString());
        } catch (Throwable t) {
            line(sb.append("读取失败:").append(t.getClass().getName())
                    .append(':').append(t.getMessage()).toString());
        }
    }

    /** 产出比对：assemble 的结果 vs 目标物品。 */
    public static void dumpProduct(String mode, ResourceLocation recipeId, ItemStack out, ItemStack want,
                                   int wantCount) {
        if (!on) return;
        line("产出核对[" + mode + "] 配方=" + rl(recipeId));
        line("      assemble 返回 = " + st(out));
        line("      目标是        = " + st(want) + "（这一步想要 " + wantCount + " 件）");
        try {
            if (out == null || out.isEmpty()) {
                line("      ✘ 产出为空：assemble 没给东西（多半是 assemble 抛异常被吞了，看上面的异常行）");
            } else if (want != null && out.getItem() != want.getItem()) {
                line("      ✘ 物品对不上：产出了 " + BuiltInRegistries.ITEM.getKey(out.getItem())
                        + "，要的是 " + BuiltInRegistries.ITEM.getKey(want.getItem())
                        + "（配方选错了 / 产物被别的模组的配方覆盖了）");
            } else {
                String a = out.getComponentsPatch() == null ? "（无）" : out.getComponentsPatch().toString();
                String b = want == null || want.getComponentsPatch() == null ? "（无）"
                        : want.getComponentsPatch().toString();
                if (!a.equals(b)) line("      ⚠ 物品对上了，但组件补丁不同：产出=" + a + " | 目标=" + b);
                line("      ✔ 物品一致，单份产出 " + (out.isEmpty() ? 0 : out.getCount()) + " 件");
            }
        } catch (Throwable t) {
            line("      产出核对读取失败:" + t.getClass().getSimpleName());
        }
    }
}
