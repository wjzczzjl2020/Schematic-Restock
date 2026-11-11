package cn.blockforge.generated.schematicrestock;

import net.minecraft.resources.ResourceLocation;

/**
 * 公共常量。
 *
 * <p><b>r14 大图纸重构</b>：数值全部按「755 方块 / 29 种材料 / 96 个箱子」的真实生存工程重新标定，
 * 旧版那些为了压扫描量而设的妥协值（512 件额度、20 秒会话、每手共用 3000 槽预算）一并作废。
 * 扫描量这一维不再靠「额度卡人」，而是靠 {@link ChestIndex} 的位置索引缓存从根上消掉。</p>
 *
 * <p><b>r16 自动建造</b>：新增 {@code BUILD_*} 一组数值。放置走服务端 {@code setBlock}，
 * 每 tick 限 {@link #BUILD_BLOCKS_PER_TICK} 块 <b>且</b> {@link #BUILD_TICK_BUDGET_NS} 纳秒，
 * 超出距离闸门的条目只延后不判死（见 AutoBuildEngine）。</p>
 */
public final class SR {
    public static final String MOD_ID = "schematic_restock";

    // ---------------- 受理规模 ----------------

    /** 单次处理材料种类上限（r14：48 → 128，索引驱动之后种类多不再是成本，48 纯属人为闸门）。 */
    public static final int MAX_TYPES = 128;
    /** 单次检索容器上限（同时是标记箱总数上限）。 */
    public static final int MAX_CONTAINERS = 96;
    /**
     * 单次框选标记箱上限（<b>r24：16 → 64</b>）。
     *
     * <p>16 的时候一整面储物墙要框好几轮。64 够装下 4×4×4 的箱子堆或一整排
     * 双箱阵列；超过 64 仍然拒绝整批并提示玩家缩小范围（不是静默截断，
     * 免得玩家以为「框过了」）。体积另有 {@link #BOX_SCAN_VOLUME_CAP} 兜底，
     * 总数另有 {@link #MAX_CONTAINERS} 封顶 —— 这道闸门只管「一次」。</p>
     */
    public static final int MAX_BOX_MARKS = 64;
    /**
     * 描边闪烁队列容量（r24 新增）。
     *
     * <p>必须 {@code >= MAX_BOX_MARKS + 8}：一次框选最多会把 64 个容器 + 两个角点
     * 全塞进队列，装不下就会边加边裁，看起来像闪烁丢了几个。</p>
     */
    public static final int FLASH_CAP = MAX_BOX_MARKS + 8;
    /** 输出箱（溢出箱）总数上限：只进不出，永远不参与取料。 */
    public static final int MAX_OUTPUTS = 32;

    /**
     * 会话总取料额度<b>下限</b>（r14：512 → 10000）。
     *
     * <p>实际额度取 {@code max(MAX_ITEMS, 缺口件数合计)}，见 TakeoverEngine：
     * 生存建大房子动辄上千件材料，固定 512 会在半程把整个会话掐死。</p>
     */
    public static final int MAX_ITEMS = 10000;
    /**
     * 合成耗料的额度放大系数（r14 新增，按缺口动态算上限的另一半）。
     *
     * <p>缺口 100 件栅栏，实际要动用的件数是「栅栏件数 + 木板 + 原木」——原料是按比例吃掉的。
     * 会话额度 = {@code max(MAX_ITEMS, 缺口合计 * 本系数)}，既不会中途掐死，也仍然有封顶。</p>
     */
    public static final int ITEM_CAP_PER_GAP_PIECE = 3;

    // ---------------- 耗时与冷却 ----------------

    public static final long RESCAN_CAP_MS = 2000;    // 重扫耗时封顶
    public static final long COOLDOWN_MS = 3000;      // 退出后再触发冷却
    public static final long MISSING_EXIT_MS = 1500;  // 缺料后自动退出延时
    public static final long FLASH_MS = 300;          // 描边闪烁时长
    public static final int BOX_SCAN_VOLUME_CAP = 16384; // 框选扫描体积封顶（格）
    public static final long MARK_CLICK_DEBOUNCE_MS = 450; // 左键连发去抖窗口

    // ---------------- 节流（r14：索引驱动，限的是"料格"而不是全箱槽位） ----------------

    /**
     * 单 tick 最多搬运件数。
     * r14：128 → 512。搬运成本已经和箱子大小无关（只走索引里的料格），
     * 这里只是防止一 tick 之内把整个网络的容器改动堆太多。
     */
    public static final int MAX_MOVE_PER_TICK = 512;
    /**
     * 单 tick 最多检视的<b>料格</b>数（搬运阶段）。
     *
     * <p>取代旧的 {@code MAX_OPS_PER_TICK}（那个数限的是「扫过多少个箱子槽位」，
     * 96 箱 × 27 格 = 2592，一种物品没货就要付满这一笔，几十种物品必然饿死）。
     * 现在一个品种要摸几个料格由索引给出，上限只在极端整合包才碰得到。</p>
     */
    public static final int MAX_LOCS_PER_TICK = 8192;

    /**
     * r22：阶段 A「索引说没货」时的<b>物理复核</b>格子预算（跨所有标记箱累计）。
     *
     * <p>索引只是缓存，缓存可以骗人（箱子被玩家/模组中途改过、建索引撞时间闸门、
     * 容器 handler 一时没应答），但它<b>没权力</b>给一种材料判「无货」——判错一次，
     * 这种货就直通阶段 B 被配方闸门贴成「查无工作台配方」，去皮原木/方解石误杀正主。
     * 所以取料拿不到货时先逐格实测刷新一遍缓存，箱里真有货就退回搬运队列继续搬。</p>
     */
    public static final int PHASE_A_SWEEP_SLOTS = 30000;

    // ---------------- 会话寿命（r14：第 2 点） ----------------

    /** 单次接管会话最长寿命：20 秒 → <b>120 秒</b>，给大工程留出时间。 */
    public static final long SESSION_TIMEOUT_MS = 120_000;
    /**
     * <b>连续零进展</b>提前退出（r14 新增，与 120 秒硬上限并用）。
     *
     * <p>判定见 {@code TakeoverEngine.advance()}：连续 {@code STALL_EXIT_MS} 毫秒内
     * 「已投进存放处的件数 / 已消耗的原料件数 / 已结清的种类」三项一个都没涨，
     * 会话就地收场。卡死的情况 10 秒就退出，不用白等两分钟。</p>
     */
    public static final long STALL_EXIT_MS = 10_000;
    /** 进度包节流（约 5 次/秒）。会话期间一直有包，所以客户端静默判定的窗口必须比它大得多。 */
    public static final long PROGRESS_EVERY_TICKS = 4;
    /** 客户端看门狗：服务端连续这么久不回进度包才解除 RUNNING（正常推进时是 80ms 一包）。 */
    public static final long CLIENT_WATCHDOG_MS = 20_000;

    // ---------------- 幽灵合成 ----------------

    /** 每 tick 最多推进几次「面向玩家的产出」（r14：待合成任务在同一个 tick 内轮转，这里是每轮给几次机会）。 */
    public static final int MAX_CRAFT_PER_TICK = 1;
    /**
     * 一次推进里链内前置 assemble 的硬上限。
     *
     * <p><b>r14 从 8 提到 64，这是配合改期封顶的必要改动，不是放宽限流。</b>
     * 撞到这个上限会返回 {@code BUDGET} 走改期，而改期满 {@link #MAX_STEP_RETRIES} 次就判 STUCK。
     * 一手 SIM 是从头展开的，所以上限必须容纳<b>整</b>一手的真实工作量，否则一定过不去 ——
     * 一个 3×3 配方、九格各需一次前置，就是 9+1=10 次 assemble，旧值 8 会把它<b>误杀</b>。
     * 64 仍然是一道硬天花板（防失控），但没有任何正常配方摸得到。</p>
     */
    public static final int MAX_CHAIN_ASSEMBLIES_PER_TICK = 64;
    /** 配方递归深度上限<b>默认值参考</b>（r28 起运行期实际读 {@code config/schematic_restock-common.toml}
     *  的 {@code restock.craftMaxDepth}，默认 4；这里只留作注释里的历史锚点，代码不再直接引用，
     *  数值与本行注释无关，改默认值请改 ModConfig）。 */
    public static final int CRAFT_MAX_DEPTH = 4;
    /** 整张配方表只扫一次建索引，上限给得宽（大整合包工作台+石切几千到几万条）。 */
    public static final int CRAFT_INDEX_SCAN_CAP = 60000;
    /** 一条配方最多试这么多条候选配方（防组合爆炸）。 */
    public static final int PLAN_MAX_CANDIDATES = 24;
    /** 一个配料格最多试这么多种「代表物品」（标签样品的可合成变体可能几十个）。 */
    public static final int PLAN_MAX_MEMBERS = 12;

    // ---------------- 预算（r14：第 1、2 点的核心） ----------------

    /**
     * SIM 阶段单手可检视的<b>料格</b>数。旧版 3000 槽且每手从零重扫全箱阵，
     * 96 箱 × 27 格天生就超过这个数 —— 于是「本手改期」无限重复（死循环正主）。
     * r14 取料走索引，这个预算只在极端情况下才碰得到，且撞到也只是本手改期，
     * 由 {@link #MAX_STEP_RETRIES} 封顶，永不无限重复。
     */
    public static final int CRAFT_SIM_LOCS = 12000;
    /** COMMIT 阶段单手可检视的料格数（与 SIM 各自一份，互不挤占）。 */
    public static final int CRAFT_COMMIT_LOCS = 12000;

    /**
     * <b>同一手连续改期上限</b>（r14 新增，直接堵死死循环）。
     *
     * <p>SIM/COMMIT 预算用完 → 本手改期；累计到这个次数还过不去，
     * 说明这个品种在这套库存下算不动，贴 {@code Reason.BUDGET} 判死、转下一种，
     * <b>绝不无限重来</b>。</p>
     */
    public static final int MAX_STEP_RETRIES = 3;

    /** 建索引/普查单 tick 的时间闸门（毫秒）。索引每会话只建一次，跨 tick 续扫用。 */
    public static final long INDEX_TICK_MS = 60;

    // ---------------- 排障日志 ----------------

    public static final boolean GHOST_DEBUG_DEFAULT = true;   // 进游戏就开着，/ghostlog off 可静音
    public static final int GHOST_DEBUG_MAX_LINES = 4000;     // 单次接管会话最多写多少行（防爆盘）
    /** 产物 DataComponents 不一致时是否直接判死（默认只写日志，不误伤模组配方）。 */
    public static final boolean GHOST_STRICT_PRODUCT_COMPONENTS = false;

    // ---------------- 自动建造（r16） ----------------

    /**
     * 每 tick 最多放几个方块（用户拍板：8 块）。
     * 与 {@link #BUILD_TICK_BUDGET_NS} 是<b>与</b>的关系：先到哪个算哪个。
     */
    public static final int BUILD_BLOCKS_PER_TICK = 8;
    /**
     * 单 tick 建造的时间闸门（4 毫秒）。
     *
     * <p>20 tick/秒的游戏里，主线程一 tick 的预算约 50ms；这里只拿 4ms，
     * 剩下的全留给原版。放一个方块要做一次 {@code setBlock}（含客户端同步、
     * 方块实体创建），大整合包里个别方块的 {@code onPlace} 很重，靠这个闸门兜住。</p>
     */
    public static final long BUILD_TICK_BUDGET_NS = 4_000_000L;
    /**
     * 距离闸门（格，用户拍板 64）：目标坐标离玩家超过这个数就<b>本轮不碰</b>，
     * 记一次「延后」，隔 {@link #BUILD_DEFER_TICKS} 再试（玩家走过去就会继续）。
     * 这条同时也是防误伤闸门：不可能隔着半张图把谁的房子改了。
     */
    public static final double BUILD_DISTANCE = 64.0;
    /** 延后条目多久之后允许重试（tick）。1 秒。 */
    public static final int BUILD_DEFER_TICKS = 20;
    /** 判定「身上没这种料」之后隔多久再试一次（tick）。5 秒——期间玩家可以按 V+M 补料。 */
    public static final int BUILD_STOCK_RETRY_TICKS = 100;
    /** 单个方块最多检视多少个存放处槽位还找不到就算缺料（防千格大背包每块死扫）。 */
    public static final int BUILD_SCAN_SLOTS = 6000;
    /** 一轮里最多<b>检视</b>多少个条目（哪怕一个都没放下）。纯 CPU 闸门，防空转。 */
    public static final int BUILD_MAX_SCANS_PER_TICK = 512;

    /** 单次建造计划的方块数上限（防客户端把海量坐标灌进服务端）。 */
    public static final int BUILD_MAX_PLAN = 200_000;
    /** 每个网络包携带的坐标条目数（分帧上传，绝不撞自定义载荷的包大小上限）。 */
    public static final int BUILD_CHUNK = 2048;
    /**
     * 客户端一 tick 最多发几包（<b>r25：1 → 4</b>）。
     *
     * <p>一包 2048 格约 20 KB，4 包 80 KB/tick —— 原版加载区块时的量级远大于此。
     * 旧值 1 让一张 20 万格的图纸要 5 秒才传完（这 5 秒正是旧版会话被 tick 清理误删的窗口），
     * 现在 1 秒出头就开工。反正服务端收包期间不再被回收（见 {@code AutoBuildEngine} 的
     * {@code sealed} 闸门），发快发慢都只是体验问题，不再是正确性问题。</p>
     */
    public static final int BUILD_CHUNKS_PER_TICK = 4;
    /** 一个计划里的形态（palette）条目上限。 */
    public static final int BUILD_MAX_PALETTE = 4096;
    /**
     * <b>r25 新增</b>：形态表的字节预算（整张表都塞在首包里）。
     *
     * <p>原版对自定义载荷有 1 MiB 硬上限（超了直接抛异常断连接）。首包 = 形态表 + 最多
     * {@link #BUILD_CHUNK} 条坐标（约 20 KB），这里给 512 KiB：模组方块那种上百字符的
     * 形态串也能容下几千条，撞了只是<b>少收一点并说明</b>，不会把连接搞掉。</p>
     */
    public static final int BUILD_MAX_PALETTE_BYTES = 512 * 1024;
    /**
     * <b>r25 新增</b>：分帧上传期间，服务端等下一包的静默上限（毫秒）。
     *
     * <p>客户端一 tick 发一包，正常间隔 50ms；静默这么久说明客户端中途退了/崩了，
     * 这个半成品会话就该丢掉（否则换世界的玩家会在服务端留下僵尸会话）。</p>
     */
    public static final long BUILD_UPLOAD_STALL_MS = 15_000;
    /** 客户端扫描原理图的时间闸门（毫秒）：超了就按「已扫到的部分」上传并说明。 */
    public static final long BUILD_SCAN_CAP_MS = 3000;

    /** 覆盖策略：只填空气（用户拍板的默认值，最安全）。 */
    public static final int BUILD_REPLACE_AIR = 0;
    /** 覆盖策略：空气 + 原版判定「可替换」的（水/草/雪那类）。 */
    public static final int BUILD_REPLACE_SOFT = 1;
    /** 覆盖策略：只要现状和目标不一样就覆盖。 */
    public static final int BUILD_REPLACE_MISMATCH = 2;
    /** 默认覆盖策略。 */
    public static final int BUILD_REPLACE_DEFAULT = BUILD_REPLACE_AIR;

    /**
     * 建造会话寿命。按 8 块/tick 折算，20 万块要 25000 tick（约 20 分钟），
     * 这里给 2 小时封顶——建造是玩家<b>主动盯着</b>的活，不像补料那样需要短超时。
     */
    public static final long BUILD_SESSION_TIMEOUT_MS = 2L * 60L * 60L * 1000L;
    /**
     * <b>r27 新增</b>：连续<b>零进展</b>多久就自动收场（毫秒）。
     *
     * <p>旧版只有上面那个两小时硬超时：一种料箱里确实没有，施工会话就一直挂在「延后重试」里，
     * HUD 定格在「建造中 · 缺料 N · 延后 M」，而每 200ms 一包的进度又让客户端每 5 秒催一次
     * 投影重算材料清单 —— 投影每起一次任务就播一句提示，玩家看到的正是
     * 「卡住了，还不停显示已开始任务」。60 秒里一格都没放下，说明这一轮再怎么等也不会有
     * 结果，收场并说清缺什么才是有用的行为（重按 V+U 会从剩下的格子接着建，
     * 已放好的客户端扫描时就被「现状已经对」闸门筛掉，不会二次扣料）。</p>
     */
    public static final long BUILD_STALL_MS = 60_000L;
    /** 建造进度包节流（tick）。 */
    public static final long BUILD_PROGRESS_EVERY_TICKS = 4;
    /** 客户端看门狗：服务端连续这么久不回建造进度就认为会话没了（正常是 200ms 一包）。 */
    public static final long BUILD_WATCHDOG_MS = 20_000;

    // ---------------- L 键列表同步（任务 1） ----------------

    public static final long SYNC_THROTTLE_MS = 500;  // 每 500ms 至多写一次投影内存
    public static final int SYNC_MAX_LISTS = 16;      // 一次同步最多处理的材料列表对象数
    public static final int SYNC_MAX_ENTRIES = 4096;  // 一次同步最多检视的条目对象数

    private SR() {}

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
