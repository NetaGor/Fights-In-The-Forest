/**
 * CharactersList.java - Data model for character information
 *
 * This class represents a game character with its properties including name,
 * description, abilities, owner username, and health points.
 *
 * Implements Serializable to allow passing character data between activities.
 */
package com.example.myproject;

import java.io.Serializable;
import java.util.List;

public class CharactersList implements Serializable {
    private String characterName;   // The name of the character
    private String description;     // Character description
    private List<String> abilities; // List of ability names this character has
    private String username = "";   // Username of the character owner
    private int health = 50;        // Default health points

    /**
     * Constructor for creating a new character
     *
     * @param characterName The name of the character
     * @param description A short description of the character
     * @param username The username of the character owner
     */
    public CharactersList(String characterName, String description, String username) {
        this.characterName = characterName;
        this.description = description;
        this.username = username;
    }

    // Getters and setters for character properties

    /**
     * Get the character's name
     *
     * @return The character name as a String
     */
    public String getCharacterName() {
        return this.characterName;
    }

    /**
     * Set the character's name
     *
     * @param characterName The new name for the character
     */
    public void setCharacterName(String characterName) {
        this.characterName = characterName;
    }

    /**
     * Get the username of the character owner
     *
     * @return The username as a String
     */
    public String getUsername() {
        return this.username;
    }

    /**
     * Set the username of the character owner
     *
     * @param username The new username
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Get the character's description
     *
     * @return The description as a String
     */
    public String getDescription() {
        return this.description;
    }

    /**
     * Set the character's description
     *
     * @param description The new description
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Get the character's abilities
     *
     * @return A List of ability names as Strings
     */
    public List<String> getAbilities() {
        return this.abilities;
    }

    /**
     * Set the character's abilities
     *
     * @param abilities A List of ability names
     */
    public void setAbilities(List<String> abilities) {
        this.abilities = abilities;
    }

    /**
     * Get the character's health points
     *
     * @return Health points as an integer
     */
    public int getHealth() {
        return this.health;
    }

    /**
     * Set the character's health points
     *
     * @param health The new health value
     */
    public void setHealth(int health) {
        this.health = health;
    }
}