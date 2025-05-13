/**
 * CharactersListAdapter - ListView adapter for character list
 *
 * Displays character info using the item_new_character layout.
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

    public CharactersListAdapter(Context context, List<CharactersList> characters) {
        super(context, R.layout.characters, characters);
        this.context = context;
        this.characters = characters;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_new_character, parent, false);
        }

        CharactersList character = characters.get(position);

        TextView characterName = convertView.findViewById(R.id.character_name);
        TextView characterDescription = convertView.findViewById(R.id.character_description);

        characterName.setText(character.getCharacterName());
        characterDescription.setText(character.getDescription());

        return convertView;
    }
}