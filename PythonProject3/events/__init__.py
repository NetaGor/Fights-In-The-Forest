"""
Events package initialization for socket and game event handlers.
"""

# Import all event handler modules to ensure they're registered
from . import socket_handlers
from . import game_handlers

__all__ = ['socket_handlers', 'game_handlers']