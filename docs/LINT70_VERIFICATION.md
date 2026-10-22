# Android 1.4.2-70 verification

## Build checkpoint (2026-10-22)

- `lintOssDebug`: zero findings, checkAllWarnings/showAll/warningsAsErrors retained.
- `testOssDebugUnitTest`: 159 tests in 34 suites, zero failures/errors/skips.
- `assembleOssDebug` and `assembleOssDebugAndroidTest`: successful.
- build-01 container, JDK 17, Android SDK/build-tools 35, one Gradle worker,
  offline dependencies, arm64-v8a. Combined build: 31m30s.
- 6,947 source input hashes matched the remote build workspace.
- Native v1.13.13-plus-68 and libgojni.so unchanged from accepted version 69.
- Signed APK: package com.nb4a.plus.debug, versionCode 335, versionName 1.4.2-70.
- Same signing certificate as installed 69; zipalign 16 KiB check passed.
- APK SHA-256: `2dcbb8b9a53ee60dac3d7b18ef509601689fcdff7d7e3094226575c2e3fa5342`.

The build-source checkout URL now points at nd4y/sing-box, where the exact pinned
native68 commit f4cd71253d9ce90126dcd78d58dd7428a6e622a0 is published. This shell
metadata change was verified after the Android build and does not alter APK code.
The existing ByeDPI patch covers the accepted six dependency file modifications.
Legacy compiler deprecation warnings are outside the original 131 lint findings.
See LINT_REVIEW.md for 92 fixes and 39 individually scoped exceptions, including
32 dependency recommendations retained with the owner's approval.

## Physical device acceptance

Pending first update through Obtainium and the subsequent Pixel test run. This
build checkpoint is not evidence of completed physical-device acceptance. Private
configuration backups and raw device diagnostics are kept outside the repository.
