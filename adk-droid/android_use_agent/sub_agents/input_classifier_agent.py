# adk-droid/android_use_agent/sub_agents/input_classifier_agent.py
import logging
import json # Added import for JSON parsing
from typing import Any, Dict, List, Optional, Tuple, Union, ClassVar
from pathlib import Path

# from google.generativeai import FunctionDeclaration # Remove this
# from google.generativeai import protos # Remove this
# Assuming BaseAgent or similar structure is used, adjust if needed
from .base_agent import BaseAgent
# Or directly from google.adk.agent import LlmAgent
# from google.adk.agent import LlmAgent

logger = logging.getLogger(__name__)

class InputClassifierAgent(BaseAgent): # Or inherit from LlmAgent
    """
    An agent responsible for classifying user input as 'Chat' or 'Task'
    and extracting the core goal if it's a task.
    """
    AGENT_TYPE: ClassVar[str] = "input_classifier"

    DEFAULT_MODEL = "gemini-1.5-flash-8b" # Updated as per request
    DEFAULT_PROMPT_FILE = "input_classifier_agent.md"

    # Add initialization if inheriting from BaseAgent or custom logic
    # If inheriting directly from LlmAgent, initialization might differ

    def __init__(self, 
                 prompts_base_dir: Optional[Union[str, Path]] = None, 
                 **kwargs):
        """Initialize the InputClassifierAgent."""
        super().__init__(prompts_base_dir=prompts_base_dir, **kwargs)

        # --- ADD: Load InputClassifierAgent specific prompt --- #
        if self._prompts_base_dir: # Check if base dir was set
             prompt_filename = f"{self.AGENT_TYPE}_agent.md"
             self.prompt_template_text = self._load_prompt_template(prompt_filename)
             if not self.prompt_template_text:
                  logger.error("InputClassifierAgent failed to load its specific prompt!")
                  self.prompt_template_text = "ERROR: InputClassifierAgent prompt load failed."
        else:
             logger.error("InputClassifierAgent cannot load prompt, prompts_base_dir not set by BaseAgent.")
             self.prompt_template_text = "ERROR: prompts_base_dir missing."

        logger.info(
            f"InputClassifierAgent initialized. Inherited model: '{self.model_name}'. "
            f"Prompt loaded: {bool(self.prompt_template_text and 'ERROR' not in self.prompt_template_text)}"
        )

    async def classify_input(
        self, message: str, conversation_history: List[Dict[str, Any]]
    ) -> Dict[str, Optional[str]]:
        """
        Classifies the user's message based on content and history using process_with_retry.

        Args:
            message: The latest user message content.
            conversation_history: The preceding conversation history.

        Returns:
            A dictionary containing:
                - classification: "Task" or "Chat"
                - extracted_goal: The core goal string if classification is "Task", else None.
        """
        logger.info(f"InputClassifierAgent classifying message: '{message}'")

        if not self.prompt_template_text or "ERROR" in self.prompt_template_text:
            logger.error("InputClassifierAgent prompt template text not loaded correctly. Cannot classify.")
            return {"classification": "Chat", "extracted_goal": None} # Default to Chat

        # 1. Manually construct the full prompt string
        # Combine the loaded template with the specific message and history
        # This avoids issues with .format() if the template has literal {}
        history_str = json.dumps(conversation_history, indent=2)
        full_prompt = f"{self.prompt_template_text}\n\nConversation History:\n{history_str}\n\nLatest User Message:\n{message}\n\nJSON Classification:"
        # logger.debug(f"Constructed full prompt for classification: {full_prompt[:500]}...") # Commented out

        # 2. Call the LLM using the base agent's retry mechanism, expecting JSON
        try:
            response_dict = await self.process_with_retry(
                prompt_content=full_prompt, # Pass the fully constructed prompt string
                parser_func=self._parse_response, # Pass the appropriate parser
            )
            logger.debug(f"process_with_retry response: {response_dict}")

            if response_dict.get("success") and isinstance(response_dict.get("content"), dict):
                content = response_dict["content"]
                classification = content.get("classification")
                extracted_goal = content.get("extracted_goal")

                # Basic validation
                if classification not in ["Task", "Chat"]:
                    logger.warning(f"LLM returned invalid classification: {classification}. Defaulting to Chat.")
                    classification = "Chat"
                    extracted_goal = None
                if classification == "Chat":
                    extracted_goal = None # Ensure goal is None for Chat

                logger.info(f"Classification result: {classification}, Goal: {extracted_goal}")
                return {"classification": classification, "extracted_goal": extracted_goal}
            else:
                error_msg = response_dict.get("message", "Unknown error from process_with_retry")
                logger.error(f"Classification failed after retries: {error_msg}")
                return {"classification": "Chat", "extracted_goal": None}

        except Exception as e:
            logger.exception(f"Error calling process_with_retry for classification: {e}")
            return {"classification": "Chat", "extracted_goal": None}

    # ---> ADD: Basic parser function <--- #
    def _parse_response(self, response_text: str) -> Dict[str, Optional[str]]:
        """Parses the simple JSON response expected from the classifier LLM."""
        try:
            # ---> ADD: Strip markdown code fences --- #
            if response_text.strip().startswith("```json"):
                response_text = response_text.strip()[7:-3].strip()
            elif response_text.strip().startswith("```"):
                 response_text = response_text.strip()[3:-3].strip()
            # --- END: Strip markdown code fences --- #

            # Basic JSON loading, assumes LLM returns correct format
            parsed_json = json.loads(response_text)
            if isinstance(parsed_json, dict):
                return parsed_json
            else:
                logger.error(f"Classifier response was not a dict: {type(parsed_json)}")
                raise ValueError("Invalid JSON structure")
        except json.JSONDecodeError as e:
            logger.error(f"Failed to decode classifier JSON response: {e}. Raw: {response_text}")
            raise ValueError(f"Invalid JSON response: {e}")
        except Exception as e:
            logger.error(f"Unexpected error parsing classifier response: {e}. Raw: {response_text}")
            raise ValueError(f"Parsing error: {e}")

    # Process method might still be needed by ADK framework in some cases,
    # but direct calls should use classify_input.
    async def process(
        self,
        context: Optional[Dict[str, Any]] = None,
    ) -> Tuple[Any, bool]:
         logger.warning("InputClassifierAgent.process called directly - intended use is via classify_input.")
         message = context.get("latest_message", "") # Match context key used above
         history_list = context.get("history", [])   # Use history list here
         # Ensure history is a list of dicts if it comes from context
         if isinstance(history_list, str):
             try:
                 history_list = json.loads(history_list)
             except json.JSONDecodeError:
                 logger.error("Failed to parse history string in process method.")
                 history_list = []

         result = await self.classify_input(message, history_list)
         # Return the dictionary itself, assuming the caller handles it.
         # The bool might indicate success/failure, return True assuming classification itself succeeded.
         return result, True 