# Pattern Upload (NL_pattern_upload)

GTOCore 樣板編碼終端「編碼並發送（上傳按鈕右鍵）」的自製目的地介面：
顯示樣板對應機器、可對「供應器」手動指定機器（接口貼子網用）、指定吻合者浮頂並持久化。

## 功能

- 攔截 GTOCore 送回客戶端的目的地清單（GTOCore 原列表框不再顯示）。
- **處理樣板直傳**：收集所有明確匹配（tier 0 手動指定吻合 / tier 1 機器類型吻合，含名稱最長機器名）。
  **剛好一個** → 直傳＋聊天欄提示；**多個** → 開面板讓玩家自選（不亂猜）；**零個** → 開面板。
- **DESTINATIONS 模式**：列出目的地樣板供應器（icon + 名稱 + 滿槽標記），含搜尋欄（支援 JECh 拼音）與滾輪捲動；點列即發送樣板（滿槽列不可點）。
- **中鍵多選批次上傳**：DESTINATIONS 模式中鍵點列 → 綠框高亮加入多選（滿槽列不選）；對**已高亮**列**再中鍵** → 對所有已選目的地各送一次樣板。
  以 `destinations`（伺服端順序）迭代逐一 `gtolib$sendPattern`，涵蓋被搜尋過濾掉的已選列並跳過滿槽；每次伺服端自 ME 網路抽一張空白樣板寫入（樣板由伺服端自扣，不需客戶端動封包）。
  面板左下顯示已選數量提示，聊天欄回報批次上傳目的地數。中鍵在面板內一律吃掉避免誤觸終端。
- **標題列最左 icon**：樣板對應機器 icon（自動判定自 GTOCore `@GuiSync` 欄位）；判不出顯示樣板 icon（純顯示）。
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
- 供應器被指定後：icon 換成指定的機器，**兩行顯示**——第一行機器名，第二行括號放 GTOCore 給的清單標籤
  （沒改名照放；供應器改名後 GTOCore 自動給自訂名 → 括號顯示自訂名，可分辨同名供應器）。
- **通用工廠換行**：名稱為「通用工廠 - 子機器」格式者拆兩行（第一行「通用工廠」、第二行子機器名）；
  未指定列直接兩行顯示，已指定列括號內只留子機器名（`splitFactoryName`，前綴後須接分隔符才拆）。
  故列高 `ROW_H` 由 16 提為 18 以容兩行。
- 浮動面板：標題列可拖曳、左上角把手可縮放（寬/列數），位置與尺寸持久化；預設在合成欄右邊。
- **置頂**：`Render.Post` 監聽器設 `EventPriority.LOWEST`（最後畫）→ 面板蓋過 EMI/JEI 右側物品；
  點擊 `MouseButtonPressed.Pre` 設 `HIGHEST`（最先吃）→ 面板先接管點擊，物品瀏覽器搶不走。
- ESC：MACHINE_SELECT → 回清單 → 關 overlay → 關終端；搜尋欄聚焦時吞按鍵避免 `E` 關閉介面。

## 架構（1.20.1/forge）— v1.1「零 mixin 劫持」

**完全不用 mixin**（v1.0.x 的 mixin 方案已廢棄：dev 環境 gtocore 類不可被變換、
正式包亦無法套用）。改為每幀監看 GTOCore 清單框，變可見即接管：

| 元件 | 說明 |
|---|---|
| `client/PatternUploadClient` | `ScreenEvent.Render.Pre` 輪詢 `term.gto$getPatternDestDisplay()`；`isVisible()` 一變 true → 反射抽資料 → `setVisible(false)` 藏原框 → 開 overlay。另掛 Render.Post/滑鼠/鍵盤/拖曳/Closing 事件 |
| `client/ListBoxReflector` | 反射讀 `AESearchPatternProviderListBox.allItems`（SimpleItem: index/icon/name/full；已對照 0.5.6-alpha/beta/26.7.x），失敗自動退回原介面 |
| `client/UploadOverlay` | 面板本體（純類）：兩模式清單、搜尋、hover/tooltip、捲動、標題列拖曳、右下角縮放、本地重排 |
| `client/PatternUploadConfig` | `config/pattern_upload.json` 持久化：`providerMachines`（供應器名稱→配方類型 id）＋面板位置/尺寸（panelX/Y/W/Rows）；供應器以顯示名稱為鍵（改名即獨立身分，同名共用） |
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
```

- 自動機器判定：反射讀 GTOCore menu 的 `@GuiSync` 欄位 `gtocore$recipe`（`"<type_rl>/<recipe>"`）。
  **殘留防護**：GTOCore 只在「載入既有樣板」時更新此欄位，手動填格新編碼不會清 → 會殘留上一張的配方
  （可能是**別台機器**：壓印器樣板殘留成液化機；也可能是**同機器別條配方**：組裝機樣板殘留成 disassembly）。
  故 `currentRecipeType` 不比對精確 recipe id，改看「**該類型的任一配方**是否產出編碼格產物」：
  以 `GTRecipeType.recipes`（gtceu 同步到 client 的表，**非**原版 RecipeManager，後者查不到 gtceu 配方）
  逐條比 `itemOutputs`／`fluidOutputs` 對編碼格產出物。對不上 → 回 null（面板顯示未知、不自動上傳）。
  流體產物在 FakeSlot 是包成 wrapper item，用公開 API `GenericStack.unwrapItemStack` 解出 fluid key
  （不反射 private `encodedOutputsInv`——其欄位名正式包 reobf 後會 SRG 失配），以 `testAeKay` 比對。
- 供應器指定純客戶端持久化（不寫樣板 NBT、不動伺服端）；v1.1 的「寫樣板 NBT 指定機器」路徑已移除。

## 建置注意

- **Java 21 toolchain**（非一般 1.20.1 mod 的 17）：gto 全家 jar 為 Java 21 class files，整合包也以 Java 21 執行。
- 依賴（全 `compileOnly`，runtime 由整合包提供）自 `maven.gtodyssey.com/releases`：
  `com.gto:gtocore-forge-1.20.1`、`com.gto:gtolib-forge-1.20.1`、`com.gregtechceu.gtceu:gtceu-1.20.1-forge-1.20.1`、`appeng:appliedenergistics2-forge-1.20.1`、`com.gto:datasynclib-forge-1.20.1`（GTRegistry 基類）。
- 該 maven 的 module metadata 已在 repo 宣告 `metadataSources { mavenPom(); artifact() }` 忽略（其 `.module` 綁 JVM21 屬性）。
- `mods.toml` 強制依賴 `gtocore`。
- **dev 測試注意**：`build/libs` 的 jar（含 `-slim`）全被 FG6 reobf 成 SRG，**不能**丟進 moddev dev 環境；
  dev 用 `build/classes + resources` 手打的 named jar（見 build/devjar2 流程）。

## 已知限制

- 只處理「這次編碼的那張」樣板（設計如此）。
- 基礎排序沿用 GTOCore 伺服端邏輯（空槽 > 機器吻合 > 名稱吻合）；本地只再把「被指定機器且吻合」者提到最前。
- 供應器指定以顯示名稱為鍵：**同名供應器共用同一指定**（GTOCore 只回傳 icon＋名稱＋滿槽＋index，
  客戶端分不出同名實體；index 每次上傳重排、不能當持久身分）。要分開指定請先在遊戲內幫接口改名；
  MACHINE_SELECT 遇同名會顯示黃字警告。改名後需重新指定。
