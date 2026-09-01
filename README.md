# notif-unlock-module

An Xposed/LSPosed module that force-unlocks the greyed-out, non-toggleable
notification category switches under **Settings → Apps → *(system app)* →
Manage notifications** — the "Android System", `com.android.systemui`,
`com.android.phone` type entries where every notification toggle is locked
and can't be turned on or off.

## What it does

It hooks a handful of boolean gate methods
(`isChannelBlockable`, `isChannelConfigurable`, `notDisabledByLocalConfig`,
`channelEnabled`, `appEnabled`, `setEnabled`) on the notification-settings
UI controller and forces them to report "yes, this is toggleable" instead of
the hardcoded "no" that blocks system-UID packages. It doesn't touch any
system files, databases, or permissions — it's a pure runtime hook that only
affects how that one settings screen renders itself.

## Compatibility

Built against and tested on:
- **OnePlus / Oppo / Realme devices running OxygenOS or ColorOS** (Android
  16 / API 36, but should work on nearby versions too)
- A device that's **rooted** with a working **Zygisk** provider (e.g.
  rezygisk) and an **Xposed/LSPosed-API framework** installed and running
  (LSPosed, or its rebrand **Vector**)

This targets the specific notification-settings app Oplus ships
(`com.oplus.notificationmanager`, "NotificationCenter") — it will very
likely **not** do anything on stock AOSP, Pixel, Samsung, or other OEMs that
ship a different app for this screen, since the hooked class simply won't
exist there.

## If it doesn't work

This module makes no persistent changes to your system — it's a live hook
that only runs while enabled. If it doesn't unlock the toggles on your
device, there's nothing to clean up: just disable it in LSPosed/Vector's
module list (or uninstall the APK) and you're back to stock behavior.
