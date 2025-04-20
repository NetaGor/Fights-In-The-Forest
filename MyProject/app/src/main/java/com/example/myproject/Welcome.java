/**
 * Welcome.java - Main navigation hub after authentication
 *
 * This activity serves as the main navigation hub after user authentication,
 * providing options to manage characters, start battles, or log out.
 */
package com.example.myproject;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

public class Welcome extends AppCompatActivity implements View.OnClickListener {
    CustomButton characters;  // Button for character management
    CustomButton fight;       // Button for battle mode
    CustomButton log_out;     // Button for logging out

    /**
     * Initializes the activity and sets up navigation buttons
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.welcome);

        // Initialize navigation buttons
        characters = findViewById(R.id.characters);
        fight = findViewById(R.id.fight);
        log_out = findViewById(R.id.log_out);

        // Set click listeners
        characters.setOnClickListener(this);
        fight.setOnClickListener(this);
        log_out.setOnClickListener(this);
    }

    /**
     * Handles button click events for navigation
     *
     * @param v The view that was clicked
     */
    @Override
    public void onClick(View v) {
        Intent intent = null;

        if (v == characters) {
            intent = new Intent(this, CharactersCreationRoom.class);
        } else if (v == fight) {
            intent = new Intent(this, Rooms.class);
        } else if (v == log_out) {
            intent = new Intent(this, MainActivity.class);
        }
        startActivity(intent);
    }
}