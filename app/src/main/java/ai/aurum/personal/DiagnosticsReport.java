package ai.aurum.personal;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DiagnosticsReport {
    private DiagnosticsReport() {}

    public static String build(Context context) {
        PackageManager packageManager = context.getPackageManager();
        PackageInfo packageInfo = getPackageInfo(packageManager, context.getPackageName());

        String versionName = packageInfo.versionName == null ? "unknown" : packageInfo.versionName;
        long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? packageInfo.getLongVersionCode()
                : packageInfo.versionCode;

        List<String> permissions = new ArrayList<>();
        if (packageInfo.requestedPermissions != null) {
            for (String permission : packageInfo.requestedPermissions) {
                int state = context.checkSelfPermission(permission);
                permissions.add(
                        permission + "="
                                + (state == PackageManager.PERMISSION_GRANTED ? "granted" : "denied")
                );
            }
        }

        boolean microphoneGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean recognitionAvailable = VoiceController.isRecognitionAvailable(context);
        String defaultSpeechLocale = Locale.getDefault().toLanguageTag();

        return "Aurum Android Diagnostics\n"
                + "Milestone: A3.2 Natural Filipino Voice\n"
                + "App version: " + versionName + " (" + versionCode + ")\n"
                + "Build commit: " + BuildConfig.GIT_SHA + "\n"
                + "Build type: " + BuildConfig.BUILD_TYPE + "\n"
                + "Package: " + context.getPackageName() + "\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "ABI: " + primaryAbi() + "\n"
                + "Permissions: "
                + (permissions.isEmpty() ? "none" : String.join(", ", permissions))
                + "\n"
                + "Aurum services: A3.2 authenticated text + push-to-talk + Core neural/local Filipino speech\n"
                + "Backend: " + BackendConfig.diagnosticBackend(context) + "\n"
                + "Connection: " + BackendConfig.loadConnectionState(context) + "\n"
                + "Remote authentication: "
                + (BackendCredentialStore.hasAccessToken(context)
                    ? "access key configured in Android Keystore"
                    : "access key not configured")
                + "\n"
                + "Voice mode: one-shot push-to-talk; replies auto-speak; Core neural with local fallback\n"
                + "Microphone permission: " + (microphoneGranted ? "granted" : "not granted") + "\n"
                + "Speech recognizer: " + (recognitionAvailable ? "available" : "unavailable") + "\n"
                + "Speech locale: device default (" + defaultSpeechLocale + ")\n"
                + "Detected speech language: " + VoiceRuntimeState.detectedLanguage() + "\n"
                + "Speech state: " + VoiceRuntimeState.speechState() + "\n"
                + "TTS state: " + VoiceRuntimeState.ttsState() + "\n"
                + "TTS preference: Core neural -> best fil-PH voice -> en-PH -> device default -> en-US\n"
                + "Wake: not enabled in A3\n"
                + "Secrets included: no\n";
    }

    public static String shortSha(String sha) {
        if (sha == null || sha.trim().isEmpty()) {
            return "unknown";
        }
        return sha.length() <= 8 ? sha : sha.substring(0, 8);
    }

    private static String primaryAbi() {
        if (Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0) {
            return "unknown";
        }
        return Build.SUPPORTED_ABIS[0];
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo getPackageInfo(PackageManager packageManager, String packageName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS)
                );
            }
            return packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException exception) {
            throw new IllegalStateException("Aurum package metadata unavailable", exception);
        }
    }
}
