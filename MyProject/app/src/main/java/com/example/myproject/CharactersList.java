/**
 * CharactersList - Data model for game characters
 *
 * Stores character info including name, description, abilities, owner, and health.
 */
package com.example.myproject;

import java.io.Serializable;
import java.util.List;

public class CharactersList implements Serializable {
    private String characterName;
    private String description;
    private List<String> abilities;
    private String username = "";
    private int health = 50;

    public CharactersList(String characterName, String description, String username) {
        this.characterName = characterName;
        this.description = description;
        this.username = username;
    }

    public String getCharacterName() {
        return this.characterName;
    }

    public void setCharacterName(String characterName) {
        this.characterName = characterName;
    }

    public String getUsername() {
        return this.username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getAbilities() {
        return this.abilities;
    }

    public void setAbilities(List<String> abilities) {
        this.abilities = abilities;
    }

    public int getHealth() {
        return this.health;
    }

    public void setHealth(int health) {
        this.health = health;
    }
}