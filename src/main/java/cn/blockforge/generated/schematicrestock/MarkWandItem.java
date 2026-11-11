package cn.blockforge.generated.schematicrestock;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 材料箱标记签：
 * <ul>
 *   <li>右键容器 —— 标记 / 取消<b>材料箱</b>（补料从这里取料）。
 *       r24：这次右键在客户端与服务端<b>同时</b>取消，箱子/工作台界面绝不弹出；
 *       右键到泥土这类非容器只会得到一句中间红字，不做任何事；</li>
 *   <li>Shift+右键两下 —— 框选一片，批量标成材料箱（r24：单次上限 16 → <b>64</b>）；</li>
 *   <li>Shift+左键容器 —— 标记 / 取消<b>输出箱（溢出箱）</b>，只进不出（r7 新增）。</li>
 * </ul>
 */
public class MarkWandItem extends Item {
    public MarkWandItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        // r28：悬浮说明走本地化（zh_cn / en_us 两份键值都在 assets/schematic_restock/lang/）
        tooltip.add(Txt.c("tooltip.mark_wand.line1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Txt.c("tooltip.mark_wand.line2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Txt.c("tooltip.mark_wand.line3", SR.MAX_BOX_MARKS).withStyle(ChatFormatting.GRAY));
        tooltip.add(Txt.c("tooltip.mark_wand.line4").withStyle(ChatFormatting.GOLD));
    }
}
