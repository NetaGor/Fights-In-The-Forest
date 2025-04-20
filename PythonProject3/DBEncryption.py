import base64
from cryptography.fernet import Fernet


class DBEncryption:
    """
    A simple field-level encryption utility for database documents
    using Fernet symmetric encryption (AES-128 in CBC mode with PKCS7 padding).
    """

    def __init__(self, secret_key=None):
        """
        Initialize with a provided key or generate a new one.

        Args:
            secret_key: Base64 encoded secret key or None to generate a new one
        """
        if secret_key:
            self.fernet = Fernet(secret_key)
        else:
            # Generate a key
            key = Fernet.generate_key()
            self.fernet = Fernet(key)

    def get_key(self):
        """Return the current encryption key as a base64 string"""
        return self.fernet._encryption_key

    def encrypt_field(self, value):
        """
        Encrypt a single field value.

        Args:
            value: String or JSON-serializable value to encrypt

        Returns:
            dict: Dictionary with encrypted value and metadata
        """
        if value is None:
            return None

        # Convert to string if not already
        if not isinstance(value, str):
            import json
            value = json.dumps(value)

        # Encrypt the value
        encrypted = self.fernet.encrypt(value.encode('utf-8'))

        # Return with metadata
        return {
            "__enc": True,
            "data": base64.b64encode(encrypted).decode('utf-8')
        }

    def decrypt_field(self, encrypted_data):
        """
        Decrypt a field that was encrypted with encrypt_field.

        Args:
            encrypted_data: Dictionary with encrypted data

        Returns:
            Original value (string or parsed JSON object)
        """
        if not encrypted_data or not isinstance(encrypted_data, dict):
            return encrypted_data

        # Check if this is an encrypted field
        if not encrypted_data.get("__enc", False):
            return encrypted_data

        # Get the encrypted data
        encrypted = base64.b64decode(encrypted_data.get("data"))

        # Decrypt
        decrypted = self.fernet.decrypt(encrypted).decode('utf-8')

        # Try to parse as JSON if it looks like JSON
        if decrypted.startswith('{') or decrypted.startswith('['):
            try:
                import json
                return json.loads(decrypted)
            except json.JSONDecodeError:
                pass

        return decrypted