/**
 * RSAEncryption.java - Class for handling RSA encryption operations
 *
 * This class manages RSA key pair generation, storage, and provides utility
 * methods for importing/exporting keys in Base64 format. Keys are stored in
 * the application's SharedPreferences for persistence.
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
    private static final int DEFAULT_KEY_SIZE = 2048; // Industry standard key size

    // Constants for SharedPreferences
    private static final String PREFS_NAME = "rsa_keys";
    private static final String PREF_PUBLIC_KEY = "public_key";
    private static final String PREF_PRIVATE_KEY = "private_key";

    /**
     * Constructor that takes an Android context
     *
     * @param context The application context for accessing SharedPreferences
     */
    public RSAEncryption(Context context) {
        this.context = context;
    }

    /**
     * Generates a new RSA key pair with specified key size
     *
     * @param keySize The size of the key in bits (e.g., 2048, 4096)
     * @return A KeyPair containing public and private RSA keys
     * @throws Exception If key generation fails
     */
    public KeyPair generateKeyPair(int keySize) throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(keySize, new SecureRandom());
        return keyPairGenerator.generateKeyPair();
    }

    /**
     * Generates a new RSA key pair with default key size (2048 bits)
     *
     * @return A KeyPair containing public and private RSA keys
     * @throws Exception If key generation fails
     */
    public KeyPair generateKeyPair() throws Exception {
        return generateKeyPair(DEFAULT_KEY_SIZE);
    }

    /**
     * Exports the public key to Base64 format for transmission
     *
     * @param publicKey The RSA public key to export
     * @return Base64-encoded string representation of the public key
     * @throws Exception If key export fails
     */
    public String exportPublicKeyToBase64(RSAPublicKey publicKey) throws Exception {
        X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(publicKey.getEncoded());
        return Base64.encodeToString(x509EncodedKeySpec.getEncoded(), Base64.NO_WRAP);
    }

    /**
     * Exports the private key to Base64 format
     *
     * @param privateKey The RSA private key to export
     * @return Base64-encoded string representation of the private key
     * @throws Exception If key export fails
     */
    public String exportPrivateKeyToBase64(RSAPrivateKey privateKey) throws Exception {
        PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
        return Base64.encodeToString(pkcs8EncodedKeySpec.getEncoded(), Base64.NO_WRAP);
    }

    /**
     * Saves the key pair to SharedPreferences for persistence
     *
     * @param keyPair The key pair to save
     * @throws Exception If saving keys fails
     */
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

    /**
     * Loads a public key from a resource file
     *
     * @param resId Resource ID of the file containing the Base64-encoded public key
     * @return RSA public key object
     * @throws Exception If loading the key fails
     */
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

    /**
     * Loads only the RSA private key from SharedPreferences
     *
     * @return RSA private key object, or null if key isn't found
     * @throws Exception If loading the key fails
     */
    public RSAPrivateKey loadPrivateKeyFromSharedPreferences() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String privateKeyBase64 = prefs.getString(PREF_PRIVATE_KEY, null);

        if (privateKeyBase64 == null) {
            Log.w(TAG, "No private key found in SharedPreferences");
            return null;
        }

        return loadPrivateKeyFromBase64(privateKeyBase64);
    }

    /**
     * Loads a public key from a Base64 string
     *
     * @param base64PublicKey Base64-encoded public key string
     * @return RSA public key object
     * @throws Exception If loading the key fails
     */
    public RSAPublicKey loadPublicKeyFromBase64(String base64PublicKey) throws Exception {
        byte[] keyBytes = Base64.decode(base64PublicKey, Base64.NO_WRAP);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        return (RSAPublicKey) keyFactory.generatePublic(keySpec);
    }

    /**
     * Loads a private key from a Base64 string
     *
     * @param base64PrivateKey Base64-encoded private key string
     * @return RSA private key object
     * @throws Exception If loading the key fails
     */
    public RSAPrivateKey loadPrivateKeyFromBase64(String base64PrivateKey) throws Exception {
        byte[] keyBytes = Base64.decode(base64PrivateKey, Base64.NO_WRAP);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
    }

    /**
     * Loads the RSA key pair from SharedPreferences
     *
     * @return KeyPair object containing the saved keys, or null if keys aren't found
     * @throws Exception If loading keys fails
     */
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

    /**
     * Checks if RSA keys exist in SharedPreferences
     *
     * @return true if both public and private keys exist, false otherwise
     */
    public boolean keysExistInSharedPreferences() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.contains(PREF_PUBLIC_KEY) && prefs.contains(PREF_PRIVATE_KEY);
    }

    /**
     * Generates a new key pair and saves it to SharedPreferences
     *
     * @return The generated KeyPair
     * @throws Exception If generation or saving fails
     */
    public KeyPair generateAndSaveKeyPair() throws Exception {
        KeyPair keyPair = generateKeyPair();
        saveKeyPairToSharedPreferences(keyPair);
        return keyPair;
    }

    /**
     * Gets the key pair from SharedPreferences or generates a new one if none exists
     *
     * @return The loaded or newly generated KeyPair
     * @throws Exception If loading or generation fails
     */
    public KeyPair getOrCreateKeyPair() throws Exception {
        if (keysExistInSharedPreferences()) {
            KeyPair keyPair = loadKeyPairFromSharedPreferences();
            if (keyPair != null) {
                return keyPair;
            }
        }

        // If we get here, either no keys exist or loading failed
        return generateAndSaveKeyPair();
    }
}
