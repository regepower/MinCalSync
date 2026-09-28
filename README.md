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
./gradlew assembleRelease
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

- **CalendarProvider**: Reads/writes events directly from Android's calendar database
- **WorkManager**: Handles periodic background sync tasks reliably
- **Event Deduplication**: Uses event UID to prevent duplicates
- **Timber Logging**: Detailed logs for debugging

## Permissions

- `READ_CALENDAR` - Read events from source calendar
- `WRITE_CALENDAR` - Write events to target calendar
- `INTERNET` - (Optional) For future cloud sync features
- `RECEIVE_BOOT_COMPLETED` - Restore sync worker on device restart

## Architecture

- **MainActivity.kt** - UI built with Jetpack Compose
- **CalendarSyncWorker.kt** - Background sync logic using WorkManager
- **Theme** - Material Design 3 colors and typography

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

MIT

## Author

Built by Trolle (@regepower)
