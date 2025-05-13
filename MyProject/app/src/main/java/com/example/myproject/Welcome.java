/**
 * Welcome - Main navigation hub
 *
 * Main screen after login with options to manage characters,
 * start battles, or log out.
 */
package com.example.myproject;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

public class Welcome extends AppCompatActivity implements View.OnClickListener {
    CustomButton characters, fight, log_out;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.welcome);

        characters = findViewById(R.id.characters);
        fight = findViewById(R.id.fight);
        log_out = findViewById(R.id.log_out);

        characters.setOnClickListener(this);
        fight.setOnClickListener(this);
        log_out.setOnClickListener(this);
    }

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