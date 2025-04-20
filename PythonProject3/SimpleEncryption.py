from Crypto.Cipher import AES
from Crypto.Util.Padding import pad, unpad
import base64
import json


class SimpleEncryption:
    """
    Simple symmetric encryption utility for API responses
    """

    def __init__(self, key=b'ThisIsASecretKey', iv=b'ThisIsAnIVectors'):
        """
        Initialize with encryption key and IV

        Parameters:
        key (bytes): 16-byte key for AES-128 encryption
        iv (bytes): 16-byte initialization vector
        """
        # Ensure key and IV are the right length
        self.key = key[:16].ljust(16, b'\0')
        self.iv = iv[:16].ljust(16, b'\0')

    def encrypt(self, data):
        """
        Encrypt data using AES

        Parameters:
        data (dict/str): Data to encrypt

        Returns:
        dict: Dictionary with encrypted data and metadata
        """
        try:
            # Convert to JSON string if it's a dictionary
            if isinstance(data, dict):
                data = json.dumps(data)

            # Convert to bytes if it's a string
            if isinstance(data, str):
                data = data.encode('utf-8')

            # Create cipher
            cipher = AES.new(self.key, AES.MODE_CBC, self.iv)

            # Pad and encrypt
            padded_data = pad(data, AES.block_size)
            encrypted_data = cipher.encrypt(padded_data)

            # Base64 encode
            encoded_data = base64.b64encode(encrypted_data).decode('utf-8')

            # Return with encryption method identifier
            return {
                "encrypted": True,
                "method": "aes-128-cbc",
                "data": encoded_data
            }

        except Exception as e:
            print(f"Encryption error: {str(e)}")
            return {"error": "Encryption failed"}

    def decrypt(self, encrypted_data):
        """
        Decrypt data that was encrypted with the same key

        Parameters:
        encrypted_data (dict/str): Encrypted data

        Returns:
        dict/str: Decrypted data
        """
        try:
            # Handle different input formats
            if isinstance(encrypted_data, dict):
                if "data" not in encrypted_data:
                    raise ValueError("Invalid encrypted data format")
                data = encrypted_data["data"]
            else:
                data = encrypted_data

            # Decode from Base64
            binary_data = base64.b64decode(data)

            # Create cipher
            cipher = AES.new(self.key, AES.MODE_CBC, self.iv)

            # Decrypt and unpad
            decrypted_padded = cipher.decrypt(binary_data)
            decrypted_data = unpad(decrypted_padded, AES.block_size)

            # Convert to string
            decrypted_str = decrypted_data.decode('utf-8')

            # Try to parse as JSON if possible
            try:
                return json.loads(decrypted_str)
            except json.JSONDecodeError:
                return decrypted_str

        except Exception as e:
            print(f"Decryption error: {str(e)}")
            raise
