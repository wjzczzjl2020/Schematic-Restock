package cn.blockforge.generated.schematicrestock;

import com.mojang.serialization.MapCodec;
import net.neoforged.neoforge.common.conditions.ICondition;

/**
 * 配方条件：材料标记签的合成表<b>只在「不用原版物品当标记物」时加载</b>（1.0.1 新增）。
 *
 * <p>做法是给 {@code data/schematic_restock/recipe/mark_wand.json} 加一段
 * {@code "neoforge:conditions": [{"type": "schematic_restock:mark_wand_enabled"}]}。
 * NeoForge 的 {@code RecipeManager} 解析配方时会先跑条件，不满足就整条跳过，
 * 日志里留一行 {@code Skipping loading recipe schematic_restock:mark_wand as its conditions were not met}。
 * 对玩家的效果：配方书里搜不到、合成不出、也不会有「已知配方」的提示——等于这条配方不存在。</p>
 *
 * <p><b>为什么不用别的办法</b>：物品本身没法从注册表里摘掉（注册表启动时定死，
 * 真删了老玩家背包里那支会变成"未知物品"丢失），而 NeoForge 1.21 也早就删掉了 Forge 时代
 * {@code RecipeMap#remove} 那种运行时改配方表的口子——官方给的答案就是条件。
 * 条件在数据包加载时求值，那时 COMMON 配置已经读完（{@code ModConfig} 的读取口另有未加载兜底），
 * 所以判到的就是玩家 TOML 里的值。</p>
 *
 * <p>条件是<b>服务端／客户端各自求值</b>的：配方表在两端各建一次，
 * 两端读自己那份 {@code schematic_restock-common.toml}。多人服上玩家客户端的开关
 * 不影响他能不能合成——合成要服务端配方表认账，这正是服主想要的方向。</p>
 */
public final class MarkWandEnabledCondition implements ICondition {

    public static final MarkWandEnabledCondition INSTANCE = new MarkWandEnabledCondition();

    /** 无字段条件：整个 JSON 对象除 {@code "type"} 之外什么都不读。 */
    public static final MapCodec<MarkWandEnabledCondition> CODEC = MapCodec.unit(INSTANCE).stable();

    private MarkWandEnabledCondition() {}

    @Override
    public boolean test(IContext context) {
        // 开关关着（用标记签）才让配方加载；开着（用原版物品）就隐藏
        return !ModConfig.useVanillaMarkerItem();
    }

    @Override
    public MapCodec<? extends ICondition> codec() {
        return CODEC;
    }

    @Override
    public String toString() {
        return "mark_wand_enabled";
    }
}
