"""
Encryption utilities for data handling in the game server.

Provides functions for:
- Encrypting/decrypting data
- Password hashing
- Public key management
- Secure database interactions
"""

import base64
import json
import bcrypt
import traceback
from Cryptodome.PublicKey import RSA
from security.hybrid_encryption import HybridEncryption
from config import db

# Initialize hybrid encryption
hybrid_encryption = HybridEncryption()

def load_key(file_path):
    """Loads an RSA key from a base64 encoded file."""
    with open(file_path, "r") as file:
        base64_key = file.read().strip()
        decoded_key = base64.b64decode(base64_key)
        return RSA.import_key(decoded_key)

# Load the server's keys
private_key = load_key("raw/private.txt")
public_key = load_key("raw/public.txt")


def get_public_key(username):
    """Retrieves a user's public key from the database for encryption."""
    try:
        if username is None:
            return None

        user_docs = db.collection("users").where('username', '==', username).get()

        if not user_docs or len(user_docs) == 0:
            return None

        user_doc = user_docs[0].to_dict()

        if 'public_key' not in user_doc or not user_doc['public_key']:
            return None

        return user_doc['public_key']

    except Exception:
        return None


def encrypt_response(response_data, username=None):
    """Encrypts response data using the user's public key or symmetric encryption as fallback."""
    if username:
        user_public_key = get_public_key(username)
        if user_public_key:
            try:
                return hybrid_encryption.encrypt_with_public_key(response_data, user_public_key)
            except Exception:
                raise

    return hybrid_encryption.encrypt_symmetric(response_data)


def decrypt_request(request_data):
    """Decrypts incoming request data using appropriate decryption method."""
    try:
        method = request_data.get("method", "")

        if method == "hybrid-rsa-aes":
            encrypted_key = request_data.get("encrypted_key")
            iv = request_data.get("iv")
            encrypted_data = request_data.get("data")

            if not all([encrypted_key, iv, encrypted_data]):
                raise

            decrypted_data = hybrid_encryption.decrypt_hybrid_request(
                encrypted_key, iv, encrypted_data, private_key
            )

            try:
                return json.loads(decrypted_data)
            except json.JSONDecodeError:
                return decrypted_data

        elif "data" in request_data and request_data.get("encrypted", True):
            return hybrid_encryption.decrypt_symmetric(request_data)

        return request_data

    except Exception:
        traceback.print_exc()


def encrypt_for_database(data):
    """Encrypts data before storing it in the database."""
    try:
        encrypted = hybrid_encryption.encrypt_symmetric(data)
        return {
            "encrypted": True,
            "data": encrypted["data"]
        }
    except Exception:
        traceback.print_exc()
        return data


def decrypt_from_database(encrypted_data):
    """Decrypts data retrieved from the database for processing."""
    try:
        if isinstance(encrypted_data, dict) and encrypted_data.get("encrypted", True):
            return hybrid_encryption.decrypt_symmetric(encrypted_data)
        return encrypted_data
    except Exception:
        traceback.print_exc()
        return encrypted_data


def hash_password(password):
    """Hashes a password using bcrypt."""
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()


def check_password(stored_hash, password):
    """Verifies a password against its stored hash."""
    return bcrypt.checkpw(password.encode(), stored_hash.encode())


def user_exists(username):
    """Checks if a username already exists in the database."""
    query = db.collection("users").where('username', '==', username).get()
    return len(query) > 0