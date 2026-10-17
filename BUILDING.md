# Building NekoBox with NetBird

The application needs a newly built `app/libs/libcore.aar`; a stock NekoBox AAR
does not include the NetBird adapter. Use the debug application ID for testing.

## Source dependencies

From the application repository, run:

```bash
bash buildScript/lib/core/get_source.sh
```

This creates sibling checkouts of sing-box, libneko, amneziawg-go, golang/net,
MasterDnsVPN-plus, utls, byedpi and netbird. Exact upstream commits are in
`buildScript/lib/core/get_source_env.sh`. It also applies the sing-box AmneziaWG
and HTTP/2 patches and the application ByeDPI and NetBird patches. Repeated runs
recognize applied patches. A revision change refuses to overwrite modified
dependencies. Keep the sibling directories together when moving this workspace.

The main Go module copies NetBird's required module replacements because Go
does not inherit `replace` directives from dependency modules. Run `go mod tidy`
in `libcore` after intentionally updating the dependency graph and review both
`go.mod` and `go.sum`.

## Android core

Prerequisites: Docker, a Linux Android SDK with platform 35, a Linux NDK
(27.3.13750724 is the upstream default), Git and Bash. A Windows NDK cannot be
mounted into the Linux build container: its compiler executables are for a
different operating system. Export `ANDROID_HOME` and `ANDROID_NDK_HOME` to the
matching Linux installations. Provision tools without restarting existing WSL,
Docker or device workloads.

```bash
export ANDROID_HOME=/path/to/linux/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.3.13750724"
bash buildScript/lib/assets.sh
bash buildScript/lib/core.docker.sh
```

The Docker build uses Go 1.26.7, required by the pinned NetBird client, with the
existing CLOCK_BOOTTIME runtime patch. It preserves WireGuard and AmneziaWG
keepalive timer behavior on older Android kernels. The source toolchain build
uses two workers by default to bound memory pressure. Verify patch application
and the native build before distributing an APK; merely changing the toolchain
version is not evidence of a successful build. The wrapper copies the resulting
AAR into `app/libs` and `build/aar-docker`.

Both native build paths use an external linker with `-Wl,-z,max-page-size=16384`
for Android 16 KB page compatibility, and `-checklinkname=0`, matching NetBird's upstream
Android build. Its `github.com/wlynxg/anet` dependency accesses Go's network
interface zone cache to work around Android interface enumeration restrictions;
Go 1.23 and later otherwise reject the link. The pinned anet cache layout was
checked against the pinned Go runtime. Recheck this when upgrading Go.
See the [NetBird Android build script](https://github.com/netbirdio/android-client/blob/main/build-android-lib.sh)
and [anet's compatibility notes](https://github.com/wlynxg/anet).

## Application and tests

Use Android Studio's JBR and an SDK containing platform 35 and build-tools
35.0.1. `local.properties` may specify `sdk.dir` and must stay untracked.

```bash
./gradlew --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g \
  :app:kspOssDebugKotlin :app:testOssDebugUnitTest :app:assembleOssDebug :app:lintOssDebug
```

On Windows, the core can also be built without a Linux container. Apply
`buildScript/lib/core/goruntime-boottime-over-monotonic.diff` to the Go 1.26.7
source archive (use LF line endings), then run `src/make.bat` with
`GOROOT_BOOTSTRAP` pointing at an installed Go distribution, `GOMAXPROCS=2` and
`GOFLAGS=-p=2`. Keep this custom toolchain separate from the system installation.
With a Windows NDK and SDK installed, run:

```powershell
./buildScript/lib/core.windows.ps1 `
  -GoRoot C:/build/go-patched `
  -AndroidSdk C:/Android/Sdk `
  -AndroidNdk C:/Android/Sdk/ndk/27.3.13750724 `
  -JavaHome 'C:/Program Files/Android/Android Studio/jbr'
```

The Windows helper verifies the patched runtime source and builds arm64 and
x86-64 by default; use `-Target android` for all Android architectures. Then
use `gradlew.bat -PnativeAbis=arm64-v8a,x86_64` for the application when building
the default two native architectures. This limits both APK splits and the
universal APK to architectures actually present in the AAR. Omit the property
only when the AAR includes all four supported architectures. Run only one Gradle invocation for this
checkout at a time. For bounded Windows verification, use:

```powershell
./gradlew.bat --no-daemon --max-workers=2 `
  '-Dorg.gradle.jvmargs=-Xmx2g -XX:+UseParallelGC -Dfile.encoding=UTF-8' `
  '-Pkotlin.compiler.execution.strategy=in-process' `
  '-PnativeAbis=arm64-v8a,x86_64' `
  :app:assembleOssDebug :app:testOssDebugUnitTest :app:lintOssDebug
```

The KSP task generates Room schema snapshots; include a new
schema when adding a database migration.

The Windows core helper fingerprints native sources before and after binding,
fails if they change during compilation, and writes `app/libs/libcore.buildinfo.json`
with source and AAR SHA256 values. Preserve that file alongside validation
artifacts. A source fingerprint does not establish successful network enrollment
or Android device testing.

For protocol and transport tests:

```bash
cd libcore
CGO_ENABLED=1 GOMAXPROCS=1 GOMEMLIMIT=1GiB \
  go test -p=1 -mod=readonly -ldflags=-checklinkname=0 -timeout 3m ./protocol/netbird
CGO_ENABLED=1 GOMAXPROCS=1 GOMEMLIMIT=1GiB \
  go test -p=1 -mod=readonly -ldflags=-checklinkname=0 \
  -run '^TestCloseCancelsBeforeWaitingForStartupLock$' -timeout 2m .
```

The independent Linux network fixture and its bounded run command are documented
in [tests/netbird-e2e/README.md](tests/netbird-e2e/README.md). It uses local
management, signal and relay services, a real VLESS hop, and NetBird peers.
Its result is separate from Android device and production TLS verification.

Enrollment against the intended management server, production TLS, a WebSocket-capable
relay, and Android Wi-Fi/LTE transitions still require validation on the target
network and device.
