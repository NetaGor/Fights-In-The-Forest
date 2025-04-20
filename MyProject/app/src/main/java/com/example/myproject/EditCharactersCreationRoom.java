/**
 * Activity for creating and editing game characters.
 * Allows users to set character name, description, and select abilities.
 */

package com.example.myproject;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.*;

public class EditCharactersCreationRoom extends AppCompatActivity {
    private EditText characterNameEditText, descriptionEditText;
    private CustomButton saveButton, viewAbilitiesButton;
    private List<String> abilities = new ArrayList<>();
    private List<String> selectedAbilitiesList = new ArrayList<>();
    private List<Toolbar> toolbars = new ArrayList<>();
    private Map<Toolbar, String> toolbarAbilities = new HashMap<>();
    private String username;
    private RSAEncryption rsaEncryption;
    private HybridEncryption hybridEncryption;
    private RSAPrivateKey privateKey;
    private int characterIndex = -1;
    private static final String SERVER_URL = "http://10.0.2.2:8080"; // Replace with your actual server URL

    /**
     * Initializes the activity, sets up UI elements, loads encryption keys,
     * and retrieves character data if editing an existing character.
     *
     * @param savedInstanceState If the activity is being re-initialized after previously being shut down,
     *                          this contains the data it most recently supplied in onSaveInstanceState.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.edit_characters);

        characterNameEditText = findViewById(R.id.character_name_edit_text);
        descriptionEditText = findViewById(R.id.description_edit_text);
        saveButton = findViewById(R.id.save_button);
        saveButton.setOnClickListener(v -> saveCharacter());

        // Initialize the view abilities button
        viewAbilitiesButton = findViewById(R.id.view_abilities_button);
        viewAbilitiesButton.setOnClickListener(v -> showAbilitiesDescriptionsDialog());

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

        characterIndex = getIntent().getIntExtra("character_index", -1);

        toolbars.add(findViewById(R.id.toolbar1));
        toolbars.add(findViewById(R.id.toolbar2));
        toolbars.add(findViewById(R.id.toolbar3));
        toolbars.add(findViewById(R.id.toolbar4));
        toolbars.add(findViewById(R.id.toolbar5));
        toolbars.add(findViewById(R.id.toolbar6));

        for (Toolbar toolbar : toolbars) {
            toolbar.setTitle("Select Ability");
            toolbar.setOnClickListener(v -> showAbilityPicker(toolbar));
        }

        if (characterIndex != -1) {
            CharactersList character = CharactersCreationRoom.characters.get(characterIndex);
            characterNameEditText.setText(character.getCharacterName());
            descriptionEditText.setText(character.getDescription());
            loadExistingAbilities(character.getAbilities());
        }

        loadAbilitiesFromServer();
    }

    /**
     * Displays a dialog for selecting an ability to assign to a toolbar.
     *
     * @param toolbar The toolbar to which the selected ability will be assigned
     */
    private void showAbilityPicker(Toolbar toolbar) {
        String[] abilitiesArray = abilities.toArray(new String[0]);

        if (abilitiesArray.length == 0) {
            Toast.makeText(this, "No more abilities available", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Ability")
                .setItems(abilitiesArray, (dialog, which) -> {
                    String selectedAbility = abilitiesArray[which];
                    assignAbilityToToolbar(toolbar, selectedAbility);
                })
                .show();
    }

    /**
     * Assigns a selected ability to a toolbar, updating the available abilities list
     * and selected abilities list. If the toolbar already had an ability assigned,
     * it's returned to the available abilities list.
     *
     * @param toolbar The toolbar to which the ability is being assigned
     * @param ability The name of the ability to assign
     */
    private void assignAbilityToToolbar(Toolbar toolbar, String ability) {
        String previousAbility = toolbarAbilities.get(toolbar);

        // Remove the new ability from available list
        abilities.remove(ability);

        // If there was a previously assigned ability, add it back to available list
        if (previousAbility != null) {
            abilities.add(previousAbility);
            // Also remove it from selected list
            selectedAbilitiesList.remove(previousAbility);
        }

        // Add the new ability to selected list
        selectedAbilitiesList.add(ability);

        // Update the toolbar map
        toolbarAbilities.put(toolbar, ability);

        // Update toolbar title
        toolbar.setTitle(ability);
    }

    /**
     * Populates the toolbars with previously selected abilities when editing an existing character.
     *
     * @param existingAbilities List of ability names that were previously assigned to the character
     */
    private void loadExistingAbilities(List<String> existingAbilities) {
        selectedAbilitiesList.clear();
        toolbarAbilities.clear();

        // Add existing abilities to selected list and assign to toolbars
        for (int i = 0; i < Math.min(existingAbilities.size(), toolbars.size()); i++) {
            String ability = existingAbilities.get(i);
            Toolbar toolbar = toolbars.get(i);
            toolbar.setTitle(ability);
            toolbarAbilities.put(toolbar, ability);
            selectedAbilitiesList.add(ability);
        }
    }

    /**
     * Retrieves detailed information about a specific ability from the server.
     * Uses encryption for secure communication.
     *
     * @param abilityName The name of the ability to retrieve details for
     */
    private void getAbilityDetails(String abilityName) {
        OkHttpClient client = new OkHttpClient();
        JSONObject jsonObject = new JSONObject();

        try {
            jsonObject.put("username", username);
            jsonObject.put("ability", abilityName);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(jsonObject);

            RequestBody body = RequestBody.create(
                    encryptedPayload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(SERVER_URL + "/get_ability_details")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> Toast.makeText(EditCharactersCreationRoom.this, "Failed to load ability details", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> Toast.makeText(EditCharactersCreationRoom.this, "Error loading ability details", Toast.LENGTH_SHORT).show());
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

                            String abilityType = decryptedJson.optString("type", "");
                            String abilityDesc = decryptedJson.optString("desc", "");
                            String numDiceStr = decryptedJson.optString("num", "1");
                            String diceType = decryptedJson.optString("dice", "d6");

                            StringBuilder detailsBuilder = new StringBuilder();
                            detailsBuilder.append("Description: ").append(abilityDesc).append("\n\n");
                            detailsBuilder.append("Type: ").append(abilityType.equals("a") ? "Attack" : "Heal").append("\n");
                            detailsBuilder.append("Dice: ").append(numDiceStr).append("d").append(diceType);

                            final String details = detailsBuilder.toString();

                            runOnUiThread(() -> {
                                showAbilityDescriptionDialog(abilityName, details);
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                            runOnUiThread(() -> Toast.makeText(EditCharactersCreationRoom.this, "Error parsing ability details", Toast.LENGTH_SHORT).show());
                        }
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error encrypting data", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Displays a dialog showing the details of a selected ability.
     *
     * @param abilityName The name of the ability
     * @param description The detailed description of the ability
     */
    private void showAbilityDescriptionDialog(String abilityName, String description) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(abilityName)
                .setMessage(description)
                .setPositiveButton("OK", null)
                .show();
    }

    /**
     * Shows a dialog listing all available abilities that the user can select to view details.
     * Includes both available and already selected abilities.
     */
    private void showAbilitiesDescriptionsDialog() {
        // Create a list of all available abilities to show descriptions for
        List<String> allAbilitiesList = new ArrayList<>(abilities);
        allAbilitiesList.addAll(selectedAbilitiesList);

        if (allAbilitiesList.isEmpty()) {
            Toast.makeText(this, "No abilities available yet", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create a list of ability names for the dialog
        final String[] abilitiesArray = allAbilitiesList.toArray(new String[0]);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Ability to View")
                .setItems(abilitiesArray, (dialog, which) -> {
                    String selectedAbility = abilitiesArray[which];
                    getAbilityDetails(selectedAbility);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Fetches the list of available abilities from the server.
     * Uses encryption for secure communication.
     */
    private void loadAbilitiesFromServer() {
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
                    .url(SERVER_URL + "/get_abilities")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> Toast.makeText(EditCharactersCreationRoom.this, "Failed to load abilities", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> Toast.makeText(EditCharactersCreationRoom.this, "Error loading abilities", Toast.LENGTH_SHORT).show());
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

                            JSONArray abilitiesArray = decryptedJson.getJSONArray("abilities");
                            List<String> tempAbilities = new ArrayList<>();
                            for (int i = 0; i < abilitiesArray.length(); i++) {
                                tempAbilities.add(abilitiesArray.getString(i));
                            }

                            // Remove already selected abilities
                            for (String selectedAbility : selectedAbilitiesList) {
                                tempAbilities.remove(selectedAbility);
                            }

                            runOnUiThread(() -> {
                                abilities.clear();
                                abilities.addAll(tempAbilities);
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                            runOnUiThread(() -> Toast.makeText(EditCharactersCreationRoom.this, "Error parsing abilities", Toast.LENGTH_SHORT).show());
                        }
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error encrypting data", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Validates and saves the character information to the server.
     * Checks for empty fields, length limits, duplicate names, and required abilities.
     * Uses encryption for secure communication.
     */
    private void saveCharacter() {
        String name = characterNameEditText.getText().toString().trim();
        String description = descriptionEditText.getText().toString().trim();

        if (name.isEmpty() || description.isEmpty()) {
            Toast.makeText(this, "Fields cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }

        if (name.length() > 20 || description.length() > 120) {
            Toast.makeText(this, "Input is too long", Toast.LENGTH_SHORT).show();
            return;
        }

        for (int i = 0; i < CharactersCreationRoom.characters.size(); i++) {
            if (i != characterIndex && CharactersCreationRoom.characters.get(i).getCharacterName().equalsIgnoreCase(name)) {
                Toast.makeText(this, "A character with this name already exists. Please choose a different name.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // Verify all toolbars have an ability assigned
        for (Toolbar toolbar : toolbars) {
            if (toolbarAbilities.get(toolbar) == null) {
                Toast.makeText(this, "Please pick 6 abilities.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        JSONObject characterJson = new JSONObject();
        OkHttpClient client = new OkHttpClient();
        try {
            characterJson.put("username", username);
            characterJson.put("name", name);
            characterJson.put("desc", description);
            characterJson.put("abilities", new JSONArray(selectedAbilitiesList));
            characterJson.put("character_index", characterIndex);

            JSONObject encryptedPayload = hybridEncryption.encryptWithPublicKey(characterJson);

            RequestBody body = RequestBody.create(
                    encryptedPayload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(SERVER_URL + "/save_character")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> Toast.makeText(EditCharactersCreationRoom.this, "Failed to save character", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> Toast.makeText(EditCharactersCreationRoom.this, "Error saving character", Toast.LENGTH_SHORT).show());
                    } else {
                        runOnUiThread(() -> {
                            Toast.makeText(EditCharactersCreationRoom.this, "character saved successfully", Toast.LENGTH_SHORT).show();
                            setResult(RESULT_OK);
                            finish();
                        });
                    }
                }
            });
        }  catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error saving character", Toast.LENGTH_SHORT).show();
        }
    }
}