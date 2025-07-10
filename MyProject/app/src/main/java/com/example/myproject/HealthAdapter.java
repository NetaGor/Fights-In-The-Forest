/**
 * HealthAdapter - ListView adapter for character health display
 *
 * Shows character health with visual indicators (bars, colors).
 */
package com.example.myproject;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

public class HealthAdapter extends ArrayAdapter<CharactersList> {
    public HealthAdapter(Context context, List<CharactersList> characters) {
        super(context, 0, characters);
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.health_list_item, parent, false);
        }

        CharactersList character = getItem(position);

        TextView characterNameView = convertView.findViewById(R.id.health_character_name);
        TextView usernameView = convertView.findViewById(R.id.health_username);
        TextView healthView = convertView.findViewById(R.id.health_points);
        View healthBar = convertView.findViewById(R.id.health_bar);

        if (character != null) {
            characterNameView.setText(character.getCharacterName());
            usernameView.setText(character.getUsername());

            int health = character.getHealth();
            healthView.setText(health + "/50");

            ViewGroup.LayoutParams params = healthBar.getLayoutParams();
            params.width = (int) (((float) health / 50f) * parent.getWidth() * 0.8f);
            healthBar.setLayoutParams(params);

            if (health <= 10) {
                healthBar.setBackgroundColor(Color.RED);
            } else if (health <= 25) {
                healthBar.setBackgroundColor(Color.YELLOW);
            } else {
                healthBar.setBackgroundColor(Color.GREEN);
            }
        }

        return convertView;
    }
}