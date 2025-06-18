"""
Utility module for standardizing model configuration across all agents using LiteLLM.
"""

import logging
import os
from typing import Dict, Any, Optional

logger = logging.getLogger(__name__)

# Define standard safety settings to block none
SAFETY_SETTINGS_BLOCK_NONE = [
    {
        "category": "HARM_CATEGORY_HARASSMENT",
        "threshold": "BLOCK_NONE",
    },
    {
        "category": "HARM_CATEGORY_HATE_SPEECH",
        "threshold": "BLOCK_NONE",
    },
    {
        "category": "HARM_CATEGORY_SEXUALLY_EXPLICIT",
        "threshold": "BLOCK_NONE",
    },
    {
        "category": "HARM_CATEGORY_DANGEROUS_CONTENT",
        "threshold": "BLOCK_NONE",
    },
]

# LiteLLM Model Definitions (using gemini/ prefix for Gemini API Key usage)
# See LiteLLM docs for other provider prefixes (e.g., openai/, anthropic/)
# https://docs.litellm.ai/docs/providers
# https://docs.litellm.ai/docs/providers/gemini (specifically for gemini/ prefix)
MODELS = {
    "reasoning_light": {
        "model_name": "gemini/gemini-2.5-flash-preview-04-17", # Example using 1.5 Pro via API key
        "generation_config": {
            "temperature": 0.3,
            "top_p": 0.95,
            # "top_k": 40, # Often not needed with top_p
            "max_tokens": 8192, # Use max_tokens for LiteLLM
        }
    },
    "reasoning_flash": {
        "model_name": "gemini/gemini-2.0-flash", # Example using 1.5 Flash via API key
        "generation_config": {
            "temperature": 0.3,
            "top_p": 0.95,
            "max_tokens": 4096,
        }
    },
    "lightweight": {
        "model_name": "gemini/gemini-1.5-flash-8b", # Defaulting lightweight tasks to Flash
        "generation_config": {
            "temperature": 0.2,
            "top_p": 0.95,
            "max_tokens": 2048,
        }
    },
    # Example using a different provider (requires OPENAI_API_KEY env var)
    # "openai_gpt4o": {
    #     "model_name": "openai/gpt-4o",
    #     "generation_config": {
    #         "temperature": 0.5,
    #         "max_tokens": 4096,
    #     }
    # }
}

# Agent to model mapping
AGENT_MODELS = {
    "core": "reasoning_light",    # Core agent uses a light reasoning model
    "dialog": "lightweight",       # Dialog uses a fast model
    "input_classifier": "lightweight", # Classifier needs to be fast
    "resolver": "reasoning_light", # Resolver uses flash reasoning model for tool calling
    # Add mappings for other agent types if needed
}

def get_model_config(agent_type: str) -> Dict[str, Any]:
    """
    Get the appropriate LiteLLM model name and generation configuration for a specific agent.

    Args:
        agent_type: The type of agent (must be one of the keys in AGENT_MODELS)

    Returns:
        A dictionary containing 'model_name' and 'generation_config'.
        Returns a default config if agent_type is unknown.
    """
    if agent_type not in AGENT_MODELS:
        logger.warning(f"Unknown agent type: {agent_type}. Defaulting to 'lightweight' model config.")
        model_category = "lightweight"
    else:
        model_category = AGENT_MODELS[agent_type]

    # Ensure the mapped model_category exists in MODELS
    if model_category not in MODELS:
        logger.error(f"Model category '{model_category}' defined for agent '{agent_type}' not found in MODELS. Defaulting to 'lightweight'.")
        model_category = "lightweight"

    # Retrieve the specific model info
    model_info = MODELS.get(model_category) # Use .get for safety, though we defaulted

    if not model_info:
        # This should technically not happen due to the default above, but as a safeguard:
        logger.error(f"FATAL: Default model category 'lightweight' not found in MODELS dict! Returning empty config.")
        return {"model_name": "unknown", "generation_config": {}}

    # Return the selected model name and its generation config
    # LiteLLM expects generation parameters directly, so we return the nested dict
    return {
        "model_name": model_info["model_name"],
        "generation_config": model_info["generation_config"]
    }

# Example of how to potentially get API key (though BaseAgent will handle this)
# def get_api_key_for_model(model_name: str) -> Optional[str]:
#     """Gets the appropriate API key based on the LiteLLM model prefix."""
#     if model_name.startswith("gemini/"):
#         return os.getenv("GEMINI_API_KEY")
#     elif model_name.startswith("openai/"):
#         return os.getenv("OPENAI_API_KEY")
#     # Add other providers as needed
#     else:
#         logger.warning(f"No specific API key found for provider in model: {model_name}")
#         return None # LiteLLM might handle ADC or other methods if key is None 

CORE_AGENT_LLM_CONFIG = {
    "model_name": "gemini/gemini-2.5-flash-preview-04-17",
    "generation_config": {
        "temperature": 0.3, # Lower for more deterministic actions
        "top_p": 0.95,
        "max_tokens": 8192, # Increased from 4096
    },
    "safety_settings": SAFETY_SETTINGS_BLOCK_NONE,
    # Add other necessary configs like API key source if not handled globally
}

RESOLVER_AGENT_LLM_CONFIG = {
    "model_name": "gemini/gemini-1.5-flash-8b", # Fast and cheap for tool use/resolution
    "generation_config": {
        "temperature": 0.3,
        "top_p": 0.95,
        "max_tokens": 4096,
    },
    "safety_settings": SAFETY_SETTINGS_BLOCK_NONE,
} 