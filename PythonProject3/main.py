"""
Main application entry point for the Fights in the Forest game server.
"""

from config import app, socketio

# Import all routes and event handlers
import routes.auth_routes
import routes.character_routes
import routes.room_routes
import events.socket_handlers
import events.game_handlers

if __name__ == '__main__':
    socketio.run(app, host='0.0.0.0', port=8080, debug=True, use_reloader=False, allow_unsafe_werkzeug=True)