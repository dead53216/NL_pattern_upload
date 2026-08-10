# Pattern Upload (NL_pattern_upload)

GTOCore 樣板編碼終端「編碼並發送（上傳按鈕右鍵）」的自製目的地介面：
顯示樣板對應機器、可對「供應器」手動指定機器（接口貼子網用）、指定吻合者浮頂並持久化。

## 功能

- 攔截 GTOCore 送回客戶端的目的地清單（GTOCore 原列表框不再顯示）。
- **處理樣板直傳**：收集所有明確匹配（tier 0 手動指定吻合 / tier 1 機器類型吻合，含名稱最長機器名）。
  **剛好一個** → 直傳＋聊天欄提示；**多個** → 開面板讓玩家自選（不亂猜）；**零個** → 開面板。
  - **自動直傳嚴格唯一（1.24.0，收回 1.23.0）**：match 多於一個——**即使是同一台機器掛多個供應器／
    同款可互換機器——一律開面板**讓玩家選，不自動直傳。1.23.0 曾以「機器簽章」全同視同單一自動挑一個，
    使用者要求唯一性從嚴（唯一機器、唯一配方，>1 個就不猜），故移除簽章合併（`matchSig` 已刪）。
  - **多類型機器 icon 匹配吃「已決定類型」（1.24.1）**：tier 1 icon 反查原用機器**全類型集**判吻合 →
    大型切割機（切割＋車床）兩台不同模式全被車床樣板判 match、永遠開面板。現 icon 為多類型機器且
    伺服端有回報「已決定類型」（1.19.1 起＝當下模式）時以它判定：吻合 → tier 1；已決定其他模式 →
    tier 4（不算明確匹配）。沒回報（舊伺服端／逾時）退回全集判定。唯一「當下模式吻合」那台 → 自動直傳。
  - **建議列顯示實際機器（1.25.0）**：建議只帶配方類型時，顯示退「類型代表機器」——化工廠（與大型
    化學反應釜同類型）經超立方／子網判定會被**顯示成大型化學反應釜**。現伺服端隨建議回報實際機器
    物品 id（`sugMachine`/`extraMachine` 尾綴；直貼＝該機器、tesseract＝綁定唯一機種才回報、子網＝
    唯一機種才回報，混綁不猜），客戶端建議列（含 extras 置頂列、聊天欄回報）icon＋名稱改用實際機器；
    未回報退類型代表機器。手動指定列尊重玩家選的類型顯示，不受影響。匹配邏輯不變（同類型本就吻合）。
  - **聊天欄回報目標優先報機器（1.17.1，`sentDisplayName` 統一格式）**：「機器名 (供應器標籤)」——
    機器取 有效機器（手動指定/建議）?? 樣板類型；合成直傳無配方類型概念，以 icon 物品名（分子裝配室/裝配矩陣）當機器名。
    判不出機器（或機器名＝標籤）退回原標籤。涵蓋 自動直傳、合成直傳、面板點列 三路徑；批次上傳仍報張數。
- **中鍵上傳鈕＝強制開面板**：中鍵點 GTOCore 上傳（編碼）鈕 → 呼叫 `gtolib$sendEncodeRequest()` 要清單，
  但設 `forcePanel` 旗標讓這批**跳過所有自動直傳**（含合成類與單一匹配），一律開面板讓玩家自選。
  攔在 `MouseButtonPressed.Pre`（HIGHEST），以 `gto$getEncodeButton()` 邊界判界；旗標開面板即清、關終端也清。
  **強制開的面板一律當非合成模式**（`force` 傳入 overlay → `craftMode()` 回 false）：即使是合成／鍛造／切石樣板，
  也能點列 icon 指定／**改機器**、顯示機器類型與吻合排序、逐列上傳（合成類原本這些互動被 craftMode 擋掉，
  中鍵手勢本意就是跳過自動流程讓玩家自控）。
- **DESTINATIONS 模式**：列出目的地樣板供應器（icon + 名稱 + 滿槽標記），含搜尋欄（支援 JECh 拼音）與滾輪捲動；點列即發送樣板（滿槽列不可點）。
  - **搜尋含配方類型名＋機器電壓（1.20.0）**：伺服端隨建議回報機器電壓等級（`GTValues.VN` 素字串如 "LV"，
    尾綴欄位 `tier[]`；直貼機器／tesseract 綁定唯一電壓／子網唯一機器，部件取控制器，判定優先序照 GTO 命名
    helper：ITieredMachine → maxOverclockTier → 超頻電壓反推）。已判定機器列第一行顯示「機器名＋電壓」；
    搜尋鍵＝標籤＋機器類型名＋電壓——打「壓印」「EV」都過濾得到（GTO 標籤有無帶電壓皆可）。
    舊伺服端沒回 → 電壓空、不顯示不參與搜尋。
- **GTO 樣板總成支援（1.26.0）**：樣板總成（`MEPatternPartMachineKt` 家族，直接放樣板的機器型容器、
  免樣板供應器）**直接實作** `IExtendedPatternContainer` 而非 AE2 供應器的 IPPPC mixin → 修前伺服端
  整段跳過：無座標、無建議、無剩餘格、無已有判定。現迴圈改判 `IExtendedPatternContainer`：剩餘格／
  已有配方對任何樣板容器都走 `PatternContainer` 樣板庫存；機器型容器（`instanceof MetaMachine`）座標取
  機器本體、建議走其 BE（`suggestionOf` 的 IMultiPart 分支 → 可程式化設定／多方塊控制器、電壓、實際
  機器物品一併回報）。extras 枚舉同款補。客戶端零改動。
- **列右緣顯示樣板槽剩餘空格（1.15.0）**：伺服端隨座標封包回報各供應器 `getTerminalPatternInventory()`
  空格數（`ReplyS2C` **尾綴欄位**，協定號不變：舊伺服端沒寫 → decode 以 `isReadable()` 判缺、全 -1 不顯示；
  舊客戶端不讀尾綴 bytes 無害——版本不齊零故障）。綠字＝有空位、紅灰＝滿；-1（伺服端沒裝本 mod）不畫，
  名稱截斷寬度自動讓位。
- **「已有該配方」視同滿槽（1.16.0）**：GTO 上傳時會忽略「已有相同主產物樣板」的供應器——本 mod 伺服端
  以**同款判定**（mixin 暫存 `gto$patternStack` 反射取本次編碼樣板 → AE2 公開 API
  `PatternDetailsHelper.decodePattern` 解主產物 → 逐張 decode 供應器樣板庫存比主產物 AEKey；**不比 NBT**，
  GTOCore encode hook 塞的殘留 `recipe` 標籤使 NBT 等值不可靠）預先標記，`hasRecipe[]` 再走一段尾綴欄位。
  客戶端該列**行為視同滿槽**：灰字、不可點、不可多選、批次跳過、不算進自動直傳明確匹配；
  右緣改顯**橙字「已有該配方」**（取代剩餘格數）＋tooltip 說明。舊伺服端沒回 → 全 false 照舊。
- **被 GTO 藏掉的「已有」供應器補回＋置頂（1.17.0）**：GTO 建清單時把「已有該配方」的供應器**整列移除**
  （removeIf `canAddPattern && containsPrimaryOutput`）→ 客戶端清單根本沒有、1.16.0 的標記無從顯示。
  現伺服端照 GTO 同款枚舉（`getMachineClasses` → `IExtendedPatternContainer` → `getActiveMachines`）
  找整網「不在清單、終端可見、已有本次主產物」者，以 `ReplyS2C` **第三段尾綴** `extras`（GTOCore 群組
  標籤名＋群組 icon 物品 id＋建議機器＋剩餘格，上限 16）回報。客戶端把它們與清單內 hasRecipe 列一起
  **置頂**（tier -1）：玩家一眼看到「這張樣板已經在哪」。額外列純資訊（不可點、不可指定機器、icon 用
  建議機器 icon 或群組 icon）。**extras 非空時押制單一匹配自動直傳**（改開面板）——網路已有這張樣板還
  無感直傳到別台＝重複鋪樣板。伺服端沒裝本 mod → 無 extras、無押制（照舊）。
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
  - **相同機器依剩餘空格由小到大（1.19.0）**：同層同機器（群組鍵＝有效機器，判不出退標籤）的列
    聚在該群首見位置、照 `free` 升冪（-1 未知排最後）——優先塞快滿的供應器、樣板集中；
    跨群與跨層仍維持 tier 排序後的相對順序。舊伺服端沒回 free → 全 -1，排序不變。
  - **配方電壓對應機器電壓（1.21.0）**：樣板配方 tier 取產物匹配定義的最小 `GTRecipeDefinition.tier`
    （與 currentRecipeType 同趟掃描、同快取；proxy 燒煉路徑無 tier＝未知）。同機器群內排序鍵插在 free 之前：
    **跑得動**（機器 tier ≥ 配方 tier）→ 任一方未知 → **電壓不足**（跑不動、沉底）；跑得動者機器電壓
    **低→高**（最貼近配方電壓者先、不佔高壓機）→ 再 free 升冪。**自動直傳同步排除電壓不足者**
    （不直傳到跑不動的機器；未知電壓寬容放行維持舊行為）。
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
  （子網掃到**唯一**一台才建議，0／多台歧義不猜）。座標封包一併回傳。
  - **多類型機器與部件設定（1.18.0，1.19.1 改「已決定類型」）**：多類型機器**模式一經設定就只跑該類型**
    （大型切割機＝切割或車床擇一），建議回報 `machineTypeOf` 的**已決定類型**——多類型取 `getRecipeType()`
    （當下設定的模式）、單一有效類型直接回、DUMMY/HATCH_COMBINED 不算。1.18.0 曾回可用類型**全集**，
    會把另一模式的樣板誤匹配置頂（1.19.1 修）。部件（輸入倉/總線）若為**可程式化**（gtmthings
    `IProgrammableMachine`，如可程式化倉）且已設定配方類型 → **優先用部件設定**（GTO 自家命名同款優先序）；
    該介面在 gtocore JiJ 內嵌、不在編譯 classpath → 反射 `getRecipeType()`（同 IBindable 雷處理）。
    建議欄位仍可為逗號串多類型（tesseract 聯集用）；舊客戶端 tryParse 逗號串失敗＝視同無建議（無害降級）。
  - **直接貼機器者也回報建議（1.13.1，供應器改名救援）**：供應器被改自訂名（不以 `+` 開頭）後，
    GTOCore 的 `gto$getTerminalGroup` 直接退回 AE2 原生群組（自訂名＋供應器自身 icon）→ 機器 icon／名稱全失，
    客戶端 icon 反查與名稱比對雙雙失效、改名前能判的機器變「未知」。故伺服端對直接貼機器也回報其配方類型；
    客戶端以「icon 反查不到機器」為門檻才採用（`usableSuggestionFor`）——未改名者 GTOCore 標籤照判、
    顯示與排序**零變動**；改名者靠建議照樣判得出（拓撲即時算，每次編碼都新鮮，無需任何刷新手段）。
    GTO 慣例：自訂名以 `+` 開頭（如 `+一號`）GTOCore 會保留機器判定、後綴附加顯示，不觸發此問題；
    改**目標機器**的名字則不影響（GTOCore 取機器 definition 翻譯鍵，不吃機器自訂名）。
  - **跨 me無線連接機（本地優先）**：GTO 的無線連接機**不合併** AE grid（只用自家 `WirelessNetwork` 層橋接）→ 遠端子網的
    存儲總線／機器 `IGrid.getActiveMachines` 掃不到。但**先只掃接口自己的子網**（原行為）——本地有機器就以本地為準、
    **不跨橋**；只有本地**完全沒有**機器時，才沿無線連接機 BFS 找遠端子網（找其上 `WirelessMachine` owner、循
    `connectedNetworkId` 取 `WirelessNetwork` 的 input/output 節點、把配對端子網 grid 排入佇列續掃，`visited` 去重、
    上限 `MAX_SCAN_GRIDS=64` 防環）。**為何本地優先**：網路若全走無線橋，從任一接口 BFS 會撈到整網各子網機器 →
    幾乎必然多機型歧義回 ""（本地明明有機器卻判不出，1.12.0 曾如此回歸）；本地優先保證本地能判者照舊、跨橋只當補救。
    `WirelessNetworkSavedData.networkPool` 型別是 gtocore JiJ 內嵌的 fastcollection、不在編譯 classpath → **該一跳走反射**
    （GTO 自有類名不經 SRG remap，穩定）；枚舉節點／反射任何失敗靜默跳過（不吞本地結果）。唯一機型才建議，跨橋後多機型仍歧義回 ""。
  - **貼「超立方體發生器」追綁定目標（1.14.0）**：GTOCore tesseract（基礎／進階／定向）是**代理機器**——
    綁定卡放機器內部、物品/流體操作轉發到被綁方塊，供應器可貼它傳輸。三者都 `extends MetaMachine` 但
    **無配方邏輯**（註冊鏈沒設 recipeType → `getRecipeTypes()` = null）→ GTOCore 標籤顯示 tesseract 自身
    icon/名稱、客戶端 icon 反查與名稱匹配（表裡只收有配方類型的機器）雙雙落空 → 修前必判「未知」。
    現伺服端 `resolveSuggestedMachine` 對直貼機器判不出配方時追 tesseract 綁定：進階／定向走 `IMultiTesseract`
    迭代 `getBlockEntity(i)`（定向版 `TesseractDirectedTarget` 為 GlobalPos，跨維度亦涵蓋）、基礎版讀公開欄位
    `pos`。gtocore 在編譯 classpath，直接 import 免反射（`IMultiTesseract` 型別階層只含 gtceu/gtocore 自有類，
    無 JiJ 缺類 instanceof 雷）。
    tesseract icon 反查必為 null → `usableSuggestionFor` 門檻自動放行，顯示／排序／自動直傳全走 1.13.1 建議路。
    綁到另一台 tesseract 不遞迴（當非機器跳過）；目標 chunk 未載入該格跳過。
    - **綁定目標取聯集（1.18.1）**：原「唯一機型才建議、多機型歧義回 ""」改為所有綁定機器類型**聯集**：
      tesseract 本就把 I/O 分派給所有綁定機器，樣板類型吻合**任一**綁定機器即可正確上傳；客戶端
      `pickSuggestion` 自聯集挑吻合本次樣板者顯示。1.19.1 起每台只貢獻「已決定」類型（不含未選用模式）。
    - **存儲總線貼 tesseract 也追綁定（1.18.1，`suggestionOrTesseract`）**：接口子網的總線路徑原本不追
      tesseract；現與直貼共用同款解析（tesseract 無配方邏輯 `suggestionOf` 必空 → 不誤觸一般機器）。
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
  - **Ctrl+滾輪內容縮放（1.22.0，1.22.1 改框內縮放）**：滑鼠在面板上按住 Ctrl 滾動 → **外框不動**、
    框內內容（字體/列/搜尋欄）縮放（0.5–2.0、一格 0.1，`panelScale` 持久化）——縮小＝同框塞更多列
    與更多字、放大＝列變大變少。實作：外框（底/邊框/右下把手）畫實座標；內容以左上角為錨 pose scale，
    內容邏輯寬高＝實寬高 ÷ 縮放（`cw()/contentH()`），可見列數 `contentRows()` 由邏輯高即時算
    （外框 resize 的 `maxRows` 只定義框高）。滑鼠入口以 `lx/ly` 反除縮放換算；tooltip 傳邏輯座標
    經縮放 pose 映回原位；拖曳移動與右下角改外框尺寸走螢幕座標（1:1 跟手）。
    非整數倍縮放字體會稍糊（原版字型限制）。
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
- 建議機器跨 me無線連接機為**本地優先**：先掃接口自己的子網，本地能判就用本地（不跨橋）；本地無機器才沿無線橋 BFS
  找遠端（見「功能」）。跨橋後涵蓋多機型仍算歧義回 ""。故全走無線橋的網路本地機器照樣判得出（1.12.0 全 BFS 曾整網歧義、
  1.12.1 修）。GTO 無線 API 走反射、失敗自動退回不跨橋。
- 供應器改名後的機器判定靠伺服端建議（1.13.1）：**伺服端沒裝本 mod** → 收不到建議，改名（不以 `+` 開頭）的
  直貼機器供應器仍會變「未知」——此時請用 GTO 的 `+後綴` 改名慣例，或手動指定機器（名稱鍵）。
- 貼超立方體發生器的供應器同理全靠伺服端建議（1.14.0）：**伺服端沒裝本 mod** → 必「未知」，只能手動指定。
  綁定目標多機型（如一台 tesseract 同時綁液化機＋壓印器）→ 1.18.1 起取**聯集**（吻合任一綁定機器即匹配、
  可自動直傳；顯示挑吻合本次樣板者）；綁鏈式 tesseract 不追第二層。
