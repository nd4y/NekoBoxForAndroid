# Lint review for Android 1.4.2-70

The 1.4.2-69 report contained 131 findings. This revision keeps checkAllWarnings,
showAll and warningsAsErrors enabled. No lint baseline or global check disable is added.
The native core remains v1.13.13-plus-68, byte-for-byte unchanged.

## Explicit exceptions

The owner approved retaining compatible dependency versions on 2026-10-22. The
32 dependency update hints are excluded only at their individual Gradle declarations.
These are update recommendations, not evidence that the pinned version is compatible
with the latest SDK, Kotlin metadata, Roomigrant processor or persisted config format.
Updating all libraries is a separate migration requiring its own compatibility tests.
This is not a claim that these dependencies are current or free of security issues.

Three Overdraw findings concern adapter/included child views. Lint inferred the
Activity startup window theme on these roots. Keep their required ripple/surface
background; each layout carries a local exception and explanation.

HardwareIds is excluded only on HwidGenerator.generate: existing subscription
providers depend on its stable app-scoped Android ID hash. Replacing it would rotate
an existing subscription identity. No identifier was added to telemetry or logs.

QueryAllPackagesPermission is excluded only on its manifest declaration. Arbitrary
per-app VPN include/exclude selection needs visibility of installed applications,
including packages without launcher intents. A finite queries list would break it.
The signature service permission retains its existing applicationId-scoped name.
PermissionNamingConvention is excluded only on that declaration: the source checker
does not expand the applicationId placeholder. A literal permission would collide
between separately signed side-by-side variants.

The rc4-md5-6 cipher display name is a protocol identifier, not a numeric range;
TypographyDashes is excluded only on this item, preserving its literal spelling.

## Changes

- Notification permission denial no longer fails VPN refresh or subscription work.
- Uncaught failures are reported locally then delegated to Android, including if
  diagnostics fails. Android service recovery/watchdog remains responsible for VPN.
- API 23 compatibility for boot state and Juicity Base64; explicit coroutine scope
  cancellation; receiver export flags supplied through ContextCompat with signature
  protection; existing generation/retry/TUN retention policies preserved.
- Remove strong static view references; store notification Application context.
  Reuse a dashboard WebView only in the same Activity context; recreate it on rotation.
- Fix enum translation mismatch, formatted WebDAV message, accessibility, font sizes,
  input type, typography, vector path precision, redundant SDK checks and KTX usage.

## Original report inventory

| Check | Findings | Disposition |
| --- | ---: | --- |
| AlwaysShowAction | 1 | Code/resource change |
| BatteryLife | 1 | Code/resource change |
| ClickableViewAccessibility | 1 | Code/resource change |
| DefaultLocale | 3 | Code/resource change |
| DefaultUncaughtExceptionDelegation | 1 | Code/resource change |
| Deprecated | 1 | Code/resource change |
| GradleDependency | 21 | Explicit scoped exception |
| HardwareIds | 1 | Explicit scoped exception |
| InconsistentArrays | 1 | Code/resource change |
| LabelFor | 2 | Code/resource change |
| LeanbackUsesWifi | 1 | Code/resource change |
| MemberExtensionConflict | 5 | Code/resource change |
| MissingPermission | 2 | Code/resource change |
| MissingSuperCall | 1 | Code/resource change |
| NewApi | 3 | Code/resource change |
| NewerVersionAvailable | 11 | Explicit scoped exception |
| ObsoleteSdkInt | 13 | Code/resource change |
| Overdraw | 3 | Explicit scoped exception |
| ParcelCreator | 1 | Code/resource change |
| PermissionImpliesUnsupportedHardware | 1 | Code/resource change |
| PermissionNamingConvention | 1 | Variant placeholder exception |
| QueryAllPackagesPermission | 1 | Explicit scoped exception |
| SmallSp | 4 | Code/resource change |
| StaticFieldLeak | 3 | Code/resource change |
| StringFormatCount | 1 | Code/resource change |
| TextFields | 1 | Code/resource change |
| TypographyDashes | 1 | Exact protocol identifier exception |
| TypographyEllipsis | 18 | Code/resource change |
| UnsafeOptInUsageError | 2 | Code/resource change |
| UnspecifiedRegisterReceiverFlag | 1 | Code/resource change |
| UseKtx | 17 | Code/resource change |
| UseRequiresApi | 6 | Code/resource change |
| VectorPath | 1 | Code/resource change |

## Pinned dependency recommendations

- A newer version of androidx.core:core-ktx than 1.9.0 is available: 1.19.1
- A newer version of androidx.recyclerview:recyclerview than 1.3.0 is available: 1.4.0
- A newer version of androidx.activity:activity-ktx than 1.10.1 is available: 1.13.0
- A newer version of androidx.fragment:fragment-ktx than 1.5.6 is available: 1.9.1
- A newer version of androidx.browser:browser than 1.5.0 is available: 1.10.0
- A newer version of androidx.swiperefreshlayout:swiperefreshlayout than 1.1.0 is available: 1.2.0
- A newer version of androidx.constraintlayout:constraintlayout than 2.1.4 is available: 2.2.2
- A newer version of androidx.navigation:navigation-fragment-ktx than 2.5.3 is available: 2.10.2
- A newer version of androidx.navigation:navigation-ui-ktx than 2.5.3 is available: 2.10.2
- A newer version of androidx.preference:preference-ktx than 1.2.0 is available: 1.2.1
- A newer version of androidx.appcompat:appcompat than 1.6.1 is available: 1.8.0
- A newer version of androidx.work:work-runtime-ktx than 2.8.1 is available: 2.12.0
- A newer version of androidx.work:work-multiprocess than 2.8.1 is available: 2.12.0
- A newer version of com.google.android.material:material than 1.13.0 is available: 1.14.0
- A newer version of androidx.camera:camera-camera2 than 1.4.2 is available: 1.6.2
- A newer version of androidx.camera:camera-lifecycle than 1.4.2 is available: 1.6.2
- A newer version of androidx.camera:camera-view than 1.4.2 is available: 1.6.2
- A newer version of androidx.room:room-runtime than 2.6.1 is available: 2.8.5
- A newer version of androidx.room:room-compiler than 2.6.1 is available: 2.8.5
- A newer version of androidx.room:room-ktx than 2.6.1 is available: 2.8.5
- A newer version of com.android.tools:desugar_jdk_libs than 2.0.3 is available: 2.1.5
- A newer version of org.jetbrains.kotlinx:kotlinx-coroutines-android than 1.6.4 is available: 1.11.0
- A newer version of com.google.code.gson:gson than 2.9.0 is available: 2.14.0
- A newer version of com.blacksquircle.ui:editorkit than 2.6.0 is available: 2.9.0
- A newer version of com.blacksquircle.ui:language-base than 2.6.0 is available: 2.9.0
- A newer version of com.blacksquircle.ui:language-json than 2.6.0 is available: 2.9.0
- A newer version of com.squareup.okhttp3:okhttp than 5.0.0-alpha.3 is available: 5.5.0
- A newer version of org.yaml:snakeyaml than 1.30 is available: 2.7
- A newer version of com.jakewharton:process-phoenix than 2.1.2 is available: 3.0.0
- A newer version of com.esotericsoftware:kryo than 5.2.1 is available: 5.7.1
- A newer version of com.google.guava:guava than 31.0.1-android is available: 33.7.2-android
- A newer version of org.json:json than 20240303 is available: 20260814

## Verification

See LINT70_VERIFICATION.md for final build, lint and physical device results.
