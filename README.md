# Pattern Upload (NL_pattern_upload)

GTOCore 樣板編碼終端「編碼並發送（上傳按鈕右鍵）」的自製目的地介面：
顯示樣板對應機器、可手動指定機器、維持「有對應機器的供應器浮頂」排序。

## 功能

- 攔截 GTOCore 送回客戶端的目的地清單，改開本 mod 的 overlay（GTOCore 原列表框不再顯示）。
- **DESTINATIONS 模式**：列出伺服端已排序的目的地樣板供應器（icon + 名稱 + 滿槽標記），含搜尋欄與滾輪捲動；點擊即發送樣板（滿槽列不可點）。
- **標題列最左 icon**：樣板對應機器的 icon（自動判定自樣板 NBT `recipe` 標籤）；判不出時顯示樣板 icon。點擊切換 **MACHINE_SELECT 模式**。
- **MACHINE_SELECT 模式**：列出所有 GTRecipeType（代表機器 icon + 本地化名 + 搜尋）；點選 → 寫入樣板 NBT 並重新請求 → 清單依新機器重新排序浮頂。
- ESC 第一下關 overlay，第二下才關終端；搜尋欄聚焦時吞按鍵避免 `E` 關閉介面。

## 架構（1.20.1/forge）— v1.1「零 mixin 劫持」

**完全不用 mixin**（v1.0.x 的 mixin 方案已廢棄：dev 環境 gtocore 類不可被變換、
正式包亦無法套用）。改為每幀監看 GTOCore 清單框，變可見即接管：

| 元件 | 說明 |
|---|---|
| `client/PatternUploadClient` | `ScreenEvent.Render.Pre` 輪詢 `term.gto$getPatternDestDisplay()`；`isVisible()` 一變 true → 反射抽資料 → `setVisible(false)` 藏原框 → 開 overlay。另掛 Render.Post/滑鼠/鍵盤/拖曳/Closing 事件 |
| `client/ListBoxReflector` | 反射讀 `AESearchPatternProviderListBox.allItems`（SimpleItem: index/icon/name/full；已對照 0.5.6-alpha/beta/26.7.x），失敗自動退回原介面 |
| `client/UploadOverlay` | 面板本體（純類）：兩模式清單、搜尋、hover/tooltip、捲動、**標題列拖曳**（位置整場記住） |
| `client/RecipeTypeIcons` | `GTRegistries.MACHINES` 掃描建 GTRecipeType→代表機器 icon 快取；名稱沿用 GTOCore 慣例 `"gtceu." + registryName.getPath()` |

`/patternupload_test`：30 秒內開啟樣板編碼終端即注入假目的地，驗證整條劫持鏈。

### 關鍵資料流

```
編碼鈕右鍵（GTOCore 原有）→ 伺服端 encode + 排序 → SEND_PATTERN_DESTINATION_S2C
  → GTOCore 填清單框 + setVisible(true) → [本 mod Render.Pre 劫持] → UploadOverlay 顯示

點目的地列  → menu.gtolib$sendPattern(destIndex)          （GTOCore 介面）
手動指定機器 → menu.gtolib$addRecipe("<type_rl>/manual")   （寫入樣板 NBT recipe 標籤 + 同步伺服端排序鍵）
            → menu.gtolib$sendEncodeRequest()             （重新編碼 + 重排 + 重送清單 → overlay 重建）
```

- 自動機器判定：反射讀 GTOCore menu 的 `@GuiSync` 欄位 `gtocore$recipe`（`"<type_rl>/<recipe>"`），失敗時退回樣板 icon（僅影響顯示）。
- 手動指定暫存 `lastManualType`，等重送清單期間保留（`expectingRefresh`），新一輪上傳自動清除。

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
- 目的地排序完全沿用 GTOCore 伺服端邏輯（空槽 > 機器吻合 > 名稱吻合）。
- 手動指定寫入的樣板 NBT `recipe` 值為 `<type_rl>/manual`。
