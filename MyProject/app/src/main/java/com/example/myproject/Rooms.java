/**
 * Rooms - Game room creation and joining screen
 *
 * Allows users to create new game rooms or join existing ones
 * using room codes. Handles encrypted server communication.
 */
package com.example.myproject;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.IOException;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;

import okhttp3.*;

public class Rooms extends AppCompatActivity implements View.OnClickListener {
    private AlertDialog currentDialog;
    private AlertDialog loadingDialog;

    private RSAEncryption rsaEncryption;
    private HybridEncryption hybridEncryption;
    private KeyPair keyPair;
    private RSAPrivateKey privateKey;
    private static final String SERVER_URL = "http://10.0.2.2:8080";

    private String username;
    private String roomCode = "0000";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.rooms);

        findViewById(R.id.join_room).setOnClickListener(this);
        findViewById(R.id.create_room).setOnClickListener(this);
        findViewById(R.id.back_welcome).setOnClickListener(this);

        SharedPreferences prefs = getSharedPreferences("Current_Connection", MODE_PRIVATE);
        username = prefs.getString("username", "");

        rsaEncryption = new RSAEncryption(getApplicationContext());
        hybridEncryption = new HybridEncryption(getApplicationContext());

        try {
            privateKey = rsaEncryption.loadPrivateKeyFromSharedPreferences();
            if (privateKey == null) {
                Toast.makeText(this, "Error with encryption, disconnecting...", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(this, MainActivity.class);
                startActivity(intent);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error loading encryption keys: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.join_room) {
            showJoinRoomDialog();
        } else if (v.getId() == R.id.create_room) {
            showCreateRoomDialog();
        } else if (v.getId() == R.id.back_welcome) {
            Intent intent = new Intent(this, Welcome.class);
            startActivity(intent);
            finish();
        }
    }

    private void showJoinRoomDialog() {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "No network connection available", Toast.LENGTH_SHORT).show();
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_join_room, null);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        currentDialog = builder.create();

        dialogView.findViewById(R.id.join_button).setOnClickListener(v -> handleJoinButtonClick(dialogView));
        dialogView.findViewById(R.id.back_button).setOnClickListener(v -> handleBackButtonClick());

        currentDialog.show();
    }

    private void showCreateRoomDialog() {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "No network connection available", Toast.LENGTH_SHORT).show();
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_create_room, null);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        currentDialog = builder.create();

        dialogView.findViewById(R.id.create_button).setOnClickListener(v -> handleCreateButtonClick());
        dialogView.findViewById(R.id.back_button).setOnClickListener(v -> handleBackButtonClick());

        currentDialog.show();
    }

    private void showLoadingDialog(String message) {
        runOnUiThread(() -> {
            if (loadingDialog != null && loadingDialog.isShowing()) {
                loadingDialog.dismiss();
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(message);
            builder.setCancelable(false);
            loadingDialog = builder.create();
            loadingDialog.show();
        });
    }

    private void dismissLoadingDialog() {
        runOnUiThread(() -> {
            if (loadingDialog != null && loadingDialog.isShowing()) {
                loadingDialog.dismiss();
            }
        });
    }

    private void handleJoinButtonClick(View dialogView) {
        EditText room_codeInput = dialogView.findViewById(R.id.room_code);
        if (room_codeInput == null) {
            Log.e("rooms", "Room code input field not found");
            Toast.makeText(this, "Error: room code input not found", Toast.LENGTH_SHORT).show();
            return;
        }

        String room_code = room_codeInput.getText().toString();

        if (room_code.isEmpty()) {
            Toast.makeText(this, "Please enter a room code", Toast.LENGTH_SHORT).show();
        } else {
            joinRoom(room_code);
        }
    }

    private void handleCreateButtonClick() {
        createRoom();
    }

    private void handleBackButtonClick() {
        if (currentDialog != null) currentDialog.dismiss();
    }

    /** Joins existing room with provided code */
    private void joinRoom(String room_code) {
        showLoadingDialog("Joining room...");
        OkHttpClient client = new OkHttpClient();
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("username", username);
            jsonObject.put("room_code", room_code);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            RequestBody body = RequestBody.create(
                    encryptedPayload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(SERVER_URL + "/join_room_route")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("rooms", "Network failure when joining room", e);
                    runOnUiThread(() -> {
                        dismissLoadingDialog();
                        Toast.makeText(Rooms.this, "Network error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> {
                            dismissLoadingDialog();
                            Toast.makeText(Rooms.this, "Error", Toast.LENGTH_SHORT).show();
                            if (currentDialog != null) currentDialog.dismiss();
                        });
                    } else {
                        try {
                            runOnUiThread(() -> {
                                dismissLoadingDialog();
                                Toast.makeText(Rooms.this, "Joined room successfully", Toast.LENGTH_SHORT).show();
                                if (currentDialog != null) currentDialog.dismiss();

                                Intent intent = new Intent(Rooms.this, WaitingRoom.class);
                                intent.putExtra("room_code", room_code);
                                startActivity(intent);
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(Rooms.this, "Error parsing characters data", Toast.LENGTH_SHORT).show());
                        }
                    }

                }
            });
        } catch (Exception e) {
            Log.e("rooms", "Error creating JSON for join room", e);
            dismissLoadingDialog();
            Toast.makeText(this, "Error preparing request: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Creates new game room */
    private void createRoom() {
        showLoadingDialog("Creating room...");
        OkHttpClient client = new OkHttpClient();
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("username", username);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            RequestBody body = RequestBody.create(
                    encryptedPayload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(SERVER_URL + "/create_room")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("rooms", "Network failure when creating room", e);
                    runOnUiThread(() -> {
                        dismissLoadingDialog();
                        Toast.makeText(Rooms.this, "Network error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> {
                            dismissLoadingDialog();
                            Toast.makeText(Rooms.this, "Error creating room", Toast.LENGTH_SHORT).show();
                            if (currentDialog != null) currentDialog.dismiss();
                        });
                    } else {
                        try {
                            String responseBody = response.body().string();
                            JSONObject jsonResponse = new JSONObject(responseBody);

                            String method = jsonResponse.optString("method", "");
                            Object decryptedData;

                            if (method.equals("hybrid-rsa-aes")) {
                                decryptedData = hybridEncryption.decryptWithPrivateKey(jsonResponse, privateKey);
                            } else {
                                decryptedData = hybridEncryption.decryptSymmetric(jsonResponse);
                            }

                            JSONObject decryptedJson;
                            if (decryptedData instanceof JSONObject) {
                                decryptedJson = (JSONObject) decryptedData;
                            } else {
                                decryptedJson = new JSONObject(decryptedData.toString());
                            }

                            roomCode = decryptedJson.getString("room_code");
                            runOnUiThread(() -> {
                                dismissLoadingDialog();
                                Toast.makeText(Rooms.this, "Created room successfully", Toast.LENGTH_SHORT).show();
                                if (currentDialog != null) currentDialog.dismiss();

                                Intent intent = new Intent(Rooms.this, WaitingRoom.class);
                                intent.putExtra("room_code", roomCode);
                                startActivity(intent);
                            });

                        } catch (Exception e) {
                            Log.e("rooms", "Error parsing room creation response: " + e.getMessage(), e);
                            runOnUiThread(() -> {
                                dismissLoadingDialog();
                                Toast.makeText(Rooms.this, "Error reading room data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            });
                        }
                    }
                }
            });
        } catch (Exception e) {
            Log.e("rooms", "Error creating JSON for create room", e);
            dismissLoadingDialog();
            Toast.makeText(this, "Error preparing request: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        boolean isAvailable = activeNetworkInfo != null && activeNetworkInfo.isConnected();

        if (!isAvailable) {
            Log.w("rooms", "No network connection available");
        }

        return isAvailable;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
        }
    }
}