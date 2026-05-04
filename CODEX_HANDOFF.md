# Clash Mi VPN Service Handoff

Last updated: 2026-05-04

This document records the current state of the open replacement for the old
closed `libclash-vpn-service` bridge, so a future session can resume work
without rediscovering the same details.

## Goal

Use the existing Clash Mi Flutter client, but replace the closed VPN bridge with
this sibling project:

- app repo: `/Users/cyenx/Library/CloudStorage/Dropbox/personal/code/clashmi`
- bridge repo: `/Users/cyenx/Library/CloudStorage/Dropbox/personal/code/clashmi-vpn-service`
- core fork: `https://github.com/cyenxchen/mihomo`
- core checkout: branch `Meta`, commit `4a99b6a29c22f9849d949430ba38cb0639bf8ac2`

The MVP target is Android first: start VPN, open TUN, pass fd into mihomo, stop,
and expose traffic/connections to Clash Mi. iOS is intentionally left for later.

## Current Status

Android MVP was previously verified on a real phone. The latest review follow-up
has been verified with static checks, Go tests, Flutter plugin tests, and an
Android debug APK build.

Implemented:

- Flutter package `clashmi_vpn_service` with the Dart-facing API expected by
  Clash Mi.
- Android `MethodChannel` plugin with `prepareConfig`, `start`, `stop`,
  `currentState`, `clashiApiTraffic`, and `clashiApiConnections`.
- Android `VpnService` that opens a TUN fd and starts the Go core.
- Android `VpnService` adds IPv6 TUN address and `::/0` route when Clash Mi
  config enables IPv6; this avoids IPv6 bypass on Android `system` stack.
- Android `VpnService` protects mihomo and Tailscale sockets before the VPN
  starts, and forwards non-VPN network snapshots for Tailscale route updates.
- Go `gomobile bind` wrapper around `cyenxchen/mihomo`.
- Android runtime default for `tun.stack` is `system`.
- Explicit `tun.stack: gvisor` is preserved.
- The Android binary is built with both system and gVisor support.
- The plugin now declares Android only. The iOS template files are kept for
  later work, but are not registered as a working implementation.

Important note: this directory is now its own git repository. The first commit is
`30e1c95 Initial import`; later work added IPv6 routing and Android Tailscale
review follow-ups.

## Main App Wiring

In the Clash Mi app:

- `pubspec.yaml` now points to `../clashmi-vpn-service`.
- imports were moved from `libclash_vpn_service` to `clashmi_vpn_service`.
- Android `compileSdkVersion` was moved to `android-36`.
- Android minSdk remains 26, matching the gomobile `-androidapi 26` build.
- Android default TUN stack in `ClashSettingManager.defaultTun()` is now:
  - Android/Linux: `system`
  - iOS/macOS: `gvisor`

Relevant app files:

- `pubspec.yaml`
- `pubspec.lock`
- `lib/app/local_services/vpn_service.dart`
- `lib/app/modules/clash_setting_manager.dart`
- `android/build.gradle.kts`

`pubspec.lock` changed substantially after dependency resolution. Review before
committing if a clean commit is needed.

## Bridge Project Layout

Key files:

- `lib/vpn_service.dart`: Dart API surface.
- `lib/vpn_service_platform_interface.dart`: platform interface.
- `lib/proxy_manager.dart`: compatibility types used by Clash Mi.
- `lib/state.dart`: state types.
- `android/src/main/kotlin/com/cyenx/clashmi/clashmi_vpn_service/ClashmiVpnServicePlugin.kt`: MethodChannel entrypoint.
- `android/src/main/kotlin/com/cyenx/clashmi/clashmi_vpn_service/ClashMiVpnService.kt`: Android `VpnService`.
- `android/src/main/kotlin/com/cyenx/clashmi/clashmi_vpn_service/PreparedVpnConfig.kt`: method-call config parsing.
- `android/src/main/kotlin/com/cyenx/clashmi/clashmi_vpn_service/ClashmiVpnRuntime.kt`: runtime state and start completion.
- `core/mobile/core.go`: Go wrapper exported through gomobile.
- `core/mobile/core_test.go`: runtime config tests.
- `core/mihomo`: local checkout of `cyenxchen/mihomo`.

Generated Android artifacts:

- `android/src/main/libs/clashmicore.aar`
- `android/libs/clashmicore.jar`
- `android/src/main/jniLibs/arm64-v8a/libgojni.so`

The main Clash Mi app currently publishes Android arm64 only while this bridge
ships only an arm64 gomobile core.

## Runtime Decisions

`tun.stack`

- Default is `system` on Android.
- A profile or final patch that explicitly sets `tun.stack: gvisor` is preserved.
- Build tags must include `with_gvisor,cmfa`; otherwise gVisor configs will not
  actually start.

TUN address

- Android `VpnService.Builder` creates:
  - address: `172.19.0.1/30`
  - IPv6 address when enabled: `fdfe:dcbe:9876::1/126`
  - DNS: `172.19.0.2`
  - route: `0.0.0.0/0`
  - IPv6 route when enabled: `::/0`
  - MTU: `4064`
- The Go wrapper normalizes Android runtime config to:
  - `dns.fake-ip-range: 172.19.0.1/16`
- This matters because mihomo system stack derives its TUN IPv4 address from
  `dns.fake-ip-range`. Without normalization, a profile with
  `198.18.0.8/16` caused mihomo to bind `198.18.0.8`, while Android had created
  `172.19.0.1/30`, so TUN failed to start.

TUN fd ownership

- Kotlin opens the TUN fd.
- Before calling `Clashmicore.start(...)`, fd ownership is treated as
  transferred to Go.
- Kotlin sets `tunFd = -1` before the Go call returns, so failure cleanup does
  not double-close the fd.
- Go closes the fd itself for early failures that happen before handing the fd
  into mihomo.
- This fixed Android fdsan aborts seen after start failure.

IPv6 routing

- Clash Mi passes `enable_ipv6` to the bridge from `ClashSettingManager`.
- When `enable_ipv6` is true, Android adds the IPv6 TUN address and `::/0`
  route before establishing the VPN.
- When `enable_ipv6` is false, Android does not add an IPv6 family address,
  route, or DNS server, so IPv6 is not allowed to fall through via an explicit
  IPv6 VPN route.
- The Go runtime also fills `tun.inet6-address` with
  `fdfe:dcbe:9876::1/126` if `ipv6: true` but the merged profile forgot to
  include an IPv6 TUN address.

## Build Commands

Run Go tests:

```sh
rtk go test ./...
rtk go test -tags with_gvisor,cmfa ./...
```

Build the Android AAR from the Go wrapper:

```sh
cd /Users/cyenx/Library/CloudStorage/Dropbox/personal/code/clashmi-vpn-service/core/mobile
rtk gomobile bind \
  -target=android/arm64 \
  -androidapi 26 \
  -tags with_gvisor,cmfa \
  -javapkg=com.cyenx.clashmi.core \
  -o ../../android/src/main/libs/clashmicore.aar .
```

Extract the jar and native library for the Android plugin:

```sh
cd /Users/cyenx/Library/CloudStorage/Dropbox/personal/code/clashmi-vpn-service
rtk mkdir -p android/libs android/src/main/jniLibs/arm64-v8a
rtk unzip -p android/src/main/libs/clashmicore.aar classes.jar > android/libs/clashmicore.jar
rtk unzip -p android/src/main/libs/clashmicore.aar jni/arm64-v8a/libgojni.so > android/src/main/jniLibs/arm64-v8a/libgojni.so
```

Build the Clash Mi debug APK:

```sh
cd /Users/cyenx/Library/CloudStorage/Dropbox/personal/code/clashmi
rtk flutter build apk --debug
```

Install to the connected Android device:

```sh
rtk "$HOME/Library/Android/sdk/platform-tools/adb" install -r build/app/outputs/flutter-apk/app-debug.apk
```

If multiple devices are attached, add `-s <device-id>`.

## Last Runtime Verification

Device used:

- model observed by adb/logs: `PLK110`
- device id used during testing: `3B15AL00ZNR00000`

Commands that passed:

```sh
rtk go test ./...
rtk go test -tags with_gvisor,cmfa ./...
rtk flutter build apk --debug
rtk "$HOME/Library/Android/sdk/platform-tools/adb" -s 3B15AL00ZNR00000 install -r build/app/outputs/flutter-apk/app-debug.apk
```

Runtime evidence:

- logcat showed `core started ... stack=System`.
- logcat showed `inet4Address` as `[172.19.0.1/30]`.
- `/configs` returned:
  - `tun.enable: true`
  - `tun.stack: "System"`
  - `tun.inet4-address: ["172.19.0.1/30"]`
  - `tun.file-descriptor` set to the Android fd.
- `dumpsys connectivity` showed VPN `CONNECTED` and `VALIDATED`.
- `curl -I https://www.gstatic.com/generate_204` on the phone returned
  `HTTP/1.1 204 No Content`.
- logcat had no `fdsan`, `Fatal signal`, `core start failed`, or
  `Start TUN listening error` after the latest fix.

When querying mihomo API from adb shell, quote the remote shell command so the
Authorization header is not split locally:

```sh
rtk "$HOME/Library/Android/sdk/platform-tools/adb" -s 3B15AL00ZNR00000 shell \
  'curl -sS --max-time 5 -H "Authorization: Bearer <secret-from-service.json>" http://127.0.0.1:9090/configs'
```

The secret is stored on device in:

```sh
run-as com.nebula.clashmi cat files/service.json
```

## Latest Review Verification

Commands that passed after the review fixes:

```sh
cd /Users/cyenx/Library/CloudStorage/Dropbox/personal/code/clashmi
rtk flutter analyze
rtk flutter build apk --debug
rtk unzip -l build/app/outputs/flutter-apk/app-debug.apk | rtk rg 'lib/.*/lib(gojni|flutter|app)\.so'

cd /Users/cyenx/Library/CloudStorage/Dropbox/personal/code/clashmi-vpn-service
rtk flutter analyze
rtk flutter test

cd /Users/cyenx/Library/CloudStorage/Dropbox/personal/code/clashmi-vpn-service/example
rtk flutter analyze
rtk flutter test

cd /Users/cyenx/Library/CloudStorage/Dropbox/personal/code/clashmi-vpn-service/core/mobile
rtk go test -tags with_gvisor,cmfa ./...

cd /Users/cyenx/Library/CloudStorage/Dropbox/personal/code/clashmi-vpn-service/example/android
rtk ./gradlew testDebugUnitTest
```

Current APK native library check only shows arm64 entries:

```text
lib/arm64-v8a/libflutter.so
lib/arm64-v8a/libgojni.so
```

The Clash Mi app has no `test/` directory at this point, so
`rtk flutter test` in the app repo exits with `Test directory "test" not found`.

## Bugs Found And Fixed

1. gVisor profile did not start.

Root cause: the AAR was initially built without `with_gvisor`, so mihomo parsed
the profile but did not actually start the TUN listener for gVisor.

Fix: build with `-tags with_gvisor,cmfa`.

2. Android system stack failed with `bind: cannot assign requested address`.

Root cause: system stack derived `198.18.0.8/30` from profile
`dns.fake-ip-range`, while Android Builder had created `172.19.0.1/30`.

Fix: normalize runtime `dns.fake-ip-range` to `172.19.0.1/16` in the Go wrapper.

3. Android process crashed with fdsan after a TUN start failure.

Root cause: Kotlin still owned and closed the fd after Go/mihomo had taken or
closed it.

Fix: after handing fd to `Clashmicore.start(...)`, Kotlin sets `tunFd = -1` and
lets Go own cleanup.

## Known Gaps / Next Work

- Add proper tests for Android Kotlin behavior if this package gets a real
  Android test harness.
- Decide whether to make TUN address configurable instead of fixed
  `172.19.0.1/30`.
- Implement or stub more of the old bridge API as Clash Mi reaches those code
  paths.
- Improve stop/restart edge cases and repeated start handling.
- Add non-arm64 Android targets if needed.
- Add iOS implementation later, likely through `NEPacketTunnelProvider`.

## Review Follow-Up On 2026-05-04

Review found three issues in the first app integration:

- non-Android platforms were wired to a plugin with only an Android
  implementation;
- APK output still contained non-arm64 native slices while `libgojni.so` existed
  only for arm64;
- the bridge SDK floor raised the app lockfile above the app's declared
  environment.

Follow-up fixes:

- `clashmi_vpn_service` now declares only Android in `pubspec.yaml`.
- non-Android `start`/`restart` returns a clear Android-only error instead of
  relying on a missing method channel.
- Clash Mi falls back to `path_provider.getApplicationSupportDirectory()` for
  profile storage when no app-group directory is available.
- Android packaging excludes non-arm64 JNI libs and build config limits native
  output to arm64.
- stale iOS/macOS `Podfile.lock` entries for the old bridge were removed.
- the app lockfile SDK floor is back to `Dart >=3.9.0` and
  `Flutter >=3.35.0`; several transitive platform packages are pinned with
  `dependency_overrides` to preserve that floor.
- the bridge example app SDK floor was also lowered to
  `Dart >=3.9.0` / `Flutter >=3.35.0`.
- Clash Mi now awaits `FlutterVpnService.prepareConfig(...)` before `start` or
  `restart`, preventing a race where Android could start before native
  `preparedConfig` was set.
- the bridge Android library minSdk is now 26, matching the app and the
  generated gomobile AAR.
- the bridge example Android app minSdk is also 26, so Gradle unit tests can
  merge the plugin manifest without `tools:overrideLibrary`.
- the stale old-package Android stub
  `android/src/main/kotlin/io/nebula/vpn_service/ClashVpnServiceImpl.kt` was
  removed.
- Android IPv6 leak review: the bridge now routes IPv6 through the VPN when
  Clash Mi enables IPv6, and tests cover both enabled and disabled config paths.
