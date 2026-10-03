package com.impulsefy;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Values are encrypted with a non-exportable, app-private Android Keystore key. */
public final class SecureStore {
    private static final String ALIAS = "impulsefy.credentials.v1";
    private static final Object KEY_LOCK = new Object();
    private final SharedPreferences preferences;

    public SecureStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("encrypted_credentials", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        synchronized (KEY_LOCK) {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            return generator.generateKey();
        }
    }

    public synchronized String get(String name) throws Exception {
        String encoded = preferences.getString(name, null);
        if (encoded == null) return null;
        byte[] data = Base64.decode(encoded, Base64.NO_WRAP);
        if (data.length < 29 || data[0] != 1) throw new IOException("Credencial inválida. Entre novamente.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(data, 1, 13)));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(data, 13, data.length - 13), StandardCharsets.UTF_8);
    }

    public synchronized void put(String name, String value) throws Exception {
        if (value == null) { remove(name); return; }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new IOException("Armazenamento seguro indisponível.");
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] data = new byte[13 + encrypted.length];
        data[0] = 1;
        System.arraycopy(iv, 0, data, 1, 12);
        System.arraycopy(encrypted, 0, data, 13, encrypted.length);
        if (!preferences.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).commit()) {
            throw new IOException("Não foi possível salvar a sessão com segurança.");
        }
    }

    public synchronized void remove(String name) {
        preferences.edit().remove(name).commit();
    }
}
