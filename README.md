# Gen5W Unified HMG-API Suite (US Hyundai Ioniq 5 — `260507` & `260903`)

Conflict-free, single-USB HMG-API suite built for **US Hyundai Ioniq 5** (`NEEV`, `NEEV23`, `NEEV23SE`) head units running **firmware `260507` or `260903`** with **NEMESIS (`>= 1.16`)** installed.

Mods that are unmodified upstream (`adb` and `egmp-aa-ev`) are bundled using their **official upstream-signed release archives** so they retain author-key continuity with the NEMESIS Package Store and HMG-API Community Catalogue, while custom/patched mods (`wireless-carplay-aa`, `aa-dpi`, `hmgapi-comfort`, `light-vibes`) are signed with `lemoyneiv` (`28ad71e0-07c0-8e61-8bf3-380cc71f9280`).

---

## Included Components

| Order | Component | Version | Authors | Source & Key Details |
|---:|---|---|---|---|
| Core | **[`lemoyneiv/HMG-API`](https://github.com/lemoyneiv/HMG-API)** | `1.1.1-rc.24` | `VysseCodes`, `lemoyneiv` | Rebased onto upstream `Open-HMG/HMG-API` **`v1.1.0-rc.7` + `#370`** (`6dbe117`) and merges [`feat/tbt-trap-hookpoints`](https://github.com/lemoyneiv/HMG-API/tree/feat/tbt-trap-hookpoints) (`a55694c`: 5 `NavigationCluster` trap-frame hook points across all 412 catalogue points + `"wifi": {"projection": true}` & `Wifi.projectionAp()` boot SoftAP keeper coordinated with Home Wi-Fi & OpenLink Dark Standby) plus open PRs `#173` (BLE Central/GATT), `#293` (`ModeManager` & `MicomAudioManager` hook points), `#303` (launcher hook ordering & menu sync), `#304` (launcher `menu_list.new` & background `LauncherReload`), **Cold-Boot Fast-Start (`svc_tick` pre-`getprop` boot service launch + `fastStartProjectionStack()` zero-fork Binder pre-start + `BtcmFastConnect` `7.6 s` idle-timer bypass + `45 s` Driver Entry Grace + `Mode 2` SoftAP self-heal)**, **Connectivity SoftAP `ConnectivitySSID`/`ConnectivityPSK` credential preservation (`setWifiApEnabledForConnectivity(null, ...)`), `bt-standby-restore` cold-boot recovery**, and **redesigned `WifiManager` Home Wi-Fi engine, ADM `PwrKeep(1)` Pre-Latch, Away-from-Home Auto-Sleep, ADB over TCP while connected (`:5555`), Parked Session Flight Log, configurable Unplugged & Plugged-In Keep-Awake, & live UI** (`HomeWifiDaemon` + `HomeWifiKeepAwake` + `HomeWifiFlightLog`) and **Settings → Home Wi-Fi → Mobile data** per-mod `allow_cellular` switches |
| `01` | **[`wireless-carplay-aa`](https://github.com/lemoyneiv/hmgapi-wireless-cp-aa)** | `2.2.7` | `VysseCodes`, `lemoyneiv` | Built on [`feat/tbt-overhaul-and-cleanup`](https://github.com/lemoyneiv/hmgapi-wireless-cp-aa/tree/feat/tbt-overhaul-and-cleanup) (`084486a`, on `Open-HMG` `v2.2.0-rc.9`): **TBT & Cluster Compass Overhaul** (`NaviBridge` immediate DisplayType `8` idle compass & heading replay + non-destructive splicing of phone approach countdown bars into stock speed-limit trap frames, exact-tenths rounding & per-maneuver monotonic distance clamping eliminating `.4/.3/.2/.1 mi` oscillation + `250 ms` short-unit glide, dynamic native cluster & AR HUD intersection scenes `tbt_standard: true` by default, `AaNavState` full Android Auto route & EV charging-stop state decoding, Issue `#24` `recoverFromDipoStop` when switching CarPlay nav apps, `Wifi.projectionAp()`, popup Settings fix across `SYSTEM_POPUP`/`LAUNCHER`/`SETTINGS_APP`), **Quick-Stop Wireless Reconnect & Clean Parked Teardown (`ParkSession`: RAM-only per-boot `ByeByeRequest(1)` / `disconnectCarPlay()` + SoftAP+BT-ready `connectWirelessSession` / `restartBonjour` without persistent `Settings.Global` phone lock-out)**, **Projection Focus Arbitrator (`FocusGuard` / `FocusPolicy`, `#56`)** enabled by default (including post-Dark-Standby Projection reclaim over involuntary FM fallback), **Home Wi-Fi & Parked Keep-Awake `Bridge` SoftAP Guard**, `Keyframes` CarPlay freeze IDR recovery (`picture`), and PR `#28` (`wifi_direct` opt-in toggle) |
| `02` | **[`adb`](https://github.com/Open-HMG/hmgapi-adb)** | `1.0.1-rc.1` | `VysseCodes` *(official release)* | Unmodified official `Open-HMG/hmgapi-adb` release archive (`7251ce71-…`); `NEMESIS.sh` seeds `/data/hmgapi/settings/adb.json` (`{"usb": false}`) when absent so USB flash drives and wired CarPlay/Android Auto keep working out of the box while turning off the Engineering Mode `eth0` subnet conflict |
| `03` | **`aa-dpi`** | `0.3.0` | `eliotwass`, `AwsomeFox`, `lemoyneiv` | Upgraded to `eliotwass/hmg-aa-dpi` `0.3.0`: target **200 DPI**, **tap/scroll limit off** (`tap_limit: false`), **60 fps** video window (`video_window: 6`), `nemesis_conflicts: ["aa.dpi"]` |
| `04` | **[`egmp-aa-ev`](https://github.com/AwsomeFox/egmp-aa-ev-hmg-api)** | `0.8.2` | `willemcvu`, `AwsomeFox` *(official release)* | Unmodified official `AwsomeFox/egmp-aa-ev-hmg-api` release archive (`839375d3-…`); Google Maps EV routing over Android Auto (live SoC, range, charge curves, automatic DC fast-charger battery preconditioning, metric/imperial settings) |
| `05` | **[`hmgapi-comfort`](https://github.com/lemoyneiv/hmgapi-comfort)** | `1.1.10` | `lemoyneiv` *(standalone repo)* | **Standalone native HMG-API mod** replacing both `hkmc-coldstart-heat` and `hkmc-factory82`: dedicated `WelcomeDaemon` (`user=system, start=android`) for cold-boot & QuickBoot STR welcome screen dismissal (preserving `startup_notice` quota across Bluelink Remote Climate preconditioning) + `12 s` cold-boot AA/CarPlay projection runway + zero-IPC dark-standby dormancy + native Metric (`°C`) & Imperial (`°F`) support + 3-tier cold heating + 3-tier warm seat ventilation + independent wheel threshold + passenger/rear policies + widescreen **Material 3** Day/Night control center |
| `06` | **[`light-vibes`](https://github.com/lemoyneiv/light-vibes/tree/intense-effects)** | `0.7.0` | `Kyle (dibble9012)`, `lemoyneiv` | **Intense Effects** branch: music- & speed-reactive Gen5W ambient lighting (`lightvibesd` static ARMv7 + `LightVibes.apk` Material 3 preset studio, Niro EV `sg2ev_us` support, USB-only LED testing & stock color capture, zero `/system` edits) |
| `07` | **[`eq-engine`](https://github.com/alexkozler/Gen5W-EQ-Engine)** | `0.7.8` | `alexkozler`, `lemoyneiv` | Hardened HMG-API integration of **Gen5W-EQ-Engine** (`2feb050`): **native Gen5W Home Screen app icon** + ★ button support, zero-data-loss migration from standalone NEMESIS `eqengine`, zero-fork early `hmgapid` boot overlay + early-boot `mediaserver` reload fallback, silent parked wake dormancy, `0..12 s` cold-boot AA/CarPlay runway + `8 s` post-`SoundSettingApp` `48 kHz` `Hold` settle, `tmpfs` `/dev/eqengine` zero-flash-wear live meter IPC, 10-band parametric / 5-band graphic EQ, CarPlay TinyALSA EQ, VHQ sinc resampler, & lossless `48 kHz` `I2S_3` output |

---

## One-Tap USB Installation (Recommended)

Assumes **NEMESIS (`>= 1.16`)** is already installed on the head unit:

1. Format a USB flash drive as **FAT32** (MBR).
2. Extract **`dist/gen5w-suite-usb.zip`** directly to the **root** of the USB drive so `NEMESIS.data`, `NEMESIS.sh`, `packages/`, and `HMGAPI/` sit at the top level.
3. With the car powered on (`READY` or Utility Mode) and the infotainment booted, insert the USB stick into the data USB port.
4. When NEMESIS asks to execute the USB item, tap **Yes** once and wait ~30 seconds for the completion dialog/toast:
   - Automatically backs up any existing standalone **EQ Engine** presets (`/data/eqengine/settings/*`), detects and uninstalls any old **Leafia** or **Evergreen** precursor packages/runtimes (`wireless.carplay.aa`, `carplay.wifi.audio`, `*leafia*`, `*evergreen*`, `/data/leafia/uninstall.sh`, `/data/evergreen/uninstall.sh`) plus conflicting standalone NEMESIS packages (`aa.dpi`, `aa.ev.model`, `coldstart.heat`, `local.hkmc.factory82`, `wireless.projection`, `enhanced.projection`, `projection.tbt`, `adb.tcp`, `light.vibes`, `eqengine`, `ambient.sound`) and restores their stock backups first.
   - Installs or updates **HMG-API Core `1.1.1-rc.24`** (installing `HmgApi.apk` cleanly without requiring a separate NEMESIS Launch step) and explicitly resets `/data/hmgapi/hook/starts` so hooks recover cleanly even if the 3-strike Zygote boot guard previously tripped.
   - Enables and verifies the car's hook map (`260507` or `260903`), ensures `block_ambient_light` is `off`, and pre-grants owner permissions for comfort and EV preconditioning vehicle commands.
   - Seeds `/data/hmgapi/settings/adb.json` (`{"usb": false}`) when absent, installs all 7 signed mods in dependency order (preserving existing `/data/hmgapi/settings/*.json` across per-mod author-key migrations and restoring migrated EQ Engine presets, while setting `aa-dpi` to `200 DPI` + `tap_limit: false` + `60 fps`), and non-destructively seeds `"hmgapi-openlink"` into `/data/hmgapi/settings/hmgapi.json` `overrides.allow_cellular` once so OpenLink has LTE access out of the box (toggleable live anytime in **HMG-API → Settings → Home Wi-Fi → Mobile data**).
   - Runs **`hmgapid engine apply` once**, installs companion APKs (`Comfort.apk`, `LightVibes.apk`, and `EQEngine.apk`), and syncs **NEMESIS Manager → My Packages**.
5. **Restart the head unit once** (pinhole reset or power cycle) to activate all Zygote hooks and mods.

### Optional Flag Files on the USB Stick (`HMGAPI/`)

| File on USB | Effect |
|---|---|
| `HMGAPI/FORCE` | *(Included by default)* Allows HMG-API core to install even if `app_process` has a newer stock MD5 |
| `HMGAPI/RESET_SETTINGS` | Resets all 7 suite mods, EQ Engine presets, and OpenLink cellular allowlist seeding back to suite defaults during install |
| `HMGAPI/ENABLE_ALL` | Re-enables any mod that was previously toggled off |
| `HMGAPI/AUTO_REBOOT` | Automatically reboots the head unit 2 seconds after a successful install |
| `HMGAPI/suite-install.log` | Written back to the USB stick automatically at the end of installation |

### Other Generated USB Layouts

- **`dist/gen5w-suite-nemesis-store-usb.zip`**: Interactive NEMESIS `type=list` stick — opens **NEMESIS Manager → Package Store → USB** so packages can be inspected or installed one by one.
- **`dist/gen5w-suite-hmgapi-updates-usb.zip`**: Contains `HMGAPI/FORCE` and `HMGAPI/updates/*.hmgapi.zip` for updating individual mods from inside the **HMG-API** app.

---

## Standalone Packages (`Comfort`, `Light Vibes` & `EQ Engine`)

**Comfort & Startup** has its own dedicated standalone repository at **[`lemoyneiv/hmgapi-comfort`](https://github.com/lemoyneiv/hmgapi-comfort)** (built directly against stock upstream `Open-HMG/HMG-API` `>= 1.0.0`), and the **Home Screen Tile Manager** has moved out of HMG-API Core into its own standalone repository at **[`lemoyneiv/hmgapi-tile-manager`](https://github.com/lemoyneiv/hmgapi-tile-manager)**. `./build-suite.sh` also outputs ready-to-share standalone archives in **`dist/standalone/`**:

| App | One-Tap NEMESIS USB Stick | HMG-API Updates USB Stick | Direct HMG-API / NEMESIS Store Package |
|---|---|---|---|
| **[Comfort (`1.1.10`)](https://github.com/lemoyneiv/hmgapi-comfort)** | `hmgapi-comfort-v1.1.10-nemesis-usb.zip` | `hmgapi-comfort-v1.1.10-hmgapi-usb.zip` | `hmgapi-comfort-v1.1.10.hmgapi.zip` / `-nemesis-package.zip` |
| **Light Vibes (`0.7.0`)** | `light-vibes-v0.7.0-nemesis-usb.zip` | `light-vibes-v0.7.0-hmgapi-usb.zip` | `light-vibes-v0.7.0.hmgapi.zip` / `-nemesis-package.zip` |
| **[EQ Engine (`0.7.8`)](https://github.com/alexkozler/Gen5W-EQ-Engine)** | `eq-engine-v0.7.8-nemesis-usb.zip` | `eq-engine-v0.7.8-hmgapi-usb.zip` | `eq-engine-v0.7.8.hmgapi.zip` / `-nemesis-package.zip` |

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
1. `patches/prepare-mods.py` and `patches/prepare_eq_engine.py` on clean pinned checkouts of `wireless-carplay-aa`, `aa-dpi`, `light-vibes`, and `Gen5W-EQ-Engine`, while SHA-256 and ECDSA-signature verifying the official upstream releases of `Open-HMG/hmgapi-adb` (`1.0.1-rc.1`) and `AwsomeFox/egmp-aa-ev-hmg-api` (`0.8.2`), and building `hmgapi-comfort` (`1.1.10`) from [`lemoyneiv/hmgapi-comfort`](https://github.com/lemoyneiv/hmgapi-comfort) against the stock upstream `Open-HMG/HMG-API` SDK.
2. Offline hook-point & floor checks (`check-points.py`) against the `1.1.1-rc.24` SDK catalogue.
3. Unit & engine tests for every built mod (`CpTurnsTest`, `AaTurnsTest`, `AaNavStateTest`, `AaText`, `AaSupportTest`, `CeevRecipeTest`, `KeyframesTest`, `FocusPolicyTest`, `leafiaaac_test`, `ioniq6/integration.py`, `DpiTest`, `ComfortPolicyTest` + `hmgapi-comfort/test.sh`, `light-vibes` `test_lamp` + `ReportTest` + beat/preset DSP analysis, and `eq-engine` ASan/UBSan `host_test`, `vhq`, `cpwtest`, `tests/boot`, & `test-hmgapi.sh`).
4. Gen5W KitKat toolbox shell verification (`check-scripts.sh`) on every head-unit script (`usb/NEMESIS.sh`, `open.sh`, `svc/start.sh`, `lights.sh`, `offload.sh`, `bin/boot.sh`, `bin/act.sh`).
5. Multi-mod & standalone integration verification (`tools/integration-test.sh`) installing and applying all 7 mods simultaneously on simulated US Ioniq 5 `260507` and `260903` units (`NEEV`, `NEEV23`, `NEEV23SE`), verifying `WelcomeDaemon`, TBT trap-frame hooks & `wifi.projection` boot keeper, `ParkSession` `car_events`, `eq-engine` Home Screen tile & early boot service, cellular isolation + `hmgapi-openlink` allowlist behavior, byte-identical stock restoration, and standalone `Comfort` / `Light Vibes` / `EQ Engine` installs on clean head-unit fixtures.

---

## Repository Layout

- [`build-suite.sh`](build-suite.sh) — Master build, test, signing, official upstream verification, integration-test, standalone packaging, and USB packaging script
- [`patches/prepare-mods.py`](patches/prepare-mods.py), [`patches/prepare_eq_engine.py`](patches/prepare_eq_engine.py) — Deterministic patchers for `wireless-carplay-aa`, `aa-dpi`, `light-vibes`, and `Gen5W-EQ-Engine`
- [`patches/wireless-focus/`](patches/wireless-focus/) — Projection vs. Bluetooth Audio focus arbitrator + quick-stop wireless session teardown/reconnect (`FocusPolicy.java`, `FocusGuard.java`, `ParkSession.java`, `FocusPolicyTest.java`)
- [`patches/wireless-picture/`](patches/wireless-picture/) — CarPlay video decoder freeze IDR recovery (`Keyframes.java`, `KeyframesTest.java`)
- [`usb/NEMESIS.data`](usb/NEMESIS.data), [`usb/NEMESIS.sh`](usb/NEMESIS.sh) — One-tap NEMESIS USB installer
- [`tools/installer-test.sh`](tools/installer-test.sh) — Off-car unit test for Leafia/Evergreen precursor cleanup, standalone `eqengine` preset migration, `HmgApi.apk` fallback, Zygote boot-guard recovery, `adb.json` & `hmgapi-openlink` cellular seeding, per-mod author UUID migration, `aa-dpi` settings migration, and KitKat shell verification
- [`tools/integration-test.sh`](tools/integration-test.sh) — Multi-mod `260507` + `260903` integration, standalone mod verification, & stock-restore test suite

## License

GNU Affero General Public License v3.0 (`AGPL-3.0`). See [`LICENSE`](LICENSE).
