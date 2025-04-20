/**
 * CharactersListAdapter.java - Adapter for displaying character list items
 *
 * This adapter handles the display of character items in list views,
 * binding character data to the item_new_character layout.
 */
package com.example.myproject;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ArrayAdapter;

import java.util.List;

public class CharactersListAdapter extends ArrayAdapter<CharactersList> {
    private final Context context;
    private final List<CharactersList> characters;

    /**
     * Constructor for the adapter
     *
     * @param context The application context
     * @param characters List of character objects to display
     */
    public CharactersListAdapter(Context context, List<CharactersList> characters) {
        super(context, R.layout.characters, characters);
        this.context = context;
        this.characters = characters;
    }

    /**
     * Creates or reuses a view for a list item and populates it with character data
     *
     * @param position The position of the item in the list
     * @param convertView The recycled view to populate
     * @param parent The parent that this view will eventually be attached to
     * @return The view for the specified position
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        // Create view if needed
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_new_character, parent, false);
        }

        // Get the character at this position
        CharactersList character = characters.get(position);

        // Find view elements
        TextView characterName = convertView.findViewById(R.id.character_name);
        TextView characterDescription = convertView.findViewById(R.id.character_description);

        // Bind character data to views
        characterName.setText(character.getCharacterName());
        characterDescription.setText(character.getDescription());

        return convertView;
    }
}