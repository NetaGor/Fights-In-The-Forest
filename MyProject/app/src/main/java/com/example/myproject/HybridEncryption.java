/**
 * Provides hybrid encryption (RSA + AES) for API requests and responses
 *
 * This class implements a hybrid encryption approach that combines the security of
 * RSA with the speed of AES encryption. RSA is used to securely transmit the AES key,
 * and AES is used for the actual data encryption. This approach is more efficient
 * for larger data payloads than using RSA alone.
 *
 * The class also provides a fallback to symmetric encryption if hybrid encryption fails.
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

    // Fixed encryption key and IV for fallback symmetric encryption (must match the server)
    private static final byte[] SYMMETRIC_KEY = "ThisIsASecretKey".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SYMMETRIC_IV = "ThisIsAnIVectors".getBytes(StandardCharsets.UTF_8);

    /**
     * Constructor that initializes the encryption components
     *
     * @param context The application context for accessing resources
     */
    public HybridEncryption(Context context) {
        this.context = context;
        this.rsaEncryption = new RSAEncryption(context);

        // Initialize server public key
        try {
            loadServerPublicKey();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load server public key: " + e.getMessage(), e);
        }
    }

    /**
     * Loads the server's public key from raw resources
     *
     * @throws Exception If loading the key fails
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
     * Gets the server's public key
     *
     * @return The server's RSA public key
     */
    public RSAPublicKey getServerPublicKey() {
        return serverPublicKey;
    }

    /**
     * Encrypts data using RSA public key to encrypt a random AES key,
     * then uses that AES key to encrypt the actual data.
     *
     * This is the primary encryption method that should be used for all API communications.
     *
     * @param data The data to encrypt (JSONObject or String)
     * @return A JSONObject containing the encrypted data package
     * @throws Exception If encryption fails
     */
    public JSONObject encryptWithPublicKey(Object data) throws Exception {
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

            // Generate a random AES key (16 bytes = 128 bits)
            byte[] aesKey = new byte[16];
            java.security.SecureRandom secureRandom = new java.security.SecureRandom();
            secureRandom.nextBytes(aesKey);

            // Generate a random IV for AES
            byte[] iv = new byte[16];
            secureRandom.nextBytes(iv);

            // Encrypt the data with AES
            SecretKeySpec secretKeySpec = new SecretKeySpec(aesKey, "AES");
            IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);
            Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); // Changed to PKCS5Padding which is available on Android
            aesCipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);
            byte[] encryptedData = aesCipher.doFinal(dataString.getBytes(StandardCharsets.UTF_8));

            // Encrypt the AES key with RSA
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding"); // Match the server's padding
            rsaCipher.init(Cipher.ENCRYPT_MODE, serverPublicKey);
            byte[] encryptedAesKey = rsaCipher.doFinal(aesKey);

            // Base64 encode everything for safe transmission
            String base64EncryptedKey = Base64.encodeToString(encryptedAesKey, Base64.NO_WRAP);
            String base64IV = Base64.encodeToString(iv, Base64.NO_WRAP);
            String base64EncryptedData = Base64.encodeToString(encryptedData, Base64.NO_WRAP);

            // Create result JSON
            JSONObject result = new JSONObject();
            result.put("encrypted", true);
            result.put("method", "hybrid-rsa-aes");
            result.put("encrypted_key", base64EncryptedKey);
            result.put("iv", base64IV);
            result.put("data", base64EncryptedData);

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Hybrid encryption error: " + e.getMessage(), e);
            // Fall back to symmetric encryption
            return encryptSymmetric(data);
        }
    }

    /**
     * Encrypts data using AES encryption with a fixed key (fallback method)
     *
     * This is used as a fallback when hybrid encryption fails. Note that this method
     * is less secure as it uses a fixed key but ensures communication can continue.
     *
     * @param data The data to encrypt (JSONObject or String)
     * @return A JSONObject containing the encrypted data
     * @throws Exception If encryption fails
     */
    public JSONObject encryptSymmetric(Object data) throws Exception {
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
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); // Changed to PKCS5Padding
            SecretKeySpec keySpec = new SecretKeySpec(SYMMETRIC_KEY, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(SYMMETRIC_IV);

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
            Log.e(TAG, "Symmetric encryption error: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Decrypts data that was encrypted with hybrid encryption
     *
     * This method decrypts data that was encrypted using the hybrid RSA+AES approach.
     * It first decrypts the AES key using the provided RSA private key, then uses
     * that key to decrypt the actual data.
     *
     * @param encryptedPackage The encrypted data package
     * @param privateKey The RSA private key for decrypting the AES key
     * @return The decrypted data as a JSONObject or String
     * @throws Exception If decryption fails
     */
    public Object decryptWithPrivateKey(JSONObject encryptedPackage, RSAPrivateKey privateKey) throws Exception {
        try {
            // Check encryption method
            String method = encryptedPackage.optString("method", "");

            // Log the incoming package for debugging
            Log.d(TAG, "Decrypting package with method: " + method);
            Log.d(TAG, "Package content: " + encryptedPackage.toString());

            // Handle hybrid encryption
            if (method.equals("hybrid-rsa-aes")) {
                // Get all the components
                String base64EncryptedKey = encryptedPackage.getString("encrypted_key");
                String base64IV = encryptedPackage.getString("iv");
                String base64EncryptedData = encryptedPackage.getString("data");

                Log.d(TAG, "Encrypted key length: " + base64EncryptedKey.length());

                // Base64 decode
                byte[] encryptedKey = Base64.decode(base64EncryptedKey, Base64.NO_WRAP);
                byte[] iv = Base64.decode(base64IV, Base64.NO_WRAP);
                byte[] encryptedData = Base64.decode(base64EncryptedData, Base64.NO_WRAP);

                // Decrypt the AES key with RSA
                Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding"); // Match the server's padding
                rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
                byte[] aesKey = rsaCipher.doFinal(encryptedKey);

                Log.d(TAG, "AES key length after decryption: " + aesKey.length);

                // Decrypt the data with AES
                SecretKeySpec secretKeySpec = new SecretKeySpec(aesKey, "AES");
                IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);
                Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); // Changed to PKCS5Padding
                aesCipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);
                byte[] decryptedBytes = aesCipher.doFinal(encryptedData);

                // Convert to string
                String decryptedString = new String(decryptedBytes, StandardCharsets.UTF_8);
                Log.d(TAG, "Decrypted string: " + decryptedString);

                // Try to parse as JSON
                try {
                    return new JSONObject(decryptedString);
                } catch (JSONException e) {
                    // Not valid JSON, return as string
                    Log.w(TAG, "Decrypted data is not valid JSON: " + e.getMessage());
                    return decryptedString;
                }
            }
            // Fall back to symmetric decryption
            else {
                Log.d(TAG, "Using symmetric decryption fallback");
                return decryptSymmetric(encryptedPackage);
            }

        } catch (Exception e) {
            Log.e(TAG, "Hybrid decryption error: " + e.getMessage(), e);
            e.printStackTrace();
            // Try symmetric decryption as a fallback
            return decryptSymmetric(encryptedPackage);
        }
    }

    /**
     * Decrypts data that was encrypted with symmetric encryption
     *
     * This method decrypts data that was encrypted using the symmetric AES approach
     * with the fixed key. It is used as a fallback when hybrid decryption fails.
     *
     * @param encryptedData The encrypted data (JSONObject or String)
     * @return The decrypted data as a JSONObject or String
     * @throws Exception If decryption fails
     */
    public Object decryptSymmetric(Object encryptedData) throws Exception {
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
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); // Changed to PKCS5Padding
            SecretKeySpec keySpec = new SecretKeySpec(SYMMETRIC_KEY, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(SYMMETRIC_IV);

            // Initialize for decryption
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            // Decrypt
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

            // Convert to string
            String decryptedString = new String(decryptedBytes, StandardCharsets.UTF_8);
            Log.d(TAG, "Symmetric decryption result: " + decryptedString);

            // Try to parse as JSON
            try {
                return new JSONObject(decryptedString);
            } catch (JSONException e) {
                // Not valid JSON, return as string
                Log.w(TAG, "Decrypted symmetric data is not valid JSON: " + e.getMessage());
                return decryptedString;
            }
        } catch (Exception e) {
            Log.e(TAG, "Symmetric decryption error: " + e.getMessage(), e);
            e.printStackTrace();
            throw e;
        }
    }
}