# NL mod 規則

CLAUDE.md 只放指引；實作細節寫在 `README.md`／程式碼。

- 通用規則見工作區根 `CLAUDE.md`（本檔只列 mod 專屬）。

## mod 專屬約束

- 只有 `1.20.1/forge` 平台；**Java 21 toolchain**（gto 全家 jar 為 Java 21 class files）。
- 依賴 GTO 整合包（gtocore/gtolib/gtceu/ae2-gto，maven.gtodyssey.com，全 compileOnly）；`mods.toml` 強制 `gtocore`。
- **全 mod 零 mixin**（mixins.json 留空勿加）：gtocore 類在 dev 不可被 mixin 變換、正式包亦套不上；
  攔截一律走「Render.Pre 劫持 GTOCore 清單框 + 反射」（見 README）。
- 與伺服端互動只准用 GTOCore 既有介面（現僅 `gtolib$sendPattern`），不自建封包。
- 供應器→機器指定為**純客戶端持久化**（`config/pattern_upload.json`，名稱為鍵）；不寫樣板 NBT。
- `jecharacters` 拼音搜尋為軟依賴（純反射，非編譯依賴，照 NL_oreveinfilter 模式）。
