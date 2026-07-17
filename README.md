# Pattern Upload (NL_pattern_upload)

GTOCore 樣板編碼終端「編碼並發送（上傳按鈕右鍵）」的自製目的地介面：
顯示樣板對應機器、可對「供應器」手動指定機器（接口貼子網用）、指定吻合者浮頂並持久化。

## 功能

- 攔截 GTOCore 送回客戶端的目的地清單，改開本 mod 的 overlay（GTOCore 原列表框不再顯示）。
- **DESTINATIONS 模式**：列出目的地樣板供應器（icon + 名稱 + 滿槽標記），含搜尋欄（支援 JECh 拼音）與滾輪捲動；點列即發送樣板（滿槽列不可點）。
- **標題列最左 icon**：樣板對應機器 icon（自動判定自 GTOCore `@GuiSync` 欄位）；判不出顯示樣板 icon（純顯示）。
- **點目的地列最左 icon** → **MACHINE_SELECT 模式**：對「該供應器」指定它服務的機器
  （供應器是 ME 接口貼子網時，伺服端判不出對應機器，這裡手動指定）。列出所有 GTRecipeType
  （代表機器 icon + 本地化名 + 搜尋）；首列「清除指定」。指定持久化於 `config/pattern_upload.json`。
- **本地重排**（穩定排序，層級 0–5）：0 手動指定吻合 → 1 目的地 icon 反查機器支援本類型 →
  2 名稱含類型名 → 3 無法判定 → 4 指定但不吻合 → 5 滿槽；同層維持伺服端順序。
  伺服端類型排序靠 menu 暫存 `gto$lastRecipeType`（重編舊樣板時 = null，只剩空位排序，
  且字串比對會誤中「電路組裝機」含「組裝機」）——本地用 `@GuiSync` 的 `gtocore$recipe` 補正。
- **合成類樣板**（CRAFTING/SMITHING/STONECUTTING，`menu.getMode()` 判定）：只有分子裝配室／裝配矩陣能做，
  配方類型概念不適用 → 停用本地重排與供應器指定（避免殘留 `gtocore$recipe` 亂排），
  完全沿用伺服端 `gto$craftFirst` 順序；標題顯示「合成樣板」＋工作台 icon。
- 供應器被指定後，列名顯示「機器名（原供應器名）」，icon 換成機器圖示。
- 面板可拖曳（標題列）、**右下角把手可縮放**（寬 120–280、3–12 列，左上角為錨點；高度固定跟 maxRows，列不足留空白）；位置與尺寸持久化於同一 config。
- 預設位置：合成欄（3x3 編碼格）右邊（`PatternEncodingTermMenu.getCraftingGridSlots()` 取座標，取不到退回 GUI 右側）。
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

- 自動機器判定：反射讀 GTOCore menu 的 `@GuiSync` 欄位 `gtocore$recipe`（`"<type_rl>/<recipe>"`），失敗退回樣板 icon（僅影響顯示與本地重排）。
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
