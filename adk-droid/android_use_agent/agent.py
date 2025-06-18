# -*- coding: utf-8 -*-
"""
Main agent definition file for the Android Use Agent application.

This file initializes various sub-agents (Core, Coding, Dialog, etc.),
configures language models (including LiteLLM fallbacks if available),
instantiates necessary tools for interacting with the Android environment,
and provides utility functions to access agents and tools.
"""

import os
import logging
import json
from google.adk.tools import FunctionTool
from typing import Any, Dict, Optional, List, Literal
from pathlib import Path
# Assuming models.py exists in the same directory or is accessible
# from android_use_agent.models import ElementSelector # Keep if models are still used

# Configure logging
# Sets up basic logging to capture informational messages and errors.
logging.basicConfig(
    level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# --- Define Prompts Directory Path --- #
# Calculate the absolute path to the prompts directory relative to this file's location
PROMPTS_DIR = Path(__file__).parent.parent / "prompts"
logger.info(f"Resolved prompts directory path: {PROMPTS_DIR}")

# --- LiteLLM Support ---
# Attempts to import LiteLLM for broader model support.
# Logs a warning if LiteLLM is not installed or available.
try:
    from google.adk.models.lite_llm import LiteLlm
    LITE_LLM_AVAILABLE = True
    logger.info("LiteLLM support is available. Fallback models can be used.")
except ImportError:
    LITE_LLM_AVAILABLE = False
    logger.warning(
        "LiteLLM support is not available. Install 'google-adk[litellm]' "
        "for fallback model capabilities."
    )

# --- Agent Imports ---
# Imports the different specialized agents used in the system.
from .sub_agents.core_agent import CoreAgent
#from .sub_agents.coding_agent import CodingAgent
from .sub_agents.dialog_agent import DialogAgent
from .sub_agents.input_classifier_agent import InputClassifierAgent
from .sub_agents.resolver_agent import ResolverAgent
# Import safety callback if used for model inspection/moderation
# from .sub_agents.safety_callback import model_inspection_callback

# --- Tool Imports ---
# Imports the tools that allow the agents to interact with the Android device
# or query information.
from .tools import RequestNodesByTextTool, RequestInteractiveNodesTool, RequestAllNodesTool, RequestClickableNodesTool
# from .tools.ui_dump_tool import get_latest_ui_dump # Keep if UI dump functionality is needed

# --- Configuration & Initialization ---

# API Keys: Fetches API keys from environment variables.
# It's crucial to set these for the agents to function.
api_key = os.environ.get("GEMINI_API_KEY")
if not api_key:
    logger.warning("GEMINI_API_KEY environment variable not set. Agents requiring it might fail.")

# Model Names: Defines constants for commonly used model names.
# Allows easy switching between models.
MODEL_GEMINI_FLASH = "gemini-1.5-flash-8b"
MODEL_GEMINI_PREVIEW = "gemini-2.5-flash-preview-04-17" # Example preview model
MODEL_GEMINI_PRO = "gemini-2.5-pro-preview-03-25"
#MODEL_GPT_TURBO = "openai/gpt-3.5-turbo" # Example LiteLLM model ID
#MODEL_CLAUDE_SONNET = "anthropic/claude-3-sonnet-20240229" # Example LiteLLM model ID

# Common Android App Packages: A helper dictionary mapping common app names
# to their package identifiers.
# CORRECTED: Replaced C-style comment with Python comment.
# _COMMON_APP_PACKAGES = {
#     "settings": "com.android.settings",
#     # Add more common package names here as needed
#     # e.g., "calculator": "com.google.android.calculator",
# }

# --- START: Tool Instantiation (Moved Before Agent Instantiation) ---
# Creates instances of the imported tools.
global_tools_dict = {
    tool.name: tool
    for tool in [
        RequestNodesByTextTool(),
        RequestInteractiveNodesTool(),
        RequestAllNodesTool(),
        RequestClickableNodesTool(),
    ]
}
for name in global_tools_dict:
    logger.info(f"Tool initialized: {name}")
logger.info(f"Successfully initialized global_tools: {list(global_tools_dict.keys())}")
# --- END: Tool Instantiation ---


# --- Agent Instantiation ---
# Creates instances of the imported agents, passing necessary configurations
# like API keys and prompt file paths. Includes error handling for initialization.

agents: Dict[str, Any] = {} # Initialize agent dictionary

# === Core Agent ===
# Core Agent initialization (now uses BaseAgent's logic)
try:
    # No need to pass api_key, model_name, or prompt_file explicitly
    # BaseAgent handles defaults or gets from config
    core_agent = CoreAgent(prompts_base_dir=PROMPTS_DIR)
    agents["core"] = core_agent
    logger.info("CoreAgent initialized successfully.")
except Exception as e:
    logger.error(f"Failed to initialize CoreAgent: {e}", exc_info=True)


# === Resolver Agent ===
try:
    if not api_key:
        raise ValueError("ResolverAgent requires GEMINI_API_KEY but it was not found.")
    resolver_agent = ResolverAgent(
        api_key=api_key,
        tools=list(global_tools_dict.values()) # Pass updated tool instances list
    )
    agents["resolver"] = resolver_agent
    logger.info("ResolverAgent initialized successfully.")
except Exception as e:
    logger.error(f"Failed to initialize ResolverAgent: {e}", exc_info=True)


# === Dialog Agent ===
try:
    dialog_agent = DialogAgent(
        prompts_base_dir=PROMPTS_DIR # BaseAgent needs this
    )
    agents["dialog"] = dialog_agent
    logger.info("DialogAgent initialized successfully.")
except Exception as e:
    logger.error(f"Failed to initialize DialogAgent: {e}", exc_info=True)


# === Input Classifier Agent ===
try:
    input_classifier_agent = InputClassifierAgent(
        prompts_base_dir=PROMPTS_DIR # BaseAgent needs this
    )
    agents["input_classifier"] = input_classifier_agent
    logger.info("InputClassifierAgent initialized successfully.")
except Exception as e:
    logger.error(f"Failed to initialize InputClassifierAgent: {e}", exc_info=True)


# --- Final Logging ---
logger.info(f"Successfully initialized agents: {list(agents.keys())}")
logger.info(f"Successfully initialized global_tools: {list(global_tools_dict.keys())}")
if not agents:
    logger.warning("No agents were successfully initialized.")
if not global_tools_dict:
    logger.warning("No global tools were successfully initialized.")


# --- Utility Functions ---
# Provide convenient access to initialized agents and tools.

def get_agent(agent_name: str) -> Optional[Any]:
    """Retrieve an initialized agent instance by name."""
    return agents.get(agent_name)

def get_tool(tool_name: str) -> Optional[Any]: # Keep Any for now
    """Retrieve an initialized global tool instance by name."""
    return global_tools_dict.get(tool_name) # Use the dict here

# --- Root Agent Definition ---
# The concept of a single 'root_agent' might be handled differently,
# potentially in the main application logic (e.g., main.py) which decides
# which agent to invoke based on input or state.
# Setting it to None here signifies that the routing logic is external.
root_agent: Optional[Any] = None
logger.info("Agent definitions loaded. Routing logic expected in main application.")

# --- End of agent.py ---
