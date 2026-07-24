# Pattern Upload (NL_pattern_upload)

GTOCore 樣板編碼終端「編碼並發送（上傳按鈕右鍵）」的自製目的地介面：
顯示樣板對應機器、可對「供應器」手動指定機器（接口貼子網用）、指定吻合者浮頂並持久化。

## 功能

- 攔截 GTOCore 送回客戶端的目的地清單（GTOCore 原列表框不再顯示）。
- **處理樣板直傳**：收集所有明確匹配（tier 0 手動指定吻合 / tier 1 機器類型吻合，含名稱最長機器名）。
  **剛好一個** → 直傳＋聊天欄提示；**多個** → 開面板讓玩家自選（不亂猜）；**零個** → 開面板。
- **中鍵上傳鈕＝強制開面板**：中鍵點 GTOCore 上傳（編碼）鈕 → 呼叫 `gtolib$sendEncodeRequest()` 要清單，
  但設 `forcePanel` 旗標讓這批**跳過所有自動直傳**（含合成類與單一匹配），一律開面板讓玩家自選。
  攔在 `MouseButtonPressed.Pre`（HIGHEST），以 `gto$getEncodeButton()` 邊界判界；旗標開面板即清、關終端也清。
- **DESTINATIONS 模式**：列出目的地樣板供應器（icon + 名稱 + 滿槽標記），含搜尋欄（支援 JECh 拼音）與滾輪捲動；點列即發送樣板（滿槽列不可點）。
- **右鍵多選、左鍵批次上傳**：DESTINATIONS 模式**右鍵**點列 → 綠框高亮切換加入/移除多選（滿槽列不選）；**左鍵**上傳時：**有多選** → 對所有已選目的地各送一次樣板（不論點在哪列）；**無多選** → 照舊只傳點擊列。
  批次以 `destinations`（伺服端順序）迭代逐一 `gtolib$sendPattern`，涵蓋被搜尋過濾掉的已選列並跳過滿槽；每次伺服端自 ME 網路抽一張空白樣板寫入（樣板由伺服端自扣，不需客戶端動封包）。
  面板左下顯示已選數量提示，聊天欄回報批次上傳目的地數。右鍵在面板內一律吃掉避免誤觸終端。
- **空白樣板檢查（不謊報成功）**：`gtolib$sendPattern` 每送一張目的地就從網路抽一張空白樣板；網路沒空白樣板時伺服端靜默不做事，但客戶端過去仍樂觀顯示「已上傳」。
  現以 `PatternEncodingTermMenu extends MEStorageMenu` 的客戶端網路物品表（`getClientRepo().getByKey(BLANK_PATTERN)`）先查空白樣板數：
  **0 張** → 不上傳、聊天欄提示「網路中沒有空白樣板」（面板留著待補樣板重試）；批次上傳**張數不足以涵蓋全部已選** → **完全不上傳**（all-or-nothing），提示需求/現有張數；查不到（`-1`）→ 維持原樂觀行為不擋。涵蓋單列、批次、合成直傳、單一匹配自動上傳四路徑。
- **標題列＝搜尋欄合併列**：一列內含 樣板機器 icon（最左，兼拖曳把手）＋搜尋欄（中，空白時 hint 顯示機器名／模式標題）＋關閉鈕（右）；搜尋欄無邊框、底色淡黑。省掉獨立搜尋列（面板矮一列）。
- **標題列最左 icon**：樣板對應機器 icon（自動判定自 GTOCore `@GuiSync` 欄位）；判不出顯示樣板 icon（純顯示，兼拖曳把手）。
- **點目的地列最左 icon** → **MACHINE_SELECT 模式**：對「該供應器」指定它服務的機器
  （供應器是 ME 接口貼子網時，伺服端判不出對應機器，這裡手動指定）。列出所有 GTRecipeType
  （代表機器 icon + 本地化名 + 搜尋）；首列「清除指定」。指定持久化於 `config/pattern_upload.json`。
- **本地重排**（穩定排序，層級）：0 手動指定吻合 → 1 目的地 icon 反查機器支援本類型 **或**
  供應器名稱裡最長機器名支援本類型 → 3 無法判定 → 4 指定但不吻合 → 5 滿槽；同層維持伺服端順序。
  名稱最長匹配讓「通用工廠 - 進階流體固化器」認得出子機器，又不把「電路組裝機」誤中「組裝機」。
  伺服端類型排序靠 menu 暫存 `gto$lastRecipeType`（重編舊樣板時 = null，只剩空位排序，
  且字串比對會誤中「電路組裝機」含「組裝機」）——本地用 `@GuiSync` 的 `gtocore$recipe` 補正。
- **合成類樣板**（CRAFTING/SMITHING/STONECUTTING，`menu.getMode()` 判定）：只有分子裝配室／裝配矩陣能做
  → **不開面板，右鍵直傳**：客戶端以 icon 認合成容器（`molecular_assembler`／`assembler_matrix_*`
  registry id），挑第一個未滿者上傳；全滿 → 停止動作。actionbar 顯示已上傳／全滿提示。
  伺服端 `gto$craftFirst` 對這些容器不可靠（`isCraftingContainer` 未實作，分子裝配室可能排最後）。
  **除分子裝配室/裝配矩陣外一律取消上傳**（沒有保底）：認不到 → 「網路上沒有…」、全滿 → 「全滿」。
- **座標身分（同名供應器獨立指定）**：GTOCore 送回客戶端的清單只有 name/icon/full/index，同名供應器
  （如接口貼子網）分不出實體。開面板時本 mod 發自建封包向伺服端要這批目的地的**世界座標**
  （伺服端反射 `gto$currentContainers` 逐個取 `IPPPC.gto$getBlockPos()`＋維度，照 index 回傳），
  ~1 tick 後回來刷新面板；指定即以座標為鍵（`pos:<dim>#<packedLong>`）落盤 → **每台機器各自獨立**，同名也分得開。
  伺服端沒裝本 mod／收不到座標 → 自動退回名稱鍵（舊行為）。
- **建議機器（接口→存儲總線自動解析）**：接口類供應器 GTOCore 判不出機器。伺服端沿固定拓撲解析——
  供應器推送面貼 ME 接口 → 接口子網上的**存儲總線** → 總線貼的機器（`busPos.relative(side)`）→ 取其配方類型
  （子網掃到**唯一**一台才建議，0／多台歧義不猜；直接貼機器者 GTOCore 已判、不重複）。座標封包一併回傳。
  - **跨 me無線連接機**：GTO 的無線連接機**不合併** AE grid（只用自家 `WirelessNetwork` 層橋接）→ 遠端子網的
    存儲總線／機器 `IGrid.getActiveMachines` 掃不到。故解析改成「以 grid 為節點 BFS」：每個 grid 先掃自己的存儲總線，
    再找其上的無線連接機（`node.getOwner()` 是 `WirelessMachine`）、循 `connectedNetworkId` 取 `WirelessNetwork` 的
    input/output 節點、把配對端各自的子網 grid 排入佇列續掃（`visited` 去重、上限 `MAX_SCAN_GRIDS=64` 防環）。
    `WirelessNetworkSavedData.networkPool` 型別是 gtocore JiJ 內嵌的 fastcollection、不在編譯 classpath → **該一跳走反射**
    （GTO 自有類名不經 SRG remap，穩定）；任何失敗回不跨橋（退舊行為）。唯一機型才建議，跨橋後多機型仍算歧義回 ""。
  客戶端**有效機器＝手動指定 ?? 伺服端建議**：不用手點就顯示對的機器、吻合者浮頂；建議**不落盤**
  （每次伺服端即時重算，永遠正確），手動指定仍可覆寫。建議列機器名以**青色**標示、與手動指定（白）區分。
- **自動直傳決策延到座標／建議到齊再判**（`decidePending`）：劫持清單後不立即決策，先向伺服端要座標＋建議，
  收到（gen 相符）或逾時（`DECIDE_WAIT_TICKS`，伺服端沒裝本 mod 時 fallback）才判「單一匹配直傳 / 多個開面板 / 零個開面板」。
  **為何必要**：接口類（子網機器）第一幀還沒解析出機器 → 會被漏算成不匹配；若同步就判，
  「同時有直接機器＋子網同型機器」會誤判成單一匹配、直傳直接那台、不開面板讓玩家選。延後判即讓子網機器也算進 tier 0。
  代價：處理樣板上傳前多等 ~1–2 tick（伺服端有本 mod 時；沒有則等逾時後照舊名稱鍵決策）。合成類樣板不走此路、仍同步。
- 供應器被指定後：icon 換成指定的機器，**兩行顯示**——第一行機器名，第二行括號放 GTOCore 給的清單標籤
  （沒改名照放；供應器改名後 GTOCore 自動給自訂名 → 括號顯示自訂名，可分辨同名供應器）。
- **通用工廠換行**：名稱為「通用工廠 - 子機器」格式者拆兩行（第一行「通用工廠」、第二行子機器名）；
  未指定列直接兩行顯示，已指定列括號內只留子機器名（`splitFactoryName`，前綴後須接分隔符才拆）。
  故列高 `ROW_H` 由 16 提為 18 以容兩行。
- 浮動面板：標題列可拖曳、左上角把手可縮放（寬/列數），位置與尺寸持久化；預設在合成欄右邊。
- **置頂**：`Render.Post` 監聽器設 `EventPriority.LOWEST`（最後畫）→ 面板蓋過 EMI/JEI 右側物品；
  點擊 `MouseButtonPressed.Pre` 設 `HIGHEST`（最先吃）→ 面板先接管點擊，物品瀏覽器搶不走。
- ESC：MACHINE_SELECT → 回清單 → 關 overlay → 關終端；搜尋欄聚焦時吞按鍵避免 `E` 關閉介面。
- **效能**：`currentRecipeType` 是唯一 per-frame 重活（`render` 每幀經 headerIcon＋headerTitle 觸發，
  內層掃該機器類型整張配方表）→ 以 (menu, `gtocore$recipe`, 產物槽簽章) 快取，樣板不變即零重掃。
  `rebuildRows` 單趟預算每列 posKey/有效機器/tier（decorate-sort），免 comparator 與顯示各自重呼 `machineFor`。
  伺服端 `resolveSuggestedMachine` 以子網 grid 為鍵在單次 request 內快取，共享子網不重掃。待機時全 mod 零 per-tick 工作。

## 架構（1.20.1/forge）— v1.1「零 mixin 劫持」

**完全不用 mixin**（v1.0.x 的 mixin 方案已廢棄：dev 環境 gtocore 類不可被變換、
正式包亦無法套用）。改為每幀監看 GTOCore 清單框，變可見即接管：

| 元件 | 說明 |
|---|---|
| `client/PatternUploadClient` | `ScreenEvent.Render.Pre` 輪詢 `term.gto$getPatternDestDisplay()`；`isVisible()` 一變 true → 反射抽資料 → `setVisible(false)` 藏原框 → 開 overlay。另掛 Render.Post/滑鼠/鍵盤/拖曳/Closing 事件 |
| `client/ListBoxReflector` | 反射讀 `AESearchPatternProviderListBox.allItems`（SimpleItem: index/icon/name/full；已對照 0.5.6-alpha/beta/26.7.x），失敗自動退回原介面 |
| `client/UploadOverlay` | 面板本體（純類）：兩模式清單、搜尋、hover/tooltip、捲動、標題列拖曳、右下角縮放、本地重排 |
| `client/PatternUploadConfig` | `config/pattern_upload.json` 持久化：`providerMachines`（供應器鍵→配方類型 id）＋面板位置/尺寸（panelX/Y/W/Rows）；供應器鍵優先「世界座標」（`pos:<dim>#<packedLong>`，同名獨立），無座標退「顯示名稱」（相容舊設定） |
| `net/Network` | 目的地座標＋建議機器同步（自建封包，雙端註冊）：C2S 請求（帶 windowId＋gen 世代號）→ 伺服端反射 `gto$currentContainers` 逐個取座標＋維度、並解析建議機器（接口→子網 grid→存儲總線→總線貼的機器→配方類型），照 index 回 S2C；client 以 gen 過濾過期回覆。子網掃描以 grid 為節點 BFS、跨 me無線連接機橋接的遠端子網（`WirelessNetwork` 節點循 grid 續掃，`MAX_SCAN_GRIDS` 封頂）。**唯一非純客戶端元件**（伺服端也需裝，多人才有座標／建議；單機自動雙端） |
| `client/PinyinMatch` | JECh（jecharacters）軟依賴，純反射 `Match#contains`；缺席退回子字串比對（同 NL_oreveinfilter 做法） |
| `client/RecipeTypeIcons` | `GTRegistries.MACHINES` 掃描建 GTRecipeType→代表機器 icon 快取；名稱沿用 GTOCore 慣例 `"gtceu." + registryName.getPath()` |

`/patternupload_test`：30 秒內開啟樣板編碼終端即注入假目的地，驗證整條劫持鏈。

### 關鍵資料流

```
編碼鈕右鍵（GTOCore 原有）→ 伺服端 encode + 排序 → SEND_PATTERN_DESTINATION_S2C
  → GTOCore 填清單框 + setVisible(true) → [本 mod Render.Pre 劫持] → UploadOverlay 顯示
  → [本地重排] 指定機器吻合的供應器浮頂

點目的地列       → menu.gtolib$sendPattern(destIndex)   （GTOCore 介面）
點目的地列 icon  → MACHINE_SELECT：指定該供應器的機器 → PatternUploadConfig.assign() 落盤 → 回清單重排

開面板同時       → Network 自建 C2S 要座標 → 伺服端反射 gto$currentContainers 回 S2C
  → 客戶端落地座標鍵（pos:<dim>#<packedLong>）→ rebuildRows 刷新（同名供應器獨立指定）
```

- 自動機器判定：反射讀 GTOCore menu 的 `@GuiSync` 欄位 `gtocore$recipe`（`"<type_rl>/<recipe>"`）。
  **殘留防護**：GTOCore 只在「載入既有樣板」時更新此欄位，手動填格新編碼不會清 → 會殘留上一張的配方
  （可能是**別台機器**：壓印器樣板殘留成液化機；也可能是**同機器別條配方**：組裝機樣板殘留成 disassembly）。
  故 `currentRecipeType` 不比對精確 recipe id，改看「**該類型的任一配方**是否產出編碼格產物」：
  以 `GTRecipeType.recipes`（gtceu 同步到 client 的表，**非**原版 RecipeManager，後者查不到 gtceu 配方）
  逐條比 `itemOutputs`／`fluidOutputs` 對編碼格產出物。對不上 → 回 null（面板顯示未知、不自動上傳）。
  流體產物在 FakeSlot 是包成 wrapper item，用公開 API `GenericStack.unwrapItemStack` 解出 fluid key
  （不反射 private `encodedOutputsInv`——其欄位名正式包 reobf 後會 SRG 失配），以 `testAeKay` 比對。
- **原版燒煉等 proxy 配方後備**（`proxyRecipeTypeFor`）：`gtocore$recipe` 只在樣板帶 `GTRecipeDefinition` 時才填，
  **原版燒煉樣板（圓石→磚等）該欄位為空**；且 gtceu 電力熔爐（`FURNACE_RECIPES`）不把原版燒煉配方收進 `recipes`，
  而是以 `proxyRecipes = {minecraft:smelting}` 委派原版 `RecipeManager`。兩者疊加使燒煉樣板一律判成「未知機器」。
  故 `currentRecipeType` 在 gtocore$recipe 判不出（空／殘留對不上）時後備：查客戶端 `RecipeManager` 中各 GTRecipeType
  proxy 的原版配方（`GTRecipeType.getProxyRecipes()`，`RecipeTypeIcons.proxyOwners()` 反查表）——編碼格產物命中某 proxy
  配方產出，即回其機器類型（熔爐＝`FURNACE_RECIPES`）。**唯一命中一種才回傳**，多種能做則歧義不猜、開面板。
- 供應器指定純客戶端持久化（不寫樣板 NBT、不動伺服端）；v1.1 的「寫樣板 NBT 指定機器」路徑已移除。

## 建置注意

- **Java 21 toolchain**（非一般 1.20.1 mod 的 17）：gto 全家 jar 為 Java 21 class files，整合包也以 Java 21 執行。
- 依賴（全 `compileOnly`，runtime 由整合包提供）自 `maven.gtodyssey.com/releases`：
  `com.gto:gtocore-forge-1.20.1`、`com.gto:gtolib-forge-1.20.1`、`com.gregtechceu.gtceu:gtceu-1.20.1-forge-1.20.1`、`appeng:appliedenergistics2-forge-1.20.1`、`com.gto:datasynclib-forge-1.20.1`（GTRegistry 基類）。
- 外加 **LowDragLib（ldlib）** 自 CurseForge（`cursemaven.com`，`curse.maven:ldlib-626676:<fileId>`，對齊 GTOCore 1.0.48）：
  `net/Network` 解析建議機器時用到 GTCEu `IMultiPart`，其型別階層含 ldlib 的 `IUIHolder`，缺它 `instanceof` 編不過。
- 該 maven 的 module metadata 已在 repo 宣告 `metadataSources { mavenPom(); artifact() }` 忽略（其 `.module` 綁 JVM21 屬性）。
- `mods.toml` 強制依賴 `gtocore`。
- **dev 測試注意**：`build/libs` 的 jar（含 `-slim`）全被 FG6 reobf 成 SRG，**不能**丟進 moddev dev 環境；
  dev 用 `build/classes + resources` 手打的 named jar（見 build/devjar2 流程）。

## 已知限制

- 只處理「這次編碼的那張」樣板（設計如此）。
- 基礎排序沿用 GTOCore 伺服端邏輯（空槽 > 機器吻合 > 名稱吻合）；本地只再把「被指定機器且吻合」者提到最前。
- 供應器指定：**伺服端有裝本 mod** → 以世界座標為鍵，同名供應器**各自獨立**（座標由自建封包補齊，
  GTOCore 原封包不帶座標）。**伺服端沒裝本 mod／收不到座標** → 退回名稱鍵，此時同名供應器共用同一指定
  （index 每次上傳重排、不能當持久身分），要分開得先在遊戲內幫接口改名，MACHINE_SELECT 遇同名顯示黃字警告。
- 座標／建議為非同步（劫持清單後 ~1–2 tick 才到）：處理樣板的自動直傳決策**延到其到齊再判**（見「功能」的
  `decidePending`），故接口類子網機器也能正確算進匹配、避免誤直傳。伺服端沒裝本 mod → 逾時後以名稱鍵照舊決策。
  合成類樣板不走延遲、仍同步（只認分子裝配室/裝配矩陣，與座標無關）。
- 建議機器跨 me無線連接機：GTO 無線連接**不合併** AE grid，故伺服端 BFS 跨橋掃描（見「功能」）。跨橋後若涵蓋
  **多種**機型仍算歧義、不建議（回 ""）；只在全網唯一機型時建議。GTO 無線 API 走反射，版本大改 API 名時自動退回不跨橋。
