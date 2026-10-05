# notif-unlock-module

An Xposed/LSPosed module that force-unlocks the greyed-out, non-toggleable
notification category switches under **Settings → Apps → *(system app)* →
Manage notifications** — the "Android System", `com.android.systemui`,
`com.android.phone` type entries where every notification toggle is locked
and can't be turned on or off.

## What it does

Turning a system app's notifications off in Settings used to "snap back" on. That isn't
the settings screen: Android's notification service itself drops the change.

- Apps whose `POST_NOTIFICATIONS` permission is **SYSTEM_FIXED** (phone, SIM, eSIM manager,
  system updates, …) can't have the permission revoked, so the app-level switch is a no-op.
- Every channel of such an app is flagged "importance locked by critical device function",
  and `PreferencesHelper.updateNotificationChannel()` puts the old importance back.

So the module hooks **system_server** (LSPosed scope: *System Framework*):

| Hook | Effect |
|---|---|
| `NotificationChannel.isImportanceLockedByCriticalDeviceFunction()` → false, setter forced false | channel switches stick |
| `PreferencesHelper.isImportanceLocked()` / `PermissionHelper.isPermissionFixed()` → false (except uid 1000) | app switch no longer locked |
| before `PermissionHelper.setNotificationPermission(…, grant=false, …)` clear SYSTEM_FIXED | app-level "off" is accepted |
| `PreferencesHelper.updateNotificationChannel()` (log only) | LSPosed log shows requested vs stored importance |

In `com.oplus.notificationmanager` it keeps the v1 hooks that make the switches usable
(`isChannelBlockable`, `isChannelConfigurable`, `notDisabledByLocalConfig`,
`channelEnabled`, `appEnabled`, `setEnabled`). v1 stopped there, so the switches moved but
system_server could still put the old value back; that is the part v3 adds.

"Android System" (uid 1000) is the one exception for the **app-level** switch: Android
grants the system uid every permission, so blocking it at app level would block all system
services at once. Its individual channels can all be turned off.

## Install

1. Install the APK (uninstall v1 first if it was signed with a different key).
2. In LSPosed, enable the module with scope **System Framework** and **NotificationCenter**
   (`com.oplus.notificationmanager`), then reboot.
3. LSPosed › Logs should show `ForceNotifyUnlock: loaded in system_server` and `hooked …`
   lines. When you change a channel you'll see `user update …` and `stored … importance`.

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

The hooks only run while the module is enabled. The one persistent effect: switching a
system-fixed app's notifications off clears that permission's SYSTEM_FIXED flag (the same
state a normal app has); switch it back on in Settings to restore notifications. If it doesn't unlock the toggles on your
device, there's nothing to clean up: just disable it in LSPosed/Vector's
module list (or uninstall the APK) and you're back to stock behavior.
