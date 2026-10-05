package com.example.forcenotifyunlock;

import android.app.NotificationChannel;
import android.os.Binder;
import android.os.Process;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Makes notification toggles of system apps actually stick.
 *
 * Why the toggles snap back on stock Android 13+ (and ColorOS on top of it):
 *  - For apps whose POST_NOTIFICATIONS permission is SYSTEM_FIXED, PermissionHelper
 *    .setNotificationPermission() returns early, so the app-level switch is a no-op.
 *  - For those apps every channel is flagged "importance locked by critical device function",
 *    and PreferencesHelper.updateNotificationChannel() puts the old importance back unless the
 *    channel is blockable. The user-locked bit is recorded, the importance is not.
 * Both checks live in system_server, so hooking only the Settings UI can't fix them.
 *
 * Scope: "System Framework" (android) does the real work; com.oplus.notificationmanager only
 * un-greys switches the OEM UI disables on its own.
 */
public class ForceNotifyUnlock implements IXposedHookLoadPackage {

    private static final String TAG = "ForceNotifyUnlock: ";
    private static final String UI_PKG = "com.oplus.notificationmanager";
    private static final String UI_CLASS =
            "com.oplus.notificationmanager.property.uicontroller.PropertyUIController";

    private static final String PREFS_HELPER =
            "com.android.server.notification.PreferencesHelper";
    private static final String PERM_HELPER =
            "com.android.server.notification.PermissionHelper";
    private static final String NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS";
    private static final int FLAG_PERMISSION_SYSTEM_FIXED = 1 << 4; // PackageManager constant

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName) && "android".equals(lpparam.processName)) {
            hookSystemServer(lpparam.classLoader);
        } else if (UI_PKG.equals(lpparam.packageName)) {
            hookOplusUi(lpparam.classLoader);
        }
    }

    // ---------------------------------------------------------------- system_server

    private void hookSystemServer(ClassLoader cl) {
        log("loaded in system_server");

        // 1. Channel lock: never report or store "locked by critical device function".
        //    This is the check that reverts channel importance in updateNotificationChannel(),
        //    and the flag Settings reads to grey out channel switches.
        tryHook("NotificationChannel.isImportanceLockedByCriticalDeviceFunction", () ->
                XposedHelpers.findAndHookMethod(NotificationChannel.class,
                        "isImportanceLockedByCriticalDeviceFunction",
                        XC_MethodReplacementFalse.INSTANCE));
        tryHook("NotificationChannel.setImportanceLockedByCriticalDeviceFunction", () ->
                XposedHelpers.findAndHookMethod(NotificationChannel.class,
                        "setImportanceLockedByCriticalDeviceFunction", boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                param.args[0] = false;
                            }
                        }));

        // 2. App-level lock reported to Settings (INotificationManager.isImportanceLocked).
        //    uid 1000 stays locked: Android grants every permission to the system uid, so an
        //    app-level switch for "Android System" can never take effect. Its channels can.
        tryHook("PreferencesHelper.isImportanceLocked", () ->
                XposedHelpers.findAndHookMethod(PREFS_HELPER, cl, "isImportanceLocked",
                        String.class, int.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                int uid = (int) param.args[1];
                                if (uidAppId(uid) != Process.SYSTEM_UID) {
                                    param.setResult(false);
                                }
                            }
                        }));

        // 2b. INotificationManager.isPermissionFixed(): what Settings uses to grey out the app
        //     switch of system-fixed apps. Report "not fixed" except for uid 1000 apps.
        tryHook("PermissionHelper.isPermissionFixed", () ->
                XposedHelpers.findAndHookMethod(PERM_HELPER, cl, "isPermissionFixed",
                        String.class, int.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!Boolean.TRUE.equals(param.getResult())) return;
                                int uid = packageUid(param.thisObject, (String) param.args[0],
                                        (int) param.args[1]);
                                if (uid >= 0 && uidAppId(uid) != Process.SYSTEM_UID) {
                                    param.setResult(false);
                                }
                            }
                        }));

        // 3. App-level switch: before the permission is revoked, clear SYSTEM_FIXED so
        //    setNotificationPermission() doesn't bail out and the permission service accepts
        //    the revoke. Re-enabling later grants it back normally (USER_SET).
        tryHook("PermissionHelper.setNotificationPermission", () ->
                XposedHelpers.findAndHookMethod(PERM_HELPER, cl, "setNotificationPermission",
                        String.class, int.class, boolean.class, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                boolean grant = (boolean) param.args[2];
                                if (grant) return;
                                clearSystemFixed(param.thisObject, (String) param.args[0],
                                        (int) param.args[1]);
                            }
                        }));

        // 4. Diagnostics: log what a user channel change asked for vs. what got stored, so a
        //    revert caused by something else (e.g. an OEM layer) shows up in the LSPosed log.
        tryHook("PreferencesHelper.updateNotificationChannel", () ->
                XposedBridge.hookAllMethods(XposedHelpers.findClass(PREFS_HELPER, cl),
                        "updateNotificationChannel", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Object[] a = param.args;
                                if (a.length < 4 || !(a[2] instanceof NotificationChannel)) return;
                                if (!(a[3] instanceof Boolean) || !((Boolean) a[3])) return;
                                NotificationChannel c = (NotificationChannel) a[2];
                                log("user update " + a[0] + "/" + c.getId()
                                        + " -> importance " + c.getImportance());
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object[] a = param.args;
                                if (a.length < 4 || !(a[2] instanceof NotificationChannel)) return;
                                if (!(a[3] instanceof Boolean) || !((Boolean) a[3])) return;
                                if (param.getThrowable() != null) {
                                    log("update threw: " + param.getThrowable());
                                    return;
                                }
                                NotificationChannel req = (NotificationChannel) a[2];
                                try {
                                    Object stored = XposedHelpers.callMethod(param.thisObject,
                                            "getNotificationChannel", a[0], a[1], req.getId(),
                                            false);
                                    if (stored instanceof NotificationChannel) {
                                        log("stored " + a[0] + "/" + req.getId()
                                                + " importance "
                                                + ((NotificationChannel) stored).getImportance());
                                    }
                                } catch (Throwable ignored) {
                                    // getNotificationChannel signature differs; logging only.
                                }
                            }
                        }));
    }

    private static int packageUid(Object permissionHelper, String pkg, int userId) {
        long token = Binder.clearCallingIdentity();
        try {
            Object pm = XposedHelpers.getObjectField(permissionHelper, "mPackageManager");
            return (int) XposedHelpers.callMethod(pm, "getPackageUid", pkg, 0L, userId);
        } catch (Throwable t) {
            return -1;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static void clearSystemFixed(Object permissionHelper, String pkg, int userId) {
        long token = Binder.clearCallingIdentity();
        try {
            Object permManager = XposedHelpers.getObjectField(permissionHelper, "mPermManager");
            Class<?> iface = Class.forName("android.permission.IPermissionManager");
            Method getFlags = findMethod(iface, "getPermissionFlags");
            Method update = findMethod(iface, "updatePermissionFlags");
            if (getFlags == null || update == null) {
                log("permission manager methods not found");
                return;
            }
            int flags = (int) getFlags.invoke(permManager,
                    withDevice(getFlags, pkg, NOTIFICATION_PERMISSION, userId));
            if ((flags & FLAG_PERMISSION_SYSTEM_FIXED) == 0) return;
            update.invoke(permManager, withDeviceUpdate(update, pkg, userId));
            log("cleared SYSTEM_FIXED on notifications for " + pkg + " (user " + userId + ")");
        } catch (Throwable t) {
            log("clearing SYSTEM_FIXED for " + pkg + " failed: " + t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // getPermissionFlags(String pkg, String perm, <String persistentDeviceId | int deviceId>, int userId)
    private static Object[] withDevice(Method m, String pkg, String perm, int userId) {
        Class<?>[] p = m.getParameterTypes();
        if (p.length == 3) return new Object[] {pkg, perm, userId};
        return new Object[] {pkg, perm, deviceArg(p[2]), userId};
    }

    // updatePermissionFlags(String pkg, String perm, int mask, int values, boolean checkAdjustPolicy,
    //                       <String persistentDeviceId | int deviceId>, int userId)
    private static Object[] withDeviceUpdate(Method m, String pkg, int userId) {
        Class<?>[] p = m.getParameterTypes();
        if (p.length == 6) {
            return new Object[] {pkg, NOTIFICATION_PERMISSION, FLAG_PERMISSION_SYSTEM_FIXED, 0,
                    false, userId};
        }
        return new Object[] {pkg, NOTIFICATION_PERMISSION, FLAG_PERMISSION_SYSTEM_FIXED, 0,
                false, deviceArg(p[5]), userId};
    }

    private static Object deviceArg(Class<?> type) {
        if (type == String.class) {
            try {
                return XposedHelpers.getStaticObjectField(
                        Class.forName("android.companion.virtual.VirtualDeviceManager"),
                        "PERSISTENT_DEVICE_ID_DEFAULT");
            } catch (Throwable t) {
                return "default:0";
            }
        }
        return 0; // Context.DEVICE_ID_DEFAULT
    }

    private static Method findMethod(Class<?> c, String name) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    private static int uidAppId(int uid) {
        return uid % 100000; // UserHandle.PER_USER_RANGE
    }

    // ---------------------------------------------------------------- ColorOS settings UI

    private void hookOplusUi(ClassLoader cl) {
        // The UI keeps its own "can this be switched" checks; these make every switch usable.
        // They don't decide whether a change is kept: that's system_server (hooks above).
        XC_MethodHook forceTrue = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                param.setResult(true);
            }
        };
        tryHook("UI isChannelBlockable", () -> XposedHelpers.findAndHookMethod(UI_CLASS, cl,
                "isChannelBlockable", boolean.class, NotificationChannel.class, forceTrue));
        tryHook("UI isChannelConfigurable", () -> XposedHelpers.findAndHookMethod(UI_CLASS, cl,
                "isChannelConfigurable", NotificationChannel.class, forceTrue));
        tryHook("UI notDisabledByLocalConfig", () -> XposedHelpers.findAndHookMethod(UI_CLASS, cl,
                "notDisabledByLocalConfig", String.class, forceTrue));
        tryHook("UI channelEnabled", () -> XposedHelpers.findAndHookMethod(UI_CLASS, cl,
                "channelEnabled", NotificationChannel.class, boolean.class, forceTrue));
        tryHook("UI appEnabled", () -> XposedHelpers.findAndHookMethod(UI_CLASS, cl,
                "appEnabled", String.class, int.class, boolean.class, forceTrue));
        tryHook("UI setEnabled", () -> XposedHelpers.findAndHookMethod(UI_CLASS, cl,
                "setEnabled", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.args[0] = true;
                    }
                }));
    }

    // ---------------------------------------------------------------- helpers

    private interface HookAction {
        void run() throws Throwable;
    }

    private static void tryHook(String name, HookAction action) {
        try {
            action.run();
            log("hooked " + name);
        } catch (Throwable t) {
            log(name + " hook failed: " + t);
        }
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + msg);
    }

    private static final class XC_MethodReplacementFalse extends XC_MethodHook {
        static final XC_MethodReplacementFalse INSTANCE = new XC_MethodReplacementFalse();

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(false);
        }
    }
}
