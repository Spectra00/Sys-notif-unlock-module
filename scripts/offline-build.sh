#!/usr/bin/env bash
# Builds and signs the release APK entirely offline from Google's SDK servers.
#
# Why this exists: the normal `./gradlew assembleRelease` path needs the
# Android Gradle Plugin and its aapt2/d8 artifacts from dl.google.com /
# maven.google.com. In network environments where that host is blocked
# (e.g. a sandboxed CI runner), this script reproduces the same build using:
#   - javac                              (JDK, already on PATH)
#   - aapt/aapt2 + zipalign + apksigner  (Ubuntu/Debian `android-sdk-build-tools`
#                                          package: apt-get install android-sdk-build-tools
#                                          aapt apksigner zipalign android-sdk-platform-23)
#   - a real D8 dexer jar, fetched from Google's public r8-releases GCS
#     bucket (storage.googleapis.com, unaffiliated with the blocked
#     dl.google.com/maven.google.com hosts)
#   - a real android.jar with API 28 classes (NotificationChannel needs 26+),
#     from org.robolectric:android-all on Maven Central, used only as the
#     javac compile classpath (resource linking still uses the apt-installed
#     API 23 platform jar, which is sufficient since this module has no res/)
#
# Output: build/offline/ForceNotifyUnlock-release-signed.apk
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/offline"
CACHE="$ROOT/build/offline-cache"
mkdir -p "$OUT" "$CACHE"

AAPT2_BIN="${AAPT2_BIN:-/usr/lib/android-sdk/build-tools/29.0.3/aapt2}"
ZIPALIGN_BIN="${ZIPALIGN_BIN:-/usr/lib/android-sdk/build-tools/29.0.3/zipalign}"
APKSIGNER_BIN="${APKSIGNER_BIN:-/usr/lib/android-sdk/build-tools/29.0.3/apksigner}"
FRAMEWORK_JAR="${FRAMEWORK_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"

ANDROID_ALL_URL="https://repo1.maven.org/maven2/org/robolectric/android-all/9-robolectric-4913185/android-all-9-robolectric-4913185.jar"
ANDROID_ALL_JAR="$CACHE/android-all-9.jar"
R8_URL="https://storage.googleapis.com/r8-releases/raw/8.9.42/r8.jar"
R8_JAR="$CACHE/r8.jar"

for bin in "$AAPT2_BIN" "$ZIPALIGN_BIN" "$APKSIGNER_BIN"; do
  [ -x "$bin" ] || { echo "missing tool: $bin (install android-sdk-build-tools)" >&2; exit 1; }
done
[ -f "$FRAMEWORK_JAR" ] || { echo "missing framework jar: $FRAMEWORK_JAR (install android-sdk-platform-23)" >&2; exit 1; }

[ -f "$ANDROID_ALL_JAR" ] || curl -sSL -o "$ANDROID_ALL_JAR" "$ANDROID_ALL_URL"
[ -f "$R8_JAR" ] || curl -sSL -o "$R8_JAR" "$R8_URL"

# --- 1. compile-only Xposed API stub (real classes are provided by the
#     Xposed/LSPosed/Vector framework at runtime; this jar only exists to
#     satisfy javac's need for the method signatures at compile time) ---
STUB_SRC="$CACHE/xposed-stub-src"
rm -rf "$STUB_SRC"
mkdir -p "$STUB_SRC/de/robv/android/xposed/callbacks"

cat > "$STUB_SRC/de/robv/android/xposed/IXposedHookLoadPackage.java" << 'EOF'
package de.robv.android.xposed;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
public interface IXposedHookLoadPackage {
    void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
EOF

cat > "$STUB_SRC/de/robv/android/xposed/callbacks/XC_LoadPackage.java" << 'EOF'
package de.robv.android.xposed.callbacks;
public class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public ClassLoader classLoader;
    }
}
EOF

cat > "$STUB_SRC/de/robv/android/xposed/XC_MethodHook.java" << 'EOF'
package de.robv.android.xposed;
public abstract class XC_MethodHook {
    public static class Unhook {}
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable t) { this.throwable = t; }
    }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
EOF

cat > "$STUB_SRC/de/robv/android/xposed/XposedBridge.java" << 'EOF'
package de.robv.android.xposed;
public class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
}
EOF

cat > "$STUB_SRC/de/robv/android/xposed/XposedHelpers.java" << 'EOF'
package de.robv.android.xposed;
public class XposedHelpers {
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
            String methodName, Object... parameterTypesAndCallback) {
        return new XC_MethodHook.Unhook();
    }
}
EOF

STUB_CLASSES="$CACHE/stub-classes"
rm -rf "$STUB_CLASSES"; mkdir -p "$STUB_CLASSES"
find "$STUB_SRC" -name "*.java" > "$CACHE/stub-sources.txt"
javac -source 8 -target 8 -nowarn -d "$STUB_CLASSES" @"$CACHE/stub-sources.txt"
XPOSED_STUB_JAR="$CACHE/xposed-api-stub.jar"
jar cf "$XPOSED_STUB_JAR" -C "$STUB_CLASSES" .

# --- 2. compile module sources ---
APP_CLASSES="$OUT/app-classes"
rm -rf "$APP_CLASSES"; mkdir -p "$APP_CLASSES"
javac -source 8 -target 8 -nowarn \
  -classpath "$ANDROID_ALL_JAR:$XPOSED_STUB_JAR" \
  -d "$APP_CLASSES" \
  "$ROOT"/app/src/main/java/com/example/forcenotifyunlock/*.java

# --- 3. dex ---
DEX_OUT="$OUT/dexout"
rm -rf "$DEX_OUT"; mkdir -p "$DEX_OUT"
java -cp "$R8_JAR" com.android.tools.r8.D8 --output "$DEX_OUT" --min-api 28 \
  $(find "$APP_CLASSES" -name "*.class")

# --- 4. package with aapt2 (manifest needs an explicit package= attribute
#     since there is no AGP manifest merger here to inject it from
#     app/build.gradle's namespace) ---
MANIFEST="$OUT/AndroidManifest.xml"
sed 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.forcenotifyunlock" android:versionCode="1" android:versionName="1.0"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36"/>#' \
  "$ROOT/app/src/main/AndroidManifest.xml" > "$MANIFEST"

"$AAPT2_BIN" link -o "$OUT/base.apk" -I "$FRAMEWORK_JAR" \
  --manifest "$MANIFEST" \
  -A "$ROOT/app/src/main/assets" \
  --min-sdk-version 28 --target-sdk-version 28

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$DEX_OUT" && zip -j -X "$OUT/unsigned.apk" classes.dex)

# --- 5. align ---
"$ZIPALIGN_BIN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# --- 6. self-signed debug keystore (generate once, reuse afterwards) ---
KEYSTORE="$CACHE/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=ForceNotifyUnlock Debug, O=Local Build, C=US"
fi

# --- 7. sign ---
FINAL_APK="$OUT/ForceNotifyUnlock-release-signed.apk"
"$APKSIGNER_BIN" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$FINAL_APK" "$OUT/aligned.apk"
"$APKSIGNER_BIN" verify --print-certs "$FINAL_APK"

echo
echo "Signed APK: $FINAL_APK"
echo "Debug keystore (keep it to re-sign future updates with the same signer): $KEYSTORE"
