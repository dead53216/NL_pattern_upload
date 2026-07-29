package com.patternupload.net;

import com.patternupload.common.ModConstants;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import com.gtocore.integration.ae.hooks.IExtendedPatternContainer;
import com.gtocore.integration.ae.wireless.WirelessMachine;
import com.gtocore.common.machine.tesseract.IMultiTesseract;
import com.gtocore.common.machine.tesseract.TesseractMachine;
import com.gtocore.common.saved.WirelessNetworkSavedData;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionHost;
import appeng.api.stacks.AEKey;
import appeng.me.helpers.IGridConnectedBlockEntity;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.parts.storagebus.StorageBusPart;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 目的地座標＋建議機器同步（純本 mod 自建封包，雙端註冊）。
 * <p>
 * GTOCore 送回客戶端的目的地清單只有 name/icon/full/index，同名供應器（如接口貼子網）分不出實體，
 * 且接口類供應器判不出對應機器。座標與機器只算得出於**伺服端**：GTOCore 選單 mixin 私有欄位
 * {@code gto$currentContainers}（{@code List<IExtendedPatternContainer>}，順序＝送客戶端的 index）。這裡：
 * <ol>
 *   <li>客戶端劫持到清單後發 {@link RequestC2S}（帶 windowId + gen 世代號）。</li>
 *   <li>伺服端逐個容器：反射取 {@code IPPPC.gto$getBlockPos()}＋維度（持久身分）；
 *       並嘗試解析「建議機器」——供應器推送面是 ME 接口時，沿接口子網找存儲總線、總線貼的機器
 *       ({@code busPos.relative(side)})、取其配方類型（唯一一台才建議，歧義不猜）。</li>
 *   <li>照 index 回 {@link ReplyS2C}；客戶端以 gen 過濾過期回覆。</li>
 * </ol>
 * 伺服端沒裝本 mod／反射或解析失敗 → 客戶端收不到 → 座標退名稱鍵、建議留空（皆為舊行為）。
 */
public final class Network {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ModConstants.MOD_ID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private static Field containersField;
    private static boolean reflectBroken = false;
    private static Field patternStackField;
    private static boolean patternStackBroken = false;

    /** 跨無線連接機 BFS 掃描的 grid 上限（防環／防爆走；正常子網拓撲遠低於此）。 */
    private static final int MAX_SCAN_GRIDS = 64;

    private Network() {}

    public static void init() {
        int id = 0;
        CHANNEL.registerMessage(id++, RequestC2S.class, RequestC2S::encode, RequestC2S::decode,
                Network::handleRequest, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, ReplyS2C.class, ReplyS2C::encode, ReplyS2C::decode,
                Network::handleReply, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** 客戶端：為目前這批目的地（windowId）請求座標＋建議機器，gen 為世代號（過濾過期回覆用）。 */
    public static void requestPositions(int windowId, int gen) {
        CHANNEL.sendToServer(new RequestC2S(windowId, gen));
    }

    // ----------------------------------------------------------------- 封包

    /** C2S：請求目前開啟選單（windowId）的目的地座標＋建議機器。 */
    public record RequestC2S(int windowId, int gen) {
        static void encode(RequestC2S m, FriendlyByteBuf b) {
            b.writeVarInt(m.windowId);
            b.writeVarInt(m.gen);
        }

        static RequestC2S decode(FriendlyByteBuf b) {
            return new RequestC2S(b.readVarInt(), b.readVarInt());
        }
    }

    /**
     * S2C：照 index 對齊的座標與建議機器。第 i 筆：
     * {@code dims[i]==null} 表無座標（該容器非方塊型），否則 {@code GlobalPos(dims[i], BlockPos.of(packed[i]))}；
     * {@code suggest[i]} 為建議機器的配方類型 registry id 字串（空字串＝無建議／歧義／已可直判）；
     * {@code free[i]} 為該供應器樣板槽剩餘空格數（-1＝未知）；
     * {@code hasRecipe[i]} 為該供應器已有「與本次編碼相同主產物」的樣板（GTO 上傳去重的同款判定）。
     * <p>
     * {@code free}（1.15.0）與 {@code hasRecipe}（1.16.0）是**尾綴欄位**（協定號不變）：encode 依序寫在
     * 原有欄位之後，decode 逐段以 {@code isReadable()} 偵測——舊伺服端沒寫 → 該段取預設（-1／false）；
     * 舊客戶端不讀 → 剩餘 bytes 被丟棄無害。兩側版本不齊皆退預設值，不斷線、不炸包。
     */
    public record ReplyS2C(int gen, long[] packed, ResourceLocation[] dims, String[] suggest, int[] free,
                           boolean[] hasRecipe) {
        static void encode(ReplyS2C m, FriendlyByteBuf b) {
            b.writeVarInt(m.gen);
            b.writeVarInt(m.packed.length);
            for (int i = 0; i < m.packed.length; i++) {
                if (m.dims[i] == null) {
                    b.writeBoolean(false);
                } else {
                    b.writeBoolean(true);
                    b.writeResourceLocation(m.dims[i]);
                    b.writeLong(m.packed[i]);
                }
                b.writeUtf(m.suggest[i] == null ? "" : m.suggest[i]);
            }
            for (int i = 0; i < m.packed.length; i++) {
                b.writeVarInt(m.free[i] + 1); // 偏移 1：-1（未知）也走單 byte varint
            }
            for (int i = 0; i < m.packed.length; i++) {
                b.writeBoolean(m.hasRecipe[i]);
            }
        }

        static ReplyS2C decode(FriendlyByteBuf b) {
            int gen = b.readVarInt();
            int n = b.readVarInt();
            long[] packed = new long[n];
            ResourceLocation[] dims = new ResourceLocation[n];
            String[] suggest = new String[n];
            for (int i = 0; i < n; i++) {
                if (b.readBoolean()) {
                    dims[i] = b.readResourceLocation();
                    packed[i] = b.readLong();
                }
                suggest[i] = b.readUtf();
            }
            int[] free = new int[n];
            if (b.isReadable()) {
                for (int i = 0; i < n; i++) {
                    free[i] = b.readVarInt() - 1;
                }
            } else {
                java.util.Arrays.fill(free, -1); // 舊伺服端：無尾綴
            }
            boolean[] hasRecipe = new boolean[n]; // 預設 false
            if (b.isReadable()) {
                for (int i = 0; i < n; i++) {
                    hasRecipe[i] = b.readBoolean();
                }
            }
            return new ReplyS2C(gen, packed, dims, suggest, free, hasRecipe);
        }
    }

    // --------------------------------------------------------------- 處理器

    private static void handleRequest(RequestC2S msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        var ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            AbstractContainerMenu menu = player.containerMenu;
            if (menu == null || menu.containerId != msg.windowId()) {
                return;
            }
            if (!(menu instanceof PatternEncodingTermMenu)) {
                return;
            }
            List<?> containers = readContainers(menu);
            if (containers == null) {
                return;
            }
            int n = containers.size();
            long[] packed = new long[n];
            ResourceLocation[] dims = new ResourceLocation[n];
            String[] suggest = new String[n];
            int[] free = new int[n];
            boolean[] hasRecipe = new boolean[n];
            // 本次編碼樣板的主產物（GTO 上傳去重同款判定：mixin 暫存 gto$patternStack → decode → primaryOutput）
            AEKey primaryOut = primaryOutputOfEncoding(menu, player.level());
            // request 範圍內以子網 grid 為鍵快取建議機器：多個供應器橋接同一子網時只掃一次
            //（grid 拓撲在單一同步 runnable 內不變 → 恆等）
            java.util.IdentityHashMap<IGrid, String> gridCache = new java.util.IdentityHashMap<>();
            for (int i = 0; i < n; i++) {
                Object o = containers.get(i);
                suggest[i] = "";
                free[i] = -1;
                if (o instanceof IExtendedPatternContainer.IPPPC ippc) {
                    Level level = ippc.gto$getLevel();
                    BlockPos pos = ippc.gto$getBlockPos();
                    if (level != null && pos != null) {
                        dims[i] = level.dimension().location();
                        packed[i] = pos.asLong();
                    }
                    suggest[i] = resolveSuggestedMachine(ippc, gridCache);
                    free[i] = countFreePatternSlots(ippc);
                    hasRecipe[i] = primaryOut != null && containsPrimaryOutput(ippc, primaryOut, player.level());
                }
            }
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new ReplyS2C(msg.gen(), packed, dims, suggest, free, hasRecipe));
        });
        ctx.setPacketHandled(true);
    }

    private static void handleReply(ReplyS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        var ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.patternupload.client.PatternUploadClient.receiveDestPositions(msg)));
        ctx.setPacketHandled(true);
    }

    /**
     * 本次編碼樣板的主產物 AEKey；取不到（沒編碼／反射失敗／解不開）回 null（hasRecipe 全 false）。
     * 樣板本體來自 GTOCore mixin 暫存欄位 {@code gto$patternStack}（編碼請求時寫入，與
     * {@code gto$currentContainers} 同批），解碼走 AE2 公開 API {@link PatternDetailsHelper#decodePattern}。
     */
    @Nullable
    private static AEKey primaryOutputOfEncoding(AbstractContainerMenu menu, Level level) {
        try {
            ItemStack pattern = readPatternStack(menu);
            if (pattern == null || pattern.isEmpty()) {
                return null;
            }
            var details = PatternDetailsHelper.decodePattern(pattern, level);
            if (details == null || details.getPrimaryOutput() == null) {
                return null;
            }
            return details.getPrimaryOutput().what();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 供應器是否已有「主產物相同」的樣板——與 GTOCore 上傳去重（{@code gto$containsPrimaryOutput}）同款判定：
     * 逐張 decode 供應器樣板庫存、比主產物 AEKey。**不比 NBT**：GTOCore encode hook 會塞殘留 {@code recipe}
     * 標籤，NBT 等值不可靠；主產物比對即 GTO 實際的忽略依據。
     */
    private static boolean containsPrimaryOutput(IExtendedPatternContainer.IPPPC ippc, AEKey key, Level level) {
        try {
            var inv = ippc.getTerminalPatternInventory();
            if (inv == null) {
                return false;
            }
            for (ItemStack st : inv) {
                if (st.isEmpty()) {
                    continue;
                }
                var details = PatternDetailsHelper.decodePattern(st, level);
                if (details != null && details.getPrimaryOutput() != null
                        && key.equals(details.getPrimaryOutput().what())) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // 解碼失敗 → 當沒有（退舊行為）
        }
        return false;
    }

    /** 反射 GTOCore mixin 欄位 {@code gto$patternStack}（GTO 自有欄位名不經 SRG remap，同 gto$currentContainers）。 */
    @Nullable
    private static ItemStack readPatternStack(AbstractContainerMenu menu) {
        if (patternStackBroken) {
            return null;
        }
        try {
            if (patternStackField == null) {
                Field f = null;
                Class<?> cls = menu.getClass();
                while (cls != null && f == null) {
                    try {
                        f = cls.getDeclaredField("gto$patternStack");
                    } catch (NoSuchFieldException e) {
                        cls = cls.getSuperclass();
                    }
                }
                if (f == null) {
                    patternStackBroken = true;
                    LOGGER.warn("[pattern_upload] 找不到 gto$patternStack 欄位，「已有該配方」判定停用");
                    return null;
                }
                f.setAccessible(true);
                patternStackField = f;
            }
            return patternStackField.get(menu) instanceof ItemStack st ? st : null;
        } catch (Throwable t) {
            patternStackBroken = true;
            return null;
        }
    }

    /**
     * 供應器樣板槽剩餘空格數；取不到（inventory null／例外）回 -1（客戶端不顯示）。
     * {@code IPPPC extends IExtendedPatternContainer extends PatternContainer} → 直接呼叫 AE2
     * {@code getTerminalPatternInventory()}（方法呼叫經 Forge remap，安全；GTOCore 的 full 布林同源，
     * 這裡只是算出確切格數）。
     */
    private static int countFreePatternSlots(IExtendedPatternContainer.IPPPC ippc) {
        try {
            var inv = ippc.getTerminalPatternInventory();
            if (inv == null) {
                return -1;
            }
            int freeCount = 0;
            for (int i = 0; i < inv.size(); i++) {
                if (inv.getStackInSlot(i).isEmpty()) {
                    freeCount++;
                }
            }
            return freeCount;
        } catch (Throwable t) {
            return -1;
        }
    }

    // --------------------------------------------------- 建議機器（接口→子網→存儲總線）

    /**
     * 解析供應器對應的機器配方類型，回 registry id 字串（無則 ""）。
     * <p>
     * 拓撲（使用者固定擺法）：主網樣板供應器 → 推送面貼 ME 接口 → 接口子網上有存儲總線 → 總線貼機器。
     * 直接貼機器者（相鄰即 {@link MetaMachineBlockEntity}）**也回報**其配方類型：正常情況 GTOCore 標籤已判、
     * 客戶端不會用到，但供應器被改名（自訂名不以 {@code +} 開頭）後 GTOCore 走 AE2 原生群組
     *（自訂名＋供應器自身 icon），機器資訊全失——此時只剩這條建議能判（客戶端以 icon 反查失敗為門檻取用）。
     * 接口子網掃到**唯一**一台有配方類型的機器才建議；0 台或多台（歧義）皆回 ""。任何例外 → ""（退舊行為）。
     * 子網若以 me無線連接機橋到遠端子網，掃描會跨橋一併涵蓋（見 {@link #scanSubnetForMachine}）。
     * 貼「超立方體發生器」（無配方邏輯的代理機器）→ 追其綁定目標判定（見 {@link #tesseractRecipeType}）。
     */
    private static String resolveSuggestedMachine(IExtendedPatternContainer.IPPPC ippc,
                                                  java.util.IdentityHashMap<IGrid, String> gridCache) {
        try {
            BlockEntity adj = IExtendedPatternContainer.getPushBlockEntity(ippc);
            if (adj == null) {
                return "";
            }
            if (adj instanceof MetaMachineBlockEntity mmbe) {
                GTRecipeType rt = recipeTypeOf(adj);
                if (rt == null) {
                    rt = tesseractRecipeType(mmbe.getMetaMachine()); // 超立方體發生器：追綁定目標
                }
                return rt == null ? "" : rt.registryName.toString(); // 直接貼機器：即時回報（改名後唯一判定來源）
            }
            IGrid grid = gridOf(adj);
            if (grid == null) {
                return "";
            }
            String cached = gridCache.get(grid);
            if (cached != null) {
                return cached; // 同一子網已掃過（含解析為 "" 的情形）
            }
            String result = scanSubnetForMachine(grid);
            gridCache.put(grid, result);
            return result;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 掃子網上所有存儲總線、算各自貼的機器配方類型；唯一一台回其 registry id，0／多台（歧義）回 ""。
     * <p>
     * **本地優先**：先只掃「接口自己的子網」（原 1.11.x 行為）——本地有機器就以本地為準
     *（單一→建議、多台→歧義），**不跨無線橋**。只有本地**完全沒有**機器時，才沿 me無線連接機 BFS
     * 找遠端子網（GTO 無線連接不合併 AE grid、{@link IGrid#getActiveMachines} 掃不過橋；{@code visited}
     * 去重、{@link #MAX_SCAN_GRIDS} 封頂防環）。
     * <p>
     * 為何本地優先：網路若全走無線橋，從任一接口子網 BFS 會撈到整網各子網的機器 → 幾乎必然多機型歧義 → 回 ""
     *（本地明明有機器卻判不出）。本地優先確保「本地能判者一律照舊」，跨橋只當本地無機器時的補救、零回歸。
     */
    private static String scanSubnetForMachine(IGrid startGrid) {
        Set<GTRecipeType> found = new HashSet<>();
        // 本地優先：接口自己的子網
        if (collectStorageBusMachines(startGrid, found)) {
            return ""; // 本地就多台 → 歧義
        }
        if (found.size() == 1) {
            return found.iterator().next().registryName.toString(); // 本地唯一 → 直接用，不跨橋
        }
        // 本地無機器 → 沿無線橋找遠端子網（fallback）
        Set<IGrid> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<IGrid> queue = new ArrayDeque<>();
        visited.add(startGrid);
        enqueueWirelessPeers(startGrid, visited, queue);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < MAX_SCAN_GRIDS) {
            IGrid grid = queue.poll();
            if (collectStorageBusMachines(grid, found)) {
                return "";
            }
            enqueueWirelessPeers(grid, visited, queue);
        }
        return found.size() == 1 ? found.iterator().next().registryName.toString() : "";
    }

    /** 掃單一 grid 的存儲總線、把貼的機器配方類型加進 found；本趟累計 >1（歧義）回 true。 */
    private static boolean collectStorageBusMachines(IGrid grid, Set<GTRecipeType> found) {
        for (var machineClass : grid.getMachineClasses()) {
            if (!StorageBusPart.class.isAssignableFrom(machineClass)) {
                continue;
            }
            for (Object m : grid.getActiveMachines(machineClass)) {
                if (!(m instanceof StorageBusPart sb)) {
                    continue;
                }
                BlockEntity busHost = sb.getHost().getBlockEntity();
                if (busHost == null || busHost.getLevel() == null) {
                    continue;
                }
                BlockPos target = busHost.getBlockPos().relative(sb.getSide());
                GTRecipeType rt = recipeTypeOf(busHost.getLevel().getBlockEntity(target));
                if (rt != null) {
                    found.add(rt);
                    if (found.size() > 1) {
                        return true; // 歧義
                    }
                }
            }
        }
        return false;
    }

    /** 找 grid 上的無線連接機、把它橋接到的遠端子網（未 visited 者）排入 queue；任何失敗靜默跳過（不吞掉已找到的本地結果）。 */
    private static void enqueueWirelessPeers(IGrid grid, Set<IGrid> visited, Deque<IGrid> queue) {
        try {
            for (IGridNode node : grid.getNodes()) {
                Object connector = asWirelessConnector(node.getOwner());
                if (connector == null) {
                    continue;
                }
                for (IGrid peer : wirelessPeerGrids(connector)) {
                    if (peer != null && visited.add(peer)) {
                        queue.add(peer);
                    }
                }
            }
        } catch (Throwable t) {
            // 枚舉節點／反射失敗 → 不跨橋
        }
    }

    /**
     * grid node 的 owner 若是無線連接機（owner 直接是，或是其 MetaMachine）回該物件（以 {@link Object} 持有），否則 null。
     * <p>
     * 全程不把值靜態定型成 {@code WirelessMachine}：它繼承 gtmthings 的 {@code IBindable}（gtocore JiJ 內嵌、不在編譯
     * classpath），一旦 javac 需在該型別上判子型別或解成員就會強制載入 {@code IBindable} 而編不過。故僅以
     * {@code Object instanceof WirelessMachine}（運算元為 {@code Object}、判定為 trivial）作辨識，成員存取一律走反射／降型呼叫。
     */
    @Nullable
    private static Object asWirelessConnector(Object owner) {
        if (owner instanceof WirelessMachine) {
            return owner;
        }
        if (owner instanceof MetaMachineBlockEntity be) {
            Object mm = be.getMetaMachine();
            if (mm instanceof WirelessMachine) {
                return mm;
            }
        }
        return null;
    }

    /**
     * 無線連接機所在 GTO 無線網路的其他節點（配對端）各自的 AE 子網 grid。
     * <p>
     * GTO 無線層的成員存取全走**反射**：{@code WirelessMachine} 繼承 gtmthings {@code IBindable}、
     * {@code getNetworkPool()} 回 fastcollection 型別，兩者皆 gtocore JiJ 內嵌、不在編譯 classpath，
     * 以其型別直呼會編不過。GTO 自有方法名不經 Forge SRG remap（shipped jar 即真名）→ 字串反射在正式包穩定
     *（同本檔既有 {@code gto$currentContainers}）。AE2 側（{@code getMainNode/getNode/getGrid}）走直呼、由 Forge remap。
     * 任何失敗（無網路 id／SavedData 未初始化／API 變動）回空清單＝不跨橋（退舊行為，零回歸風險）。
     */
    private static List<IGrid> wirelessPeerGrids(Object self) {
        List<IGrid> out = new ArrayList<>();
        try {
            Object netIdObj = self.getClass().getMethod("getConnectedNetworkId").invoke(self);
            if (!(netIdObj instanceof String netId) || netId.isEmpty()) {
                return out;
            }
            Object savedData = WirelessNetworkSavedData.getINSTANCE();
            if (savedData == null) {
                return out;
            }
            Object pool = savedData.getClass().getMethod("getNetworkPool").invoke(savedData);
            if (pool == null) {
                return out;
            }
            Object net = pool.getClass().getMethod("get", Object.class).invoke(pool, netId);
            if (net == null) {
                return out;
            }
            addPeerGrids(out, net.getClass().getMethod("getInputNodes").invoke(net), self);
            addPeerGrids(out, net.getClass().getMethod("getOutputNodes").invoke(net), self);
        } catch (Throwable t) {
            // 反射失敗／API 變動 → 不跨橋
        }
        return out;
    }

    /** 把一組無線節點（反射得到的 {@code ReferenceOpenHashSet<WirelessMachine>}，以 {@link Iterable} 持有）除自己外各自的 grid 收進 out。 */
    private static void addPeerGrids(List<IGrid> out, Object nodesObj, Object self) {
        if (!(nodesObj instanceof Iterable<?> nodes)) {
            return;
        }
        for (Object peer : nodes) {
            if (peer == null || peer == self) {
                continue;
            }
            IGrid g = gridOfWireless(peer);
            if (g != null) {
                out.add(g);
            }
        }
    }

    /** 無線連接機自身的 AE 子網 grid（其 mainNode 所在網路）；直呼 AE2 API（SRG-safe），未上線／無節點回 null。 */
    @Nullable
    private static IGrid gridOfWireless(Object connector) {
        try {
            if (!(connector instanceof IGridConnectedBlockEntity gc)) {
                return null;
            }
            IManagedGridNode mn = gc.getMainNode();
            if (mn == null) {
                return null;
            }
            IGridNode node = mn.getNode();
            return node == null ? null : node.getGrid();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 從方塊實體取其所在 AE 網路（子網）；接口 BE 走 IActionHost，退 IInWorldGridNodeHost。 */
    @Nullable
    private static IGrid gridOf(BlockEntity be) {
        if (be instanceof IActionHost ah) {
            IGridNode node = ah.getActionableNode();
            if (node != null) {
                return node.getGrid();
            }
        }
        if (be instanceof IInWorldGridNodeHost host) {
            for (Direction d : Direction.values()) {
                IGridNode node = host.getGridNode(d);
                if (node != null) {
                    return node.getGrid();
                }
            }
            IGridNode node = host.getGridNode(null);
            if (node != null) {
                return node.getGrid();
            }
        }
        return null;
    }

    /**
     * 供應器貼「超立方體發生器」（GTOCore 代理方塊：綁定卡放機器內部、物品/流體操作轉發到被綁方塊）時，
     * 追其綁定目標解析配方類型。tesseract 本身無配方邏輯（recipeTypes = null）→ GTOCore 標籤顯示 tesseract
     * 自身 icon、客戶端 icon 反查必落空 → 這裡的建議是唯一判定來源（{@code usableSuggestionFor} 門檻自動放行）。
     * <ul>
     *   <li>進階／定向（{@link IMultiTesseract}）：迭代 {@code getBlockEntity(i)}（定向版 GlobalPos 跨維度亦涵蓋）。</li>
     *   <li>基礎版（{@link TesseractMachine}）：單一公開欄位 {@code pos}。</li>
     * </ul>
     * **唯一**機型才回（綁多台同型機器＝常見擺法，照判）；多機型歧義／全非機器 → null。
     * 綁定目標又是 tesseract → 不遞迴（recipeTypeOf 判 null 跳過）；目標 chunk 未載入 → 該格 null 跳過。
     */
    @Nullable
    private static GTRecipeType tesseractRecipeType(MetaMachine mm) {
        if (mm instanceof IMultiTesseract multi) {
            Set<GTRecipeType> found = new HashSet<>();
            int total = multi.getTotalBlockEntities();
            for (int i = 0; i < total; i++) {
                GTRecipeType rt = recipeTypeOf(multi.getBlockEntity(i));
                if (rt != null) {
                    found.add(rt);
                    if (found.size() > 1) {
                        return null; // 多機型歧義
                    }
                }
            }
            return found.size() == 1 ? found.iterator().next() : null;
        }
        if (mm instanceof TesseractMachine tm && tm.pos != null && tm.getLevel() != null) {
            return recipeTypeOf(tm.getLevel().getBlockEntity(tm.pos));
        }
        return null;
    }

    /** 方塊實體是 GTCEu 機器且有配方邏輯時回其配方類型（多方塊取控制器），否則 null。 */
    @Nullable
    private static GTRecipeType recipeTypeOf(@Nullable BlockEntity be) {
        if (!(be instanceof MetaMachineBlockEntity mmbe)) {
            return null;
        }
        MetaMachine mm = mmbe.getMetaMachine();
        if (mm instanceof IMultiPart part) {
            return part.getController() instanceof IRecipeLogicMachine rlm ? rlm.getRecipeType() : null;
        }
        if (mm instanceof IRecipeLogicMachine rlm) {
            return rlm.getRecipeType();
        }
        return null;
    }

    /**
     * 反射伺服端 {@code PatternEncodingTermMenu} 的 GTOCore mixin 私有欄位 {@code gto$currentContainers}。
     * 沿類階層找欄位（防子類）；任一次失敗即停用（回 null，客戶端退名稱鍵、無建議）。
     */
    private static List<?> readContainers(AbstractContainerMenu menu) {
        if (reflectBroken) {
            return null;
        }
        try {
            if (containersField == null) {
                Field f = null;
                Class<?> cls = menu.getClass();
                while (cls != null && f == null) {
                    try {
                        f = cls.getDeclaredField("gto$currentContainers");
                    } catch (NoSuchFieldException e) {
                        cls = cls.getSuperclass();
                    }
                }
                if (f == null) {
                    reflectBroken = true;
                    LOGGER.warn("[pattern_upload] 找不到 gto$currentContainers 欄位，座標／建議停用（退名稱鍵）");
                    return null;
                }
                f.setAccessible(true);
                containersField = f;
            }
            Object v = containersField.get(menu);
            return v instanceof List<?> list ? list : null;
        } catch (Throwable t) {
            reflectBroken = true;
            LOGGER.error("[pattern_upload] 反射 gto$currentContainers 失敗，座標／建議停用", t);
            return null;
        }
    }
}
