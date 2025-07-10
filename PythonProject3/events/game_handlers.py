"""
Game logic and event handlers for gameplay mechanics.
"""
import time
import traceback
from config import socketio, db, active_rooms, active_turn_timers, disconnection_timers
from security.encryption_utils import (encrypt_response, decrypt_request, encrypt_for_database, decrypt_from_database)


def start_first_turn(room_code):
    """Initiates the first turn for a new game using the room's configured player order."""
    room_ref = db.collection('rooms').document(room_code)
    room_doc = room_ref.get()
    room_data = room_doc.to_dict()

    current_turn = room_data['game_state']['turn']
    player_order = room_data['game_state']['player_order']

    # Select first active player and determine who goes next
    current_player = player_order[current_turn % len(player_order)]
    next_player = find_next_active_player(room_code, current_player, player_order)
    room_data['game_state']['current_player'] = current_player
    room_data['game_state']['next_player'] = next_player
    room_ref.set(room_data)

    # Start timer for the first player's turn
    start_turn_timer(room_code, current_player, next_player)


def find_next_active_player(room_code, current_player, player_order):
    """Locates the next player in sequence who hasn't been defeated yet."""
    if not player_order or len(player_order) <= 1:
        return ""

    room_ref = db.collection('rooms').document(room_code)
    room_doc = room_ref.get()
    room_data = room_doc.to_dict()

    # Get decrypted health data
    character_health = room_data.get('character_health', {})
    if isinstance(character_health, dict) and character_health.get("encrypted", False):
        character_health = decrypt_from_database(character_health)

    # Find current player's position
    try:
        current_index = player_order.index(current_player)
    except ValueError:
        current_index = -1

    # Check each player in order until we find an active one
    for i in range(1, len(player_order) + 1):
        next_index = (current_index + i) % len(player_order)
        next_player = player_order[next_index]
        if next_player in character_health:
            if character_health[next_player] > 0 and next_player in room_data['players']:
                return next_player


def start_turn_timer(room_code, current_player, next_player):
    """Sets up a 60-second timer for the current player's turn and notifies all players."""
    # Cancel any existing timer for this room
    if room_code in active_turn_timers:
        timer = active_turn_timers[room_code].get('timer')
        if timer:
            timer.cancel()

    # Set up timer data
    start_time = int(time.time())
    end_time = start_time + 60  # 60 seconds

    # Store timer data
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
    """Advances the game to the next player's turn, ensuring we skip defeated players."""
    room_ref = db.collection('rooms').document(room_code)
    room_doc = room_ref.get()
    room_data = room_doc.to_dict()

    if room_data['game_state']['status'] != 'started':
        return None, None

    # Update turn number
    room_data['game_state']['turn'] += 1
    current_turn = room_data['game_state']['turn']

    # Get player order
    player_order = room_data['game_state']['player_order']

    # The current player should be the next_player from previous turn
    if 'next_player' in room_data['game_state']:
        current_player = room_data['game_state']['next_player']
    else:
        current_player = player_order[current_turn % len(player_order)]

    # Find next active player
    next_player = find_next_active_player(room_code, current_player, player_order)

    # Update current_player and next_player in game state
    room_data['game_state']['current_player'] = current_player
    room_data['game_state']['next_player'] = next_player

    # Save the updated player order and player references
    room_ref.set(room_data)

    return current_player, next_player


@socketio.on('game_started')
def on_game_started(data):
    """Handles game initialization when all players are ready to start."""
    try:
        request_json = decrypt_request(data)
        room_code = request_json.get('room_code')

        # Start the first turn
        start_first_turn(room_code)
    except Exception:
        traceback.print_exc()


@socketio.on('get_ability')
def on_get_ability(data):
    """Fetches ability details for a player, including type, description, and dice values."""
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

    except Exception:
        traceback.print_exc()
        error_response = {'error': 'Error processing ability'}
        return encrypt_response(error_response, username) if username else error_response


@socketio.on('make_move')
def on_make_move(data):
    """Processes a player's combat action, updates health values, and advances to the next turn."""
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

        # Decrypt character_health if it's encrypted
        character_health = room_data.get('character_health', {})
        if isinstance(character_health, dict) and character_health.get("encrypted", False):
            character_health = decrypt_from_database(character_health)
            room_data['character_health'] = character_health

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

        # Identify target player's group and username
        target_group = None

        for player_username, character_name in room_data.get('group1', {}).items():
            if player_username == target_player:
                target_group = 'group1'
                break

        if target_group is None:
            for player_username, character_name in room_data.get('group2', {}).items():
                if player_username == target_player:
                    target_group = 'group2'
                    break

        if target_group is None:
            error_response = {'error': 'Target not found'}
            return encrypt_response(error_response, username)

        # Update health in game state if not already there
        if 'character_health' not in room_data:
            room_data['character_health'] = {}

        if target_player not in room_data['character_health']:
            room_data['character_health'][target_player] = 50

        # Apply effect based on move type
        current_health = room_data['character_health'][target_player]

        if move_type == 'a':  # Attack
            new_health = max(0, current_health - value)
            effect_message = f"{target} took {value} damage!"
        else:  # Heal
            new_health = min(50, current_health + value)
            effect_message = f"{target} healed for {value} health!"

        room_data['character_health'][target_player] = new_health

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
            if target_player and target_player in room_data['game_state']['player_order']:
                if target_player in room_data['game_state']['next_player']:
                    room_data['game_state']['next_player'] = find_next_active_player(room_code, target_player, room_data['game_state']['player_order'])
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
        elif current_turn >= len(room_data['character_health']) * 15:
            game_over = True
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

        # Encrypt character_health before storing
        room_data['character_health'] = encrypt_for_database(room_data['character_health'])

        # Save updated room data
        room_ref.set(room_data)

        # If game not over, advance to next turn
        if not game_over:
            current_player, next_player = next_turn(room_code)
            move_notification = {
                'event': 'move_made',
                'username': username,
                'ability': ability,
                'target': target,
                'effect': effect_message,
                'chat': chat_message,
                'health': {
                    target_player: new_health,
                    'character_name': target
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

    except Exception:
        traceback.print_exc()
        error_response = {'error': 'Error making move: {str(e)}'}
        return encrypt_response(error_response, username) if username else error_response


@socketio.on('skip_turn')
def on_skip_turn(data):
    """Allows a player to skip their turn without making any moves."""
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

        if current_turn >= len(room_data['character_health']) * 15:
            game_over = True
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

                # Notify all players about game end with encryption
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

    except Exception:
        traceback.print_exc()
        error_response = {'error': 'Error skipping turn'}
        return encrypt_response(error_response, username) if username else error_response


@socketio.on('get_game_state')
def on_get_game_state(data):
    """Retrieves the current game state for a player, including health values and chat history."""
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
                    game_state['next_player'] = find_next_active_player(room_code, game_state['current_player'],
                                                                        player_order)

        response_data = {'game_state': game_state}
        return encrypt_response(response_data, username)

    except Exception:
        traceback.print_exc()
        error_response = {'error': 'Error getting game state'}
        return encrypt_response(error_response, username) if username else error_response


@socketio.on('reconnect_to_game')
def on_reconnect_to_game(data):
    """Handles player reconnection by syncing game state and canceling any disconnection timers."""
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
            # Get the last 20 messages
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

        # Find the player's socket and send the sync data with encryption
        if room_code in active_rooms:
            for client in active_rooms[room_code]:
                if client.get("username") == username:
                    encrypted_sync = encrypt_response(reconnection_data, username)
                    socketio.emit('reconnection_sync', encrypted_sync, to=client.get("sid"))
                    break

    except Exception:
        traceback.print_exc()