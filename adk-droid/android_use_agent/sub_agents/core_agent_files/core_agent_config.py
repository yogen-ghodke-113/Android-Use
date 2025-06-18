# adk-droid/android_use_agent/sub_agents/core_agent_files/core_agent_config.py
"""
Configuration constants for the Core Agent.
"""
from pathlib import Path
import logging

logger = logging.getLogger(__name__)

# Default model name if not overridden
DEFAULT_MODEL_NAME = "gemini-2.5-flash-preview-04-17" # Upgraded default for vision-first

# Agent type identifier
DEFAULT_AGENT_TYPE = "core_agent_v2_vision"

# Limits for context building
CONVERSATION_HISTORY_LIMIT = 10  # Max number of recent messages to include
MAX_UI_ELEMENTS_IN_PROMPT = 50 # Deprecated, use vision limit
MAX_VISION_ELEMENTS_IN_PROMPT = 75 # Max ROIs to include in the prompt

# LLM Request Timeout (in seconds)
LLM_TIMEOUT_SECONDS = 120.0 # Increased timeout

# Directory containing prompt templates
PROMPT_DIR = Path(__file__).parent.parent.parent.parent / "prompts"

# Default prompt file name
DEFAULT_PROMPT_FILE = "core_agent.md"

def get_prompt_path(filename: str | None = None) -> Path:
    """Gets the full path to the specified prompt file."""
    return PROMPT_DIR / (filename or DEFAULT_PROMPT_FILE)

def load_prompt(file_path: Path) -> str:
    """Loads the instruction text from the prompt file."""
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            prompt_text = f.read()
            if not prompt_text or len(prompt_text) < 50: # Basic sanity check
                 logger.warning(f"Loaded prompt from {file_path} seems very short or empty.")
            return prompt_text
    except FileNotFoundError:
        logger.error(f"Prompt file not found: {file_path}")
        return "ERROR: Prompt file missing."
    except Exception as e:
        logger.error(f"Error loading prompt file {file_path}: {e}")
        return f"ERROR: Could not load prompt - {e}"

