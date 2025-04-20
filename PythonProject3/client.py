import requests

url = "http://127.0.0.1:8080/get_room_data"
data = {
    "room_code": "1234"
}
print("sent data")

response = requests.post(url, json=data)
print(response.json())


url = "http://127.0.0.1:5000/save_hero"
data = {
    "username": "user3",
    "hero_index": -1,
    "name": "hero1",
    "desc": "desc",
    "abilities": ["acid_splash", "acid_splash", "acid_splash", "acid_splash", "acid_splash", "acid_splash"]
}
print("sent data")

response = requests.post(url, json=data)
print(response.json())

url = "http://127.0.0.1:5000/assign_character"
data = {
    "username": "user1",
    "room_code": '1234',
    "name": "hero3",
    "character_type": "hero"
}
print("sent data")

response = requests.post(url, json=data)
print(response.json())


url = "http://127.0.0.1:5000/save_ability"
data = {
    "username": "user1",
    "room_code": '1234',
    "target_name": "user2",
    "ability": "acid_splash"
}
print("sent data")

response = requests.post(url, json=data)
print(response.json())
