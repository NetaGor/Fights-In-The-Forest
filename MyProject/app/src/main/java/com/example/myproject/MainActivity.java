/**
 * MainActivity.java - Application entry point
 *
 * This activity serves as the entry point to the application, displaying options for
 * login or registration. It also monitors battery levels and notifies users when
 * battery is running low.
 */
package com.example.myproject;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity implements View.OnClickListener{
    CustomButton btn_login;           // Button for navigating to login screen
    CustomButton btn_register;        // Button for navigating to registration screen
    BroadCastBattery broadCastBattery; // Receiver for battery status changes

    /**
     * Initializes the activity, sets up UI components, and registers battery monitor
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.opening_screen);

        // Initialize battery monitor
        broadCastBattery = new BroadCastBattery();

        // Set up navigation buttons
        btn_login = findViewById(R.id.btn_login);
        btn_login.setOnClickListener(this);
        btn_register = findViewById(R.id.btn_register);
        btn_register.setOnClickListener(this);
    }

    /**
     * Inner class to monitor battery level and notify user when it's low
     */
    private class BroadCastBattery extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int battery = intent.getIntExtra("level", 0);
            if (battery <= 15) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Please Charge Your Phone!", Toast.LENGTH_SHORT).show());
            }
        }
    }

    /**
     * Registers the battery monitor when activity resumes
     */
    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(broadCastBattery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    /**
     * Unregisters the battery monitor when activity pauses
     */
    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(broadCastBattery);
    }

    /**
     * Handles button click events for navigation
     *
     * @param v The view that was clicked
     */
    @Override
    public void onClick(View v) {
        if (v == btn_login) {
            startActivity(new Intent(this, Login.class));
        }
        if (v == btn_register) {
            startActivity(new Intent(this, Register.class));
        }
    }
}
