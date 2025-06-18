# Android-Use Python Server (adk-droid)

This is the Python server component of Android-Use that handles AI-powered automation logic. It uses Google's Agent Development Kit (ADK) and communicates with the Android client via WebSocket connections.

## 🚀 Features

- **Multi-Agent Architecture**: Core, Dialog, and Input Classifier agents for different tasks
- **Google ADK Integration**: Leverages Google's Agent Development Kit for robust agent management
- **WebSocket Communication**: Real-time bidirectional communication with Android clients
- **AI-Powered Vision**: Uses Gemini models for screenshot analysis and decision making
- **Correlation-based Messaging**: Reliable request/response matching using correlation IDs

## 📋 Prerequisites

- Python 3.8 or later
- Google Cloud Project with ADK access
- Gemini API key or Vertex AI access
- Virtual environment (recommended)

## 🛠️ Installation

### 1. Clone and Navigate

```bash
git clone https://github.com/your-username/android-use.git
cd android-use/adk-droid
```

### 2. Create Virtual Environment

```bash
python -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
```

### 3. Install Dependencies

```bash
pip install -r requirements.txt
```

### 4. Environment Configuration

Create a `.env` file in the `adk-droid` directory:

```env
# Google Cloud Configuration
GOOGLE_APPLICATION_CREDENTIALS=path/to/your/service-account-key.json
GOOGLE_CLOUD_PROJECT=your-google-cloud-project-id

# Gemini API Configuration
GEMINI_API_KEY=your-gemini-api-key-here

# Server Configuration
SERVER_HOST=0.0.0.0
SERVER_PORT=8000

# Application Configuration
APP_NAME=android-use-agent
USER_ID=default-user

# Logging Configuration
LOG_LEVEL=INFO
```

### 5. Google Cloud Setup

#### Option A: Using Gemini API (Recommended for beginners)

1. Go to [Google AI Studio](https://aistudio.google.com/app/apikey)
2. Create a new API key
3. Add it to your `.env` file as `GEMINI_API_KEY`

#### Option B: Using Google Cloud with ADK

1. Create a Google Cloud Project
2. Enable necessary APIs (Vertex AI, ADK)
3. Create a service account and download the JSON key
4. Set `GOOGLE_APPLICATION_CREDENTIALS` to the path of your JSON key

## 🚀 Running the Server

### Start the Server

```bash
python main.py
```

The server will start on `http://localhost:8000` by default.

### Verify Installation

Check the health endpoint:

```bash
curl http://localhost:8000/health
```

## 🏗️ Architecture Overview

### Core Components

- **`main.py`**: FastAPI application entry point and WebSocket handler
- **`task_manager.py`**: Main orchestration logic for task execution
- **`android_use_agent/`**: AI agents and tools
- **`main_files/`**: Core server utilities and configuration

### Agent System

1. **InputClassifierAgent**: Classifies user input as task or chat
2. **CoreAgent**: Main decision-making agent that analyzes screens and determines actions
3. **DialogAgent**: Handles conversational interactions and clarifications

### Communication Flow

1. Android client connects via WebSocket
2. User sends natural language command
3. InputClassifierAgent classifies the input
4. If it's a task, TaskManager starts execution loop:
   - Requests screenshot from client
   - CoreAgent analyzes and decides next action
   - Sends action command to client
   - Receives execution result
   - Repeats until task completion

## 🔧 Configuration

### Environment Variables

| Variable                         | Description                               | Required              |
| -------------------------------- | ----------------------------------------- | --------------------- |
| `GOOGLE_APPLICATION_CREDENTIALS` | Path to Google Cloud service account JSON | Yes\*                 |
| `GOOGLE_CLOUD_PROJECT`           | Google Cloud project ID                   | Yes\*                 |
| `GEMINI_API_KEY`                 | Gemini API key                            | Yes\*                 |
| `SERVER_HOST`                    | Server bind address                       | No (default: 0.0.0.0) |
| `SERVER_PORT`                    | Server port                               | No (default: 8000)    |
| `LOG_LEVEL`                      | Logging level                             | No (default: INFO)    |

\*Either Google Cloud credentials OR Gemini API key is required

### Agent Configuration

Agents are configured in `android_use_agent/sub_agents/model_config.py`:

- **CoreAgent**: Uses `gemini-2.5-flash-preview-04-17` for vision and reasoning
- **DialogAgent**: Uses `gemini-1.5-flash-8b` for conversations
- **InputClassifierAgent**: Uses `gemini-1.5-flash-8b` for classification

## 🐛 Troubleshooting

### Common Issues

1. **"Module not found" errors**:

   ```bash
   pip install -r requirements.txt
   ```

2. **Google Cloud authentication errors**:

   - Verify your service account JSON file path
   - Ensure the service account has necessary permissions
   - Check that APIs are enabled in your Google Cloud project

3. **Connection refused**:

   - Check if the server is running on the correct port
   - Verify firewall settings
   - Ensure the Android client is using the correct server URL

4. **WebSocket connection failures**:
   - Check network connectivity between client and server
   - Verify the session ID is being properly handled
   - Look at server logs for connection errors

### Debugging

Enable debug logging:

```env
LOG_LEVEL=DEBUG
```

Check server logs for detailed information about:

- Agent decisions and reasoning
- WebSocket message flow
- Error details and stack traces

### Performance Optimization

For better performance:

- Use an SSD for faster file I/O
- Ensure adequate RAM (8GB+ recommended)
- Use a stable internet connection for API calls
- Consider running on cloud infrastructure for production

## 📚 Development

### Project Structure

```
adk-droid/
├── main.py                     # FastAPI entry point
├── task_manager.py            # Main orchestration logic
├── requirements.txt           # Python dependencies
├── android_use_agent/         # AI agents and models
│   ├── agent.py              # Agent initialization
│   ├── models.py             # Data models
│   ├── sub_agents/           # Individual agent implementations
│   ├── tools/                # Agent tools
│   └── task_manager_files/   # Task management utilities
├── main_files/               # Core server components
│   ├── config.py             # Configuration and environment
│   ├── connection_manager.py # WebSocket management
│   ├── api_models.py         # Message models
│   └── dependencies.py       # Dependency injection
└── prompts/                  # Agent prompt templates
```

### Adding New Features

1. **New Agent**: Add to `android_use_agent/sub_agents/`
2. **New Action Type**: Update models in `android_use_agent/models.py`
3. **New Tool**: Add to `android_use_agent/tools/`
4. **API Endpoint**: Add to `main_files/api_routes.py`

### Testing

Run tests:

```bash
python -m pytest tests/
```

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.

## 🤝 Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for contribution guidelines.

## 📞 Support

- **Issues**: [GitHub Issues](https://github.com/your-username/android-use/issues)
- **Discussions**: [GitHub Discussions](https://github.com/your-username/android-use/discussions)
