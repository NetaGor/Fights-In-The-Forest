/**
 * WaitingRoom Activity
 *
 * This activity serves as a lobby where players can join teams, select characters,
 * and prepare before the actual gameplay begins. It manages team formation, character selection,
 * and real-time synchronization between all players using WebSockets.
 *
 * Communication with the server is secured using hybrid encryption (RSA + AES).
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
    private static final String TAG = "WaitingRoom";
    private static final String SERVER_URL = "http://10.0.2.2:8080";

    // UI Components
    private ListView listRoomGroup1, listRoomGroup2;
    private TextView room_code_display;
    private CustomButton playButton, joinGroup1Button, joinGroup2Button;

    // Data Management
    static List<CharactersList> myCharacters = new ArrayList<>();
    static List<CharactersList> group1 = new ArrayList<>();
    static List<CharactersList> group2 = new ArrayList<>();
    private CharactersListAdapter characterAdapterGroup1, characterAdapterGroup2;

    // WebSocket Connection
    private Socket mSocket;

    // Game State
    private String currentPlayerCharacter = null;
    private String currentGroup = null;
    private String username, room_code;
    private boolean is_pressed = false;
    private boolean isTransitioningToGameplay = false;
    private AlertDialog countdownDialog;

    // Network Components
    private RSAEncryption rsaEncryption;
    private HybridEncryption hybridEncryption;
    private OkHttpClient client;
    private KeyPair keyPair;
    private RSAPrivateKey privateKey;
    private AlertDialog loadingDialog;

    /**
     * Initializes the waiting room activity when it is first created.
     *
     * This method performs the following tasks:
     * - Sets up the activity layout and initializes UI components
     * - Retrieves room code and username information from intent and shared preferences
     * - Initializes RSA and hybrid encryption systems
     * - Loads private key for encryption; returns to MainActivity if encryption setup fails
     * - Establishes or reuses WebSocket connection through GlobalSocketManager
     * - Sets up socket event listeners and joins the room
     * - Initializes adapters and loads group data
     * - Loads available characters for selection
     *
     * @param savedInstanceState Bundle containing the activity's previously saved state, if any
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.waiting_room);

        // Initialize components
        client = new OkHttpClient();
        initializeUIComponents();

        // Get room and user details
        room_code = getIntent().getStringExtra("room_code");

        SharedPreferences prefs = getSharedPreferences("Current_Connection", MODE_PRIVATE);
        username = prefs.getString("username", "");

        // Initialize encryption
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

        // Check if there's a socket in the manager first
        if (GlobalSocketManager.getSocket() != null) {
            mSocket = GlobalSocketManager.getSocket();
            // Add event listeners
            setupSocketListeners();
            // Join room with existing socket
            joinRoom();
        } else {
            // Create a new socket if none exists
            setupWebSocket();
        }

        // Set room code display
        room_code_display.setText("Room Code: " + room_code);

        // Initialize adapters
        initializeAdapters();
        refreshGroupsData();

        // Load characters
        loadCharacters();
    }

    /**
     * Initializes UI components and sets up click listeners for buttons.
     */
    private void initializeUIComponents() {
        listRoomGroup1 = findViewById(R.id.list_group1);
        listRoomGroup2 = findViewById(R.id.list_group2);
        room_code_display = findViewById(R.id.code);
        playButton = findViewById(R.id.play);
        joinGroup1Button = findViewById(R.id.join_group1);
        joinGroup2Button = findViewById(R.id.join_group2);

        // Set click listeners
        joinGroup1Button.setOnClickListener(this);
        joinGroup2Button.setOnClickListener(this);
        playButton.setOnClickListener(this);
    }
    /**
     * Initializes list adapters for both character groups.
     */
    private void initializeAdapters() {
        characterAdapterGroup1 = new CharactersListAdapter(this, group1);
        listRoomGroup1.setAdapter(characterAdapterGroup1);

        characterAdapterGroup2 = new CharactersListAdapter(this, group2);
        listRoomGroup2.setAdapter(characterAdapterGroup2);
    }
    /**
     * Sets up all socket event listeners for real-time communication.
     */
    private void setupSocketListeners() {
        mSocket.on("new_player", onNewPlayer);
        mSocket.on("group_change", onGroupChange);
        mSocket.on("player_ready", onPlayerReady);
        mSocket.on("player_unready", onPlayerUnready);
        mSocket.on("game_started", onGameStarted);
        mSocket.on("validate_connection", onValidateConnection);
        mSocket.on("game_start_failed", onGameStartFailed);
    }
    /**
     * Configures and establishes WebSocket connection with server.
     */
    private void setupWebSocket() {
        try {
            // Configure Socket.IO connection options
            IO.Options opts = new IO.Options();
            opts.forceNew = true;
            opts.reconnection = true;
            opts.reconnectionAttempts = 10;
            opts.reconnectionDelay = 1000;

            // Create socket connection
            mSocket = IO.socket(SERVER_URL, opts);

            // Connection event listeners
            mSocket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    runOnUiThread(() -> {
                        Log.d(TAG, "WebSocket Connected");
                        joinRoom();
                    });
                }
            });

            // Setup all event listeners
            setupSocketListeners();

            // Connect to WebSocket
            mSocket.connect();

        } catch (URISyntaxException e) {
            Log.e(TAG, "WebSocket connection error", e);
            Toast.makeText(this, "Failed to connect to server", Toast.LENGTH_SHORT).show();
        }
    }

    // WebSocket Event Listeners
    /**
     * WebSocket event listener that handles when a new player joins the room.
     */
    private Emitter.Listener onNewPlayer = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            runOnUiThread(() -> {
                try {
                    JSONObject encryptedData = (JSONObject) args[0];

                    // Decrypt the incoming data using hybrid decryption
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
    /**
     * WebSocket event listener that processes player group changes.
     */
    private Emitter.Listener onGroupChange = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            runOnUiThread(() -> {
                try {
                    JSONObject encryptedData = (JSONObject) args[0];

                    // Decrypt the incoming data using hybrid decryption
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

                    // Update group lists
                    refreshGroupsData();

                    // Notify adapters
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
    /**
     * WebSocket event listener that handles when a player indicates they are ready.
     */
    private Emitter.Listener onPlayerReady = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            runOnUiThread(() -> {
                try {
                    JSONObject encryptedData = (JSONObject) args[0];

                    // Decrypt the incoming data using hybrid decryption
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
    /**
     * WebSocket event listener that handles when a player is no longer ready.
     */
    private Emitter.Listener onPlayerUnready = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            runOnUiThread(() -> {
                try {
                    JSONObject encryptedData = (JSONObject) args[0];

                    // Decrypt the incoming data using hybrid decryption
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
    /**
     * WebSocket event listener that processes game start events.
     */
    private Emitter.Listener onGameStarted = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            runOnUiThread(() -> {
                try {
                    if (args.length > 0 && args[0] instanceof JSONObject) {
                        JSONObject encryptedData = (JSONObject) args[0];

                        // Try to decrypt if it seems to be encrypted
                        if (encryptedData.has("method") || encryptedData.has("data")) {
                            // Decrypt the incoming data using hybrid decryption
                            String method = encryptedData.optString("method", "");
                            if (method.equals("hybrid-rsa-aes")) {
                                hybridEncryption.decryptWithPrivateKey(encryptedData, privateKey);
                            } else if (encryptedData.has("data")) {
                                hybridEncryption.decryptSymmetric(encryptedData);
                            }
                        }
                    }

                    // Dismiss countdown dialog if it's showing
                    if (countdownDialog != null && countdownDialog.isShowing()) {
                        countdownDialog.dismiss();
                    }

                    // Set flag to indicate transition to gameplay
                    isTransitioningToGameplay = true;

                    Toast.makeText(WaitingRoom.this, "Game is starting!", Toast.LENGTH_SHORT).show();

                    // Store the socket in the global manager
                    GlobalSocketManager.setSocket(mSocket);

                    // Start the game activity
                    Intent intent = new Intent(WaitingRoom.this, Gameplay.class);
                    intent.putExtra("room_code", room_code);
                    intent.putExtra("character_name", currentPlayerCharacter);
                    intent.putExtra("group", currentGroup);
                    startActivity(intent);
                    finish();
                } catch (Exception e) {
                    Log.e(TAG, "Error processing game started: " + e.getMessage(), e);
                    // Still try to start the game
                    isTransitioningToGameplay = true;

                    Toast.makeText(WaitingRoom.this, "Game is starting!", Toast.LENGTH_SHORT).show();

                    // Store the socket in the global manager
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
    /**
     * WebSocket event listener for connection validation before game start.
     */
    private Emitter.Listener onValidateConnection = args -> {
        try {
            JSONObject encryptedData = (JSONObject) args[0];

            // Decrypt the incoming data
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

            // Extract data from the validation request
            String roomCode = data.getString("room_code");
            int timeout = data.optInt("timeout", 10);

            // Show a countdown dialog
            runOnUiThread(() -> {
                showCountdownDialog(timeout);
            });

            // Send ready confirmation back to the server
            try {
                JSONObject readyData = new JSONObject();
                readyData.put("username", username);
                readyData.put("room_code", room_code);

                // Encrypt using hybrid encryption
                JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(readyData);

                // Emit connection ready event
                mSocket.emit("connection_ready", encryptedPayload);

                Log.d(TAG, "Sent connection ready confirmation");
            } catch (Exception e) {
                Log.e(TAG, "Error sending ready confirmation: " + e.getMessage(), e);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing validate connection event: " + e.getMessage(), e);
        }
    };
    /**
     * WebSocket event listener that handles game start failure scenarios.
     */
    private Emitter.Listener onGameStartFailed = args -> {
        try {
            JSONObject encryptedData = (JSONObject) args[0];

            // Decrypt the data
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
                // Dismiss countdown dialog if it's showing
                if (countdownDialog != null && countdownDialog.isShowing()) {
                    countdownDialog.dismiss();
                }

                // Show failure message
                Toast.makeText(WaitingRoom.this,
                        "Game failed to start: " + reason,
                        Toast.LENGTH_LONG).show();

                // Reset the UI
                is_pressed = false;
                playButton.setButtonText("FIGHT!");
            });

        } catch (Exception e) {
            Log.e(TAG, "Error processing game start failed event: " + e.getMessage(), e);
        }
    };

    /**
     * Displays a countdown dialog with the specified duration.
     * @param seconds The countdown duration in seconds
     */
    private void showCountdownDialog(int seconds) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Game Starting Soon")
                .setMessage("Game will start in " + seconds + " seconds...\nPreparing game environment...")
                .setCancelable(false);

        countdownDialog = builder.create();
        countdownDialog.show();

        // Auto dismiss after timeout
        new Handler().postDelayed(() -> {
            if (countdownDialog != null && countdownDialog.isShowing()) {
                countdownDialog.dismiss();
            }
        }, seconds * 1000);
    }

    /**
     * Handles button click events for the activity.
     * @param view The view that was clicked
     */
    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.join_group1 || view.getId() == R.id.join_group2) {
            handleGroupJoin(view);
        } else if (view.getId() == R.id.play) {
            handlePlayButtonClick();
        }
    }

    /**
     * Sends request to join room with encrypted credentials.
     */
    private void joinRoom() {
        try {
            JSONObject roomData = new JSONObject();
            roomData.put("username", username);
            roomData.put("room_code", room_code);

            // Encrypt using hybrid encryption
            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(roomData);

            mSocket.emit("join_room", encryptedPayload);
        } catch (Exception e) {
            Log.e(TAG, "Error encrypting join room data", e);
        }
    }
    /**
     * Handles the process of joining a character group.
     * @param view The button view that triggered the action
     */
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

                    // Emit group join via WebSocket with encryption
                    try {
                        JSONObject groupData = new JSONObject();
                        groupData.put("username", username);
                        groupData.put("room_code", room_code);
                        groupData.put("group", currentGroup);
                        groupData.put("character_name", currentPlayerCharacter);

                        // Encrypt using hybrid encryption
                        JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(groupData);

                        mSocket.emit("join_group", encryptedPayload);
                    } catch (Exception e) {
                        Log.e(TAG, "Error encrypting join group data", e);
                    }
                })
                .show();
    }
    /**
     * Processes play button clicks for toggling ready status.
     */
    private void handlePlayButtonClick() {
        if (currentPlayerCharacter == null || currentGroup == null) {
            Toast.makeText(this, "Please select a character and join a group first", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject readyData = new JSONObject();
            readyData.put("username", username);
            readyData.put("room_code", room_code);

            // Encrypt using hybrid encryption
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

    /**
     * Retrieves user's characters from server with encryption.
     */
    private void loadCharacters() {
        showLoadingDialog("Loading characters...");
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("username", username);

            // Encrypt using hybrid encryption - same as CharactersCreationRoom
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

                            // Determine decryption method based on response
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
    /**
     * Refreshes both group data lists with a short delay.
     */
    private void refreshGroupsData() {
        Log.d(TAG, "Refreshing groups data");
        new Handler().postDelayed(() -> {
            getGroup1();
            getGroup2();
        }, 300);
    }
    /**
     * Retrieves encrypted group 1 character data from server.
     */
    private void getGroup1() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("room_code", room_code);

            // Encrypt using hybrid encryption
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

                            // Determine decryption method based on response
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
    /**
     * Retrieves encrypted group 2 character data from server.
     */
    private void getGroup2() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("room_code", room_code);

            // Encrypt using hybrid encryption
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

                            // Determine decryption method based on response
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
    /**
     * Sends request to remove player from current room.
     */
    private void removePlayerFromRoom() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("username", username);
            jsonObject.put("room_code", room_code);

            // Encrypt using hybrid encryption
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

    /**
     * Displays a loading dialog with custom message.
     * @param message The message to display
     */
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
    /**
     * Dismisses the loading dialog if it's showing.
     */
    private void dismissLoadingDialog() {
        runOnUiThread(() -> {
            if (loadingDialog != null && loadingDialog.isShowing()) {
                loadingDialog.dismiss();
            }
        });
    }

    /**
     * Handles back button press with confirmation dialog.
     */
    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("Leave Room")
                .setMessage("Are you sure you want to leave this room?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    isTransitioningToGameplay = false; // Ensure this flag is false when explicitly leaving
                    removePlayerFromRoom();
                    finish();
                })
                .setNegativeButton("No", null)
                .show();
    }
    /**
     * Activity lifecycle method for resuming, reconnects socket if needed.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (mSocket != null && !mSocket.connected()) {
            mSocket.connect();
        }
    }
    /**
     * Activity lifecycle method for pausing.
     */
    @Override
    protected void onPause() {
        super.onPause();
    }
    /**
     * Activity lifecycle method for cleanup, manages socket disconnection.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSocket != null) {
            try {
                // Remove all listeners
                mSocket.off("new_player", onNewPlayer);
                mSocket.off("group_change", onGroupChange);
                mSocket.off("player_ready", onPlayerReady);
                mSocket.off("player_unready", onPlayerUnready);
                mSocket.off("game_started", onGameStarted);
                mSocket.off("validate_connection", onValidateConnection);
                mSocket.off("game_start_failed", onGameStartFailed);

                // Only disconnect the socket if we're not transitioning to gameplay
                if (!isTransitioningToGameplay) {
                    // Simple disconnect
                    mSocket.disconnect();
                    GlobalSocketManager.setSocket(null);

                    // Remove player from room
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