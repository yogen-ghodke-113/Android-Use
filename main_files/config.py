import logging
import os
from pathlib import Path
from dotenv import load_dotenv
# import vertexai # REMOVE

# --- Constants ---
APP_NAME = "android-use-agent"
USER_ID = "default-user"

# --- Logging Setup ---
# Moved formatter definition here
log_formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger() # Get root logger
# Clear existing handlers (important in environments like Uvicorn)
if logger.hasHandlers():
    logger.handlers.clear()

# Add console handler
console_handler = logging.StreamHandler()
console_handler.setFormatter(log_formatter)
logger.addHandler(console_handler)
# Add file handler (optional)
# file_handler = logging.FileHandler('adk_server.log')
# file_handler.setFormatter(log_formatter)
# logger.addHandler(file_handler)

# Set default level (can be overridden by environment variable)
log_level_name = os.getenv('LOG_LEVEL', 'DEBUG').upper()
log_level = getattr(logging, log_level_name, logging.DEBUG)
logger.setLevel(log_level)

logger.info(f"Logging configured with level: {log_level}")

# --- Environment Loading ---
def load_environment():
    # Determine the root directory of the project (adk-droid)
    # Assuming this script is in main_files, go up one level
    project_root = Path(__file__).parent.parent
    dotenv_path = project_root / '.env'
    logger.info(f"Attempting to load .env file from: {dotenv_path}")
    loaded = load_dotenv(dotenv_path=dotenv_path)
    if loaded:
        logger.info(".env file loaded successfully.")
        # Debug: Check if specific keys are loaded
        api_key = os.getenv("GOOGLE_API_KEY")
        if api_key:
            logger.debug(f"DEBUG: GOOGLE_API_KEY found after load. Starts: {api_key[:5]}, Ends: {api_key[-5:]}")
        else:
            logger.warning("GOOGLE_API_KEY not found in .env or environment.")
    else:
        logger.warning(f".env file not found at {dotenv_path} or is empty.")

# Load environment variables immediately
load_environment()

