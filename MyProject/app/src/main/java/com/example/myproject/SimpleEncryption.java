package com.example.myproject;

import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Provides simple symmetric encryption for API requests and responses
 */
public class SimpleEncryption {
    private static final String TAG = "SimpleEncryption";

    // Fixed encryption key and IV (must match the server)
    private static final byte[] KEY = "ThisIsASecretKey".getBytes(StandardCharsets.UTF_8);
    private static final byte[] IV = "ThisIsAnIVectors".getBytes(StandardCharsets.UTF_8);

    /**
     * Encrypts data using AES encryption
     *
     * @param data The data to encrypt (can be a JSONObject or String)
     * @return A JSONObject containing the encrypted data
     * @throws Exception If encryption fails
     */
    public static JSONObject encrypt(Object data) throws Exception {
        try {
            // Convert the data to a string
            String dataString;
            if (data instanceof JSONObject) {
                dataString = data.toString();
            } else if (data instanceof String) {
                dataString = (String) data;
            } else {
                throw new IllegalArgumentException("Data must be a JSONObject or String");
            }

            // Create cipher
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            SecretKeySpec keySpec = new SecretKeySpec(KEY, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(IV);

            // Initialize for encryption
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

            // Encrypt
            byte[] encryptedBytes = cipher.doFinal(dataString.getBytes(StandardCharsets.UTF_8));

            // Base64 encode
            String encodedData = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);

            // Create result JSON
            JSONObject result = new JSONObject();
            result.put("encrypted", true);
            result.put("method", "aes-128-cbc");
            result.put("data", encodedData);

            return result;
        } catch (Exception e) {
            Log.e(TAG, "Encryption error: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Decrypts data that was encrypted with the same key
     *
     * @param encryptedData The encrypted data (can be a JSONObject or String)
     * @return The decrypted data as a JSONObject or String
     * @throws Exception If decryption fails
     */
    public static Object decrypt(Object encryptedData) throws Exception {
        try {
            String dataToDecrypt;

            // Extract the encrypted data string
            if (encryptedData instanceof JSONObject) {
                JSONObject jsonData = (JSONObject) encryptedData;

                // Check if this is our encrypted format
                if (!jsonData.optBoolean("encrypted", false)) {
                    // Not our encrypted format, return as is
                    return encryptedData;
                }

                dataToDecrypt = jsonData.getString("data");
            } else if (encryptedData instanceof String) {
                dataToDecrypt = (String) encryptedData;
            } else {
                throw new IllegalArgumentException("Encrypted data must be a JSONObject or String");
            }

            // Decode from Base64
            byte[] encryptedBytes = Base64.decode(dataToDecrypt, Base64.NO_WRAP);

            // Create cipher
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            SecretKeySpec keySpec = new SecretKeySpec(KEY, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(IV);

            // Initialize for decryption
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            // Decrypt
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

            // Convert to string
            String decryptedString = new String(decryptedBytes, StandardCharsets.UTF_8);

            // Try to parse as JSON
            try {
                return new JSONObject(decryptedString);
            } catch (JSONException e) {
                // Not valid JSON, return as string
                return decryptedString;
            }
        } catch (Exception e) {
            Log.e(TAG, "Decryption error: " + e.getMessage());
            throw e;
        }
    }
}