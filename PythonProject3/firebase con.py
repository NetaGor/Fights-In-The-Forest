import firebase_admin
from firebase_admin import credentials, firestore

try:
    # Initialize Firebase
    print("Initializing Firebase...")
    cred = credentials.Certificate("fightsintheforest-firebase-adminsdk-fbsvc-c35c3cb72b.json")
    firebase_admin.initialize_app(cred)
    print("Firebase initialized.")

    # Connect to Firestore
    db = firestore.client()
    print("Connected to Firestore.")

    # Test Connectivity
    print("Fetching documents...")
    users = db.collection("users").stream()
    for user in users:
        print(f"User ID: {user.id}, Data: {user.to_dict()}")

    print("Adding document...")
    doc_ref = db.collection("users").document("user2").set({
      "name": "Jane Doe",
      "email": "janedoe@example.com",
      "age": 28
    })
    print("Document added with ID:", doc_ref)

except Exception as e:
    print("An error occurred:", e)
