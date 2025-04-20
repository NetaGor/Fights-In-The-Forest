/**
 * HealthAdapter.java - Adapter for displaying character health information
 *
 * This adapter handles the display of character health in list views,
 * binding health data to the health_list_item layout with visual indicators.
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
    /**
     * Constructor for the adapter
     *
     * @param context The application context
     * @param characters List of character objects to display
     */
    public HealthAdapter(Context context, List<CharactersList> characters) {
        super(context, 0, characters);
    }

    /**
     * Creates or reuses a view for a list item and populates it with health data
     *
     * @param position The position of the item in the list
     * @param convertView The recycled view to populate
     * @param parent The parent that this view will eventually be attached to
     * @return The view for the specified position
     */
    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        // Create view if needed
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.health_list_item, parent, false);
        }

        // Get the character at this position
        CharactersList character = getItem(position);

        // Find view elements
        TextView characterNameView = convertView.findViewById(R.id.health_character_name);
        TextView usernameView = convertView.findViewById(R.id.health_username);
        TextView healthView = convertView.findViewById(R.id.health_points);
        View healthBar = convertView.findViewById(R.id.health_bar);

        if (character != null) {
            // Bind character data to views
            characterNameView.setText(character.getCharacterName());
            usernameView.setText(character.getUsername());

            // Get health from our health map and update UI accordingly
            Integer health = Gameplay.characterHealth.get(character.getCharacterName());
            if (health != null) {
                healthView.setText(health + "/50");

                // Update health bar width based on health percentage
                ViewGroup.LayoutParams params = healthBar.getLayoutParams();
                params.width = (int) (((float) health / 50f) * parent.getWidth() * 0.8f);
                healthBar.setLayoutParams(params);

                // Change color based on health level
                if (health <= 10) {
                    healthBar.setBackgroundColor(Color.RED);     // Critical health
                } else if (health <= 25) {
                    healthBar.setBackgroundColor(Color.YELLOW);  // Low health
                } else {
                    healthBar.setBackgroundColor(Color.GREEN);   // Normal health
                }
            }
        }

        return convertView;
    }
}