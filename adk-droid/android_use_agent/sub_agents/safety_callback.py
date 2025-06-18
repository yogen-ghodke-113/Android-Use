"""
Safety callback implementation for ADK agents.
Provides guardrails for model requests and responses.
"""

import logging
from google.adk.agents.callback_context import CallbackContext
from google.adk.models.llm_request import LlmRequest
from google.adk.models.llm_response import LlmResponse
from google.genai import types
from typing import Optional, List, Dict, Any

logger = logging.getLogger(__name__)

def model_inspection_callback(
    callback_context: CallbackContext, llm_request: LlmRequest
) -> Optional[LlmResponse]:
    """
    Inspects model requests for potentially problematic content and 
    applies safety guardrails before sending to the model.
    
    Args:
        callback_context: Context information about the current agent and state
        llm_request: The request that will be sent to the LLM
        
    Returns:
        None to proceed with normal LLM processing, or an LlmResponse to override
    """
    agent_name = callback_context.agent_name
    logger.debug(f"Safety callback inspecting request for agent: {agent_name}")
    
    # Check if this is a sensitive agent that should have extra guardrails
    # For example, agents that might execute code or automation actions
    sensitive_agents = ["core", "coding"]
    
    # Extract the latest user message for inspection
    last_user_message = ""
    if llm_request.contents:
        for content in reversed(llm_request.contents):
            if content.role == 'user' and content.parts:
                if hasattr(content.parts[0], 'text') and content.parts[0].text:
                    last_user_message = content.parts[0].text
                    break
    
    # List of problematic patterns to watch for
    blocked_commands = [
        "format phone",
        "factory reset",
        "delete all",
        "wipe data"
    ]
    
    # Check for blocked commands in sensitive agents
    if agent_name in sensitive_agents:
        for command in blocked_commands:
            if command.lower() in last_user_message.lower():
                logger.warning(f"Blocked potentially harmful command '{command}' to {agent_name}")
                
                # Record the event in agent state
                callback_context.state["safety_guardrail_triggered"] = True
                callback_context.state["blocked_command"] = command
                
                # Return a safe response instead of proceeding to the model
                return LlmResponse(
                    content=types.Content(
                        role="model",
                        parts=[types.Part(text=f"For safety reasons, I cannot process requests related to '{command}'. This is a protective measure to prevent accidental data loss or device damage.")]
                    )
                )
    
    # If we reach here, no issues were found
    return None  # Continue normal processing

def after_model_callback(
    callback_context: CallbackContext, llm_response: LlmResponse
) -> Optional[LlmResponse]:
    """
    Post-processes model responses to add disclaimers or perform sanitization.
    
    Args:
        callback_context: Context information about the current agent and state
        llm_response: The response received from the LLM
        
    Returns:
        None to use the original response, or a modified LlmResponse
    """
    agent_name = callback_context.agent_name
    logger.debug(f"After-model callback processing response from agent: {agent_name}")
    
    # Check if we need to modify this agent's responses
    if agent_name == "dialog":
        # For dialog agent, add attribution and disclaimer
        
        # Get the original response text
        original_text = ""
        if llm_response.content and llm_response.content.parts:
            for part in llm_response.content.parts:
                if hasattr(part, 'text') and part.text:
                    original_text = part.text
                    break
        
        # Add a subtle attribution footer for user messages
        if original_text:
            # Check if response should include a disclaimer
            high_risk_topics = ["medical", "health", "legal", "finance"]
            needs_disclaimer = any(topic in original_text.lower() for topic in high_risk_topics)
            
            if needs_disclaimer:
                disclaimer = "\n\n_Note: This is an AI assistant for Android automation. " \
                            "For professional advice on health, legal, or financial matters, " \
                            "please consult with qualified experts._"
                modified_text = original_text + disclaimer
                
                # Create a new response with the modified text
                return LlmResponse(
                    content=types.Content(
                        role="model",
                        parts=[types.Part(text=modified_text)]
                    )
                )
    
    # For CoreAgent, we could potentially sanitize actions if needed
    # This would involve parsing the JSON response and modifying action parameters
    
    # By default, return None to use the original response
    return None 