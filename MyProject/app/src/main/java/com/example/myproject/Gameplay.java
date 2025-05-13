/**
 * Gameplay - Core game battle screen
 *
 * Manages turn-based combat between two teams. Handles:
 * - Real-time turn management via Socket.IO
 * - Character abilities and health tracking
 * - Encrypted communication with server
 * - Combat animations and visual feedback
 */
package com.example.myproject;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import okhttp3.*;

public class Gameplay extends AppCompatActivity implements View.OnClickListener {

    private CustomButton ability1, ability2, ability3, ability4, ability5, ability6;
    private TextView currentPlayer, nextPlayer, timer;
    private RecyclerView chatRecyclerView;

    private static final String TAG = "Gameplay";
    private static final String SERVER_URL = "http://10.0.2.2:8080";


    private String username, character_name, group, room_code;
    static List<CharactersList> group1 = new ArrayList<>();
    static List<CharactersList> group2 = new ArrayList<>();
    private CharactersList user_character;
    static Map<String, String> characterToUsername = new HashMap<>();
    static Map<String, Integer> characterHealth = new HashMap<>();

    private List<String> chatMessages = new ArrayList<>();
    private ChatAdapter chatAdapter;
    private Handler timerHandler = new Handler();
    private Runnable timerRunnable;
    private long turnStartTime;
    private long turnDuration;
    private boolean isTurn = false;

    private String currentTurnPlayer;
    private String nextTurnPlayer;

    private RSAEncryption rsaEncryption;
    private HybridEncryption hybridEncryption;
    private OkHttpClient client;
    private RSAPrivateKey privateKey;
    private Socket mSocket;
    private AlertDialog loadingDialog;

    private boolean gameEnded = false;
    private boolean gameStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.game_screen);

        client = new OkHttpClient();

        SharedPreferences prefs = getSharedPreferences("Current_Connection", MODE_PRIVATE);
        username = prefs.getString("username", "");
        character_name = getIntent().getStringExtra("character_name");
        group = getIntent().getStringExtra("group");
        room_code = getIntent().getStringExtra("room_code");

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

        initializeUIComponents();
        startBackgroundMusic();

        loadCharacter();
        getGroups();

        if (GlobalSocketManager.getSocket() != null) {
            mSocket = GlobalSocketManager.getSocket();
            setupSocketListeners();

            joinRoom();
        } else {
            setupWebSocket();
        }
    }

    private void initializeUIComponents() {
        ability1 = findViewById(R.id.ability1);
        ability2 = findViewById(R.id.ability2);
        ability3 = findViewById(R.id.ability3);
        ability4 = findViewById(R.id.ability4);
        ability5 = findViewById(R.id.ability5);
        ability6 = findViewById(R.id.ability6);
        currentPlayer = findViewById(R.id.current_player);
        nextPlayer = findViewById(R.id.next_player);
        timer = findViewById(R.id.timer);

        ability1.setOnClickListener(this);
        ability2.setOnClickListener(this);
        ability3.setOnClickListener(this);
        ability4.setOnClickListener(this);
        ability5.setOnClickListener(this);
        ability6.setOnClickListener(this);

        chatRecyclerView = findViewById(R.id.chat_text);
        chatAdapter = new ChatAdapter(chatMessages);
        chatRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        chatRecyclerView.setAdapter(chatAdapter);
        chatRecyclerView.setHasFixedSize(true);
        chatRecyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));

        findViewById(R.id.view_health_btn).setOnClickListener(v -> showHealthStatusDialog());

        chatRecyclerView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom < oldBottom) {
                chatRecyclerView.postDelayed(() ->
                        chatRecyclerView.smoothScrollToPosition(Math.max(chatAdapter.getItemCount() - 1, 0)), 100);
            }
        });
    }

    private void setupSocketListeners() {
        mSocket.on("turn_started", onTurnStarted);
        mSocket.on("turn_expired", onTurnExpired);
        mSocket.on("move_made", onMoveMade);
        mSocket.on("game_ended", onGameEnded);
        mSocket.on("game_start_failed", onGameStartFailed);
        mSocket.on("skip_made", onSkipMade);
    }

    private void setupWebSocket() {
        try {
            IO.Options opts = new IO.Options();
            opts.forceNew = true;
            opts.reconnection = true;
            opts.reconnectionAttempts = 10;
            opts.reconnectionDelay = 1000;

            mSocket = IO.socket(SERVER_URL, opts);

            GlobalSocketManager.setSocket(mSocket);

            mSocket.on(Socket.EVENT_CONNECT, args -> {
                runOnUiThread(() -> {
                    Log.d(TAG, "WebSocket Connected");
                    joinRoom();
                });
            });

            setupSocketListeners();

            mSocket.connect();

        } catch (URISyntaxException e) {
            Log.e(TAG, "WebSocket connection error", e);
            Toast.makeText(this, "Failed to connect to server", Toast.LENGTH_SHORT).show();
        }
    }

    private void joinRoom() {
        try {
            JSONObject roomData = new JSONObject();
            roomData.put("username", username);
            roomData.put("room_code", room_code);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(roomData);

            mSocket.emit("join_room", encryptedPayload);

            getGameState();
        } catch (Exception e) {
            Log.e(TAG, "Error joining room", e);
        }
    }

    /** Gets current game state from server */
    private void getGameState() {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("username", username);
            jsonObject.put("room_code", room_code);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            mSocket.emit("get_game_state", new JSONObject[]{encryptedPayload}, args -> {
                if (args.length > 0 && args[0] != null) {
                    try {
                        JSONObject encryptedResponse = (JSONObject) args[0];

                        String method = encryptedResponse.optString("method", "");
                        Object decryptedData;

                        if (method.equals("hybrid-rsa-aes")) {
                            decryptedData = hybridEncryption.decryptWithPrivateKey(encryptedResponse, privateKey);
                        } else {
                            decryptedData = hybridEncryption.decryptSymmetric(encryptedResponse);
                        }

                        JSONObject gameStateData;
                        if (decryptedData instanceof JSONObject) {
                            gameStateData = (JSONObject) decryptedData;
                        } else {
                            gameStateData = new JSONObject(decryptedData.toString());
                        }

                        JSONObject gameState = gameStateData.getJSONObject("game_state");
                        String status = gameState.getString("status");

                        if (status.equals("started")) {
                            gameStarted = true;

                            int turn = gameState.getInt("turn");
                            String currentPlayerName = gameState.getString("current_player");
                            String nextPlayerName = gameState.getString("next_player");

                            runOnUiThread(() -> {
                                currentTurnPlayer = currentPlayerName;
                                nextTurnPlayer = nextPlayerName;
                                currentPlayer.setText(currentPlayerName);
                                nextPlayer.setText(nextPlayerName);

                                isTurn = username.equals(currentPlayerName);
                                updateAbilityButtonsState(isTurn);

                                if (isTurn) {
                                    Toast.makeText(Gameplay.this, "It's your turn!", Toast.LENGTH_SHORT).show();
                                }

                                turnStartTime = System.currentTimeMillis();
                                turnDuration = 60000;
                                startCountdown();
                            });

                            // In on_get_game_state response handler, after health data is processed:
                            if (gameState.has("character_health")) {
                                JSONObject healthData = gameState.getJSONObject("character_health");

                                // Clear existing health data
                                characterHealth.clear();

                                // Store health data by username
                                for (Iterator<String> it = healthData.keys(); it.hasNext(); ) {
                                    String playerUsername = it.next();
                                    int hp = healthData.getInt(playerUsername);
                                    characterHealth.put(playerUsername, hp);
                                    Log.d(TAG, "Loaded health for player " + playerUsername + ": " + hp);
                                }

                                // Add this call to update character objects
                                updateGroupCharactersHealth();
                            }

                            if (gameState.has("chat_log")) {
                                JSONArray chatLog = gameState.getJSONArray("chat_log");
                                List<String> newMessages = new ArrayList<>();

                                for (int i = 0; i < chatLog.length(); i++) {
                                    JSONObject chatEntry = chatLog.getJSONObject(i);
                                    String message = chatEntry.getString("message");
                                    if (chatEntry.has("effect")) {
                                        message += " " + chatEntry.getString("effect");
                                    }
                                    newMessages.add(message);
                                }

                                runOnUiThread(() -> {
                                    chatMessages.clear();
                                    chatMessages.addAll(newMessages);
                                    chatAdapter.notifyDataSetChanged();
                                    chatRecyclerView.scrollToPosition(chatMessages.size() - 1);
                                });
                            }
                        } else if (status.equals("ended")) {
                            gameEnded = true;
                            gameStarted = true;
                            String winner = gameState.optString("winner", "unknown");

                            runOnUiThread(() -> {
                                showGameEndDialog(winner);
                                disableAllAbilityButtons();
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing game state", e);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error requesting game state", e);
        }
    }

    private void startBackgroundMusic() {
        Intent playIntent = new Intent(this, PlayService.class);
        startService(playIntent);
    }

    private void stopBackgroundMusic() {
        Intent playIntent = new Intent(this, PlayService.class);
        stopService(playIntent);
    }

    /** Handles turn start events from server */
    private Emitter.Listener onTurnStarted = args -> {
        try {
            Log.d(TAG, "TURN_STARTED event received");
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

            String currentPlayer = data.getString("current_player");
            String nextPlayer = data.getString("next_player");
            long startTime = data.has("start_time") ? data.getLong("start_time") * 1000 : System.currentTimeMillis();
            long duration = data.has("duration") ? data.getLong("duration") * 1000 : 60000;

            turnStartTime = startTime;
            turnDuration = duration;

            runOnUiThread(() -> {
                if (timerRunnable != null) {
                    timerHandler.removeCallbacks(timerRunnable);
                    timerRunnable = null;
                }

                currentTurnPlayer = currentPlayer;
                nextTurnPlayer = nextPlayer;
                this.currentPlayer.setText(currentTurnPlayer);
                this.nextPlayer.setText(nextTurnPlayer);

                isTurn = username.equals(currentTurnPlayer);
                updateAbilityButtonsState(isTurn);

                startCountdown();

                if (isTurn) {
                    Toast.makeText(Gameplay.this, "It's your turn!", Toast.LENGTH_SHORT).show();
                }

                Log.d(TAG, "Turn started for player: " + currentPlayer);
                gameStarted = true;
            });

        } catch (Exception e) {
            Log.e(TAG, "Error processing turn started event: " + e.getMessage(), e);
        }
    };

    /** Handles turn expiration events */
    private Emitter.Listener onTurnExpired = args -> {
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

            String currentPlayer = data.getString("current_player");
            String nextPlayer = data.getString("next_player");

            if (timerRunnable != null) {
                timerHandler.removeCallbacks(timerRunnable);
                timerRunnable = null;
            }

            runOnUiThread(() -> {
                String expiredMessage = "Turn expired for " + this.currentTurnPlayer;
                chatMessages.add(expiredMessage);
                chatAdapter.notifyDataSetChanged();
                chatRecyclerView.scrollToPosition(chatMessages.size() - 1);

                if (isTurn) {
                    Toast.makeText(Gameplay.this, "Your turn expired!", Toast.LENGTH_SHORT).show();
                    isTurn = false;
                }

                this.currentTurnPlayer = currentPlayer;
                this.nextTurnPlayer = nextPlayer;
                this.currentPlayer.setText(currentPlayer);
                this.nextPlayer.setText(nextPlayer);

                isTurn = username.equals(currentPlayer);
                updateAbilityButtonsState(isTurn);

                gameStarted = true;
            });
        } catch (Exception e) {
            Log.e(TAG, "Error processing turn expired event: " + e.getMessage(), e);
        }
    };

    /** Processes moves made by players */
    private Emitter.Listener onMoveMade = args -> {
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

            String player = data.getString("username");
            String ability = data.getString("ability");
            String target = data.getString("target");
            String effect = data.getString("effect");
            String chatMsg = data.getString("chat");

            runOnUiThread(() -> {
                // In the onMoveMade method
                if (data.has("health")) {
                    try {
                        JSONObject healthUpdates = data.getJSONObject("health");

                        // Handle health updates by username
                        for (Iterator<String> it = healthUpdates.keys(); it.hasNext(); ) {
                            String key = it.next();

                            // Skip the character_name entry which is just for reference
                            if (key.equals("character_name")) continue;

                            int hp = healthUpdates.getInt(key);

                            // Update the health in our local map
                            characterHealth.put(key, hp);
                            Log.d(TAG, "Updated health for player " + key + ": " + hp);
                        }

                        // Update character objects with new health values
                        updateGroupCharactersHealth();
                    } catch (JSONException e) {
                        Log.e(TAG, "Error updating health: " + e.getMessage());
                    }
                }

                String moveMessage = chatMsg + " \n" + effect;
                chatMessages.add(moveMessage);
                chatAdapter.notifyDataSetChanged();
                chatRecyclerView.scrollToPosition(chatMessages.size() - 1);

                gameStarted = true;
            });

            if (data.has("current_player") && data.has("next_player")) {
                String currentPlayer = data.getString("current_player");
                String nextPlayer = data.getString("next_player");
                startNextTurn(currentPlayer, nextPlayer);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing move made event: " + e.getMessage(), e);
        }
    };

    /** Handles skip turn notifications */
    private Emitter.Listener onSkipMade = args -> {
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

            String player = data.getString("username");

            runOnUiThread(() -> {
                String moveMessage = "Player " + player + " skipped their turn";
                chatMessages.add(moveMessage);
                chatAdapter.notifyDataSetChanged();
                chatRecyclerView.scrollToPosition(chatMessages.size() - 1);
                gameStarted = true;
            });

            if (data.has("current_player") && data.has("next_player")) {
                String currentPlayer = data.getString("current_player");
                String nextPlayer = data.getString("next_player");
                startNextTurn(currentPlayer, nextPlayer);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing move made event: " + e.getMessage(), e);
        }
    };

    /** Handles game end notifications */
    private Emitter.Listener onGameEnded = args -> {
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

                String winner = data.getString("winner");

                gameEnded = true;
                gameStarted = true;

                if (timerRunnable != null) {
                    timerHandler.removeCallbacks(timerRunnable);
                }

                showGameEndDialog(winner);
                disableAllAbilityButtons();
                stopBackgroundMusic();

            } catch (Exception e) {
                Log.e(TAG, "Error processing game ended event: " + e.getMessage(), e);
            }
        });
    };

    /** Handles failed game start scenarios */
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
                Toast.makeText(Gameplay.this,
                        "Game failed to start: " + reason,
                        Toast.LENGTH_LONG).show();

                finish();
            });
        } catch (Exception e) {
            Log.e(TAG, "Error processing game start failed event: " + e.getMessage(), e);
        }
    };

    /** Starts turn countdown timer */
    private void startCountdown() {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - turnStartTime;
        long remainingTime = Math.max(0, turnDuration - elapsedTime);

        updateTimerDisplay((int) (remainingTime / 1000));

        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                long elapsedTime = currentTime - turnStartTime;
                long remainingTime = turnDuration - elapsedTime;

                if (remainingTime <= 0) {
                    updateTimerDisplay(0);

                    if (isTurn) {
                        Toast.makeText(Gameplay.this, "Time's up!", Toast.LENGTH_SHORT).show();
                        sendSkipToServer();
                        isTurn = false;
                        updateAbilityButtonsState(false);
                    }

                } else {
                    updateTimerDisplay((int) (remainingTime / 1000));
                    timerHandler.postDelayed(this, 1000);
                }
            }
        };

        timerHandler.postDelayed(timerRunnable, 1000);
    }

    /** Updates UI for new turn */
    private void startNextTurn(String currentPlayer, String nextPlayer) {
        currentTurnPlayer = currentPlayer;
        nextTurnPlayer = nextPlayer;

        runOnUiThread(() -> {
            this.currentPlayer.setText(currentTurnPlayer);
            this.nextPlayer.setText(nextTurnPlayer);

            isTurn = username.equals(currentTurnPlayer);
            updateAbilityButtonsState(isTurn);

            if (isTurn) {
                Toast.makeText(Gameplay.this, "It's your turn! You have 60 seconds.", Toast.LENGTH_SHORT).show();
            }

            turnStartTime = System.currentTimeMillis();
            turnDuration = 60000;
            startCountdown();

            gameStarted = true;
        });
    }

    /** Updates timer display */
    private void updateTimerDisplay(int secondsRemaining) {
        if (secondsRemaining > 60) {
            secondsRemaining = 60;
        }
        timer.setText(String.valueOf(secondsRemaining));

        if (secondsRemaining <= 10) {
            timer.setTextColor(Color.RED);
        } else  {
            timer.setTextColor(Color.WHITE);
        }
    }

    /** Enables/disables ability buttons */
    private void updateAbilityButtonsState(boolean enabled) {
        ability1.setEnabled(enabled);
        ability2.setEnabled(enabled);
        ability3.setEnabled(enabled);
        ability4.setEnabled(enabled);
        ability5.setEnabled(enabled);
        ability6.setEnabled(enabled);

        float alpha = enabled ? 1.0f : 0.5f;
        ability1.setAlpha(alpha);
        ability2.setAlpha(alpha);
        ability3.setAlpha(alpha);
        ability4.setAlpha(alpha);
        ability5.setAlpha(alpha);
        ability6.setAlpha(alpha);
    }

    private void disableAllAbilityButtons() {
        updateAbilityButtonsState(false);
    }

    /** Maps character abilities to UI buttons */
    private void setAbilitiesToButtons() {
        if (user_character != null && user_character.getAbilities() != null) {
            List<String> abilities = user_character.getAbilities();
            setAbilityButtonText(ability1, abilities, 0);
            setAbilityButtonText(ability2, abilities, 1);
            setAbilityButtonText(ability3, abilities, 2);
            setAbilityButtonText(ability4, abilities, 3);
            setAbilityButtonText(ability5, abilities, 4);
            setAbilityButtonText(ability6, abilities, 5);
        } else {
            Log.e(TAG, "Character or abilities is null");
        }
    }

    private void setAbilityButtonText(CustomButton button, List<String> abilities, int index) {
        if (index < abilities.size()) {
            button.setButtonText(abilities.get(index));
        } else {
            button.setButtonText("--");
        }
    }

    /** Loads user's character from server */
    private void loadCharacter() {
        showLoadingDialog("Loading character...");
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("username", username);
            jsonObject.put("name", character_name);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            RequestBody body = RequestBody.create(
                    encryptedPayload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(SERVER_URL + "/get_character")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        dismissLoadingDialog();
                        Toast.makeText(Gameplay.this, "Failed to load character: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Failed to load user character: " + e.getMessage());
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> {
                            dismissLoadingDialog();
                            Toast.makeText(Gameplay.this, "Error fetching character: " + response.code(),
                                    Toast.LENGTH_SHORT).show();
                            Log.e(TAG, "Error fetching user character: " + response.code());
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

                            JSONObject characterObject = decryptedJson.getJSONObject("character");
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
                            character.setHealth(50);
                            user_character = character;

                            characterHealth.put(username, 50);
                            characterToUsername.put(character.getCharacterName(), username);

                            runOnUiThread(() -> {
                                dismissLoadingDialog();
                                setAbilitiesToButtons();
                                Log.d(TAG, "Loaded user character: " + character.getCharacterName());
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                dismissLoadingDialog();
                                Toast.makeText(Gameplay.this, "Error parsing character data: " + e.getMessage(),
                                        Toast.LENGTH_SHORT).show();
                                Log.e(TAG, "Error parsing user character: " + e.getMessage(), e);
                            });
                        }
                    }
                }
            });
        } catch (Exception e) {
            dismissLoadingDialog();
            e.printStackTrace();
            Toast.makeText(this, "Error encrypting data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error encrypting data for character request: " + e.getMessage());
        }
    }

    /** Refreshes both group data */
    private void getGroups() {
        Log.d(TAG, "Refreshing groups data");
        getGroup1();
        getGroup2();
    }

    /** Loads group 1 data from server */
    private void getGroup1() {
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
                                String playerUsername = characterObject.getString("username");
                                String charName = characterObject.getString("name");
                                CharactersList character = new CharactersList(
                                        charName,
                                        characterObject.getString("desc"),
                                        playerUsername);

                                // Update character to username mapping
                                characterToUsername.put(charName, playerUsername);

                                // Only initialize health to 50 if this is a new player we haven't seen yet
                                if (!characterHealth.containsKey(playerUsername)) {
                                    characterHealth.put(playerUsername, 50);
                                    Log.d(TAG, "Initialized new player health: " + playerUsername + " = 50");
                                    character.setHealth(50);
                                } else {
                                    int currentHealth = characterHealth.get(playerUsername);
                                    character.setHealth(currentHealth);
                                    Log.d(TAG, "Set character health for group1: " + charName +
                                            " (player: " + playerUsername + ") health = " + currentHealth);
                                }

                                newGroup1.add(character);
                            }

                            runOnUiThread(() -> {
                                group1.clear();
                                group1.addAll(newGroup1);
                                Log.d(TAG, "Updated group1 with " + group1.size() + " characters");
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing group1 data: " + e.getMessage(), e);
                        }
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating request for group1: " + e.getMessage(), e);
        }
    }

    /** Loads group 2 data from server */
    private void getGroup2() {
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
                                String playerUsername = characterObject.getString("username");
                                String charName = characterObject.getString("name");
                                CharactersList character = new CharactersList(
                                        charName,
                                        characterObject.getString("desc"),
                                        playerUsername);

                                // Update character to username mapping
                                characterToUsername.put(charName, playerUsername);

                                // Only initialize health to 50 if this is a new player we haven't seen yet
                                if (!characterHealth.containsKey(playerUsername)) {
                                    characterHealth.put(playerUsername, 50);
                                    Log.d(TAG, "Initialized new player health: " + playerUsername + " = 50");
                                    character.setHealth(50);
                                } else {
                                    int currentHealth = characterHealth.get(playerUsername);
                                    character.setHealth(currentHealth);
                                    Log.d(TAG, "Set character health for group2: " + charName +
                                            " (player: " + playerUsername + ") health = " + currentHealth);
                                }

                                newGroup2.add(character);
                            }

                            runOnUiThread(() -> {
                                group2.clear();
                                group2.addAll(newGroup2);
                                Log.d(TAG, "Updated group2 with " + group2.size() + " characters");
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing group2 data: " + e.getMessage(), e);
                        }
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating request for group2: " + e.getMessage(), e);
        }
    }

    /**
     * Updates the health values of characters in both groups based on the characterHealth map
     */
    private void updateGroupCharactersHealth() {
        // Update health for characters in group1
        for (CharactersList character : group1) {
            String username = character.getUsername();
            if (characterHealth.containsKey(username)) {
                character.setHealth(characterHealth.get(username));
                Log.d(TAG, "Updated group1 character " + character.getCharacterName() +
                        " health to: " + character.getHealth());
            }
        }

        // Update health for characters in group2
        for (CharactersList character : group2) {
            String username = character.getUsername();
            if (characterHealth.containsKey(username)) {
                character.setHealth(characterHealth.get(username));
                Log.d(TAG, "Updated group2 character " + character.getCharacterName() +
                        " health to: " + character.getHealth());
            }
        }
    }

    @Override
    public void onClick(View v) {
        if (gameEnded) {
            Toast.makeText(this, "Game has ended", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isTurn) {
            Toast.makeText(this, "It's not your turn!", Toast.LENGTH_SHORT).show();
            return;
        }

        String abilityName = null;
        if (v.getId() == R.id.ability1) {
            abilityName = ability1.getButtonText().toString();
        } else if (v.getId() == R.id.ability2) {
            abilityName = ability2.getButtonText().toString();
        } else if (v.getId() == R.id.ability3) {
            abilityName = ability3.getButtonText().toString();
        } else if (v.getId() == R.id.ability4) {
            abilityName = ability4.getButtonText().toString();
        } else if (v.getId() == R.id.ability5) {
            abilityName = ability5.getButtonText().toString();
        } else if (v.getId() == R.id.ability6) {
            abilityName = ability6.getButtonText().toString();
        }

        if (abilityName == null || abilityName.equals("--")) {
            return;
        }

        getAbilityDetails(abilityName);
    }

    /** Gets ability details from server */
    private void getAbilityDetails(String abilityName) {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("username", username);
            jsonObject.put("ability", abilityName);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            mSocket.emit("get_ability", new JSONObject[]{encryptedPayload}, args -> {
                if (args.length > 0 && args[0] != null) {
                    try {
                        JSONObject encryptedResponse = (JSONObject) args[0];

                        String method = encryptedResponse.optString("method", "");
                        Object decryptedData;

                        if (method.equals("hybrid-rsa-aes")) {
                            decryptedData = hybridEncryption.decryptWithPrivateKey(encryptedResponse, privateKey);
                        } else {
                            decryptedData = hybridEncryption.decryptSymmetric(encryptedResponse);
                        }

                        JSONObject abilityData;
                        if (decryptedData instanceof JSONObject) {
                            abilityData = (JSONObject) decryptedData;
                        } else {
                            abilityData = new JSONObject(decryptedData.toString());
                        }

                        String abilityType = abilityData.optString("type", "");
                        String abilityDesc = abilityData.optString("desc", "");
                        String numDiceStr = abilityData.optString("num", "1");
                        String diceType = abilityData.optString("dice", "d6");

                        Log.d(TAG, "Received ability details for " + abilityName + ": type=" + abilityType +
                                ", num=" + numDiceStr + ", dice=" + diceType);

                        int numDice = 1;
                        try {
                            numDice = Integer.parseInt(numDiceStr);
                        } catch (NumberFormatException e) {
                            Log.e(TAG, "Error parsing numDice: " + numDiceStr, e);
                        }

                        if (diceType == null || diceType.isEmpty()) {
                            diceType = "d6";
                            Log.w(TAG, "Empty dice type received, using default d6");
                        }

                        final String finalDiceType = diceType;
                        final int finalNumDice = numDice;
                        final String finalAbilityType = abilityType;
                        final String finalAbilityDesc = abilityDesc;

                        runOnUiThread(() -> {
                            showAbilityDescriptionDialog(abilityName, finalAbilityDesc, finalAbilityType,
                                    finalNumDice, finalDiceType);
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing ability details", e);
                        runOnUiThread(() -> {
                            Toast.makeText(Gameplay.this, "Error getting ability details: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        });
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error getting ability details", e);
            Toast.makeText(this, "Error requesting ability details: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Rolls dice and confirms move */
    private void rollDiceAndMakeMove(String abilityName, String target_user, String target_name, String type, int numDice, String diceType) {
        try {
            if (diceType == null || diceType.isEmpty()) {
                Log.e(TAG, "Invalid dice type: " + diceType);
                Toast.makeText(this, "Error: Invalid dice type", Toast.LENGTH_SHORT).show();
                return;
            }

            if (diceType.length() < 1) {
                Log.e(TAG, "Dice type too short: " + diceType);
                Toast.makeText(this, "Error: Invalid dice format", Toast.LENGTH_SHORT).show();
                return;
            }

            int diceSides;
            try {
                diceSides = Integer.parseInt(diceType);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Failed to parse dice sides from: " + diceType, e);
                Toast.makeText(this, "Error: Invalid dice format", Toast.LENGTH_SHORT).show();
                return;
            }

            if (diceSides <= 0) {
                Log.e(TAG, "Invalid number of dice sides: " + diceSides);
                Toast.makeText(this, "Error: Invalid dice sides", Toast.LENGTH_SHORT).show();
                return;
            }

            int[] diceRolls = new int[numDice];
            int totalValue = 0;

            for (int i = 0; i < numDice; i++) {
                diceRolls[i] = (int) (Math.random() * diceSides) + 1;
                totalValue += diceRolls[i];
            }

            StringBuilder rollMessage = new StringBuilder();
            rollMessage.append("You rolled: ");
            for (int i = 0; i < diceRolls.length; i++) {
                rollMessage.append(diceRolls[i]);
                if (i < diceRolls.length - 1) {
                    rollMessage.append(", ");
                }
            }
            rollMessage.append("\nTotal: ").append(totalValue);

            final int finalValue = totalValue;

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Dice Roll Results")
                    .setMessage(rollMessage.toString())
                    .setPositiveButton("Confirm", (dialog, which) -> {
                        sendMoveToServer(abilityName, target_user, target_name, type, finalValue);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error in rollDiceAndMakeMove: " + e.getMessage(), e);
            Toast.makeText(this, "An error occurred: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Sends move to server */
    private void sendMoveToServer(String ability, String target_user, String target_name, String type, int value) {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("username", username);
            jsonObject.put("ability", ability);
            jsonObject.put("target_user", target_user);
            jsonObject.put("target_name", target_name);
            jsonObject.put("type", type);
            jsonObject.put("value", value);
            jsonObject.put("room_code", room_code);
            jsonObject.put("character", character_name);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            mSocket.emit("make_move", encryptedPayload);

            isTurn = false;
            updateAbilityButtonsState(false);

            gameStarted = true;

        } catch (Exception e) {
            Log.e(TAG, "Error sending move to server", e);
            Toast.makeText(this, "Error sending move: " + e.getMessage(), Toast.LENGTH_SHORT).show();

            isTurn = true;
            updateAbilityButtonsState(true);
        }
    }

    /** Sends skip turn request */
    private void sendSkipToServer() {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("username", username);
            jsonObject.put("room_code", room_code);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            mSocket.emit("skip_turn", encryptedPayload);

            isTurn = false;
            updateAbilityButtonsState(false);

            gameStarted = true;

        } catch (Exception e) {
            Log.e(TAG, "Error sending move to server", e);
            Toast.makeText(this, "Error sending move: " + e.getMessage(), Toast.LENGTH_SHORT).show();

            isTurn = true;
            updateAbilityButtonsState(true);
        }
    }

    /** Shows ability description dialog */
    private void showAbilityDescriptionDialog(String abilityName, String description, String type, int numDice, String diceType) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(abilityName)
                .setMessage(description)
                .setPositiveButton("Use Ability", (dialog, which) -> {
                    showTargetSelectionDialog(abilityName, type, numDice, diceType);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Shows target selection dialog */
    private void showTargetSelectionDialog(String abilityName, String type, int numDice, String diceType) {

        Log.d(TAG, "Showing target selection dialog for ability: " + abilityName);

        List<CharactersList> targetGroup;
        String dialogTitle;

        if (type.equals("a")) {
            targetGroup = group.equals("group1") ? group2 : group1;
            dialogTitle = "Select target to attack";
        } else {
            targetGroup = group.equals("group1") ? group1 : group2;
            dialogTitle = "Select ally to heal";
        }

        TargetAdapter adapter = new TargetAdapter(this, targetGroup);

        if (adapter.getCount() == 0) {
            Toast.makeText(this, "No valid targets available", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.target_selection_dialog, null);
        ListView targetListView = dialogView.findViewById(R.id.target_list);
        targetListView.setAdapter(adapter);

        builder.setTitle(dialogTitle)
                .setView(dialogView)
                .setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.show();

        targetListView.setOnItemClickListener((parent, view, position, id) -> {
            CharactersList target = adapter.getItem(position);
            dialog.dismiss();
            rollDiceAndMakeMove(abilityName, target.getUsername(), target.getCharacterName(), type, numDice, diceType);
        });
    }

    /** Shows health status for all characters */
    private void showHealthStatusDialog() {
        updateGroupCharactersHealth();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.health_status_dialog, null);

        ListView group1ListView = dialogView.findViewById(R.id.group1_health_list);
        ListView group2ListView = dialogView.findViewById(R.id.group2_health_list);

        HealthAdapter group1Adapter = new HealthAdapter(this, group1);
        HealthAdapter group2Adapter = new HealthAdapter(this, group2);

        group1ListView.setAdapter(group1Adapter);
        group2ListView.setAdapter(group2Adapter);

        builder.setTitle("Character Health Status")
                .setView(dialogView)
                .setPositiveButton("Close", null);

        builder.create().show();
    }

    /** Shows game end dialog with winner */
    private void showGameEndDialog(String winner) {
        String message;
        if (winner.equals("group1")) {
            message = "Group 1 has won the battle!";
        } else if (winner.equals("group2")) {
            message = "Group 2 has won the battle!";
        } else {
            message = "The battle ended in a tie!";
        }

        boolean playerWon = (group.equals("group1") && winner.equals("group1")) ||
                (group.equals("group2") && winner.equals("group2"));

        if (playerWon) {
            message += "\n\nYour team is victorious!";
        } else if (!winner.equals("tie")) {
            message += "\n\nYour team has been defeated.";
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Game Over")
                .setMessage(message)
                .setPositiveButton("Return to Lobby", (dialog, which) -> {
                    finish();
                })
                .setCancelable(false)
                .show();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (!gameStarted) {
            try {
                JSONObject jsonObject = new JSONObject();
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
                        if (response.isSuccessful()) {
                            Log.d(TAG, "Successfully removed player from room");
                        } else {
                            Log.e(TAG, "Error removing player from room: " + response.code());
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error creating request to remove player from room: " + e.getMessage());
            }
        }

        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        if (mSocket != null) {
            mSocket.off("turn_started", onTurnStarted);
            mSocket.off("turn_expired", onTurnExpired);
            mSocket.off("move_made", onMoveMade);
            mSocket.off("game_ended", onGameEnded);
            mSocket.off("game_start_failed", onGameStartFailed);
        }

        if (gameEnded) {
            if (mSocket != null) {
                mSocket.disconnect();
                GlobalSocketManager.setSocket(null);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (gameEnded) {
            super.onBackPressed();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Leave Game")
                .setMessage("Are you sure you want to leave the game? Your character will be removed from the battle.")
                .setPositiveButton("Yes", (dialog, which) -> {
                    try {
                        JSONObject jsonObject = new JSONObject();
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
                                if (response.isSuccessful()) {
                                    Log.d(TAG, "Successfully removed player from room");
                                } else {
                                    Log.e(TAG, "Error removing player from room: " + response.code());
                                }
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Error creating request to remove player from room: " + e.getMessage());
                    }

                    super.onBackPressed();
                })
                .setNegativeButton("No", (dialog, which) -> {
                    dialog.dismiss();
                })
                .show();
    }
}