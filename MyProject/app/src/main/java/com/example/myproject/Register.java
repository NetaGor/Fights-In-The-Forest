/**
 * Register.java - User registration activity
 *
 * This activity handles new user registration, including secure data transmission,
 * encryption key setup, and "remember me" functionality.
 */
package com.example.myproject;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import java.io.IOException;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import okhttp3.*;

public class Register extends AppCompatActivity implements View.OnClickListener {
    private CustomButton enter, back;            // Register button
    private EditText username, password;   // Input fields for credentials
    private CheckBox rememberMe;           // Option to remember credentials
    private RSAEncryption rsaEncryption;   // For RSA encryption operations
    private HybridEncryption hybridEncryption; // For hybrid encryption operations
    private KeyPair keyPair;              // User's RSA key pair
    private RSAPublicKey publicKey;       // Public key for encryption
    private RSAPrivateKey privateKey;     // Private key for decryption
    private static final String SERVER_URL = "http://10.0.2.2:8080"; // Server URL
    private static final String REMEMBER_ME_PREFS = "Remember_Me"; // Preferences for saved credentials

    /**
     * Initializes the activity, sets up UI components, and loads encryption keys
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.register);

        // Initialize UI components
        enter = findViewById(R.id.btn_enter2);
        username = findViewById(R.id.user2);
        password = findViewById(R.id.password2);
        rememberMe = findViewById(R.id.remember2);
        back = findViewById(R.id.btn_back2);

        // Initialize encryption components
        rsaEncryption = new RSAEncryption(getApplicationContext());
        hybridEncryption = new HybridEncryption(getApplicationContext());

        enter.setOnClickListener(this);
        back.setOnClickListener(this);

        // Get or create RSA key pair
        try {
            keyPair = rsaEncryption.getOrCreateKeyPair();
            publicKey = (RSAPublicKey) keyPair.getPublic();
            privateKey = (RSAPrivateKey) keyPair.getPrivate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Clear current connection data
        saveCurrentConnection("");
    }

    /**
     * Handles register button click
     * Validates input and sends registration request
     */
    @Override
    public void onClick(View v) {
        if (v == enter) {
            String inputUsername = username.getText().toString().trim();
            String inputPassword = password.getText().toString().trim();

            // Validate input
            if (inputUsername.isEmpty() || inputPassword.isEmpty()) {
                Toast.makeText(this, "Please enter both username and password", Toast.LENGTH_SHORT).show();
                return;
            }

            if (inputUsername.length() > 20 || inputPassword.length() > 50) {
                Toast.makeText(this, "Input is too long", Toast.LENGTH_SHORT).show();
                return;
            }

            // Send login request
            try {
                sendRegistrationRequest(inputUsername, inputPassword);
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Error encrypting password", Toast.LENGTH_SHORT).show();
            }
        } else if (v == back) {
            startActivity(new Intent(Register.this, MainActivity.class));
        }
    }

    /**
     * Sends an encrypted registration request to the server
     *
     * @param inputUsername The username to register
     * @param inputPassword The password to register
     */
    private void sendRegistrationRequest(String inputUsername, String inputPassword) {
        OkHttpClient client = new OkHttpClient();
        JSONObject jsonObject = new JSONObject();
        try {
            // Prepare registration data
            jsonObject.put("username", inputUsername);
            jsonObject.put("password", inputPassword);

            // Include public key in the registration request for secure communication
            String publicKeyBase64 = rsaEncryption.exportPublicKeyToBase64(publicKey);
            jsonObject.put("public_key", publicKeyBase64);

            // Encrypt the request payload
            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            RequestBody body = RequestBody.create(
                    encryptedPayload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(SERVER_URL + "/register")
                    .post(body)
                    .build();

            // Send the request asynchronously
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> Toast.makeText(Register.this, "Request failed", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    runOnUiThread(() -> {
                        if (!response.isSuccessful()) {
                            try {
                                // Try to decrypt error response
                                JSONObject jsonResponse = new JSONObject(responseBody);
                                Object decryptedResponse = hybridEncryption.decryptWithPrivateKey(jsonResponse, privateKey);

                                JSONObject result;
                                if (decryptedResponse instanceof JSONObject) {
                                    result = (JSONObject) decryptedResponse;
                                } else {
                                    result = new JSONObject((String) decryptedResponse);
                                }

                                String errorMessage = result.optString("message", "Registration failed");
                                Toast.makeText(Register.this, errorMessage, Toast.LENGTH_SHORT).show();
                            } catch (Exception e) {
                                // If decryption fails, show generic error message
                                Toast.makeText(Register.this, "Registration failed", Toast.LENGTH_SHORT).show();
                                e.printStackTrace();
                            }
                        } else {
                            try {
                                // Decrypt the successful response
                                JSONObject jsonResponse = new JSONObject(responseBody);
                                Object decryptedResponse = hybridEncryption.decryptWithPrivateKey(jsonResponse, privateKey);

                                JSONObject result;
                                if (decryptedResponse instanceof JSONObject) {
                                    result = (JSONObject) decryptedResponse;
                                } else {
                                    result = new JSONObject((String) decryptedResponse);
                                }

                                String status = result.getString("status");
                                String message = result.getString("message");
                                Toast.makeText(Register.this, message, Toast.LENGTH_SHORT).show();

                                // Handle successful registration
                                if ("success".equals(status)) {
                                    // Save or clear credentials based on remember me checkbox
                                    if (rememberMe.isChecked()) {
                                        saveUserCredentials(inputUsername, inputPassword);
                                    } else {
                                        saveUserCredentials("", "");
                                    }

                                    // Save current connection data
                                    saveCurrentConnection(inputUsername);

                                    // Navigate to Welcome screen
                                    startActivity(new Intent(Register.this, Welcome.class));
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                                Toast.makeText(Register.this, "Error parsing response: " + e.getMessage(),
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error encrypting data", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Saves the current user connection data
     *
     * @param username The username to save
     */
    private void saveCurrentConnection(String username) {
        SharedPreferences prefs = getSharedPreferences("Current_Connection", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("username", username);
        editor.apply();
    }

    /**
     * Saves user credentials for "remember me" functionality
     *
     * @param username The username to save
     * @param password The password to save
     */
    private void saveUserCredentials(String username, String password) {
        SharedPreferences prefs = getSharedPreferences(REMEMBER_ME_PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("username", username);
        editor.putString("password", password);
        editor.apply();
    }
}