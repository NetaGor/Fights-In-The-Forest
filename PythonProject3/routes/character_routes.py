"""
Character management routes for creating, updating, and deleting characters.

Handles:
- Character retrieval
- Character creation/update
- Character deletion
- Secure encryption of character data
"""

import traceback
import uuid
from flask import request, jsonify
from config import app, db, USERS_COLLECTION
from security.encryption_utils import (
    encrypt_response, decrypt_request, encrypt_for_database, decrypt_from_database
)


def get_characters_func(username):
    """Retrieve all characters for a user with proper decryption."""
    user_ref = db.collection(USERS_COLLECTION).document(username)
    collection_ref = user_ref.collection("characters")
    docs = collection_ref.stream()

    characters = []
    for doc in docs:
        char_data = doc.to_dict()

        if isinstance(char_data, dict) and char_data.get("encrypted", False):
            decrypted_char = decrypt_from_database(char_data)
            if 'unencrypted_name' in char_data:
                decrypted_char['name'] = char_data['unencrypted_name']
            characters.append(decrypted_char)
        else:
            characters.append(char_data)

    return characters


def get_character_func(username, name):
    """Retrieve a specific character by name with decryption."""
    user_ref = db.collection(USERS_COLLECTION).document(username)
    collection_ref = user_ref.collection("characters")

    character_docs = collection_ref.where('unencrypted_name', '==', name).limit(1).stream()

    for doc in character_docs:
        char_data = doc.to_dict()

        if isinstance(char_data, dict) and char_data.get("encrypted", False):
            decrypted_char = decrypt_from_database(char_data)
            decrypted_char['name'] = char_data['unencrypted_name']
            return decrypted_char
        else:
            return char_data

    # Fallback search if unencrypted name fails
    docs = collection_ref.stream()
    for doc in docs:
        char_data = doc.to_dict()

        if isinstance(char_data, dict) and char_data.get("encrypted", False):
            decrypted_char = decrypt_from_database(char_data)
            if decrypted_char.get('name') == name:
                return decrypted_char
        else:
            if char_data.get('name') == name:
                return char_data

    return None


def update_characters(username, new_data):
    """Update characters collection with encryption."""
    user_ref = db.collection(USERS_COLLECTION).document(username)
    characters_ref = user_ref.collection("characters")

    existing_characters = {}
    for doc in characters_ref.stream():
        char_data = doc.to_dict()

        char_name = (char_data.get('unencrypted_name')
                     if 'unencrypted_name' in char_data
                     else (decrypt_from_database(char_data).get('name')
                           if isinstance(char_data, dict) and char_data.get("encrypted", False)
                           else char_data.get('name')))

        existing_characters[char_name] = {'id': doc.id, 'data': char_data}

    updated_names = {char.get('name') for char in new_data}

    for char in new_data:
        char_name = char.get('name')
        char_to_encrypt = char.copy()

        encrypted_char = encrypt_for_database(char_to_encrypt)
        encrypted_char['unencrypted_name'] = char_name

        if char_name in existing_characters:
            doc_id = existing_characters[char_name]['id']
            characters_ref.document(doc_id).set(encrypted_char)
        else:
            characters_ref.add(encrypted_char)

    # Remove characters not in updated data
    for char_name in existing_characters:
        if char_name not in updated_names:
            doc_id = existing_characters[char_name]['id']
            characters_ref.document(doc_id).delete()


def generate_unique_character_id(username):
    """Generate a unique ID for a new character."""
    while True:
        new_id = str(uuid.uuid4())
        user_ref = db.collection(USERS_COLLECTION).document(username)
        characters_ref = user_ref.collection("characters")
        existing_character = characters_ref.document(new_id).get()

        if not existing_character.exists:
            return new_id


@app.route("/get_characters", methods=["POST"])
def get_characters():
    """Retrieve all characters for a user."""
    try:
        data = request.get_json()
        request_json = decrypt_request(data)

        username = request_json.get('username')
        characters = get_characters_func(username)

        response_data = {"characters": characters}
        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred during loading."}), 500


@app.route("/get_character", methods=["POST"])
def get_character():
    """Retrieve a single character by name."""
    try:
        data = request.get_json()
        request_json = decrypt_request(data)

        username = request_json.get('username')
        name = request_json.get('name')

        character = get_character_func(username, name)

        response_data = {"character": character}
        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred during loading."}), 500


@app.route("/save_character", methods=["POST"])
def save_character():
    """Save a new character or update an existing one."""
    try:
        data = request.get_json()
        character_data = decrypt_request(data)

        username = character_data.get("username")
        character_index = character_data.get("character_index", -1)

        if not all(k in character_data for k in ("name", "desc", "abilities")):
            error_response = {"error": "Missing required fields"}
            return jsonify(encrypt_response(error_response, username)), 400

        character = {
            "name": character_data["name"],
            "desc": character_data["desc"],
            "abilities": character_data["abilities"],
        }

        if character_index == -1:
            character["id"] = generate_unique_character_id(username)
        else:
            characters = get_characters_func(username)
            existing_character = characters[character_index]
            if "id" not in existing_character:
                error_response = {"error": "Character id is missing"}
                return jsonify(encrypt_response(error_response, username)), 400
            character["id"] = existing_character["id"]

        characters = get_characters_func(username)
        for i, h in enumerate(characters):
            if i != character_index and h["name"].lower() == character["name"].lower():
                error_response = {"error": "Character name already exists"}
                return jsonify(encrypt_response(error_response, username)), 400

        if character_index == -1:
            characters.append(character)
        else:
            if character_index >= len(characters):
                error_response = {"error": "Invalid character index"}
                return jsonify(encrypt_response(error_response, username)), 400
            characters[character_index] = character

        # Update characters in the database
        update_characters(username, characters)

        response_data = {"message": "Character saved successfully", "characters": characters}
        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred saving character."}), 500


@app.route("/delete_character", methods=["POST"])
def delete_character():
    """Delete a character by name."""
    try:
        data = request.get_json()
        delete_data = decrypt_request(data)

        username = delete_data.get("username")
        name = delete_data.get("name")

        if not username or not name:
            error_response = {"error": "Missing username or character name"}
            return jsonify(encrypt_response(error_response, username)), 400

        # Get the user document reference
        user_ref = db.collection(USERS_COLLECTION).document(username)
        characters_ref = user_ref.collection("characters")

        # First try to find character by unencrypted name
        character_found = False
        character_doc_ref = characters_ref.where('unencrypted_name', '==', name).limit(1).stream()

        for doc in character_doc_ref:
            characters_ref.document(doc.id).delete()
            character_found = True
            break

        # If not found by unencrypted name, try the old way
        if not character_found:
            all_docs = characters_ref.stream()
            for doc in all_docs:
                char_data = doc.to_dict()

                char_name = None
                if isinstance(char_data, dict) and char_data.get("encrypted", False):
                    decrypted_char = decrypt_from_database(char_data)
                    char_name = decrypted_char.get('name')
                else:
                    char_name = char_data.get('name')

                if char_name == name:
                    characters_ref.document(doc.id).delete()
                    character_found = True
                    break

        if not character_found:
            error_response = {"error": "Character not found"}
            return jsonify(encrypt_response(error_response, username)), 404

        response_data = {"message": "Character deleted successfully"}
        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred deleting character."}), 500


@app.route("/get_abilities", methods=["POST"])
def get_abilities():
    """Retrieve all available abilities in the game."""
    try:
        data = request.get_json()
        request_json = decrypt_request(data)

        username = request_json.get('username')

        col = db.collection("ability")
        docs = col.stream()

        abilities = [doc.to_dict().get("name") for doc in docs if "name" in doc.to_dict()]
        response_data = {"abilities": abilities}

        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred fetching abilities."}), 500


@app.route('/get_ability_details', methods=['POST'])
def get_ability_details():
    """Retrieve details for a specific ability."""
    try:
        data = request.get_json()
        request_json = decrypt_request(data)

        ability_name = request_json.get('ability')
        username = request_json.get('username')

        if not ability_name or not username:
            error_response = {'error': 'Missing ability name or username'}
            return jsonify(encrypt_response(error_response, username)), 400

        ability_docs = db.collection("ability").where('name', '==', ability_name).limit(1).get()

        if not ability_docs or len(ability_docs) == 0:
            error_response = {'error': 'Ability not found'}
            return jsonify(encrypt_response(error_response, username)), 404

        ability_data = ability_docs[0].to_dict()
        ability_type = ability_data.get("type", "")
        ability_desc = ability_data.get("desc", "")
        num_dice = ability_data.get("num", "")
        dice_type = ability_data.get("dice", "")

        response_data = {
            'type': ability_type,
            'desc': ability_desc,
            'num': num_dice,
            'dice': dice_type
        }

        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred getting ability details."}), 500