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
import org.lwjgl.glfw.GLFW;

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

    /**
     * 中鍵點上傳鈕時設 true：下一批目的地清單回來時強制開面板、跳過自動直傳（讓玩家自選）。
     * 由 onRenderPre 消費（開面板即清）；面板關閉時也清，避免殘留影響下次右鍵自動上傳。
     */
    private static boolean forcePanel = false;

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
            // 收集編碼格所有非空產出物的 AEKey（物品或流體）。
            // 流體產物在 FakeSlot 是包成 wrapper item，用公開 API GenericStack.unwrapItemStack 解出
            //（避免反射 private encodedOutputsInv——其欄位名在正式包會被 reobf 成 SRG）。
            java.util.List<appeng.api.stacks.AEKey> keys = new java.util.ArrayList<>();
            for (var slot : petm.getProcessingOutputSlots()) {
                net.minecraft.world.item.ItemStack st = slot.getItem();
                if (st.isEmpty()) {
                    continue;
                }
                appeng.api.stacks.GenericStack gs = appeng.api.stacks.GenericStack.unwrapItemStack(st);
                keys.add(gs != null ? gs.what() : appeng.api.stacks.AEItemKey.of(st));
            }
            if (keys.isEmpty()) {
                return false;
            }
            // 該類型任一配方的 item/fluid Outputs 命中任一產出物即算匹配
            for (var def : type.recipes.values()) {
                for (var key : keys) {
                    if (key instanceof appeng.api.stacks.AEItemKey ik) {
                        for (var content : def.itemOutputs) {
                            if (content.inner.testAeKay(ik)) {
                                return true;
                            }
                        }
                    } else if (key instanceof appeng.api.stacks.AEFluidKey fk) {
                        for (var content : def.fluidOutputs) {
                            if (content.inner.testAeKay(fk)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return false; // 任何 API 異動/查不到 → 保守視為不匹配（改開面板讓玩家自選）
        }
    }

    /**
     * 客戶端可見的網路空白樣板數量（gtolib$sendPattern 每送一張目的地就從網路抽一張空白樣板）。
     * PatternEncodingTermMenu extends MEStorageMenu → 有同步到客戶端的網路物品表（ClientRepo）。
     * 回傳：≥0 = 網路空白樣板數；-1 = 查不到（保守維持原樂觀行為，不擋上傳）。
     */
    static long blankPatternCount(AbstractContainerMenu menu) {
        try {
            if (!(menu instanceof appeng.menu.me.common.MEStorageMenu me)) {
                return -1;
            }
            appeng.menu.me.common.IClientRepo repo = me.getClientRepo();
            if (repo == null) {
                return -1;
            }
            var entry = repo.getByKey(appeng.api.stacks.AEItemKey.of(appeng.core.definitions.AEItems.BLANK_PATTERN));
            return entry == null ? 0 : entry.getStoredAmount();
        } catch (Throwable t) {
            return -1; // API 異動/查不到 → 回 -1，維持原行為
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
        // 中鍵手勢：這批清單強制開面板、跳過所有自動直傳（消費即清）
        boolean force = forcePanel;
        forcePanel = false;
        if (!force && isCraftMode(screen.getMenu())) {
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
            if (target != null && blankPatternCount(screen.getMenu()) == 0) {
                // 網路沒空白樣板 → 不上傳、不謊報成功
                if (player != null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "pattern_upload.no_blank"), false);
                }
                LOGGER.info("[pattern_upload] craft: no blank pattern in network, aborted");
            } else if (target != null) {
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
        // 處理樣板：收集所有「明確匹配」的目的地（tier 0 手動指定吻合 / tier 1 機器類型吻合）。
        // 剛好一個 → 直傳；多個 → 開面板讓玩家自選（不亂猜）；零個 → 開面板。
        GTRecipeType current = force ? null : currentRecipeType(screen.getMenu());
        if (current != null) {
            List<ListBoxReflector.Dest> matches = new java.util.ArrayList<>();
            for (var d : dests) {
                int tier = UploadOverlay.sortTier(d, current);
                if (tier == 0 || tier == 1) {
                    matches.add(d);
                }
            }
            if (matches.size() == 1) {
                ListBoxReflector.Dest target = matches.get(0);
                overlay = null;
                var player = Minecraft.getInstance().player;
                if (blankPatternCount(screen.getMenu()) == 0) {
                    // 網路沒空白樣板 → 不上傳、不謊報成功
                    if (player != null) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                                "pattern_upload.no_blank"), false);
                    }
                    LOGGER.info("[pattern_upload] single match but no blank pattern in network, aborted");
                    return;
                }
                ((IExtendedPatternEncodingTerm.Menu) screen.getMenu()).gtolib$sendPattern(target.index());
                if (player != null) {
                    // false = 聊天欄（actionbar 會被終端 GUI 蓋住看不到）
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "pattern_upload.sent", target.name()), false);
                }
                LOGGER.info("[pattern_upload] pattern sent directly to '{}' (single type match)", target.name().getString());
                return;
            }
            if (matches.size() > 1) {
                LOGGER.info("[pattern_upload] {} matches → open panel for user choice", matches.size());
            }
        }
        overlay = new UploadOverlay(screen, dests);
        LOGGER.info("[pattern_upload] hijacked GTOCore destination list: {} entries", dests.size());
    }

    // ------------------------------------------------------------- 事件疊加

    // LOWEST：Render.Post 監聽器最後執行 → 面板最後畫 → 置頂蓋過 EMI/JEI 的右側物品
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onRender(ScreenEvent.Render.Post event) {
        var o = activeOverlay(event);
        if (o != null) {
            o.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
    }

    // HIGHEST：點擊 Pre 最先執行 → 面板先吃掉點擊並取消，EMI 不會搶走
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        var o = activeOverlay(event);
        if (o != null) {
            if (o.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
                event.setCanceled(true);
            }
            return;
        }
        // 無面板時：中鍵點 GTOCore 上傳（編碼）鈕 → 要一批目的地清單但強制開面板（不自動上傳）
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE
                && event.getScreen() instanceof PatternEncodingTermScreen<?> screen
                && screen instanceof IExtendedPatternEncodingTerm term) {
            var btn = term.gto$getEncodeButton();
            if (btn != null && overEncodeButton(btn, event.getMouseX(), event.getMouseY())) {
                forcePanel = true;
                ((IExtendedPatternEncodingTerm.Menu) screen.getMenu()).gtolib$sendEncodeRequest();
                event.setCanceled(true);
                LOGGER.info("[pattern_upload] middle-click encode button → force panel (no auto-upload)");
            }
        }
    }

    /** 滑鼠是否落在上傳鈕矩形內（手動判界，不受按鈕 active 狀態影響）。 */
    private static boolean overEncodeButton(appeng.client.gui.widgets.ActionButton btn, double mx, double my) {
        return mx >= btn.getX() && mx < btn.getX() + btn.getWidth()
                && my >= btn.getY() && my < btn.getY() + btn.getHeight();
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
        // 關終端就清中鍵旗標，避免無效樣板（伺服端沒回清單）殘留到下次右鍵誤觸
        if (event.getScreen() instanceof PatternEncodingTermScreen<?>) {
            forcePanel = false;
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
