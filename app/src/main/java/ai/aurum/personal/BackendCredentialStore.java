package ai.aurum.personal;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class BackendCredentialStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "aurum_remote_access_v1";
    private static final String PREFS = "aurum_remote_auth";
    private static final String KEY_CIPHERTEXT = "ciphertext";
    private static final String KEY_IV = "iv";
    private static final int MIN_TOKEN_LENGTH = 32;

    private BackendCredentialStore() {}

    public static void saveAccessToken(Context context, String token) {
        String value = token == null ? "" : token.trim();
        if (value.length() < MIN_TOKEN_LENGTH || value.matches(".*\\s+.*")) {
            throw new IllegalArgumentException("Aurum access key must be at least 32 characters with no spaces");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            preferences(context).edit()
                    .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not protect Aurum access key", exception);
        }
    }

    public static String loadAccessToken(Context context) {
        SharedPreferences prefs = preferences(context);
        String ciphertext = prefs.getString(KEY_CIPHERTEXT, "");
        String iv = prefs.getString(KEY_IV, "");
        if (ciphertext == null || ciphertext.isEmpty() || iv == null || iv.isEmpty()) return "";
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            );
            byte[] plain = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply();
            return "";
        }
    }

    public static boolean hasAccessToken(Context context) {
        return !loadAccessToken(context).isEmpty();
    }

    public static void clearAccessToken(Context context) {
        preferences(context).edit().clear().apply();
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
            // Preferences are already cleared; fail closed if keystore cleanup is unavailable.
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
