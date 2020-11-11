# FIND3 Android App

This is a continuation of the open-source minimal Android app for using with [FIND3](https://github.com/schollz/find3). This app will allow you to use your phone to perform constant scans of Bluetooth and WiFi signals and levels that can be associated with certain locations in your home to allow internal positioning.

## Changes made since fork:
- Updated to work on Android 9 and Android 10!
- New scanning behavior in back-end: Instead of an alarm firing every 60 seconds, starting four successive 10 second scans, scanning simply starts shortly after you tap "Start Scan," and ends immediately after you tap "Stop Scan"
- New permissions handling in back-end: Now, every permission is checked. All permissions that are currently denied are requested.
- General cleanup/code tidying.

To get started with contributing to this app, use Android Studio 3+ and "Import Project" after downloading the Git repository.

## Roadmap

- [ ] Get locations for a family after it is typed in, and use those for autocompletion.
- [ ] Add settings screen to allow for various adjustments, such as time between each scan.

## Documentation

https://find3.internalpositioning.com/doc/tracking_your_phone.md

## License

MIT

## Feel free to help out!
This continuation is not yet on the Play Store, but please do reach out if you'd like to help!
