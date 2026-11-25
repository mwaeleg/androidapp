# ZipRarBox (Android ZIP & RAR Archiver)

An Android application written in **Kotlin** and **Jetpack Compose (Material 3)** for compressing files into ZIP archives and inspecting/extracting ZIP and RAR archives using Android's Storage Access Framework (SAF).

## Features

- **Compress to ZIP** ([Zip4j 2.11.6](https://mvnrepository.com/artifact/net.lingala.zip4j/zip4j/2.11.6)): Select multiple files, pick a compression level (`Store`, `Fast`, `Normal`, `Maximum`), and optionally encrypt with **AES-256**.
- **Inspect & Extract ZIP and RAR** ([Junrar 8.1.2](https://deps.dev/maven/com.github.junrar%3Ajunrar)): Preview archive entries (file sizes, directories, encryption indicators) and extract safely into any user-selected folder.
- **Scoped Storage Ready**: Uses `ActivityResultContracts` (`OpenMultipleDocuments`, `CreateDocument`, `OpenDocument`, `OpenDocumentTree`) with zero legacy storage permissions required.
- **Zip Slip Protection**: Sanitizes archive paths and validates canonical destination paths before writing extracted files.
- **Open-With Integration**: Registers an `ACTION_VIEW` intent filter for `.zip` and `.rar` MIME types.

## Important Notes on RAR5 and RAR Creation

- **RAR5 Extraction**: Pure-JVM `junrar` supports RAR v1–v4 archives. `ArchiveEngine` detects RAR5 signatures (`Rar!\x1A\x07\x01\x00`) and reports a clear message. To enable RAR5 extraction, add native bindings such as [7-Zip-JBinding-4Android](https://github.com/omicronapps/7-Zip-JBinding-4Android).
- **Creating `.rar` Files**: The RAR compression format is proprietary to RARLAB (WinRAR), so no open-source RAR encoder exists for Android. Compression outputs standard `.zip` archives with optional AES-256 encryption.

## Build & Run

1. Unzip `ZipRarBox-Android.zip` and open the `ZipRarBox` folder in **Android Studio** (Ladybug or newer, JDK 17+).
2. Let Gradle sync download dependencies (`compileSdk 35`, `minSdk 26`).
3. Run the `app` configuration on an emulator or physical Android device (Android 8.0+), or run unit tests with `./gradlew testDebugUnitTest`.

### Build an APK in the Cloud (GitHub Actions — No Android Studio Needed)

1. Unzip `ZipRarBox-Android.zip` and push the contents of the `ZipRarBox` folder (including `.github/workflows/build-apk.yml`) to a GitHub repository.
2. Open the **Actions** tab in your repository and click the **Build Debug APK** workflow run (or trigger it manually with **Run workflow**).
3. Once it finishes, download **ZipRarBox-debug-apk** from the **Artifacts** section at the bottom of the run page and install `app-debug.apk` on your Android device.
