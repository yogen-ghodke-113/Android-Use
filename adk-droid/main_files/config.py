# adk-droid/main_files/config.py
"""
Configuration setup: Logging and Environment Variables.
"""
import logging
import os
from pathlib import Path
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Depends
from fastapi.middleware.cors import CORSMiddleware
import uvicorn
from typing import Dict, Any, Optional, List
from dotenv import load_dotenv

# --- REMOVE VERTEX AI IMPORT --- #
# import vertexai
# --- END REMOVE VERTEX AI IMPORT --- #

from .connection_manager import ConnectionManager

# Define constants for app name and user ID that will be used consistently
APP_NAME = "android-use-agent"
USER_ID = "default-user"

# --- Configure Logging FIRST ---
def setup_logging():
    """Configures the application logging."""
    # Determine log level from environment variable or default to INFO
    log_level_name = os.environ.get("LOG_LEVEL", "INFO").upper()
    # Default to INFO if the environment variable value is invalid
    log_level = getattr(logging, log_level_name, logging.INFO)

    # --- CHANGE HERE: Force DEBUG for this test --- #
    log_level = logging.DEBUG # Keep DEBUG for now
    # --- END CHANGE --- #

    # Use basicConfig for simplicity, suitable for many scenarios.
    # For more complex logging (e.g., multiple handlers, file output), consider dictConfig.
    logging.basicConfig(level=log_level, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
    logger = logging.getLogger(__name__) # Logger for this module

    # Suppress overly verbose loggers from dependencies
    logging.getLogger("watchfiles").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.INFO)
    logging.getLogger("httpx").setLevel(logging.INFO)
    # Add LiteLLM logger suppression if needed
    logging.getLogger("LiteLLM").setLevel(logging.INFO) # Example: Set LiteLLM to INFO
    logger.info(f"Logging configured with level: {log_level}")
    return logger # Return logger instance

# --- Load Environment Variables ---
def load_environment():
    """Loads environment variables from a .env file located in the parent directory."""
    logger = logging.getLogger(__name__) # Get logger instance
    try:
        from dotenv import load_dotenv
        # Calculate path to .env file in the parent directory (adk-droid)
        dotenv_path = Path(__file__).resolve().parent.parent / '.env'
        logger.info(f"Attempting to load .env file from: {dotenv_path}")
        loaded = load_dotenv(dotenv_path=dotenv_path, verbose=True, override=True)
        if loaded:
            logger.info(".env file loaded successfully.")
            # Optional: Debug log for specific keys if needed
            gemini_key_debug = os.environ.get("GEMINI_API_KEY")
            if gemini_key_debug:
                logger.debug(f"DEBUG: GEMINI_API_KEY found after load. Starts: {gemini_key_debug[:5]}..., Ends: ...{gemini_key_debug[-4:]}")
            else:
                logger.debug("DEBUG: GEMINI_API_KEY *NOT* found after load.")
            # Add checks for other keys (OPENAI_API_KEY, etc.) if needed
        else:
            # This is common if the .env file doesn't exist, treat as info not warning
            logger.info(f".env file not found or empty at {dotenv_path}.")
    except ImportError:
        logger.info("python-dotenv not installed, skipping .env file loading.")
    except Exception as e:
        logger.warning(f"An error occurred loading .env file: {e}")

# Call setup functions immediately when this module is imported
# This ensures logging and environment are set up early
logger = setup_logging()
load_environment()

# --- REMOVE Vertex AI Initialization Section --- #
# GOOGLE_CLOUD_PROJECT = os.environ.get("GOOGLE_CLOUD_PROJECT")
# if GOOGLE_CLOUD_PROJECT:
#     try:
#         vertexai.init(project=GOOGLE_CLOUD_PROJECT)
#         logger.info(f"Vertex AI initialized successfully for project: {GOOGLE_CLOUD_PROJECT}")
#     except Exception as e:
#         logger.error(f"Failed to initialize Vertex AI for project {GOOGLE_CLOUD_PROJECT}: {e}", exc_info=True)
# else:
#     logger.warning("GOOGLE_CLOUD_PROJECT environment variable not set. Vertex AI SDK might face issues determining the project.")
# --- END REMOVE Vertex AI Initialization Section --- #
