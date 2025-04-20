"""
Simple Database Encryption Module for Fights in the Forest

This module handles the encryption and decryption of sensitive database fields
using Fernet symmetric encryption with a static key stored in a file.
"""

import os
import json
import base64
from cryptography.fernet import Fernet

class KeyFileEncryption:
    """
    Handles encryption and decryption of sensitive database fields
    using Fernet symmetric encryption with a static key.
    """

    # Key file path
    KEY_FILE = "fernet_key.txt"

    # Default encryption key - will be generated if file doesn't exist
    DEFAULT_KEY = b'dGhpc2lzYXNlY3JldGtleWZvcmZpZ2h0c2ludGhlZm9yZXN0Z2FtZQ=='

    def __init__(self):
        """Initialize the encryption module and load the key from file"""
        self.key = self._load_or_create_key()
        self.cipher = Fernet(self.key)

    def _load_or_create_key(self):
        """
        Load the key from file or create it if it doesn't exist

        Returns:
            bytes: The Fernet encryption key
        """
        try:
            if os.path.exists(self.KEY_FILE):
                with open(self.KEY_FILE, 'rb') as f:
                    key_data = f.read().strip()
                return key_data
            else:
                # Create a new key file with the default key or generate a new one
                key = self.DEFAULT_KEY
                with open(self.KEY_FILE, 'wb') as f:
                    f.write(key)
                print(f"Created new key file: {self.KEY_FILE}")
                return key
        except Exception as e:
            print(f"Error loading key: {str(e)}")
            # Use the default key as fallback
            return self.DEFAULT_KEY

    def encrypt_field(self, data):
        """
        Encrypt a single field with Fernet symmetric encryption

        Args:
            data (str/dict/list): Data to encrypt

        Returns:
            str: Base64-encoded encrypted data
        """
        if data is None:
            return None

        try:
            # Convert to JSON string if it's a complex type
            if isinstance(data, (dict, list)):
                plaintext = json.dumps(data).encode('utf-8')
            else:
                plaintext = str(data).encode('utf-8')

            # Encrypt the data
            encrypted_data = self.cipher.encrypt(plaintext)

            # Return as string
            return encrypted_data.decode('utf-8')

        except Exception as e:
            print(f"Encryption error: {str(e)}")
            # Return the original data on error to prevent data loss
            return str(data)

    def decrypt_field(self, encrypted_data):
        """
        Decrypt a field that was encrypted with encrypt_field

        Args:
            encrypted_data (str): Encrypted data string

        Returns:
            The decrypted data, converted from JSON if applicable
        """
        if not encrypted_data:
            return None

        try:
            # Convert to bytes if it's a string
            if isinstance(encrypted_data, str):
                encrypted_bytes = encrypted_data.encode('utf-8')
            else:
                encrypted_bytes = encrypted_data

            # Decrypt
            decrypted_data = self.cipher.decrypt(encrypted_bytes)

            # Convert from bytes to string
            plaintext_str = decrypted_data.decode('utf-8')

            # Try to parse as JSON if it looks like JSON
            if (plaintext_str.startswith('{') and plaintext_str.endswith('}')) or \
               (plaintext_str.startswith('[') and plaintext_str.endswith(']')):
                try:
                    return json.loads(plaintext_str)
                except json.JSONDecodeError:
                    pass

            return plaintext_str

        except Exception as e:
            print(f"Decryption error: {str(e)}")
            return encrypted_data  # Return the encrypted data on error

    def encrypt_document(self, document, sensitive_fields):
        """
        Encrypt specified fields in a document/dictionary

        Args:
            document (dict): The document to encrypt fields in
            sensitive_fields (list): List of field names to encrypt

        Returns:
            dict: Document with encrypted fields
        """
        if not document:
            return document

        result = document.copy()

        for field in sensitive_fields:
            if field in result and result[field] is not None:
                result[field] = self.encrypt_field(result[field])

        return result

    def decrypt_document(self, document, sensitive_fields):
        """
        Decrypt specified fields in a document/dictionary

        Args:
            document (dict): The document with encrypted fields
            sensitive_fields (list): List of field names to decrypt

        Returns:
            dict: Document with decrypted fields
        """
        if not document:
            return document

        result = document.copy()

        for field in sensitive_fields:
            if field in result and result[field] is not None:
                result[field] = self.decrypt_field(result[field])

        return result