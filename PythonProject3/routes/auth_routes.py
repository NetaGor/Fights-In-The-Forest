"""
Authentication routes for user registration and login.

Provides secure endpoints for:
- User registration
- User login
- Encrypted request/response handling
"""

import traceback
from flask import request, jsonify
from config import app, db, USERS_COLLECTION
from security.encryption_utils import (
    encrypt_response, decrypt_request, hash_password,
    check_password, user_exists, hybrid_encryption
)


@app.route('/register', methods=['POST'])
def register():
    """Handle user registration with encrypted communication."""
    try:
        data = request.get_json()
        credentials = decrypt_request(data)

        username = credentials.get('username')
        password = credentials.get('password')
        user_public_key = credentials.get('public_key')

        if user_exists(username):
            error_response = {"status": "error", "message": "Username already exists."}
            if user_public_key:
                try:
                    encrypted_response = hybrid_encryption.encrypt_with_public_key(error_response, user_public_key)
                    return jsonify(encrypted_response), 400
                except Exception as e:
                    raise
            return jsonify(hybrid_encryption.encrypt_symmetric(error_response)), 400

        hashed_password = hash_password(password)

        user_data = {
            'username': username,
            'password': hashed_password
        }

        if user_public_key:
            user_data['public_key'] = user_public_key

        db.collection(USERS_COLLECTION).document(username).set(user_data)

        success_response = {"status": "success", "message": "User registered successfully."}

        if user_public_key:
            try:
                encrypted_response = hybrid_encryption.encrypt_with_public_key(success_response, user_public_key)
                return jsonify(encrypted_response)
            except Exception as e:
                raise

        return jsonify(hybrid_encryption.encrypt_symmetric(success_response))

    except Exception as e:
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred during registration."}), 500


@app.route('/login', methods=['POST'])
def login():
    """Handle user login with encrypted communication."""
    try:
        data = request.get_json()
        credentials = decrypt_request(data)

        username = credentials.get('username')
        password = credentials.get('password')
        user_public_key = credentials.get('public_key')

        query = db.collection(USERS_COLLECTION).where('username', '==', username).get()

        if not query or len(query) == 0:
            error_response = {"status": "error", "message": "User does not exist."}
            if user_public_key:
                try:
                    encrypted_response = hybrid_encryption.encrypt_with_public_key(error_response, user_public_key)
                    return jsonify(encrypted_response), 400
                except Exception as e:
                    raise

            return jsonify(hybrid_encryption.encrypt_symmetric(error_response)), 400

        user_data = query[0].to_dict()

        stored_password = user_data.get("password", "")

        if not check_password(stored_password, password):
            error_response = {"status": "error", "message": "Invalid password."}
            if user_public_key:
                try:
                    encrypted_response = hybrid_encryption.encrypt_with_public_key(error_response, user_public_key)
                    return jsonify(encrypted_response), 400
                except Exception as e:
                    raise

            return jsonify(hybrid_encryption.encrypt_symmetric(error_response)), 400

        if user_public_key:
            db.collection(USERS_COLLECTION).document(username).update({
                'public_key': user_public_key
            })

        success_response = {"status": "success", "message": "Login successful."}

        if user_public_key:
            try:
                encrypted_response = hybrid_encryption.encrypt_with_public_key(success_response, user_public_key)
                return jsonify(encrypted_response)
            except Exception as e:
               raise

        return jsonify(hybrid_encryption.encrypt_symmetric(success_response))

    except Exception as e:
        traceback.print_exc()
        return jsonify({"status": "error", "message": "An error occurred during login."}), 500