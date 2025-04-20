"""
Server-Database Encryption with ChaCha20-Poly1305

This module handles secure encryption for data stored in the Firebase database.
The encryption is handled entirely by the server with no client involvement.
Only the server needs the encryption key, which is stored in environment variables.

Features:
- ChaCha20-Poly1305 authenticated encryption
- Transparent encryption/decryption of sensitive fields
- Key management on server side only
- Performance optimized for game data
- Data integrity verification
"""

import os
import json
import base64
import hashlib
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes


class ServerDBEncryption:
    """
    Handles encryption between server and database using ChaCha20-Poly1305
    """

    def __init__(self, encryption_key=None, salt=None):
        """
        Initialize encryption with server-side key

        Args:
            encryption_key (str/bytes): Server's encryption key, or read from environment
            salt (bytes): Optional salt for key derivation
        """
        # Get key from parameter or environment variable
        if not encryption_key:
            encryption_key = os.environ.get('SERVER_DB_KEY')
            if not encryption_key:
                # Generate a temporary key with warning
                encryption_key = os.urandom(32).hex()
                print("\033[91mWARNING: No encryption key provided or found in SERVER_DB_KEY\033[0m")
                print(f"Generated temporary key: {encryption_key}")
                print("This key will be lost when the server restarts!")
                os.environ['SERVER_DB_KEY'] = encryption_key

        # Convert string key to bytes if needed
        if isinstance(encryption_key, str):
            self.master_key = encryption_key.encode('utf-8')
        else:
            self.master_key = encryption_key

        # Set salt
        if salt:
            self.salt = salt
        else:
            # Fixed salt for consistent key derivation across server restarts
            self.salt = b'fights_forest_server_db_salt_v1'

        # Derive encryption key
        self._derive_key()

        # Cache for already encrypted/decrypted values to improve performance
        self._cache = {}
        self._cache_size = 1000  # Maximum cache entries

    def _derive_key(self):
        """Derive encryption key using PBKDF2"""
        kdf = PBKDF2HMAC(
            algorithm=hashes.SHA256(),
            length=32,  # 256 bits for ChaCha20
            salt=self.salt,
            iterations=100000,
        )
        self.key = kdf.derive(self.master_key)

    def _cache_key(self, data):
        """Generate a cache key for the data"""
        if isinstance(data, str) and len(data) < 1000:
            return hashlib.sha256(data.encode('utf-8')).hexdigest()
        return None  # Don't cache large or non-string data

    def encrypt(self, data):
        """
        Encrypt data for database storage

        Args:
            data: The data to encrypt (string, dict, list, etc.)

        Returns:
            str: JSON string with encrypted data and metadata
        """
        # Skip encryption for None values
        if data is None:
            return None

        # Check cache first
        cache_key = self._cache_key(data if isinstance(data, str) else json.dumps(data))
        if cache_key and cache_key in self._cache.get('encrypt', {}):
            return self._cache['encrypt'][cache_key]

        try:
            # Store original type information
            data_type = type(data).__name__

            # Serialize data if not already a string
            if not isinstance(data, str):
                data_str = json.dumps(data)
            else:
                data_str = data

            # Convert to bytes
            plaintext = data_str.encode('utf-8')

            # Generate nonce
            nonce = os.urandom(12)  # 96 bits for ChaCha20-Poly1305

            # Create cipher and encrypt
            cipher = ChaCha20Poly1305(self.key)
            ciphertext = cipher.encrypt(nonce, plaintext, None)

            # Format for storage with metadata
            result = {
                "v": 1,  # Version
                "alg": "chacha20poly1305",  # Algorithm
                "t": data_type,  # Data type
                "n": base64.b64encode(nonce).decode('utf-8'),  # Nonce
                "d": base64.b64encode(ciphertext).decode('utf-8')  # Data
            }

            # Convert to JSON string
            encrypted = json.dumps(result)

            # Store in cache if applicable
            if cache_key:
                if 'encrypt' not in self._cache:
                    self._cache['encrypt'] = {}

                # Manage cache size
                if len(self._cache['encrypt']) >= self._cache_size:
                    # Remove random entry when cache is full
                    import random
                    key_to_remove = random.choice(list(self._cache['encrypt'].keys()))
                    del self._cache['encrypt'][key_to_remove]

                self._cache['encrypt'][cache_key] = encrypted

            return encrypted

        except Exception as e:
            print(f"Encryption error: {str(e)}")
            # Return the original data if encryption fails
            if isinstance(data, str):
                return data
            return json.dumps(data)

    def decrypt(self, encrypted_data):
        """
        Decrypt data from database

        Args:
            encrypted_data: The encrypted data to decrypt

        Returns:
            The decrypted data with original type if possible
        """
        # Skip decryption for None values
        if encrypted_data is None:
            return None

        # Handle non-string data (shouldn't happen, but just in case)
        if not isinstance(encrypted_data, str):
            return encrypted_data

        # Check cache first
        cache_key = self._cache_key(encrypted_data)
        if cache_key and cache_key in self._cache.get('decrypt', {}):
            return self._cache['decrypt'][cache_key]

        try:
            # Try to parse as JSON
            try:
                parsed = json.loads(encrypted_data)

                # Check if this is our encrypted format
                if (not isinstance(parsed, dict) or "alg" not in parsed or
                        parsed.get("alg") != "chacha20poly1305"):
                    # Not our format, return as is
                    return encrypted_data

                # Extract components
                nonce = base64.b64decode(parsed["n"])
                ciphertext = base64.b64decode(parsed["d"])
                data_type = parsed.get("t", "str")

                # Create cipher
                cipher = ChaCha20Poly1305(self.key)

                # Decrypt
                plaintext = cipher.decrypt(nonce, ciphertext, None)

                # Convert from bytes to string
                data_str = plaintext.decode('utf-8')

                # Convert to original type if possible
                result = None
                if data_type == "str":
                    result = data_str
                elif data_type in ["dict", "list", "int", "float", "bool"]:
                    result = json.loads(data_str)
                else:
                    # Default to string for unknown types
                    result = data_str

                # Store in cache if applicable
                if cache_key:
                    if 'decrypt' not in self._cache:
                        self._cache['decrypt'] = {}

                    # Manage cache size
                    if len(self._cache['decrypt']) >= self._cache_size:
                        # Remove random entry when cache is full
                        import random
                        key_to_remove = random.choice(list(self._cache['decrypt'].keys()))
                        del self._cache['decrypt'][key_to_remove]

                    self._cache['decrypt'][cache_key] = result

                return result

            except json.JSONDecodeError:
                # Not JSON, return as is
                return encrypted_data

        except Exception as e:
            print(f"Decryption error: {str(e)}")
            # Return error indicator rather than the encrypted data
            return "[ENCRYPTED DATA ERROR]"

    def clear_cache(self):
        """Clear the encryption/decryption cache"""
        self._cache = {}