package cn.blockforge.generated.schematicrestock;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 本地化小工具（键值全在 {@code assets/schematic_restock/lang/zh_cn.json|en_us.json}）。
 *
 * <p>约定：<b>客户端自己能拼的话一律走 {@code Component.translatable}</b>——HUD、动作栏提示、
 * 物品悬浮说明、两端命令的反馈；服务端自己拼的诊断自由文本（接管收尾 note、GhostDebug 日志）
 * 保持中文原文，它们走的是网络字符串载荷，要翻译得改协议，不值当。</p>
 */
final class Txt {

    private Txt() {}

    /**
     * 取一条可翻译文本（聊天栏 / 动作栏 / HUD 用，交给原版按客户端语言渲染）。
     *
     * <p>返回 {@link MutableComponent} 而不是 {@link Component}：1.21.1 的
     * {@code withStyle(...)} 只挂在 {@code MutableComponent} 上，
     * 调用点全都得接着 {@code .withStyle(颜色)}，接口类型会把编译卡死。</p>
     */
    static MutableComponent c(String key, Object... args) {
        return Component.translatable(key, args);
    }

    /** 就地渲染成字符串（HUD 的 drawString 只吃字符串，客户端语言当场解析）。 */
    static String s(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
