"""
Core task execution loop using ADK Runner and interacting with the client
via ConnectionManager and session data. Handles semantic intent resolution
using ResolverAgent and executes actions via Accessibility Service.
"""
import asyncio
import logging
import time
import base64
import json
import inspect
import os
from typing import Dict, Any, Optional, List, Union, Tuple
import uuid
import hashlib # Added for state hashing
# Remove unused ProcessPoolExecutor
# from concurrent.futures import ProcessPoolExecutor
import warnings
from datetime import datetime
import collections # For deque

# Remove numpy import if not needed elsewhere
# import numpy as np

from pydantic import BaseModel, Field

# ADK Core Components
from google.adk.runners import Runner
# Use google.genai.types for Content, Part, FunctionResponse, etc.
from google.genai import types as adk_types
from google.adk.sessions import InMemorySessionService, Session
from google.adk.artifacts import InMemoryArtifactService, BaseArtifactService
from google.adk.tools import BaseTool

# Project specific components
from . import agent_utils
from .task_manager_files import task_comms as comms # Ensure comms is imported correctly
# Remove unused parser import
# from .sub_agents.core_agent_files import core_agent_parser as parser
# from .models import CoreAgentOutput # Removed import
from main_files.connection_manager import ConnectionManager
# Import the models module
from . import models # Import the models package
# Remove Resolver Agent imports
# from .sub_agents.resolver_agent import ResolverAgent, ResolverResponse, ToolCallRequest
# Import Resolver Tools (for type hinting or potential direct use if needed)
# from .tools.resolver_tools import (...) # Schemas now in models.py or resolver_tools.py

# Import local utilities
# from . import utils

# Import Constants Directly from Config and local constants
try:
    from main_files.config import APP_NAME, USER_ID
    logger = logging.getLogger(__name__)
    logger.debug(f"Using APP_NAME='{APP_NAME}' and USER_ID='{USER_ID}' from main_files.config")
except ImportError:
    logger = logging.getLogger(__name__)
    logger.error("Could not import APP_NAME, USER_ID from main_files.config. Using defaults.")
    APP_NAME = "default_app"
    USER_ID = "default_user"

from .task_manager_files import task_constants as const

# Constants
MAX_STEPS = const.MAX_TASK_STEPS
EVENT_TIMEOUT = const.CLIENT_RESPONSE_TIMEOUT
# --- NEW: Loop Termination Constants ---
MAX_CONSECUTIVE_ACTION_FAILURES = 3
MAX_STEPS_WITHOUT_STATE_CHANGE = 3
# --- END NEW --
MAX_RECENT_FAILED_SELECTORS = 5 # Store last 5 failed tap selectors


# --- REMOVED Resolver specific constants ---
# MAX_RESOLVER_TURNS = 5

# --- REMOVED SEMANTIC_ACTIONS list ---

# --- UPDATED Expected Action Keys ---
EXPECTED_ACTION_KEYS = {
    # Selector-based actions
    "tap_by_selector", "input_by_selector", "copy_by_selector",
    "paste_by_selector", "select_by_selector", "long_click_by_selector",
    # Simple/Direct actions
    "swipe_semantic", "perform_global_action", "launch_app",
    # Node request actions (treated as tools by CoreAgent)
    "request_nodes_by_text", "request_interactive_nodes", "request_all_nodes", "request_clickable_nodes",
    # Control flow actions
    "done", "request_clarification", "wait",
    # Deprecated Index Actions (REMOVE LATER)
    "tap_by_index", "input_by_index", "copy_by_index", "paste_by_index", "select_by_index", "long_click_by_index",
    # Deprecated tools/actions (REMOVE LATER)
     "list_packages" # Keep if still used? CoreAgent should prefer other methods.
    # "swipe" # Removed coordinate swipe, prefer swipe_semantic
}

# Define node request actions that need special handling
NODE_REQUEST_ACTIONS = [
    "request_nodes_by_text",
    "request_interactive_nodes",
    "request_all_nodes",
    "request_clickable_nodes",
]

# Remove unused Worker Pool Configuration
# logger = logging.getLogger(__name__) # Logger already defined above


# --- REMOVED _run_resolver_loop function ---


# --- REMOVED execute_resolver_tool function ---

# --- MODIFIED execute_resolver_tool signature and logic ---
async def execute_resolver_tool(
    # tool_call: ToolCallRequest, # Original parameter type, adjust if needed based on actual usage
    tool_name: str, # Using tool_name and tool_args directly as per original implementation
    tool_args: Dict[str, Any], # Using tool_name and tool_args directly as per original implementation
    session_id: str,
    connection_manager: ConnectionManager, # Pass ConnectionManager instance
    logger: logging.Logger, # Added logger as per request (though original didn't have it)
    state: dict # Add state here
) -> Dict[str, Any]: # Return type changed to Dict to match original implementation
    """Handles sending request and waiting for response for resolver tools."""
    request_correlation_id = str(uuid.uuid4()) # Generate ID here

    # Store the request ID immediately for node requests
    if tool_name in ["request_nodes_by_text", "request_interactive_nodes", "request_all_nodes", "request_clickable_nodes"]:
        state["last_node_request_correlation_id"] = request_correlation_id
        logger.debug(f"[{session_id}] Storing correlation ID for upcoming {tool_name}: {request_correlation_id}")

    logger.info(f"[{session_id}] Requesting tool execution: {tool_name} with args: {tool_args}")

    request_type_map = {
        "request_nodes_by_text": comms.TYPE_REQUEST_NODES_BY_TEXT,
        "request_interactive_nodes": comms.TYPE_REQUEST_INTERACTIVE_NODES,
        "request_all_nodes": comms.TYPE_REQUEST_ALL_NODES,
        "request_clickable_nodes": comms.TYPE_REQUEST_CLICKABLE_NODES,
    }
    response_type_map = {
        "request_nodes_by_text": comms.TYPE_NODES_BY_TEXT_RESULT,
        "request_interactive_nodes": comms.TYPE_INTERACTIVE_NODES_RESULT,
        "request_all_nodes": comms.TYPE_ALL_NODES_RESULT,
        "request_clickable_nodes": comms.TYPE_CLICKABLE_NODES_RESULT,
    }

    if tool_name not in request_type_map:
        logger.error(f"[{session_id}] Attempted to execute unknown resolver tool: '{tool_name}'")
        # Return structure matching original function's return type
        return {"success": False, "nodes": None, "message": f"Error: Unknown resolver tool '{tool_name}' requested."}


    request_type = request_type_map[tool_name]
    expected_response_type = response_type_map[tool_name]
    payload = tool_args.copy()

    logger.info(f"[{session_id}] Executing resolver tool '{tool_name}'. Sending '{request_type}' to client with args: {payload}, correlation_id: {request_correlation_id}")

    try:
        # Use the generated request_correlation_id when sending
        response_data = await connection_manager.send_request_and_wait_for_response(
            session_id=session_id,
            # Wrap payload (tool_args) inside a 'content' key
            request_payload={"content": payload}, # Corrected syntax
            request_type=request_type, # Specify the type for the message envelope
            expected_response_type=expected_response_type, # For potential validation if needed
            correlation_id=request_correlation_id, # Pass the correlation ID
            timeout=EVENT_TIMEOUT
        )

        if response_data is None:
            logger.error(f"[{session_id}] Timeout or connection error waiting for '{expected_response_type}' from client for tool '{tool_name}'.")
            return {"success": False, "nodes": None, "message": f"Error: Timeout or connection error waiting for client response for {tool_name}."}

        response_content = response_data.get("content", {})
        success = response_content.get("success", False)
        message = response_content.get("message", "No message from client.")
        nodes_list = None
        # --- MODIFIED: Extract 'nodes' from 'data' sub-dict if present --- #
        data_dict = response_content.get("data")
        if isinstance(data_dict, dict):
            nodes_list = data_dict.get("nodes")
        elif data_dict is not None:
            logger.warning(f"[{session_id}] Received unexpected 'data' type in response for {tool_name}: {type(data_dict)}")
        # --- END MODIFICATION --- #

        log_nodes_msg = f"Nodes found: {len(nodes_list)}" if isinstance(nodes_list, list) else "Nodes: None/Invalid"
        logger.info(f"[{session_id}] Received '{expected_response_type}'. Success: {success}, {log_nodes_msg}. Msg: '{message}'")

        # Return the raw content dictionary, CoreAgent will parse it
        return response_content # Return the full content dict

    except asyncio.TimeoutError:
        logger.error(f"[{session_id}] Explicit TimeoutError caught in execute_resolver_tool waiting for '{expected_response_type}'.")
        return {"success": False, "nodes": None, "message": "Error: Timeout waiting for client response."}

    except Exception as e:
        logger.exception(f"[{session_id}] Error during resolver tool WebSocket communication '{tool_name}': {e}")
        return {"success": False, "nodes": None, "message": f"Error during tool communication '{tool_name}': {e}"}
# --- END MODIFICATION ---


async def run_task_loop(
    goal: str,
    session_id: str,
    connection_manager: Optional[ConnectionManager] = None,
    agents: Optional[Dict[str, Any]] = None,
    tools: Optional[Dict[str, BaseTool]] = None,
    session_service: Optional[InMemorySessionService] = None,
    artifact_service: Optional[InMemoryArtifactService] = None,
    max_steps: int = MAX_STEPS,
    verbose: bool = False,
    **kwargs,
) -> Dict[str, Any]:
    """
    Run the main task execution loop for a given user goal.
    CoreAgent determines the next action, which might be a node request or
    an executable command.
    """
    start_time = time.time()
    logger.info(f"[{session_id}] Starting task loop for goal: {goal}")

    if not all([connection_manager, agents, tools, session_service, artifact_service]):
        missing = [name for name, dep in [("connection_manager", connection_manager), ("agents", agents), ("tools", tools), ("session_service", session_service), ("artifact_service", artifact_service)] if dep is None]
        error_msg = f"[{session_id}] Task loop cannot start: Missing dependencies: {', '.join(missing)}"
        logger.error(error_msg)
        return {"status": "failed", "error": error_msg, "task_id": session_id, "steps_taken": 0}

    try:
        session = session_service.get_session(app_name=APP_NAME, user_id=USER_ID, session_id=session_id)
        if not session: raise RuntimeError("ADK session not found")
        if session.state is None: session.state = {}
        logger.info(f"[{session_id}] Successfully loaded ADK session {session_id}")
    except Exception as e:
        logger.exception(f"[{session_id}] Failed to load ADK session: {e}")
        return {"status": "failed", "error": f"Failed to load session: {e}", "task_id": session_id, "steps_taken": 0}

    # Initialize state dictionary (use session.state directly)
    state = session.state # Use session.state as the state dictionary
    state.setdefault('task_history', [])
    state.setdefault('last_action_result', None)
    state.setdefault('latest_screenshot_bytes', None)
    state.setdefault('conversation_history', [])
    state.setdefault('goal', goal)
    state.setdefault('clarification_queue', asyncio.Queue())
    state.setdefault('last_node_request_correlation_id', None) # Initialize correlation ID tracker
    state.setdefault('recent_failed_tap_selectors', collections.deque(maxlen=MAX_RECENT_FAILED_SELECTORS))
    logger.info(f"[{session_id}] Session state initialized/updated for goal: {goal[:50]}...")

    core_agent = agents.get("core")
    resolver_agent = agents.get("resolver") # Keep resolver agent for potential future use
    if not core_agent: return {"status": "failed", "error": "CoreAgent not found.", "task_id": session_id, "steps_taken": 0}
    # Resolver agent check removed as it's not directly used in the main loop logic provided
    # if not resolver_agent: return {"status": "failed", "error": "ResolverAgent not found.", "task_id": session_id, "steps_taken": 0}

    await comms.send_status_update("Initializing automation agent...", session_id, connection_manager)

    final_status = "unknown"
    steps_taken = 0
    consecutive_action_failures = 0
    steps_without_state_change = 0
    previous_state_hash = None
    last_screenshot_hash = None
    last_nodes = None
    last_action_result: Dict[str, Any] | None = None
    last_core_agent_response: models.CoreAgentOutput | None = None

    # +++ NEW HELPER: Guardrail Function +++
    def _reject_non_clickable(action_dict: dict):
        action_type = action_dict.get("action_name")
        action_params = action_dict.get("action_params")
        if action_type != "tap_by_selector" or not action_params:
            return
        selector = action_params.get("selector")
        if not isinstance(selector, dict):
             raise ValueError("Invalid selector format in tap_by_selector parameters.")

        # Check the actionability flags from the selector the agent provided
        is_clickable = selector.get("is_clickable", False)
        is_long_clickable = selector.get("is_long_clickable", False) # Check long_clickable too for tap

        if not (is_clickable or is_long_clickable):
            # Extract minimal info for logging
            selector_info = selector.get('view_id') or selector.get('content_desc') or selector.get('text') or f"Bounds: {selector.get('bounds')}"
            raise ValueError(
                f"Blocked tap on NON-actionable node (clickable={is_clickable}, long_clickable={is_long_clickable}): {selector_info}"
            )
    # +++ END HELPER +++

    for i in range(max_steps):
        steps_taken = i + 1
        step_start_time = time.time()
        logger.info(f"[{session_id}] Starting step {steps_taken}/{max_steps}")
        step_success = True
        any_action_succeeded_this_step = False

        try:
            # --- 1. Observe --- #
            await comms.send_status_update("Getting current screen state...", session_id, connection_manager)
            # Pass state dictionary to comms functions that might need it
            screenshot_ok = await comms.request_and_process_screenshot(session_id, session, connection_manager) # Pass session which contains state
            if not screenshot_ok or not state.get("latest_screenshot_bytes"):
                final_status, error_msg = "failed", "Failed to capture screen state."
                await comms.send_error_update(error_msg, session_id, connection_manager)
                break
            screenshot_bytes = state["latest_screenshot_bytes"]
            current_state_hash = hashlib.sha256(screenshot_bytes).hexdigest()
            logger.debug(f"[{session_id}] Previous state hash: {previous_state_hash}, Current state hash: {current_state_hash}")

            # --- 2. Reason --- #
            await comms.send_status_update("Analyzing screen and deciding next step...", session_id, connection_manager)
            try:
                core_agent_input_messages = []
                if core_agent.prompt_template_text and "ERROR:" not in core_agent.prompt_template_text: core_agent_input_messages.append({"role": "system", "content": core_agent.prompt_template_text})
                core_agent_input_messages.extend(state.get('conversation_history', []))
                user_content_parts = []
                current_goal = state.get('goal', goal)
                user_content_parts.append({"type": "text", "text": f"Current Goal: {current_goal}"})
                last_result = state.get('last_action_result')
                if last_result:
                    last_action_str = json.dumps(last_result, indent=2) if isinstance(last_result, dict) else str(last_result)
                    user_content_parts.append({"type": "text", "text": f"\n\nLast Action Result:\n```json\n{last_action_str}\n```"})
                else:
                    user_content_parts.append({"type": "text", "text": "\n\nLast Action Result: None"})
                screenshot_b64 = base64.b64encode(screenshot_bytes).decode('utf-8') if screenshot_bytes else None
                if screenshot_b64: user_content_parts.append({"type": "image_url","image_url": {"url": f"data:image/jpeg;base64,{screenshot_b64}"}})
                core_agent_input_messages.append({"role": "user", "content": user_content_parts})

                core_agent_result = await core_agent.process_with_retry(
                    prompt_content=core_agent_input_messages,
                    parser_func=lambda resp_text: models.CoreAgentOutput.model_validate_json(resp_text),
                    force_json=True
                )
                if not core_agent_result or not core_agent_result["success"]: raise RuntimeError(f"CoreAgent.process_with_retry failed: {core_agent_result.get('error', 'Unknown error')}")
                parsed_output: Optional[models.CoreAgentOutput] = core_agent_result.get("content")
                if not parsed_output: raise RuntimeError("CoreAgent parser function failed to return content.")

            except Exception as agent_err:
                logger.exception(f"[{session_id}] Error calling or validating CoreAgent: {agent_err}")
                # Use the TaskState model for consistent structure
                state['task_state'] = models.TaskState(
                    goal=goal, # Include original goal
                    status="failed",
                    history=state.get('task_history', []),
                    current_step=steps_taken,
                    last_action_result={"success": False, "message": f"CoreAgent failed: {agent_err}"},
                    error_message=str(agent_err) # Store the error message
                ).model_dump()
                final_status = "failed"
                await comms.send_error_update(f"Agent failed to process: {agent_err}", session_id, connection_manager)
                break # Exit loop on agent failure

            # Extract info from the validated Pydantic model
            reasoning_obj = parsed_output.reasoning # This is now a ReasoningModel object
            action_name = parsed_output.action_name
            action_params = parsed_output.action_params if parsed_output.action_params is not None else {}

            # Log the structured reasoning
            logger.info(f"[{session_id}] CoreAgent Reasoning: Eval='{reasoning_obj.evaluation_previous_action}', Visual='{reasoning_obj.visual_analysis[:50]}...', Acc='{reasoning_obj.accessibility_analysis[:50]}...', SubGoal='{reasoning_obj.next_sub_goal}'")
            await comms.send_status_update(f"Plan: {reasoning_obj.next_sub_goal}", session_id, connection_manager)

            if not action_name:
                logger.warning(f"[{session_id}] CoreAgent did not propose any action (action_name is None).")
                await comms.send_warning_update("Agent finished or could not determine next step.", session_id, connection_manager)
                final_status = "completed" if "complete" in reasoning_obj.next_sub_goal.lower() else "stuck"
                state['last_action_result'] = {"success": True, "message": reasoning_obj.next_sub_goal}
                step_success = False
            else:
                # --- 3. Action Processing & Execution (REVISED) --- #
                try:
                    # --- Get proposed action ---
                    core_agent_output_dict = parsed_output.model_dump() # Use the dict form for checks
                    action_name = core_agent_output_dict.get("action_name")
                    action_params = core_agent_output_dict.get("action_params")

                    logger.info(f"[{session_id}] Proposed action: {action_name} with params: {action_params}")
                    await comms.send_status_update(f"Preparing to {action_name.replace('_', ' ')}...", session_id, connection_manager)

                    executable_action_type = action_name
                    executable_action_params = action_params if action_params is not None else {}

                    # --- >>> START FAILURE MEMORY & GUARDRAIL CHECKS <<< ---
                    should_force_replan = False
                    replan_reason = ""

                    if executable_action_type == "tap_by_selector":
                        current_selector = executable_action_params.get("selector")
                        if isinstance(current_selector, dict):
                            # Check Failure Memory (Compare selector dicts directly)
                            failed_selectors_list = list(state['recent_failed_tap_selectors']) # Get list copy
                            if current_selector in failed_selectors_list:
                                 logger.warning(f"[{session_id}] Action '{action_name}' with this exact selector failed recently. Forcing re-plan.")
                                 should_force_replan = True
                                 replan_reason = "Repeated tap on previously failed selector."
                            else:
                                # Check Guardrail (Only if not already rejected by memory)
                                try:
                                    _reject_non_clickable(core_agent_output_dict) # Pass the original dict output
                                except ValueError as guard_err:
                                    logger.warning(f"[{session_id}] GUARDRAIL: {guard_err} Forcing re-plan.")
                                    should_force_replan = True
                                    replan_reason = str(guard_err) # Use error as reason
                                    # Add to failure memory immediately upon guardrail block
                                    state['recent_failed_tap_selectors'].append(current_selector)

                    if should_force_replan:
                        step_success = False
                        state['last_action_result'] = {"success": False, "message": replan_reason}
                        # Skip action execution, jump to failure counting/loop end
                    # --- >>> END FAILURE MEMORY & GUARDRAIL CHECKS <<< ---

                    # --- Execute action only if not forced to re-plan ---
                    elif executable_action_type in NODE_REQUEST_ACTIONS:
                        request_type = executable_action_type # e.g., "request_nodes_by_text"
                        response_type_map = {
                            "request_nodes_by_text": (comms.TYPE_REQUEST_NODES_BY_TEXT, comms.TYPE_NODES_BY_TEXT_RESULT),
                            "request_interactive_nodes": (comms.TYPE_REQUEST_INTERACTIVE_NODES, comms.TYPE_INTERACTIVE_NODES_RESULT),
                            "request_all_nodes": (comms.TYPE_REQUEST_ALL_NODES, comms.TYPE_ALL_NODES_RESULT),
                            "request_clickable_nodes": (comms.TYPE_REQUEST_CLICKABLE_NODES, comms.TYPE_CLICKABLE_NODES_RESULT),
                        }
                        mapping = response_type_map.get(request_type)

                        if not mapping:
                            logger.error(f"[{session_id}] Internal error: Unknown node request action '{request_type}' mapping.")
                            state['last_action_result'] = {"success": False, "error": f"Internal mapping error for {request_type}"}
                            step_success = False
                        else:
                            client_request_type, expected_response_type = mapping
                            await comms.send_status_update(f"Executing {request_type.replace('_', ' ')}...", session_id, connection_manager)
                            try:
                                # --- Generate and Store Correlation ID for Node Request ---
                                request_correlation_id = str(uuid.uuid4())
                                state["last_node_request_correlation_id"] = request_correlation_id
                                logger.debug(f"[{session_id}] Storing correlation ID for {request_type}: {request_correlation_id}")
                                # --- End Correlation ID Handling ---

                                response_data = await connection_manager.send_request_and_wait_for_response(
                                    session_id=session_id,
                                    request_payload={"content": executable_action_params}, # Send params in content
                                    request_type=client_request_type, # Use the mapped client request type
                                    expected_response_type=expected_response_type,
                                    timeout=EVENT_TIMEOUT
                                )

                                if response_data is None:
                                    logger.error(f"[{session_id}] Timeout or connection error waiting for '{expected_response_type}'.")
                                    state['last_action_result'] = {"success": False, "message": f"Timeout or connection error waiting for {expected_response_type}."}
                                    step_success = False
                                else:
                                    # Store the entire response content (which should include success, message, nodes etc.)
                                    response_content = response_data.get("content", {})
                                    # --- NEW LOGGING: Log received nodes ---
                                    nodes_received = response_content.get("nodes")
                                    if isinstance(nodes_received, list):
                                        logger.debug(f"[{session_id}] Received {len(nodes_received)} nodes for '{request_type}': {json.dumps(nodes_received)}")
                                    else:
                                        logger.debug(f"[{session_id}] Received response for '{request_type}', but 'nodes' key not found or not a list in content: {response_content}")
                                    # --- END NEW LOGGING ---

                                    # --- Start Modification ---
                                    # Filter nodes based on interactability if the original action required it
                                    required_key = None
                                    # 'action_name' should be the CoreAgent's proposed action from the start of the loop step
                                    if action_name == 'tap_semantic_element':
                                        required_key = 'clickable'
                                    elif action_name == 'input_semantic_element':
                                         required_key = 'editable'
                                    # Add other semantic actions and their required keys if necessary

                                    if required_key and isinstance(response_content.get('nodes'), list):
                                        original_nodes = response_content['nodes']
                                        filtered_nodes = [node for node in original_nodes if isinstance(node, dict) and node.get(required_key) is True]
                                        if len(filtered_nodes) < len(original_nodes):
                                            logger.info(f"[{session_id}] Filtered {len(original_nodes) - len(filtered_nodes)} non-{required_key} nodes from '{request_type}' result. Keeping {len(filtered_nodes)}.")
                                            response_content['nodes'] = filtered_nodes # Update the content with filtered nodes
                                        else:
                                             logger.debug(f"[{session_id}] No nodes filtered based on key '{required_key}' for '{request_type}'.")
                                    # --- End Modification ---

                                    state['last_action_result'] = response_content
                                    log_nodes_msg = f"Nodes: {len(response_content.get('nodes', []))}" if isinstance(response_content.get('nodes'), list) else "Nodes: None/Invalid"
                                    logger.info(f"[{session_id}] Received '{expected_response_type}'. Success: {response_content.get('success')}, {log_nodes_msg}. Msg: '{response_content.get('message')}'")
                                    step_success = response_content.get("success", False)
                                    if step_success: any_action_succeeded_this_step = True # Data request counts as progress
                            except asyncio.TimeoutError:
                                logger.error(f"[{session_id}] Timeout waiting for '{expected_response_type}'.")
                                state['last_action_result'] = {"success": False, "message": f"Timeout waiting for {expected_response_type}."}
                                step_success = False
                            except Exception as req_err:
                                logger.exception(f"[{session_id}] Error sending/receiving '{request_type}': {req_err}")
                                state['last_action_result'] = {"success": False, "message": f"Error during {request_type}: {req_err}"}
                                step_success = False

                    # --- 3b. Execute Other Actions Directly --- #
                    else:
                        # --- NEW LOGGING: Log chosen index and correlation ID if applicable ---
                        if action_name.endswith("_by_index") and isinstance(executable_action_params, dict):
                            chosen_index = executable_action_params.get('index')
                            last_node_request_correlation_id = state.get("last_node_request_correlation_id", None) # Get ID from state
                            logger.info(f"[{session_id}] CoreAgent chose action '{action_name}' with index: {chosen_index}")
                            # --- ADDED DEBUG LOG ---
                            logger.debug(f"[{session_id}] Preparing {action_name} (index={chosen_index}). Last node request ID should be: {last_node_request_correlation_id}")
                            # --- END ADDED DEBUG LOG ---
                        # --- END NEW LOGGING ---

                        # (Existing logic for handling other actions like swipe_semantic, perform_global_action, *_by_index, done etc.)
                        await comms.send_status_update(f"Executing {executable_action_type.replace('_', ' ')}...", session_id, connection_manager)
                        # Pass state to send_action_to_client
                        action_result = await comms.send_action_to_client(
                            session_id=session_id,
                            session=session, # Pass session which contains state
                            manager=connection_manager,
                            action_type=executable_action_type, # This uses 'execute' type implicitly
                            parameters=executable_action_params
                        )
                        state['last_action_result'] = action_result
                        # --- NEW LOGGING: Log full execution result ---
                        logger.debug(f"[{session_id}] Received full execution_result for '{executable_action_type}': {json.dumps(action_result)}")
                        # --- END NEW LOGGING ---
                        action_content = action_result.get("content", {}) if isinstance(action_result, dict) else {}
                        action_success_flag = action_content.get("success", False)

                        # (Existing Swipe Semantic Fallback Logic ...)
                        is_unsupported_semantic_swipe = ( executable_action_type == "swipe_semantic" and not action_success_flag and "unsupported action" in action_content.get("message", "").lower())
                        if is_unsupported_semantic_swipe:
                            original_direction = executable_action_params.get("direction")
                            if original_direction in ["up", "down", "left", "right"]:
                                fallback_params = {"direction": original_direction}
                                logger.info(f"[{session_id}] Using direction-based 'swipe' fallback with direction: {original_direction}")
                                await comms.send_status_update(f"Executing direction-based swipe fallback (direction: {original_direction})...", session_id, connection_manager)
                                # Pass state to fallback call
                                action_result = await comms.send_action_to_client(session_id=session_id, session=session, manager=connection_manager, action_type="swipe", parameters=fallback_params)
                                state['last_action_result'] = action_result
                                action_content = action_result.get("content", {}) if isinstance(action_result, dict) else {}
                                action_success_flag = action_content.get("success", False)
                                if action_success_flag: logger.info(f"[{session_id}] Direction-based 'swipe' fallback succeeded.")
                                else: logger.warning(f"[{session_id}] Direction-based 'swipe' fallback failed. Result: {action_result}")
                            else:
                                logger.error(f"[{session_id}] Cannot perform swipe fallback: Original 'swipe_semantic' action was missing a valid 'direction' parameter. Params received: {executable_action_params}")
                                action_success_flag = False

                        if not action_success_flag:
                            logger.warning(f"[{session_id}] Action '{executable_action_type}' ultimately failed on client (Success Flag: {action_success_flag}). Final Result: {action_result}")
                            step_success = False
                            # --- >>> Add failed tap selector to memory <<< ---
                            if executable_action_type == "tap_by_selector" and isinstance(executable_action_params.get("selector"), dict):
                                failed_selector = executable_action_params["selector"]
                                if failed_selector not in list(state['recent_failed_tap_selectors']): # Avoid duplicates if guardrail already added it
                                    logger.info(f"[{session_id}] Recording failed tap selector: {failed_selector}")
                                    state['recent_failed_tap_selectors'].append(failed_selector)
                            # --- >>> END <<< ---
                        else:
                            logger.info(f"[{session_id}] Action '{executable_action_type}' executed successfully (potentially via fallback).")
                            any_action_succeeded_this_step = True

                        # Handle 'done' action
                        if executable_action_type == "done":
                            final_status = "completed" if action_success_flag else "failed"
                            logger.info(f"[{session_id}] 'done' action received. Final status set to: {final_status}")
                            if isinstance(executable_action_params, dict) and 'message' in executable_action_params:
                                state['last_action_result'] = {"success": action_success_flag, "message": executable_action_params['message']}
                            break # Exit loop on 'done'

                except Exception as action_proc_err:
                    logger.exception(f"[{session_id}] Error processing action {action_name}: {action_proc_err}. Skipping.")
                    step_success = False
                    state['last_action_result'] = {"success": False, "message": f"Error processing action {action_name}: {action_proc_err}"}

            # --- Update loop termination counters --- #
            if step_success and any_action_succeeded_this_step:
                consecutive_action_failures = 0
                if previous_state_hash and current_state_hash == previous_state_hash:
                    steps_without_state_change += 1
                    logger.info(f"[{session_id}] State unchanged for {steps_without_state_change} consecutive successful step(s).")
                    if steps_without_state_change >= MAX_STEPS_WITHOUT_STATE_CHANGE:
                        final_status, error_msg = "failed_no_progress", f"Task failed: No screen change detected for {MAX_STEPS_WITHOUT_STATE_CHANGE} successful steps."
                        await comms.send_error_update(error_msg, session_id, connection_manager)
                        break
                else:
                    steps_without_state_change = 0
                    previous_state_hash = current_state_hash
            elif not step_success:
                consecutive_action_failures += 1
                logger.warning(f"[{session_id}] Step failed. Consecutive failures: {consecutive_action_failures}")
                steps_without_state_change = 0
                if consecutive_action_failures >= MAX_CONSECUTIVE_ACTION_FAILURES:
                    final_status, error_msg = "failed_consecutive_errors", f"Task failed: {MAX_CONSECUTIVE_ACTION_FAILURES} consecutive steps failed to execute successfully."
                    await comms.send_error_update(error_msg, session_id, connection_manager)
                    break

            if final_status != "unknown": break

            # Store the response for the next iteration's history
            last_core_agent_response = parsed_output

            action_name = last_core_agent_response.action_name
            action_params = last_core_agent_response.action_params if last_core_agent_response.action_params is not None else {}
            reasoning = last_core_agent_response.reasoning # Store the ReasoningModel object
            screenshot_b64 = base64.b64encode(screenshot_bytes).decode('utf-8') if screenshot_bytes else None
            current_screenshot_hash = hashlib.sha256(screenshot_bytes).hexdigest() if screenshot_bytes else None
            current_screenshot_b64 = screenshot_b64

            task_history = state.get('task_history', [])
            task_history.append({
                "step": steps_taken,
                "goal": goal,
                "screenshot_b64": current_screenshot_b64,
                "nodes": last_nodes, # Nodes *before* the action was taken
                "core_agent_reasoning": reasoning.model_dump() if reasoning else "N/A", # Store reasoning as dict
                "action": action_name,
                "action_params": action_params,
                "client_result": state.get('last_action_result'), # Get result from state
                "timestamp": datetime.now().isoformat()
            })

            # Update Session State using TaskState model for consistency
            task_state = models.TaskState(
                goal=goal,
                status="running", # Assume running unless changed later
                history=task_history,
                current_step=steps_taken,
                max_steps=max_steps,
                consecutive_failures=consecutive_action_failures, # Updated based on step outcome
                max_consecutive_failures=MAX_CONSECUTIVE_ACTION_FAILURES,
                last_screenshot_b64=current_screenshot_b64, # Store B64 for potential future use
                last_nodes=last_nodes, # Nodes before the action was taken
                last_action_result=state.get('last_action_result'), # Get result from state
                last_core_agent_response=last_core_agent_response.model_dump() if last_core_agent_response else None, # Store the agent's decision as dict
                last_screenshot_hash=current_state_hash, # Store hash for state change detection
                last_node_request_correlation_id=state.get("last_node_request_correlation_id") # Include correlation ID
            )
            state['task_state'] = task_state.model_dump() # Store as dict
            # Redundant individual state updates are removed as they are part of task_state

            # Add delay specifically after GLOBAL_ACTION_HOME
            if action_name == 'perform_global_action' and action_params.get('action_id') == 'GLOBAL_ACTION_HOME':
                delay = 1 # seconds
                logger.info("[%s] GLOBAL_ACTION_HOME detected, pausing for %s seconds...", session_id, delay)
                await asyncio.sleep(delay)

            # Check if the state has changed significantly

        except Exception as e:
            logger.exception(f"[{session_id}] Unhandled error during task step {steps_taken}: {e}")
            # Use TaskState for error reporting
            task_state = models.TaskState(
                goal=goal,
                status="failed",
                history=state.get('task_history', []),
                current_step=steps_taken,
                last_action_result={"success": False, "message": f"Unhandled error: {e}"},
                error_message=str(e) # Add error message field
            ).model_dump()
            state['task_state'] = task_state
            final_status = "failed"
            # Ensure loop breaks on unhandled exception
            break

        step_duration = time.time() - step_start_time
        logger.info(f"[{session_id}] Step {steps_taken} finished in {step_duration:.2f} seconds.")

    # --- Loop End & Final Result --- #
    total_duration = time.time() - start_time
    if final_status == "unknown":
        if steps_taken >= max_steps:
            logger.warning(f"[{session_id}] Task exceeded max steps ({max_steps}).")
            final_status = "failed_max_steps"
            error_msg = f"Task failed: Exceeded maximum steps ({max_steps})."
            await comms.send_error_update(error_msg, session_id, connection_manager)
        else:
            logger.warning(f"[{session_id}] Task loop finished unexpectedly after {steps_taken} steps without explicit status.")
            final_status = "stuck"
            error_msg = "Task ended without explicit completion or failure status."
            await comms.send_warning_update(error_msg, session_id, connection_manager)

    final_result = {
        "status": final_status,
        "task_id": session_id,
        "steps_taken": steps_taken,
        "duration_seconds": round(total_duration, 2)
    }
    last_res = state.get('last_action_result', {})
    last_res_content = last_res.get("content", {}) if isinstance(last_res, dict) else {} # Look inside 'content'
    if final_status.startswith("fail") or final_status == "stuck":
        final_result["error"] = error_msg if 'error_msg' in locals() and error_msg else last_res_content.get("message", f"Task ended with status: {final_status}")
    elif "message" in last_res_content:
        final_result["message"] = last_res_content["message"]

    logger.info(f"[{session_id}] Task loop finished. Final result: {final_result}")

    # Ensure TaskState exists before final update
    if 'task_state' not in state or not isinstance(state['task_state'], dict):
        state['task_state'] = {} # Initialize if missing

    # Update final status in TaskState
    state['task_state']['status'] = final_status
    if final_status.startswith("fail") and 'error' in final_result:
        state['task_state']['error_message'] = final_result['error']

    # Persist final state by updating the session
    session_service.update_session(session) # session.state is the 'state' dictionary we've been using

    return final_result
