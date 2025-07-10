# routes/room_routes.py
"""
Routes for room management and player interactions.

Handles:
- Room creation and joining
- Player group management
- Room-related operations with secure encryption
"""

import traceback
import random
from flask import request, jsonify
from config import app, db, active_rooms
from security.encryption_utils import (encrypt_response, decrypt_request, encrypt_for_database)
from routes.character_routes import get_characters_func


def remove_player_from_rooms(username):
    """Remove player from all existing rooms."""
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
                        from config import socketio
                        socketio.emit('update', encrypted_notification, to=sid)


def generate_room_code():
    """Generate a unique 4-digit room code."""
    while True:
        room_code = str(random.randint(1000, 9999))
        room_ref = db.collection('rooms').document(room_code)
        if not room_ref.get().exists:
            return room_code


@app.route('/join_room_route', methods=['POST'])
def join_room_route():
    """Join an existing room."""
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

    except Exception:
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred joining room."}), 500


@app.route('/create_room', methods=['POST'])
def create_room():
    """Create a new game room with a unique code."""
    try:
        data = request.get_json()
        request_json = decrypt_request(data)

        username = request_json.get('username')

        # Remove player from any existing rooms
        remove_player_from_rooms(username)

        # Generate unique room code
        room_code = generate_room_code()

        # Encrypt character_health for initial storage
        character_health = encrypt_for_database({})

        # Initialize room structure
        room_ref = db.collection('rooms').document(room_code)
        room_ref.set({
            'code': room_code,
            'players': [username],
            'group1': {},
            'group2': {},
            'ready_players': [],
            'character_health': character_health,
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

    except Exception:
        traceback.print_exc()
        return jsonify({"status": "error", "message": "Error creating room"}), 500


@app.route('/remove_player_from_room', methods=['POST'])
def remove_player_from_room():
    """Remove a player from a specific room."""
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
                        from config import socketio
                        socketio.emit('update', encrypted_notification, to=sid)

        response_data = {'message': f'Player {username} removed from room {room_code}'}
        return jsonify(encrypt_response(response_data, username))

    except Exception:
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred removing player from room."}), 500


@app.route('/get_group1', methods=['POST'])
def get_group1():
    """Retrieve characters in group1 for a room."""
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

    except Exception:
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred getting group1 data."}), 500


@app.route('/get_group2', methods=['POST'])
def get_group2():
    """Retrieve characters in group2 for a room."""
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

    except Exception:
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred getting group2 data."}), 500