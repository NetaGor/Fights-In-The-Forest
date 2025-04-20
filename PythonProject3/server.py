"""
Fights in the Forest - Game Server

This server implements the backend for a turn-based multiplayer game with real-time communication.
The server handles user authentication, character management, room creation, and game mechanics.
It uses Flask for HTTP endpoints and SocketIO for real-time communication.

Security features:
- Hybrid Encryption: Combines RSA and AES for secure communication
- Database Encryption: Sensitive data is encrypted before storage
- Password Hashing: Uses bcrypt for secure password storage
- Public/Private Key Management: Each user has their own public key for secure messaging

Author: Original author unknown, documentation added April 2025
"""

import threading
import time
import os
from Crypto.PublicKey import RSA
from Crypto.Cipher import PKCS1_v1_5
import base64
import firebase_admin
from firebase_admin import credentials, firestore
import bcrypt
import random
import uuid
from flask import Flask, request, jsonify
from flask_socketio import SocketIO
import traceback
from flask_cors import CORS
import json
from HybridEncryption import HybridEncryption
from DBEncryption import DBEncryption

# Dictionary to track active game rooms and connected clients
active_rooms = {}
# Dictionary to track active turn timers by room
active_turn_timers = {}
# Dictionary to track disconnection timers by user-room combination
disconnection_timers = {}

# Create Flask app with CORS support
app = Flask(__name__)
CORS(app, resources={
    r"/*": {
        "origins": "*",
        "allow_headers": [
            "Content-Type",
            "Authorization",
            "Access-Control-Allow-Credentials"
        ],
        "supports_credentials": True
    }
})

# Configure SocketIO with more robust settings
socketio = SocketIO(
    app,
    cors_allowed_origins="*",
    async_mode='eventlet',
    ping_timeout=25000,
    ping_interval=10000,
    logger=True,
    engineio_logger=True
)

# Initialize Firebase
cred = credentials.Certificate("fightsintheforest-firebase-adminsdk-fbsvc-c35c3cb72b.json")
firebase_admin.initialize_app(cred)
db = firestore.client()
print("Connected to Firebase Firestore")

# Constants and configuration
USERS_COLLECTION = "users"
encryption_key = os.environ.get('dskvhnjksdnvkjehfbdskbv')
db_encryption = DBEncryption(encryption_key)
hybrid_encryption = HybridEncryption()

#################################################
# ENCRYPTION AND SECURITY FUNCTIONS
#################################################

# Loads an RSA key from a file for encryption/decryption purposes
def load_key(file_path):
    """
    Load an RSA key from a base64 encoded file

    Args:
        file_path (str): Path to the key file

    Returns:
        RSA key object for encryption/decryption
    """
    with open(file_path, "r") as file:
        base64_key = file.read().strip()
        decoded_key = base64.b64decode(base64_key)
        return RSA.import_key(decoded_key)


# Load the server's keys
private_key = load_key("private.txt")
public_key = load_key("public.txt")


# Retrieves a user's public key from the database with decryption
def get_public_key(username):
    """
    Retrieve a user's public key from the database with decryption

    Args:
        username (str): The username of the user

    Returns:
        str: The user's public key as a string, or None if not found
    """
    try:
        if username is None:
            return None
        # Query the users collection for the specified username
        user_docs = db.collection(USERS_COLLECTION).where('username', '==', username).get()

        # Check if any user documents were found
        if not user_docs or len(user_docs) == 0:
            print(f"User {username} not found in database")
            return None

        # Get the first matching document
        user_doc = user_docs[0].to_dict()

        # Check if the public key exists in the user document
        if 'public_key' not in user_doc or not user_doc['public_key']:
            print(f"No public key found for user {username}")
            return None

        # Return the decrypted public key
        return db_encryption.decrypt_field(user_doc['public_key'])

    except Exception as e:
        print(f"Error retrieving public key for {username}: {str(e)}")
        return None


# Encrypts response data using the hybrid encryption system
def encrypt_response(response_data, username=None):
    """
    Encrypt response data using the hybrid encryption system

    Args:
        response_data (dict/str): The data to encrypt
        username (str, optional): The username to determine the public key to use

    Returns:
        dict: The encrypted data in the appropriate format
    """
    # If username is provided, try to get their public key and use hybrid encryption
    if username:
        user_public_key = get_public_key(username)
        if user_public_key:
            try:
                return hybrid_encryption.encrypt_with_public_key(response_data, user_public_key)
            except Exception as e:
                print(f"Error encrypting with public key: {str(e)}")
                # Fall back to symmetric encryption if hybrid fails

    # Fall back to symmetric encryption
    return hybrid_encryption.encrypt_symmetric(response_data)


# Decrypts request data using the hybrid encryption system
def decrypt_request(request_data):
    """
    Decrypt request data using the hybrid encryption system

    Args:
        request_data (dict): The encrypted request data

    Returns:
        dict/str: The decrypted data
    """
    try:
        # Check for hybrid encryption format
        method = request_data.get("method", "")

        if method == "hybrid-rsa-aes":
            # Decrypt using hybrid method
            encrypted_key = request_data.get("encrypted_key")
            iv = request_data.get("iv")
            encrypted_data = request_data.get("data")

            if not all([encrypted_key, iv, encrypted_data]):
                raise ValueError("Missing required encryption parameters")

            decrypted_data = hybrid_encryption.decrypt_hybrid_request(
                encrypted_key, iv, encrypted_data, private_key
            )

            # If the decrypted data is a JSON string, parse it
            try:
                return json.loads(decrypted_data)
            except json.JSONDecodeError:
                return decrypted_data

        elif "data" in request_data and request_data.get("encrypted", False):
            # Fall back to symmetric decryption
            return hybrid_encryption.decrypt_symmetric(request_data)

        # If no encryption is detected, return the data as is
        return request_data

    except Exception as e:
        print(f"Error decrypting request: {str(e)}")
        import traceback
        traceback.print_exc()
        raise


# Checks if a username already exists in the database
def user_exists(username):
    """
    Check if a username already exists in the database

    Args:
        username (str): The username to check

    Returns:
        bool: True if the username exists, False otherwise
    """
    query = db.collection(USERS_COLLECTION).where('username', '==', username).get()
    return len(query) > 0


# Hashes a password using bcrypt for secure storage
def hash_password(password):
    """
    Hash a password using bcrypt

    Args:
        password (str): The plain text password

    Returns:
        str: The hashed password as a string
    """
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()


# Encrypts sensitive fields in a character before database storage
def encrypt_character(character):
    """
    Encrypt sensitive fields in a character

    Args:
        character (dict): Character data with sensitive fields

    Returns:
        dict: Character with encrypted sensitive fields
    """
    encrypted = character.copy()
    if "desc" in encrypted:
        encrypted["desc"] = db_encryption.encrypt_field(encrypted["desc"])
    if "abilities" in encrypted:
        encrypted["abilities"] = db_encryption.encrypt_field(encrypted["abilities"])
    return encrypted


# Decrypts sensitive fields in a character after database retrieval
def decrypt_character(encrypted_character):
    """
    Decrypt sensitive fields in a character

    Args:
        encrypted_character (dict): Character with encrypted fields

    Returns:
        dict: Character with decrypted fields
    """
    if not encrypted_character:
        return encrypted_character

    character = encrypted_character.copy()
    if "desc" in character:
        character["desc"] = db_encryption.decrypt_field(character["desc"])
    if "abilities" in character:
        character["abilities"] = db_encryption.decrypt_field(character["abilities"])
    return character


# Verifies a password against its hashed version
def check_password(stored_hash, password):
    """
    Verify a password against its hash

    Args:
        stored_hash (str): The hashed password from the database
        password (str): The plain text password to check

    Returns:
        bool: True if password matches, False otherwise
    """
    return bcrypt.checkpw(password.encode(), stored_hash.encode())


# Create cipher objects for encryption/decryption
cipher_rsa_private = PKCS1_v1_5.new(private_key)
cipher_rsa_public = PKCS1_v1_5.new(public_key)

#################################################
# USER AUTHENTICATION ROUTES
#################################################

# Handles user registration with secure password storage and encryption
@app.route('/register', methods=['POST'])
def register():
    """
    Register a new user.

    Request format:
    {
        "username": string,
        "password": string,
        "public_key": string (optional)
    }

    Response:
    {
        "status": "success|error",
        "message": string
    }
    """
    try:
        data = request.get_json()
        credentials = decrypt_request(data)

        username = credentials.get('username')
        password = credentials.get('password')
        user_public_key = credentials.get('public_key')  # Get public key from registration request

        if user_exists(username):
            error_response = {"status": "error", "message": "Username already exists."}
            # Use the public key from the request if available for response encryption
            if user_public_key:
                try:
                    encrypted_response = hybrid_encryption.encrypt_with_public_key(error_response, user_public_key)
                    return jsonify(encrypted_response), 400
                except Exception as e:
                    print(f"Failed to encrypt with client's public key: {str(e)}")

            # Fall back to symmetric encryption
            return jsonify(hybrid_encryption.encrypt_symmetric(error_response)), 400

        # Hash password for security
        hashed_password = hash_password(password)

        # Save user with encrypted sensitive fields
        user_data = {
            'username': username,
            'password': db_encryption.encrypt_field(hashed_password)
        }

        if user_public_key:
            user_data['public_key'] = db_encryption.encrypt_field(user_public_key)

        db.collection(USERS_COLLECTION).document(username).set(user_data)

        success_response = {"status": "success", "message": "User registered successfully."}

        # Try to encrypt with the provided public key first
        if user_public_key:
            try:
                encrypted_response = hybrid_encryption.encrypt_with_public_key(success_response, user_public_key)
                return jsonify(encrypted_response)
            except Exception as e:
                print(f"Failed to encrypt registration response with client's public key: {str(e)}")

        # Fall back to symmetric encryption
        return jsonify(hybrid_encryption.encrypt_symmetric(success_response))

    except Exception as e:
        print(f"Error during registration: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred during registration."}), 500


# Authenticates a user and updates their public key if provided
@app.route('/login', methods=['POST'])
def login():
    """
    Authenticate a user and log them in.

    Request format:
    {
        "username": string,
        "password": string,
        "public_key": string (optional)
    }

    Response:
    {
        "status": "success|error",
        "message": string
    }
    """
    try:
        data = request.get_json()
        credentials = decrypt_request(data)

        username = credentials.get('username')
        password = credentials.get('password')
        user_public_key = credentials.get('public_key')  # Get public key from login request

        query = db.collection(USERS_COLLECTION).where('username', '==', username).get()

        if not query or len(query) == 0:
            error_response = {"status": "error", "message": "User does not exist."}
            # Try to encrypt with the provided public key
            if user_public_key:
                try:
                    encrypted_response = hybrid_encryption.encrypt_with_public_key(error_response, user_public_key)
                    return jsonify(encrypted_response), 400
                except Exception as e:
                    print(f"Failed to encrypt with client's public key: {str(e)}")

            # Fall back to symmetric encryption
            return jsonify(hybrid_encryption.encrypt_symmetric(error_response)), 400

        user_data = query[0].to_dict()

        # Decrypt the stored password
        stored_password_enc = user_data.get("password", "")
        stored_password = db_encryption.decrypt_field(stored_password_enc)

        # Check password
        is_valid_password = check_password(stored_password, password)

        if not is_valid_password:
            error_response = {"status": "error", "message": "Invalid password."}
            # Try to encrypt with the provided public key
            if user_public_key:
                try:
                    encrypted_response = hybrid_encryption.encrypt_with_public_key(error_response, user_public_key)
                    return jsonify(encrypted_response), 400
                except Exception as e:
                    print(f"Failed to encrypt with client's public key: {str(e)}")

            # Fall back to symmetric encryption
            return jsonify(hybrid_encryption.encrypt_symmetric(error_response)), 400

        # If login successful and public key provided, update it in the database
        if user_public_key:
            db.collection(USERS_COLLECTION).document(username).update({
                'public_key': db_encryption.encrypt_field(user_public_key)
            })

        success_response = {"status": "success", "message": "Login successful."}

        # Try to encrypt with the provided public key first
        if user_public_key:
            try:
                encrypted_response = hybrid_encryption.encrypt_with_public_key(success_response, user_public_key)
                return jsonify(encrypted_response)
            except Exception as e:
                print(f"Failed to encrypt login response with client's public key: {str(e)}")

        # Fall back to symmetric encryption
        return jsonify(hybrid_encryption.encrypt_symmetric(success_response))

    except Exception as e:
        print(f"Error during login: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred during login."}), 500


#################################################
# CHARACTER MANAGEMENT ROUTES
#################################################

# Retrieves all characters for a user with decryption
@app.route("/get_characters", methods=["POST"])
def get_characters():
    """
    Get all characters for a user.

    Request format:
    {
        "username": string
    }

    Response:
    {
        "characters": [
            {
                "name": string,
                "desc": string,
                "abilities": array,
                "id": string
            },
            ...
        ]
    }
    """
    try:
        data = request.get_json()
        request_json = decrypt_request(data)

        username = request_json.get('username')
        characters = get_characters_func(username)

        response_data = {"characters": characters}
        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        print(f"Error during loading characters: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred during loading."}), 500


# Retrieves a specific character by name
@app.route("/get_character", methods=["POST"])
def get_character():
    """
    Get a single character by name.

    Request format:
    {
        "username": string,
        "name": string
    }

    Response:
    {
        "character": {
            "name": string,
            "desc": string,
            "abilities": array,
            "id": string
        }
    }
    """
    try:
        data = request.get_json()
        request_json = decrypt_request(data)

        username = request_json.get('username')
        name = request_json.get('name')

        encrypted_character = get_character_func(username, name)
        character = decrypt_character(encrypted_character)

        response_data = {"character": character}
        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        print(f"Error during loading character: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred during loading."}), 500


# Helper function to get all characters for a user and decrypt them
def get_characters_func(username):
    """
    Helper function to get all characters for a user.

    Args:
        username (str): The username to get characters for

    Returns:
        list: List of decrypted character objects
    """
    user_ref = db.collection(USERS_COLLECTION).document(username)
    collection_ref = user_ref.collection("characters")
    docs = collection_ref.stream()

    # Get encrypted characters and decrypt them
    encrypted_characters = [doc.to_dict() for doc in docs]
    return [decrypt_character(char) for char in encrypted_characters]


# Retrieves raw (encrypted) character data by name
def get_character_func(username, name):
    """
    Get raw (encrypted) character data from the database

    Args:
        username (str): The username to get the character for
        name (str): The character name to retrieve

    Returns:
        dict: Raw character data with encrypted fields, or None if not found
    """
    user_ref = db.collection(USERS_COLLECTION).document(username)
    collection_ref = user_ref.collection("characters")
    docs = collection_ref.stream()
    for doc in docs:
        docdict = doc.to_dict()
        if docdict['name'] == name:
            return docdict
    return None


# Updates the characters collection for a user, handling adds/updates/deletes
def update_characters(username, new_data):
    """
    Update the characters collection for a user.

    This function handles adding, updating, and removing characters.

    Args:
        username (str): The username to update characters for
        new_data (list): List of character objects to save
    """
    user_ref = db.collection(USERS_COLLECTION).document(username)
    characters_ref = user_ref.collection("characters")

    # Get all existing characters
    existing_characters = {}
    for doc in characters_ref.stream():
        char_data = doc.to_dict()
        char_name = char_data.get('name')
        existing_characters[char_name] = {'id': doc.id, 'data': char_data}

    # Names from the updated data
    updated_names = {char.get('name') for char in new_data}

    # Handle updates and additions
    for char in new_data:
        char_name = char.get('name')

        if char_name in existing_characters:
            # Update existing character
            doc_id = existing_characters[char_name]['id']
            characters_ref.document(doc_id).set(char)
        else:
            # Add new character
            characters_ref.add(char)

    # Handle deletions
    for char_name in existing_characters:
        if char_name not in updated_names:
            doc_id = existing_characters[char_name]['id']
            characters_ref.document(doc_id).delete()


# Generates a unique ID for new characters
def generate_unique_character_id(username):
    """
    Generate a unique ID for a new character.

    Args:
        username (str): The username to generate the ID for

    Returns:
        str: A unique character ID
    """
    while True:
        new_id = str(uuid.uuid4())
        user_ref = db.collection(USERS_COLLECTION).document(username)
        characters_ref = user_ref.collection("characters")
        existing_character = characters_ref.document(new_id).get()

        if not existing_character.exists:
            return new_id


# Creates or updates a character with encryption
@app.route("/save_character", methods=["POST"])
def save_character():
    """
    Save a new character or update an existing one.

    Request format:
    {
        "username": string,
        "character_index": number,
        "name": string,
        "desc": string,
        "abilities": array
    }

    Response:
    {
        "message": string,
        "characters": array
    }
    """
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

        # Encrypt characters before saving to database
        encrypted_characters = [encrypt_character(char) for char in characters]
        update_characters(username, encrypted_characters)

        response_data = {"message": "Character saved successfully", "characters": characters}
        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        print(f"Error saving character: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred saving character."}), 500


# Updates specific fields of a character
@app.route("/edit_character", methods=["POST"])
def edit_character():
    """
    Edit specific fields of a character.

    Request format:
    {
        "username": string,
        "id": string,
        "desc": string (optional),
        "abilities": array (optional)
    }

    Response:
    {
        "message": string,
        "characters": array
    }
    """
    try:
        data = request.get_json()
        edit_data = decrypt_request(data)

        username = edit_data.get("username")
        character_id = edit_data.get("id")

        if not username or not character_id:
            error_response = {"error": "Missing username or character ID"}
            return jsonify(encrypt_response(error_response, username)), 400

        new_desc = edit_data.get("desc")
        new_abilities = edit_data.get("abilities")

        # Get decrypted characters
        characters = get_characters_func(username)

        character_found = False
        for character in characters:
            if character["id"] == character_id:
                if new_desc:
                    character["desc"] = new_desc
                if new_abilities:
                    character["abilities"] = new_abilities
                character_found = True
                break

        if not character_found:
            error_response = {"error": "Character not found"}
            return jsonify(encrypt_response(error_response, username)), 404

        # Encrypt characters before saving
        encrypted_characters = [encrypt_character(char) for char in characters]
        update_characters(username, encrypted_characters)

        response_data = {"message": "Character updated successfully", "characters": characters}
        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        print(f"Error editing character: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred editing character."}), 500


# Deletes a character by name
@app.route("/delete_character", methods=["POST"])
def delete_character():
    """
    Delete a character by name.

    Request format:
    {
        "username": string,
        "name": string
    }

    Response:
    {
        "message": string
    }
    """
    try:
        data = request.get_json()
        delete_data = decrypt_request(data)

        username = delete_data.get("username")
        name = delete_data.get("name")

        if not username or not name:
            error_response = {"error": "Missing username or character name"}
            return jsonify(encrypt_response(error_response, username)), 400

        print(f"Deleting character: {name} for user: {username}")  # Debug print

        # Get the user document reference
        user_ref = db.collection(USERS_COLLECTION).document(username)
        characters_ref = user_ref.collection("characters")

        # Find and remove the character by name
        character_found = False
        character_doc_ref = characters_ref.where('name', '==', name).limit(1).stream()

        for doc in character_doc_ref:
            # Document found, now delete it
            characters_ref.document(doc.id).delete()
            character_found = True
            break

        if not character_found:
            error_response = {"error": "Character not found"}
            return jsonify(encrypt_response(error_response, username)), 404

        response_data = {"message": "Character deleted successfully"}
        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        print(f"Error deleting character: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred deleting character."}), 500


# Retrieves all available abilities in the game
@app.route("/get_abilities", methods=["POST"])
def get_abilities():
    """
    Get all available abilities in the game.

    Request format:
    {
        "username": string
    }

    Response:
    {
        "abilities": array of strings
    }
    """
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
        print(f"Error fetching abilities: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred fetching abilities."}), 500


# Retrieves details for a specific ability
@app.route('/get_ability_details', methods=['POST'])
def get_ability_details():
    """
    Get details for a specific ability.

    Request format:
    {
        "username": string,
        "ability": string
    }

    Response:
    {
        "type": string,
        "desc": string,
        "num": string/number,
        "dice": string/number
    }
    """
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
        print(f"Error getting ability details: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred getting ability details."}), 500


#################################################
# ROOM MANAGEMENT ROUTES AND SOCKET HANDLERS
#################################################

# Handles when a client connects to the socket server
@socketio.on('connect')
def handle_connect():
    """
    Handle a client connecting to the socket server.
    Initializes the client connection for real-time communication.
    """
    print('Client connected')


# Handles a player joining a game room
@socketio.on('join_room')
def on_join(data):
    """
    Handle a player joining a room with consistent hybrid encryption.

    Event data:
    {
        "username": string,
        "room_code": string
    }

    Emits 'new_player' event to all clients in the room.
    """
    try:
        request_json = decrypt_request(data)

        username = request_json.get('username')
        room_code = request_json.get('room_code')

        # Initialize room in active_rooms if it doesn't exist
        if room_code not in active_rooms:
            active_rooms[room_code] = []

        # Check if there's a pending disconnect timer for this user
        timer_key = f"{username}_{room_code}"
        if timer_key in disconnection_timers:
            # Cancel the disconnect timer since they're reconnecting
            disconnection_timers[timer_key].cancel()
            del disconnection_timers[timer_key]
            print(f"Cancelled disconnect timer for {username} in room {room_code} due to reconnection")

        # Add the user to the room
        already_in_room = False
        for client in active_rooms[room_code]:
            if client.get("username") == username:
                # User already in room, just update their sid
                client["sid"] = request.sid
                already_in_room = True
                print(f"Updated SID for existing user {username} in room {room_code}")
                break

        if not already_in_room:
            # User not in room, add them
            active_rooms[room_code].append({"sid": request.sid, "username": username})
            print(f"Added new user {username} to room {room_code}")

        print(f"User {username} joined room {room_code}")
        print(active_rooms)

        # Notify all users in the room
        for client in active_rooms[room_code]:
            sid = client.get("sid")
            client_username = client.get("username")

            if sid and client_username:
                # Prepare notification data
                notification_data = {
                    'username': username,
                    'room_code': room_code
                }

                # Encrypt with client's public key if available
                encrypted_notification = encrypt_response(notification_data, client_username)
                socketio.emit('new_player', encrypted_notification, to=sid)

    except Exception as e:
        print(f"Error in join_room handler: {str(e)}")
        import traceback
        traceback.print_exc()


# Handles a player joining a specific group within a room
@socketio.on('join_group')
def on_join_group(data):
    """
    Handle a player joining a group with consistent hybrid encryption.

    Event data:
    {
        "username": string,
        "room_code": string,
        "group": string,
        "character_name": string
    }

    Updates room in Firebase and emits 'group_change' event.
    """
    try:
        request_json = decrypt_request(data)

        username = request_json.get('username')
        room_code = request_json.get('room_code')
        group = request_json.get('group')
        character_name = request_json.get('character_name')

        # Update room in Firebase
        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if room_doc.exists:
            room_data = room_doc.to_dict()

            # Track old character name before removing player from groups
            old_character_name = None
            # Remove player from other group if they exist, and remember their character
            for g in ['group1', 'group2']:
                if g in room_data and username in room_data[g]:
                    old_character_name = room_data[g][username]
                    del room_data[g][username]

            # Add player to the specified group
            if group not in room_data:
                room_data[group] = {}
            room_data[group][username] = character_name

            # Initialize character health dictionary if not present
            if 'character_health' not in room_data:
                room_data['character_health'] = {}

            # Remove old character's health if it exists and is different from new character
            if old_character_name and old_character_name != character_name:
                if old_character_name in room_data['character_health']:
                    del room_data['character_health'][old_character_name]

            # Set character health to default 50 if not already set
            if character_name not in room_data['character_health']:
                room_data['character_health'][character_name] = 50

            room_ref.set(room_data)

            # Prepare notification data
            notification_data = {
                'username': username,
                'character_name': character_name,
                'group': group,
                'room_code': room_code
            }

            # Emit group change event to all in the room with encryption
            if room_code in active_rooms:
                for client in active_rooms[room_code]:
                    client_username = client.get("username")
                    sid = client.get("sid")

                    if client_username and sid:
                        # Encrypt with client's public key
                        encrypted_notification = encrypt_response(notification_data, client_username)
                        socketio.emit('group_change', encrypted_notification, to=sid)

    except Exception as e:
        print(f"Error in join_group handler: {str(e)}")
        import traceback
        traceback.print_exc()


# Handles when a player marks themselves as ready to start the game
@socketio.on('press_ready')
def on_press_ready(data):
    """
    Handle a player pressing ready with consistent hybrid encryption.

    Event data:
    {
        "username": string,
        "room_code": string
    }

    Updates ready status and emits 'player_ready' event.
    Checks if all players are ready to start the game.
    """
    try:
        request_json = decrypt_request(data)

        username = request_json.get('username')
        room_code = request_json.get('room_code')

        # Update room in Firebase
        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if room_doc.exists:
            room_data = room_doc.to_dict()

            # Ensure ready_players list exists
            if 'ready_players' not in room_data:
                room_data['ready_players'] = []

            # Add player to ready list if not already there
            if username not in room_data['ready_players']:
                room_data['ready_players'].append(username)

            room_ref.set(room_data)

            # Prepare notification data
            notification_data = {
                'username': username,
                'room_code': room_code
            }

            # Emit ready status to all in the room with encryption
            if room_code in active_rooms:
                for client in active_rooms[room_code]:
                    client_username = client.get("username")
                    sid = client.get("sid")

                    if client_username and sid:
                        # Encrypt with client's public key
                        encrypted_notification = encrypt_response(notification_data, client_username)
                        socketio.emit('player_ready', encrypted_notification, to=sid)

            # Check if game can start
            check_all_ready(room_data, room_code)

    except Exception as e:
        print(f"Error in press_ready handler: {str(e)}")
        import traceback
        traceback.print_exc()


# Handles when a player marks themselves as not ready
@socketio.on('unpress_ready')
def on_unpress_ready(data):
    """
    Handle a player unpressing ready with consistent hybrid encryption.

    Event data:
    {
        "username": string,
        "room_code": string
    }

    Updates ready status and emits 'player_unready' event.
    """
    try:
        request_json = decrypt_request(data)

        username = request_json.get('username')
        room_code = request_json.get('room_code')

        # Update room in Firebase
        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if room_doc.exists:
            room_data = room_doc.to_dict()

            # Ensure ready_players list exists
            if 'ready_players' not in room_data:
                room_data['ready_players'] = []

            # Remove player from ready list if they exist
            if username in room_data['ready_players']:
                room_data['ready_players'].remove(username)

            room_ref.set(room_data)

            # Prepare notification data
            notification_data = {
                'username': username,
                'room_code': room_code
            }

            # Emit unready status to all in the room with encryption
            if room_code in active_rooms:
                for client in active_rooms[room_code]:
                    client_username = client.get("username")
                    sid = client.get("sid")

                    if client_username and sid:
                        # Encrypt with client's public key
                        encrypted_notification = encrypt_response(notification_data, client_username)
                        socketio.emit('player_unready', encrypted_notification, to=sid)

    except Exception as e:
        print(f"Error in unpress_ready handler: {str(e)}")
        import traceback
        traceback.print_exc()


# Handles client disconnection and schedules player removal with timeout
@socketio.on('disconnect')
def handle_disconnect():
    """
    Handle client disconnection from the socket server.

    - Finds which player disconnected by their session ID
    - Schedules player removal after timeout (10 seconds)
    - Cancels removal if player reconnects in time
    """
    print('Client disconnected')

    # Find which player disconnected by looking up their session ID
    sid = request.sid
    disconnected_username = None
    disconnected_room = None

    # Search through all rooms to find the disconnected user
    for room_code, clients in active_rooms.items():
        for client in clients:
            if client.get("sid") == sid:
                disconnected_username = client.get("username")
                disconnected_room = room_code
                # Remove client from active_rooms
                active_rooms[room_code].remove(client)
                break
        if disconnected_username:
            break

    # If we found the disconnected user, schedule their removal from the room
    if disconnected_username and disconnected_room:
        try:
            print(
                f"User {disconnected_username} disconnected from room {disconnected_room}, scheduling removal in 10 seconds")

            # Cancel any existing removal timer for this user in this room
            timer_key = f"{disconnected_username}_{disconnected_room}"
            if timer_key in disconnection_timers:
                # Cancel existing timer
                existing_timer = disconnection_timers[timer_key]
                existing_timer.cancel()
                print(f"Cancelled existing disconnect timer for {disconnected_username}")

            # Create a new timer to remove the player after 10 seconds
            def remove_player():
                try:
                    # Check if player reconnected (their username should be in active_rooms for this room)
                    player_reconnected = False
                    if disconnected_room in active_rooms:
                        for client in active_rooms[disconnected_room]:
                            if client.get("username") == disconnected_username:
                                player_reconnected = True
                                break

                    if player_reconnected:
                        print(f"Player {disconnected_username} reconnected before timeout, not removing from room")
                    else:
                        # Player didn't reconnect, remove them from the room
                        print(
                            f"Removing player {disconnected_username} from room {disconnected_room} after disconnect timeout")

                        # Call remove_player_from_room function
                        room_ref = db.collection('rooms').document(disconnected_room)
                        room_doc = room_ref.get()

                        if room_doc.exists:
                            room_data = room_doc.to_dict()
                            game_state = room_data.get('game_state', {})

                            # Only remove if game hasn't ended
                            if game_state.get('status') != 'ended':
                                room_changed = False

                                # Remove player from various room components
                                if 'players' in room_data and disconnected_username in room_data['players']:
                                    room_data['players'].remove(disconnected_username)
                                    room_changed = True

                                # Find and remove their character
                                old_character_name = None
                                for group in ['group1', 'group2']:
                                    if group in room_data and disconnected_username in room_data[group]:
                                        old_character_name = room_data[group][disconnected_username]
                                        del room_data[group][disconnected_username]
                                        room_changed = True

                                # Remove their character's health
                                if old_character_name and 'character_health' in room_data:
                                    if old_character_name in room_data['character_health']:
                                        del room_data['character_health'][old_character_name]

                                if 'ready_players' in room_data and disconnected_username in room_data['ready_players']:
                                    room_data['ready_players'].remove(disconnected_username)
                                    room_changed = True

                                if 'game_state' in room_data and 'player_order' in room_data[
                                    'game_state'] and disconnected_username in room_data['game_state']['player_order']:
                                    room_data['game_state']['player_order'].remove(disconnected_username)
                                    room_changed = True

                                # Update room if changes were made
                                if room_changed:
                                    room_ref.set(room_data)

                                    # Notify remaining players
                                    notification_data = {
                                        'type': 'player_left',
                                        'username': disconnected_username,
                                        'room_code': disconnected_room,
                                        'reason': 'disconnected'
                                    }

                                    for client in active_rooms[disconnected_room]:
                                        client_username = client.get("username")
                                        client_sid = client.get("sid")

                                        if client_username and client_sid:
                                            encrypted_notification = encrypt_response(notification_data,
                                                                                      client_username)
                                            socketio.emit('update', encrypted_notification, to=client_sid)

                    # Clean up the timer reference
                    if timer_key in disconnection_timers:
                        del disconnection_timers[timer_key]

                except Exception as e:
                    print(f"Error in scheduled disconnect handler: {str(e)}")
                    traceback.print_exc()

            # Create and start the timer
            disconnect_timer = threading.Timer(10.0, remove_player)
            disconnect_timer.daemon = True
            disconnection_timers[timer_key] = disconnect_timer
            disconnect_timer.start()

        except Exception as e:
            print(f"Error handling disconnection: {str(e)}")
            traceback.print_exc()


# Handles HTTP endpoint for joining a room
@app.route('/join_room_route', methods=['POST'])
def join_room_route():
    """
    HTTP endpoint for a player to join a room.

    Request format:
    {
        "username": string,
        "room_code": string
    }

    Response:
    {
        "message": string
    }

    First removes player from any existing rooms,
    then adds them to the requested room if it exists
    and the game hasn't started yet.
    """
    try:
        data = request.get_json()
        request_json = decrypt_request(data)

        username = request_json.get('username')
        room_code = request_json.get('room_code')

        # Remove player from any existing rooms
        remove_player_from_rooms(username)

        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if not room_doc.exists:
            error_response = {'error': 'Room not found'}
            return jsonify(encrypt_response(error_response, username)), 404

        room_data = room_doc.to_dict()

        # Check if game already started
        if room_data.get("game_state", {}).get("status") == 'started':
            error_response = {'error': 'Game already started'}
            return jsonify(encrypt_response(error_response, username)), 400

        # Ensure players is a list and player is not already in room
        if 'players' not in room_data:
            room_data['players'] = []

        if username in room_data['players']:
            error_response = {'error': 'Player already in the room'}
            return jsonify(encrypt_response(error_response, username)), 400

        # Add player to room
        room_data['players'].append(username)
        room_ref.set(room_data)

        response_data = {'message': f'{username} joined room {room_code}'}
        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        print(f"Error joining room: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred joining room."}), 500


# Creates a new game room with a unique code
@app.route('/create_room', methods=['POST'])
def create_room():
    """
    Create a new game room with a unique code.

    Request format:
    {
        "username": string
    }

    Response:
    {
        "room_code": string
    }

    Generates a unique 4-digit room code and initializes
    the room structure in Firebase.
    """
    try:
        data = request.get_json()
        request_json = decrypt_request(data)

        username = request_json.get('username')

        # Remove player from any existing rooms
        remove_player_from_rooms(username)

        # Generate unique room code
        room_code = generate_room_code()

        # Initialize room structure
        room_ref = db.collection('rooms').document(room_code)
        room_ref.set({
            'code': room_code,
            'players': [username],
            'group1': {},
            'group2': {},
            'ready_players': [],
            'character_health': {},  # Initialize empty character health dictionary
            'game_state': {
                'status': 'not started',
                'turn': 1,
                'player_order': [],
            }
        })

        # Initialize the room in active_rooms
        active_rooms[room_code] = []

        # Prepare the response data
        response_data = {'room_code': room_code}
        return jsonify(encrypt_response(response_data, username)), 201

    except Exception as e:
        print(f"Error creating room: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "Error creating room"}), 500


# Removes a player from a specific room
@app.route('/remove_player_from_room', methods=['POST'])
def remove_player_from_room():
    """
    Remove a player from a specific room.

    Request format:
    {
        "username": string,
        "room_code": string
    }

    Response:
    {
        "message": string
    }

    Removes player from the room data and notifies other players.
    """
    try:
        data = request.get_json()
        request_json = decrypt_request(data)

        username = request_json.get('username')
        room_code = request_json.get('room_code')

        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if not room_doc.exists:
            error_response = {'error': 'Room not found'}
            return jsonify(encrypt_response(error_response, username)), 404

        room_data = room_doc.to_dict()

        # Remove player from room
        room_changed = False
        if username in room_data.get('players', []):
            room_data['players'].remove(username)
            room_changed = True

        for group in ['group1', 'group2']:
            if group in room_data and username in room_data[group]:
                del room_data[group][username]
                room_changed = True

        if 'ready_players' in room_data and username in room_data['ready_players']:
            room_data['ready_players'].remove(username)
            room_changed = True

        if room_changed:
            room_ref.set(room_data)

            # Emit update event to all clients in the room
            if room_code in active_rooms:
                notification_data = {
                    'type': 'player_removed',
                    'username': username,
                    'room_code': room_code
                }

                for client in active_rooms[room_code]:
                    client_username = client.get("username")
                    sid = client.get("sid")

                    if client_username and sid:
                        encrypted_notification = encrypt_response(notification_data, client_username)
                        socketio.emit('update', encrypted_notification, to=sid)

        response_data = {'message': f'Player {username} removed from room {room_code}'}
        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        print(f"Error removing player from room: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred removing player from room."}), 500


# Retrieves all data for a specific room
@app.route('/get_room_data', methods=['POST'])
def get_room_data():
    """
    Get all data for a specific room.

    Request format:
    {
        "room_code": string,
        "username": string
    }

    Response:
    {
        "data": {
            // full room data object
        }
    }
    """
    try:
        data = request.get_json()
        request_json = decrypt_request(data)

        room_code = request_json.get('room_code')
        username = request_json.get('username')

        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if not room_doc.exists:
            error_response = {'error': 'Room not found'}
            return jsonify(encrypt_response(error_response, username)), 404

        room_data = room_doc.to_dict()
        response_data = {'data': room_data}

        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        print(f"Error getting room data: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred getting room data."}), 500


# Checks if all players are ready and starts the game if conditions are met
def check_all_ready(room_data, room_code):
    """
    Checks if all players are ready and starts the game if conditions are met.

    Args:
        room_data (dict): The current room data
        room_code (str): The room code

    Conditions for starting:
    - All players must be in ready_players list
    - At least 2 players in the game
    - At least 1 player in either group

    If all conditions are met, the game is started and all clients are notified.
    """
    # Ensure ready_players and players are in the data
    if 'ready_players' not in room_data or 'players' not in room_data:
        return

    # Ensure there are players in the game
    if not room_data['players']:
        return

    if len(room_data['players']) < 2:
        return

    if len(room_data['group1']) < 1 and len(room_data['group2']) < 1:
        return

    # Ensure every player pressed "Fight!"
    if set(room_data['ready_players']) != set(room_data['players']):
        return

    # All conditions met, start the game
    room_ref = db.collection('rooms').document(room_code)
    room_data['game_state']['status'] = 'started'

    # Set player order
    player_list = list(room_data['players'])
    random.shuffle(player_list)
    room_data['game_state']['player_order'] = player_list
    room_ref.set(room_data)

    # Emit game_started event to all clients in the room
    if room_code in active_rooms:
        notification_data = {'event': 'game_started'}

        for client in active_rooms[room_code]:
            client_username = client.get("username")
            sid = client.get("sid")

            if client_username and sid:
                encrypted_notification = encrypt_response(notification_data, client_username)
                socketio.emit('game_started', encrypted_notification, to=sid)


# Removes a player from all existing rooms
def remove_player_from_rooms(username):
    """
    Remove player from all existing rooms

    Args:
        username (str): The username to remove from rooms

    Updates room data and notifies remaining players.
    """
    rooms = db.collection('rooms').stream()
    for room in rooms:
        room_data = room.to_dict()
        room_changed = False

        # Remove player from various room components
        if 'players' in room_data and username in room_data['players']:
            room_data['players'].remove(username)
            room_changed = True

        for group in ['group1', 'group2']:
            if group in room_data and username in room_data[group]:
                del room_data[group][username]
                room_changed = True

        if 'ready_players' in room_data and username in room_data['ready_players']:
            room_data['ready_players'].remove(username)
            room_changed = True

        if 'game_state' in room_data and 'player_order' in room_data['game_state'] and username in \
                room_data['game_state']['player_order']:
            room_data['game_state']['player_order'].remove(username)
            room_changed = True

        # Update room if changes were made
        if room_changed:
            db.collection('rooms').document(room.id).set(room_data)

            # Notify remaining players if any
            if room.id in active_rooms:
                notification_data = {
                    'type': 'player_left',
                    'username': username,
                    'room_code': room.id
                }

                for client in active_rooms[room.id]:
                    client_username = client.get("username")
                    sid = client.get("sid")

                    if client_username and sid:
                        encrypted_notification = encrypt_response(notification_data, client_username)
                        socketio.emit('update', encrypted_notification, to=sid)


# Generates a unique room code for new rooms
def generate_room_code():
    """
    Generates a unique room code

    Returns:
        str: A 4-digit room code that doesn't exist yet
    """
    while True:
        room_code = str(random.randint(1000, 9999))
        room_ref = db.collection('rooms').document(room_code)
        if not room_ref.get().exists:
            return room_code


# Gets all characters in group 1 for a room
@app.route('/get_group1', methods=['POST'])
def get_group1():
    """
    Get characters in group1 for a room.

    Request format:
    {
        "room_code": string,
        "username": string
    }

    Response:
    {
        "characters": [
            {
                "name": string,
                "username": string,
                "desc": string
            },
            ...
        ]
    }
    """
    try:
        data = request.get_json()
        request_json = decrypt_request(data)

        room_code = request_json.get('room_code')
        username = request_json.get('username')

        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if not room_doc.exists:
            error_response = {'error': 'Room not found'}
            return jsonify(encrypt_response(error_response, username)), 404

        room_data = room_doc.to_dict()
        group1 = room_data.get('group1', {})

        characters = []
        for username_in_group, character_name in group1.items():
            characters.append({
                "name": character_name,
                "username": username_in_group,
                "desc": "desc"
            })

        response_data = {"characters": characters}
        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        print(f"Error getting group1 data: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred getting group1 data."}), 500


# Gets all characters in group 2 for a room
@app.route('/get_group2', methods=['POST'])
def get_group2():
    """
    Get characters in group2 for a room.

    Request format:
    {
        "room_code": string,
        "username": string
    }

    Response:
    {
        "characters": [
            {
                "name": string,
                "username": string,
                "desc": string
            },
            ...
        ]
    }
    """
    try:
        data = request.get_json()
        request_json = decrypt_request(data)

        room_code = request_json.get('room_code')
        username = request_json.get('username')

        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if not room_doc.exists:
            error_response = {'error': 'Room not found'}
            return jsonify(encrypt_response(error_response, username)), 404

        room_data = room_doc.to_dict()
        group2 = room_data.get('group2', {})

        characters = []
        for username_in_group, character_name in group2.items():
            characters.append({
                "name": character_name,
                "username": username_in_group,
                "desc": "desc"
            })

        response_data = {"characters": characters}
        return jsonify(encrypt_response(response_data, username))

    except Exception as e:
        print(f"Error getting group2 data: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred getting group2 data."}), 500


#################################################
# GAMEPLAY ROUTES AND FUNCTIONS
#################################################

# Encrypts sensitive game state data before storage
def encrypt_game_state(game_state):
    """
    Encrypt sensitive game state data

    Args:
        game_state (dict): The game state data to encrypt

    Returns:
        dict: Encrypted game state with sensitive fields protected
    """
    if not game_state:
        return game_state

    encrypted = game_state.copy()

    # Encrypt character health data
    if "character_health" in encrypted:
        encrypted["character_health"] = db_encryption.encrypt_field(encrypted["character_health"])

    # Encrypt chat log if it contains sensitive information
    if "chat_log" in encrypted:
        encrypted["chat_log"] = db_encryption.encrypt_field(encrypted["chat_log"])

    return encrypted


# Decrypts game state data after retrieval from the database
def decrypt_game_state(encrypted_state):
    """
    Decrypt game state data

    Args:
        encrypted_state (dict): The encrypted game state data

    Returns:
        dict: Decrypted game state with readable fields
    """
    if not encrypted_state:
        return encrypted_state

    state = encrypted_state.copy()

    # Decrypt character health data
    if "character_health" in state:
        state["character_health"] = db_encryption.decrypt_field(state["character_health"])

    # Decrypt chat log
    if "chat_log" in state:
        state["chat_log"] = db_encryption.decrypt_field(state["chat_log"])

    return state


# Handles when the game has started socket event
@socketio.on('game_started')
def on_game_started(data):
    """
    Handle the game started event to initialize the first turn.

    Event data:
    {
        "room_code": string
    }

    Starts the first turn in the game.
    """
    try:
        request_json = decrypt_request(data)
        room_code = request_json.get('room_code')

        # Start the first turn
        start_first_turn(room_code)
    except Exception as e:
        print(f"Error starting game: {str(e)}")
        traceback.print_exc()


# Initializes the first turn of the game
def start_first_turn(room_code):
    """
    Start the first turn of the game.

    Args:
        room_code (str): The room code to start the game for

    Gets the current player from the turn order and starts their turn.
    """
    # Get current player from room data
    room_ref = db.collection('rooms').document(room_code)
    room_doc = room_ref.get()

    if not room_doc.exists:
        return

    room_data = room_doc.to_dict()
    if room_data.get('game_state', {}).get('status') != 'started':
        return

    current_turn = room_data['game_state']['turn']
    player_order = room_data['game_state']['player_order']

    if not player_order:
        return

    current_player = player_order[current_turn % len(player_order)]
    next_player = player_order[(current_turn + 1) % len(player_order)]

    # Start timer for this turn
    start_turn_timer(room_code, current_player, next_player)


# Starts the timer for a player's turn
def start_turn_timer(room_code, current_player, next_player):
    """
    Start a timer for the current player's turn.

    Args:
        room_code (str): The room code
        current_player (str): Username of the current player
        next_player (str): Username of the next player

    Sets up turn timer data and notifies all players in the room.
    """
    # Cancel any existing timer for this room
    if room_code in active_turn_timers:
        timer = active_turn_timers[room_code].get('timer')
        if timer:
            timer.cancel()

    # Set up timer data
    start_time = int(time.time())
    end_time = start_time + 60  # 60 seconds

    # Store timer data without scheduling an expiration timer
    active_turn_timers[room_code] = {
        'current_player': current_player,
        'next_player': next_player,
        'start_time': start_time,
        'end_time': end_time
    }

    # Notify all clients that turn has started
    if room_code in active_rooms:
        notification_data = {
            'current_player': current_player,
            'next_player': next_player,
            'start_time': start_time,
            'duration': 60,
            'event': 'turn_started'
        }

        for client in active_rooms[room_code]:
            client_username = client.get("username")
            sid = client.get("sid")

            if client_username and sid:
                encrypted_notification = encrypt_response(notification_data, client_username)
                socketio.emit('turn_started', encrypted_notification, to=sid)

    # No server-side timer setup - client will send skip_turn when timer expires


# Handles ability lookup during gameplay
@socketio.on('get_ability')
def on_get_ability(data):
    """
    Get ability details during gameplay.

    Event data:
    {
        "ability": string,
        "username": string
    }

    Returns ability details including type, description, and dice values.
    """
    try:
        request_json = decrypt_request(data)

        ability_name = request_json.get('ability')
        username = request_json.get('username')

        if not ability_name or not username:
            error_response = {'error': 'Missing ability name or username'}
            return encrypt_response(error_response, username)

        ability_docs = db.collection("ability").where('name', '==', ability_name).limit(1).get()

        if not ability_docs or len(ability_docs) == 0:
            error_response = {'error': 'Ability not found'}
            return encrypt_response(error_response, username)

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

        return encrypt_response(response_data, username)

    except Exception as e:
        print(f"Error getting ability: {str(e)}")
        traceback.print_exc()
        error_response = {'error': f'Error processing ability: {str(e)}'}
        return encrypt_response(error_response, username) if username else error_response


# Handles a player making a move during their turn
@socketio.on('make_move')
def on_make_move(data):
    """
    Process a player's turn action.

    Event data:
    {
        "username": string,
        "ability": string,
        "target": string,
        "type": string (a/h for attack/heal),
        "value": number,
        "room_code": string,
        "character": string
    }

    Updates health, chat log, and game state.
    Checks for game end conditions and advances to next turn.
    """
    try:
        request_json = decrypt_request(data)

        username = request_json.get('username')
        ability = request_json.get('ability')
        target = request_json.get('target')
        move_type = request_json.get('type')
        value = request_json.get('value')
        room_code = request_json.get('room_code')
        character = request_json.get('character')

        if not all([username, ability, target, move_type, room_code, character]):
            error_response = {'error': 'Missing required fields'}
            return encrypt_response(error_response, username)

            # Cancel the timer for this room
        if room_code in active_turn_timers:
            timer = active_turn_timers[room_code].get('timer')
            if timer:
                timer.cancel()

            # Get room data
        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if not room_doc.exists:
            error_response = {'error': 'Room not found'}
            return encrypt_response(error_response, username)

        room_data = room_doc.to_dict()

        # Decrypt any encrypted fields in room data
        if 'character_health' in room_data:
            room_data['character_health'] = db_encryption.decrypt_field(room_data['character_health'])

        if 'chat_log' in room_data:
            room_data['chat_log'] = db_encryption.decrypt_field(room_data['chat_log'])

        # Check if it's this player's turn
        current_turn = room_data['game_state']['turn']
        player_order = room_data['game_state']['player_order']

        if not player_order:
            error_response = {'error': 'No players in game order'}
            return encrypt_response(error_response, username)

        current_player = player_order[current_turn % len(player_order)]

        if current_player != username:
            error_response = {'error': 'Not your turn'}
            return encrypt_response(error_response, username)

        # Process the move effects
        # Get ability details
        ability_docs = db.collection("ability").where('name', '==', ability).limit(1).get()

        if not ability_docs or len(ability_docs) == 0:
            error_response = {'error': 'Ability not found'}
            return encrypt_response(error_response, username)

        ability_data = ability_docs[0].to_dict()
        chat_message = ability_data.get("chat", "")

        # Replace placeholders in chat message
        chat_message = chat_message.replace("[player1]", target)
        chat_message = chat_message.replace("[player2]", character)

        # Identify target player's group
        target_group = None

        for player_username, character_name in room_data.get('group1', {}).items():
            if character_name == target:
                target_group = 'group1'
                break

        if target_group is None:
            for player_username, character_name in room_data.get('group2', {}).items():
                if character_name == target:
                    target_group = 'group2'
                    break

        if target_group is None:
            error_response = {'error': 'Target not found'}
            return encrypt_response(error_response, username)

        # Update health in game state if not already there
        if 'character_health' not in room_data:
            room_data['character_health'] = {}

        if target not in room_data['character_health']:
            room_data['character_health'][target] = 50  # Default starting health

        # Apply effect based on move type
        current_health = room_data['character_health'][target]

        if move_type == 'a':  # Attack
            new_health = max(0, current_health - value)
            effect_message = f"{target} took {value} damage!"
        else:  # Heal
            new_health = min(50, current_health + value)  # Assume max health is 50
            effect_message = f"{target} healed for {value} health!"

        room_data['character_health'][target] = new_health

        # Add to chat log if not already there
        if 'chat_log' not in room_data:
            room_data['chat_log'] = []

        room_data['chat_log'].append({
            'message': chat_message,
            'effect': effect_message,
            'turn': current_turn
        })

        # Check if target died
        if new_health <= 0:
            room_data['chat_log'].append({
                'message': f"{target} has been defeated!",
                'turn': current_turn
            })

        # Check if game is over (all players in a group defeated)
        group1_all_dead = True
        group2_all_dead = True

        for character in room_data.get('character_health', {}):
            # Determine which group this character belongs to
            char_group = None
            for player_username, char_name in room_data.get('group1', {}).items():
                if char_name == character:
                    char_group = 'group1'
                    break

            if char_group is None:
                for player_username, char_name in room_data.get('group2', {}).items():
                    if char_name == character:
                        char_group = 'group2'
                        break

            health = room_data['character_health'][character]
            if health > 0:
                if char_group == 'group1':
                    group1_all_dead = False
                elif char_group == 'group2':
                    group2_all_dead = False

        # Check if round limit reached
        game_over = False
        winner = None

        if group1_all_dead:
            game_over = True
            winner = 'group2'
        elif group2_all_dead:
            game_over = True
            winner = 'group1'
        elif current_turn >= len(room_data['character_health']) * 15:  # Round limit (10 rounds)
            game_over = True
            # Determine winner based on total remaining health
            group1_health = 0
            group2_health = 0

            for character, health in room_data['character_health'].items():
                char_group = None
                for player_username, char_name in room_data.get('group1', {}).items():
                    if char_name == character:
                        char_group = 'group1'
                        break

                if char_group is None:
                    for player_username, char_name in room_data.get('group2', {}).items():
                        if char_name == character:
                            char_group = 'group2'
                            break

                if char_group == 'group1':
                    group1_health += health
                elif char_group == 'group2':
                    group2_health += health

            if group1_health > group2_health:
                winner = 'group1'
            elif group2_health > group1_health:
                winner = 'group2'
            else:
                winner = 'tie'

        if game_over:
            room_data['game_state']['status'] = 'ended'
            room_data['game_state']['winner'] = winner

            # Notify all players about game end
            end_notification = {
                'event': 'game_ended',
                'winner': winner
            }

            for client in active_rooms[room_code]:
                client_username = client.get("username")
                sid = client.get("sid")

                if client_username and sid:
                    encrypted_notification = encrypt_response(end_notification, client_username)
                    socketio.emit('game_ended', encrypted_notification, to=sid)

        # Save updated room data
        if 'character_health' in room_data:
            room_data['character_health'] = db_encryption.encrypt_field(room_data['character_health'])

        if 'chat_log' in room_data:
            room_data['chat_log'] = db_encryption.encrypt_field(room_data['chat_log'])

        room_ref.set(room_data)

        # If game not over, advance to next turn
        if not game_over:
            # Get the next players
            current_player, next_player = next_turn(room_code)

            # For response, we need to use decrypted values
            health_response = db_encryption.decrypt_field(room_data['character_health'])

            # Notify clients about move and effects with decrypted data for display
            move_notification = {
                'event': 'move_made',
                'username': username,
                'ability': ability,
                'target': target,
                'effect': effect_message,
                'chat': chat_message,
                'health': {target: health_response.get(target, 0)}
            }

            for client in active_rooms[room_code]:
                client_username = client.get("username")
                sid = client.get("sid")

                if client_username and sid:
                    encrypted_notification = encrypt_response(move_notification, client_username)
                    socketio.emit('move_made', encrypted_notification, to=sid)

            # Start the next turn's timer
            start_turn_timer(room_code, current_player, next_player)

        # Prepare response
        response_data = {'success': True}
        return encrypt_response(response_data, username)

    except Exception as e:
        print(f"Error making move: {str(e)}")
        traceback.print_exc()
        error_response = {'error': f'Error making move: {str(e)}'}
        return encrypt_response(error_response, username) if username else error_response


# Handles a player skipping their turn
@socketio.on('skip_turn')
def on_skip_turn(data):
    """
    Process a player skipping their turn.

    Event data:
    {
        "username": string,
        "room_code": string
    }

    Validates it's the player's turn, then advances to the next player.
    """
    try:
        request_json = decrypt_request(data)
        print("!!!!!!!!!!!!!")
        username = request_json.get('username')
        room_code = request_json.get('room_code')

        if not all([username, room_code]):
            error_response = {'error': 'Missing required fields'}
            return encrypt_response(error_response, username)

        # Cancel the timer for this room
        if room_code in active_turn_timers:
            timer = active_turn_timers[room_code].get('timer')
            if timer:
                timer.cancel()

        # Get room data
        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if not room_doc.exists:
            error_response = {'error': 'Room not found'}
            return encrypt_response(error_response, username)
        print("!!!!!!!!!!!!!")
        room_data = room_doc.to_dict()
        print("!!!!!!!!!!!!!")
        # Check if it's this player's turn
        current_turn = room_data['game_state']['turn']
        player_order = room_data['game_state']['player_order']

        if not player_order:
            error_response = {'error': 'No players in game order'}
            return encrypt_response(error_response, username)
        print("!!!!!!!!!!!!!")
        current_player = player_order[current_turn % len(player_order)]

        if current_player != username:
            error_response = {'error': 'Not your turn'}
            return encrypt_response(error_response, username)
        print("!!!!!!!!!!!!!")
        # Check if round limit reached
        game_over = False

        if current_turn >= 10:  # Round limit (10 rounds)
            game_over = True
            # Determine winner based on total remaining health
            group1_health = 0
            group2_health = 0

            for character, health in room_data['character_health'].items():
                char_group = None
                for player_username, char_name in room_data.get('group1', {}).items():
                    if char_name == character:
                        char_group = 'group1'
                        break

                if char_group is None:
                    for player_username, char_name in room_data.get('group2', {}).items():
                        if char_name == character:
                            char_group = 'group2'
                            break

                if char_group == 'group1':
                    group1_health += health
                elif char_group == 'group2':
                    group2_health += health

            if group1_health > group2_health:
                winner = 'group1'
            elif group2_health > group1_health:
                winner = 'group2'
            else:
                winner = 'tie'

            if game_over:
                room_data['game_state']['status'] = 'ended'
                room_data['game_state']['winner'] = winner

                # Notify all players about game end
                end_notification = {
                    'event': 'game_ended',
                    'winner': winner
                }

                for client in active_rooms[room_code]:
                    client_username = client.get("username")
                    sid = client.get("sid")

                    if client_username and sid:
                        encrypted_notification = encrypt_response(end_notification, client_username)
                        socketio.emit('game_ended', encrypted_notification, to=sid)
        print("!!!!!!!!!!!!!")

        # Save updated room data
        room_ref.set(room_data)

        # If game not over, advance to next turn
        if not game_over:
            # Get the next players
            current_player, next_player = next_turn(room_code)

            # Notify clients about move and effects
            move_notification = {
                'event': 'skip_made',
                'username': username,
            }

            for client in active_rooms[room_code]:
                client_username = client.get("username")
                sid = client.get("sid")

                if client_username and sid:
                    encrypted_notification = encrypt_response(move_notification, client_username)
                    socketio.emit('move_made', encrypted_notification, to=sid)

            # Start the next turn's timer
            start_turn_timer(room_code, current_player, next_player)

        # Prepare response
        response_data = {'success': True}
        print("!!!!!!!!!!!!!")
        return encrypt_response(response_data, username)

    except Exception as e:
        print(f"Error making move: {str(e)}")
        traceback.print_exc()
        error_response = {'error': f'Error making move: {str(e)}'}
        return encrypt_response(error_response, username) if username else error_response


# Advances to the next turn in the game
def next_turn(room_code):
    """
    Advance to the next turn in the game.

    Args:
        room_code (str): The room code

    Returns:
        tuple: (current_player, next_player) usernames after advancing the turn
    """
    room_ref = db.collection('rooms').document(room_code)
    room_doc = room_ref.get()

    if not room_doc.exists:
        return None, None

    room_data = room_doc.to_dict()

    if room_data['game_state']['status'] != 'started':
        return None, None

    room_data['game_state']['turn'] += 1
    room_ref.set(room_data)

    current_turn = room_data['game_state']['turn']
    player_order = room_data['game_state']['player_order']

    if not player_order:
        return None, None

    current_player = player_order[current_turn % len(player_order)]
    next_player = player_order[(current_turn + 1) % len(player_order)]

    return current_player, next_player


# Returns the current game state to a player
@socketio.on('get_game_state')
def on_get_game_state(data):
    """
    Get the current game state for a player.

    Event data:
    {
        "room_code": string,
        "username": string
    }

    Returns game state including turn data, health, chat log, etc.
    """
    try:
        request_json = decrypt_request(data)

        room_code = request_json.get('room_code')
        username = request_json.get('username')

        if not room_code or not username:
            error_response = {'error': 'Missing room code or username'}
            return encrypt_response(error_response, username)

        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if not room_doc.exists:
            error_response = {'error': 'Room not found'}
            return encrypt_response(error_response, username)

        room_data = room_doc.to_dict()

        # Decrypt any encrypted game state data
        if 'game_state' in room_data:
            room_data['game_state'] = decrypt_game_state(room_data['game_state'])

        if 'character_health' in room_data:
            room_data['character_health'] = db_encryption.decrypt_field(room_data['character_health'])

        if 'chat_log' in room_data:
            room_data['chat_log'] = db_encryption.decrypt_field(room_data['chat_log'])

        # Filter out just what we need for game state
        game_state = {
            'status': room_data.get('game_state', {}).get('status', 'not started'),
            'turn': room_data.get('game_state', {}).get('turn', 0),
            'current_player': None,
            'next_player': None,
            'character_health': room_data.get('character_health', {}),
            'chat_log': room_data.get('chat_log', [])[-10:],  # Last 10 messages only
            'group1': room_data.get('group1', {}),
            'group2': room_data.get('group2', {})
        }

        # Get current and next player
        if game_state['status'] == 'started':
            player_order = room_data.get('game_state', {}).get('player_order', [])
            if player_order:
                current_turn = game_state['turn']
                game_state['current_player'] = player_order[current_turn % len(player_order)]
                game_state['next_player'] = player_order[(current_turn + 1) % len(player_order)]

        response_data = {'game_state': game_state}
        return encrypt_response(response_data, username)

    except Exception as e:
        print(f"Error getting game state: {str(e)}")
        traceback.print_exc()
        error_response = {'error': f'Error getting game state: {str(e)}'}
        return encrypt_response(error_response, username) if username else error_response


# Handles a player reconnecting to an ongoing game
@socketio.on('reconnect_to_game')
def on_reconnect_to_game(data):
    """
    Handles a player reconnecting to an ongoing game

    Event data:
    {
        "username": string,
        "room_code": string
    }

    Cancels disconnect timer if active and syncs game state to the reconnected player.
    """
    try:
        request_json = decrypt_request(data)

        username = request_json.get('username')
        room_code = request_json.get('room_code')

        if not username or not room_code:
            print(f"Missing username or room_code in reconnect request")
            return

        # Check if this player's timer is active and cancel it
        timer_key = f"{username}_{room_code}"
        if timer_key in disconnection_timers:
            # Cancel the disconnect timer since they're reconnecting
            disconnection_timers[timer_key].cancel()
            del disconnection_timers[timer_key]
            print(f"Cancelled disconnect timer for {username} in reconnect_to_game")

        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if not room_doc.exists:
            print(f"Room {room_code} not found during reconnection")
            return

        room_data = room_doc.to_dict()
        game_state = room_data.get('game_state', {})

        # Check if the game is ongoing and the player is part of it
        status = game_state.get('status')
        player_order = game_state.get('player_order', [])

        if status not in ['started', 'validating']:
            print(f"Player {username} tried to reconnect to game {room_code} but game status is {status}")
            return

        # Check if player is in a group
        in_group = False
        for group_name in ['group1', 'group2']:
            if group_name in room_data and username in room_data[group_name]:
                in_group = True
                break

        if not in_group:
            print(f"Player {username} tried to reconnect but is not in any group")
            return

        # Player is valid, send them the current game state
        print(f"Player {username} reconnected to game {room_code}, syncing state")

        # Get the current turn info
        current_turn = game_state.get('turn', 0)

        if player_order:
            current_player = player_order[current_turn % len(player_order)]
            next_player = player_order[(current_turn + 1) % len(player_order)]
        else:
            current_player = ""
            next_player = ""

        # Get turn timer info if available
        turn_timer_info = {}
        if room_code in active_turn_timers:
            timer_data = active_turn_timers[room_code]
            turn_timer_info = {
                'start_time': timer_data.get('start_time', int(time.time())),
                'end_time': timer_data.get('end_time', int(time.time()) + 60),
                'duration': 60
            }

        # Prepare reconnection data with complete game state
        reconnection_data = {
            'event': 'reconnection_sync',
            'current_player': current_player,
            'next_player': next_player,
            'character_health': room_data.get('character_health', {}),
            'group1': room_data.get('group1', {}),
            'group2': room_data.get('group2', {}),
            'chat_log': room_data.get('chat_log', [])[-10:],  # Last 10 messages
            'turn_timer': turn_timer_info,
            'game_status': status,
            'turn': current_turn
        }

        # Find the player's socket and send the sync data
        player_sid = None
        if room_code in active_rooms:
            for client in active_rooms[room_code]:
                if client.get("username") == username:
                    player_sid = client.get("sid")
                    break

        if player_sid:
            encrypted_sync = encrypt_response(reconnection_data, username)
            socketio.emit('reconnection_sync', encrypted_sync, to=player_sid)
            print(f"Sent reconnection sync to {username}")
        else:
            print(f"Could not find socket for {username} to send reconnection sync")

    except Exception as e:
        print(f"Error in reconnect_to_game handler: {str(e)}")
        traceback.print_exc()


# Main application entry point
if __name__ == '__main__':
    socketio.run(app, host='0.0.0.0', port=8080, debug=True, allow_unsafe_werkzeug=True)