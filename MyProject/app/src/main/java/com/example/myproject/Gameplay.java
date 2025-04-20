/**
 * Gameplay Activity
 *
 * This activity manages the core gameplay mechanics of a turn-based multiplayer battle game.
 * It handles real-time turn management, character abilities, health tracking, and combat
 * interactions between two player groups. The activity uses Socket.IO for real-time
 * communication and hybrid encryption (RSA + AES) for secure data transmission.
 *
 * All network communication is encrypted using HybridEncryption before transmission.
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

    // UI Components
    private CustomButton ability1, ability2, ability3, ability4, ability5, ability6;
    private TextView currentPlayer, nextPlayer, timer;
    private RecyclerView chatRecyclerView;

    // Constants
    private static final String TAG = "Gameplay";
    private static final String SERVER_URL = "http://10.0.2.2:8080";


    // User and Game Data
    private String username, character_name, group, room_code;
    static List<CharactersList> group1 = new ArrayList<>();
    static List<CharactersList> group2 = new ArrayList<>();
    private CharactersList user_character;
    static Map<String, Integer> characterHealth = new HashMap<>();

    // Chat and Timer
    private List<String> chatMessages = new ArrayList<>();
    private ChatAdapter chatAdapter;
    private Handler timerHandler = new Handler();
    private Runnable timerRunnable;
    private long turnStartTime;
    private long turnDuration;
    private boolean isTurn = false;

    // Current Turn Info
    private String currentTurnPlayer;
    private String nextTurnPlayer;

    // Network
    private RSAEncryption rsaEncryption;
    private HybridEncryption hybridEncryption;
    private OkHttpClient client;
    private RSAPrivateKey privateKey;
    private Socket mSocket;
    private AlertDialog loadingDialog;

    // Game Status
    private boolean gameEnded = false;
    private boolean gameStarted = false;

    /**
     * Called when activity is created. Initializes network client, retrieves intent data,
     * sets up encryption, loads UI components, and establishes socket connection.
     * @param savedInstanceState Saved state bundle
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.game_screen);

        // Initialize network client
        client = new OkHttpClient();

        // Get user and game info
        SharedPreferences prefs = getSharedPreferences("Current_Connection", MODE_PRIVATE);
        username = prefs.getString("username", "");
        character_name = getIntent().getStringExtra("character_name");
        group = getIntent().getStringExtra("group");
        room_code = getIntent().getStringExtra("room_code");

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

        // Initialize UI components
        initializeUIComponents();
        startBackgroundMusic();

        // Load game data
        loadCharacter();
        getGroups();

        // Setup WebSocket - get the existing socket if available
        if (GlobalSocketManager.getSocket() != null) {
            mSocket = GlobalSocketManager.getSocket();
            setupSocketListeners();

            // Since we already have a socket, just join the room
            joinRoom();
        } else {
            // If no socket exists, create a new one
            setupWebSocket();
        }
    }
    /**
     * Initializes all UI components including ability buttons, player displays,
     * timer, and chat RecyclerView. Sets up click listeners and adapters.
     */
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

        // Add floating health display button
        findViewById(R.id.view_health_btn).setOnClickListener(v -> showHealthStatusDialog());

        chatRecyclerView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom < oldBottom) {
                chatRecyclerView.postDelayed(() ->
                        chatRecyclerView.smoothScrollToPosition(Math.max(chatAdapter.getItemCount() - 1, 0)), 100);
            }
        });
    }
    /**
     * Configures Socket.IO event listeners for all game events including turn changes,
     * moves, game end, and other real-time updates.
     */
    private void setupSocketListeners() {
        // Game events
        mSocket.on("turn_started", onTurnStarted);
        mSocket.on("turn_expired", onTurnExpired);
        mSocket.on("move_made", onMoveMade);
        mSocket.on("game_ended", onGameEnded);
        mSocket.on("game_start_failed", onGameStartFailed);
        mSocket.on("skip_made", onSkipMade);
    }
    /**
     * Creates and configures the Socket.IO connection to the server. Sets up connection
     * parameters, reconnection attempts, and stores the socket in GlobalSocketManager.
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

            // Store in global manager
            GlobalSocketManager.setSocket(mSocket);

            // Connection event listeners
            mSocket.on(Socket.EVENT_CONNECT, args -> {
                runOnUiThread(() -> {
                    Log.d(TAG, "WebSocket Connected");
                    joinRoom();
                });
            });

            // Setup other listeners
            setupSocketListeners();

            // Connect to WebSocket
            mSocket.connect();

        } catch (URISyntaxException e) {
            Log.e(TAG, "WebSocket connection error", e);
            Toast.makeText(this, "Failed to connect to server", Toast.LENGTH_SHORT).show();
        }
    }
    /**
     * Joins a game room by sending encrypted join request with username and room code.
     * Also requests the current game state after joining.
     */
    private void joinRoom() {
        try {
            JSONObject roomData = new JSONObject();
            roomData.put("username", username);
            roomData.put("room_code", room_code);

            // Encrypt using hybrid encryption
            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(roomData);

            mSocket.emit("join_room", encryptedPayload);

            // Request current game state
            getGameState();
        } catch (Exception e) {
            Log.e(TAG, "Error joining room", e);
        }
    }
    /**
     * Requests the current game state from the server, including player positions,
     * health values, turn information, and chat history. Updates UI accordingly.
     */
    private void getGameState() {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("username", username);
            jsonObject.put("room_code", room_code);

            // Encrypt using hybrid encryption
            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            mSocket.emit("get_game_state", new JSONObject[]{encryptedPayload}, args -> {
                if (args.length > 0 && args[0] != null) {
                    try {
                        JSONObject encryptedResponse = (JSONObject) args[0];

                        // Decrypt the response
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

                        // Process game state
                        JSONObject gameState = gameStateData.getJSONObject("game_state");
                        String status = gameState.getString("status");

                        if (status.equals("started")) {
                            // Set gameStarted to true when the game is running
                            gameStarted = true;

                            int turn = gameState.getInt("turn");
                            String currentPlayerName = gameState.getString("current_player");
                            String nextPlayerName = gameState.getString("next_player");

                            // Update UI with current state
                            runOnUiThread(() -> {
                                currentTurnPlayer = currentPlayerName;
                                nextTurnPlayer = nextPlayerName;
                                currentPlayer.setText(currentPlayerName);
                                nextPlayer.setText(nextPlayerName);

                                // Check if it's this player's turn
                                isTurn = username.equals(currentPlayerName);
                                updateAbilityButtonsState(isTurn);

                                if (isTurn) {
                                    Toast.makeText(Gameplay.this, "It's your turn!", Toast.LENGTH_SHORT).show();
                                }

                                // Start timer (approximate since we don't have exact start time)
                                turnStartTime = System.currentTimeMillis();
                                turnDuration = 60000; // 60 seconds
                                startCountdown();
                            });

                            // Update character health
                            if (gameState.has("character_health")) {
                                JSONObject healthData = gameState.getJSONObject("character_health");
                                for (Iterator<String> it = healthData.keys(); it.hasNext(); ) {
                                    String characterName = it.next();
                                    int hp = healthData.getInt(characterName);
                                    characterHealth.put(characterName, hp);
                                }
                            }

                            // Update chat messages
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
                            gameStarted = true; // Game has started and ended
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

    // Background Music
    /**
     * Starts background music that plays throughout the game session.
     */
    private void startBackgroundMusic() {
        // Start PlayService with the music URL
        Intent playIntent = new Intent(this, PlayService.class);
        startService(playIntent);
    }
    /**
     * Stops the background music.
     */
    private void stopBackgroundMusic() {
        Intent playIntent = new Intent(this, PlayService.class);
        stopService(playIntent);
    }

    // Event Listeners for Socket.IO Events
    /**
     * Handles turn started event from server. Updates current/next player display,
     * enables/disables ability buttons, and starts countdown timer.
     */
    private Emitter.Listener onTurnStarted = args -> {
        try {
            Log.d(TAG, "TURN_STARTED event received");
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

            // Extract turn data
            String currentPlayer = data.getString("current_player");
            String nextPlayer = data.getString("next_player");
            long startTime = data.has("start_time") ? data.getLong("start_time") * 1000 : System.currentTimeMillis(); // Convert to milliseconds
            long duration = data.has("duration") ? data.getLong("duration") * 1000 : 60000; // Convert to milliseconds or default to 60s

            // Store the timing info
            turnStartTime = startTime;
            turnDuration = duration;

            // Update turn info
            runOnUiThread(() -> {
                // Cancel any existing timer first
                if (timerRunnable != null) {
                    timerHandler.removeCallbacks(timerRunnable);
                    timerRunnable = null;
                }

                // Update turn status
                currentTurnPlayer = currentPlayer;
                nextTurnPlayer = nextPlayer;
                this.currentPlayer.setText(currentTurnPlayer);
                this.nextPlayer.setText(nextTurnPlayer);

                // Check if it's this player's turn
                isTurn = username.equals(currentTurnPlayer);
                updateAbilityButtonsState(isTurn);

                // Start countdown with the new timing info
                startCountdown();

                // Notify if it's this player's turn
                if (isTurn) {
                    Toast.makeText(Gameplay.this, "It's your turn!", Toast.LENGTH_SHORT).show();
                }

                Log.d(TAG, "Turn started for player: " + currentPlayer);
                gameStarted = true; // Mark game as started when we receive turn events
            });

        } catch (Exception e) {
            Log.e(TAG, "Error processing turn started event: " + e.getMessage(), e);
        }
    };
    /**
     * Handles turn expiration event. Updates turn information and switches to next player.
     * Shows notification if current player's turn expired.
     */
    private Emitter.Listener onTurnExpired = args -> {
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

            // Extract turn data - make sure these are available in the data
            String currentPlayer = data.getString("current_player");
            String nextPlayer = data.getString("next_player");

            // Cancel any existing timers to ensure clean state
            if (timerRunnable != null) {
                timerHandler.removeCallbacks(timerRunnable);
                timerRunnable = null;
            }

            runOnUiThread(() -> {
                // Add turn expired message to chat
                String expiredMessage = "⏰ Turn expired for " + this.currentTurnPlayer;
                chatMessages.add(expiredMessage);
                chatAdapter.notifyDataSetChanged();
                chatRecyclerView.scrollToPosition(chatMessages.size() - 1);

                // Check if it was this player's turn that expired
                if (isTurn) {
                    Toast.makeText(Gameplay.this, "Your turn expired!", Toast.LENGTH_SHORT).show();
                    isTurn = false; // Ensure turn is properly switched off
                }

                // Force UI update for the new turn
                this.currentTurnPlayer = currentPlayer;
                this.nextTurnPlayer = nextPlayer;
                this.currentPlayer.setText(currentPlayer);
                this.nextPlayer.setText(nextPlayer);

                // Update turn status
                isTurn = username.equals(currentPlayer);
                updateAbilityButtonsState(isTurn);

                // Don't start a new timer here - wait for the turn_started event
                gameStarted = true; // Mark game as started when we receive turn events
            });
        } catch (Exception e) {
            Log.e(TAG, "Error processing turn expired event: " + e.getMessage(), e);
        }
    };
    /**
     * Processes move made by any player. Updates health values, adds combat messages
     * to chat, and handles turn transitions.
     */
    private Emitter.Listener onMoveMade = args -> {
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

            // Extract move data
            String player = data.getString("username");
            String ability = data.getString("ability");
            String target = data.getString("target");
            String effect = data.getString("effect");
            String chatMsg = data.getString("chat");

            runOnUiThread(() -> {
                // Update health if provided
                if (data.has("health")) {
                    try {
                        JSONObject healthUpdates = data.getJSONObject("health");
                        for (Iterator<String> it = healthUpdates.keys(); it.hasNext(); ) {
                            String characterName = it.next();
                            int hp = healthUpdates.getInt(characterName);
                            characterHealth.put(characterName, hp);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error updating health: " + e.getMessage());
                    }
                }

                // Add move message to chat
                String moveMessage = chatMsg + " \n" + effect;
                chatMessages.add(moveMessage);
                chatAdapter.notifyDataSetChanged();
                chatRecyclerView.scrollToPosition(chatMessages.size() - 1);

                gameStarted = true; // Mark game as started when we receive move events
            });

            // Update UI if next turn info is included
            if (data.has("current_player") && data.has("next_player")) {
                String currentPlayer = data.getString("current_player");
                String nextPlayer = data.getString("next_player");
                startNextTurn(currentPlayer, nextPlayer);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing move made event: " + e.getMessage(), e);
        }
    };
    /**
     * Processes skip turn events from players. Updates chat with skip notification.
     */
    private Emitter.Listener onSkipMade = args -> {
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

            // Extract move data
            String player = data.getString("username");

            runOnUiThread(() -> {
                // Add move message to chat
                String moveMessage = "Player " + player + " skipped their turn";
                chatMessages.add(moveMessage);
                chatAdapter.notifyDataSetChanged();
                chatRecyclerView.scrollToPosition(chatMessages.size() - 1);

                gameStarted = true;
            });

            // Update UI if next turn info is included
            if (data.has("current_player") && data.has("next_player")) {
                String currentPlayer = data.getString("current_player");
                String nextPlayer = data.getString("next_player");
                startNextTurn(currentPlayer, nextPlayer);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing move made event: " + e.getMessage(), e);
        }
    };
    /**
     * Handles game completion event. Shows winner dialog, disables all actions,
     * and prepares for return to lobby.
     */
    private Emitter.Listener onGameEnded = args -> {
        runOnUiThread(() -> {
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

                // Get winner
                String winner = data.getString("winner");

                // Update game state
                gameEnded = true;
                gameStarted = true;

                // Stop timer
                if (timerRunnable != null) {
                    timerHandler.removeCallbacks(timerRunnable);
                }

                // Show game end dialog
                showGameEndDialog(winner);

                // Disable all ability buttons
                disableAllAbilityButtons();

                stopBackgroundMusic();

            } catch (Exception e) {
                Log.e(TAG, "Error processing game ended event: " + e.getMessage(), e);
            }
        });
    };
    /**
     * Handles failed game start attempts. Shows error message and returns to lobby.
     */
    private Emitter.Listener onGameStartFailed = args -> {
        try {
            JSONObject encryptedData = (JSONObject) args[0];

            // Decrypt the data as usual
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

                // Return to main menu or waiting room as appropriate
                finish();
            });
        } catch (Exception e) {
            Log.e(TAG, "Error processing game start failed event: " + e.getMessage(), e);
        }
    };

    // Timer Management
    /**
     * Starts the turn countdown timer. Calculates remaining time based on server
     * timestamp and updates display every second. Automatically skips turn when expired.
     */
    private void startCountdown() {
        // Cancel any existing timer
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        // Calculate time remaining based on server time
        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - turnStartTime;
        long remainingTime = Math.max(0, turnDuration - elapsedTime);

        // Initial update
        updateTimerDisplay((int) (remainingTime / 1000));

        // Create timer runnable
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                // Calculate new remaining time
                long currentTime = System.currentTimeMillis();
                long elapsedTime = currentTime - turnStartTime;
                long remainingTime = turnDuration - elapsedTime;

                if (remainingTime <= 0) {
                    // Timer expired
                    updateTimerDisplay(0);
                    // Don't remove timerRunnable reference here, let the server notify us

                    if (isTurn) {
                        Toast.makeText(Gameplay.this, "Time's up!", Toast.LENGTH_SHORT).show();
                        sendSkipToServer();
                        // Don't start a new timer here - wait for the server to tell us about the next turn
                        isTurn = false; // Mark our turn as done
                        updateAbilityButtonsState(false); // Disable buttons
                    }

                    // Don't disable buttons here, wait for the server notification
                } else {
                    // Update timer display
                    updateTimerDisplay((int) (remainingTime / 1000));
                    // Schedule next update
                    timerHandler.postDelayed(this, 1000);
                }
            }
        };

        // Start the timer
        timerHandler.postDelayed(timerRunnable, 1000);
    }
    /**
     * Updates UI for a new turn. Sets current/next player displays, enables/disables
     * buttons, and restarts the countdown timer.
     * @param currentPlayer Username of the current player
     * @param nextPlayer Username of the next player
     */
    private void startNextTurn(String currentPlayer, String nextPlayer) {
        // Update UI
        currentTurnPlayer = currentPlayer;
        nextTurnPlayer = nextPlayer;

        runOnUiThread(() -> {
            // Update UI elements
            this.currentPlayer.setText(currentTurnPlayer);
            this.nextPlayer.setText(nextTurnPlayer);

            // Check if it's this player's turn
            isTurn = username.equals(currentTurnPlayer);
            updateAbilityButtonsState(isTurn);

            // Show notification for player's turn
            if (isTurn) {
                Toast.makeText(Gameplay.this, "It's your turn! You have 60 seconds.", Toast.LENGTH_SHORT).show();
            }

            // Start the countdown timer for the new turn
            turnStartTime = System.currentTimeMillis();
            turnDuration = 60000; // 60 seconds
            startCountdown();

            gameStarted = true; // Mark game as started when turns change
        });
    }
    /**
     * Updates the timer display with remaining seconds. Changes color to red when
     * time is running low.
     * @param secondsRemaining Seconds left in current turn
     */
    private void updateTimerDisplay(int secondsRemaining) {
        if (secondsRemaining > 60) {
            secondsRemaining = 60;
        }
        timer.setText(String.valueOf(secondsRemaining));

        // Change color based on remaining time
        if (secondsRemaining <= 10) {
            timer.setTextColor(Color.RED);
        } else  {
            timer.setTextColor(Color.WHITE);
        }
    }

    // Button State Management
    /**
     * Enables or disables all ability buttons based on turn state. Provides visual
     * feedback by changing opacity.
     * @param enabled Whether buttons should be enabled
     */
    private void updateAbilityButtonsState(boolean enabled) {
        ability1.setEnabled(enabled);
        ability2.setEnabled(enabled);
        ability3.setEnabled(enabled);
        ability4.setEnabled(enabled);
        ability5.setEnabled(enabled);
        ability6.setEnabled(enabled);

        // Visual feedback for disabled buttons
        float alpha = enabled ? 1.0f : 0.5f;
        ability1.setAlpha(alpha);
        ability2.setAlpha(alpha);
        ability3.setAlpha(alpha);
        ability4.setAlpha(alpha);
        ability5.setAlpha(alpha);
        ability6.setAlpha(alpha);
    }
    /**
     * Disables all ability buttons. Used when game ends or turn transitions.
     */
    private void disableAllAbilityButtons() {
        updateAbilityButtonsState(false);
    }
    /**
     * Maps character abilities to UI buttons. Retrieves abilities from character
     * data and assigns them to corresponding buttons.
     */
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
    /**
     * Sets the text for a specific ability button. Handles cases where character
     * has fewer abilities than available buttons.
     * @param button The button to update
     * @param abilities List of ability names
     * @param index Index of ability to display
     */
    private void setAbilityButtonText(CustomButton button, List<String> abilities, int index) {
        if (index < abilities.size()) {
            button.setButtonText(abilities.get(index));
        } else {
            button.setButtonText("--");
        }
    }

    // Character and Health Management
    /**
     * Loads character data from server for the current player. Retrieves abilities,
     * initializes health, and updates UI accordingly.
     */
    private void loadCharacter() {
        showLoadingDialog("Loading character...");
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("username", username);
            jsonObject.put("name", character_name);

            // Use hybrid encryption for the request
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
                            character.setHealth(50); // Default starting health
                            user_character = character;

                            // Initialize character health
                            characterHealth.put(character.getCharacterName(), 50);

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
    /**
     * Refreshes data for both player groups by calling getGroup1() and getGroup2().
     */
    private void getGroups() {
        Log.d(TAG, "Refreshing groups data");
        getGroup1();
        getGroup2();
    }
    /**
     * Loads group 1 character data from server. Updates character list and health
     * values for all group 1 members.
     */
    private void getGroup1() {
        // Similar to original implementation but add health initialization
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
                                CharactersList character = new CharactersList(
                                        characterObject.getString("name"),
                                        characterObject.getString("desc"),
                                        characterObject.getString("username")
                                );
                                // Initialize health if not already set
                                if (!characterHealth.containsKey(character.getCharacterName())) {
                                    characterHealth.put(character.getCharacterName(), 50); // Default health
                                }
                                character.setHealth(characterHealth.get(character.getCharacterName()));
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
    /**
     * Loads group 2 character data from server. Updates character list and health
     * values for all group 2 members.
     */
    private void getGroup2() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("username", username);
            jsonObject.put("room_code", room_code);

            // Use hybrid encryption for the request
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
                                        characterObject.getString("desc"),
                                        characterObject.getString("username")
                                );
                                // Initialize health if not already set
                                if (!characterHealth.containsKey(character.getCharacterName())) {
                                    characterHealth.put(character.getCharacterName(), 50); // Default health
                                }
                                character.setHealth(characterHealth.get(character.getCharacterName()));
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

    // Gameplay Actions
    /**
     * Handles ability button clicks. Checks if it's player's turn and game is active,
     * then initiates ability usage process.
     * @param v The clicked button view
     */
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

        // Get the ability name from the button
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

        // Get ability details
        getAbilityDetails(abilityName);
    }

    /**
     * Retrieves ability details from server including type, damage dice, and description.
     * Shows ability description dialog after receiving data.
     * @param abilityName Name of the ability to fetch details for
     */
    private void getAbilityDetails(String abilityName) {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("username", username);
            jsonObject.put("ability", abilityName);

            // Encrypt the data
            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            // Create socket event
            mSocket.emit("get_ability", new JSONObject[]{encryptedPayload}, args -> {
                if (args.length > 0 && args[0] != null) {
                    try {
                        JSONObject encryptedResponse = (JSONObject) args[0];

                        // Decrypt the response
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

                        // Get ability details
                        String abilityType = abilityData.optString("type", "");
                        String abilityDesc = abilityData.optString("desc", "");
                        String numDiceStr = abilityData.optString("num", "1");
                        String diceType = abilityData.optString("dice", "d6");

                        // Log the received ability data
                        Log.d(TAG, "Received ability details for " + abilityName + ": type=" + abilityType +
                                ", num=" + numDiceStr + ", dice=" + diceType);

                        // Ensure numDice is at least 1
                        int numDice = 1;
                        try {
                            numDice = Integer.parseInt(numDiceStr);
                            if (numDice < 1) numDice = 1;
                        } catch (NumberFormatException e) {
                            Log.e(TAG, "Error parsing numDice: " + numDiceStr, e);
                        }

                        // Use default d6 if diceType is empty
                        if (diceType == null || diceType.isEmpty()) {
                            diceType = "d6";
                            Log.w(TAG, "Empty dice type received, using default d6");
                        }

                        // Store final values for lambda
                        final String finalDiceType = diceType;
                        final int finalNumDice = numDice;
                        final String finalAbilityType = abilityType;
                        final String finalAbilityDesc = abilityDesc;

                        // Show ability description on UI thread
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
    /**
     * Rolls dice for selected ability and shows confirmation dialog before sending move.
     * Calculates total damage/heal value based on dice rolls.
     * @param abilityName Name of the ability being used
     * @param targetName Name of the target character
     * @param type "a" for attack, other values for heal
     * @param numDice Number of dice to roll
     * @param diceType Type of dice (e.g., "d6", "d8", "d12")
     */
    private void rollDiceAndMakeMove(String abilityName, String targetName, String type, int numDice, String diceType) {
        try {
            // Check if diceType is empty or null
            if (diceType == null || diceType.isEmpty()) {
                Log.e(TAG, "Invalid dice type: " + diceType);
                Toast.makeText(this, "Error: Invalid dice type", Toast.LENGTH_SHORT).show();
                return;
            }

            // Make sure diceType is at least 2 characters long (e.g. "d4")
            if (diceType.length() < 1) {
                Log.e(TAG, "Dice type too short: " + diceType);
                Toast.makeText(this, "Error: Invalid dice format", Toast.LENGTH_SHORT).show();
                return;
            }

            // Parse dice type (e.g., "d4" -> 4 sides)
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

            // Roll the dice
            int[] diceRolls = new int[numDice];
            int totalValue = 0;

            for (int i = 0; i < numDice; i++) {
                diceRolls[i] = (int) (Math.random() * diceSides) + 1;
                totalValue += diceRolls[i];
            }

            // Show dice roll results
            StringBuilder rollMessage = new StringBuilder();
            rollMessage.append("You rolled: ");
            for (int i = 0; i < diceRolls.length; i++) {
                rollMessage.append(diceRolls[i]);
                if (i < diceRolls.length - 1) {
                    rollMessage.append(", ");
                }
            }
            rollMessage.append("\nTotal: ").append(totalValue);

            // Store final value for use in lambda
            final int finalValue = totalValue;

            // Display roll results and confirm move
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Dice Roll Results")
                    .setMessage(rollMessage.toString())
                    .setPositiveButton("Confirm", (dialog, which) -> {
                        // Send the move to the server
                        sendMoveToServer(abilityName, targetName, type, finalValue);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error in rollDiceAndMakeMove: " + e.getMessage(), e);
            Toast.makeText(this, "An error occurred: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    /**
     * Sends move data to server with ability, target, and calculated value.
     * Disables ability buttons after sending.
     * @param ability Name of the ability used
     * @param target Target character name
     * @param type Ability type ("a" for attack)
     * @param value Calculated damage/heal value
     */
    private void sendMoveToServer(String ability, String target, String type, int value) {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("username", username);
            jsonObject.put("ability", ability);
            jsonObject.put("target", target);
            jsonObject.put("type", type);
            jsonObject.put("value", value);
            jsonObject.put("room_code", room_code);
            jsonObject.put("character", character_name);

            // Encrypt the data
            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            // Send the move
            mSocket.emit("make_move", encryptedPayload);

            // Our turn is now done - disable buttons until turn_changed event
            isTurn = false;
            updateAbilityButtonsState(false);

            gameStarted = true; // Mark game as started when we make a move

        } catch (Exception e) {
            Log.e(TAG, "Error sending move to server", e);
            Toast.makeText(this, "Error sending move: " + e.getMessage(), Toast.LENGTH_SHORT).show();

            isTurn = true;
            updateAbilityButtonsState(true);
        }
    }
    /**
     * Sends skip turn request to server when time expires or player chooses to skip.
     */
    private void sendSkipToServer() {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("username", username);
            jsonObject.put("room_code", room_code);

            // Encrypt the data
            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            // Send the move
            mSocket.emit("skip_turn", encryptedPayload);

            // Our turn is now done - disable buttons until turn_changed event
            isTurn = false;
            updateAbilityButtonsState(false);

            gameStarted = true; // Mark game as started when we make a move

        } catch (Exception e) {
            Log.e(TAG, "Error sending move to server", e);
            Toast.makeText(this, "Error sending move: " + e.getMessage(), Toast.LENGTH_SHORT).show();

            isTurn = true;
            updateAbilityButtonsState(true);
        }
    }

    // UI Helper Methods
    /**
     * Displays dialog with ability description and usage confirmation.
     * @param abilityName Name of the ability
     * @param description Ability description text
     * @param type Ability type ("a" for attack)
     * @param numDice Number of dice to roll
     * @param diceType Type of dice to use
     */
    private void showAbilityDescriptionDialog(String abilityName, String description, String type, int numDice, String diceType) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(abilityName)
                .setMessage(description)
                .setPositiveButton("Use Ability", (dialog, which) -> {
                    // Show target selection
                    showTargetSelectionDialog(abilityName, type, numDice, diceType);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    /**
     * Shows target selection dialog based on ability type. For attacks, shows enemy
     * team members. For heals, shows allied team members.
     * @param abilityName Name of the ability being used
     * @param type Ability type determining valid targets
     * @param numDice Number of dice for the ability
     * @param diceType Type of dice to roll
     */
    private void showTargetSelectionDialog(String abilityName, String type, int numDice, String diceType) {

        Log.d(TAG, "Showing target selection dialog for ability: " + abilityName);

        List<CharactersList> targetGroup;
        String dialogTitle;

        // Determine which group to target based on ability type
        if (type.equals("a")) { // Attack ability
            targetGroup = group.equals("group1") ? group2 : group1;
            dialogTitle = "Select target to attack";
        } else { // Heal ability
            targetGroup = group.equals("group1") ? group1 : group2;
            dialogTitle = "Select ally to heal";
        }

        // Log the available targets
        Log.d(TAG, "Available targets: " + targetGroup.size());
        for (CharactersList character : targetGroup) {
            Log.d(TAG, "Target: " + character.getCharacterName() + ", Health: " +
                    characterHealth.get(character.getCharacterName()));
        }

        // At the beginning of showTargetSelectionDialog
        if (targetGroup == null || targetGroup.isEmpty()) {
            Toast.makeText(this, "No valid targets available", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create target list adapter
        TargetAdapter adapter = new TargetAdapter(this, targetGroup);

        // Create dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.target_selection_dialog, null);
        ListView targetListView = dialogView.findViewById(R.id.target_list);
        targetListView.setAdapter(adapter);

        builder.setTitle(dialogTitle)
                .setView(dialogView)
                .setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.show();

        // Set click listener
        targetListView.setOnItemClickListener((parent, view, position, id) -> {
            CharactersList target = targetGroup.get(position);
            dialog.dismiss();
            rollDiceAndMakeMove(abilityName, target.getCharacterName(), type, numDice, diceType);
        });
    }
    /**
     * Displays dialog showing current health status for all characters in both groups.
     * Uses separate ListViews for each group with health bars.
     */
    private void showHealthStatusDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.health_status_dialog, null);

        ListView group1ListView = dialogView.findViewById(R.id.group1_health_list);
        ListView group2ListView = dialogView.findViewById(R.id.group2_health_list);

        // Create adapters for both groups
        HealthAdapter group1Adapter = new HealthAdapter(this, group1);
        HealthAdapter group2Adapter = new HealthAdapter(this, group2);

        group1ListView.setAdapter(group1Adapter);
        group2ListView.setAdapter(group2Adapter);

        builder.setTitle("Character Health Status")
                .setView(dialogView)
                .setPositiveButton("Close", null);

        builder.create().show();
    }
    /**
     * Shows game end dialog with winner information and option to return to lobby.
     * Displays personalized message based on whether player's team won.
     * @param winner Group that won ("group1", "group2", or "tie")
     */
    private void showGameEndDialog(String winner) {
        String message;
        if (winner.equals("group1")) {
            message = "Group 1 has won the battle!";
        } else if (winner.equals("group2")) {
            message = "Group 2 has won the battle!";
        } else {
            message = "The battle ended in a tie!";
        }

        // Check if this player's group won
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
                    // Navigate back to lobby
                    finish();
                })
                .setCancelable(false)
                .show();
    }
    /**
     * Displays a loading dialog with custom message. Used during network operations.
     * @param message The message to display in the dialog
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
     * Dismisses the currently displayed loading dialog.
     */
    private void dismissLoadingDialog() {
        runOnUiThread(() -> {
            if (loadingDialog != null && loadingDialog.isShowing()) {
                loadingDialog.dismiss();
            }
        });
    }

    /**
     * Called when activity is destroyed. Removes player from room if game hasn't started,
     * cancels timers, removes socket listeners, and disconnects if game ended.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Only remove player from room if game hasn't started
        if (!gameStarted) {
            try {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("username", username);
                jsonObject.put("room_code", room_code);

                // Use hybrid encryption for the request
                JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

                RequestBody body = RequestBody.create(
                        encryptedPayload.toString(),
                        MediaType.get("application/json; charset=utf-8")
                );

                Request request = new Request.Builder()
                        .url(SERVER_URL + "/remove_player_from_room")
                        .post(body)
                        .build();

                // Make the request asynchronously
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

        // Cancel any active timers
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        // Clean up socket listeners but don't disconnect the socket
        if (mSocket != null) {
            mSocket.off("turn_started", onTurnStarted);
            mSocket.off("turn_expired", onTurnExpired);
            mSocket.off("move_made", onMoveMade);
            mSocket.off("game_ended", onGameEnded);
            mSocket.off("game_start_failed", onGameStartFailed);
        }

        // If the game ended properly, clear the socket reference
        if (gameEnded) {
            if (mSocket != null) {
                mSocket.disconnect();
                GlobalSocketManager.setSocket(null);
            }
        }
    }
    /**
     * Handles back button press. Shows confirmation dialog before leaving an active game,
     * or allows immediate exit if game has ended.
     */
    @Override
    public void onBackPressed() {
        if (gameEnded) {
            super.onBackPressed();
            return;
        }

        // Show confirmation dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Leave Game")
                .setMessage("Are you sure you want to leave the game? Your character will be removed from the battle.")
                .setPositiveButton("Yes", (dialog, which) -> {
                    // Call remove_player_from_room endpoint
                    try {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("username", username);
                        jsonObject.put("room_code", room_code);

                        // Use hybrid encryption for the request
                        JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

                        RequestBody body = RequestBody.create(
                                encryptedPayload.toString(),
                                MediaType.get("application/json; charset=utf-8")
                        );

                        Request request = new Request.Builder()
                                .url(SERVER_URL + "/remove_player_from_room")
                                .post(body)
                                .build();

                        // Make the request asynchronously
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

                    // Exit the activity
                    super.onBackPressed();
                })
                .setNegativeButton("No", (dialog, which) -> {
                    // Do nothing, stay in the game
                    dialog.dismiss();
                })
                .show();
    }
}