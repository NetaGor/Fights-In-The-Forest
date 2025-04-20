# SERVER-SIDE ENCRYPTION FIXES

# 1. Update HybridEncryption.py to handle hybrid-encrypted requests

from Crypto.Cipher import AES, PKCS1_v1_5
from Crypto.PublicKey import RSA
from Crypto.Random import get_random_bytes
from Crypto.Util.Padding import pad, unpad
import base64
import json
import traceback


class HybridEncryption:
    """
    Hybrid encryption utility that combines RSA and AES encryption.
    - Uses RSA for encrypting a random AES key
    - Uses AES for encrypting the actual data
    """

    def __init__(self, symmetric_key=b'ThisIsASecretKey', symmetric_iv=b'ThisIsAnIVectors'):
        """
        Initialize with fallback symmetric encryption parameters

        Parameters:
        symmetric_key (bytes): 16-byte key for AES-128 encryption when RSA fails
        symmetric_iv (bytes): 16-byte initialization vector when RSA fails
        """
        # Ensure key and IV are the right length for fallback symmetric encryption
        self.symmetric_key = symmetric_key[:16].ljust(16, b'\0')
        self.symmetric_iv = symmetric_iv[:16].ljust(16, b'\0')

    def encrypt_with_public_key(self, data, public_key_str):
        """
        Encrypt data using hybrid encryption (RSA + AES)

        Parameters:
        data (dict/str): Data to encrypt
        public_key_str (str): RSA public key in PEM or base64 format

        Returns:
        dict: Dictionary with encrypted data, encrypted key, and metadata
        """
        try:
            # Convert data to JSON string if it's a dictionary
            if isinstance(data, dict):
                data = json.dumps(data)

            # Convert string data to bytes
            if isinstance(data, str):
                data = data.encode('utf-8')

            # Try to normalize the key format if it's not in PEM format
            if not public_key_str.startswith('-----BEGIN PUBLIC KEY-----'):
                # If it's just base64, try to add PEM headers
                pem_key = "-----BEGIN PUBLIC KEY-----\n"
                # Insert newlines every 64 characters
                for i in range(0, len(public_key_str), 64):
                    pem_key += public_key_str[i:i + 64] + "\n"
                pem_key += "-----END PUBLIC KEY-----"
                public_key_str = pem_key

            # Import the public key
            public_key = RSA.import_key(public_key_str)

            # Generate a random AES key
            aes_key = get_random_bytes(16)  # 128-bit key

            # Encrypt the data with AES
            cipher_aes = AES.new(aes_key, AES.MODE_CBC)
            iv = cipher_aes.iv
            padded_data = pad(data, AES.block_size)
            encrypted_data = cipher_aes.encrypt(padded_data)

            # Encrypt the AES key with RSA using PKCS#1 v1.5 padding to match Android client
            cipher_rsa = PKCS1_v1_5.new(public_key)
            encrypted_key = cipher_rsa.encrypt(aes_key)

            # Base64 encode everything for transmission
            return {
                "encrypted": True,
                "method": "hybrid-rsa-aes",
                "encrypted_key": base64.b64encode(encrypted_key).decode('utf-8'),
                "iv": base64.b64encode(iv).decode('utf-8'),
                "data": base64.b64encode(encrypted_data).decode('utf-8')
            }

        except Exception as e:
            print(f"Error in hybrid encryption: {str(e)}")
            traceback.print_exc()

            # Fall back to symmetric encryption
            return self.encrypt_symmetric(data)

    def decrypt_hybrid_request(self, encrypted_key_base64, iv_base64, encrypted_data_base64, private_key):
        """
        Decrypts a hybrid-encrypted request from a client

        Parameters:
        encrypted_key_base64 (str): Base64-encoded RSA-encrypted AES key
        iv_base64 (str): Base64-encoded initialization vector
        encrypted_data_base64 (str): Base64-encoded AES-encrypted data
        private_key (RSAPrivateKey): The server's RSA private key

        Returns:
        str: The decrypted data
        """
        try:
            # Decode all components from Base64
            encrypted_key = base64.b64decode(encrypted_key_base64)
            iv = base64.b64decode(iv_base64)
            encrypted_data = base64.b64decode(encrypted_data_base64)

            # Decrypt the AES key with the server's private RSA key
            cipher_rsa = PKCS1_v1_5.new(private_key)
            sentinel = get_random_bytes(16)  # Value returned if decryption fails
            aes_key = cipher_rsa.decrypt(encrypted_key, sentinel)

            if aes_key == sentinel:
                raise ValueError("RSA decryption of AES key failed")

            # Decrypt the data with the decrypted AES key
            cipher_aes = AES.new(aes_key, AES.MODE_CBC, iv)
            decrypted_padded = cipher_aes.decrypt(encrypted_data)

            # Unpad the decrypted data
            decrypted_data = unpad(decrypted_padded, AES.block_size)

            # Return the decrypted data as a string
            return decrypted_data.decode('utf-8')

        except Exception as e:
            print(f"Error in hybrid request decryption: {str(e)}")
            traceback.print_exc()
            raise

    def encrypt_symmetric(self, data):
        """
        Fall back to simple symmetric encryption

        Parameters:
        data (dict/str): Data to encrypt

        Returns:
        dict: Dictionary with encrypted data using symmetric encryption
        """
        try:
            # Convert to JSON string if it's a dictionary
            if isinstance(data, dict):
                data = json.dumps(data)

            # Convert to bytes if it's a string
            if isinstance(data, str):
                data = data.encode('utf-8')

            # Create cipher
            cipher = AES.new(self.symmetric_key, AES.MODE_CBC, self.symmetric_iv)

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
            print(f"Symmetric encryption error: {str(e)}")
            return {"error": "Encryption failed"}

    def decrypt_symmetric(self, encrypted_data):
        """
        Decrypt data that was encrypted with symmetric encryption

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
            cipher = AES.new(self.symmetric_key, AES.MODE_CBC, self.symmetric_iv)

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
            print(f"Symmetric decryption error: {str(e)}")
            raise
