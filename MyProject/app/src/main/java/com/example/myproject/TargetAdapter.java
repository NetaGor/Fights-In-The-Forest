/**
 * TargetAdapter.java - Adapter for displaying target selection items
 *
 * This adapter handles the display of character targets in list views,
 * binding character data to the target_list_item layout with visual health indicators.
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

public class TargetAdapter extends ArrayAdapter<CharactersList> {
    /**
     * Constructor for the adapter
     *
     * @param context The application context
     * @param characters List of character objects to display as targets
     */
    public TargetAdapter(Context context, List<CharactersList> characters) {
        super(context, 0, characters);
    }

    /**
     * Creates or reuses a view for a list item and populates it with target data
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
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.target_list_item, parent, false);
        }

        // Get the character at this position
        CharactersList character = getItem(position);

        // Find view elements
        TextView characterNameView = convertView.findViewById(R.id.character_name);
        TextView usernameView = convertView.findViewById(R.id.username);
        TextView healthView = convertView.findViewById(R.id.health_value);

        if (character != null) {
            // Bind character data to views
            characterNameView.setText(character.getCharacterName());
            usernameView.setText("Player: " + character.getUsername());

            // Get health from our health map and update UI accordingly
            Integer health = Gameplay.characterHealth.get(character.getCharacterName());
            if (health != null) {
                healthView.setText("HP: " + health);

                // Change color based on health level
                if (health <= 10) {
                    healthView.setTextColor(Color.RED);     // Critical health
                } else if (health <= 25) {
                    healthView.setTextColor(Color.YELLOW);  // Low health
                } else {
                    healthView.setTextColor(Color.GREEN);   // Normal health
                }
            }
        }

        return convertView;
    }
}