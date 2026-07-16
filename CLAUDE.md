# NL mod 規則

CLAUDE.md 只放指引；實作細節寫在 `README.md`／程式碼。

- 通用規則見工作區根 `CLAUDE.md`（本檔只列 mod 專屬）。

## mod 專屬約束

- 只有 `1.20.1/forge` 平台；**Java 21 toolchain**（gto 全家 jar 為 Java 21 class files）。
- 依賴 GTO 整合包（gtocore/gtolib/gtceu/ae2-gto，maven.gtodyssey.com，全 compileOnly）；`mods.toml` 強制 `gtocore`。
- Mixin 目標只有一個：`com.gtocore.client.Message$Client#patternDestinationReceived`（remap=false）。**不要對 vanilla 類加 mixin**——畫面疊加一律走 Forge `ScreenEvent`（見 README）。
- 與伺服端互動只准用 GTOCore 既有介面（`gtolib$sendPattern`/`gtolib$addRecipe`/`gtolib$sendEncodeRequest`），不自建封包。
