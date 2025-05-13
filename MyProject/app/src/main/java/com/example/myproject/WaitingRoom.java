/**
 * WaitingRoom - Game lobby
 *
 * Players join teams, select characters, and prepare for battle.
 * Manages real-time team sync via WebSocket and encrypted communication.
 */
package com.example.myproject;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayList;
import java.util.List;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

import okhttp3.*;

public class WaitingRoom extends AppCompatActivity implements View.OnClickListener {
    private ListView listRoomGroup1, listRoomGroup2;
    private TextView room_code_display;
    private CustomButton playButton, joinGroup1Button, joinGroup2Button;

    private static final String TAG = "WaitingRoom";
    private static final String SERVER_URL = "http://10.0.2.2:8080";

    static List<CharactersList> myCharacters = new ArrayList<>();
    static List<CharactersList> group1 = new ArrayList<>();
    static List<CharactersList> group2 = new ArrayList<>();
    private CharactersListAdapter characterAdapterGroup1, characterAdapterGroup2;

    private String currentPlayerCharacter = null;
    private String currentGroup = null;
    private String username, room_code;
    private boolean is_pressed = false;
    private boolean isTransitioningToGameplay = false;
    private AlertDialog countdownDialog;

    private RSAEncryption rsaEncryption;
    private HybridEncryption hybridEncryption;
    private OkHttpClient client;
    private RSAPrivateKey privateKey;
    private AlertDialog loadingDialog;
    private Socket mSocket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.waiting_room);

        client = new OkHttpClient();
        initializeUIComponents();

        room_code = getIntent().getStringExtra("room_code");

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

        if (GlobalSocketManager.getSocket() != null) {
            mSocket = GlobalSocketManager.getSocket();
            setupSocketListeners();
            joinRoom();
        } else {
            setupWebSocket();
        }

        room_code_display.setText("Room Code: " + room_code);

        initializeAdapters();
        refreshGroupsData();

        loadCharacters();
    }

    private void initializeUIComponents() {
        listRoomGroup1 = findViewById(R.id.list_group1);
        listRoomGroup2 = findViewById(R.id.list_group2);
        room_code_display = findViewById(R.id.code);
        playButton = findViewById(R.id.play);
        joinGroup1Button = findViewById(R.id.join_group1);
        joinGroup2Button = findViewById(R.id.join_group2);

        joinGroup1Button.setOnClickListener(this);
        joinGroup2Button.setOnClickListener(this);
        playButton.setOnClickListener(this);
    }

    private void initializeAdapters() {
        characterAdapterGroup1 = new CharactersListAdapter(this, group1);
        listRoomGroup1.setAdapter(characterAdapterGroup1);

        characterAdapterGroup2 = new CharactersListAdapter(this, group2);
        listRoomGroup2.setAdapter(characterAdapterGroup2);
    }

    private void setupSocketListeners() {
        mSocket.on("new_player", onNewPlayer);
        mSocket.on("group_change", onGroupChange);
        mSocket.on("player_ready", onPlayerReady);
        mSocket.on("player_unready", onPlayerUnready);
        mSocket.on("game_started", onGameStarted);
        mSocket.on("validate_connection", onValidateConnection);
        mSocket.on("game_start_failed", onGameStartFailed);
        mSocket.on("update", onUpdate);
    }

    private void setupWebSocket() {
        try {
            IO.Options opts = new IO.Options();
            opts.forceNew = true;
            opts.reconnection = true;
            opts.reconnectionAttempts = 10;
            opts.reconnectionDelay = 1000;

            mSocket = IO.socket(SERVER_URL, opts);

            mSocket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    runOnUiThread(() -> {
                        Log.d(TAG, "WebSocket Connected");
                        joinRoom();
                    });
                }
            });

            setupSocketListeners();

            mSocket.connect();

        } catch (URISyntaxException e) {
            Log.e(TAG, "WebSocket connection error", e);
            Toast.makeText(this, "Failed to connect to server", Toast.LENGTH_SHORT).show();
        }
    }

    /** Handle new player joining room */
    private Emitter.Listener onNewPlayer = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            runOnUiThread(() -> {
                try {
                    JSONObject encryptedData = (JSONObject) args[0];

                    String method = encryptedData.optString("method", "");
                    Object decryptedData;

                    if (method.equals("hybrid-rsa-aes")) {
                        decryptedData = hybridEncryption.decryptWithPrivateKey(encryptedData, privateKey);
                    } else {
                        decryptedData = hybridEncryption.decryptSymmetric(encryptedData);
                    }

                    JSONObject data;
                    if (decryptedData instanceof JSONObject) {
                        data = (JSONObject) decryptedData;
                    } else {
                        data = new JSONObject(decryptedData.toString());
                    }

                    String newUsername = data.getString("username");
                    Toast.makeText(WaitingRoom.this, newUsername + " joined the room", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "Error processing new player: " + e.getMessage(), e);
                }
            });
        }
    };

    /** Handle player changing groups */
    private Emitter.Listener onGroupChange = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            runOnUiThread(() -> {
                try {
                    JSONObject encryptedData = (JSONObject) args[0];

                    String method = encryptedData.optString("method", "");
                    Object decryptedData;

                    if (method.equals("hybrid-rsa-aes")) {
                        decryptedData = hybridEncryption.decryptWithPrivateKey(encryptedData, privateKey);
                    } else {
                        decryptedData = hybridEncryption.decryptSymmetric(encryptedData);
                    }

                    JSONObject data;
                    if (decryptedData instanceof JSONObject) {
                        data = (JSONObject) decryptedData;
                    } else {
                        data = new JSONObject(decryptedData.toString());
                    }

                    String playerUsername = data.getString("username");
                    String characterName = data.getString("character_name");
                    String group = data.getString("group");

                    refreshGroupsData();

                    if (group.equals("group1")) {
                        characterAdapterGroup1.notifyDataSetChanged();
                    } else {
                        characterAdapterGroup2.notifyDataSetChanged();
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error processing group change: " + e.getMessage(), e);
                }
            });
        }
    };

    /** Handle player ready status */
    private Emitter.Listener onPlayerReady = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            runOnUiThread(() -> {
                try {
                    JSONObject encryptedData = (JSONObject) args[0];

                    String method = encryptedData.optString("method", "");
                    Object decryptedData;

                    if (method.equals("hybrid-rsa-aes")) {
                        decryptedData = hybridEncryption.decryptWithPrivateKey(encryptedData, privateKey);
                    } else {
                        decryptedData = hybridEncryption.decryptSymmetric(encryptedData);
                    }

                    JSONObject data;
                    if (decryptedData instanceof JSONObject) {
                        data = (JSONObject) decryptedData;
                    } else {
                        data = new JSONObject(decryptedData.toString());
                    }

                    String playerUsername = data.getString("username");
                    Toast.makeText(WaitingRoom.this,
                            playerUsername + " is ready to fight!",
                            Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "Error processing player ready: " + e.getMessage(), e);
                }
            });
        }
    };

    /** Handle player unready status */
    private Emitter.Listener onPlayerUnready = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            runOnUiThread(() -> {
                try {
                    JSONObject encryptedData = (JSONObject) args[0];

                    String method = encryptedData.optString("method", "");
                    Object decryptedData;

                    if (method.equals("hybrid-rsa-aes")) {
                        decryptedData = hybridEncryption.decryptWithPrivateKey(encryptedData, privateKey);
                    } else {
                        decryptedData = hybridEncryption.decryptSymmetric(encryptedData);
                    }

                    JSONObject data;
                    if (decryptedData instanceof JSONObject) {
                        data = (JSONObject) decryptedData;
                    } else {
                        data = new JSONObject(decryptedData.toString());
                    }

                    String playerUsername = data.getString("username");
                    Toast.makeText(WaitingRoom.this,
                            playerUsername + " is no longer ready",
                            Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "Error processing player unready: " + e.getMessage(), e);
                }
            });
        }
    };

    /** Handle game start notification */
    private Emitter.Listener onGameStarted = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            runOnUiThread(() -> {
                try {
                    if (countdownDialog != null && countdownDialog.isShowing()) {
                        countdownDialog.dismiss();
                    }

                    isTransitioningToGameplay = true;

                    Toast.makeText(WaitingRoom.this, "Game is starting!", Toast.LENGTH_SHORT).show();

                    GlobalSocketManager.setSocket(mSocket);

                    Intent intent = new Intent(WaitingRoom.this, Gameplay.class);
                    intent.putExtra("room_code", room_code);
                    intent.putExtra("character_name", currentPlayerCharacter);
                    intent.putExtra("group", currentGroup);
                    startActivity(intent);
                    finish();
                } catch (Exception e) {
                    Log.e(TAG, "Error processing game started: " + e.getMessage(), e);
                    isTransitioningToGameplay = true;

                    Toast.makeText(WaitingRoom.this, "Game is starting!", Toast.LENGTH_SHORT).show();

                    GlobalSocketManager.setSocket(mSocket);

                    Intent intent = new Intent(WaitingRoom.this, Gameplay.class);
                    intent.putExtra("room_code", room_code);
                    intent.putExtra("character_name", currentPlayerCharacter);
                    intent.putExtra("group", currentGroup);
                    startActivity(intent);
                    finish();
                }
            });
        }
    };

    /** Handle connection validation before game start */
    private Emitter.Listener onValidateConnection = args -> {
        try {
            JSONObject encryptedData = (JSONObject) args[0];

            String method = encryptedData.optString("method", "");
            Object decryptedData;

            if (method.equals("hybrid-rsa-aes")) {
                decryptedData = hybridEncryption.decryptWithPrivateKey(encryptedData, privateKey);
            } else {
                decryptedData = hybridEncryption.decryptSymmetric(encryptedData);
            }

            JSONObject data;
            if (decryptedData instanceof JSONObject) {
                data = (JSONObject) decryptedData;
            } else {
                data = new JSONObject(decryptedData.toString());
            }

            String roomCode = data.getString("room_code");
            int timeout = data.optInt("timeout", 10);

            runOnUiThread(() -> {
                showCountdownDialog(timeout);
            });

            try {
                JSONObject readyData = new JSONObject();
                readyData.put("username", username);
                readyData.put("room_code", room_code);

                JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(readyData);

                mSocket.emit("connection_ready", encryptedPayload);

                Log.d(TAG, "Sent connection ready confirmation");
            } catch (Exception e) {
                Log.e(TAG, "Error sending ready confirmation: " + e.getMessage(), e);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing validate connection event: " + e.getMessage(), e);
        }
    };

    /** Handle game start failure */
    private Emitter.Listener onGameStartFailed = args -> {
        try {
            JSONObject encryptedData = (JSONObject) args[0];

            String method = encryptedData.optString("method", "");
            Object decryptedData;

            if (method.equals("hybrid-rsa-aes")) {
                decryptedData = hybridEncryption.decryptWithPrivateKey(encryptedData, privateKey);
            } else {
                decryptedData = hybridEncryption.decryptSymmetric(encryptedData);
            }

            JSONObject data;
            if (decryptedData instanceof JSONObject) {
                data = (JSONObject) decryptedData;
            } else {
                data = new JSONObject(decryptedData.toString());
            }

            String reason = data.getString("reason");

            runOnUiThread(() -> {
                if (countdownDialog != null && countdownDialog.isShowing()) {
                    countdownDialog.dismiss();
                }

                Toast.makeText(WaitingRoom.this,
                        "Game failed to start: " + reason,
                        Toast.LENGTH_LONG).show();

                is_pressed = false;
                playButton.setButtonText("FIGHT!");
            });

        } catch (Exception e) {
            Log.e(TAG, "Error processing game start failed event: " + e.getMessage(), e);
        }
    };
    /** Handle updates (mainly when a player leaves the room) */
    private Emitter.Listener onUpdate = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            runOnUiThread(() -> {
                try {
                    JSONObject encryptedData = (JSONObject) args[0];

                    String method = encryptedData.optString("method", "");
                    Object decryptedData;

                    if (method.equals("hybrid-rsa-aes")) {
                        decryptedData = hybridEncryption.decryptWithPrivateKey(encryptedData, privateKey);
                    } else {
                        decryptedData = hybridEncryption.decryptSymmetric(encryptedData);
                    }

                    JSONObject data;
                    if (decryptedData instanceof JSONObject) {
                        data = (JSONObject) decryptedData;
                    } else {
                        data = new JSONObject(decryptedData.toString());
                    }

                    String type = data.getString("type");
                    String affectedUsername = data.getString("username");

                    if (type.equals("player_left") || type.equals("player_removed")) {
                        Toast.makeText(WaitingRoom.this,
                                affectedUsername + " has left the room",
                                Toast.LENGTH_SHORT).show();

                        // Refresh the UI to show updated groups
                        refreshGroupsData();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing update: " + e.getMessage(), e);
                }
            });
        }
    };


    /** Shows countdown before game starts */
    private void showCountdownDialog(int seconds) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Game Starting Soon")
                .setMessage("Game will start in " + seconds + " seconds...\nPreparing game environment...")
                .setCancelable(false);

        countdownDialog = builder.create();
        countdownDialog.show();

        new Handler().postDelayed(() -> {
            if (countdownDialog != null && countdownDialog.isShowing()) {
                countdownDialog.dismiss();
            }
        }, seconds * 1000);
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.join_group1 || view.getId() == R.id.join_group2) {
            handleGroupJoin(view);
        } else if (view.getId() == R.id.play) {
            handlePlayButtonClick();
        }
    }

    private void joinRoom() {
        try {
            JSONObject roomData = new JSONObject();
            roomData.put("username", username);
            roomData.put("room_code", room_code);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(roomData);

            mSocket.emit("join_room", encryptedPayload);
        } catch (Exception e) {
            Log.e(TAG, "Error encrypting join room data", e);
        }
    }

    /** Handles player selecting a group */
    private void handleGroupJoin(View view) {
        if (myCharacters.isEmpty()) {
            Toast.makeText(this, "You don't have any characters to select", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] characterNames = new String[myCharacters.size()];
        for (int i = 0; i < myCharacters.size(); i++) {
            characterNames[i] = myCharacters.get(i).getCharacterName();
        }

        new AlertDialog.Builder(this)
                .setTitle("Choose a Character")
                .setItems(characterNames, (dialog, which) -> {
                    CharactersList selectedCharacter = myCharacters.get(which);
                    currentPlayerCharacter = selectedCharacter.getCharacterName();
                    currentGroup = (view.getId() == R.id.join_group1) ? "group1" : "group2";

                    try {
                        JSONObject groupData = new JSONObject();
                        groupData.put("username", username);
                        groupData.put("room_code", room_code);
                        groupData.put("group", currentGroup);
                        groupData.put("character_name", currentPlayerCharacter);

                        JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(groupData);

                        mSocket.emit("join_group", encryptedPayload);
                    } catch (Exception e) {
                        Log.e(TAG, "Error encrypting join group data", e);
                    }
                })
                .show();
    }

    /** Handles ready/unready toggle */
    private void handlePlayButtonClick() {
        if (currentPlayerCharacter == null || currentGroup == null) {
            Toast.makeText(this, "Please select a character and join a group first", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject readyData = new JSONObject();
            readyData.put("username", username);
            readyData.put("room_code", room_code);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(readyData);

            if (is_pressed) {
                mSocket.emit("unpress_ready", encryptedPayload);
                playButton.setButtonText("FIGHT!");
                is_pressed = false;
            } else {
                mSocket.emit("press_ready", encryptedPayload);
                playButton.setButtonText("CANCEL");
                is_pressed = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error encrypting ready data", e);
        }
    }

    /** Loads user's characters from server */
    private void loadCharacters() {
        showLoadingDialog("Loading characters...");
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("username", username);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            RequestBody body = RequestBody.create(
                    encryptedPayload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(SERVER_URL + "/get_characters")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        dismissLoadingDialog();
                        Toast.makeText(WaitingRoom.this, "Failed to load characters: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Failed to load user characters: " + e.getMessage());
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> {
                            dismissLoadingDialog();
                            Toast.makeText(WaitingRoom.this, "Error fetching characters: " + response.code(),
                                    Toast.LENGTH_SHORT).show();
                            Log.e(TAG, "Error fetching user characters: " + response.code());
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

                            JSONArray charactersArray = decryptedJson.getJSONArray("characters");

                            myCharacters.clear();
                            for (int i = 0; i < charactersArray.length(); i++) {
                                JSONObject characterObject = charactersArray.getJSONObject(i);
                                CharactersList character = new CharactersList(
                                        characterObject.getString("name"),
                                        characterObject.getString("desc"),
                                        username
                                );
                                JSONArray abilitiesArray = characterObject.getJSONArray("abilities");
                                List<String> abilities = new ArrayList<>();
                                for (int j = 0; j < abilitiesArray.length(); j++) {
                                    abilities.add(abilitiesArray.getString(j));
                                }
                                character.setAbilities(abilities);
                                myCharacters.add(character);
                            }

                            runOnUiThread(() -> {
                                dismissLoadingDialog();
                                Log.d(TAG, "Loaded " + myCharacters.size() + " user characters");
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                dismissLoadingDialog();
                                Toast.makeText(WaitingRoom.this, "Error parsing characters data: " + e.getMessage(),
                                        Toast.LENGTH_SHORT).show();
                                Log.e(TAG, "Error parsing user characters: " + e.getMessage());
                            });
                        }
                    }
                }
            });
        } catch (Exception e) {
            dismissLoadingDialog();
            e.printStackTrace();
            Toast.makeText(this, "Error encrypting data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error encrypting data for user characters: " + e.getMessage());
        }
    }

    private void refreshGroupsData() {
        Log.d(TAG, "Refreshing groups data");
        new Handler().postDelayed(() -> {
            getGroup1();
            getGroup2();
        }, 300);
    }

    /** Gets group 1 data */
    private void getGroup1() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("room_code", room_code);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            RequestBody body = RequestBody.create(
                    encryptedPayload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(SERVER_URL + "/get_group1")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Failed to load group1: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "Error fetching group1: " + response.code());
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

                            JSONArray charactersArray = decryptedJson.getJSONArray("characters");

                            List<CharactersList> newGroup1 = new ArrayList<>();
                            for (int i = 0; i < charactersArray.length(); i++) {
                                JSONObject characterObject = charactersArray.getJSONObject(i);
                                CharactersList character = new CharactersList(
                                        characterObject.getString("name"),
                                        characterObject.getString("username"),
                                        characterObject.getString("username")
                                );
                                newGroup1.add(character);
                            }

                            runOnUiThread(() -> {
                                group1.clear();
                                group1.addAll(newGroup1);
                                characterAdapterGroup1.notifyDataSetChanged();
                                Log.d(TAG, "Updated group1 with " + group1.size() + " characters");
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing group1 data: " + e.getMessage());
                        }
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating request for group1: " + e.getMessage());
        }
    }

    /** Gets group 2 data */
    private void getGroup2() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("room_code", room_code);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            RequestBody body = RequestBody.create(
                    encryptedPayload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(SERVER_URL + "/get_group2")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Failed to load group2: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "Error fetching group2: " + response.code());
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

                            JSONArray charactersArray = decryptedJson.getJSONArray("characters");

                            List<CharactersList> newGroup2 = new ArrayList<>();
                            for (int i = 0; i < charactersArray.length(); i++) {
                                JSONObject characterObject = charactersArray.getJSONObject(i);
                                CharactersList character = new CharactersList(
                                        characterObject.getString("name"),
                                        characterObject.getString("username"),
                                        characterObject.getString("username")
                                );
                                newGroup2.add(character);
                            }

                            runOnUiThread(() -> {
                                group2.clear();
                                group2.addAll(newGroup2);
                                characterAdapterGroup2.notifyDataSetChanged();
                                Log.d(TAG, "Updated group2 with " + group2.size() + " characters");
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing group2 data: " + e.getMessage());
                        }
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating request for group2: " + e.getMessage());
        }
    }

    /** Removes player from room on server */
    private void removePlayerFromRoom() {
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
                    .url(SERVER_URL + "/remove_player_from_room")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Failed to remove player from room: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "Error removing player from room: " + response.code());
                    } else {
                        Log.d(TAG, "Player removed from room successfully");
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "Error encrypting data for room removal: " + e.getMessage());
        }
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

    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("Leave Room")
                .setMessage("Are you sure you want to leave this room?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    isTransitioningToGameplay = false;
                    removePlayerFromRoom();
                    finish();
                })
                .setNegativeButton("No", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mSocket != null && !mSocket.connected()) {
            mSocket.connect();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSocket != null) {
            try {
                mSocket.off("new_player", onNewPlayer);
                mSocket.off("group_change", onGroupChange);
                mSocket.off("player_ready", onPlayerReady);
                mSocket.off("player_unready", onPlayerUnready);
                mSocket.off("game_started", onGameStarted);
                mSocket.off("validate_connection", onValidateConnection);
                mSocket.off("game_start_failed", onGameStartFailed);
                mSocket.off("update", onUpdate);

                if (!isTransitioningToGameplay) {
                    mSocket.disconnect();
                    GlobalSocketManager.setSocket(null);

                    Log.d(TAG, "Destroying waiting room - removing player from room");
                    removePlayerFromRoom();
                } else {
                    Log.d(TAG, "Transitioning to gameplay - keeping player in room");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during socket cleanup: " + e.getMessage(), e);
            }
        }
    }
}