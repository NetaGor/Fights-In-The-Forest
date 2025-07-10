"""
Socket event handlers for real-time communication.
"""

import threading
import time
import random
import traceback
from flask import request
from config import socketio, db, active_rooms, active_turn_timers, disconnection_timers
from security.encryption_utils import (encrypt_response, decrypt_request, encrypt_for_database, decrypt_from_database)
from events.game_handlers import next_turn, start_turn_timer


def check_all_ready(room_data, room_code):
    """Checks if all players are ready and starts the game if conditions are met."""
    # Ensure ready_players and players are in the data
    if 'ready_players' not in room_data or 'players' not in room_data:
        return

    # Ensure the player limitation
    if not room_data['players']:
        return
    if len(room_data['players']) < 2:
        return
    if len(room_data['group1']) < 1 or len(room_data['group2']) < 1:
        return
    if set(room_data['ready_players']) != set(room_data['players']):
        return

    # All conditions met, start the game
    room_ref = db.collection('rooms').document(room_code)
    room_data['game_state']['status'] = 'started'

    # Set player order
    player_list = list(room_data['players'])
    random.shuffle(player_list)
    room_data['game_state']['player_order'] = player_list
    room_data['game_state']['current_player'] = player_list[0]
    room_data['game_state']['next_player'] = player_list[1]


    # Make sure character_health is encrypted
    if 'character_health' in room_data:
        if not (isinstance(room_data['character_health'], dict) and room_data['character_health'].get("encrypted", False)):
            room_data['character_health'] = encrypt_for_database(room_data['character_health'])

    # Save the room data
    room_ref.set(room_data)

    # Emit game_started event to all clients in the room with encryption
    if room_code in active_rooms:
        notification_data = {'event': 'game_started'}
        for client in active_rooms[room_code]:
            client_username = client.get("username")
            sid = client.get("sid")
            if client_username and sid:
                encrypted_notification = encrypt_response(notification_data, client_username)
                socketio.emit('game_started', encrypted_notification, to=sid)


@socketio.on('connect')
def handle_connect():
    """Initializes client connection for real-time communication."""


@socketio.on('join_room')
def on_join(data):
    """Adds a player to a game room and notifies all players in the room."""
    try:
        request_json = decrypt_request(data)

        username = request_json.get('username')
        room_code = request_json.get('room_code')

        # Initialize room in active_rooms if it doesn't exist
        if room_code not in active_rooms:
            active_rooms[room_code] = []

        # Check if there's a pending disconnect timer for this user and cancel it as so
        timer_key = f"{username}_{room_code}"
        if timer_key in disconnection_timers:
            disconnection_timers[timer_key].cancel()
            del disconnection_timers[timer_key]

        # Add the user to the room/update their sid
        already_in_room = False
        for client in active_rooms[room_code]:
            if client.get("username") == username:
                client["sid"] = request.sid
                already_in_room = True
                break

        # Add if user not in room
        if not already_in_room:
            active_rooms[room_code].append({"sid": request.sid, "username": username})

        # Notify all users in the room with encryption
        for client in active_rooms[room_code]:
            sid = client.get("sid")
            client_username = client.get("username")
            if sid and client_username:
                notification_data = {
                    'username': username,
                    'room_code': room_code
                }
                encrypted_notification = encrypt_response(notification_data, client_username)
                socketio.emit('new_player', encrypted_notification, to=sid)

    except Exception:
        traceback.print_exc()


@socketio.on('join_group')
def on_join_group(data):
    """Assigns a player to a specific team group and updates their character selection."""
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

            # Decrypt character_health if it's encrypted
            character_health = room_data.get('character_health', {})
            if isinstance(character_health, dict) and character_health.get("encrypted", False):
                character_health = decrypt_from_database(character_health)
                room_data['character_health'] = character_health

            # Set the user's character health
            if 'character_health' not in room_data:
                room_data['character_health'] = {}
            if username not in room_data['character_health']:
                room_data['character_health'][username] = 50
            room_data['character_health'] = encrypt_for_database(room_data['character_health'])

            # Remove player from other group if they exist, and remember their character
            for g in ['group1', 'group2']:
                if g in room_data and username in room_data[g]:
                    del room_data[g][username]

            # Add player to the specified group
            room_data[group][username] = character_name
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
                        encrypted_notification = encrypt_response(notification_data, client_username)
                        socketio.emit('group_change', encrypted_notification, to=sid)

    except Exception:
        traceback.print_exc()


@socketio.on('press_ready')
def on_press_ready(data):
    """Marks a player as ready to start the game and checks if all players are ready to begin."""
    try:
        request_json = decrypt_request(data)

        username = request_json.get('username')
        room_code = request_json.get('room_code')

        # Update room in Firebase
        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if room_doc.exists:
            room_data = room_doc.to_dict()

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
                        encrypted_notification = encrypt_response(notification_data, client_username)
                        socketio.emit('player_ready', encrypted_notification, to=sid)

            # Check if game can start
            check_all_ready(room_data, room_code)

    except Exception:
        traceback.print_exc()


@socketio.on('unpress_ready')
def on_unpress_ready(data):
    """Removes a player's ready status and notifies all players in the room."""
    try:
        request_json = decrypt_request(data)

        username = request_json.get('username')
        room_code = request_json.get('room_code')

        # Update room in Firebase
        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if room_doc.exists:
            room_data = room_doc.to_dict()

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
                        encrypted_notification = encrypt_response(notification_data, client_username)
                        socketio.emit('player_unready', encrypted_notification, to=sid)

    except Exception:
        traceback.print_exc()


@socketio.on('disconnect')
def handle_disconnect():
    """Handles player disconnection by scheduling their removal after a timeout period."""
    # Find which player disconnected by looking up their sid
    sid = request.sid
    disconnected_username = None
    disconnected_room = None

    # Search through all rooms to find the disconnected user
    for room_code, clients in active_rooms.items():
        for client in clients:
            if client.get("sid") == sid:
                disconnected_username = client.get("username")
                disconnected_room = room_code
                active_rooms[room_code].remove(client)
                break
        if disconnected_username:
            break

    # If we found the disconnected user, schedule their removal from the room
    if disconnected_username and disconnected_room:
        try:
            # Cancel any existing removal timer for this user in this room
            timer_key = f"{disconnected_username}_{disconnected_room}"
            if timer_key in disconnection_timers:
                # Cancel existing timer
                existing_timer = disconnection_timers[timer_key]
                existing_timer.cancel()

            # Create a new timer to remove the player after 10 seconds
            def remove_player():
                try:
                    # Check if player reconnected (their username should be in active_rooms for this room)
                    if disconnected_room in active_rooms:
                        for client in active_rooms[disconnected_room]:
                            if client.get("username") == disconnected_username:
                                break

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
                                for group in ['group1', 'group2']:
                                    if group in room_data and disconnected_username in room_data[group]:
                                        del room_data[group][disconnected_username]
                                        room_changed = True

                                # Remove their character's health
                                if disconnected_username and 'character_health' in room_data:
                                    if disconnected_username in room_data['character_health']:
                                        del room_data['character_health'][disconnected_username]
                                if 'ready_players' in room_data and disconnected_username in room_data['ready_players']:
                                    room_data['ready_players'].remove(disconnected_username)
                                    room_changed = True
                                if 'game_state' in room_data and 'player_order' in room_data['game_state'] and disconnected_username in room_data['game_state']['player_order']:
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
                                            encrypted_notification = encrypt_response(notification_data, client_username)
                                            socketio.emit('update', encrypted_notification, to=client_sid)

                    # Clean up the timer reference
                    if timer_key in disconnection_timers:
                        del disconnection_timers[timer_key]

                except Exception :
                    traceback.print_exc()

            # Create and start the timer
            disconnect_timer = threading.Timer(10.0, remove_player)
            disconnect_timer.daemon = True
            disconnection_timers[timer_key] = disconnect_timer
            disconnect_timer.start()

        except Exception:
            traceback.print_exc()