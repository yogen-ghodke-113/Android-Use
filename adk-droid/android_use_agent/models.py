from typing import Dict, List, Optional, Any, Union, Literal
from pydantic import BaseModel, Field, validator, field_validator

# --- NEW: Selector Model (Mirrors Kotlin Selector.kt) ---
class RectModel(BaseModel):
    # Match Kotlin Rect properties (assuming simple ints)
    left: int
    top: int
    right: int
    bottom: int

class Selector(BaseModel):
    view_id: Optional[str] = Field(None, description="The view ID resource name of the element.")
    text: Optional[str] = Field(None, description="The text content of the element.")
    content_desc: Optional[str] = Field(None, description="The content description of the element.")
    class_name: str | None = None
    window_id: int | None = None   # Made optional based on analysis
    bounds: RectModel | None = None
    is_clickable: bool | None = None
    is_editable: bool | None = None
    is_long_clickable: bool = Field(False, description="Whether the element is long-clickable.")

    class Config:
        # Allow extra fields if needed, though ideally should match Kotlin exactly
        extra = 'ignore'

# --- Parameter Models for Actions ---
# Kept models based on new_plan.md and README analysis

# --- DEPRECATED: Index-based Params ---
class TapByIndexParams(BaseModel):
    index: int = Field(..., description="DEPRECATED: Use *_by_selector actions.")

# class InputByIndexParams(BaseModel): # ... Deprecated ...
# class CopyByIndexParams(BaseModel): # ... Deprecated ...
# class PasteByIndexParams(BaseModel): # ... Deprecated ...
# class SelectByIndexParams(BaseModel): # ... Deprecated ...
# class LongClickByIndexParams(BaseModel): # ... Deprecated ...
# --- END DEPRECATED ---

# --- NEW: Selector-based Params ---
class TapBySelectorParams(BaseModel):
    selector: Selector = Field(..., description="Selector identifying the element to tap.")

class InputBySelectorParams(BaseModel):
    selector: Selector = Field(..., description="Selector identifying the element to input text into.")
    text_to_type: str = Field(..., description="Text to type.")

class CopyBySelectorParams(BaseModel):
    selector: Selector = Field(..., description="Selector identifying the element to copy from.")

class PasteBySelectorParams(BaseModel):
    selector: Selector = Field(..., description="Selector identifying the element to paste into.")

class SelectBySelectorParams(BaseModel):
    selector: Selector = Field(..., description="Selector identifying the element to select text within.")
    start: Optional[int] = Field(None, description="Selection start index (optional, defaults to beginning).")
    end: Optional[int] = Field(None, description="Selection end index (optional, defaults to end).")

class LongClickBySelectorParams(BaseModel):
    selector: Selector = Field(..., description="Selector identifying the element to long-click.")
# --- END Selector-based Params ---

# --- Other Action Params (Unchanged) ---
class PerformGlobalActionParams(BaseModel):
    action_id: str = Field(..., description="Global action identifier (e.g., 'GLOBAL_ACTION_BACK')")

class DoneParams(BaseModel):
    success: bool = Field(..., description="Was the overall user task successful?")
    message: str = Field(..., description="Final message summarizing the outcome and any gathered info.")

class RequestClarificationParams(BaseModel):
    question: str = Field(..., description="Question to ask the user.")

class WaitParams(BaseModel):
    duration_seconds: int = Field(..., description="Duration to wait in seconds.")

class SwipeSemanticParams(BaseModel):
    direction: Literal["up", "down", "left", "right"] = Field(..., description="Semantic direction to swipe.")

class LaunchAppParams(BaseModel):
    package_name: str = Field(..., description="The package name of the app to launch (e.g., com.android.settings).")
    activity: Optional[str] = None

# --- Node Request Params (Treated as tools, defined by CoreAgent output) ---
class RequestNodesByTextParams(BaseModel):
    text: str = Field(..., description="Text to search for.")

class RequestClickableNodesParams(BaseModel): pass # No parameters
class RequestInteractiveNodesParams(BaseModel): pass # No parameters
class RequestAllNodesParams(BaseModel): pass # No parameters
# --------------------------------------

# --- Core Agent Output Structure --- #

class AgentReasoningState(BaseModel):
    evaluation_previous_action: str = Field(..., description="Evaluation of the previous action's outcome (Success/Failed/Unknown + why)")
    visual_analysis: str = Field(..., description="Describe key visual elements relevant to the goal and the next step.")
    accessibility_analysis: Optional[str] = Field(None, description="Analysis of node data (now Selectors) if available, identification of target, or statement that no node data was available.")
    next_sub_goal: str = Field(..., description="The immediate, concise goal for the next action(s).")
    confidence_score: Optional[float] = None

class CoreAgentOutput(BaseModel):
    reasoning: AgentReasoningState
    action_name: Optional[str] = None
    action_params: Optional[Dict[str, Any]] = None

    @field_validator('action_params', mode='before')
    @classmethod
    def check_params_match_action(cls, v, info):
        # Use info.data which contains the model fields being validated
        action_name = info.data.get('action_name')
        if action_name is None:
            return v # No action, no params needed

        # Ensure v is a dict if not None
        if v is not None and not isinstance(v, dict):
             raise ValueError(f"action_params must be a dictionary or None, got {type(v)}")

        # Map action names to their expected Pydantic parameter models
        param_model_map = {
            "tap_by_selector": TapBySelectorParams,
            "input_by_selector": InputBySelectorParams,
            "copy_by_selector": CopyBySelectorParams,
            "paste_by_selector": PasteBySelectorParams,
            "select_by_selector": SelectBySelectorParams,
            "long_click_by_selector": LongClickBySelectorParams,
            "perform_global_action": PerformGlobalActionParams,
            "done": DoneParams,
            "request_clarification": RequestClarificationParams,
            "wait": WaitParams,
            "swipe_semantic": SwipeSemanticParams,
            "launch_app": LaunchAppParams,
            # Node request actions (tools)
            "request_nodes_by_text": RequestNodesByTextParams,
            "request_clickable_nodes": RequestClickableNodesParams,
            "request_interactive_nodes": RequestInteractiveNodesParams,
            "request_all_nodes": RequestAllNodesParams,
            # Deprecated index actions (keep validation during transition?)
            "tap_by_index": TapByIndexParams,
            # Add other actions as needed
        }

        model_cls = param_model_map.get(action_name)
        if model_cls:
            try:
                # Attempt to validate the parameters using the corresponding model
                model_cls.model_validate(v or {}) # Validate empty dict if params are None but expected
                return v
            except Exception as e:
                # Raise a validation error if params don't match the expected model
                raise ValueError(f"Invalid parameters for action '{action_name}': {e}") from e
        else:
            # Handle unknown action names or actions with no expected params
            if v is not None and v: # If params are provided for an unknown/no-param action
                 raise ValueError(f"Unexpected parameters provided for action '{action_name}'")
            return v # Allow None or empty params for unknown/no-param actions


# --- REMOVED Resolver Agent Tool Schemas --- #
# class RequestNodesByTextArgs(...)
# class NodePayload(...)
# class RequestAccessibilityNodesOutput(...)

# --- Task State Model (Used by TaskManager) ---
class TaskState(BaseModel):
    goal: str
    status: Literal["running", "completed", "failed", "stuck", "failed_no_progress", "failed_consecutive_errors"]
    history: List[Dict[str, Any]] = Field(default_factory=list)
    current_step: int = 0
    last_action_result: Optional[Dict[str, Any]] = None
    error_message: Optional[str] = None # Add error message field
    last_nodes: Optional[List[Dict[str, Any]]] = Field(None, description="List of Selector dictionaries from last node request") # Store Selectors as dicts
    last_core_agent_response: Optional[Dict[str, Any]] = Field(None, description="Last output from CoreAgent as dict") # Store CoreAgentOutput as dict
    last_screenshot_hash: Optional[str] = None
    last_screenshot_b64: Optional[str] = None
    last_node_request_correlation_id: Optional[str] = None
    consecutive_failures: int = 0
    max_steps: Optional[int] = None
    max_consecutive_failures: Optional[int] = None

    # Method to easily convert back to CoreAgentOutput model if needed
    def get_last_core_agent_output(self) -> Optional[CoreAgentOutput]:
        if self.last_core_agent_response:
            try:
                return CoreAgentOutput.model_validate(self.last_core_agent_response)
            except Exception as e:
                # Log error if validation fails
                # logger.error(f"Failed to validate last CoreAgent response from TaskState: {e}")
                return None
        return None
