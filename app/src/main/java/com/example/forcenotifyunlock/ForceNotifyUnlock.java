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
    private static final String CONFIG_LIST_CHANNEL_CLASS =
        "com.oplus.notificationmanager.property.configlist.ConfigListChannel";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;

        XC_MethodHook forceTrueResult = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                param.setResult(true);
            }
        };

        // --- keep forcing these three: confirmed fix for the original grey-out issue ---
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

        // --- downgraded to diagnostic-only: no longer forcing these, just logging ---
        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "channelEnabled", NotificationChannel.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        NotificationChannel ch = (NotificationChannel) param.args[0];
                        XposedBridge.log("ForceNotifyUnlock: channelEnabled CALLED channelId="
                            + (ch != null ? ch.getId() : "null") + " arg1=" + param.args[1]);
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(true);
                        XposedBridge.log("ForceNotifyUnlock: channelEnabled FORCED true (was going to return something else)");
                    }
                });
            XposedBridge.log("ForceNotifyUnlock: hooked channelEnabled (forcing true, logging)");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: channelEnabled hook failed: " + t);
        }

        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "appEnabled", String.class, int.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log("ForceNotifyUnlock: appEnabled CALLED pkg=" + param.args[0]
                            + " uid=" + param.args[1] + " arg2=" + param.args[2]);
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(true);
                        XposedBridge.log("ForceNotifyUnlock: appEnabled FORCED true (was going to return something else)");
                    }
                });
            XposedBridge.log("ForceNotifyUnlock: hooked appEnabled (forcing true, logging)");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: appEnabled hook failed: " + t);
        }

        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "setEnabled", boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log("ForceNotifyUnlock: setEnabled CALLED with arg=" + param.args[0]);
                    }
                });
            XposedBridge.log("ForceNotifyUnlock: hooked setEnabled (diagnostic, not forcing)");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: setEnabled hook failed: " + t);
        }

        // --- new: the actual checked-state and persistence methods ---
        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "setChecked", boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log("ForceNotifyUnlock: setChecked CALLED with arg=" + param.args[0]);
                    }
                });
            XposedBridge.log("ForceNotifyUnlock: hooked setChecked (diagnostic)");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: setChecked hook failed: " + t);
        }

        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "isChecked",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        XposedBridge.log("ForceNotifyUnlock: isChecked RETURNED " + param.getResult());
                    }
                });
            XposedBridge.log("ForceNotifyUnlock: hooked isChecked (diagnostic)");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: isChecked hook failed: " + t);
        }

        XC_MethodHook logSaveUserRecord = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                StringBuilder sb = new StringBuilder("ForceNotifyUnlock: saveUserRecordAndReportData(");
                for (int i = 0; i < param.args.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(param.args[i]);
                }
                sb.append(") CALLED");
                XposedBridge.log(sb.toString());
            }
        };

        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "saveUserRecordAndReportData", boolean.class, logSaveUserRecord);
            XposedBridge.log("ForceNotifyUnlock: hooked saveUserRecordAndReportData(Z)");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: saveUserRecordAndReportData(Z) hook failed: " + t);
        }

        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "saveUserRecordAndReportData", boolean.class, String.class, logSaveUserRecord);
            XposedBridge.log("ForceNotifyUnlock: hooked saveUserRecordAndReportData(Z,String)");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: saveUserRecordAndReportData(Z,String) hook failed: " + t);
        }

        try {
            findAndHookMethod(TARGET_CLASS, lpparam.classLoader,
                "saveUserRecordAndReportData", String.class, String.class, int.class, String.class, boolean.class,
                logSaveUserRecord);
            XposedBridge.log("ForceNotifyUnlock: hooked saveUserRecordAndReportData(full)");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: saveUserRecordAndReportData(full) hook failed: " + t);
        }

        // --- diagnostic only: boot-time channel reconciliation ---
        try {
            findAndHookMethod(
                CONFIG_LIST_CHANNEL_CLASS,
                lpparam.classLoader,
                "initChannel",
                String.class, int.class,
                "com.oplus.notificationmanager.property.model.PackageConfig",
                "com.oplus.notificationmanager.property.model.PackageConfig",
                "com.oplus.notificationmanager.property.model.ChannelConfig",
                NotificationChannel.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        NotificationChannel ch = (NotificationChannel) param.args[5];
                        XposedBridge.log("ForceNotifyUnlock: initChannel BEFORE pkg=" + param.args[0]
                            + " channelId=" + (ch != null ? ch.getId() : "null")
                            + " importance=" + (ch != null ? ch.getImportance() : "null"));
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        NotificationChannel ch = (NotificationChannel) param.args[5];
                        XposedBridge.log("ForceNotifyUnlock: initChannel AFTER pkg=" + param.args[0]
                            + " channelId=" + (ch != null ? ch.getId() : "null")
                            + " importance=" + (ch != null ? ch.getImportance() : "null"));
                    }
                }
            );
            XposedBridge.log("ForceNotifyUnlock: hooked initChannel (diagnostic)");
        } catch (Throwable t) {
            XposedBridge.log("ForceNotifyUnlock: initChannel hook failed: " + t);
        }
    }
}
