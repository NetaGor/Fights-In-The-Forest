/**
 * MainActivity - App entry point
 *
 * Main screen with login/register options.
 * Monitors battery levels and alerts at 15% or below.
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

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    CustomButton btn_login;
    CustomButton btn_register;
    BroadCastBattery broadCastBattery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.opening_screen);

        broadCastBattery = new BroadCastBattery();

        btn_login = findViewById(R.id.btn_login);
        btn_login.setOnClickListener(this);
        btn_register = findViewById(R.id.btn_register);
        btn_register.setOnClickListener(this);
    }

    /** Monitors battery level and shows low battery warning */
    private class BroadCastBattery extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int battery = intent.getIntExtra("level", 0);
            if (battery <= 15) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Please Charge Your Phone!", Toast.LENGTH_SHORT).show());
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(broadCastBattery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(broadCastBattery);
    }

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