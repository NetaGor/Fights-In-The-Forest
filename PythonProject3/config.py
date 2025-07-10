"""
Configuration settings for the Fights in the Forest game server.
Sets up Flask app, SocketIO, CORS, Firebase connection and Maps for server-use.
"""

import os
import firebase_admin
from firebase_admin import credentials, firestore
from flask import Flask
from flask_socketio import SocketIO
from flask_cors import CORS

def initialize_firebase():
    """Sets up Firebase connection for database operations."""
    cred = credentials.Certificate("raw/fightsintheforest-firebase-adminsdk-fbsvc-c35c3cb72b.json")
    firebase_admin.initialize_app(cred)
    return firestore.client()

# Create Flask app with CORS support
app = Flask(__name__)
CORS(app, resources={
    r"/*": {
        "origins": ["http://127.0.0.1:8080"],
        "allow_headers": [
            "Content-Type",
            "Authorization"
        ],
        "supports_credentials": True
    }
})

# Configure SocketIO with settings for real-time game communication
socketio = SocketIO(
    app,
    cors_allowed_origins="http://127.0.0.1:8080",
    async_mode='eventlet',
    ping_timeout=25000,
    ping_interval=10000,
    logger=True,
    engineio_logger=True
)

# Initialize Firebase database connection
db = initialize_firebase()
print("Connected database")

active_rooms = {}            # Maps room codes to lists of connected clients
active_turn_timers = {}      # Maps room codes to turn timer data
disconnection_timers = {}    # Maps username_roomcode to disconnection timers
