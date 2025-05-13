/**
 * TargetAdapter - ListView adapter for target selection
 *
 * Displays characters with health info for ability targeting.
 * Updated to use username-based health tracking and only show characters with health > 0.
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

import java.util.ArrayList;
import java.util.List;

public class TargetAdapter extends ArrayAdapter<CharactersList> {
    private List<CharactersList> filteredCharacters;

    public TargetAdapter(Context context, List<CharactersList> characters) {
        super(context, 0, filterAliveCharacters(characters));
        this.filteredCharacters = filterAliveCharacters(characters);
    }

    /**
     * Filter to only show characters with health > 0
     */
    private static List<CharactersList> filterAliveCharacters(List<CharactersList> characters) {
        List<CharactersList> aliveCharacters = new ArrayList<>();
        for (CharactersList character : characters) {
            String playerUsername = character.getUsername();
            if (Gameplay.characterHealth.containsKey(playerUsername) &&
                    Gameplay.characterHealth.get(playerUsername) > 0) {
                aliveCharacters.add(character);
            }
        }
        return aliveCharacters;
    }

    @Override
    public int getCount() {
        return filteredCharacters.size();
    }

    @Override
    public CharactersList getItem(int position) {
        return filteredCharacters.get(position);
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.target_list_item, parent, false);
        }

        CharactersList character = getItem(position);

        TextView characterNameView = convertView.findViewById(R.id.character_name);
        TextView usernameView = convertView.findViewById(R.id.username);
        TextView healthView = convertView.findViewById(R.id.health_value);

        if (character != null) {
            characterNameView.setText(character.getCharacterName());
            usernameView.setText("Player: " + character.getUsername());

            // Get health by username instead of character name
            String playerUsername = character.getUsername();
            Integer health = Gameplay.characterHealth.get(playerUsername);

            if (health != null) {
                healthView.setText("HP: " + health);

                if (health <= 10) {
                    healthView.setTextColor(Color.RED);
                } else if (health <= 25) {
                    healthView.setTextColor(Color.YELLOW);
                } else {
                    healthView.setTextColor(Color.GREEN);
                }
            } else {
                healthView.setText("HP: 0");
                healthView.setTextColor(Color.RED);
            }
        }

        return convertView;
    }
}