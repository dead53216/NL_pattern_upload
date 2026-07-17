package com.patternupload.client;

import com.patternupload.PatternUploadMod;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import com.gtocore.integration.ae.hooks.IExtendedPatternEncodingTerm;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import appeng.client.gui.me.items.PatternEncodingTermScreen;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 零 mixin 架構：
 * 每幀（Render.Pre）檢查 GTOCore 的目的地清單框是否剛被設為可見；
 * 一可見即「劫持」——反射抽出資料、把原清單藏起來、改開本 mod 的 overlay。
 * 送出/指定機器仍走 GTOCore 既有介面方法（純呼叫，不需類變換）。
 */
@Mod.EventBusSubscriber(modid = PatternUploadMod.MOD_ID, value = Dist.CLIENT)
public final class PatternUploadClient {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    @Nullable
    private static UploadOverlay overlay;

    static {
        LOGGER.info("[pattern_upload] PatternUploadClient loaded (hijack mode, no mixin)");
    }

    private PatternUploadClient() {}

    public static void removeOverlay() {
        overlay = null;
    }

    /**
     * 非處理樣板（合成/鍛造/切石）只有分子裝配室或裝配矩陣能做，
     * 配方類型概念不適用；伺服端 gto$craftFirst 已把合成容器排前，本地不要再動。
     */
    static boolean isCraftMode(AbstractContainerMenu menu) {
        return menu instanceof appeng.menu.me.items.PatternEncodingTermMenu petm &&
                petm.getMode() != appeng.parts.encoding.EncodingMode.PROCESSING;
    }

    /**
     * 目前樣板對應的配方類型：讀選單同步的 GTOCore @GuiSync 欄位 gtocore$recipe。
     * <p>
     * GTOCore 只在「載入既有樣板」時更新此欄位，手動填格新編碼不會清 → 會殘留上一張樣板的配方
     *（例：先編液化機、再手動編壓印器樣板，欄位仍是液化機）。因此**必須驗證**編碼格的主產物
     * 確實是該配方 id 的產物之一；對不上即視為殘留 → 回 null（面板顯示未知、不自動上傳）。
     */
    @Nullable
    static GTRecipeType currentRecipeType(AbstractContainerMenu menu) {
        if (isCraftMode(menu)) {
            return null; // 殘留的 gtocore$recipe 不適用於合成類樣板
        }
        try {
            Object value = menu.getClass().getField("gtocore$recipe").get(menu);
            if (value instanceof String s && !s.isEmpty()) {
                ResourceLocation typeRl = ResourceLocation.tryParse(s.split("/")[0]);
                if (typeRl == null) {
                    return null;
                }
                GTRecipeType type = GTRegistries.RECIPE_TYPES.get(typeRl);
                if (type == null) {
                    return null;
                }
                return typeProducesPatternOutput(menu, type) ? type : null;
            }
        } catch (Throwable ignored) {
            // GTOCore 內部欄位名變動時退回「未知」，僅影響顯示 icon，不影響功能
        }
        return null;
    }

    /**
     * 判定 type 這台機器是否真能做出「編碼格的主產物」——防 gtocore$recipe 殘留。
     * <p>
     * gtocore$recipe 只在載入既有樣板時更新，殘留的可能是**別的機器**（壓印器樣板殘留成液化機）
     * 或**同機器別條配方**（組裝機樣板殘留成 disassembly），故不比對精確 recipe id，
     * 改看「該類型的<b>任一</b>配方」是否產出此樣板產物。gtceu 配方不在原版 client RecipeManager，
     * 查 {@code GTRecipeType.recipes}（gtceu 同步到 client 的表）。
     */
    private static boolean typeProducesPatternOutput(AbstractContainerMenu menu, GTRecipeType type) {
        try {
            if (!(menu instanceof appeng.menu.me.items.PatternEncodingTermMenu petm)) {
                return false;
            }
            // 先收集編碼格所有非空產出物（處理模式產出槽）
            java.util.List<net.minecraft.world.item.ItemStack> outs = new java.util.ArrayList<>();
            for (var slot : petm.getProcessingOutputSlots()) {
                net.minecraft.world.item.ItemStack st = slot.getItem();
                if (!st.isEmpty()) {
                    outs.add(st);
                }
            }
            if (outs.isEmpty()) {
                return false;
            }
            // 該類型任一配方的 itemOutputs 命中任一產出物即算匹配
            for (var def : type.recipes.values()) {
                for (var content : def.itemOutputs) {
                    for (var out : outs) {
                        if (content.inner.test(out)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return false; // 任何 API 異動/查不到 → 保守視為不匹配（改開面板讓玩家自選）
        }
    }

    @Nullable
    private static UploadOverlay activeOverlay(ScreenEvent event) {
        if (overlay != null && event.getScreen() == overlay.screen()) {
            return overlay;
        }
        return null;
    }

    // ---------------------------------------------------------------- 劫持

    @SubscribeEvent
    public static void onRenderPre(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof PatternEncodingTermScreen<?> screen)) {
            return;
        }
        if (!(screen instanceof IExtendedPatternEncodingTerm term)) {
            return;
        }
        var box = term.gto$getPatternDestDisplay();
        if (box == null || !box.isVisible()) {
            return;
        }
        // GTOCore 剛把清單設為可見（新一批目的地）→ 接管
        List<ListBoxReflector.Dest> dests = ListBoxReflector.extract(box);
        if (dests == null) {
            return; // 反射失敗：保留 GTOCore 原清單
        }
        box.setVisible(false);
        if (isCraftMode(screen.getMenu())) {
            // 合成/鍛造/切石樣板：只有分子裝配室/裝配矩陣能做。伺服端 gto$craftFirst 對這些容器
            // 不可靠（isCraftingContainer 沒實作、平手看網路迭代順序，分子裝配室可能排最後），
            // 所以客戶端自己以 icon 認合成容器：挑第一個未滿的合成容器直傳；全滿 → 停止動作。
            overlay = null;
            var player = Minecraft.getInstance().player;
            // 只允許分子裝配室/裝配矩陣；其他供應器一律不上傳（沒有保底）
            ListBoxReflector.Dest target = null;
            boolean sawCraftContainer = false;
            for (var d : dests) {
                if (RecipeTypeIcons.isCraftContainer(d.icon())) {
                    sawCraftContainer = true;
                    if (!d.full()) {
                        target = d;
                        break;
                    }
                }
            }
            if (target != null) {
                ((IExtendedPatternEncodingTerm.Menu) screen.getMenu()).gtolib$sendPattern(target.index());
                if (player != null) {
                    // false = 聊天欄（actionbar 會被終端 GUI 蓋住看不到）
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "pattern_upload.craft.sent", target.name()), false);
                }
                LOGGER.info("[pattern_upload] craft pattern sent directly to '{}'", target.name().getString());
            } else if (player != null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        sawCraftContainer ? "pattern_upload.craft.full" : "pattern_upload.craft.none"), false);
            }
            return;
        }
        // 處理樣板：有明確匹配（手動指定吻合＝tier 0 或 icon 機器類型吻合＝tier 1）→ 直傳，
        // 不再依靠排序；找不到匹配才顯示面板讓玩家自選。
        GTRecipeType current = currentRecipeType(screen.getMenu());
        if (current != null) {
            ListBoxReflector.Dest target = null;
            for (var d : dests) {
                if (UploadOverlay.sortTier(d, current) == 0) {
                    target = d;
                    break;
                }
            }
            if (target == null) {
                for (var d : dests) {
                    if (UploadOverlay.sortTier(d, current) == 1) {
                        target = d;
                        break;
                    }
                }
            }
            if (target != null) {
                overlay = null;
                ((IExtendedPatternEncodingTerm.Menu) screen.getMenu()).gtolib$sendPattern(target.index());
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    // false = 聊天欄（actionbar 會被終端 GUI 蓋住看不到）
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "pattern_upload.sent", target.name()), false);
                }
                LOGGER.info("[pattern_upload] pattern sent directly to '{}' (type match)", target.name().getString());
                return;
            }
        }
        overlay = new UploadOverlay(screen, dests);
        LOGGER.info("[pattern_upload] hijacked GTOCore destination list: {} entries", dests.size());
    }

    // ------------------------------------------------------------- 事件疊加

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        var o = activeOverlay(event);
        if (o != null) {
            o.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
    }

    @SubscribeEvent
    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        var o = activeOverlay(event);
        if (o != null && o.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        var o = activeOverlay(event);
        if (o != null && o.mouseDragged(event.getMouseX(), event.getMouseY(), event.getMouseButton(), event.getDragX(), event.getDragY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        var o = activeOverlay(event);
        if (o != null && o.mouseReleased(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(ScreenEvent.MouseScrolled.Pre event) {
        var o = activeOverlay(event);
        if (o != null && o.mouseScrolled(event.getMouseX(), event.getMouseY(), event.getScrollDelta())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        var o = activeOverlay(event);
        if (o != null && o.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        var o = activeOverlay(event);
        if (o != null && o.charTyped(event.getCodePoint(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (overlay != null && event.getScreen() == overlay.screen()) {
            overlay = null;
        }
    }

    // ---------------------------------------------------------- 測試指令

    /** 等待「樣板編碼終端開啟」後注入假資料的倒數；-1 = 停用。 */
    private static int testPending = -1;

    /**
     * 診斷指令 /patternupload_test：之後 30 秒內只要開啟樣板編碼終端，
     * 就以假資料觸發 GTOCore 的清單 → 本 mod 應立即劫持並顯示自製面板。
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(net.minecraftforge.client.event.RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                com.mojang.brigadier.builder.LiteralArgumentBuilder.<net.minecraft.commands.CommandSourceStack>literal("patternupload_test")
                        .executes(ctx -> {
                            testPending = 600; // 30 秒內開終端即觸發
                            ctx.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "[pattern_upload] 測試待命：30 秒內開啟樣板編碼終端即會顯示測試清單"));
                            return 1;
                        }));
    }

    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || testPending < 0) {
            return;
        }
        testPending--;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PatternEncodingTermScreen<?>) {
            testPending = -1;
            LOGGER.info("[pattern_upload] TEST: injecting dummy destinations via GTOCore list");
            try {
                var dummyGroup = new appeng.api.implementations.blockentities.PatternContainerGroup(
                        appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.CRAFTING_TABLE),
                        net.minecraft.network.chat.Component.literal("pattern_upload test"),
                        java.util.List.of());
                com.gtocore.client.Message.Client.patternDestinationReceived(new com.gtocore.client.Message.PatternDestination[] {
                        new com.gtocore.client.Message.PatternDestination(dummyGroup, false)
                });
            } catch (Throwable t) {
                LOGGER.error("[pattern_upload] TEST threw", t);
            }
        }
    }
}
