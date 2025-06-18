# adk-droid/android_use_agent/sub_agents/core_agent_files/core_agent_utils.py
"""
Utility functions for the CoreAgent, including error response generation.
"""
import logging
from typing import Dict, Any

logger = logging.getLogger(__name__)

def create_error_response(error_message: str) -> Dict[str, Any]:
    """
    Creates a standardized dictionary representing an error state,
    conforming loosely to the CoreAgentOutput structure for graceful failure.

    Args:
        error_message: The description of the error.

    Returns:
        A dictionary containing error information and a suggested recovery action.
    """
    logger.error(f"Creating error response: {error_message}")
    # Ensure the error message is concise for the reasoning fields
    concise_error = error_message[:200] + ('...' if len(error_message) > 200 else '')
    return {
        "reasoning": {
            "evaluation_previous_action": f"ERROR: {concise_error}",
            "memory_update": "ERROR: Processing failed. Cannot proceed reliably.",
            "next_sub_goal": "Attempting generic recovery (e.g., Go Back) or requires user intervention."
        },
        "actions": [{
            # Default recovery action: Go Back
            "perform_global_action": {
                "action_id": "GLOBAL_ACTION_BACK"
            }
            # Alternative: Return a 'done' action with an error message
            # "done": {
            #     "message": f"Failed to complete task due to error: {concise_error}"
            # }
        }]
    }

# Placeholder for other potential utility functions needed by the CoreAgent
