# Android-Use: AI-Powered Android Automation

Android-Use is an open-source system that enables natural language control of Android devices through AI-driven automation. It combines computer vision and accessibility services to understand user intent and execute precise actions on Android interfaces.

## 🚀 Features

- **Natural Language Control**: Control your Android device using plain English commands
- **Hybrid Vision + Accessibility**: Uses screenshots for context understanding and accessibility services for precise interaction
- **Cross-App Automation**: Works with any Android app that properly implements accessibility features
- **Real-time Feedback**: Provides status updates during task execution
- **Intelligent Element Detection**: Uses AI to identify and interact with UI elements
- **WebSocket Communication**: Real-time communication between server and client

## 🏗️ Architecture

The system consists of two main components:

### 1. Android Client App (Kotlin)
- **Accessibility Service**: Captures UI structure and performs actions
- **Screen Capture Service**: Takes screenshots for visual analysis
- **WebSocket Client**: Communicates with the Python server
- **Selector-based Actions**: Uses robust selectors to identify UI elements

### 2. Python Server (adk-droid)
- **Google ADK Integration**: Leverages Google's Agent Development Kit
- **Multi-Agent System**: Core, Dialog, and Input Classifier agents
- **FastAPI WebSocket Server**: Handles client connections and messaging
- **AI Vision Analysis**: Processes screenshots to understand screen content

## 📋 Prerequisites

### For Android Client
- Android device or emulator (API level 24+)
- Developer options enabled
- USB debugging enabled (for installation)

### For Python Server
- Python 3.8+
- Google Cloud Project with ADK access
- Gemini API key
- Required Python packages (see `adk-droid/requirements.txt`)

## 🛠️ Installation

### Android Client Setup

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-username/android-use.git
   cd android-use
   ```

2. **Configure Google Services** (Optional):
   - Copy `google-services.json.template` to `google-services.json`
   - Replace placeholder values with your Firebase/Google Cloud project details
   - This is only needed if you plan to use Firebase services

3. **Open in Android Studio**:
   - Import the project
   - Sync Gradle files

4. **Build and install**:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

5. **Grant permissions**:
   - Enable Accessibility Service for Android-Use
   - Grant screen capture permissions
   - Allow overlay permissions

### Python Server Setup

#### Local Development

1. **Navigate to server directory**:
   ```bash
   cd adk-droid
   ```

2. **Create virtual environment**:
   ```bash
   python -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   ```

3. **Install dependencies**:
   ```bash
   pip install -r requirements.txt
   ```

4. **Configure environment**:
   Create a `.env` file in the `adk-droid` directory:
   ```env
   GEMINI_API_KEY=your-gemini-api-key-here
   LOG_LEVEL=INFO
   ```

5. **Run the server**:
   ```bash
   python main.py
   ```

#### Production Deployment

For production use, you should deploy your own server instance. See [DEPLOYMENT.md](DEPLOYMENT.md) for detailed instructions on deploying to:
- Google Cloud Run (recommended)
- Railway
- Heroku
- Render
- AWS/Azure/DigitalOcean

## 🔧 Configuration

### Environment Variables

Create a `.env` file in the `adk-droid` directory:

```env
# Google Cloud Configuration
GOOGLE_APPLICATION_CREDENTIALS=path/to/your/credentials.json
GOOGLE_CLOUD_PROJECT=your-project-id

# Gemini API
GEMINI_API_KEY=your-gemini-api-key

# Server Configuration
SERVER_HOST=0.0.0.0
SERVER_PORT=8000

# Logging
LOG_LEVEL=INFO
```

### Android App Configuration

Update the server URL in the Android app to point to your deployed server:

1. Open `app/src/main/java/com/yogen/Android_Use/utils/Constants.kt`
2. Update the `SERVER_URL`:
   ```kotlin
   // For local development
   const val SERVER_URL = "ws://127.0.0.1:8000"
   
   // For deployed server (use wss:// for HTTPS endpoints)
   const val SERVER_URL = "wss://your-server-domain.com"
   ```

## 🚀 Usage

1. **Start the Python server**:
   ```bash
   cd adk-droid
   python main.py
   ```

2. **Launch the Android app** and ensure all permissions are granted

3. **Connect to server** through the app interface

4. **Send natural language commands**:
   - "Open Settings and turn on Wi-Fi"
   - "Send a message to John saying 'Hello'"
   - "Take a screenshot and save it"
   - "Navigate to the home screen"

## 📱 Supported Actions

### Basic Interactions
- **Tap**: Click on buttons, links, and other clickable elements
- **Input**: Enter text in input fields
- **Swipe**: Scroll through lists and pages
- **Long Press**: Trigger context menus and special actions

### System Actions
- **Volume Control**: Adjust device volume
- **Global Navigation**: Home, back, recent apps
- **App Launch**: Open specific applications
- **Screenshot**: Capture current screen

### Advanced Features
- **Copy/Paste**: Text manipulation
- **Multi-step Tasks**: Complex workflows across multiple apps
- **Visual Verification**: Confirm action completion

## 🔒 Privacy & Security

- **Local Processing**: Most operations happen locally on your device
- **No Data Storage**: Commands and screenshots are not permanently stored
- **Secure Communication**: WebSocket connections use proper authentication
- **Permission-based**: Only accesses what you explicitly allow

## 🧪 Development

### Project Structure

```
android-use/
├── app/                          # Android client application
│   ├── src/main/java/com/yogen/Android_Use/
│   │   ├── accessibility/        # Accessibility service implementation
│   │   ├── api/                  # WebSocket communication
│   │   ├── execution/            # Action execution logic
│   │   ├── models/               # Data models (Selector, etc.)
│   │   ├── screenshot/           # Screen capture functionality
│   │   └── ui/                   # User interface components
├── adk-droid/                    # Python server
│   ├── android_use_agent/        # Agent implementations
│   ├── main_files/               # Core server components
│   ├── prompts/                  # AI agent prompts
│   └── main.py                   # Server entry point
└── README.md                     # This file
```

### Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Running Tests

```bash
# Android tests
./gradlew test

# Python tests
cd adk-droid
python -m pytest
```

## 🐛 Troubleshooting

### Common Issues

1. **Accessibility Service not working**:
   - Ensure the service is enabled in Android Settings > Accessibility
   - Restart the app after enabling

2. **Connection failed**:
   - Check server is running on correct port
   - Verify firewall settings
   - Ensure Android device can reach server IP

3. **Actions not executing**:
   - Verify target app has proper accessibility implementation
   - Check if UI elements have changed (selectors may need updating)

4. **Screen capture permission denied**:
   - Grant screen capture permission when prompted
   - Restart the app if permission was initially denied

### Debugging

Enable debug logging:
- **Android**: Set log level in `utils/Constants.kt`
- **Python**: Set `LOG_LEVEL=DEBUG` in `.env`

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Google ADK team for the Agent Development Kit
- Android Accessibility team for the robust accessibility APIs
- Gemini AI for powering the intelligent automation

## 📞 Support

- **Issues**: [GitHub Issues](https://github.com/your-username/android-use/issues)
- **Discussions**: [GitHub Discussions](https://github.com/your-username/android-use/discussions)
- **Documentation**: [Project Wiki](https://github.com/your-username/android-use/wiki)

---

**Note**: This project is in active development. Features and APIs may change. Always use the latest release for production use cases. 