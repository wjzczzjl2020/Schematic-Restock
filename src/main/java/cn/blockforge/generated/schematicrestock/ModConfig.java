package cn.blockforge.generated.schematicrestock;

import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.common.ModConfigSpec;

// 注意：这里<b>不能</b> import net.neoforged.fml.config.ModConfig —— 本文件定义的类就叫 ModConfig，
// 同名 import 会被 javac 判成"同一编译单元里定义了两遍"。下面用全限定名引用它。

/**
 * 通用配置 —— {@code .minecraft/config/schematic_restock-common.toml}（NeoForge {@code ModConfigSpec}，
 * {@code Type.COMMON}：单机的一个文件管两端，专用服务器只认服务端自己那一份）。
 *
 * <p>这里暴露的是玩家最该自己调的六个闸门；其余内部节流（时间预算、槽位上限、会话寿命之类）
 * 刻意<b>不</b>进配置——它们和引擎的判定是一体的，改错一个就是一次"有货不拿"级别的血案，
 * 出问题请优先看排障日志而不是调数字。</p>
 *
 * <p>游戏内命令照旧可用，作为快捷修改方式：
 * {@code /matfill build replace <air|soft|all>} 与 {@code /matfill build fluids <on|off>}
 * 在<b>下一份建造计划</b>生效，不改本文件；{@code /ghostlog} 系列控制排障日志。</p>
 */
public final class ModConfig {

    /** 覆盖策略的配置写法（枚举名进 TOML，引擎内部仍是 0/1/2 整数编码，见 {@code SR.BUILD_REPLACE_*}）。 */
    public enum ReplacePolicy { AIR, SOFT, MISMATCH }

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue BUILD_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue BUILD_DISTANCE;
    public static final ModConfigSpec.BooleanValue BUILD_PLACE_FLUIDS;
    public static final ModConfigSpec.EnumValue<ReplacePolicy> BUILD_REPLACE_POLICY;
    public static final ModConfigSpec.IntValue RESTOCK_CRAFT_DEPTH;
    public static final ModConfigSpec.IntValue RESTOCK_SEARCH_RADIUS;

    static final ModConfigSpec SPEC;

    static {
        B.comment("Auto-build (V+U): how the server engine places the schematic.").push("autobuild");

        BUILD_BLOCKS_PER_TICK = B
                .comment(
                        "Blocks placed per server tick while a build session runs.",
                        "A second, per-tick TIME gate (4 ms) applies on top - whichever is hit first ends the tick,",
                        "so very heavy modded blocks cannot melt the server even with a large value here.",
                        "Raise for faster walls on simple builds; lower it if the TPS dips while printing.",
                        "Range 1..64. Default 8.")
                .defineInRange("blocksPerTick", 8, 1, 64);

        BUILD_DISTANCE = B
                .comment(
                        "Reach gate in blocks. Cells further away than this from the player are DEFERRED, never dropped -",
                        "walk closer and the engine picks them up again on its own.",
                        "This is also the safety rail that makes it impossible to edit a distant base by accident.",
                        "Range 8..512. Default 64.")
                .defineInRange("distanceLimit", 64, 8, 512);

        BUILD_PLACE_FLUIDS = B
                .comment(
                        "Allow the build engine to place fluid blocks (water / lava / modded liquids).",
                        "Fluids are always queued LAST, after every solid block, so a dead-water column cannot wash over",
                        "the half-finished build. When false, fluid cells are skipped and the end-of-run chat line",
                        "says how many were dropped this way.",
                        "Default true.")
                .define("placeFluids", true);

        BUILD_REPLACE_POLICY = B
                .comment(
                        "Which existing block a build cell is allowed to overwrite.",
                        "AIR      - only replace air. Safest, the default.",
                        "SOFT     - air plus anything vanilla considers replaceable (grass, snow layers, water, ...).",
                        "MISMATCH - overwrite whatever stands there if it is not exactly the schematic block.",
                        "The chat command /matfill build replace <air|soft|all> overrides this for the NEXT uploaded",
                        "plan without touching this file.")
                .defineEnum("replacePolicy", ReplacePolicy.AIR);

        B.pop();

        B.comment("Restock takeover (V+M): how materials are gathered and ghost-crafted.").push("restock");

        RESTOCK_CRAFT_DEPTH = B
                .comment(
                        "Maximum recursion depth of a crafting chain (log -> planks -> sticks -> crafting table = depth 3).",
                        "A deeper requirement is cut off at this level and reported as 'no crafting-table recipe'.",
                        "Defaults to 4 - one level above the vanilla chain above - because modded intermediates",
                        "(dyed beds, machine parts, nested components) routinely need four. The recipe index makes an",
                        "extra level cheap; take it to 5 or 6 for packs with very long chains, back down to 3 only if",
                        "you want the engine to give up on deep crafting sooner.",
                        "Range 1..8. Default 4.")
                .defineInRange("craftMaxDepth", 4, 1, 8);

        RESTOCK_SEARCH_RADIUS = B
                .comment(
                        "Fallback search radius in blocks around the player.",
                        "Only used when NOT A SINGLE material chest is marked with the wand: the engine then borrows",
                        "loaded container blocks (chest / barrel / shulker on the ground ...) within this radius for",
                        "that ONE session and says so in chat. Marked chests are never distance-limited - mark your",
                        "storage wall properly and this knob stops mattering. 0 disables the fallback entirely.",
                        "Range 0..64. Default 16.")
                .defineInRange("searchRadius", 16, 0, 64);

        B.pop();

        SPEC = B.build();
    }

    private ModConfig() {}

    /** 模组构造器里调一次：注册 COMMON 配置，游戏启动时在 config/ 下生成/回写本文件。 */
    public static void register() {
        ModLoadingContext.get().getActiveContainer()
                .registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, SPEC);
    }

    // ---- 带钳制的读取口：手改坏的 TOML 永远拖不垮引擎，只会退回边界值 ----

    /** 每 tick 放置上限（1..64）。 */
    public static int buildBlocksPerTick() {
        int v = BUILD_BLOCKS_PER_TICK.get();
        return v < 1 ? 1 : Math.min(v, 64);
    }

    /** 距离闸门（格，8..512，取平方前统一转 double）。 */
    public static double buildDistance() {
        int v = BUILD_DISTANCE.get();
        return v < 8 ? 8 : Math.min(v, 512);
    }

    /** 是否允许服务端放流体（客户端建计划与服务端落砖两头都查这个）。 */
    public static boolean placeFluidsAllowed() {
        return BUILD_PLACE_FLUIDS.get();
    }

    /** 覆盖策略整数编码：0=只填空气 1=含原版可替换 2=不对就覆盖。 */
    public static int replacePolicyCode() {
        ReplacePolicy p = BUILD_REPLACE_POLICY.get();
        if (p == null) return SR.BUILD_REPLACE_AIR;
        return switch (p) {
            case SOFT -> SR.BUILD_REPLACE_SOFT;
            case MISMATCH -> SR.BUILD_REPLACE_MISMATCH;
            default -> SR.BUILD_REPLACE_AIR;
        };
    }

    /** 配方递归深度上限（1..8），替代旧的 {@code SR.CRAFT_MAX_DEPTH} 常量。 */
    public static int craftMaxDepth() {
        int v = RESTOCK_CRAFT_DEPTH.get();
        return v < 1 ? 1 : Math.min(v, 8);
    }

    /** 补料兜底搜索半径（0..64，0=关）。 */
    public static int restockSearchRadius() {
        int v = RESTOCK_SEARCH_RADIUS.get();
        return v < 0 ? 0 : Math.min(v, 64);
    }
}
