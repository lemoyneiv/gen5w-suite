# Gen5W Unified HMG-API Suite (US Hyundai Ioniq 5 — `260507` & `260903`)

Conflict-free, single-USB HMG-API suite built for **US Hyundai Ioniq 5** (`NEEV`, `NEEV23`, `NEEV23SE`) head units running **firmware `260507` or `260903`** with **NEMESIS (`>= 1.16`)** installed.

Mods that are unmodified upstream (`adb` and `egmp-aa-ev`) are bundled using their **official upstream-signed release archives** so they retain author-key continuity with the NEMESIS Package Store and HMG-API Community Catalogue, while custom/patched mods (`wireless-carplay-aa`, `aa-dpi`, `hmgapi-comfort`, `light-vibes`) are signed with `lemoyneiv` (`28ad71e0-07c0-8e61-8bf3-380cc71f9280`).

---

## Included Components

| Order | Component | Version | Authors | Source & Key Details |
|---:|---|---|---|---|
| Core | **[`lemoyneiv/HMG-API`](https://github.com/lemoyneiv/HMG-API)** | `1.1.1-rc.11` | `VysseCodes`, `lemoyneiv` | Rebased onto upstream `Open-HMG/HMG-API` **`v1.1.0-rc.7` + `#370`** (`6dbe117`: Home Wi-Fi, default cellular block, Record a problem, `boot_id` boot guard, `#340`/`#341`) and merges open PRs `#173` (BLE Central/GATT), `#293` (`ModeManager` & `MicomAudioManager` hook points), `#303` (launcher hook ordering & menu sync), `#304` (launcher `menu_list.new` & background `LauncherReload`), plus **redesigned `WifiManager` Home Wi-Fi engine, configurable Unplugged & Plugged-In Parked Keep-Awake, & live UI** (`HomeWifiDaemon` + `HomeWifiKeepAwake`: separate Unplugged & Plugged-In keep-awake durations including *While plugged in*, `charging.plugged` EVSE cable detection, `45 s` `PwrKeep(1)` refresh + `WifiLock`/`WakeLock`, peer OpenLink lease coordination, live Park scan with 0 saved networks, per-SSID *"Use for Home Wi-Fi"* dashcam exclusion, `bcmdhd` watchdog, instant wrong-password detection, `150 ms` DHCP + `/data/hmgapi/wifi/lease` + `table 95` split routing) and **Settings → Home Wi-Fi → Mobile data** per-mod `allow_cellular` switches; preserves QuickBoot `/data/hmgapi/public/wake`, `INCONSISTENT_CERTIFICATES` APK cert-swap fallback, and `1 s` boot service tick |
| `01` | **`wireless-carplay-aa`** | `2.2.2` | `VysseCodes`, `lemoyneiv` | Rebased onto upstream **`v2.2.0-rc.8` + `#94`** (`27e629d`: `"connect a phone"` popup Settings fix `#86`, CarPlay log switch `#84`, `#94` revert of `#69` phone-selection bug, wireless CarPlay Now Playing, AR HUD standard turns `#33`, atomic `leafiaaac.c` `#55`, `NEEV23SE`/`260903` `#54`/`#58`), keeping **Projection Focus Arbitrator (`FocusGuard` / `FocusPolicy`, `#56`)** enabled by default, PR `#16` subset (`Keyframes` CarPlay freeze IDR recovery + `CpTurns` highway/rotary/stale-arrow fixes + post-route Compass replay, **without** `Watchdog`), and PR `#28` (`wifi_direct` opt-in toggle) |
| `02` | **[`adb`](https://github.com/Open-HMG/hmgapi-adb)** | `1.0.1-rc.1` | `VysseCodes` *(official release)* | Unmodified official `Open-HMG/hmgapi-adb` release archive (`7251ce71-…`); `NEMESIS.sh` seeds `/data/hmgapi/settings/adb.json` (`{"usb": false}`) when absent so USB flash drives and wired CarPlay/Android Auto keep working out of the box while turning off the Engineering Mode `eth0` subnet conflict |
| `03` | **`aa-dpi`** | `0.3.0` | `eliotwass`, `AwsomeFox`, `lemoyneiv` | Upgraded to `eliotwass/hmg-aa-dpi` `0.3.0`: target **200 DPI**, **tap/scroll limit off** (`tap_limit: false`), **60 fps** video window (`video_window: 6`), `nemesis_conflicts: ["aa.dpi"]` |
| `04` | **[`egmp-aa-ev`](https://github.com/AwsomeFox/egmp-aa-ev-hmg-api)** | `0.8.2` | `willemcvu`, `AwsomeFox` *(official release)* | Unmodified official `AwsomeFox/egmp-aa-ev-hmg-api` release archive (`839375d3-…`); Google Maps EV routing over Android Auto (live SoC, range, charge curves, automatic DC fast-charger battery preconditioning, metric/imperial settings) |
| `05` | **[`hmgapi-comfort`](https://github.com/lemoyneiv/hmgapi-comfort)** | `1.1.8` | `lemoyneiv` *(standalone repo)* | **Standalone native HMG-API mod** replacing both `hkmc-coldstart-heat` and `hkmc-factory82`: dedicated `WelcomeDaemon` (`user=system, start=boot`) for sub-second cold-boot & QuickBoot STR welcome screen dismissal + native Metric (`°C`) & Imperial (`°F`) support + 3-tier cold heating + 3-tier warm seat ventilation + independent wheel threshold + passenger/rear policies + widescreen **Material 3** Day/Night control center |
| `06` | **[`light-vibes`](https://github.com/lemoyneiv/light-vibes/tree/intense-effects)** | `0.7.0` | `Kyle (dibble9012)`, `lemoyneiv` | **Intense Effects** branch: music- & speed-reactive Gen5W ambient lighting (`lightvibesd` static ARMv7 + `LightVibes.apk` Material 3 preset studio, Niro EV `sg2ev_us` support, USB-only LED testing & stock color capture, zero `/system` edits) |

---

## One-Tap USB Installation (Recommended)

Assumes **NEMESIS (`>= 1.16`)** is already installed on the head unit:

1. Format a USB flash drive as **FAT32** (MBR).
2. Extract **`dist/gen5w-suite-usb.zip`** directly to the **root** of the USB drive so `NEMESIS.data`, `NEMESIS.sh`, `packages/`, and `HMGAPI/` sit at the top level.
3. With the car powered on (`READY` or Utility Mode) and the infotainment booted, insert the USB stick into the data USB port.
4. When NEMESIS asks to execute the USB item, tap **Yes** once and wait ~30 seconds for the completion dialog/toast:
   - Automatically detects and uninstalls any old **Leafia** or **Evergreen** precursor packages/runtimes (`wireless.carplay.aa`, `carplay.wifi.audio`, `*leafia*`, `*evergreen*`, `/data/leafia/uninstall.sh`, `/data/evergreen/uninstall.sh`) plus conflicting standalone NEMESIS packages (`aa.dpi`, `aa.ev.model`, `coldstart.heat`, `local.hkmc.factory82`, `wireless.projection`, `enhanced.projection`, `projection.tbt`, `adb.tcp`, `light.vibes`, `ambient.sound`) and restores their stock backups first.
   - Installs or updates **HMG-API Core `1.1.1-rc.11`** (installing `HmgApi.apk` cleanly without requiring a separate NEMESIS Launch step) and explicitly resets `/data/hmgapi/hook/starts` so hooks recover cleanly even if the 3-strike Zygote boot guard previously tripped.
   - Enables and verifies the car's hook map (`260507` or `260903`), ensures `block_ambient_light` is `off`, and pre-grants owner permissions for comfort and EV preconditioning vehicle commands.
   - Seeds `/data/hmgapi/settings/adb.json` (`{"usb": false}`) when absent, installs all 6 signed mods in dependency order (preserving existing `/data/hmgapi/settings/*.json` across per-mod author-key migrations, while setting `aa-dpi` to `200 DPI` + `tap_limit: false` + `60 fps`), and non-destructively seeds `"hmgapi-openlink"` into `/data/hmgapi/settings/hmgapi.json` `overrides.allow_cellular` once so OpenLink has LTE access out of the box (toggleable live anytime in **HMG-API → Settings → Home Wi-Fi → Mobile data**).
   - Runs **`hmgapid engine apply` once**, installs companion APKs (`Comfort.apk` and `LightVibes.apk`), and syncs **NEMESIS Manager → My Packages**.
5. **Restart the head unit once** (pinhole reset or power cycle) to activate all Zygote hooks and mods.

### Optional Flag Files on the USB Stick (`HMGAPI/`)

| File on USB | Effect |
|---|---|
| `HMGAPI/FORCE` | *(Included by default)* Allows HMG-API core to install even if `app_process` has a newer stock MD5 |
| `HMGAPI/RESET_SETTINGS` | Resets all 6 suite mods and OpenLink cellular allowlist seeding back to suite defaults during install |
| `HMGAPI/ENABLE_ALL` | Re-enables any mod that was previously toggled off |
| `HMGAPI/AUTO_REBOOT` | Automatically reboots the head unit 2 seconds after a successful install |
| `HMGAPI/suite-install.log` | Written back to the USB stick automatically at the end of installation |

### Other Generated USB Layouts

- **`dist/gen5w-suite-nemesis-store-usb.zip`**: Interactive NEMESIS `type=list` stick — opens **NEMESIS Manager → Package Store → USB** so packages can be inspected or installed one by one.
- **`dist/gen5w-suite-hmgapi-updates-usb.zip`**: Contains `HMGAPI/FORCE` and `HMGAPI/updates/*.hmgapi.zip` for updating individual mods from inside the **HMG-API** app.

---

## Standalone Packages (`Comfort` & `Light Vibes`)

**Comfort & Startup** has its own dedicated standalone repository at **[`lemoyneiv/hmgapi-comfort`](https://github.com/lemoyneiv/hmgapi-comfort)** (built directly against stock upstream `Open-HMG/HMG-API` `>= 1.0.0`), and the **Home Screen Tile Manager** has moved out of HMG-API Core into its own standalone repository at **[`lemoyneiv/hmgapi-tile-manager`](https://github.com/lemoyneiv/hmgapi-tile-manager)**. `./build-suite.sh` also outputs ready-to-share standalone archives in **`dist/standalone/`**:

| App | One-Tap NEMESIS USB Stick | HMG-API Updates USB Stick | Direct HMG-API / NEMESIS Store Package |
|---|---|---|---|
| **[Comfort (`1.1.8`)](https://github.com/lemoyneiv/hmgapi-comfort)** | `hmgapi-comfort-v1.1.8-nemesis-usb.zip` | `hmgapi-comfort-v1.1.8-hmgapi-usb.zip` | `hmgapi-comfort-v1.1.8.hmgapi.zip` / `-nemesis-package.zip` |
| **Light Vibes (`0.7.0`)** | `light-vibes-v0.7.0-nemesis-usb.zip` | `light-vibes-v0.7.0-hmgapi-usb.zip` | `light-vibes-v0.7.0.hmgapi.zip` / `-nemesis-package.zip` |

- **Via NEMESIS USB**: Extract `*-nemesis-usb.zip` to the root of a FAT32 USB drive (`NEMESIS.data` and `packages/` at root), insert into the car's data USB port, and accept the NEMESIS install prompt.
- **Via HMG-API App USB**: Extract `*-hmgapi-usb.zip` to the root of a FAT32 USB drive (`HMGAPI/updates/*.zip` at root), open **HMG-API → Mods → Install from USB**, and apply.
- **Stock HMG-API Notes**:
  - On stock upstream `Open-HMG/HMG-API`, HMG-API prompts once to trust author key `lemoyneiv` (`28ad71e0-07c0-8e61-8bf3-380cc71f9280`) during install.
  - For **Comfort**, enable the owner permission toggles (`startup_notice`, `seat_heat`, `seat_vent`, `wheel_heat`) under **HMG-API → Permissions** after installing. Comfort automatically detects when the standalone **Home Screen Tile Manager** (`dev.lemoyne.tiles`) is installed and opens it directly, or hides the shortcut button when Tile Manager is not installed.
  - For **Light Vibes**, ensure **Block ambient light changes** is **off** under **HMG-API → Permissions** so `lightvibesd` can drive the cabin LEDs.

---

## Building & Running All Tests From Source

### Prerequisites

- Linux or macOS with JDK 17+, Python 3.10+ (`numpy` + `zig` or `pip install ziglang` for `light-vibes`), `clang` + `lld` (for `libleafiaaac.so` & `lpatch`), `zip`/`unzip`, and Android SDK (`platforms;android-19` and `build-tools`).
- Sibling checkout of [`lemoyneiv/HMG-API`](https://github.com/lemoyneiv/HMG-API) at `../HMG-API` (or set `HMGAPI=/path/to/HMG-API`).
- Persistent signing keys in `~/.hmgapi/` (or set `HMGAPI_KEY=/path/to/author.key`).

### Build Command

```sh
./build-suite.sh
```

`build-suite.sh` runs:
1. `patches/prepare-mods.py` on clean pinned checkouts of `wireless-carplay-aa`, `aa-dpi`, and `light-vibes`, while SHA-256 and ECDSA-signature verifying the official upstream releases of `Open-HMG/hmgapi-adb` (`1.0.1-rc.1`) and `AwsomeFox/egmp-aa-ev-hmg-api` (`0.8.2`), and building `hmgapi-comfort` (`1.1.8`) from [`lemoyneiv/hmgapi-comfort`](https://github.com/lemoyneiv/hmgapi-comfort) against the stock upstream `Open-HMG/HMG-API` SDK.
2. Offline hook-point & floor checks (`check-points.py`) against the `1.1.1-rc.11` SDK catalogue.
3. Unit & engine tests for every built mod (`CpTurnsTest`, `AaTurnsTest`, `AaSupportTest`, `CeevRecipeTest`, `KeyframesTest`, `FocusPolicyTest`, `leafiaaac_test`, `ioniq6/integration.py`, `DpiTest`, `ComfortPolicyTest` + `hmgapi-comfort/test.sh`, `light-vibes` `test_lamp` + `ReportTest` + beat/preset DSP analysis).
4. Gen5W KitKat toolbox shell verification (`check-scripts.sh`) on every head-unit script (`usb/NEMESIS.sh`, `open.sh`, `svc/start.sh`, `lights.sh`, `offload.sh`).
5. Multi-mod & standalone integration verification (`tools/integration-test.sh`) installing and applying all 6 mods simultaneously on simulated US Ioniq 5 `260507` and `260903` units (`NEEV`, `NEEV23`, `NEEV23SE`), verifying `WelcomeDaemon`, cellular isolation + `hmgapi-openlink` allowlist behavior, byte-identical stock restoration, and standalone `Comfort` / `Light Vibes` installs on clean head-unit fixtures.

---

## Repository Layout

- [`build-suite.sh`](build-suite.sh) — Master build, test, signing, official upstream verification, integration-test, standalone packaging, and USB packaging script
- [`patches/prepare-mods.py`](patches/prepare-mods.py) — Deterministic patcher for `wireless-carplay-aa`, `aa-dpi`, and `light-vibes`
- [`patches/wireless-focus/`](patches/wireless-focus/) — Projection vs. Bluetooth Audio focus arbitrator (`FocusPolicy.java`, `FocusGuard.java`, `FocusPolicyTest.java`)
- [`patches/wireless-pr16-no-watchdog.patch`](patches/wireless-pr16-no-watchdog.patch) — Extracted PR `#16` CarPlay freeze recovery + cluster TBT + compass restore (without `Watchdog`)
- [`usb/NEMESIS.data`](usb/NEMESIS.data), [`usb/NEMESIS.sh`](usb/NEMESIS.sh) — One-tap NEMESIS USB installer
- [`tools/installer-test.sh`](tools/installer-test.sh) — Off-car unit test for Leafia/Evergreen precursor cleanup, `HmgApi.apk` fallback, Zygote boot-guard recovery, `adb.json` & `hmgapi-openlink` cellular seeding, per-mod author UUID migration, `aa-dpi` settings migration, and KitKat shell verification
- [`tools/integration-test.sh`](tools/integration-test.sh) — Multi-mod `260507` + `260903` integration, standalone mod verification, & stock-restore test suite

## License

GNU Affero General Public License v3.0 (`AGPL-3.0`). See [`LICENSE`](LICENSE).
