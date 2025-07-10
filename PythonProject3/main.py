"""
Application entry point for Fights in the Forest server.
"""

from config import app, socketio

import routes.auth_routes
import routes.character_routes
import routes.room_routes
import events.socket_handlers
import events.game_handlers

if __name__ == '__main__':
    socketio.run(app, host='0.0.0.0', port=8080, debug=True, use_reloader=False, allow_unsafe_werkzeug=True)