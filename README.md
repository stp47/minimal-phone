# minimal-phone

Android dialer, SMS and local contacts app.

## Features

- Dialer, call log, SIM selection and in-call screen
- SMS conversations, multipart sending and notifications
- Local contacts, vCard 3.0 import and export
- No INTERNET permission, analytics or cloud sync

MMS is not supported.

## Requirements

- Android 15 (API 35) or newer
- arm64-v8a for release builds
- Permissions and default dialer/SMS roles are assigned manually

## Build

Release builds run in GitHub Actions via `.github/workflows/release.yml`.

Required secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`.

Push a `v*` tag to build and attach the APK to a GitHub Release:

```sh
git tag v1.0
git push origin v1.0
```

Local debug build with Gradle 8.14.6 and JDK 17:

```sh
gradle assembleDebug
```

A local release build requires `keystore.properties` in the repository root
with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. The file is
ignored by git.

## License

GPL-3.0. See `LICENSE` and `NOTICE`.
