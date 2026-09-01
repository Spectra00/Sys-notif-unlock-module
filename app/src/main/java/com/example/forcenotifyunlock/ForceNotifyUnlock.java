package com.example.forcenotifyunlock;

import android.app.NotificationChannel;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

public class ForceNotifyUnlock implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "com.oplus.notificationmanager";
    private static final String TARGET_CLASS =
        "com.oplus.notificationmanager.property.uicontroller.PropertyUIController";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;

        XC_MethodHook forceTrueResult = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                param.setResult(true);
            }
        };

        XC_MethodHook forceTrueArg = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.args[0] = true;
            }
        };

        // --- previously confirmed hooked successfully, keep them ---
        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "isChannelBlockable", boolean.class, NotificationChannel.class, forceTrueResult);
            XposedBridge.log("ForceNotifyUnlock: hooked isChannelBlockable");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: isChannelBlockable hook failed: " + t);
        }

        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "isChannelConfigurable", NotificationChannel.class, forceTrueResult);
            XposedBridge.log("ForceNotifyUnlock: hooked isChannelConfigurable");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: isChannelConfigurable hook failed: " + t);
        }

        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "notDisabledByLocalConfig", String.class, forceTrueResult);
            XposedBridge.log("ForceNotifyUnlock: hooked notDisabledByLocalConfig");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: notDisabledByLocalConfig hook failed: " + t);
        }

        // --- new: the actual widget-state methods ---
        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "channelEnabled", NotificationChannel.class, boolean.class, forceTrueResult);
            XposedBridge.log("ForceNotifyUnlock: hooked channelEnabled");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: channelEnabled hook failed: " + t);
        }

        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "appEnabled", String.class, int.class, boolean.class, forceTrueResult);
            XposedBridge.log("ForceNotifyUnlock: hooked appEnabled");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: appEnabled hook failed: " + t);
        }

        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "setEnabled", boolean.class, forceTrueArg);
            XposedBridge.log("ForceNotifyUnlock: hooked setEnabled");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: setEnabled hook failed: " + t);
        }
    }
}
