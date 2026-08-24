# Vendored Carify dependency

`APKEditor-1.4.8.jar` is APKEditor 1.4.8 from the official REAndroid project:

- Source: https://github.com/REAndroid/APKEditor/tree/V1.4.8
- Release: https://github.com/REAndroid/APKEditor/releases/tag/V1.4.8
- License: Apache-2.0 (https://github.com/REAndroid/APKEditor/blob/V1.4.8/LICENSE)
- Upstream SHA-256: `026906af28497577496a3e1f5054a878a7cf9c1b3889626882d87ea88d09c20f`
- Vendored SHA-256: `693d2dbec4a3e3b36404a7ba4ee8066a14c1e90c0786851b9bf3067a3f188df1`

The vendored JAR is the upstream fat JAR with only its compile-time Android framework stubs
(`android/**`) and duplicate JSR-305 annotation stubs (`javax/annotation/**`) removed. Android
provides the former at runtime and the app already provides the latter; retaining either causes
duplicate-class failures. APKEditor itself is otherwise unchanged.

The app invokes APKEditor's in-process `Main.execute` API to decode, merge, and rebuild a user's
chosen installed app entirely on the phone. It never uploads an APK.
