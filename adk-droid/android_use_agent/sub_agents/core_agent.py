# adk-droid/android_use_agent/sub_agents/core_agent.py
"""
Core Agent: Orchestrates the main Observe->Reason->Act loop using modular helpers.
"""
import os
import json
import logging
import base64 # <-- Import base64
from typing import Dict, Any, Optional, List, ClassVar, Union
from pathlib import Path
import asyncio

# --- Import Compatibility Layer & Base Agent ---
# Assumes adk_compat provides necessary base classes and types
try:
    # Go up one level to android_use_agent, then import adk_compat and models
    # from ..adk_compat import LlmAgent, LlmRequest, ToolConfig <-- REMOVE THIS
    from google.adk.agents import LlmAgent # <-- ADD
    from google.adk.models import LlmRequest # <-- ADD
    # from google.adk.tools import ToolConfig # <-- REMOVE THIS LINE
    from ..models import CoreAgentOutput # Import the specific output model
    from .safety_callback import model_inspection_callback # Import the callback
except ImportError as e:
    logging.error(f"CoreAgent: Failed base imports: {e}. Using dummy classes.")
    # Define dummy classes if imports fail
    class LlmAgent: pass
    # Define dummy CoreAgentOutput if the real one fails to import
    class CoreAgentOutput:
        @classmethod
        def model_validate(cls, data): return data # Simple pass-through dummy
    def model_inspection_callback(*args, **kwargs): pass

# --- Import shared config/constants ---
from ..task_manager_files import task_constants # <-- ADD
from . import model_config # <-- ADD
from typing import ClassVar
from .base_agent import BaseAgent # <<< IMPORT BaseAgent

# --- Import Modularized Components ---
# try:
#     # Import from the core_agent_files sub-package
#     from .core_agent_files import core_agent_config as config
#     from .core_agent_files import core_agent_prompting as prompting
#     from .core_agent_files import core_agent_parser as parser
#     from .core_agent_files import core_agent_utils as utils
# except ImportError as e:
#     logging.error(f"CoreAgent: Failed modular imports from core_agent_files: {e}. Agent will likely fail.")
#     # Define dummy modules/functions if imports fail
#     from pathlib import Path
#     class config: DEFAULT_MODEL_NAME="dummy"; DEFAULT_AGENT_TYPE="core"; get_prompt_path=lambda:Path("dummy"); load_prompt=lambda p:"dummy"
#     class prompting: build_prompt_context=lambda *a, **kw: "dummy"
#     class parser: parse_and_validate_response=lambda t: {"reasoning":{}, "actions":[]}
#     class utils: create_error_response=lambda m: {"reasoning":{"error":m},"actions":[]}


logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


class CoreAgent(BaseAgent):
    """
    Unified agent for observation -> reasoning -> action generation.
    Inherits common logic (model init, prompt loading, retry) from BaseAgent.
    """
    AGENT_TYPE: ClassVar[str] = "core"

    def __init__(self,
                 api_key: Optional[str] = None, # Argument kept for consistency, but BaseAgent handles primary SDK init
                 prompt_file: Optional[str] = None,
                 model_name: Optional[str] = None, # Allow override for BaseAgent
                 prompts_base_dir: Optional[Union[str, Path]] = None) -> None:
        """
        Initialize the CoreAgent.
        """
        # --- Call BaseAgent's __init__ FIRST ---
        # Pass prompts_base_dir. BaseAgent uses self.AGENT_TYPE ('core')
        # to determine the model via model_config.
        # If model_name is provided, it overrides the one from model_config.
        super().__init__(prompts_base_dir=prompts_base_dir)
        # Note: BaseAgent.__init__ now sets self.model_name and self._prompts_base_dir
        logger.info(f"CoreAgent: BaseAgent initialized. Model: {self.model_name}, Prompts Base: {self._prompts_base_dir}")

        # --- Load the prompt *after* BaseAgent init ---*
        effective_prompt_filename = prompt_file if prompt_file else f"{self.AGENT_TYPE}_agent.md"
        if hasattr(self, '_load_prompt_template'):
            self.prompt_template_text = self._load_prompt_template(effective_prompt_filename)
            if not self.prompt_template_text or "ERROR:" in self.prompt_template_text:
                 logger.error(f"CoreAgent prompt '{effective_prompt_filename}' was NOT loaded successfully by BaseAgent.")
                 self.prompt_template_text = "ERROR: CoreAgent prompt could not be loaded."
                 prompt_loaded_ok = False
            else:
                 logger.info(f"CoreAgent prompt '{effective_prompt_filename}' loaded successfully by BaseAgent.")
                 prompt_loaded_ok = True
        else:
             logger.error("BaseAgent does not have _load_prompt_template method. Cannot load CoreAgent prompt.")
             self.prompt_template_text = "ERROR: Prompt loading mechanism missing."
             prompt_loaded_ok = False

        logger.info(f"CoreAgent initialized. Inherited model: '{self.model_name}'. Prompt loaded: {prompt_loaded_ok}")

    async def process(
        self,
        goal: str,
        conversation_history: Optional[List[Dict[str, Any]]] = None,
        last_action_result: Optional[Dict[str, Any]] = None,
        screenshot_base64: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        Processes the input context to generate reasoning and actions using the LLM.
        The agent is expected to request tool use if vision analysis is needed.
        Args:
            goal: User goal to process.
            conversation_history: Optional previous conversation turns.
            last_action_result: Optional result of the last action (dict expected).
            screenshot_base64: Optional base64 encoded string of the screenshot.
        Returns:
            Dict containing reasoning and actions or a tool_calls request.
        """
        if not goal:
            logger.error("CoreAgent process called with empty goal.")
            return self._create_error_response("Empty goal provided.")

        logger.info(f"CoreAgent processing goal: {goal[:50]}{'...' if len(goal) > 50 else ''}")

        # --- REMOVED Internal Vision Analysis --- 
        # The LLM should request the tool based on the prompt and screenshot_base64 input.
        # Initialize vision_rois to None (or don't pass it to build_prompt if not needed)
        vision_rois = None # If build_prompt_context expects it, otherwise remove

        # Build prompt context using the new internal helper method
        try:
            # Assemble prompt parts - Note: Screenshot bytes/base64 are handled by the LLM's multimodal input capability directly
            # The text prompt needs to reference that the image is provided.
            # Assuming the base instruction (self.instruction) already tells the model to expect an image.
            prompt_text_parts = self._build_prompt( # Call internal helper
                goal=goal,
                conversation_history=conversation_history,
                last_action_result=last_action_result
                # vision_analysis_result=vision_rois # Removed, LLM sees image directly
            )

            # Prepare multimodal content if screenshot is provided
            if screenshot_base64:
                try:
                    image_bytes = base64.b64decode(screenshot_base64)
                    image_part = {"mime_type": "image/png", "data": image_bytes} # Assuming PNG
                    # Prepend the text prompt part
                    prompt_content = [prompt_text_parts, image_part]
                except Exception as img_err:
                    logger.error(f"Failed to decode/prepare screenshot for prompt: {img_err}")
                    return self._create_error_response(f"Invalid screenshot data: {img_err}") # Use helper
            else:
                # Text-only prompt
                prompt_content = prompt_text_parts

        except Exception as build_err:
            logger.exception(f"Error building prompt context: {build_err}")
            return self._create_error_response(f"Failed to build prompt context: {str(build_err)}") # Use helper

        # Make the LLM request
        try:
            logger.debug(f"[{goal[:20]}...] About to call self.llm_request with content type: {type(prompt_content)}")
            response_text = await self.llm_request(prompt_content) # Pass prepared content
            logger.debug(f"[{goal[:20]}...] Returned from self.llm_request")
        except Exception as llm_err:
            logger.error(f"LLM request failed during processing: {llm_err}")
            return self._create_error_response(f"LLM request failed: {str(llm_err)}") # Use helper

        # Parse and validate the response using the new internal helper method
        try:
            validated_output_model = self._parse_response(response_text) # Call internal helper
            # Return the validated output as a dictionary
            return validated_output_model.model_dump()
        except Exception as parse_err:
            logger.exception(f"Failed to parse/validate LLM response: {parse_err}")
            return self._create_error_response(f"Failed to parse/validate LLM response: {str(parse_err)}") # Use helper

    # --- ADDED Private Helper Methods --- #
    def _build_prompt(
        self,
        goal: str,
        conversation_history: Optional[List[Dict[str, Any]]] = None,
        last_action_result: Optional[Dict[str, Any]] = None,
    ) -> str:
        """Builds the full prompt context string for the LLM."""
        prompt_parts = [self.instruction]

        # Add conversation history (if any)
        if conversation_history:
            prompt_parts.append("\n\n--- Conversation History ---")
            for turn in conversation_history:
                role = turn.get("role")
                content = turn.get("content")
                if role and content:
                    prompt_parts.append(f"{role.capitalize()}: {content}")
            prompt_parts.append("--------------------------")

        # Add last action result (if any)
        if last_action_result:
            try:
                result_str = json.dumps(last_action_result, indent=2)
                prompt_parts.append(f"\n\n--- Previous Action Result ---\n```json\n{result_str}\n```")
            except Exception as e:
                logger.warning(f"Could not format last action result for prompt: {e}")
                prompt_parts.append(f"\n\n--- Previous Action Result ---\n[Error formatting result: {e}]")
            prompt_parts.append("--------------------------")

        # Add the current goal and screenshot placeholder
        # The base_instruction should tell the model to expect the screenshot implicitly
        prompt_parts.append(f"\n\n--- Current Goal ---\n{goal}")
        prompt_parts.append("--------------------")
        prompt_parts.append("\nAnalyze the provided screenshot and the context above to determine the next single semantic action, direct action, tool call, or control flow step required to achieve the goal. Output ONLY the JSON structure specified in the initial instructions.")

        return "\n".join(prompt_parts)

    def _parse_response(self, response_text: str) -> CoreAgentOutput:
        """Parses and validates the LLM's JSON response text."""
        # Simple extraction: assumes JSON is the main content
        # More robust parsing might strip markdown fences (```json ... ```)
        clean_text = response_text.strip()
        if clean_text.startswith("```json"):
            clean_text = clean_text[7:]
        if clean_text.endswith("```"):
            clean_text = clean_text[:-3]
        clean_text = clean_text.strip()

        # --- ADDED LOGGING ---
        logger.debug(f"Raw LLM response text (cleaned):\\n>>>\\n{clean_text}\\n<<<")
        # --- END ADDED LOGGING ---
        
        try:
            response_dict = json.loads(clean_text)
            # Validate using the Pydantic model
            validated_output = CoreAgentOutput.model_validate(response_dict)
            return validated_output
        except json.JSONDecodeError as json_err:
            logger.error(f"Failed to decode LLM JSON response: {json_err}. Raw text:\\n{response_text}")
            raise ValueError(f"LLM response was not valid JSON: {json_err}") from json_err
        except Exception as val_err: # Catch Pydantic validation errors etc.
            # Log the raw text that caused the validation error
            logger.error(f"Failed to validate LLM response structure: {val_err}. Raw response text:\\n>>>\\n{response_text}\\n<<<")
            # Log the dictionary if parsing was successful before validation failed
            logger.error(f"Parsed dict before validation error: {response_dict if 'response_dict' in locals() else '<JSON parsing failed>'}")
            raise ValueError(f"LLM response structure invalid: {val_err}") from val_err

    def _create_error_response(self, error_message: str) -> Dict[str, Any]:
        """Creates a standard error response dictionary."""
        # Mimic the structure of CoreAgentAction for consistency downstream
        return {
            # Use default/empty reasoning state on error
            "reasoning": {
                "evaluation_previous_action": "Unknown - Error occurred",
                "visual_analysis": "Error processing request.",
                "next_sub_goal": "None - Error occurred",
                "assertion": "None - Error occurred",
            },
            # Return a specific 'error' action
            "actions": [
                {
                    "action_type": "error", # Define a specific error action type
                    "parameters": {"message": error_message},
                }
            ],
        }
    # --- END Private Helper Methods --- #

    @property
    def instruction(self) -> str:
        """Returns the loaded prompt template text."""
        return self.prompt_template_text if hasattr(self, 'prompt_template_text') else "ERROR: Prompt not loaded."

# Note: The example usage (__main__ block) has been removed for cleaner modularization.
# Testing should be done via integration tests or by running the main application.
