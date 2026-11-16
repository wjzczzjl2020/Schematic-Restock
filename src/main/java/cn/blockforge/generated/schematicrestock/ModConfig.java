package cn.blockforge.generated.schematicrestock;

import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.common.ModConfigSpec;

// 注意：这里<b>不能</b> import net.neoforged.fml.config.ModConfig —— 本文件定义的类就叫 ModConfig，
// 同名 import 会被 javac 判成"同一编译单元里定义了两遍"。下面用全限定名引用它。

/**
 * 通用配置 —— {@code .minecraft/config/schematic_restock-common.toml}（NeoForge {@code ModConfigSpec}，
 * {@code Type.COMMON}：单机的一个文件管两端，专用服务器只认服务端自己那一份）。
 *
 * <p>这里暴露的是玩家最该自己调的八个闸门（标记物一项 + 建造四项 + 补料三项）；其余内部节流
 * （时间预算、槽位上限、会话寿命之类）刻意<b>不</b>进配置——它们和引擎的判定是一体的，
 * 改错一个就是一次"有货不拿"级别的血案，出问题请优先看排障日志而不是调数字。</p>
 *
 * <p><b>1.0.1 新增 {@code wand} 一节</b>：{@code useVanillaItem} + {@code markerItemId}，
 * 决定「手里拿什么去标箱子」。默认用原版的金锄（几乎没人用的工具，占掉它的右键不心疼），
 * 此时模组自带的材料标记签会从创造模式和合成表里一起隐藏；关掉开关一切照旧。
 * 两种物品在开关打开时<b>都</b>能用来标记，老玩家背包里已有的标记签永远不会失效。
 * 改完这两项要重启游戏（配方和创造页在世界加载时就定死了）。</p>
 *
 * <p>游戏内命令照旧可用，作为快捷修改方式：
 * {@code /matfill build replace <air|soft|all>} 与 {@code /matfill build fluids <on|off>}
 * 在<b>下一份建造计划</b>生效，不改本文件；{@code /ghostlog} 系列控制排障日志。</p>
 */
public final class ModConfig {

    /** 覆盖策略的配置写法（枚举名进 TOML，引擎内部仍是 0/1/2 整数编码，见 {@code SR.BUILD_REPLACE_*}）。 */
    public enum ReplacePolicy { AIR, SOFT, MISMATCH }

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    // ---- 默认值：既是写进 TOML 的那一份，也是「配置还没加载完」时的安全退路（两边共用同一个数字，不会走偏） ----

    public static final int DEFAULT_BUILD_BLOCKS_PER_TICK = 8;
    public static final int DEFAULT_BUILD_DISTANCE = 64;
    public static final boolean DEFAULT_PLACE_FLUIDS = true;
    public static final ReplacePolicy DEFAULT_REPLACE_POLICY = ReplacePolicy.AIR;
    public static final int DEFAULT_CRAFT_MAX_DEPTH = 4;
    public static final int DEFAULT_RESTOCK_SEARCH_RADIUS = 16;
    public static final boolean DEFAULT_USE_VANILLA_MARKER = true;
    public static final String DEFAULT_MARKER_ITEM_ID = "minecraft:golden_hoe";

    public static final ModConfigSpec.BooleanValue WAND_USE_VANILLA_ITEM;
    public static final ModConfigSpec.ConfigValue<String> WAND_MARKER_ITEM_ID;
    public static final ModConfigSpec.IntValue BUILD_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue BUILD_DISTANCE;
    public static final ModConfigSpec.BooleanValue BUILD_PLACE_FLUIDS;
    public static final ModConfigSpec.EnumValue<ReplacePolicy> BUILD_REPLACE_POLICY;
    public static final ModConfigSpec.IntValue RESTOCK_CRAFT_DEPTH;
    public static final ModConfigSpec.IntValue RESTOCK_SEARCH_RADIUS;

    static final ModConfigSpec SPEC;

    static {
        B.comment("Marker item: which item you hold to mark chests (V+M needs marked chests).").push("wand");

        WAND_USE_VANILLA_ITEM = B
                .comment(
                        "true (default)  - hold a VANILLA item (see markerItemId below, golden hoe by default)",
                        "                 to mark chests. The mod's own 'Material Marking Tag' then disappears from",
                        "                 the creative menu AND its crafting recipe is not loaded, so there is nothing",
                        "                 new to look for. A tag already in someone's inventory keeps working forever -",
                        "                 old items are never revoked.",
                        "false          - the mod's Material Marking Tag comes back (creative menu + recipe), and the",
                        "                 vanilla item goes back to behaving exactly like vanilla (holding a golden hoe",
                        "                 will open chests normally again).",
                        "Takes effect on game restart: recipes and the creative menu are built while the world loads.",
                        "Both the client and the server read their own copy of this file, so on a multiplayer server",
                        "the owner decides what the marker item is.",
                        "Default true.")
                .define("useVanillaItem", true);

        WAND_MARKER_ITEM_ID = B
                .comment(
                        "The item id used as the marker while useVanillaItem=true. Any registered item id works,",
                        "modded ones included - 'minecraft:golden_hoe', 'minecraft:paper', 'minecraft:name_tag',",
                        "'curios:bundle_of_your_mod'. Pick something whose right-click you can afford to lose:",
                        "marking works by SWALLOWING the right-click on a block, so while you hold this item you cannot",
                        "open a chest, place a sign or till soil with it. Golden hoe is the default precisely because",
                        "nobody uses it. Signs, item frames, doors, buttons and chests are bad choices - their own",
                        "right-click would stop working. Leave it empty or write a typo and the mod falls back to",
                        "holding the Material Marking Tag only (nothing breaks, you just get a warning in the log).",
                        "Restart after changing it. Default 'minecraft:golden_hoe'.")
                .define("markerItemId", "minecraft:golden_hoe");

        B.pop();

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
    //
    // <h3>r32：为什么每个读取口都要先问一句「配置加载完了吗」</h3>
    // <p>{@code Type.COMMON} 配置文件<b>不是</b>在 {@code registerConfig()} 那一行读进来的：
    // 构造器里只是登记，真正的加载发生在所有模组构造器跑完之后的 COMPLETE_CONFIG 阶段。
    // 于是就有了这个坑：客户端类（{@link BuildBridge}）在模组构造器里就被摸到，
    // 它的静态字段初值当时直接 {@code .get()} —— 那会儿配置还没加载，
    // NeoForge 毫不犹豫地抛
    // {@code IllegalStateException: Cannot get config value before config is loaded.}，
    // 玩家看到的就是「启动即崩溃」。</p>
    //
    // <p>现在一律走这里的读取口：<b>没加载就退回本文件顶部的 DEFAULT_*，引擎照常跑</b>，
    // 并且<b>每次调用都现读</b>（不缓存），所以配置一旦加载完成，下一次读就能拿到玩家自己改的值。
    // 中间那层 {@code try/catch} 是给「配置正在被重载」这类瞬时状态兜底的：
    // 读不到数字不是世界末日，把启动带崩才是。</p>

    /** TOML 是否已经加载完成（没加载或状态异常都算 false）。 */
    private static boolean loaded() {
        try {
            return SPEC.isLoaded();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 整数项的「读不到就退回默认值」通道：未加载、瞬时异常、值为 null 三种情况全兜住。 */
    private static int readInt(ModConfigSpec.IntValue value, int fallback) {
        if (!loaded()) return fallback;
        try {
            Integer v = value.get();
            return v == null ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 布尔项的同款通道。 */
    private static boolean readBool(ModConfigSpec.BooleanValue value, boolean fallback) {
        if (!loaded()) return fallback;
        try {
            Boolean v = value.get();
            return v == null ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 枚举项的同款通道（本项目只有 replacePolicy 一个枚举，签名写死最省事）。 */
    private static ReplacePolicy readPolicy(ModConfigSpec.EnumValue<ReplacePolicy> value, ReplacePolicy fallback) {
        if (!loaded()) return fallback;
        try {
            ReplacePolicy v = value.get();
            return v == null ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 字符串项的同款通道（配置里写没写引号都收，见 {@link #markerItemId()}）。 */
    private static String readString(ModConfigSpec.ConfigValue<String> value, String fallback) {
        if (!loaded()) return fallback;
        try {
            String v = value.get();
            return v == null ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 每 tick 放置上限（1..64）。 */
    public static int buildBlocksPerTick() {
        int v = readInt(BUILD_BLOCKS_PER_TICK, DEFAULT_BUILD_BLOCKS_PER_TICK);
        return v < 1 ? 1 : Math.min(v, 64);
    }

    /** 距离闸门（格，8..512，取平方前统一转 double）。 */
    public static double buildDistance() {
        int v = readInt(BUILD_DISTANCE, DEFAULT_BUILD_DISTANCE);
        return v < 8 ? 8 : Math.min(v, 512);
    }

    /** 是否允许服务端放流体（客户端建计划与服务端落砖两头都查这个）。 */
    public static boolean placeFluidsAllowed() {
        return readBool(BUILD_PLACE_FLUIDS, DEFAULT_PLACE_FLUIDS);
    }

    /** 覆盖策略整数编码：0=只填空气 1=含原版可替换 2=不对就覆盖。 */
    public static int replacePolicyCode() {
        ReplacePolicy p = readPolicy(BUILD_REPLACE_POLICY, DEFAULT_REPLACE_POLICY);
        return switch (p) {
            case SOFT -> SR.BUILD_REPLACE_SOFT;
            case MISMATCH -> SR.BUILD_REPLACE_MISMATCH;
            default -> SR.BUILD_REPLACE_AIR;
        };
    }

    /** 配方递归深度上限（1..8），替代旧的 {@code SR.CRAFT_MAX_DEPTH} 常量。 */
    public static int craftMaxDepth() {
        int v = readInt(RESTOCK_CRAFT_DEPTH, DEFAULT_CRAFT_MAX_DEPTH);
        return v < 1 ? 1 : Math.min(v, 8);
    }

    /** 补料兜底搜索半径（0..64，0=关）。 */
    public static int restockSearchRadius() {
        int v = readInt(RESTOCK_SEARCH_RADIUS, DEFAULT_RESTOCK_SEARCH_RADIUS);
        return v < 0 ? 0 : Math.min(v, 64);
    }

    /**
     * 是否用原版物品当标记物（{@code wand.useVanillaItem}）。
     *
     * <p>读的是<b>本端</b>那一份配置文件：COMMON 类型，单机器两端同一份，联机时服务端那份说了算
     * （手持判定两端都要做，见 {@code CommonEvents}）。改完要重启游戏——创造页和合成表在世界加载时
     * 就定下来了，中途改不会让配方凭空出现。</p>
     */
    public static boolean useVanillaMarkerItem() {
        return readBool(WAND_USE_VANILLA_ITEM, DEFAULT_USE_VANILLA_MARKER);
    }

    /** 标记物的物品 id 原文（{@code wand.markerItemId}），合法性由 {@code MarkerItems} 去注册表里验。 */
    public static String markerItemId() {
        String v = readString(WAND_MARKER_ITEM_ID, DEFAULT_MARKER_ITEM_ID);
        return v == null ? DEFAULT_MARKER_ITEM_ID : v.trim();
    }

    /** 给日志/诊断用：配置文件到底加载了没有（"我改了 TOML 怎么没生效"第一眼就问这个）。 */
    public static boolean configLoaded() {
        return loaded();
    }
}
