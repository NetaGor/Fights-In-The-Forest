/**
 * CharactersCreationRoom.java - Activity for managing characters
 *
 * This activity allows users to view, create, edit, and delete their game
 * characters. It communicates with the server to fetch and update character
 * data with encrypted communication for security.
 */
package com.example.myproject;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;

import okhttp3.*;

public class CharactersCreationRoom extends AppCompatActivity implements View.OnClickListener {
    static List<CharactersList> characters = new ArrayList<>(); // List of the user's characters
    String username;                                           // Current user's username
    private CustomButton addCharacterButton, backButton;       // UI buttons
    private ListView characterListView;                        // ListView for displaying characters
    private CharactersListAdapter adapter;                     // Adapter for the character list
    private RSAEncryption rsaEncryption;                       // For RSA encryption operations
    private HybridEncryption hybridEncryption;                 // For hybrid encryption operations
    private RSAPrivateKey privateKey;                          // User's private key for decryption
    private static final String TAG = "CharactersCreationRoom"; // Tag for logging
    private static final String SERVER_URL = "http://10.0.2.2:8080"; // Server URL (localhost for emulator)

    /**
     * Initializes the activity, sets up UI components, and loads characters
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.characters);

        // Initialize UI components
        characterListView = findViewById(R.id.list_characters);
        addCharacterButton = findViewById(R.id.add_character);
        backButton = findViewById(R.id.back);
        addCharacterButton.setOnClickListener(this);
        backButton.setOnClickListener(this);

        // Get current username from shared preferences
        SharedPreferences prefs = getSharedPreferences("Current_Connection", MODE_PRIVATE);
        username = prefs.getString("username", "");

        // Initialize encryption components
        rsaEncryption = new RSAEncryption(getApplicationContext());
        hybridEncryption = new HybridEncryption(getApplicationContext());

        // Load the private key for decryption
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

        // Load the user's characters from the server
        loadCharacters();

        // Set up the list adapter and item click listeners
        adapter = new CharactersListAdapter(this, characters);
        characterListView.setAdapter(adapter);

        // Set up click listener for editing a character
        characterListView.setOnItemClickListener((parent, view, position, id) -> {
            Intent intent = new Intent(CharactersCreationRoom.this, EditCharactersCreationRoom.class);
            intent.putExtra("character_index", position);
            intent.putExtra("username", username);
            startActivityForResult(intent, 1);
        });

        // Set up long-click listener for deleting a character
        characterListView.setOnItemLongClickListener((parent, view, position, id) -> {
            new AlertDialog.Builder(CharactersCreationRoom.this)
                    .setTitle("Delete Character")
                    .setMessage("Do you want to delete this character?")
                    .setPositiveButton("Yes", (dialog, which) -> {
                        Log.d("DeleteCharacter", "Name: " + characters.get(position).getCharacterName() + ", Username: " + username);
                        deleteCharacter(position);
                    })
                    .setNegativeButton("No", null)
                    .show();
            return true;
        });
    }

    /**
     * Handles button click events
     *
     * @param v The view that was clicked
     */
    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.add_character) {
            Intent intent = new Intent(this, EditCharactersCreationRoom.class);
            startActivityForResult(intent, 1);
        } else if (v.getId() == R.id.back) {
            Intent intent = new Intent(this, Welcome.class);
            startActivity(intent);
        }
    }

    /**
     * Handles the result from the EditCharactersCreationRoom activity
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) {
            loadCharacters(); // Reload characters after edit/create
        }
    }

    /**
     * Loads the user's characters from the server
     *
     * Uses encrypted communication to securely fetch character data
     */
    private void loadCharacters() {
        OkHttpClient client = new OkHttpClient();
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("username", username);

            // Encrypt the request payload
            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            RequestBody body = RequestBody.create(
                    encryptedPayload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(SERVER_URL + "/get_characters")
                    .post(body)
                    .build();

            // Send the request asynchronously
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Network request failed: " + e.getMessage(), e);
                    runOnUiThread(() -> Toast.makeText(CharactersCreationRoom.this,
                            "Failed to load characters: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "Server returned error: " + response.code());
                        runOnUiThread(() -> Toast.makeText(CharactersCreationRoom.this,
                                "Error fetching characters: " + response.code(), Toast.LENGTH_SHORT).show());
                    } else {
                        try {
                            String responseBody = response.body().string();
                            JSONObject jsonResponse = new JSONObject(responseBody);

                            // Determine encryption method and decrypt response
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

                            // Parse character data from the response
                            JSONArray charactersArray = decryptedJson.getJSONArray("characters");

                            characters.clear();
                            for (int i = 0; i < charactersArray.length(); i++) {
                                JSONObject characterObject = charactersArray.getJSONObject(i);

                                CharactersList character = new CharactersList(
                                        characterObject.getString("name"),
                                        characterObject.getString("desc"),
                                        username
                                );

                                // Parse abilities for each character
                                JSONArray abilitiesArray = characterObject.getJSONArray("abilities");
                                List<String> abilities = new ArrayList<>();
                                for (int j = 0; j < abilitiesArray.length(); j++) {
                                    abilities.add(abilitiesArray.getString(j));
                                }
                                character.setAbilities(abilities);
                                characters.add(character);
                            }

                            // Update UI on the main thread
                            runOnUiThread(() -> {
                                adapter.notifyDataSetChanged();
                                Toast.makeText(CharactersCreationRoom.this,
                                        "Loaded " + characters.size() + " characters", Toast.LENGTH_SHORT).show();
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                            runOnUiThread(() -> Toast.makeText(CharactersCreationRoom.this,
                                    "Error parsing characters data: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error encrypting data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Deletes a character at the specified position
     *
     * @param position The position of the character in the list to delete
     */
    private void deleteCharacter(int position) {
        OkHttpClient client = new OkHttpClient();
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("username", username);
            jsonObject.put("name", characters.get(position).getCharacterName());

            // Encrypt the request payload
            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            RequestBody body = RequestBody.create(
                    encryptedPayload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(SERVER_URL + "/get_characters")
                    .post(body)
                    .build();

            // Send the request asynchronously
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> Toast.makeText(CharactersCreationRoom.this,
                            "Failed to delete character: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> Toast.makeText(CharactersCreationRoom.this,
                                "Error deleting character: " + response.code(), Toast.LENGTH_SHORT).show());
                    } else {
                        try {
                            loadCharacters(); // Reload characters after deletion
                            runOnUiThread(() -> Toast.makeText(CharactersCreationRoom.this,
                                    "Character deleted successfully", Toast.LENGTH_SHORT).show());
                        } catch (Exception e) {
                            e.printStackTrace();
                            runOnUiThread(() -> Toast.makeText(CharactersCreationRoom.this,
                                    "Error processing response: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error encrypting delete data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
