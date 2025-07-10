# Fights In The Forest

A multiplayer strategy combat game inspired by D&D, bringing tabletop RPG combat to mobile devices with real-time multiplayer functionality.


## Features

- **Multiplayer Combat**: Real-time multiplayer battles with multiple players
- **D&D-Inspired Mechanics**: Turn-based combat system inspired by tabletop RPGs
- **Mobile-First Design**: Optimized for Android devices
- **Strategic Gameplay**: Tactical combat requiring planning and strategy
- **Forest Setting**: Immersive forest environment for battles



## Prerequisites

### Client (Android)
- Android 11.0+ (API level 31+)
- Internet connection for multiplayer functionality

### Server
- Python 3.9 or higher
- Access to Firebase database
- Required Python packages (see `requirements.txt`)



## Installation

### Server Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/NetaGor/Fights-In-The-Forest.git
   cd Fights-In-The-Forest
   ```

2. **Install Python dependencies**
   ```bash
   pip install -r requirements.txt
   ```

3. **Set up Firebase**
   - Create a Firebase project
   - Download the Firebase SDK configuration
   - Place the Firebase SDK files in the appropriate directory

4. **Configure encryption keys**
   - Generate public/private key pairs for encryption
   - Place the key files in the designated directory (see Missing Files section)

### Android Client

1. **Build Requirements**
   - Android Studio
   - Android SDK with API level 31+

2. **Installation**
   - Import the project into Android Studio
   - Build and install the APK on your Android device



## Missing Files

The following files are required but not included in the repository for security reasons:

- **Public/Private Key Files**: Encryption key pairs for secure communication
- **Firebase SDK**: Firebase configuration files for database connectivity

Please ensure these files are properly configured before running the application.



## Usage

1. **Start the Server**
   ```bash
   python server.py  # Adjust based on actual server file name
   ```

2. **Connect with Android Client**
   - Launch the app on your Android device
   - Ensure you have an active internet connection
   - Connect to the server to start multiplayer battles



## Gameplay

- **Turn-Based Combat**: Each player takes turns planning and executing actions
- **Strategic Positioning**: Use the forest environment to your tactical advantage
- **D&D Mechanics**: Familiar combat rules adapted for mobile gameplay
- **Multiplayer Battles**: Engage with other players in real-time combat scenarios



## License

This project is licensed under the [MIT License](LICENSE) - see the LICENSE file for details.
