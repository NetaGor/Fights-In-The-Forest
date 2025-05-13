# routes/__init__.py
"""
Routes package initialization.
"""

# Import all route modules to ensure they're registered
from . import auth_routes
from . import character_routes
from . import room_routes

__all__ = ['auth_routes', 'character_routes', 'room_routes']