#!/bin/sh
set -eu

# Install without disabling the stock launcher. Test the new home screen first.
APK=${1:-app/build/outputs/apk/release/app-release.apk}
TV_ADDRESS=${2:-}
ADB=${ADB:-adb}
PACKAGE=dev.plume.growquiz
ADMIN=dev.plume.quiz.QuizDeviceAdminReceiver

if [ ! -f "$APK" ]; then
    echo "APK not found: $APK" >&2
    echo "Run ./gradlew assembleRelease first, or pass an APK path." >&2
    exit 1
fi

if [ -n "$TV_ADDRESS" ]; then
    "$ADB" connect "$TV_ADDRESS"
fi

"$ADB" install -r "$APK"
if ! "$ADB" shell pm grant "$PACKAGE" android.permission.WRITE_SECURE_SETTINGS; then
    echo "Optional Home-service grant was not accepted; continuing." >&2
fi

"$ADB" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME

cat <<EOF
Installed $PACKAGE.
Select 成长桌面 as the default home app, then open 桌面设置 -> 儿童定时问答.
The stock launcher was NOT disabled.

Optional, on a suitable test device only:
  $ADB shell dpm set-device-owner $PACKAGE/$ADMIN

Device Owner provisioning can require a fresh device, removed accounts or a factory reset.
Back up data first. It grants kiosk policy control, not root access.
EOF
