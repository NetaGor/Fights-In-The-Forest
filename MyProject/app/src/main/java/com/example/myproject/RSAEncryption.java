/**
 * RSAEncryption - Handles RSA key operations
 *
 * Manages key pair generation, storage in SharedPreferences,
 * and Base64 import/export utilities. Used by HybridEncryption
 * for secure communications.
 */
package com.example.myproject;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

public class RSAEncryption {
    private final Context context;
    private static final String TAG = "RSAEncryption";
    private static final int DEFAULT_KEY_SIZE = 2048;

    private static final String PREFS_NAME = "rsa_keys";
    private static final String PREF_PUBLIC_KEY = "public_key";
    private static final String PREF_PRIVATE_KEY = "private_key";

    public RSAEncryption(Context context) {
        this.context = context;
    }

    public KeyPair generateKeyPair(int keySize) throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(keySize, new SecureRandom());
        return keyPairGenerator.generateKeyPair();
    }

    public KeyPair generateKeyPair() throws Exception {
        return generateKeyPair(DEFAULT_KEY_SIZE);
    }

    public String exportPublicKeyToBase64(RSAPublicKey publicKey) throws Exception {
        X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(publicKey.getEncoded());
        return Base64.encodeToString(x509EncodedKeySpec.getEncoded(), Base64.NO_WRAP);
    }

    public String exportPrivateKeyToBase64(RSAPrivateKey privateKey) throws Exception {
        PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
        return Base64.encodeToString(pkcs8EncodedKeySpec.getEncoded(), Base64.NO_WRAP);
    }

    public void saveKeyPairToSharedPreferences(KeyPair keyPair) throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

        String publicKeyBase64 = exportPublicKeyToBase64(publicKey);
        String privateKeyBase64 = exportPrivateKeyToBase64(privateKey);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_PUBLIC_KEY, publicKeyBase64);
        editor.putString(PREF_PRIVATE_KEY, privateKeyBase64);
        editor.apply();

        Log.d(TAG, "Key pair saved to SharedPreferences");
    }

    /** Loads server public key from resources */
    public RSAPublicKey loadPublicKey(int resId) throws Exception {
        InputStream inputStream = context.getResources().openRawResource(resId);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int bytesRead;

        while ((bytesRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        inputStream.close();

        String keyBase64 = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
        byte[] keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP);

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        return (RSAPublicKey) keyFactory.generatePublic(keySpec);
    }

    /** Loads your private key from SharedPreferences */
    public RSAPrivateKey loadPrivateKeyFromSharedPreferences() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String privateKeyBase64 = prefs.getString(PREF_PRIVATE_KEY, null);

        if (privateKeyBase64 == null) {
            Log.w(TAG, "No private key found in SharedPreferences");
            return null;
        }

        return loadPrivateKeyFromBase64(privateKeyBase64);
    }

    public RSAPublicKey loadPublicKeyFromBase64(String base64PublicKey) throws Exception {
        byte[] keyBytes = Base64.decode(base64PublicKey, Base64.NO_WRAP);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        return (RSAPublicKey) keyFactory.generatePublic(keySpec);
    }

    public RSAPrivateKey loadPrivateKeyFromBase64(String base64PrivateKey) throws Exception {
        byte[] keyBytes = Base64.decode(base64PrivateKey, Base64.NO_WRAP);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
    }

    public KeyPair loadKeyPairFromSharedPreferences() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String publicKeyBase64 = prefs.getString(PREF_PUBLIC_KEY, null);
        String privateKeyBase64 = prefs.getString(PREF_PRIVATE_KEY, null);

        if (publicKeyBase64 == null || privateKeyBase64 == null) {
            Log.w(TAG, "No keys found in SharedPreferences");
            return null;
        }

        RSAPublicKey publicKey = loadPublicKeyFromBase64(publicKeyBase64);
        RSAPrivateKey privateKey = loadPrivateKeyFromBase64(privateKeyBase64);

        return new KeyPair(publicKey, privateKey);
    }

    public boolean keysExistInSharedPreferences() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.contains(PREF_PUBLIC_KEY) && prefs.contains(PREF_PRIVATE_KEY);
    }

    public KeyPair generateAndSaveKeyPair() throws Exception {
        KeyPair keyPair = generateKeyPair();
        saveKeyPairToSharedPreferences(keyPair);
        return keyPair;
    }

    /** Gets existing key pair or creates new one if needed */
    public KeyPair getOrCreateKeyPair() throws Exception {
        if (keysExistInSharedPreferences()) {
            KeyPair keyPair = loadKeyPairFromSharedPreferences();
            if (keyPair != null) {
                return keyPair;
            }
        }
        return generateAndSaveKeyPair();
    }
}