# Split Tracker

Android app that reads your bank SMS, asks whose spend it was, and tracks who owes you.
All data stays on the phone (local database). Only the last 4 card digits are stored.

## Build the APK with GitHub (no Android Studio)
1. Put all files from this folder in the root of your repo (keep the `.github` folder) and push to `main`.
2. Open the repo > Actions > "Build APK" (it runs on push; you can also run it manually).
3. When it finishes, open the repo's Releases page on your phone, download `SplitTracker.apk` from "Latest build" and install it (allow "install unknown apps" for your browser).

## Or build in Android Studio
Open this folder, let Gradle sync, press Run with your phone connected.

## First launch
Allow SMS, Contacts and Notifications. The app imports the last 120 days of card messages, then
tags new ones as they arrive (you get a notification for each).
Supported: SBI Card, HDFC, ICICI, IndusInd, YES Bank cards and Kotak debit.
