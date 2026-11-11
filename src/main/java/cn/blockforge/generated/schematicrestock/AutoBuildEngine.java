package cn.blockforge.generated.schematicrestock;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自动建造引擎（<b>服务端</b>）。
 *
 * <h3>一、放置只走 {@code level.setBlock}，绝不模拟右键</h3>
 * <p>Printer 那一类实现的通病是「射线 + {@code BlockPlaceContext} + 右键」：楼梯朝哪边、
 * 半砖在上半格还是下半格，全靠打中方块的哪一面现场推，推歪就是方向错乱。
 * 这里<b>完全不碰</b>右键、射线、{@code BlockPlaceContext}：
 * 目标坐标的 {@link BlockState}（属性齐全，朝向 / 半砖 / 轴向都在里面）由客户端
 * {@link BuildBridge} 从投影的原理图世界取来、编成文本形态表传过来，
 * 服务端还原成 BlockState 之后一条 {@code level.setBlock(pos, want, BUILD_FLAGS)} 写进去。
 * 方向不可能错，因为它不是<b>算</b>出来的，是原理图里带的。</p>
 *
 * <h3>二、防炸的 BlockFlags（照 1.21.1 真实字节码逐位核对）</h3>
 * <p>{@link #BUILD_FLAGS} = {@code Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE}
 * （常量值 2 | 16 = 18 = 0x12，也正是投影自己 {@code SchematicPlacingUtils} 放原理图时用的那个数）。
 * 对照 {@code Level.markAndNotifyBlock} 的分支：</p>
 * <ul>
 *   <li><b>0x01 {@code UPDATE_NEIGHBORS}</b> —— 置位才会走
 *       {@code blockUpdated(pos, prev)} 这条邻居通知链，外加
 *       {@code updateNeighbourForOutputSignal}（比较器读数）。观察者、红石线、活塞、TNT
 *       全靠这条链被叫醒 → <b>我们不置位</b>；</li>
 *   <li><b>0x02 {@code UPDATE_CLIENTS}</b> —— 置位才 {@code sendBlockUpdated} 把这一格推给周围玩家
 *       （服务端还会先检查该 chunk 的 {@code FullChunkStatus >= BLOCK_TICKING}，
 *       所以我们「chunk 没加载就延后」那道闸门是配套的）→ <b>置位</b>，不然眼前不出砖；</li>
 *   <li><b>0x10 {@code UPDATE_KNOWN_SHAPE}</b> —— 这个位是<b>反着</b>的：
 *       字节码里 {@code (flags & 16) == 0} 才去跑 {@code updateIndirectNeighbourShapes}
 *       + {@code updateNeighbourShapes} + 再来一遍 indirect（相邻方块形状重算/间接形状更新，
 *       玻璃填角、围墙连接、以及「被推一把」的连锁都在这里）→ <b>置位 = 跳过</b>。</li>
 * </ul>
 * <p>净效果：<b>零邻居更新</b> —— 点不着 TNT、推不动活塞、唤不醒观察者、红石线当场不会算信号；
 * 但方块本身带着全部属性精确落位，客户端立刻看得见。</p>
 *
 * <h3>三、扣料</h3>
 * <p>放置<b>之前</b>先扣料（先扣再放，绝不先放再补）；走 {@link StorageTargets.Feeder}，
 * 顺位与投放一致（主手容器 → 副手容器 → Curios 饰品容器 → 随身潜影盒 → 主物品栏（主栏先、
 * 快捷栏垫底）→ 副手整件）。三条硬规矩：
 * <ol>
 *   <li><b>装着东西的容器堆一律不用</b>（{@link StorageTargets#holdsForeignItems}）：
 *       造到一半把玩家装满家当的潜影盒 / 精妙背包当砖砌上墙，是比不建严重得多的事故；</li>
 *   <li>床的第二格（{@code part=head}）、门 / 高植物的第二格（{@code half=upper}）<b>不额外收费</b>，
 *       免得一张床扣两张床；流体不要东西；创造模式不扣；</li>
 *   <li>{@code setBlock} 万一被模组方块自己拒收，已取出的东西<b>原样退回</b>玩家身上，绝不黑料。</li>
 * </ol>
 * <p>每件消耗按品种累计进 {@code consumed}，随进度包发回客户端；
 * {@link MaterialSyncClient#onBuildConsumed} 据此<b>反向扣减投料账本</b>
 * （东西从背包进了墙里，账本就不该再记着它，否则下一次 V+M 的动态校验会当成「还躺在身上」而少补料）。
 * 收尾时客户端再催一次投影重算 L 键缺口。</p>
 *
 * <h3>三之二、r27「一格两料 / 一格多件」：花盆放不出来就是栽在这上面</h3>
 * <p>原版绝大多数方块都能用 {@code Item.byBlock(方块)} 反查到唯一一个对应物品，一格扣一件就完事。
 * 但有几类<b>查不到</b>或者<b>不止一件</b>，旧版一律按「一件物品都查不到 = 原版没有对应物品」
 * 整格跳过，于是<b>花盆里带植物</b>（{@code minecraft:potted_bamboo} 这一类）永远放不上去 ——
 * 玩家看着幽灵里明明有花盆，施工却一格不动。投影自己的材料清单（{@code MaterialCache}）
 * 早就给了标准答案，这里逐条照抄它的口径：
 * <ul>
 *   <li><b>一格两料</b>：{@code FlowerPotBlock} 且里面有植物 → 收 1 个<b>空花盆</b>
 *       + 1 个<b>植物方块的对应物品</b>（竹子就是竹子）。两样都齐了才放；缺哪样就欠哪样的账，
 *       并按那一件的品种延后重试（与投影 L 键缺口同一个口径，V+M 会把两样一起搬进背包）。
 *       花盆本体走 {@code getEmptyPot()}、植物走 {@code getPotted()}，都是 public，
 *       模组自己的花盆只要继承 {@code FlowerPotBlock} 就一并生效；</li>
 *   <li><b>一格多件</b>：双层台阶 2 件、雪层按 {@code layers}、蜡烛按 {@code candles}、
 *       海龟蛋按 {@code eggs}、海泡菜按 {@code pickles}（= 投影的 {@code overrideStackSize}）；</li>
 *   <li><b>查不到物品但有公认来源方块</b>：耕地 → 泥土（= 投影的 {@code getStateToItemOverride}）；
 *       这一路只收主料，绝不凭空造第二件；</li>
 *   <li>其余真·无物品的（火、红石线、屏障、结构空位）依旧判「原版没有对应物品」跳过。</li>
 * </ul>
 * <p>扣不满的<b>零头</b>（要 2 件只摸到 1 件）一律当场退回身上再延后，绝不出现
 * 「料被抠走、方块却没放」的黑料；多件品种靠 {@link Job#shortItem}/{@link Job#shortCount}
 * 记欠账，放上去或判死时按<b>原样件数</b>销账，不会残下一笔永远平不掉的缺口。</p>
 *
 * <h3>四、限流与延后</h3>
 * <p>每 tick 最多 {@link SR#BUILD_BLOCKS_PER_TICK} 块<b>且</b>不超过
 * {@link SR#BUILD_TICK_BUDGET_NS} 纳秒（先到算哪个），一轮检视条目数另有
 * {@link SR#BUILD_MAX_SCANS_PER_TICK} 封顶。撞距离闸门（{@link SR#BUILD_DISTANCE} 格）、
 * chunk 没加载、身上缺料 —— 都<b>不判死</b>，只是延后重试（缺料的重试点更远，
 * 好给玩家按 V+M 补料的时间）。一个 tick 零进展就睡到最早那个重试点，绝不空转烧 CPU。</p>
 *
 * <h3>四之一、r27「卡住也要有个交代」：零进展自动收场</h3>
 * <p>延后不等于永远等。旧版一场施工能挂着 {@link SR#BUILD_SESSION_TIMEOUT_MS}（两小时）
 * 一动不动：HUD 常年停在「建造中 · 缺料 N · 延后 M」，客户端每收到一次进度包就催一遍
 * 投影重算材料清单，投影每起一次任务就在聊天栏播一句「已开始…」——玩家看到的就是
 * <b>卡在原地、消息刷个不停</b>。现在只要连续 {@link SR#BUILD_STALL_MS} 没有<b>任何一格落地</b>，
 * 会话就自己收场，收尾那句会写明还缺什么、以及「补完重按 V+U」（已放好的格子下一轮
 * 会被 {@link BuildBridge} 的「现状已经对」闸门直接跳过，不重放、不二次扣料）。
 * 补料让路（{@code heldByRestock}）期间不计入这个钟，续工时也会把钟拨回零，
 * 免得玩家刚补完料就被判「卡住」。</p>
 *
 * <h3>四之二、r23「补料让路」：延后 ≠ 霸占背包</h3>
 * <p>旧版这里有个闭环死结：卡缺料的会话<b>一直活着</b>（延后队列每 5 秒重试一轮，
 * 两小时超时前绝不收工），而客户端 V+M 又拿「自动建造正在进行」一票否决——
 * 于是「缺料 → 按 V+M 补料 → 接着建」正好死在补料这一环：箱里堆成山，一件不进包。
 * r23 起服务端接手这个交接棒：收到 StartTakeover 就 {@link #pauseForRestock}——
 * <b>只把 RUNNING 的会话按下暂停并记档 {@code restockPaused}</b>；补料会话收场
 * （干完/超时/出错都算）{@link #resumeAfterRestock} 自动续工，并清空缺料重试闸门
 * （刚补进背包的料第一 tick 就该被翻到）。玩家手动暂停的会话不会被自动续；
 * 手动按 V+U 继续则作废让路标记、拿回控制权。背包任何一刻仍只有一个引擎在动。</p>
 *
 * <h3>五、方块实体一律不搬数据</h3>
 * <p>原理图里箱子的内容、告示牌的字、潜影盒里套的东西，<b>全部不带</b>：
 * 服务端只 {@code setBlock} 一个纯 BlockState，出来就是空箱子 / 空白告示牌。
 * 这一条同时防崩（不用把 NBT 过网络再还原成 BE）与防复制（不可能借建造把原理图里存的东西刷出来）。
 * 收尾在聊天栏说明这类格子放了多少个。</p>
 */
public final class AutoBuildEngine {

    /**
     * 放置用的 BlockFlags：{@code 0x02}（变化推给客户端）+ {@code 0x10}（形状已知，跳过邻居形状更新）。
     * <b>故意不含</b> {@code 0x01}（邻居通知链：红石 / 观察者 / 活塞 / TNT 的总开关）。
     * 逐位论证见类注释第二节。
     */
    static final int BUILD_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** 会话阶段（数值与客户端 {@link BuildBridge} 那一份对齐；这里自己写一份，免得服务端加载客户端类）。 */
    public static final int ST_IDLE = 0, ST_RUNNING = 1, ST_PAUSED = 2,
            ST_DONE = 3, ST_CANCELLED = 4, ST_ERROR = 5;

    /** playerUUID -> 施工会话。一个玩家同时只有一支施工队。 */
    private static final Map<UUID, Session> SESSIONS = new LinkedHashMap<>();

    /**
     * playerUUID -> 刚刚被拒收的计划号（<b>r25 新增</b>，与 {@link #SESSIONS} 同一把锁）。
     *
     * <p>一份大图纸是几十包。拒收之后剩下的包还会照来，旧版每包各回一句错误，
     * 聊天栏能被同一条提示刷上百行，把真正的诊断埋掉。记一笔"这个 plan 已经报过了"，
     * 同一 plan 的后续分包就静默丢弃。</p>
     */
    private static final Map<UUID, Long> REJECTED_PLAN = new HashMap<>();

    private AutoBuildEngine() {}

    // ---------------- 对外入口（payload 处理器调用，已在主线程） ----------------

    /** 收到计划分包（首包带形态表）。收齐 {@code last=true} 那一包才开工。 */
    public static void handlePlan(Object playerObj, Packets.BuildPlan p) {
        if (!(playerObj instanceof ServerPlayer sp)) return;
        UUID id = sp.getUUID();
        long nowMs = System.currentTimeMillis();
        synchronized (SESSIONS) {
            Session s = SESSIONS.get(id);
            if (p.first()) {
                if (s != null && s.live()) {
                    s.finish(ST_CANCELLED, "收到新计划，上一轮未完工即被替换（放下去的方块保留）");
                    s.silentDrop();
                } else if (s != null && !s.sealed) {
                    // 上一份计划只传了一半就被新计划顶掉（客户端重按 V+U 会作废旧 outbox）
                    GhostDebug.line("build: 半成品会话 plan=" + s.planId + "（已收 "
                            + s.packets + " 包）被新计划 plan=" + p.planId() + " 替换");
                }
                if (TakeoverEngine.isBusy(id)) {
                    rejectOnce(sp, p.planId(), "补料接管正在往你身上搬东西，先等它跑完再开工");
                    return;
                }
                setPolicy(p.policy());
                REJECTED_PLAN.remove(id);
                s = new Session(sp, p);
                SESSIONS.put(id, s);
            }
            if (s == null || s.planId != p.planId()) {
                // r25：同一份计划只报一次。一张大图纸是几十包，旧版这里每包各刷一行，
                // 聊天栏能连着糊上百条一模一样的错误，把真正的诊断埋掉。
                rejectOnce(sp, p.planId(), "服务端没有这一号建造计划（两端 jar 不一致，或会话在中途被清掉）");
                if (GhostDebug.on() && GhostDebug.once("build:lostplan:" + p.planId())) {
                    GhostDebug.line("build: 拒收分包 plan=" + p.planId() + " seq=" + p.seq()
                            + " —— 服务端现存会话=" + (s == null ? "无" : "plan=" + s.planId
                            + " state=" + stateName(s.state) + (s.sealed ? " 已收齐" : " 上传中")));
                }
                return;
            }
            s.lastPacketMs = nowMs;
            s.absorb(p);
            // r25：先盖章"包收齐了"再开工。这一位是会话能不能活过 tick 清理的唯一依据，
            // 顺序反了（start 抛异常）就会留下一个永不收口的半成品。
            if (p.last()) {
                s.sealed = true;
                s.start();
            }
        }
    }

    /** 收到控制包：1=暂停 2=继续 3=取消。 */
    public static void handleControl(Object playerObj, Packets.BuildControl c) {
        if (!(playerObj instanceof ServerPlayer sp)) return;
        synchronized (SESSIONS) {
            Session s = SESSIONS.get(sp.getUUID());
            if (s == null) {
                // r25：取消一个已经不存在的会话是正常收尾（客户端本地就清了 outbox），
                // 不该回一记 ST_ERROR 把玩家刚看到的"已取消"改成"建造出错"。
                if (c.op() != 3) reject(sp, 0L, "当前没有进行中的建造");
                return;
            }
            if (!s.sealed) {
                // r25：计划还在分帧上传，服务端连队列都没建，暂停/继续无从谈起。
                // 取消就顺手把半成品丢掉（客户端已清 outbox，不会再发剩下的包）。
                if (c.op() == 3) {
                    GhostDebug.line("build: 上传途中取消，丢弃半成品会话 plan=" + s.planId
                            + "（已收 " + s.packets + " 包 / 约需 " + s.expectedPacketCount() + " 包）");
                    s.silentDrop();
                    SESSIONS.remove(sp.getUUID());
                } else {
                    GhostDebug.line("build: 忽略 op=" + c.op() + " —— plan=" + s.planId
                            + " 还在上传（已收 " + s.packets + " 包），服务端尚未开工");
                }
                return;
            }
            if (!s.live()) {
                if (c.op() != 3) reject(sp, s.planId, "当前没有进行中的建造（上一轮已收场）");
                SESSIONS.remove(sp.getUUID());
                return;
            }
            switch (c.op()) {
                case 1 -> {
                    // r23：玩家在补料让路期间自己按了暂停 = 拿回控制权，自动续工作废
                    s.heldByRestock = false;
                    s.state = ST_PAUSED;
                    s.report(true);
                }
                case 2 -> {
                    // r23：补料正在往身上搬东西，这时候续工会两个引擎抢同一只背包。
                    // 只回一包"仍在暂停"的现状、不发 ST_ERROR——会话还活着，补料收场后
                    // 照常自动续工；误发错误包反而会把客户端状态打成死错。（常态下客户端
                    // toggle 已挡住这一按，这里只是抢跑竞态的兜底。）
                    if (TakeoverEngine.isBusy(sp.getUUID())) {
                        s.report(true);
                        return;
                    }
                    if (s.state == ST_PAUSED) s.state = ST_RUNNING;
                    s.heldByRestock = false;       // 手动续工：让路记档作废
                    s.sleepUntil = 0;
                    s.lastProgressMs = System.currentTimeMillis();   // r27：续工重置零进展计时
                    s.report(true);
                }
                case 3 -> s.finish(ST_CANCELLED, "按 V+Y 取消（放下去的方块保留）");
                default -> { }
            }
            if (!s.live()) SESSIONS.remove(sp.getUUID());
        }
    }

    /** 本玩家是否有施工会话（含已暂停）：客户端 r23 起只在「计划还在上传」时避开 V+M。 */
    public static boolean isBusy(UUID id) {
        synchronized (SESSIONS) {
            Session s = SESSIONS.get(id);
            return s != null && s.live();
        }
    }

    /**
     * r23「有货不拿」主修①：V+M 补料开场时把该玩家的施工会话挂起。
     *
     * <p>只有 RUNNING 会被我们按下暂停并记档 {@code heldByRestock}（收场时自动续工）；
     * 玩家<b>手动</b>暂停的会话原样不动——补料期间它本来就不碰背包，天然无冲突，
     * 补完也<b>不</b>替玩家擅自开工。返回 true 表示"会话存在，补料期间由我们看管"。</p>
     */
    public static boolean holdBuildForRestock(UUID id) {
        synchronized (SESSIONS) {
            Session s = SESSIONS.get(id);
            if (s == null || !s.live()) return false;
            if (s.state == ST_RUNNING) {
                s.state = ST_PAUSED;
                s.heldByRestock = true;
                s.report(true);
                GhostDebug.line("build: V+M 补料开场，施工自动暂停（放下去的方块保留，补完自动续工）");
            }
            return true;
        }
    }

    /** r23：补料会话收场。被我们按下的暂停原样续回去，缺料重试闸门同步作废。 */
    public static void releaseBuildAfterRestock(UUID id) {
        synchronized (SESSIONS) {
            Session s = SESSIONS.get(id);
            if (s == null || !s.live() || !s.heldByRestock) return;
            s.heldByRestock = false;
            s.state = ST_RUNNING;
            s.sleepUntil = 0;
            // r27：续工把「零进展」的钟拨回零——刚补完料就判卡住是最冤的一种收场
            s.lastProgressMs = System.currentTimeMillis();
            // 刚补进背包的料第一 tick 就该被翻到：上一轮记的"这品种没货"全部作废
            s.outOfStockUntil.clear();
            s.report(true);
            GhostDebug.line("build: 补料收场，施工自动续工（身上取不到货的缓存已清空，立刻重翻背包）");
        }
    }

    /** 诊断（{@code /matfill status}）。 */
    public static String describe(UUID id) {
        synchronized (SESSIONS) {
            Session s = SESSIONS.get(id);
            return s == null ? "服务端无建造会话" : s.describe();
        }
    }

    private static void reject(ServerPlayer sp, long planId, String why) {
        PacketDistributor.sendToPlayer(sp, new Packets.BuildResult(planId, ST_ERROR,
                0, 0, 0, 0, 0, 0, 0, 0, List.of(), why));
    }

    /**
     * r25：同一份计划<b>只报一次错</b>。
     *
     * <p>分帧上传一份大图纸是几十包，一旦服务端这边对不上号，剩下的包还会一包接一包地来。
     * 旧代码每包各发一记 {@code BuildResult(ST_ERROR)}，客户端聊天栏被同一句话刷上百行，
     * HUD 也一直在"出错/空闲"之间跳。这里把刚报过错的 planId 记下来，同一 plan 的后续
     * 分包静默丢弃（不同 plan 会覆盖，客户端重按 V+U 用的是新 nanoTime）。</p>
     */
    private static void rejectOnce(ServerPlayer sp, long planId, String why) {
        Long last = REJECTED_PLAN.get(sp.getUUID());
        if (last != null && last == planId) return;      // 这一份已经报过了，后面的包丢掉就行
        REJECTED_PLAN.put(sp.getUUID(), planId);
        reject(sp, planId, why);
    }

    // ---------------- 驱动 ----------------

    @SubscribeEvent
    public static void onServerTick(Post event) {
        long nowMs = System.currentTimeMillis();
        synchronized (SESSIONS) {
            if (SESSIONS.isEmpty()) return;
            Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
            while (it.hasNext()) {
                Session s = it.next().getValue();
                if (s.player.isRemoved()) {
                    s.silentDrop();
                    it.remove();
                    continue;
                }
                if (s.player.isSpectator()) {
                    if (s.sealed) s.finish(ST_ERROR, "旁观模式下停手（放下去的方块保留）");
                    else s.silentDrop();
                    it.remove();
                    continue;
                }
                // <b>r25 主修：分帧上传途中的会话不是"已结束"，是"还没开始"</b>
                //
                // <p>旧版收尾只认 {@link Session#live()}（RUNNING / PAUSED），而一张超过
                // {@link SR#BUILD_CHUNK} 格的图纸会有几秒～几十秒处在 <b>state=ST_IDLE 且
                // 包还没收齐</b> 的中间态：客户端一 tick 发一包，两包之间必然跨过一个服务端
                // tick，于是首包刚建好的会话就在这道清理里被 {@code it.remove()} 掉了。
                // 第二包到达时 {@code SESSIONS.get(id)} 为空 → 回一句
                // 「服务端没有这一号建造计划」——正是玩家看到的"服务端没这个任务"。
                // 现在收包中的会话原样留着，只在长时间等不到下一包（客户端退了/崩了）时丢掉。</p>
                if (!s.sealed) {
                    if (nowMs - s.lastPacketMs > SR.BUILD_UPLOAD_STALL_MS) {
                        GhostDebug.line("build: 上传静默 " + (nowMs - s.lastPacketMs) / 1000
                                + " 秒，丢弃半成品会话 plan=" + s.planId + "（已收 " + s.packets
                                + " 包 / 约需 " + s.expectedPacketCount() + " 包，条目 " + s.total + "/"
                                + s.expectedEntries + "）—— 客户端多半中途退了");
                        s.silentDrop();
                        it.remove();
                    }
                    continue;
                }
                s.tick++;
                try {
                    s.advance();
                } catch (Throwable t) {
                    s.finish(ST_ERROR, "内部异常：" + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : " " + t.getMessage()));
                    GhostDebug.throwAt("建造会话 advance", t);
                }
                if (!s.live()) it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            synchronized (SESSIONS) {
                Session s = SESSIONS.remove(sp.getUUID());
                if (s != null) s.silentDrop();
                REJECTED_PLAN.remove(sp.getUUID());     // r25：记档跟着人走，别留垃圾键
            }
        }
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            synchronized (SESSIONS) {
                Session s = SESSIONS.remove(sp.getUUID());
                if (s != null) s.silentDrop();   // 换维度：坐标全对不上了，直接收队
                REJECTED_PLAN.remove(sp.getUUID());
            }
        }
    }

    // ---------------- 会话 ----------------

    /** 一个待办格子。 */
    private static final class Job {
        final long pos;
        final int pal;
        long readyAt;      // 只有 tick >= readyAt 才允许再碰（延后重试）
        boolean done;
        /**
         * r23：这一格的缺料已经记进过 {@code missingBy} 没有。延后重试一轮就再记一笔，
         * 是「HUD 缺料 145、L 键真缺 7」那笔糊涂账的正主——一块格子只许欠账一次，
         * 放下去（或确定永远放不了、被别的账收走）时再销账。
         */
        boolean shortCounted;
        /**
         * <b>r27</b>：这笔欠账记在<b>哪一个物品</b>上、欠了<b>几件</b>。
         *
         * <p>旧版销账写死「一次销 1 件」，那是因为那时一格最多只欠 1 件。现在一格可能是两样
         * 材料（花盆 + 植物）、也可能一样要好几件（双层台阶 2 件、雪层按层数），销账只销 1 件
         * 就会留下一笔永远平不掉的缺口，投影 L 键又跟着变虚高。欠多少记多少，销的时候照数销。</p>
         */
        Item shortItem;
        int shortCount;

        Job(long pos, int pal) {
            this.pos = pos;
            this.pal = pal;
        }
    }

    private static final class Session {
        final ServerPlayer player;
        final long planId;
        final long createdAt = System.currentTimeMillis();
        /** 形态表：下标 -> 解析结果。 */
        final List<Entry> palette = new ArrayList<>();
        final Map<String, Integer> paletteIndex = new HashMap<>();
        /** 上传期间先攒在这儿（客户端已按「自下往上、流体垫底」排好，这里只照单执行）。 */
        final List<Job> jobs = new ArrayList<>();
        /**
         * 开工后真正执行的队列。用<b>双端队列</b>而不是「定长数组 + 轮转指针」：
         * 放好的条目出队就<b>真的消失</b>，不会因为列表里躺着几十万个已完成的格子而空转，
         * 延后的条目 {@code offerLast} 回到队尾即可。
         */
        final ArrayDeque<Job> pending = new ArrayDeque<>();

        int state = ST_IDLE;
        /** r23：本会话是被 V+M 补料按下的暂停（补料收场自动续工）；玩家手动暂停不记这个档。 */
        boolean heldByRestock;
        /**
         * <b>r25：分包是否已收齐</b>（收到过 {@code last=true} 那一包，{@link #start()} 已尝试过）。
         *
         * <p>这是「还没开始」与「已经结束」的分界线：上传途中 {@link #state} 一直是
         * {@code ST_IDLE}，{@link #live()} 为 false，但它<b>不是</b>可以回收的会话——
         * {@link #onServerTick} 旧版就是把这个当成收场的会话删了，第二包才对不上号。</p>
         */
        boolean sealed;
        /** r25：最近一次收到本计划分包的时刻（毫秒），用来回收半途而废的上传。 */
        long lastPacketMs = System.currentTimeMillis();
        /** r25：已收到的分包数（诊断用）。 */
        int packets;
        /** r25：客户端声明的条目总数（首包带的 {@code total}），用来估还要几包。 */
        int expectedEntries;
        int total;          // 服务端实际收下的条目数（非法形态已剔除）
        long tick;
        long sleepUntil;    // 全员都在等重试点时睡到第几 tick
        /**
         * <b>r27</b>：上一次<b>真有一格落地</b>（{@link #placed} 涨）的时刻。
         * 零进展连续超过 {@link SR#BUILD_STALL_MS} 就自动收场，见类注释第四之一节。
         */
        long lastProgressMs = System.currentTimeMillis();

        // 计数
        int placed, fluidPlaced, bePlaced, skipped, blocked, missing, noItem, badState, deferred;
        /** r28：因 {@code autobuild.placeFluids=false} 被服务端闸门判掉的流体格数。 */
        int fluidsDropped;
        int settled;        // 累计已结算的条目数（HUD/进度用；是否收工看 pending 空不空）

        final Map<Item, Integer> consumed = new HashMap<>();
        final Map<Item, Integer> missingBy = new HashMap<>();
        final Map<Item, Long> outOfStockUntil = new HashMap<>();

        final StorageTargets.Feeder feeder;
        boolean consumedDirty;
        int lastReportTick;

        Session(ServerPlayer sp, Packets.BuildPlan first) {
            this.player = sp;
            this.planId = first.planId();
            this.feeder = new StorageTargets.Feeder(sp);
        }

        boolean live() { return state == ST_RUNNING || state == ST_PAUSED; }

        // ---- 收包 ----

        void absorb(Packets.BuildPlan p) {
            packets++;
            if (p.total() > 0) expectedEntries = p.total();
            if (p.first()) {
                for (String s : p.palette()) addPalette(s);
            }
            int n = Math.min(p.positions().size(), p.states().size());
            for (int i = 0; i < n; i++) {
                int idx = p.states().get(i);
                if (idx < 0 || idx >= palette.size()) { badState++; continue; }
                if (palette.get(idx).bad) { badState++; continue; }
                if (jobs.size() >= SR.BUILD_MAX_PLAN) { badState++; continue; }
                jobs.add(new Job(p.positions().get(i), idx));
            }
            total = jobs.size();
        }

        /** r25：按客户端声明的条目数估算一共要几包（纯诊断，不参与判定）。 */
        int expectedPacketCount() {
            if (expectedEntries <= 0) return 0;
            return (expectedEntries + SR.BUILD_CHUNK - 1) / SR.BUILD_CHUNK;
        }

        private void addPalette(String encoded) {
            if (paletteIndex.containsKey(encoded) || palette.size() >= SR.BUILD_MAX_PALETTE) return;
            Entry e = decode(encoded);
            paletteIndex.put(encoded, palette.size());
            palette.add(e);
            if (e.bad && GhostDebug.on() && GhostDebug.once("build:badstate:" + encoded)) {
                GhostDebug.line("build: 形态无法解析，整类跳过 -> " + encoded);
            }
        }

        void start() {
            if (state == ST_RUNNING || state == ST_PAUSED) return;
            if (jobs.isEmpty()) {
                state = ST_DONE;
                PacketDistributor.sendToPlayer(player, new Packets.BuildResult(planId, ST_DONE,
                        0, 0, 0, skipped, blocked, 0, badState, 0, List.of(),
                        badState > 0 ? "服务端把 " + badState + " 格的形态判成非法，没有可放的东西"
                                     : "没有收到任何待放置格子"));
                return;
            }
            pending.addAll(jobs);
            jobs.clear();               // 之后一律以 pending 为准，省内存也免得两处口径打架
            // <b>r23 防御 → r25 改挂起</b>：补料包正好插在分帧上传的中途，两个引擎会抢同一只背包。
            // 旧版在这里直接判 ST_ERROR，整份刚传完的计划就此作废、还得重按 V+U。
            // 现在先把队列攒好、以"补料让路"的暂停态交出去：补料会话收场时
            // releaseBuildAfterRestock 会照常把它续成 RUNNING（r23 的交接棒机制原样复用）。
            if (TakeoverEngine.isBusy(player.getUUID())) {
                state = ST_PAUSED;
                heldByRestock = true;
                lastReportTick = 0;
                report(true);
                GhostDebug.line("build: 计划已收齐 " + total + " 格，但补料接管正在动背包 —— "
                        + "本会话挂起等待（plan=" + planId + "，补料收场自动开工）");
                return;
            }
            state = ST_RUNNING;
            lastReportTick = 0;
            report(true);
            GhostDebug.line("build: 开工 player=" + player.getName().getString()
                    + " 条目=" + total + " 形态=" + palette.size()
                    + " 覆盖策略=" + policyName(CURRENT_POLICY)
                    + " 距离闸门=" + (int) ModConfig.buildDistance() + " 格"
                    + " 限流=" + ModConfig.buildBlocksPerTick() + "块/tick + "
                    + (SR.BUILD_TICK_BUDGET_NS / 1_000_000L) + "ms"
                    + " 流体=" + (ModConfig.placeFluidsAllowed() ? "放（排最后）" : "不放（配置挡住）"));
        }

        // ---- 推进 ----

        void advance() {
            if (state != ST_RUNNING) {
                // 暂停也要按节流发心跳，否则客户端看门狗会把会话判死
                if (state == ST_PAUSED && dueForReport()) report(false);
                return;
            }
            if (pending.isEmpty()) {
                finish(ST_DONE, summarize());
                return;
            }
            if (System.currentTimeMillis() - createdAt > SR.BUILD_SESSION_TIMEOUT_MS) {
                finish(ST_ERROR, "会话超时（" + SR.BUILD_SESSION_TIMEOUT_MS / 1000
                        + " 秒）；剩下的按 V+U 重开一轮即可（已放好的不会重放）");
                return;
            }
            // <b>r27 主修之二：零进展自动收场。</b>
            //
            // <p>这道闸门放在睡眠判定<b>之前</b>——真正卡住的会话恰恰是睡在重试点上、
            // 一 tick 都不干活的那种。缺料延后本身是对的（要给玩家按 V+M 补料的时间），
            // 但一种料箱里压根没有时，旧版能让会话挂着两小时一动不动：HUD 定格在
            // 「建造中 · 缺料 N · 延后 M」，客户端每收一包进度就催一次投影重算材料清单，
            // 投影每起一次任务就播一句「已开始…」，看起来就是卡死＋刷屏。</p>
            long stalledMs = System.currentTimeMillis() - lastProgressMs;
            if (stalledMs > SR.BUILD_STALL_MS) {
                finish(ST_DONE, "连续 " + (stalledMs / 1000) + " 秒没有一格落地，这一轮先收场。"
                        + summarize() + "。补齐后重按 V+U 会接着建（已经放好的不会重放、不会二次扣料）");
                GhostDebug.line("build: 零进展 " + stalledMs / 1000 + " 秒自动收场 plan=" + planId
                        + " 待处理=" + pending.size() + " 欠账品种=" + missingBy.size()
                        + " " + describeMissing());
                return;
            }
            if (sleepUntil > tick) {
                if (dueForReport()) report(false);
                return;
            }

            long deadline = Util.getNanos() + SR.BUILD_TICK_BUDGET_NS;
            ServerLevel level = player.serverLevel();
            int placedAtEntry = placed;
            int resolvedAtEntry = settled;
            long minReady = Long.MAX_VALUE;
            feeder.refresh(player, tick);

            int scans = 0;
            final int perTick = ModConfig.buildBlocksPerTick();   // r28：每 tick 限流走配置文件
            final double reach = ModConfig.buildDistance();        // r28：距离闸门同上（格）
            final boolean fluidsOk = ModConfig.placeFluidsAllowed();
            while (!pending.isEmpty()
                    && scans < SR.BUILD_MAX_SCANS_PER_TICK
                    && placed - placedAtEntry < perTick
                    && Util.getNanos() < deadline) {
                Job j = pending.pollFirst();          // 出队即负责：放好就结束，延后就回队尾
                scans++;
                if (j.readyAt > tick) {
                    pending.offerLast(j);
                    if (j.readyAt < minReady) minReady = j.readyAt;
                    continue;
                }
                place(level, j, reach, fluidsOk);
                if (!j.done) {
                    pending.offerLast(j);             // place() 判为「延后」：排回队尾等重试点
                    if (j.readyAt < minReady) minReady = j.readyAt;
                }
            }

            if (placed + settled == placedAtEntry + resolvedAtEntry) {
                // 这个 tick 一格都没落地：轮转范围内全是在等价着的条目。
                // 睡到最早那个重试点，但<b>最多睡一个延后周期</b> —— 零进展也可能只是
                // 「扫描配额用完了、还没轮到那些能做的格子」，睡死在这儿就会假停滞。
                sleepUntil = Math.min(minReady == Long.MAX_VALUE ? tick + 1 : minReady,
                        tick + SR.BUILD_DEFER_TICKS);
                deferred = pending.size();
            } else {
                sleepUntil = 0;
                deferred = 0;
            }
            if (pending.isEmpty()) {
                finish(ST_DONE, summarize());
                return;
            }
            if (dueForReport()) report(false);
        }

        private void place(ServerLevel level, Job j, double reach, boolean fluidsOk) {
            Entry e = palette.get(j.pal);
            BlockPos pos = BlockPos.of(j.pos);

            if (e.bad || e.state == null) { settle(j); badState++; return; }
            if (level.isOutsideBuildHeight(pos)) { settle(j); blocked++; return; }
            // r28：autobuild.placeFluids=false —— 服务端这一闸是权威（客户端建计划时已经滤过一遍，
            // 但两端各拿自己那份 COMMON 配置，专用服务器上以服务端这份为准）。判掉的流体单独计数，
            // 收尾那句会说明有多少格是因为流体被禁而没放；不记 blocked（那不是"非空"也不是拒收）。
            if (e.fluid && !fluidsOk) { settle(j); unshort(e, j); fluidsDropped++; return; }
            // chunk 没加载：延后。绝不为了造砖去强拉区块（那才是真正的卡顿来源）。
            if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
                j.readyAt = tick + SR.BUILD_DEFER_TICKS;
                return;
            }
            // 距离闸门：够不着就延后（玩家走过去自然会继续），不判死
            double dx = pos.getX() + 0.5 - player.getX();
            double dy = pos.getY() + 0.5 - player.getY();
            double dz = pos.getZ() + 0.5 - player.getZ();
            if (dx * dx + dy * dy + dz * dz > reach * reach) {
                j.readyAt = tick + SR.BUILD_DEFER_TICKS;
                return;
            }

            BlockState want = e.state;
            BlockState have = level.getBlockState(pos);
            if (have == want) { settle(j); unshort(e, j); skipped++; return; }   // 已经是对的砖
            if (!mayReplace(have)) { settle(j); unshort(e, j); blocked++; return; } // 覆盖策略不放行

            // r27：花盆那一类以前就是从这一步掉出去的 —— Item.byBlock 查不到物品，
            // 于是整格判「原版没有对应物品」。现在 decode 已经把两样料配好了，
            // 只有真·无物品（火、红石线、屏障、结构空位）才会走到这里。
            if (!e.hasAnyItem() && !e.fluid && !e.secondary && !player.isCreative()) {
                // 生存模式下不放（白给 = 复制）；创造模式照样给
                settle(j);
                unshort(e, j);
                noItem++;
                return;
            }

            // ---- 扣料（先扣再放；一格两料的两样都齐了才放）----
            int cost = e.cost(player);
            int cost2 = e.cost2(player);
            // 两样里有一样上一轮已经查明身上取不到：整格直接延后。
            // 不先过这道闸，花盆那一格会每一轮都把空花盆掏出来再退回去，白翻两遍背包。
            long shortUntil = 0;
            if (cost > 0) shortUntil = Math.max(shortUntil, cachedShort(e.item));
            if (cost2 > 0) shortUntil = Math.max(shortUntil, cachedShort(e.second));
            if (shortUntil > tick) { j.readyAt = shortUntil; return; }
            if (cost > 0 && !charge(e, j, e.item, cost)) return;
            if (cost2 > 0 && !charge(e, j, e.second, cost2)) {
                // 花盆拿到了、植物没拿到：主料原样退回，这一格整体延后，绝不黑料。
                // 这里必须走 refund（不是 giveBack）：第一笔已经 pay 记进消耗账了，
                // 退东西同时要把那笔账冲掉，否则客户端会按「已进墙」倒扣投料账本，
                // 下一轮 V+M 就少补这一件。
                if (cost > 0) refund(e.item, cost);
                return;
            }

            // ---- 落砖 ----
            int paid1 = cost, paid2 = cost2;
            boolean ok;
            try {
                ok = level.setBlock(pos, want, BUILD_FLAGS);
            } catch (Throwable t) {
                settle(j);
                unshort(e, j);
                blocked++;
                if (paid1 > 0) refund(e.item, paid1);
                if (paid2 > 0) refund(e.second, paid2);
                GhostDebug.line("build: setBlock 抛异常 @ " + GhostDebug.pos(pos) + " " + e.encoded
                        + " -> " + t.getClass().getSimpleName() + " " + t.getMessage());
                return;
            }
            settle(j);
            if (!ok) {
                // 少数模组方块会自己拒收这个形态：东西退回，别黑玩家的料
                if (paid1 > 0) refund(e.item, paid1);
                if (paid2 > 0) refund(e.second, paid2);
                unshort(e, j);
                blocked++;
                return;
            }
            placed++;
            lastProgressMs = System.currentTimeMillis();   // r27：真有一格落地，零进展计时归零
            if (e.fluid) fluidPlaced++;
            if (want.hasBlockEntity()) bePlaced++;
            if (e.item != null) outOfStockUntil.remove(e.item);
            if (e.second != null) outOfStockUntil.remove(e.second);
            // r23：这一格放上了，它自己那份欠账当场销掉（只有欠过账的格子才扣，
            // 不会把别的格子的账凭空平掉，也不会给从没欠账的格子凭空减数）。
            unshort(e, j);
        }

        /** r27：上一轮给这个品种记的「身上取不到」时点（没有就 0）。 */
        private long cachedShort(Item it) {
            Long u = outOfStockUntil.get(it);
            return u == null ? 0L : u;
        }

        /**
         * 从身上扣一笔料；扣不满就按「这一品种没货」延后这一格，并<b>只欠一次账</b>。
         *
         * @return true = 已扣到 {@code n} 件（{@link #pay} 已记账）；false = 没扣到，
         *         调用方直接把这一格交出去（{@code j.readyAt} 已设好）
         */
        private boolean charge(Entry e, Job j, Item it, int n) {
            Long until = outOfStockUntil.get(it);
            if (until != null && until > tick) {           // 已知没货：别反复翻背包
                j.readyAt = until;
                return false;
            }
            int got = feeder.take(it, n, SR.BUILD_SCAN_SLOTS);
            if (got < n) {
                // r27：零头当场退回。要 2 件只摸到 1 件时，旧写法会把这 1 件"记成已消耗"
                // 却什么都不放——格子延后到下一轮，料就此凭空蒸发（取消施工时更是一个都不剩）。
                if (got > 0) giveBack(it, got);
                j.readyAt = tick + SR.BUILD_STOCK_RETRY_TICKS;
                outOfStockUntil.put(it, j.readyAt);
                // r23：一块格子的欠账只记一次。旧版每轮延后重试都 merge 一遍，
                // 缺料数字被同一批格子滚成好几倍（HUD"缺料 145"、L 键真缺 7 的元凶）。
                int gap = n - got;
                // r27：一格两料（花盆 + 植物）会换品种挡路 —— 上一轮缺竹子、这一轮花盆先用掉了，
                // 挡住它的是另一样料。旧账不销，L 键缺口就一直指着不存在的那一样。
                if (j.shortCounted && (j.shortItem != it || j.shortCount != gap)) unshort(e, j);
                if (!j.shortCounted) {
                    j.shortCounted = true;
                    j.shortItem = it;
                    j.shortCount = gap;
                    missingBy.merge(it, gap, Integer::sum);
                    missing += gap;
                }
                if (GhostDebug.on() && GhostDebug.once("build:nostock:"
                        + BuiltInRegistries.ITEM.getKey(it))) {
                    GhostDebug.line("build: 身上取不到 " + BuiltInRegistries.ITEM.getKey(it) + " ×" + n
                            + "（这一格要 " + n + " 件，只摸到 " + got + " 件，已原样退回；翻了 "
                            + feeder.lastScanSlots() + " 格，其中 " + feeder.lastSkippedFilled()
                            + " 个装着东西的容器按规矩跳过）来源=" + feeder.describe()
                            + " 形态=" + e.encoded);
                }
                return false;
            }
            pay(it, n);
            return true;
        }

        private void settle(Job j) {
            j.done = true;
            settled++;
        }

        /**
         * r23（r27 改了销账件数）：一格收场（放上、已对、拒收、无物品）时销掉它自己的欠账。
         * 只有 {@code shortCounted} 的那一格才扣，按<b>当初记的那件物品、那个件数</b>原样销掉
         * ——一格两料（花盆+植物）和一格多件（双层台阶 2 件、雪层 N 件）都靠这一点才平得干净。
         */
        private void unshort(Entry e, Job j) {
            if (!j.shortCounted) return;
            j.shortCounted = false;
            Item it = j.shortItem != null ? j.shortItem : e.item;
            int n = j.shortCount;
            j.shortItem = null;
            j.shortCount = 0;
            if (it == null || n <= 0) return;
            Integer m = missingBy.get(it);
            if (m != null) {
                if (m <= n) missingBy.remove(it);
                else missingBy.put(it, m - n);
            }
            missing = Math.max(0, missing - n);
        }

        /** 真实付料：记账（客户端据此反向扣减投料账本）。 */
        private void pay(Item it, int n) {
            consumed.merge(it, n, Integer::sum);
            consumedDirty = true;
        }

        /**
         * <b>r27 新增</b>：把「取出来了但这一格没能用上」的零头塞回身上，<b>不记消耗</b>
         * （压根没进墙，记进账本就等于凭空吞料，还会把客户端那份投料账本扣歪）。
         */
        private void giveBack(Item it, int n) {
            if (it == null || n <= 0) return;
            try {
                ItemStack back = new ItemStack(it, n);
                if (!player.getInventory().add(back)) player.drop(back, false);
            } catch (Throwable ignored) {}
        }

        /** {@code setBlock} 被拒：把已经取出来的东西退回身上（塞不下就掉在脚下，绝不吞）。 */
        private void refund(Item it, int n) {
            consumed.merge(it, -n, Integer::sum);
            consumedDirty = true;
            try {
                ItemStack back = new ItemStack(it, n);
                if (!player.getInventory().add(back)) player.drop(back, false);
            } catch (Throwable ignored) {}
        }

        // ---- 收尾 ----

        void finish(int endState, String note) {
            if (state != ST_RUNNING && state != ST_PAUSED) {
                state = endState;     // 只收了包就没了：发结果没人认，直接静默
                return;
            }
            state = endState;
            List<Packets.Entry> list = new ArrayList<>();
            for (Map.Entry<Item, Integer> m : missingBy.entrySet()) {
                if (m.getValue() == null || m.getValue() <= 0) continue;
                list.add(new Packets.Entry(BuiltInRegistries.ITEM.getKey(m.getKey()).toString(), m.getValue()));
            }
            PacketDistributor.sendToPlayer(player, new Packets.BuildResult(planId, endState,
                    placed, fluidPlaced, bePlaced, skipped, blocked, noItem, badState,
                    sum(list), list, note == null ? "" : note));
            // r28：结果包已发出，欠账/待办这些大集合当场释放（长期开服防漏，见 silentDrop 注释）。
            list.clear();
            wipe();
            GhostDebug.line("build: 结束[" + (endState == ST_CANCELLED ? "取消"
                    : endState == ST_ERROR ? "中断" : "完成") + "] 放=" + placed
                    + " 流体=" + fluidPlaced + " 方块实体=" + bePlaced + " 已对=" + skipped
                    + " 非空/拒收=" + blocked + " 缺料=" + missing + " 无物品=" + noItem
                    + " 坏形态=" + badState + " | " + note);
        }

        private static int sum(List<Packets.Entry> list) {
            int n = 0;
            for (Packets.Entry e : list) n += e.count();
            return n;
        }

        private String summarize() {
            StringBuilder sb = new StringBuilder();
            sb.append("共放 ").append(placed).append(" 块");
            if (fluidPlaced > 0) sb.append("（流体 ").append(fluidPlaced).append(" 格，最后放的）");
            if (skipped > 0) sb.append("；本来就对的 ").append(skipped).append(" 格没动");
            if (blocked > 0) sb.append("；非空/拒收跳过 ").append(blocked).append(" 格");
            if (noItem > 0) sb.append("；").append(noItem).append(" 格原版没有对应物品（火/红石线/屏障一类）没放");
            if (fluidsDropped > 0) sb.append("；").append(fluidsDropped)
                    .append(" 格流体被配置挡住（autobuild.placeFluids=false）");
            if (badState > 0) sb.append("；形态解析失败 ").append(badState).append(" 格");
            if (!missingBy.isEmpty()) {
                sb.append("；还缺 ").append(sum(missingList())).append(" 块（")
                        .append(missingBy.size()).append(" 种）：按 V+M 补料（建造自动暂停让路，补完自动续工）");
            }
            return sb.toString();
        }

        private List<Packets.Entry> missingList() {
            List<Packets.Entry> out = new ArrayList<>(missingBy.size());
            for (Map.Entry<Item, Integer> e : missingBy.entrySet()) {
                out.add(new Packets.Entry(BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), e.getValue()));
            }
            return out;
        }

        /** r27：欠账明细（只进排障日志，一句话列到 8 种）。 */
        private String describeMissing() {
            if (missingBy.isEmpty()) return "（没有欠账，纯粹是够不着/区块没加载）";
            StringBuilder sb = new StringBuilder("缺：");
            int n = 0;
            for (Map.Entry<Item, Integer> e : missingBy.entrySet()) {
                if (n++ >= 8) { sb.append("…其余从略"); break; }
                sb.append(BuiltInRegistries.ITEM.getKey(e.getKey())).append('×').append(e.getValue()).append(' ');
            }
            return sb.toString();
        }

        // ---- 发包 ----

        boolean dueForReport() {
            return tick - lastReportTick >= SR.BUILD_PROGRESS_EVERY_TICKS;
        }

        void report(boolean force) {
            if (!force && tick - lastReportTick < SR.BUILD_PROGRESS_EVERY_TICKS) return;
            lastReportTick = (int) tick;
            List<Packets.Entry> snap = consumedDirty ? consumedList() : List.of();
            consumedDirty = false;
            PacketDistributor.sendToPlayer(player, new Packets.BuildProgress(planId, state,
                    placed, skipped, blocked, missing, pendingCount(), total,
                    !snap.isEmpty(), snap));
        }

        private int pendingCount() {
            return pending.size();
        }

        private List<Packets.Entry> consumedList() {
            List<Packets.Entry> out = new ArrayList<>(consumed.size());
            for (Map.Entry<Item, Integer> e : consumed.entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                out.add(new Packets.Entry(BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), e.getValue()));
            }
            return out;
        }

        String describe() {
            if (!sealed) {
                return "正在收计划包（已收 " + packets + " 包 / 约需 " + expectedPacketCount()
                        + " 包，条目 " + total + "/" + expectedEntries + "，形态表 " + palette.size()
                        + " 条；上一包 " + (System.currentTimeMillis() - lastPacketMs) / 1000 + " 秒前）";
            }
            return "state=" + stateName(state) + " 已放 " + placed + "/" + total
                    + "，缺料 " + missing + "，非空 " + blocked + "，待处理 " + pendingCount()
                    + "，形态表 " + palette.size() + " 条";
        }

        /**
         * 玩家掉线 / 换维度 / 上传半途而废：不再发包，直接弃会话。
         *
         * <p><b>r28（发布前防漏）：就地清空</b>。形态表 + 待办队列在 20 万格的大图纸上是
         * 几十 MB 量级；"从 {@code SESSIONS} 摘掉就等 GC"平时够用，但服主长期开服、
         * 玩家频繁掉线重连时，任何一条还攥着 Session 引用的异常路径都会把整串大集合拖住。
         * 收场时清空，留一个空壳，内存回收不再依赖"没人引用"这一件事。</p>
         */
        void silentDrop() {
            state = ST_IDLE;
            wipe();
        }

        /** r28：清干净本会话持有的全部集合（silentDrop 与 finish 收尾都走这里）。 */
        private void wipe() {
            jobs.clear();
            pending.clear();
            palette.clear();
            paletteIndex.clear();
            missingBy.clear();
            outOfStockUntil.clear();
            consumed.clear();
        }
    }

    private static String stateName(int s) {
        return switch (s) {
            case ST_RUNNING -> "进行中";
            case ST_PAUSED -> "已暂停";
            case ST_DONE -> "已完成";
            case ST_CANCELLED -> "已取消";
            case ST_ERROR -> "出错";
            default -> "空闲";
        };
    }

    // ---------------- 形态表条目 ----------------

    /**
     * 一条形态：BlockState + 主料（件数）+ 附加料（件数）。
     *
     * <p>{@code secondary} 是<b>床 / 门 / 双格高植物</b>这类「一个物品占两格」方块的关键：
     * 第二格（{@code part=head} 或 {@code half=upper}）记 0 件，免得一张床扣玩家两张床。
     * 由于放置是直接写 BlockState（不是右键合成），第二格照样能放对，只是不再收钱。</p>
     *
     * <p><b>r27</b>：一格不再恒等于一件。
     * {@code number} 是主料件数（双层台阶 2、雪层按 {@code layers}、蜡烛按 {@code candles}…），
     * {@code second / secondNumber} 是第二样料 —— 只有「花盆里带植物」这类方块用得上：
     * {@code minecraft:potted_bamboo} 用 {@code Item.byBlock} 反查是<b>查不到物品</b>的，
     * 旧版就把它判成「原版没有对应物品」整格跳过，玩家的花盆永远砌不上去。
     * 投影自己的材料清单（{@code MaterialCache.requiresMultipleItems / getItems}）
     * 把它算成「空花盆 1 个 + 植物 1 个」两样，这里就照这个口径收，L 键缺口和 V+M 补料才对得上。</p>
     */
    private static final class Entry {
        final String encoded;
        BlockState state;
        Item item;
        int number = 1;
        /** 第二样料（花盆里那株植物）；没有就是 {@code null}。 */
        Item second;
        int secondNumber = 1;
        boolean fluid;
        boolean secondary;
        boolean bad;

        Entry(String encoded) { this.encoded = encoded; }

        /** 这一格到底要不要收钱（流体、双格方块的第二格、判坏的形态、创造模式都免费）。 */
        private boolean chargeable(net.minecraft.world.entity.player.Player player) {
            return !(fluid || secondary || bad) && !player.isCreative();
        }

        /** 主料要几件（0 = 不收）。 */
        int cost(net.minecraft.world.entity.player.Player player) {
            if (!chargeable(player) || item == null || item == Items.AIR) return 0;
            return Math.max(1, number);
        }

        /** 附加料要几件（0 = 没有第二样）。 */
        int cost2(net.minecraft.world.entity.player.Player player) {
            if (!chargeable(player) || second == null || second == Items.AIR) return 0;
            return Math.max(1, secondNumber);
        }

        /** 这个形态身上带不带任何一件可消耗的物品（决定「原版没有对应物品」判不判）。 */
        boolean hasAnyItem() {
            return item != null && item != Items.AIR;
        }
    }

    // ---------------- 覆盖策略 ----------------

    /**
     * 生效中的覆盖策略：0=只填空气（用户拍板的默认）1=含原版可替换 2=不对就换。
     * 客户端建计划时按同一口径筛过一遍，服务端这里用<b>真实世界</b>的状态再判一遍。
     */
    static volatile int CURRENT_POLICY = SR.BUILD_REPLACE_DEFAULT;

    public static void setPolicy(int p) {
        CURRENT_POLICY = Math.max(0, Math.min(2, p));
    }

    public static int policy() { return CURRENT_POLICY; }

    public static String policyName(int p) {
        return p == 0 ? "只填空气" : p == 1 ? "含可替换（草/雪/水）" : "不对就覆盖";
    }

    private static boolean mayReplace(BlockState have) {
        return switch (CURRENT_POLICY) {
            case SR.BUILD_REPLACE_SOFT -> have.isAir() || have.canBeReplaced();
            case SR.BUILD_REPLACE_MISMATCH -> true;
            default -> have.isAir();
        };
    }

    // ---------------- BlockState 文本还原 ----------------

    /**
     * 把 {@code namespace:path[k=v,k2=v2]} 还原成 BlockState —— 直接借用原版
     * {@code /setblock} 那套解析器（{@link BlockStateParser}）：属性名、取值、要不要引号，
     * 全部和玩家手敲指令一模一样，不必自己再造一遍轮子。
     *
     * <p>为什么用文本而不是数字 id：方块形态的数字 id 是注册表下标，客户端/服务端的模组加载顺序
     * 只要有一点不同就会整体错位，错一位就是一墙面目全非的砖；文本 id 永远指得准。</p>
     *
     * <p>第三个参数 {@code allowBlockEntityTag=false} 是<b>故意</b>关掉的：那是方块实体 NBT
     * 唯一的入口。关掉之后，就算传进来的文本里夹了 {@code <{...}>}，解析器当场报错、
     * 这条形态被判坏，而不是悄悄把原理图里箱子/告示牌的内容搬进真实世界（见第五节：绝对不搬）。</p>
     *
     * <p>每种形态只解析<b>一次</b>（结果存进形态表），放置热路径上不重复解析。</p>
     */
    private static Entry decode(String s) {
        Entry e = new Entry(s);
        try {
            BlockState st = BlockStateParser
                    .parseForBlock(BuiltInRegistries.BLOCK.asLookup(), s, false)
                    .blockState();
            if (st == null || st.isAir()) {     // 空气没什么可放的：判坏，让它进不了队列
                e.bad = true;
                return e;
            }
            e.state = st;
            var fl = st.getFluidState();
            e.fluid = !fl.isEmpty() && fl.createLegacyBlock() == st;
            e.secondary = detectSecondary(st);
            if (e.fluid || e.secondary) {
                e.item = Items.AIR;             // 流体与双格方块的第二格：不收东西
                e.number = 0;
            } else {
                Item it = itemOf(st.getBlock());
                if (it != null) {
                    e.item = it;
                    e.number = piecesFor(st);   // 双层台阶/雪层/蜡烛这类一格不止一件的
                } else if (pottedPlant(e, st)) {
                    // 花盆 + 植物：一格两样料（见 Entry 的 r27 说明）
                } else if (overrideItem(e, st)) {
                    // 耕地这类「方块本身没有物品、但投影按某个来源方块计料」的
                } else {
                    e.item = Items.AIR;         // 真·无物品（火、红石线、屏障…）
                    e.number = 0;
                }
            }
        } catch (Throwable t) {
            // CommandSyntaxException（方块不存在 / 属性名或取值非法 / 夹带了 NBT）一律归到这里：
            // 只把这一条形态判坏、跳过它自己，绝不牵连整份计划。
            e.bad = true;
            e.state = null;
        }
        return e;
    }

    /** 方块对应的物品；查不到（没有 BlockItem）返回 {@code null}。 */
    private static Item itemOf(Block block) {
        try {
            Item it = Item.byBlock(block);
            return (it == null || it == Items.AIR) ? null : it;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * <b>r27 主修：花盆里带植物（{@code minecraft:potted_bamboo} 这一类）。</b>
     *
     * <p>这类方块 {@code Item.byBlock} 反查不到物品，旧版直接判「原版没有对应物品」跳过，
     * 于是原理图里的花盆一个都放不出来（投影的幽灵还亮在那儿，施工却一格不动）。
     * 这里按投影 {@code MaterialCache.getItems()} 的同一口径收两样料：</p>
     * <ol>
     *   <li>主料 = {@code getEmptyPot()} 对应的物品（原版就是花盆）；</li>
     *   <li>附加料 = {@code getPotted()}（里面那株植物）对应的物品。</li>
     * </ol>
     * <p>放置仍然是一条 {@code setBlock} 直接写最终形态 —— 不需要「先放花盆再右键插竹子」，
     * 那是模拟玩家右键的实现才需要的绕路；我们写的就是带植物的那个 BlockState，
     * 落下来和手工摆的一模一样。两样料缺任一样都只延后不判死，欠账记在<b>缺的那一样</b>上。</p>
     *
     * <p>模组自己的花盆只要继承 {@link FlowerPotBlock} 就一并生效；
     * 空花盆本身（{@code getPotted()} 是空气）不走这里，按普通方块收一个花盆。</p>
     *
     * @return true = 已把这件形态配成「花盆 + 植物」两样料
     */
    private static boolean pottedPlant(Entry e, BlockState st) {
        Block b = st.getBlock();
        if (!(b instanceof FlowerPotBlock)) return false;
        Block plant;
        FlowerPotBlock empty;
        try {
            plant = ((FlowerPotBlock) b).getPotted();
            empty = ((FlowerPotBlock) b).getEmptyPot();
        } catch (Throwable t) {
            // 个别模组花盆用自定义 supplier 还原植物，取不到就放弃这一路
            return false;
        }
        if (plant == null || empty == null || b == empty) return false;   // 这就是空花盆本身
        if (plant == Blocks.AIR || plant.defaultBlockState().isAir()) return false;
        Item pot = itemOf(empty);
        if (pot == null) return false;                                    // 模组空花盆没有对应物品：不硬造
        e.item = pot;
        e.number = 1;
        e.second = null;
        e.secondNumber = 1;
        Item plantItem = itemOf(plant);
        if (plantItem != null) e.second = plantItem;                     // 植物没有物品的（少见）只收花盆
        if (GhostDebug.on() && GhostDebug.once("build:pot:" + e.encoded)) {
            GhostDebug.line("build: 一格两料 " + e.encoded + " -> 主料 "
                    + BuiltInRegistries.ITEM.getKey(pot) + "×1 + 附加料 "
                    + (e.second == null ? "（这株植物没有对应物品，只收花盆）"
                                        : BuiltInRegistries.ITEM.getKey(e.second) + "×1"));
        }
        return true;
    }

    /**
     * <b>r27</b>：方块本身没有对应物品、但投影按某个「来源方块」计料的少数几种 —— 目前就耕地
     * （投影 {@code getStateToItemOverride} 把 {@code FARMLAND} 记成泥土）。
     * 收一件泥土、直接写 {@code farmland[moisture=n]} 的最终形态，跟 L 键缺口同一口径。
     */
    private static boolean overrideItem(Entry e, BlockState st) {
        Block b = st.getBlock();
        Item it = null;
        if (b instanceof FarmBlock) it = itemOf(Blocks.DIRT);
        if (it == null) return false;
        e.item = it;
        e.number = 1;
        return true;
    }

    /**
     * <b>r27</b>：这一格要几件主料 —— 与投影 {@code MaterialCache.overrideStackSize} 逐条对齐，
     * 免得「投影按 2 件计缺口、我们只扣 1 件」这种少扣（也顺便免得放不上去时说不清欠几件）。
     * 默认 1 件。
     */
    private static int piecesFor(BlockState st) {
        Block b = st.getBlock();
        try {
            if (b instanceof SlabBlock && st.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) return 2;
            if (b instanceof SnowLayerBlock) return Math.max(1, st.getValue(SnowLayerBlock.LAYERS));
            if (b instanceof CandleBlock) return Math.max(1, st.getValue(CandleBlock.CANDLES));
            if (b instanceof TurtleEggBlock) return Math.max(1, st.getValue(TurtleEggBlock.EGGS));
            if (b instanceof SeaPickleBlock) return Math.max(1, st.getValue(SeaPickleBlock.PICKLES));
        } catch (Throwable ignored) {
            return 1;   // 模组子类少写了某个属性之类，绝不因为算件数把整格判坏
        }
        return 1;
    }

    /**
     * 双格方块的第二格：床的 {@code part=head}、门 / 高格植物的 {@code half=upper}。
     * （楼梯的 {@code half} 取值是 {@code top/bottom}，不会误命中。）
     */
    private static boolean detectSecondary(BlockState st) {
        for (Map.Entry<Property<?>, Comparable<?>> en : st.getValues().entrySet()) {
            String n = en.getKey().getName();
            String v = String.valueOf(en.getValue());
            if ("part".equals(n) && "head".equals(v)) return true;
            if ("half".equals(n) && "upper".equals(v)) return true;
        }
        return false;
    }

}
