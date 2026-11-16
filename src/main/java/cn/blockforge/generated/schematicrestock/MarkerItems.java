package cn.blockforge.generated.schematicrestock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「手里拿的东西算不算标记物」的唯一口径（1.0.1 新增）。
 *
 * <p>以前这个判断写死成 {@code stack.is(MARK_WAND)}，两处（右键方块、左键方块）。现在玩家可以在
 * {@code wand.useVanillaItem} / {@code wand.markerItemId} 里指定一件原版物品当标记物，
 * 于是所有手持判定都必须过这里，否则会出现「客户端认、服务端不认」的分裂。</p>
 *
 * <p>三条不变式：</p>
 * <ol>
 *   <li><b>标记签永远有效</b>：不管开关是开是关，本模组的 {@code mark_wand} 都能标箱子。
 *       老玩家背包／箱子里已有的那些不会某天突然变成废铁——这也是配置注释里承诺的「旧物品永不作废」。</li>
 *   <li><b>原版物品只在开关打开时介入</b>：关掉之后金锄就是普通金锄，右键箱子照常打开。
 *       这一点很重要，否则玩家只是关个开关，手里却莫名开不了箱子。</li>
 *   <li><b>配置写错不能崩</b>：空串、缺 namespace、拼错、甚至是没装的模组物品，一律退回
 *       「只认标记签」并往日志写一行警告。启动阶段就崩一次（r32 那个 config 崩溃）已经够教训了，
 *       这种纯玩家偏好项更没资格把游戏带倒。</li>
 * </ol>
 *
 * <p><b>为什么每次现读配置、只缓存解析结果</b>：解析物品 id 要查注册表，每 tick 两次的事件里
 * 反复查不划算；但 {@code /schematic_restock} 之类热重载配置后必须立刻生效，所以缓存的键是
 * <b>配置原文</b>——字符串一变就重新解析，天然自愈，不需要额外的失效通知机制。</p>
 */
public final class MarkerItems {

    private static final Logger LOG = LoggerFactory.getLogger("matfill/marker");

    /** 上次解析用的配置原文（已 trim + 转小写）；null = 还没解析过。 */
    private static String cachedKey = null;
    /** 对应的解析结果；null = 关着 / id 非法 / 物品不存在。 */
    private static Item cachedItem = null;

    private MarkerItems() {}

    /**
     * 配置指定的那件「替代品」。
     *
     * @return 解析到的物品；开关关闭、或 id 为空 / 非法 / 注册表里没有时为 {@code null}
     */
    public static Item configuredItem() {
        if (!ModConfig.useVanillaMarkerItem()) return null;

        String raw = ModConfig.markerItemId();
        String key = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (key.isEmpty()) {
            cachedKey = null;
            cachedItem = null;
            return null;
        }
        if (!key.equals(cachedKey)) {          // 配置变了（或第一次读）：重新查注册表
            cachedKey = key;
            cachedItem = resolve(key);
        }
        return cachedItem;
    }

    /**
     * 主手／副手这个东西能不能用来标箱子。
     * 标记签永远放行；替代品只在开关打开且 id 有效时放行。
     */
    public static boolean isMarker(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.is(SchematicRestockMod.MARK_WAND.get())) return true;
        Item vanilla = configuredItem();
        return vanilla != null && stack.is(vanilla);
    }

    /**
     * 这次拿的是<b>配置指定的原版物品</b>（不含标记签）。
     *
     * <p>只给悬浮说明用：标记签自己会画 tooltip（{@link MarkWandItem}），
     * 原版物品这边再挂一份，两边都出现就成重复广告了。</p>
     */
    public static boolean isConfiguredVanillaMarker(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item vanilla = configuredItem();
        return vanilla != null && stack.is(vanilla);
    }

    /** 供日志／诊断：当前生效的标记物 id（开关关着时直接说明用的是标记签）。 */
    public static String describe() {
        if (!ModConfig.useVanillaMarkerItem()) return SchematicRestockMod.MARK_WAND.getId().toString();
        Item vanilla = configuredItem();
        return vanilla == null ? "(配置无效，仅标记签可用)" : BuiltInRegistries.ITEM.getKey(vanilla).toString();
    }

    // ---------------- 内部 ----------------

    /** id 字符串 → 注册表里的物品；任何一步不对都返回 null 并留下一行警告。 */
    private static Item resolve(String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                LOG.warn("wand.markerItemId=\"{}\" 不是合法物品 id，本次只用材料标记签", id);
                return null;
            }
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == null || item == Items.AIR) {
                // 未注册 / 对应模组没装，get 会退回空气。空气永远不该当标记物：
                // 空手右键箱子会被吞掉，那是「什么都做不了」级别的手感事故。
                LOG.warn("wand.markerItemId=\"{}\" 在物品注册表里找不到（模组没装？拼错了？），本次只用材料标记签", rl);
                return null;
            }
            if (item == SchematicRestockMod.MARK_WAND.get()) {
                // 有人把开关打开又把 id 写成标记签自己：等价于关着，不必重复警告
                return null;
            }
            LOG.info("标记物 = {}（wand.useVanillaItem=true, wand.markerItemId={}）",
                    BuiltInRegistries.ITEM.getKey(item), id);
            return item;
        } catch (Throwable t) {
            LOG.warn("解析 wand.markerItemId=\"{}\" 出错（{}），本次只用材料标记签", id, t.getClass().getSimpleName());
            return null;
        }
    }

    /** 配置换了之后强制重解析（诊断命令用，正常流程靠 {@link #cachedKey} 比对自动自愈）。 */
    static void invalidateCache() {
        cachedKey = null;
        cachedItem = null;
    }
}
