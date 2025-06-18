"""
Dialog Agent: Handles communication with the user.
"""

import os
import json
import logging
from typing import Dict, Any, Optional, List, ClassVar, Union
from pathlib import Path
import textwrap

# Import ADK Agent instead of BaseAgent
# from google.adk.agents import Agent
# from google.adk.runners import Runner # Import Runner
from google.adk.sessions import InMemorySessionService # For type hint
from google.adk.artifacts import BaseArtifactService # For type hint
from google.genai import types as genai_types # For Content/Part types

# Import the safety callbacks
from .safety_callback import model_inspection_callback, after_model_callback
# --- Import BaseAgent --- #
from .base_agent import BaseAgent # <-- ADD
# --- Import Constants Directly from Config ---
try:
    from main_files.config import APP_NAME, USER_ID
    logger = logging.getLogger(__name__)
except ImportError:
    logger = logging.getLogger(__name__)
    logger.error("Could not import APP_NAME, USER_ID from main_files.config in DialogAgent. Using defaults.")
    APP_NAME = "default_app"
    USER_ID = "default_user"

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
# logger = logging.getLogger(__name__) # Already defined above

class DialogAgent(BaseAgent): # <-- Inherit from BaseAgent
    """
    Agent responsible for managing conversation flow with the user.
    Uses Google ADK Agent class directly, invoked via Runner.
    """
    AGENT_TYPE: ClassVar[str] = "dialog" # Define agent type

    def __init__(
        self,
        prompts_base_dir: Optional[Union[str, Path]] = None,
        # Removed api_key, prompt_file, agent_type
        **kwargs
    ):
        """Initializes the DialogAgent."""
        super().__init__(prompts_base_dir=prompts_base_dir, **kwargs) # Pass relevant args to BaseAgent

        # --- ADD: Load DialogAgent specific prompt --- #
        if self._prompts_base_dir: # Check if base dir was set
             prompt_filename = f"{self.AGENT_TYPE}_agent.md"
             self.prompt_template_text = self._load_prompt_template(prompt_filename)
             if not self.prompt_template_text:
                  logger.error("DialogAgent failed to load its specific prompt!")
                  self.prompt_template_text = "ERROR: DialogAgent prompt load failed."
        else:
             logger.error("DialogAgent cannot load prompt, prompts_base_dir not set by BaseAgent.")
             self.prompt_template_text = "ERROR: prompts_base_dir missing."

        logger.info(
            f"DialogAgent initialized. Inherited model: '{self.model_name}'. "
            f"Prompt loaded: {bool(self.prompt_template_text and 'ERROR' not in self.prompt_template_text)}"
        )
        # BaseAgent handles model initialization

    async def generate_dialog_response(self, 
                                     user_message: str, 
                                     conversation_history: Optional[List[Dict[str, str]]] = None,
                                     # Add session_id for runner context
                                     # session_id: Optional[str] = None
                                     ) -> Dict[str, Any]:
        """
        Generates a conversational response using the Runner.
        Generates a conversational response using BaseAgent.process_with_retry.
        """
        try:
            # 1. Prepare the prompt string
            prompt_parts = [self.prompt_template_text] # Start with base instruction
            if conversation_history:
                prompt_parts.append("\n\n--- History ---\n")
                for msg in conversation_history:
                    role = msg.get("role", "user")
                    content = msg.get("content", "")
                    prompt_parts.append(f"{role.capitalize()}: {content}\n")
                prompt_parts.append("---------------\n")
            prompt_parts.append(f"\nUser: {user_message}\n")
            prompt_parts.append("\nAssistant (JSON Response):\n") # Ask for JSON
            full_prompt = "\n".join(prompt_parts)

            # Define a simple JSON parser function
            def _parse_json(text: str) -> Any:
                # Strip potential markdown fences
                clean_text = text.strip().removeprefix('```json').removesuffix('```').strip()
                try:
                    return json.loads(clean_text)
                except json.JSONDecodeError:
                    logger.warning(f"DialogAgent: Failed to decode JSON after cleaning: {clean_text[:100]}...")
                    # Return the raw text or a specific error structure if preferred
                    return {"error": "Invalid JSON format received", "raw_text": text} # Return original text in error

            # 2. Call LLM using BaseAgent method
            llm_result = await self.process_with_retry(
                prompt_content=full_prompt,
                parser_func=_parse_json
                # Use default temperature from BaseAgent config
            )

            # 3. Process the result
            if llm_result.get("success"):
                parsed_content = llm_result.get("content")
                if isinstance(parsed_content, dict) and "message" in parsed_content:
                    final_reply = parsed_content["message"]
                    logger.info(f"Dialog Agent generated response: {final_reply[:100]}...")
                    return {"success": True, "type": "response", "message": final_reply}
                else:
                    logger.error(f"Dialog Agent received success but invalid content format: {parsed_content}")
                    return {"success": False, "type": "error", "message": "Agent returned invalid format."}
            else:
                error_msg = llm_result.get("error", "Dialog agent LLM call failed.")
                logger.error(f"DialogAgent failed: {error_msg}")
                return {"success": False, "type": "error", "message": error_msg}
            # --- REFACTOR END ---

            # OLD RUNNER LOGIC:
            # # 1. Prepare the new message Content object
            # try:
            #     current_message_content = genai_types.Content(role="user", parts=[genai_types.Part(text=user_message)])
            # except Exception as current_msg_err:
            #     logger.error(f"Error creating Content object for current message: {current_msg_err}", exc_info=True)
            #     return {"success": False, "type": "error", "message": "Internal error preparing message."}
            #
            # # 2. Create a Runner instance for this call
            # # (Could optimize by creating runner once if services/agent don't change)
            # runner = Runner(
            #      agent=self.model,
            #      session_service=self.session_service,
            #      artifact_service=self.artifact_service, # Can be None if not used
            #      app_name=self.app_name 
            # )
            # 
            # # 3. Call runner.run_async with keyword arguments
            # response_generator = runner.run_async(
            #     session_id=session_id,
            #     user_id=self.user_id, # Use the user_id stored during init
            #     new_message=current_message_content
            # )
            # 
            # # 4. Iterate to get the final response event
            # final_response_text = None
            # async for event in response_generator:
            #      # Optional: log events for debugging
            #      # logger.debug(f"DialogAgent Runner Event: {event}")
            #      if event.is_final_response():
            #          if event.content and event.content.parts:
            #              final_response_text = event.content.parts[0].text
            #          break # Got the final response
            # 
            # message = final_response_text or "" # Use the extracted text
            #  
            # if message:
            #     logger.info(f"Dialog Agent generated response: {message[:100]}...")
            #     return {"success": True, "type": "response", "message": message}
            # else:
            #     logger.error("Dialog Agent returned empty response")
            #     return {"success": False, "type": "error", "message": "Empty response from dialog agent"}

        except Exception as e:
            logger.exception(f"Error during dialog response generation: {e}")
            return {"success": False, "type": "error", "message": f"An unexpected error occurred: {str(e)}"}

    # async def format_response(self, <-- REMOVE METHOD START
    #                           status_update: Dict[str, Any],
    #                           conversation_history: Optional[List[Dict[str, str]]] = None
    #                          ) -> Dict[str, Any]:
    #     """
    #     Formats a status update or result into a user-friendly message.
    #
    #     Args:
    #         status_update: A dictionary containing the status or result details.
    #         conversation_history: Previous turns in the conversation (optional).
    #
    #     Returns:
    #         A dictionary containing the user-facing message.
    #         Example: {"success": True, "message": "Okay, I've opened the Settings app."}
    #     """
    #     if not self.agent:
    #         logger.warning("Dialog agent not available. Returning raw status.")
    #         return {"success": True, "message": json.dumps(status_update), "is_mock": True}
    #
    #     try:
    #         # Prepare the conversation history for the agent
    #         formatted_history = []
    #         if conversation_history:
    #             for msg in conversation_history:
    #                 formatted_history.append({
    #                     "role": msg.get("role", "user"),
    #                     "content": msg.get("content", "")
    #                 })
    #         
    #         # Add the status update as a system message
    #         status_json = json.dumps(status_update, indent=2)
    #         formatted_history.append({
    #             "role": "system",
    #             "content": f"Status update: {status_json}\\nPlease format this into a user-friendly message."
    #         })
    #         
    #         # Call the ADK agent
    #         response = await self.agent.run_async(history=formatted_history)
    #         message = response.content.parts[0].text if response and response.content and response.content.parts else ""
    #         
    #         if message:
    #             # Clean up message - remove JSON formatting if present
    #             import re
    #             clean_message = re.sub(r'```json.*?```', '', message, flags=re.DOTALL)
    #             clean_message = re.sub(r'^\\s*{\\s*\"message\"\\s*:\\s*\"(.*?)\"\\s*}\\s*$', r'\\1', clean_message.strip())
    #             
    #             logger.info(f"Dialog Agent formatted message: {clean_message[:100]}...")
    #             return {"success": True, "message": clean_message}
    #         else:
    #             logger.error("Dialog Agent returned empty formatted response")
    #             return {"success": False, "message": "Failed to format status update"}
    #
    #     except Exception as e:
    #         logger.exception(f"Error during response formatting: {e}")
    #         return {"success": False, "message": f"An error occurred while formatting response: {str(e)}"}
    # <-- REMOVE METHOD END

# Example usage (for testing purposes)
# async def main():
#     # ... setup ...
#     dialog_agent = DialogAgent(api_key=api_key, debug_mode=True)
#     
#     # Test clarification
#     clarification = await dialog_agent.clarify_goal("Do the thing with the settings")
#     print(f"Clarification Result: {clarification}")
#     
#     # Test formatting
#     status = {"type": "execution_result", "success": True, "message": "Action completed."}
#     formatted = await dialog_agent.format_response(status)
#     print(f"Formatted Result: {formatted}")
# 
# if __name__ == "__main__":
#     # ... run async main ... 