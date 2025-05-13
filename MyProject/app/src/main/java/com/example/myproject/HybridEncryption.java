/**
 * HybridEncryption - Handles RSA and AES encryption for secure communication
 */
package com.example.myproject;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class HybridEncryption {
    private static final String TAG = "HybridEncryption";
    private final Context context;
    private final RSAEncryption rsaEncryption;
    private RSAPublicKey serverPublicKey;
    private static final byte[] SYMMETRIC_KEY = "SecureKey7890123".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SYMMETRIC_IV = "Vector4567890123".getBytes(StandardCharsets.UTF_8);

    /**
     * Constructor - initializes the encryption system and loads server public key
     */
    public HybridEncryption(Context context) {
        this.context = context;
        this.rsaEncryption = new RSAEncryption(context);
        try {
            loadServerPublicKey();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load server public key: " + e.getMessage(), e);
        }
    }

    /**
     * Loads the server's public key from app resources
     */
    private void loadServerPublicKey() throws Exception {
        try {
            serverPublicKey = rsaEncryption.loadPublicKey(R.raw.public_key);
            Log.d(TAG, "Successfully loaded server public key");
        } catch (Exception e) {
            Log.e(TAG, "Error loading server public key: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Encrypts data using hybrid RSA-AES approach - RSA for key encryption, AES for data
     */
    public JSONObject encryptWithPublicKey(Object data) throws Exception {
        try {
            String dataString;
            if (data instanceof JSONObject) {
                dataString = data.toString();
            } else if (data instanceof String) {
                dataString = (String) data;
            } else {
                throw new IllegalArgumentException("Data must be a JSONObject or String");
            }

            byte[] aesKey = new byte[16];
            java.security.SecureRandom secureRandom = new java.security.SecureRandom();
            secureRandom.nextBytes(aesKey);

            byte[] iv = new byte[16];
            secureRandom.nextBytes(iv);

            SecretKeySpec secretKeySpec = new SecretKeySpec(aesKey, "AES");
            IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);
            Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aesCipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);
            byte[] encryptedData = aesCipher.doFinal(dataString.getBytes(StandardCharsets.UTF_8));

            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsaCipher.init(Cipher.ENCRYPT_MODE, serverPublicKey);
            byte[] encryptedAesKey = rsaCipher.doFinal(aesKey);

            String base64EncryptedKey = Base64.encodeToString(encryptedAesKey, Base64.NO_WRAP);
            String base64IV = Base64.encodeToString(iv, Base64.NO_WRAP);
            String base64EncryptedData = Base64.encodeToString(encryptedData, Base64.NO_WRAP);

            JSONObject result = new JSONObject();
            result.put("encrypted", true);
            result.put("method", "hybrid-rsa-aes");
            result.put("encrypted_key", base64EncryptedKey);
            result.put("iv", base64IV);
            result.put("data", base64EncryptedData);

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Hybrid encryption error: " + e.getMessage(), e);
            return encryptSymmetric(data);
        }
    }

    /**
     * Fallback encryption using a symmetric key when hybrid encryption fails
     */
    public JSONObject encryptSymmetric(Object data) throws Exception {
        try {
            String dataString;
            if (data instanceof JSONObject) {
                dataString = data.toString();
            } else if (data instanceof String) {
                dataString = (String) data;
            } else {
                throw new IllegalArgumentException("Data must be a JSONObject or String");
            }

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(SYMMETRIC_KEY, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(SYMMETRIC_IV);

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encryptedBytes = cipher.doFinal(dataString.getBytes(StandardCharsets.UTF_8));

            String encodedData = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);

            JSONObject result = new JSONObject();
            result.put("encrypted", true);
            result.put("method", "aes-128-cbc");
            result.put("data", encodedData);

            return result;
        } catch (Exception e) {
            Log.e(TAG, "Symmetric encryption error: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Decrypts data that was encrypted with the hybrid RSA-AES method
     */
    public Object decryptWithPrivateKey(JSONObject encryptedPackage, RSAPrivateKey privateKey) throws Exception {
        try {
            String method = encryptedPackage.optString("method", "");
            Log.d(TAG, "Decrypting package with method: " + method);

            if (method.equals("hybrid-rsa-aes")) {
                String base64EncryptedKey = encryptedPackage.getString("encrypted_key");
                String base64IV = encryptedPackage.getString("iv");
                String base64EncryptedData = encryptedPackage.getString("data");

                byte[] encryptedKey = Base64.decode(base64EncryptedKey, Base64.NO_WRAP);
                byte[] iv = Base64.decode(base64IV, Base64.NO_WRAP);
                byte[] encryptedData = Base64.decode(base64EncryptedData, Base64.NO_WRAP);

                Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
                byte[] aesKey = rsaCipher.doFinal(encryptedKey);

                SecretKeySpec secretKeySpec = new SecretKeySpec(aesKey, "AES");
                IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);
                Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                aesCipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);
                byte[] decryptedBytes = aesCipher.doFinal(encryptedData);

                String decryptedString = new String(decryptedBytes, StandardCharsets.UTF_8);

                try {
                    return new JSONObject(decryptedString);
                } catch (JSONException e) {
                    return decryptedString;
                }
            }
            else {
                return decryptSymmetric(encryptedPackage);
            }

        } catch (Exception e) {
            Log.e(TAG, "Hybrid decryption error: " + e.getMessage(), e);
            return decryptSymmetric(encryptedPackage);
        }
    }

    /**
     * Decrypts data that was encrypted with the symmetric method
     */
    public Object decryptSymmetric(Object encryptedData) throws Exception {
        try {
            String dataToDecrypt;

            if (encryptedData instanceof JSONObject) {
                JSONObject jsonData = (JSONObject) encryptedData;

                if (!jsonData.optBoolean("encrypted", false)) {
                    return encryptedData;
                }

                dataToDecrypt = jsonData.getString("data");
            } else if (encryptedData instanceof String) {
                dataToDecrypt = (String) encryptedData;
            } else {
                throw new IllegalArgumentException("Encrypted data must be a JSONObject or String");
            }

            byte[] encryptedBytes = Base64.decode(dataToDecrypt, Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(SYMMETRIC_KEY, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(SYMMETRIC_IV);

            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

            String decryptedString = new String(decryptedBytes, StandardCharsets.UTF_8);

            try {
                return new JSONObject(decryptedString);
            } catch (JSONException e) {
                return decryptedString;
            }
        } catch (Exception e) {
            Log.e(TAG, "Symmetric decryption error: " + e.getMessage(), e);
            throw e;
        }
    }
}