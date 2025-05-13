/**
 * Login - User authentication screen
 *
 * Handles user login with encrypted credentials, RSA key setup,
 * and "remember me" functionality.
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

public class Login extends AppCompatActivity implements View.OnClickListener {
    private CustomButton enter, back;
    private EditText username, password;
    private CheckBox rememberMe;

    private RSAEncryption rsaEncryption;
    private HybridEncryption hybridEncryption;
    private KeyPair keyPair;
    private RSAPublicKey publicKey;
    private RSAPrivateKey privateKey;

    private static final String SERVER_URL = "http://10.0.2.2:8080";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.login);

        enter = findViewById(R.id.btn_enter1);
        username = findViewById(R.id.user1);
        password = findViewById(R.id.password1);
        rememberMe = findViewById(R.id.remember1);
        back = findViewById(R.id.btn_back1);

        rsaEncryption = new RSAEncryption(getApplicationContext());
        hybridEncryption = new HybridEncryption(getApplicationContext());

        enter.setOnClickListener(this);
        back.setOnClickListener(this);

        try {
            keyPair = rsaEncryption.getOrCreateKeyPair();
            publicKey = (RSAPublicKey) keyPair.getPublic();
            privateKey = (RSAPrivateKey) keyPair.getPrivate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        loadSavedCredentials();
        saveCurrentConnection("");
    }

    @Override
    public void onClick(View v) {
        if (v == enter) {
            String inputUsername = username.getText().toString().trim();
            String inputPassword = password.getText().toString().trim();

            if (inputUsername.isEmpty() || inputPassword.isEmpty()) {
                Toast.makeText(this, "Please enter both username and password", Toast.LENGTH_SHORT).show();
                return;
            }

            if (inputUsername.length() > 20 || inputPassword.length() > 50) {
                Toast.makeText(this, "Input is too long", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                sendLoginRequest(inputUsername, inputPassword);
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "An error occurred", Toast.LENGTH_SHORT).show();
            }
        } else if (v == back) {
            startActivity(new Intent(Login.this, MainActivity.class));
        }
    }

    /** Sends encrypted login request to server */
    private void sendLoginRequest(String inputUsername, String inputPassword) {
        OkHttpClient client = new OkHttpClient();
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("username", inputUsername);
            jsonObject.put("password", inputPassword);

            String publicKeyBase64 = rsaEncryption.exportPublicKeyToBase64(publicKey);
            jsonObject.put("public_key", publicKeyBase64);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            RequestBody body = RequestBody.create(
                    encryptedPayload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(SERVER_URL + "/login")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> Toast.makeText(Login.this, "Request failed", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    runOnUiThread(() -> {
                        if (!response.isSuccessful()) {
                            try {
                                JSONObject jsonResponse = new JSONObject(responseBody);
                                Object decryptedResponse = hybridEncryption.decryptWithPrivateKey(jsonResponse, privateKey);

                                JSONObject result;
                                if (decryptedResponse instanceof JSONObject) {
                                    result = (JSONObject) decryptedResponse;
                                } else {
                                    result = new JSONObject((String) decryptedResponse);
                                }

                                String errorMessage = result.optString("message", "Login failed");
                                Toast.makeText(Login.this, errorMessage, Toast.LENGTH_SHORT).show();
                            } catch (Exception e) {
                                Toast.makeText(Login.this, "Login failed", Toast.LENGTH_SHORT).show();
                                e.printStackTrace();
                            }
                        } else {
                            try {
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
                                Toast.makeText(Login.this, message, Toast.LENGTH_SHORT).show();

                                if ("success".equals(status)) {
                                    if (rememberMe.isChecked()) {
                                        saveUserCredentials(inputUsername, inputPassword);
                                    } else {
                                        saveUserCredentials("", "");
                                    }

                                    saveCurrentConnection(inputUsername);
                                    startActivity(new Intent(Login.this, Welcome.class));
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                                Toast.makeText(Login.this, "Error parsing response: " + e.getMessage(),
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

    private void saveCurrentConnection(String username) {
        SharedPreferences prefs = getSharedPreferences("Current_Connection", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("username", username);
        editor.apply();
    }

    private void saveUserCredentials(String username, String password) {
        SharedPreferences prefs = getSharedPreferences("Remember_Me", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("username", username);
        editor.putString("password", password);
        editor.apply();
    }

    private void loadSavedCredentials() {
        SharedPreferences prefs = getSharedPreferences("Remember_Me", MODE_PRIVATE);
        String savedUsername = prefs.getString("username", "");
        String savedPassword = prefs.getString("password", "");

        if (!savedUsername.isEmpty() && !savedPassword.isEmpty()) {
            username.setText(savedUsername);
            password.setText(savedPassword);
            rememberMe.setChecked(true);
        }
    }
}