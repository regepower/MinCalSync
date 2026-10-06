# MinCalSync

A minimal Android app to automatically sync events between two calendars every 24 hours.

## Features

- 🔄 Automatic calendar synchronization via WorkManager
- 📅 Support for any local calendars (Exchange, Google, etc.)
- ⚙️ Configurable sync interval (in hours)
- 🔐 Uses CalendarProvider API (no OAuth needed)
- 🎨 Material Design 3 UI with Jetpack Compose
- 📝 Logging with Timber

## Requirements

- Android 9.0 (API 28) or higher
- Calendar app installed with configured calendars
- Calendar permissions granted

## Installation

### From GitHub Releases

Download the latest APK from [Releases](https://github.com/regepower/MinCalSync/releases) and install it.

### Building from Source

```bash
git clone https://github.com/regepower/MinCalSync.git
cd MinCalSync
gradle assembleRelease   # Gradle 8.11.1, JDK 17
```

The APK will be generated at `app/build/outputs/apk/release/app-release-unsigned.apk`.

## Usage

1. Open the app
2. Grant calendar read/write permissions when prompted
3. Select source calendar (e.g., Exchange calendar from Outlook)
4. Select target calendar (e.g., Google Calendar)
5. Set sync interval (default: 24 hours)
6. Tap "Start Sync"

The app will now automatically sync events at the specified interval in the background using WorkManager.

## How It Works

- **One-way mirror** of the source calendar into the target calendar, window: 30 days back, 365 days ahead
- **Recurring events** are read via the `Instances` table and copied as single events (moved/cancelled occurrences stay correct)
- **Ownership tracking**: MinCalSync remembers locally which target events it created. Only those are ever updated or deleted, and only if they still carry the title/start/end it wrote. Events you add to the target calendar yourself are never touched.
- **No erase-and-rebuild**: an interrupted sync never empties the target calendar
- Copies older than the window are kept, just no longer updated
- Deleting a copy in the target calendar: it is re-created on the next sync (mirror semantics)

## Permissions

- `READ_CALENDAR` - Read events from source calendar
- `WRITE_CALENDAR` - Write events to target calendar
- `INTERNET` - (Optional) For future cloud sync features
- `RECEIVE_BOOT_COMPLETED` - Restore sync worker on device restart

## Architecture

No libraries: framework APIs only, release APK is a few dozen KB.

- **MainActivity.kt** - UI built from plain views, Material You colors (dynamic on Android 12+)
- **sync/CalendarMirror.kt** - Mirror logic (create/update/delete with ownership checks)
- **sync/MirrorStore.kt** - Local record of which target events MinCalSync owns
- **sync/SyncRunner.kt** - One sync run, serialized, writes the status line
- **sync/SyncJobService.kt** / **sync/SyncScheduler.kt** - Periodic sync via JobScheduler (persisted across reboots)

Build: GitHub Actions (`lintDebug` + signed `assembleRelease`), artifact `MinCalSync-release`.

## Troubleshooting

### No calendars appear in the dropdown

Make sure:
- Your calendars are configured in the native Calendar app
- You've granted the app calendar permissions
- Your calendar names match exactly

### Sync not working

Check logs in Logcat:
```bash
adb logcat | grep MinCalSync
```

Enable battery optimization exemption:
- Settings → Apps → MinCalSync → Battery → Allow unrestricted battery usage

## License

[GPL-3.0](LICENSE) – free software: use, modify and share it; derived versions must also be licensed under GPL-3.0.

## Author

Built by Trolle (@regepower)

## Support

This app is smaller than a photo. Support its development on [Liberapay](https://liberapay.com/regepower/donate) or [GitHub Sponsors](https://github.com/sponsors/regepower).
