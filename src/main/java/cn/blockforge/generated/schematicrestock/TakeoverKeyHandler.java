package cn.blockforge.generated.schematicrestock;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 任务 1：{@code V+M} / {@code V+U} / {@code V+Y} 组合键的抢键处理（r16 起三条共用一套）。
 *
 * <p>做法是监听 NeoForge 的 {@link InputEvent.Key}：只有「按住 V + 按下我们自己的某条键」
 * 这一组合才由本模组吃下——先阻止这次按键继续传给其他模组，再触发对应动作。
 * 单独按 M / U / Y 完全不拦，小地图之类把它们当快捷键的模组照旧能用。</p>
 *
 * <h3>关于 event.setCanceled(true)</h3>
 * <p>本工程的 NeoForge 21.1.244 里 {@code InputEvent.Key} 明确<b>不可取消</b>：
 * 该事件类没有实现 {@code ICancellableEvent}（源码注释写着 "This event is not
 * cancellable"），所以不存在 {@code setCanceled(boolean)} 可调；而且从分发的字节码看，
 * 事件是在 {@code KeyMapping.click(...)} <b>之后</b>才发的，就算能取消也拦不住别的模组
 * 已经排上的那一次点击。因此这里用等效手段（{@link #swallow}）：
 * ①反射探测到 {@code setCanceled} 就照用户要求调一次（将来版本支持了就自动生效）；
 * ②把这次按键的「按下」状态清掉；③把绑在同一个物理键上的<b>其他模组</b>的点击计数排空，
 * 使它们在客户端 tick 里的 {@code consumeClick()} 取不到东西——净效果就是事件没往下传。</p>
 *
 * <h3>r16 的两处改动（沿用同一套机制，不另起炉灶）</h3>
 * <ul>
 *   <li>命中判定从「只比 {@code KEY_TAKEOVER}」改成 {@link #ownMappingFor}：三条映射按
 *       <b>玩家当前绑定</b>逐个比（键可以改绑，所以不写死 M/U/Y），命中哪条就把事件派给哪条；</li>
 *   <li>第 ③ 步排空别的模组时跳过的是<b>我们自己的全部三条</b>：玩家可以图省事把 M/U/Y
 *       绑到同一个物理键上，这时若只跳过一条，另外两条的点击会被自己排空，等于自己抢自己的键。</li>
 * </ul>
 *
 * <h3>r21 Bug2：单按 M 必须放行</h3>
 * <p>{@link #onKey} 里「按住 V 没有」的判定挪到了<b>方法最前</b>：没按住 V 时零开销直接返回，
 * 不做任何键位查询、不清任何模组的按下状态、不排空任何点击——单按 M 开小地图不受本模组影响。
 * 配套的兜底轮询（{@link ClientEvents}）同步改成静默：单按 M / U / Y 一律不响、不弹提示、
 * 不发起接管，抢键只保留「V + 我们的键」这一种命中方式。</p>
 */
public final class TakeoverKeyHandler {

    /** 组合前缀键：按住 V。写死，避免和别的模组再撞。 */
    static final int PREFIX_KEY = GLFW.GLFW_KEY_V;

    private static boolean cancelProbed;
    private static Method cancelMethod;     // 有就调，没有就跳过
    private static boolean tableProbed;
    private static Collection<KeyMapping> allMappings; // KeyMapping.ALL（私有静态，反射一次）
    private static boolean swallowDegraded;            // 反射拿不到总表：只能清我们自己的那份

    private TakeoverKeyHandler() {}

    public static void init() {
        NeoForge.EVENT_BUS.register(TakeoverKeyHandler.class);
    }

    /** 抢键走最高优先级，尽量排在别的模组的 InputEvent.Key 处理器之前。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKey(InputEvent.Key e) {
        if (e.getAction() == GLFW.GLFW_RELEASE) return; // 松开不管

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.screen != null) return; // 聊天框/书写作界面里不抢键

        // r21 Bug2：V 的判定挪到<b>最前面</b>。没按住 V 时这个方法三步就返回——
        // 不查键位表、不碰反射、不清任何 KeyMapping 的按下状态、不排空任何点击。
        // 单按 M / U / Y 是别的模组（小地图一类）的快捷键，必须原样放行。
        if (!InputConstants.isKeyDown(mc.getWindow().getWindow(), PREFIX_KEY)) return;

        // r16：三个组合键共用这一套抢键（M=补料接管，U=建造开始/暂停，Y=建造取消）。
        // 命中条件是「这个物理键确实绑在我们的某一条映射上」，绑到别处的键一概不管。
        KeyMapping key = ownMappingFor(e.getKey());
        if (key == null) return;

        // 走到这里 = 按住 V + 按下我们的键，才算我们的组合，由本模组吃下。
        swallow(e, key);
        if (e.getAction() == GLFW.GLFW_PRESS) ClientEvents.onComboKey(mc, key); // 长按连发不重复触发
    }

    /**
     * 这个物理键是否落在<b>我们自己</b>的某条映射上（玩家可以把三条键改绑到任意键，
     * 所以只能按「当前绑定」判，不能写死 M/U/Y）。没命中返回 null。
     */
    private static KeyMapping ownMappingFor(int glfwKey) {
        for (KeyMapping km : OWN) {
            if (km == null || km.isUnbound()) continue;
            InputConstants.Key bound = km.getKey();
            if (bound.getType() != InputConstants.Type.KEYSYM) continue;   // 鼠标键走另一条路
            if (bound.getValue() == glfwKey) return km;
        }
        return null;
    }

    /** 组合命中：阻止这次按键继续传递给其他模组。 */
    private static void swallow(InputEvent.Key e, KeyMapping mine) {
        // ① 若所在版本支持取消，按需求调 event.setCanceled(true)
        Method m = cancelMethod();
        if (m != null) {
            try {
                m.invoke(e, true);
                return;
            } catch (Throwable ignored) {
                // 调不通就继续走下面的等效手段
            }
        }

        int keyCode = e.getKey();

        // ② 清掉绑定了这个物理键的所有映射的按下状态（公开静态方法，含其他模组的）
        try {
            KeyMapping.set(InputConstants.getKey(keyCode, e.getScanCode()), false);
        } catch (Throwable ignored) {}

        // ③ 排空其他模组在这个键上排好的点击，让它们的 consumeClick() 拿不到。
        //    r16：要跳过的是<b>我们自己的全部三条</b>映射——玩家可以图省事把 M/U/Y
        //    绑到同一个物理键上，这时把另外两条也排空会直接吃掉它们各自的触发。
        for (KeyMapping km : mappings()) {
            if (km == null || isOwn(km)) continue;
            InputConstants.Key k = km.getKey();
            if (k.getType() != InputConstants.Type.KEYSYM || k.getValue() != keyCode) continue;
            while (km.consumeClick()) { /* drain */ }
            try {
                km.setDown(false);
            } catch (Throwable ignored) {}
        }

        // ④ 触发的那一条自己的点击也排空，避免 tick 轮询二次触发
        while (mine.consumeClick()) { /* drain */ }
    }

    /** 我们自己的三条映射（V+M 补料、V+U 建造、V+Y 取消）。 */
    private static final KeyMapping[] OWN = {
            ClientEvents.KEY_TAKEOVER, BuildBridge.KEY_BUILD, BuildBridge.KEY_BUILD_CANCEL
    };

    private static boolean isOwn(KeyMapping km) {
        for (KeyMapping o : OWN) if (o == km) return true;
        return false;
    }

    /** 诊断：走没走降级路径（反射拿不到 KeyMapping 总表时只会清自己）。 */
    public static boolean swallowDegraded() {
        mappings();
        return swallowDegraded;
    }

    /** 诊断：当前版本的 InputEvent.Key 到底能不能 setCanceled。 */
    public static boolean cancelAvailable() {
        return cancelMethod() != null;
    }

    /** V 是否真的按着（诊断/命令回显用；r21 起兜底轮询静默化，不再依赖它分岔）。 */
    public static boolean prefixHeld() {
        try {
            return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), PREFIX_KEY);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 反射小工具（都只探一次，失败就降级） ----------------

    private static Method cancelMethod() {
        if (cancelProbed) return cancelMethod;
        cancelProbed = true;
        try {
            cancelMethod = InputEvent.Key.class.getMethod("setCanceled", boolean.class);
        } catch (Throwable t) {
            cancelMethod = null; // 本版本就是不可取消
        }
        return cancelMethod;
    }

    @SuppressWarnings("unchecked")
    private static Collection<KeyMapping> mappings() {
        if (tableProbed) return allMappings;
        tableProbed = true;
        try {
            Field f = KeyMapping.class.getDeclaredField("ALL");
            f.setAccessible(true);
            Object o = f.get(null);
            if (o instanceof Map<?, ?> map) {
                List<KeyMapping> out = new ArrayList<>();
                for (Object v : ((Map<Object, Object>) map).values()) {
                    if (v instanceof KeyMapping km) out.add(km);
                }
                allMappings = out;
                return allMappings;
            }
        } catch (Throwable ignored) {
            // 拿不到总表：只能清我们自己的映射
        }
        swallowDegraded = allMappings == null;
        allMappings = List.of();
        return allMappings;
    }
}
