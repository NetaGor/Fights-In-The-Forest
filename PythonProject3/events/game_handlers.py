"""
Game logic and event handlers for gameplay mechanics with improved player defeat handling.
"""

import time
import traceback
from config import socketio, db, active_rooms, active_turn_timers, disconnection_timers
from security.encryption_utils import (
    encrypt_response, decrypt_request, encrypt_for_database, decrypt_from_database
)


def start_first_turn(room_code):
    """Starts the first turn for a game using the room's player order."""
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

    # Get the first active player
    current_player = player_order[current_turn % len(player_order)]
    next_player = find_next_active_player(room_code, current_player, player_order)

    # Update room data with current and next player
    room_data['game_state']['current_player'] = current_player
    room_data['game_state']['next_player'] = next_player
    room_ref.set(room_data)

    # Start timer for this turn
    start_turn_timer(room_code, current_player, next_player)


def find_next_active_player(room_code, current_player, player_order):
    """
    Find the next active player in the order, skipping defeated players.
    Returns the username of the next active player.
    """
    if not player_order or len(player_order) <= 1:
        return ""

    # Get room data to check player health
    room_ref = db.collection('rooms').document(room_code)
    room_doc = room_ref.get()

    if not room_doc.exists:
        return player_order[0]  # Fallback to first player

    room_data = room_doc.to_dict()

    # Decrypt character health if it's encrypted
    character_health = room_data.get('character_health', {})
    if isinstance(character_health, dict) and character_health.get("encrypted", False):
        character_health = decrypt_from_database(character_health)

    # Get the index of the current player
    try:
        current_index = player_order.index(current_player)
    except ValueError:
        # Current player not in list, start from beginning
        current_index = -1

    # Check each player in order starting from the next one
    for i in range(1, len(player_order) + 1):
        next_index = (current_index + i) % len(player_order)
        next_player = player_order[next_index]

        if next_player and next_player in character_health:
            if character_health[next_player] > 0:
                return next_player

    # If no active players found (shouldn't happen in normal gameplay)
    return player_order[(current_index + 1) % len(player_order)]


def start_turn_timer(room_code, current_player, next_player):
    """Starts a 60-second timer for the current player's turn."""
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


def next_turn(room_code):
    """Advances game to next turn and returns the current and next players."""
    room_ref = db.collection('rooms').document(room_code)
    room_doc = room_ref.get()

    if not room_doc.exists:
        return None, None

    room_data = room_doc.to_dict()

    if room_data['game_state']['status'] != 'started':
        return None, None

    # Update turn number
    room_data['game_state']['turn'] += 1
    current_turn = room_data['game_state']['turn']

    # Get player order
    player_order = room_data['game_state']['player_order']

    if not player_order:
        return None, None

    # Filter out defeated players from player_order
    updated_player_order = []

    # Decrypt character_health if needed
    character_health = room_data.get('character_health', {})
    if isinstance(character_health, dict) and character_health.get("encrypted", False):
        character_health = decrypt_from_database(character_health)

    for player in player_order:
        # Keep player if character is still alive
        if player and player in character_health:
            if character_health[player] > 0:
                updated_player_order.append(player)

    # Use the updated player order
    room_data['game_state']['player_order'] = updated_player_order

    # If no players left, end the game (should be handled elsewhere)
    if not updated_player_order:
        return None, None

    # The current player should be the next_player from previous turn
    if 'next_player' in room_data['game_state']:
        # Use the previously calculated next player as current player
        current_player = room_data['game_state']['next_player']
    else:
        # Fallback if next_player wasn't set
        current_player = updated_player_order[current_turn % len(updated_player_order)]

    # Find next active player
    next_player = find_next_active_player(room_code, current_player, updated_player_order)

    # Update current_player and next_player in game state
    room_data['game_state']['current_player'] = current_player
    room_data['game_state']['next_player'] = next_player

    # Encrypt any sensitive data before saving
    if 'character_health' in room_data:
        if not (isinstance(room_data['character_health'], dict) and room_data['character_health'].get("encrypted", False)):
            room_data['character_health'] = encrypt_for_database(room_data['character_health'])

    # Save the updated player order and player references
    room_ref.set(room_data)

    return current_player, next_player


@socketio.on('game_started')
def on_game_started(data):
    """Handles the game started event and initializes the first turn."""
    try:
        request_json = decrypt_request(data)
        room_code = request_json.get('room_code')

        # Start the first turn
        start_first_turn(room_code)
    except Exception as e:
        traceback.print_exc()


@socketio.on('get_ability')
def on_get_ability(data):
    """Returns ability details including type, description, and dice values."""
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
        traceback.print_exc()
        error_response = {'error': f'Error processing ability: {str(e)}'}
        return encrypt_response(error_response, username) if username else error_response


@socketio.on('make_move')
def on_make_move(data):
    """Processes a player's turn action and updates game state."""
    try:
        request_json = decrypt_request(data)

        username = request_json.get('username')
        ability = request_json.get('ability')
        target_player = request_json.get('target_user')
        target = request_json.get('target_name')
        move_type = request_json.get('type')
        value = request_json.get('value')
        room_code = request_json.get('room_code')
        character = request_json.get('character')
        print(target_player)
        print("1")
        if not all([username, ability, target, move_type, room_code, character]):
            error_response = {'error': 'Missing required fields'}
            return encrypt_response(error_response, username)

        # Cancel the timer for this room
        if room_code in active_turn_timers:
            timer = active_turn_timers[room_code].get('timer')
            if timer:
                timer.cancel()
        print("2")
        print(target_player)
        # Get room data
        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if not room_doc.exists:
            error_response = {'error': 'Room not found'}
            return encrypt_response(error_response, username)

        room_data = room_doc.to_dict()
        print("3")
        # Decrypt character_health if it's encrypted
        character_health = room_data.get('character_health', {})
        if isinstance(character_health, dict) and character_health.get("encrypted", False):
            character_health = decrypt_from_database(character_health)
            room_data['character_health'] = character_health

        print(target_player)
        # Check if it's this player's turn
        current_turn = room_data['game_state']['turn']
        player_order = room_data['game_state']['player_order']
        print("4")
        if not player_order:
            error_response = {'error': 'No players in game order'}
            return encrypt_response(error_response, username)

        if 'current_player' in room_data['game_state']:
            current_player = room_data['game_state']['current_player']
        else:
            current_player = player_order[current_turn % len(player_order)]

        if current_player != username:
            error_response = {'error': 'Not your turn'}
            return encrypt_response(error_response, username)

        # Process the move effects
        # Get ability details
        ability_docs = db.collection("ability").where('name', '==', ability).limit(1).get()
        print(target_player)
        if not ability_docs or len(ability_docs) == 0:
            error_response = {'error': 'Ability not found'}
            return encrypt_response(error_response, username)

        ability_data = ability_docs[0].to_dict()
        chat_message = ability_data.get("chat", "")
        print("5")

        # Replace placeholders in chat message
        chat_message = chat_message.replace("[player1]", target)
        chat_message = chat_message.replace("[player2]", character)

        # Identify target player's group and username
        target_group = None
        print(target_player)
        for player_username, character_name in room_data.get('group1', {}).items():
            print()
            if player_username == target_player:
                target_group = 'group1'
                break

        if target_group is None:
            for player_username, character_name in room_data.get('group2', {}).items():
                if player_username == target_player:
                    target_group = 'group2'
                    break

        if target_group is None:
            print("group error")
            error_response = {'error': 'Target not found'}
            return encrypt_response(error_response, username)
        print("6")

        # Update health in game state if not already there
        if 'character_health' not in room_data:
            room_data['character_health'] = {}

        if target_player not in room_data['character_health']:
            room_data['character_health'][target_player] = 50  # Default starting health

        # Apply effect based on move type
        current_health = room_data['character_health'][target_player]

        if move_type == 'a':  # Attack
            new_health = max(0, current_health - value)
            effect_message = f"{target} took {value} damage!"
        else:  # Heal
            new_health = min(50, current_health + value)  # Assume max health is 50
            effect_message = f"{target} healed for {value} health!"

        room_data['character_health'][target_player] = new_health
        print("7")

        # Add to chat log if not already there
        if 'chat_log' not in room_data:
            room_data['chat_log'] = []

        # Create chat entry and always encrypt it
        chat_entry = {
            'message': chat_message,
            'effect': effect_message,
            'turn': current_turn
        }

        # Always encrypt the chat entry before storing
        encrypted_chat_entry = encrypt_for_database(chat_entry)
        room_data['chat_log'].append(encrypted_chat_entry)

        if new_health <= 0:
            # Remove the defeated player from turn order if they're in it
            if target_player and target_player in room_data['game_state']['player_order']:
                if target_player in room_data['game_state']['next_player']:
                    room_data['game_state']['next_player'] = find_next_active_player(room_code, current_player, room_data['game_state']['player_order'])
                room_data['game_state']['player_order'].remove(target_player)


        # Check if game is over (all players in a group defeated)
        group1_all_dead = True
        group2_all_dead = True

        for player_username in room_data.get('group1', {}):
            if player_username in room_data['character_health'] and room_data['character_health'][player_username] > 0:
                group1_all_dead = False
                break

        for player_username in room_data.get('group2', {}):
            if player_username in room_data['character_health'] and room_data['character_health'][player_username] > 0:
                group2_all_dead = False
                break


        # Check if round limit reached
        game_over = False
        winner = None

        if group1_all_dead:
            game_over = True
            winner = 'group2'
        elif group2_all_dead:
            game_over = True
            winner = 'group1'
        elif current_turn >= len(room_data['character_health']) * 15:  # Round limit (15 rounds)
            game_over = True
            # Determine winner based on total remaining health
            group1_health = 0
            group2_health = 0

            for player_username, health in room_data['character_health'].items():
                if player_username in room_data.get('group1', {}):
                    group1_health += health
                elif player_username in room_data.get('group2', {}):
                    group2_health += health

            if group1_health > group2_health:
                winner = 'group1'
            elif group2_health > group1_health:
                winner = 'group2'
            else:
                winner = 'tie'

        if game_over:
            # Create and encrypt game end message
            end_message = {
                'message': f"Game over! Winner: {winner}",
                'turn': current_turn
            }
            encrypted_end_message = encrypt_for_database(end_message)
            room_data['chat_log'].append(encrypted_end_message)

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

        print(room_data['character_health'])
        # Encrypt character_health before storing
        room_data['character_health'] = encrypt_for_database(room_data['character_health'])

        # Save updated room data
        room_ref.set(room_data)

        # If game not over, advance to next turn
        if not game_over:
            # Get the next players
            current_player, next_player = next_turn(room_code)

            # Notify clients about move and effects
            move_notification = {
                'event': 'move_made',
                'username': username,
                'ability': ability,
                'target': target,
                'effect': effect_message,
                'chat': chat_message,
                'health': {
                    target_player: new_health,  # Send username as key instead of character name
                    'character_name': target    # Include target character name for client-side lookup
                },
                'current_player': current_player,
                'next_player': next_player
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
        traceback.print_exc()
        error_response = {'error': f'Error making move: {str(e)}'}
        return encrypt_response(error_response, username) if username else error_response


@socketio.on('skip_turn')
def on_skip_turn(data):
    """Processes a player skipping their turn and advances to the next player."""
    try:
        request_json = decrypt_request(data)
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

        room_data = room_doc.to_dict()

        # Decrypt character_health if it's encrypted
        character_health = room_data.get('character_health', {})
        if isinstance(character_health, dict) and character_health.get("encrypted", False):
            character_health = decrypt_from_database(character_health)
            room_data['character_health'] = character_health

        # Decrypt chat_log if it exists and is encrypted
        if 'chat_log' in room_data:
            chat_log = []
            for entry in room_data['chat_log']:
                if isinstance(entry, dict) and entry.get("encrypted", False):
                    decrypted_entry = decrypt_from_database(entry)
                    chat_log.append(decrypted_entry)
                else:
                    chat_log.append(entry)
            room_data['chat_log'] = chat_log

        # Check if it's this player's turn
        current_turn = room_data['game_state']['turn']
        player_order = room_data['game_state']['player_order']

        if not player_order:
            error_response = {'error': 'No players in game order'}
            return encrypt_response(error_response, username)

        if 'current_player' in room_data['game_state']:
            current_player = room_data['game_state']['current_player']
        else:
            current_player = player_order[current_turn % len(player_order)]

        if current_player != username:
            error_response = {'error': 'Not your turn'}
            return encrypt_response(error_response, username)

        # Add skip entry to chat log
        if 'chat_log' not in room_data:
            room_data['chat_log'] = []

        skip_entry = {
            'message': f"{username} skipped their turn",
            'turn': current_turn
        }

        # Encrypt the skip entry
        encrypted_skip_entry = encrypt_for_database(skip_entry)
        room_data['chat_log'].append(encrypted_skip_entry)

        # Check for round limit/game end condition
        game_over = False
        winner = None

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
                # Add encrypted game end message
                end_message = {
                    'message': f"Game over! Winner: {winner}",
                    'turn': current_turn
                }
                encrypted_end_message = encrypt_for_database(end_message)
                room_data['chat_log'].append(encrypted_end_message)

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

        # Encrypt character health before saving
        room_data['character_health'] = encrypt_for_database(room_data['character_health'])

        # Save updated room data
        room_ref.set(room_data)

        # If game not over, advance to next turn
        if not game_over:
            # Get the next players
            current_player, next_player = next_turn(room_code)

            # Notify clients about skip
            skip_notification = {
                'event': 'skip_made',
                'username': username,
                'current_player': current_player,
                'next_player': next_player
            }

            for client in active_rooms[room_code]:
                client_username = client.get("username")
                sid = client.get("sid")

                if client_username and sid:
                    encrypted_notification = encrypt_response(skip_notification, client_username)
                    socketio.emit('skip_made', encrypted_notification, to=sid)

            # Start the next turn's timer
            start_turn_timer(room_code, current_player, next_player)

        # Prepare response
        response_data = {'success': True}
        return encrypt_response(response_data, username)

    except Exception as e:
        traceback.print_exc()
        error_response = {'error': f'Error skipping turn: {str(e)}'}
        return encrypt_response(error_response, username) if username else error_response


@socketio.on('get_game_state')
def on_get_game_state(data):
    """Returns the current game state with decrypted sensitive data."""
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

        # Decrypt character_health if needed
        character_health = room_data.get('character_health', {})
        if isinstance(character_health, dict) and character_health.get("encrypted", False):
            character_health = decrypt_from_database(character_health)
        else:
            character_health = room_data.get('character_health', {})

        # Get the chat log and decrypt it if needed
        chat_log = []
        if 'chat_log' in room_data and room_data['chat_log']:
            # Get the last 10 messages
            recent_chat = room_data['chat_log'][-10:]
            for chat_entry in recent_chat:
                if isinstance(chat_entry, dict) and chat_entry.get("encrypted", False):
                    decrypted_entry = decrypt_from_database(chat_entry)
                    chat_log.append(decrypted_entry)
                else:
                    chat_log.append(chat_entry)

        # Filter out just what we need for game state
        game_state = {
            'status': room_data.get('game_state', {}).get('status', 'not started'),
            'turn': room_data.get('game_state', {}).get('turn', 0),
            'current_player': None,
            'next_player': None,
            'character_health': character_health,
            'chat_log': chat_log,
            'group1': room_data.get('group1', {}),
            'group2': room_data.get('group2', {})
        }

        # Get current and next player
        if game_state['status'] == 'started':
            game_state['current_player'] = room_data.get('game_state', {}).get('current_player')
            game_state['next_player'] = room_data.get('game_state', {}).get('next_player')

            # If current_player or next_player are not set, calculate them
            if not game_state['current_player'] or not game_state['next_player']:
                player_order = room_data.get('game_state', {}).get('player_order', [])
                if player_order:
                    current_turn = game_state['turn']
                    current_index = current_turn % len(player_order)
                    game_state['current_player'] = player_order[current_index]

                    # Find next active player
                    game_state['next_player'] = find_next_active_player(room_code, game_state['current_player'], player_order)

        response_data = {'game_state': game_state}
        return encrypt_response(response_data, username)

    except Exception as e:
        traceback.print_exc()
        error_response = {'error': f'Error getting game state: {str(e)}'}
        return encrypt_response(error_response, username) if username else error_response


@socketio.on('reconnect_to_game')
def on_reconnect_to_game(data):
    """Handles player reconnection to an ongoing game and syncs game state."""
    try:
        request_json = decrypt_request(data)

        username = request_json.get('username')
        room_code = request_json.get('room_code')

        if not username or not room_code:
            return

        # Check if this player's timer is active and cancel it
        timer_key = f"{username}_{room_code}"
        if timer_key in disconnection_timers:
            disconnection_timers[timer_key].cancel()
            del disconnection_timers[timer_key]

        room_ref = db.collection('rooms').document(room_code)
        room_doc = room_ref.get()

        if not room_doc.exists:
            return

        room_data = room_doc.to_dict()
        game_state = room_data.get('game_state', {})

        status = game_state.get('status')
        player_order = game_state.get('player_order', [])

        if status != 'started':
            return

        in_group = False
        for group_name in ['group1', 'group2']:
            if group_name in room_data and username in room_data[group_name]:
                in_group = True
                break

        if not in_group:
            return

        current_turn = game_state.get('turn', 0)

        current_player = game_state.get('current_player', '')
        next_player = game_state.get('next_player', '')

        if not current_player or not next_player:
            if player_order:
                current_player = player_order[current_turn % len(player_order)]
                next_player = find_next_active_player(room_code, current_player, player_order)
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

        # Decrypt character_health if needed
        character_health = room_data.get('character_health', {})
        if isinstance(character_health, dict) and character_health.get("encrypted", False):
            character_health = decrypt_from_database(character_health)
        else:
            character_health = room_data.get('character_health', {})

        # Decrypt chat log for reconnection sync
        chat_log = []
        if 'chat_log' in room_data and room_data['chat_log']:
            # Get the last 10 messages
            recent_chat = room_data['chat_log'][-20:]
            for chat_entry in recent_chat:
                if isinstance(chat_entry, dict) and chat_entry.get("encrypted", False):
                    decrypted_entry = decrypt_from_database(chat_entry)
                    chat_log.append(decrypted_entry)
                else:
                    chat_log.append(chat_entry)

        # Prepare reconnection data with complete game state
        reconnection_data = {
            'event': 'reconnection_sync',
            'current_player': current_player,
            'next_player': next_player,
            'character_health': character_health,
            'group1': room_data.get('group1', {}),
            'group2': room_data.get('group2', {}),
            'chat_log': chat_log,
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

    except Exception as e:
        traceback.print_exc()