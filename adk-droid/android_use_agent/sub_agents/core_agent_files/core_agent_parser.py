# adk-droid/android_use_agent/sub_agents/core_agent_files/core_agent_parser.py
"""
Functions for parsing, validating, and reformatting the LLM response for the CoreAgent.
"""
import json
import logging
import re
from typing import Dict, Any, Optional, List, Union

from pydantic import ValidationError, BaseModel

# Import models from the parent package level
try:
    from ... import models # Go up two levels to android_use_agent, then import models
    from ...models import CoreAgentAction, ActionModel # Specific model imports
except ImportError:
    logger = logging.getLogger(__name__)
    logger.error("Failed to import models from parent package. Parsing might fail.")
    # Define dummy classes if import fails (e.g., for testing parser standalone)
    class CoreAgentAction:
        @staticmethod
        def model_validate(data):
            # Dummy validation
            return data

    class ActionModel:
        pass
    class models: pass # Dummy module

logger = logging.getLogger(__name__)

# --- Action Reformatting Helper ---

def _reformat_simple_action(action_dict: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """
    Tries to heuristically reformat a simplified action dictionary into the
    standard {"action_type": {params}} format. Used as a helper in parsing.

    Args:
        action_dict: A dictionary potentially representing a simplified action.

    Returns:
        A dictionary in the standard format, or None if reformatting fails.
    """
    logger.debug(f"Attempting to reformat simple action: {action_dict}")
    formatted_action = {}
    action_type = None
    parameters = None

    # Case 0: {"function": "action_name", "parameters": {...}} <-- New case
    if "function" in action_dict and "parameters" in action_dict and isinstance(action_dict.get("parameters"), dict):
        action_type = action_dict["function"] # Use 'function' key as action type
        parameters = action_dict["parameters"]
        logger.debug(f"Reformatting case 0 (function/parameters): action='{action_type}'")
    # Case 1: {"action": "type", "parameters": {...}}
    elif "action" in action_dict and "parameters" in action_dict and isinstance(action_dict.get("parameters"), dict):
        action_type = action_dict["action"]
        parameters = action_dict["parameters"]
        logger.debug(f"Reformatting case 1: action='{action_type}'")
    # Case 2: {"click": {...}} or {"type": {...}} etc. (Single key is the action type)
    elif len(action_dict) == 1:
        key = list(action_dict.keys())[0]
        value = action_dict[key]
        # Check if the value looks like parameters (is a dict)
        if isinstance(value, dict):
            action_type = key
            parameters = value
            logger.debug(f"Reformatting case 2: action_type='{action_type}'")
        else:
            # Special handling for simple actions like "done": True or "go_back": {}
            # **FIX:** Use standardized keys from ActionModel if possible
            simple_actions = ["done", "go_back", "home", "recents", "notifications"] # Add more as needed
            if key.lower() in simple_actions:
                action_type = key
                parameters = {} if value is None or value is True else value # Allow empty dict or existing params
                logger.debug(f"Reformatting case 2 (special simple): action_type='{action_type}'")

    # Add more heuristic cases if needed based on observed model outputs

    if not action_type or parameters is None: # Cannot determine format
        logger.warning(f"Could not determine action type/params from dict: {action_dict}")
        return None

    # Map common simple names to standard model keys
    action_type_lower = str(action_type).lower()
    final_action_key = action_type_lower # Default to lowercased version
    params_model = None

    # Map action types to ActionModel field names and corresponding Param models
    action_map = {
        "click": ("click_element", getattr(models, 'ClickParams', None)),
        "click_element": ("click_element", getattr(models, 'ClickParams', None)),
        "type": ("type_text", getattr(models, 'TypeParams', None)),
        "type_text": ("type_text", getattr(models, 'TypeParams', None)),
        "input_text": ("type_text", getattr(models, 'TypeParams', None)),
        "scroll": ("scroll", getattr(models, 'ScrollParams', None)),
        "go_home": ("perform_global_action", getattr(models, 'PerformGlobalActionParams', None)),
        "home": ("perform_global_action", getattr(models, 'PerformGlobalActionParams', None)),
        "go_back": ("perform_global_action", getattr(models, 'PerformGlobalActionParams', None)),
        "back": ("perform_global_action", getattr(models, 'PerformGlobalActionParams', None)),
        "recents": ("perform_global_action", getattr(models, 'PerformGlobalActionParams', None)),
        "recent_apps": ("perform_global_action", getattr(models, 'PerformGlobalActionParams', None)),
        "notifications": ("perform_global_action", getattr(models, 'PerformGlobalActionParams', None)),
        "show_notifications": ("perform_global_action", getattr(models, 'PerformGlobalActionParams', None)),
        "open_app": ("start_activity_intent", getattr(models, 'StartActivityIntentParams', None)),
        "start_activity_intent": ("start_activity_intent", getattr(models, 'StartActivityIntentParams', None)),
        "intent_construction": ("intent_construction", getattr(models, 'IntentConstructionParams', None)), # Assuming model exists
        "query_package_manager": ("query_package_manager", getattr(models, 'QueryPackageManagerParams', None)), # Assuming model exists
        "request_code_generation": ("request_code_generation", getattr(models, 'RequestCodeGenerationParams', None)), # Assuming model exists
        "done": ("done", getattr(models, 'DoneParams', None)),
    }

    if action_type_lower in action_map:
        final_action_key, params_model = action_map[action_type_lower]

        # --- Apply specific parameter adjustments based on mapped type ---
        if final_action_key == "type_text":
            if isinstance(parameters, dict) and 'text_to_type' not in parameters:
                if 'text' in parameters: parameters['text_to_type'] = parameters.pop('text')
                elif 'value' in parameters: parameters['text_to_type'] = parameters.pop('value')
        elif final_action_key == "scroll":
            if isinstance(parameters, dict):
                 if 'direction' not in parameters:
                     # Allow simple string value like "scroll": "DOWN"
                     if isinstance(action_dict.get(action_type), str):
                         parameters['direction'] = action_dict[action_type]
                     else: # Default if missing
                         parameters['direction'] = 'DOWN'
                 # Normalize direction
                 if 'direction' in parameters:
                     parameters['direction'] = str(parameters['direction']).upper()
            elif isinstance(parameters, str): # Handle {"scroll": "DOWN"}
                 direction = parameters.upper()
                 parameters = {"direction": direction} # Convert to dict
        elif final_action_key == "perform_global_action":
             # Map simple names to action_id
             if action_type_lower in ["go_home", "home"]: parameters = {"action_id": "GLOBAL_ACTION_HOME"}
             elif action_type_lower in ["go_back", "back"]: parameters = {"action_id": "GLOBAL_ACTION_BACK"}
             elif action_type_lower in ["recents", "recent_apps"]: parameters = {"action_id": "GLOBAL_ACTION_RECENTS"}
             elif action_type_lower in ["notifications", "show_notifications"]: parameters = {"action_id": "GLOBAL_ACTION_NOTIFICATIONS"}
        elif final_action_key == "start_activity_intent":
             # Handle { "open_app": { "app_name": "..." } }
             if action_type_lower == "open_app":
                 app_name = parameters.get("app_name")
                 if app_name:
                     # Construct the nested structure expected by StartActivityIntentParams
                     try:
                          parameters = models.StartActivityIntentParams(
                               intent_params=models.IntentParams(
                                    action="android.intent.action.MAIN",
                                    categories=["android.intent.category.LAUNCHER"],
                                    extras={"app_name_hint": app_name} # Use extras for hints
                               )
                          ).model_dump() # Convert back to dict
                     except AttributeError: # Handle case where models might be dummy
                          logger.warning("Dummy models used, cannot create nested intent structure.")
                          parameters = {"intent_params": {"extras": {"app_name_hint": app_name}}} # Basic structure
                 else:
                     logger.warning(f"'open_app' action missing 'app_name' parameter: {parameters}")
                     return None # Invalid action
             # Handle { "start_activity_intent": "com.example.app" }
             elif isinstance(action_dict.get(action_type), str):
                  package_name = action_dict[action_type]
                  try:
                      parameters = models.StartActivityIntentParams(
                           intent_params=models.IntentParams(
                                action="android.intent.action.MAIN",
                                categories=["android.intent.category.LAUNCHER"],
                                package_name=package_name
                           )
                      ).model_dump()
                  except AttributeError:
                      logger.warning("Dummy models used, cannot create nested intent structure.")
                      parameters = {"intent_params": {"package_name": package_name}}
             # Handle { "start_activity_intent": { "parameters": {...} } } -> needs intent_params
             elif isinstance(parameters, dict) and "parameters" in parameters and "intent_params" not in parameters:
                  intent_params_dict = parameters.pop("parameters", {})
                  if isinstance(intent_params_dict, dict):
                       parameters["intent_params"] = intent_params_dict # Move to correct key
                  else:
                       logger.warning("Value under 'parameters' for start_activity_intent was not a dict.")
                       return None # Invalid structure
             # Handle { "start_activity_intent": { "parameters": { "intent_params": {...} } } }
             elif isinstance(parameters, dict) and "parameters" in parameters and isinstance(parameters.get("parameters"), dict) and "intent_params" in parameters.get("parameters",{}):
                  intent_params_dict = parameters["parameters"].get("intent_params", {})
                  parameters = {"intent_params": intent_params_dict} # Simplify to correct structure
        elif final_action_key == "done":
             if isinstance(parameters, dict) and 'message' not in parameters:
                 parameters['message'] = "Task completed." # Default message
             elif isinstance(parameters, bool) and parameters: # Handle {"done": true}
                 parameters = {"message": "Task completed."}


    # Try to validate parameters against the corresponding Pydantic model if found
    if params_model and isinstance(parameters, dict):
        try:
            # Validate parameters (using Pydantic v1/v2 methods)
            if hasattr(params_model, 'model_validate'):
                validated_params = params_model.model_validate(parameters)
            elif hasattr(params_model, '__init__'): # Basic check for v1 style
                validated_params = params_model(**parameters)
            else:
                validated_params = parameters # No validation possible

            # Use the validated data (converted back to dict)
            if hasattr(validated_params, 'model_dump'):
                 parameters = validated_params.model_dump()
            elif hasattr(validated_params, '__dict__'): # Fallback for simple classes
                 parameters = validated_params.__dict__
            # Else: keep parameters as is if no dump method found

            logger.debug(f"Successfully validated parameters for '{final_action_key}'")

        except Exception as e: # Catch Pydantic ValidationError etc.
            logger.warning(f"Parameter validation failed for action '{final_action_key}' with params {parameters}. Error: {e}. Using raw params.")
            # Proceed with potentially invalid parameters, validation will catch later if crucial

    # Construct the final standard action format
    formatted_action[final_action_key] = parameters if isinstance(parameters, dict) else {}

    logger.debug(f"Reformatted action: {formatted_action}")
    return formatted_action


# --- JSON Extraction ---

def _extract_json_from_response(response_text: str) -> Any:
    """
    Attempts to extract a JSON object or list from the llm response text.
    Handles responses wrapped in markdown code blocks (```json ... ```) or raw JSON.

    Args:
        response_text: The raw response text from the LLM.

    Returns:
        The parsed JSON object (usually a dictionary or list).

    Raises:
        json.JSONDecodeError: If no valid JSON can be extracted.
    """
    logger.debug("Attempting to extract JSON from response...")

    # 1. Try extracting from markdown code blocks ```json ... ``` (Improved robustness)
    # Use non-greedy matching for content, handle potential leading/trailing whitespace carefully.
    code_block_regex = r"```(?:json)?\s*([\s\S]+?)\s*```"
    match = re.search(code_block_regex, response_text, re.IGNORECASE | re.DOTALL)

    if match:
        block_content = match.group(1).strip()
        logger.debug(f"Found potential JSON code block content (length: {len(block_content)}). Preview: {block_content[:100]}...")
        if not block_content.startswith(("{", "[")):
            logger.warning("Code block content doesn't start with { or [, might not be JSON.")
            # Fall through to raw parsing attempt
        else:
            try:
                parsed_json = json.loads(block_content)
                logger.debug("Successfully parsed JSON from code block.")
                return parsed_json # Return the first successfully parsed block
            except json.JSONDecodeError as e:
                logger.warning(f"Failed to parse JSON from code block: {e}. Content preview: '{block_content[:100]}...'. Trying raw parsing next.")
                # Fall through to raw parsing attempt
    else:
        logger.debug("No JSON code blocks detected.")

    # 2. If no blocks found or blocks failed, try parsing a substring of the whole response
    logger.debug("Trying to parse substring from raw response.")
    try:
        # Look for the first '{' or '[' and the last '}' or ']'
        first_brace = response_text.find('{')
        first_bracket = response_text.find('[')

        start_index = -1
        end_char = ''
        if first_brace != -1 and (first_bracket == -1 or first_brace < first_bracket):
            start_index = first_brace
            end_char = '}'
            logger.debug("Found potential JSON object start '{'.")
        elif first_bracket != -1:
            start_index = first_bracket
            end_char = ']'
            logger.debug("Found potential JSON array start '['.")

        if start_index != -1:
            # Be careful with rfind, ensure it finds the *correct* corresponding closing bracket
            # This basic approach might still fail for nested structures if interrupted.
            last_end_char = response_text.rfind(end_char)
            if last_end_char != -1 and last_end_char >= start_index:
                potential_json_str = response_text[start_index : last_end_char + 1]
                logger.debug(f"Extracted potential JSON substring (length: {len(potential_json_str)}): {potential_json_str[:100]}...")
                parsed_json = json.loads(potential_json_str)
                logger.debug("Successfully parsed JSON from extracted raw text substring.")
                return parsed_json
            else:
                 logger.debug(f"Found start delimiter but no matching end delimiter '{end_char}'.")
                 raise json.JSONDecodeError(f"Mismatched delimiters for {end_char}", response_text, 0)
        else:
            logger.debug("Could not find JSON object/array start delimiters in raw text.")
            raise json.JSONDecodeError("Raw text does not contain JSON start delimiters", response_text, 0)

    except json.JSONDecodeError as e:
        logger.error(f"Failed to parse JSON from the entire response or substring: {e}")
        # Re-raise the error if all attempts fail
        raise json.JSONDecodeError(f"Could not extract valid JSON from response: {e.msg}", response_text, e.pos)


# --- Response Parsing and Validation ---

def _convert_list_to_core_agent_output(action_list: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Wraps a list of actions into the standard CoreAgentAction dictionary structure."""
    logger.warning("Model response was a list, wrapping in CoreAgentAction structure.")
    formatted_actions = []
    for action_dict in action_list:
        if not isinstance(action_dict, dict):
            logger.warning(f"Skipping non-dict item in action list: {action_dict}")
            continue
        # Attempt reformatting for each item
        reformatted = _reformat_simple_action(action_dict)
        if reformatted:
            formatted_actions.append(reformatted)
        else:
            logger.warning(f"Could not reformat list item, skipping: {action_dict}")

    return {
        "reasoning": {
            "evaluation_previous_action": "Model provided list output, wrapped and potentially reformatted.",
            "memory_update": "Using direct model output (list format).",
            "next_sub_goal": "Execute the recommended action(s)"
        },
        "actions": formatted_actions
    }

def _validate_parsed_actions(parsed_data: Union[Dict[str, Any], List[Any]]) -> None:
    """
    Validates and potentially reformats actions within the parsed data (dict or list).

    Mutates the input `parsed_data` in place for dictionaries.
    For lists, it returns a new list with validated/reformatted actions.

    Args:
        parsed_data: The dictionary (expected format like CoreAgentAction) or list of actions.

    Raises:
        ValueError: If the action structure is invalid or reformatting fails.
    """
    logger.debug("Validating parsed actions...")
    actions_to_validate = []
    is_dict = False

    if isinstance(parsed_data, dict):
        if "actions" in parsed_data and isinstance(parsed_data["actions"], list):
            actions_to_validate = parsed_data["actions"]
            is_dict = True
            logger.debug(f"Validating 'actions' list within dictionary. Found {len(actions_to_validate)} actions.")
        else:
            logger.warning("Parsed data is a dictionary but missing 'actions' list. Cannot validate actions.")
            # Allow dicts without 'actions' (e.g., just thought/summary) but log it.
            # Validate against CoreAgentAction later if possible.
            return # Nothing to validate here
    elif isinstance(parsed_data, list):
        actions_to_validate = parsed_data
        logger.debug(f"Validating list of {len(actions_to_validate)} potential actions.")
    else:
        raise ValueError("Input to _validate_parsed_actions must be a dictionary or a list.")

    validated_actions = []
    for i, action_item in enumerate(actions_to_validate):
        logger.debug(f"Validating action item {i}: {action_item}")
        if not isinstance(action_item, dict):
            logger.warning(f"Action item {i} is not a dictionary: {action_item}. Skipping.")
            # Optionally raise error depending on strictness:
            # raise ValueError(f"Action item {i} must be a dictionary, got {type(action_item)}")
            continue # Skip non-dict items in the list

        # 1. Try Reformatting Simple Actions
        reformatted_action = _reformat_simple_action(action_item)
        if reformatted_action:
            logger.debug(f"Action item {i} successfully reformatted to: {reformatted_action}")
            action_to_validate = reformatted_action
        else:
            # If reformatting failed or wasn't needed, use the original dict
            # We assume it *should* already be in the standard format { "action_type": {params} }
            action_to_validate = action_item
            if len(action_to_validate) != 1:
                 logger.warning(f"Action item {i} (after potential reformat failure) is not in the expected standard format (single key): {action_to_validate}. Validation might fail.")
            elif not isinstance(list(action_to_validate.values())[0], dict):
                 logger.warning(f"Action item {i}'s parameters are not a dictionary: {action_to_validate}. Validation might fail.")


        # 2. Validate against ActionModel (Pydantic)
        try:
            # Validate the structure { "action_type": {params} }
            validated_pydantic_action = ActionModel.model_validate(action_to_validate)
            # Convert back to dict for consistency in the list (might contain Pydantic models otherwise)
            validated_dict_action = validated_pydantic_action.model_dump(exclude_unset=True) # Use model_dump
            validated_actions.append(validated_dict_action)
            logger.debug(f"Action item {i} successfully validated against ActionModel: {validated_dict_action}")

        except Exception as e: # Catch Pydantic ValidationError and other issues
            logger.error(f"Validation failed for action item {i}: {action_to_validate}. Error: {e}", exc_info=True)
            # Decide whether to skip the invalid action or raise an error
            # For now, we'll raise an error to be strict.
            raise ValueError(f"Action item {i} failed validation: {action_to_validate}. Reason: {e}") from e

    # Update the original dictionary's actions list or return the new list
    if is_dict:
        parsed_data["actions"] = validated_actions
        logger.debug("Updated 'actions' list in the original dictionary.")
    else: # Input was a list
        logger.debug("Returning new list of validated actions.")
        # This case shouldn't happen if called by validate_and_format_core_agent_dict,
        # but keeping it for potential direct use of _validate_parsed_actions.
        # We need to return it, as lists are modified differently than dicts in place.
        # However, the caller (validate_and_format_core_agent_dict) doesn't use the return value
        # when the input is a dict. This design is a bit confusing.
        # Let's modify the function to *always* return the validated data for clarity.
        # The caller can then decide what to do with it.
        # *** REVISED APPROACH: Mutate dict in place, return list if input is list ***
        # Let's revert to mutating the dict in place as it's simpler for the primary use case.
        # If input is list, the caller (parse_and_validate_response) handles the returned list.
        pass # Dict was mutated in place

# --- Main Parsing/Validation Functions ---

def validate_and_format_core_agent_dict(response_dict: Dict[str, Any]) -> CoreAgentAction:
    """
    Validates and formats a dictionary presumed to be the output of CoreAgent.process.

    This function assumes the input dictionary already has the basic structure
    (e.g., 'thought', 'summary', 'actions'). It focuses on validating the 'actions'
    list using Pydantic models and reformatting helpers.

    Args:
        response_dict: The dictionary returned by CoreAgent.process.

    Returns:
        A validated CoreAgentAction object.

    Raises:
        ValidationError: If the dictionary fails validation against the ActionModel.
        Exception: Can re-raise Pydantic validation errors or others.
    """
    logger.info("Validating and formatting CoreAgent response dictionary...")
    logger.debug(f"Input dictionary: {response_dict}")

    if not isinstance(response_dict, dict):
        raise ValueError(f"Input must be a dictionary, got {type(response_dict)}")

    # 1. Validate/Reformat Actions (mutates response_dict['actions'] in place)
    # _validate_parsed_actions handles the case where 'actions' might be missing
    try:
        _validate_parsed_actions(response_dict) # Modifies dict in place
        logger.debug("Actions within the dictionary validated/reformatted successfully.")
    except ValueError as e:
        logger.error(f"Action validation failed: {e}", exc_info=True)
        raise # Re-raise the specific validation error

    # 2. Validate the entire dictionary against CoreAgentAction
    try:
        validated_output = CoreAgentAction.model_validate(response_dict)
        logger.info("CoreAgent response dictionary successfully validated against CoreAgentAction model.")
        return validated_output
    except ValidationError as e:
        logger.error(f"Final validation against CoreAgentAction failed for dict: {response_dict}. Error: {e}", exc_info=True)
        raise ValueError(f"Response dictionary failed CoreAgentAction validation: {e}") from e


def parse_and_validate_response(response_text: str) -> Union[CoreAgentAction, Dict[str, Any]]:
    """
    Parses the raw LLM response text, attempts reformatting, and validates
    against the CoreAgentAction Pydantic model OR returns a tool_calls dict.

    Args:
        response_text: The raw text response from the model.

    Returns:
        A validated CoreAgentAction object or a dictionary containing tool_calls.

    Raises:
        ValueError: If parsing, reformatting, or validation fails.
    """
    if not response_text or not response_text.strip():
        logger.error("Received empty response from model for parsing")
        raise ValueError("Empty response received from model")

    logger.debug(f"Parsing raw response of length {len(response_text)}")
    parsed_data = None
    try:
        # Extract JSON first (handles ```json ... ``` or raw)
        parsed_data = _extract_json_from_response(response_text)
        logger.debug(f"Extracted data type: {type(parsed_data)}")

        # --- Check for Tool Call Structure FIRST ---
        if isinstance(parsed_data, dict) and "tool_calls" in parsed_data and isinstance(parsed_data.get("tool_calls"), list):
            logger.info("Detected 'tool_calls' structure in LLM response. Returning as is for ADK framework.")
            # Basic validation: Ensure it's a list of dicts with function/args?
            if all(isinstance(tc, dict) and 'function' in tc for tc in parsed_data["tool_calls"]):
                 return parsed_data # Return the raw dict for ADK runner
            else:
                 logger.error("Invalid structure within 'tool_calls' list.")
                 raise ValueError("Invalid structure within 'tool_calls' list.")
        
        # --- If not tool_calls, proceed with reasoning/actions format ---

        # If extracted data is a list (old format?), convert it
        if isinstance(parsed_data, list):
            parsed_data = _convert_list_to_core_agent_output(parsed_data)

        # Ensure basic structure (reasoning dict, actions list) exists after potential conversion
        if not isinstance(parsed_data, dict) or \
           'reasoning' not in parsed_data or not isinstance(parsed_data.get('reasoning'), dict) or \
           'actions' not in parsed_data or not isinstance(parsed_data.get('actions'), list):
            logger.error(f"Data structure invalid (expected reasoning/actions): {str(parsed_data)[:300]}...")
            raise ValueError("Extracted/Converted data does not have required 'reasoning' (dict) and 'actions' (list) keys.")

        # --- Apply _reformat_simple_action to each action BEFORE validation ---
        original_actions = parsed_data.get("actions", [])
        reformatted_actions = []
        if isinstance(original_actions, list):
            for action_item in original_actions:
                if isinstance(action_item, dict):
                    # Attempt to reformat using the helper function
                    reformatted = _reformat_simple_action(action_item)
                    if reformatted:
                        reformatted_actions.append(reformatted)
                        logger.debug(f"Reformatted action item: {action_item} -> {reformatted}")
                    else:
                        # If reformatting fails, keep the original structure but log a warning
                        logger.warning(f"Could not reformat action item, keeping original (might fail validation): {action_item}")
                        reformatted_actions.append(action_item)
                else:
                    logger.warning(f"Skipping non-dict item found in actions list: {action_item}")
            parsed_data["actions"] = reformatted_actions # Update parsed_data with the potentially reformatted list
        else:
            logger.error("'actions' field is not a list after initial processing.")
            parsed_data["actions"] = [] # Ensure it's an empty list if structure was wrong

        # Ensure reasoning sub-keys exist
        reasoning_dict = parsed_data['reasoning']
        reasoning_dict.setdefault("evaluation_previous_action", "N/A")
        reasoning_dict.setdefault("memory_update", "N/A")
        reasoning_dict.setdefault("next_sub_goal", "N/A")

        # Validate actions list is not empty and contains valid actions
        _validate_parsed_actions(parsed_data) # Raises ValueError on failure

        # --- Pydantic Validation ---
        logger.debug(f"Attempting Pydantic validation for reasoning/actions: {str(parsed_data)[:300]}...")
        validated_output = CoreAgentAction.model_validate(parsed_data) # Use model_validate for Pydantic v2
        logger.debug("Successfully validated against CoreAgentAction Pydantic model.")
        return validated_output

    except (json.JSONDecodeError, ValueError, AttributeError, Exception) as validation_err:
        logger.error(f"Failed to parse, reformat, or validate response: {validation_err}")
        logger.debug(f"Data at point of failure: {str(parsed_data)[:500]}...") # Log data that failed
        logger.debug(f"Original Response snippet: {response_text[:500]}...")
        # Re-raise as ValueError for consistent error handling upstream
        raise ValueError(f"Failed to produce valid output format: {validation_err}")

