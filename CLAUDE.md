# NL mod 規則

CLAUDE.md 只放指引；實作細節寫在 `README.md`／程式碼。

- 通用規則見工作區根 `CLAUDE.md`（本檔只列 mod 專屬）。

## mod 專屬約束

- 只有 `1.20.1/forge` 平台；**Java 21 toolchain**（gto 全家 jar 為 Java 21 class files）。
- 依賴 GTO 整合包（gtocore/gtolib/gtceu/ae2-gto，maven.gtodyssey.com，全 compileOnly）；`mods.toml` 強制 `gtocore`。
  另加 **ldlib**（LowDragLib，CurseForge `cursemaven.com`，compileOnly）：GTCEu `IMultiPart` 型別階層含其 `IUIHolder`，缺它編不過。
- **全 mod 零 mixin**（mixins.json 留空勿加）：gtocore 類在 dev 不可被 mixin 變換、正式包亦套不上；
  攔截一律走「Render.Pre 劫持 GTOCore 清單框 + 反射」（見 README）。
- 與伺服端互動優先用 GTOCore 既有介面（`gtolib$sendPattern` / `gtolib$sendEncodeRequest`）。
  **例外**：目的地座標＋建議機器同步走本 mod 自建封包 `com.patternupload.net.Network`
  （雙端註冊；伺服端反射 GTOCore 私有欄位 `gto$currentContainers`）——GTOCore 沒把座標／機器放進它的封包，
  無此路同名供應器分不出實體、接口類判不出機器。伺服端另解析「建議機器」（接口→子網 grid→存儲總線→機器配方類型）。
  零 mixin 仍成立（只反射＋自建封包）。伺服端沒裝本 mod → 收不到 → 座標退名稱鍵、無建議（皆舊行為）。
- 供應器→機器指定為**純客戶端持久化**（`config/pattern_upload.json`）；不寫樣板 NBT。
  鍵優先用「世界座標」（伺服端回傳 → 同名供應器各自獨立），無座標時退「顯示名稱」（相容舊設定）。
- `jecharacters` 拼音搜尋為軟依賴（純反射，非編譯依賴，照 NL_oreveinfilter 模式）。
- 動攔截／dev 流程／建置鏈前，先讀工作區 `docs/NL_pattern_upload/gtocore-hijack-pitfalls.md`（雷點筆記）。
