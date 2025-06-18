import asyncio
import logging
import time
import base64
import json
import os
import uuid
import hashlib
from collections import deque # Add deque import
from typing import Any, Coroutine, Dict, List, Optional, Tuple, cast

from google.adk.sessions import Session, SessionService
from google.adk.artifacts import ArtifactService, Artifact, ArtifactType
from pydantic import ValidationError

from . import task_manager_files as tm_files
from .sub_agents import core_agent, dialog_agent, input_classifier_agent
from .sub_agents.model_config import CORE_AGENT_LLM_CONFIG
from .models import (
    ActionModel,
    CoreAgentOutput,
    Selector,
    TaskState,
    RequestClarificationParams,
)
from main_files.connection_manager import ConnectionManager
from main_files.dependencies import get_connection_manager, get_adk_session_service

logger = logging.getLogger(__name__)


MAX_STEPS = 20
MAX_CONSECUTIVE_FAILURES = 3
NO_STATE_CHANGE_LIMIT = 5
WAIT_DEFAULT_SECONDS = 3


def _calculate_state_hash(screenshot_bytes: Optional[bytes], nodes: Optional[List[Selector]]) -> str:
    """Calculates a hash based on screenshot and nodes."""
    hasher = hashlib.sha256()
    if screenshot_bytes:
        hasher.update(screenshot_bytes)
    if nodes:
        # Sort nodes based on a consistent key (e.g., view_id or bounds)
        # to ensure hash stability regardless of order. Fallback to repr.
        try:
            sorted_nodes_str = json.dumps(
                sorted(
                    [n.model_dump(exclude_none=True) for n in nodes],
                    key=lambda x: (
                        x.get("view_id", ""),
                        x.get("bounds", {}).get("top", 0),
                        x.get("bounds", {}).get("left", 0),
                    ),
                ),
                sort_keys=True,
            )
            hasher.update(sorted_nodes_str.encode("utf-8"))
        except Exception: # Fallback if sorting/dumping fails
             hasher.update(repr(nodes).encode("utf-8"))
    return hasher.hexdigest()

# --- Start: New Helper for Selector Hashing ---
def _selector_hash(sel: dict) -> str:
    """Creates a stable hash for a selector dictionary based on stable fields."""
    stable_data = {
        key: sel.get(key)
        for key in ["view_id", "text", "content_desc"]
        if sel.get(key) is not None
    }
    # sort_keys ensures key order stability
    try:
        # Hash only the selected stable fields
        return hashlib.sha1(json.dumps(stable_data, sort_keys=True).encode()).hexdigest()
    except TypeError:
        # Fallback for unhashable types within selector (less likely now)
        return hashlib.sha1(repr(stable_data).encode()).hexdigest()
# --- End: New Helper for Selector Hashing ---

# --- Start: New Helper for Clickability Guardrail ---
def _validate_clickable(selector: dict):
    """Raises ValueError if the selector isn't clickable or long-clickable."""
    # Check for presence and truthiness of is_clickable or is_long_clickable
    is_click = selector.get("is_clickable", False)
    is_long_click = selector.get("is_long_clickable", False)
    if not (is_click or is_long_click):
        # Try to get an identifier for logging, default to the selector dict itself
        identifier = selector.get('view_id') or selector.get('content_desc') or selector.get('text') or str(selector)
        raise ValueError(f"Attempt to tap non-clickable selector: {identifier}")
# --- End: New Helper for Clickability Guardrail ---


async def run_task_loop(
    session_id: str,
    goal: str,
    agents: Dict[str, Any], # Consider defining a BaseAgent type hint
    global_tools: Dict[str, Any], # Consider defining a BaseTool type hint
    session_service: SessionService,
    artifact_service: ArtifactService,
    connection_manager: ConnectionManager,
) -> Dict[str, Any]:
    """Main loop for executing an automation task."""

    start_time = time.time()
    comms = tm_files.TaskCommunications(session_id, connection_manager)
    steps_taken = 0
    consecutive_failures = 0
    no_state_change_count = 0
    last_state_hash: Optional[str] = None

    logger.info(f"[{session_id}] Starting task loop for goal: {goal}")

    # --- Initialize session state ---
    try:
        session = await session_service.get_session(session_id=session_id)
        if not session:
            logger.error(f"[{session_id}] Session not found during task loop startup.")
            await comms.send_error_update("Session expired or not found.")
            return {"status": "failed_session_not_found", "task_id": session_id}

        state = cast(dict, session.state)
        state.setdefault("goal", goal)
        state.setdefault("history", [])
        state.setdefault("screenshots", [])
        state.setdefault("node_results", {})
        state.setdefault("last_action_result", {"success": True, "message": "Task initiated."})
        state.setdefault("failed_tap_hashes", deque(maxlen=20)) # Use deque

        logger.info(f"[{session_id}] Successfully loaded ADK session {session_id}")
        logger.debug(f"[{session_id}] Initial session state keys: {list(state.keys())}")
        await comms.send_status_update(f"Starting task for goal: {goal[:50]}...")

    except Exception as e:
        logger.exception(f"[{session_id}] Error initializing session state: {e}")
        await comms.send_error_update("Failed to initialize task state.")
        return {"status": "failed_initialization", "task_id": session_id, "error": str(e)}
    # --- End: Initialize session state ---


    core_agent_instance = agents.get("core")
    if not core_agent_instance:
         logger.error(f"[{session_id}] CoreAgent not found in provided agents.")
         await comms.send_error_update("Core agent configuration error.")
         return {"status": "failed_agent_config", "task_id": session_id}


    # --- Main Task Loop ---
    while steps_taken < MAX_STEPS:
        steps_taken += 1
        step_start_time = time.time()
        logger.info(f"[{session_id}] Starting step {steps_taken}/{MAX_STEPS}")
        await comms.send_status_update(f"Starting step {steps_taken}/{MAX_STEPS}")

        # Initialize step variables
        current_screenshot_bytes: Optional[bytes] = None
        current_nodes: Optional[List[Selector]] = None
        node_request_type: Optional[str] = None
        # --- START: Reset temporary variables for the new step ---
        action_name: Optional[str] = None
        action_params: Dict[str, Any] = {}
        action_result: Dict[str, Any] = {}
        parsed_output: Optional[CoreAgentOutput] = None
        # --- END: Reset ---

        try:
            # 1. Observe: Get Screenshot
            await comms.send_status_update("Getting current screen state...")
            screenshot_result = await comms.request_and_process_screenshot()
            if not screenshot_result["success"] or not screenshot_result["image_bytes"]:
                logger.warning(f"[{session_id}] Failed to get screenshot: {screenshot_result.get('message')}")
                await comms.send_warning_update("Could not get screen state.")
                consecutive_failures += 1
                if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                    logger.error(f"[{session_id}] Exceeded max consecutive failures ({MAX_CONSECUTIVE_FAILURES}) getting screenshot.")
                    await comms.send_error_update("Task failed: Unable to get screen state repeatedly.")
                    return {"status": "failed_max_failures", "task_id": session_id, "steps_taken": steps_taken, "duration_seconds": round(time.time() - start_time, 2)}
                continue # Try next step

            current_screenshot_bytes = screenshot_result["image_bytes"]
            screenshot_timestamp = screenshot_result.get("timestamp", time.time())
            # Optionally save screenshot artifact
            try:
                artifact_name = f"screenshot_step_{steps_taken}_{session_id}.png"
                screenshot_artifact = Artifact(
                    name=artifact_name, owner=session_id,
                    artifact_type=ArtifactType.SCREENSHOT, payload=current_screenshot_bytes
                )
                await artifact_service.save_artifact(screenshot_artifact)
                state["screenshots"].append({"step": steps_taken, "artifact_name": artifact_name, "timestamp": screenshot_timestamp})
                logger.debug(f"[{session_id}] Saved screenshot artifact: {artifact_name}")
            except Exception as artifact_exc:
                 logger.warning(f"[{session_id}] Failed to save screenshot artifact: {artifact_exc}")


            # -- Check for state change (using only screenshot for now) --
            current_state_hash = _calculate_state_hash(current_screenshot_bytes, None)
            logger.debug(f"[{session_id}] Previous state hash: {last_state_hash}, Current state hash: {current_state_hash}")
            if last_state_hash == current_state_hash:
                no_state_change_count += 1
                logger.warning(f"[{session_id}] State hash unchanged for {no_state_change_count} steps.")
                if no_state_change_count >= NO_STATE_CHANGE_LIMIT:
                    logger.error(f"[{session_id}] State unchanged for {NO_STATE_CHANGE_LIMIT} steps. Assuming task stuck.")
                    await comms.send_error_update("Task failed: UI state is not changing.")
                    return {"status": "failed_stuck", "task_id": session_id, "steps_taken": steps_taken, "duration_seconds": round(time.time() - start_time, 2)}
            else:
                no_state_change_count = 0 # Reset counter if state changes
            # Update hash *after* processing potential node results later in the loop
            # -- End Check for state change --


            # 2. Reason: Get next action from Core Agent
            await comms.send_status_update("Analyzing screen and deciding next step...")
            
            # --- START: Build Agent Context --- 
            last_action_str = json.dumps(state["last_action_result"], indent=2)
            user_content_parts = [
                {"type": "text", "text": f"Goal: {state['goal']}"},
            ]
            
            # --- START: Summarize History --- 
            summarized_history = []
            for entry in state["history"][-5:]: # Limit history size
                result = entry.get("action_result", {})
                result_summary = f"Success: {result.get('success')}" 
                if not result.get("success"):
                     result_summary += f", Msg: {str(result.get('message', ''))[:50]}..."
                
                summary_entry = {
                    "step": entry.get("step"),
                    "action": entry.get("action_name"),
                    # Optionally include sub_goal if concise
                    # "sub_goal": str(entry.get("sub_goal", "N/A"))[:50] + "...", 
                    "result": result_summary
                }
                summarized_history.append(summary_entry)
            
            if summarized_history:
                history_str = json.dumps(summarized_history, indent=2) 
                user_content_parts.append({"type": "text", "text": f"\n\nRecent History Summary (Last 5 steps):\n```json\n{history_str}\n```"})
            # --- END: Summarize History --- 

            # --- START: Delta Screenshot --- 
            if last_state_hash is not None and current_state_hash == last_state_hash:
                # State hasn't changed, don't send full image
                user_content_parts.append({"type": "text", "text": "\n\n[Screenshot Unchanged from Previous Step]"})
                logger.debug(f"[{session_id}] Screenshot unchanged, sending placeholder text.")
            elif current_screenshot_bytes:
                 # State changed OR it's the first step, send image
                 user_content_parts.append({"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": base64.b64encode(current_screenshot_bytes).decode('utf-8')}})
                 logger.debug(f"[{session_id}] Sending new screenshot data.")
            else:
                 # Should not happen if screenshot capture succeeded, but handle defensively
                 user_content_parts.append({"type": "text", "text": "\n\n[Error retrieving screenshot]"})
                 logger.warning(f"[{session_id}] Screenshot bytes were null when building context.")
            # --- END: Delta Screenshot --- 
            
            # Include summarized nodes if they were fetched in the previous step
            last_step_history = state["history"][-1] if state["history"] else None
            last_nodes = None
            # Check if the last action was a node request and successful
            if last_step_history and last_step_history.get('node_request_type') and last_step_history.get('action_result', {}).get('success'):
                 last_nodes_result = last_step_history.get("action_result", {})
                 last_nodes = last_nodes_result.get("nodes") # These are Selector objects
                 if last_nodes:
                      try:
                          # --- START: Bullet Point Node Summary --- 
                          node_summary_lines = []
                          for node_selector in last_nodes:
                              parts = []
                              # Use Selector methods/properties if they are Pydantic models
                              # Otherwise, access as dicts if they are already dicts
                              node_dict = node_selector.model_dump(exclude_none=True) if hasattr(node_selector, 'model_dump') else node_selector
                              
                              view_id = node_dict.get('view_id')
                              text = node_dict.get('text')
                              desc = node_dict.get('content_desc')
                              cls = node_dict.get('class_name', 'UnknownClass').split('.')[-1] # Short class name
                              bounds_dict = node_dict.get('bounds')
                              clickable = node_dict.get('is_clickable', False)
                              long_clickable = node_dict.get('is_long_clickable', False)

                              if view_id: parts.append(f"id={view_id}")
                              if text: parts.append(f'text="{str(text)[:30]}..."') # Truncate long text
                              if desc: parts.append(f'desc="{str(desc)[:30]}..."') # Truncate long desc
                              parts.append(f"cls={cls}")
                              if bounds_dict: parts.append(f"b={bounds_dict.get('left')},{bounds_dict.get('top')}-{bounds_dict.get('right')},{bounds_dict.get('bottom')}")
                              if clickable: parts.append("CLICKABLE")
                              if long_clickable: parts.append("LONG_CLICK")
                              
                              if parts: # Only add if we have some info
                                  node_summary_lines.append(f"- {' '.join(parts)}")
                          
                          if node_summary_lines:
                              nodes_str = "\n".join(node_summary_lines)
                              user_content_parts.append({"type": "text", "text": f"\n\nNodes Found ({last_step_history.get('node_request_type')} - Summary):\n{nodes_str}"}) # No JSON fences
                          else:
                               user_content_parts.append({"type": "text", "text": f"\n\nNodes Found ({last_step_history.get('node_request_type')}): [None with relevant details]"})
                          # --- END: Bullet Point Node Summary --- 
                          
                      except Exception as e:
                           logger.warning(f"[{session_id}] Failed to create node summary string for prompt: {e}")
                           user_content_parts.append({"type": "text", "text": f"\n\nNodes Found ({last_step_history.get('node_request_type')}): [Error summarizing nodes]"})
                 else: # nodes list was empty
                     user_content_parts.append({"type": "text", "text": f"\n\nNodes Found ({last_step_history.get('node_request_type')}): [List was empty]"})
            
            # Update hash calculation to use current_nodes if they were just fetched
            # This ensures the hash reflects the full state observed in this step
            current_state_hash = _calculate_state_hash(current_screenshot_bytes, last_nodes)
            last_state_hash = current_state_hash # Update for next iteration's comparison
            
            # Modify how last_action_result is presented (Keep as is)
            last_action_res = state["last_action_result"]

            # --- END: Build Agent Context ---
            
            agent_context = {
                # "goal": state["goal"], "history": state["history"],
                # "screenshot_bytes": current_screenshot_bytes,
                # "last_action_result": state["last_action_result"],
                "user_content": user_content_parts, # Pass structured content
                "available_actions": tm_files.get_core_agent_available_actions(),
            }
            response = await core_agent_instance.process_with_retry(context=agent_context)

            if not response or not response.get('success'):
                error_msg = response.get('error', 'CoreAgent failed to generate a valid response.')
                logger.error(f"[{session_id}] CoreAgent failed: {error_msg}")
                await comms.send_warning_update(f"Agent decision failed: {error_msg}")
                consecutive_failures += 1
                if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                     logger.error(f"[{session_id}] Exceeded max consecutive failures ({MAX_CONSECUTIVE_FAILURES}) due to agent errors.")
                     await comms.send_error_update("Task failed: Agent error.")
                     return {"status": "failed_max_failures", "task_id": session_id, "steps_taken": steps_taken, "duration_seconds": round(time.time() - start_time, 2)}
                continue

            try:
                parsed_output = CoreAgentOutput.model_validate(response.get('content'))
                logger.info(f"[{session_id}] CoreAgent Reasoning: Eval='{parsed_output.reasoning.evaluation[:50]}...', Visual='{parsed_output.reasoning.visual_analysis[:50]}...', Acc='{parsed_output.reasoning.accessibility_analysis[:50]}...', SubGoal='{parsed_output.reasoning.next_sub_goal[:50]}...'")
                await comms.send_status_update(f"Plan: {parsed_output.reasoning.next_sub_goal[:100]}")
            except ValidationError as e:
                logger.error(f"[{session_id}] CoreAgent output validation failed: {e}. Raw: {response.get('content')}")
                await comms.send_warning_update("Agent output format error.")
                consecutive_failures += 1
                continue

            action_name = parsed_output.action_name
            action_params = parsed_output.action_params or {}
            logger.info(f"[{session_id}] Proposed action: {action_name} with params: {action_params}")


            # 3. Route & Execute Action
            # --- START: Moved Guardrail/Memory Check Here ---
            try:
                if action_name == "tap_by_selector":
                    selector_to_tap = action_params.get("selector")
                    if not selector_to_tap or not isinstance(selector_to_tap, dict):
                        raise ValueError("Invalid or missing selector for tap_by_selector.")
                    # 1. Check Clickability Guardrail
                    _validate_clickable(selector_to_tap) # Raises ValueError if not clickable
                    # 2. Check Failure Memory
                    failed_hashes = cast(deque, state["failed_tap_hashes"])
                    current_hash = _selector_hash(selector_to_tap)
                    if current_hash in failed_hashes:
                        raise ValueError(f"Selector hash {current_hash} previously failed.")
                # If checks pass or action is not tap_by_selector, proceed normally

            except ValueError as ve:
                # Guardrail triggered or selector previously failed
                logger.warning(f"[{session_id}] Pre-execution check failed: {ve}; forcing re-plan.")
                # Update last_action_result with specific reason
                state['last_action_result'] = {"success": False, "message": f"Action blocked by guardrail/memory: {ve}."}
                # Skip the rest of the step logic and force agent re-evaluation
                await session_service.save_session(session) # Save the updated state before continuing
                continue
            # --- END: Moved Guardrail/Memory Check Here ---

            # --- Now process the action (which passed the guardrails if it was a tap) ---
            if action_name in tm_files.NODE_REQUEST_ACTIONS:
                node_request_type = action_name
                request_params = action_params
                await comms.send_status_update(f"Preparing to {action_name.replace('_', ' ')}...")
                await comms.send_status_update(f"Executing {action_name.replace('_', ' ')}...")
                node_corr_id = str(uuid.uuid4())
                state['node_results'][node_corr_id] = None
                logger.debug(f"[{session_id}] Storing correlation ID for {action_name}: {node_corr_id}")
                node_response = await comms.request_nodes_from_client(
                    request_type=action_name, parameters=request_params, correlation_id=node_corr_id
                )
                action_result = node_response
                if node_response.get("success"):
                    current_nodes = node_response.get("nodes", [])
                    state['node_results'][node_corr_id] = current_nodes
                    logger.info(f"[{session_id}] Received '{action_name}_result'. Success: {node_response.get('success')}, Nodes: {len(current_nodes)}. Msg: '{node_response.get('message', '')[:50]}...'")
                    current_state_hash = _calculate_state_hash(current_screenshot_bytes, current_nodes)
                    last_state_hash = current_state_hash
                    consecutive_failures = 0
                else:
                    logger.warning(f"[{session_id}] Failed to get nodes for {action_name}: {node_response.get('message')}")
                    # Hash remains based on screenshot only

            elif action_name in tm_files.EXECUTABLE_ACTIONS:
                executable_action_type = action_name
                executable_action_params = action_params
                
                # --- START: Add Clickability Guardrail for Long Click --- 
                if executable_action_type == "long_click_by_selector":
                    selector_to_long_click = executable_action_params.get("selector")
                    if not selector_to_long_click or not isinstance(selector_to_long_click, dict):
                        raise ValueError("Invalid or missing selector for long_click_by_selector.")
                    # Check if the node is actually long-clickable
                    if not selector_to_long_click.get("is_long_clickable", False):
                         identifier = selector_to_long_click.get('view_id') or selector_to_long_click.get('content_desc') or selector_to_long_click.get('text') or str(selector_to_long_click)
                         raise ValueError(f"Attempt to long-click non-long-clickable selector: {identifier}")
                # --- END: Add Clickability Guardrail for Long Click --- 
                
                await comms.send_status_update(f"Preparing to {action_name.replace('_', ' ')}...")
                await comms.send_status_update(f"Executing {action_name.replace('_', ' ')}...")
                action_result = await comms.send_action_to_client(
                    action_type=executable_action_type, parameters=executable_action_params
                )
                action_success_flag = action_result.get("success", False)
                if action_success_flag:
                    logger.info(f"[{session_id}] Action '{action_name}' executed successfully (potentially via fallback).")
                    consecutive_failures = 0
                else:
                    logger.warning(f"[{session_id}] Action '{action_name}' ultimately failed on client (Success Flag: False). Final Result: {action_result}")
                    consecutive_failures += 1
                    if executable_action_type == "tap_by_selector":
                        failed_selector = executable_action_params.get("selector", {})
                        if failed_selector:
                            try:
                                 failed_hash = _selector_hash(failed_selector)
                                 failed_hashes = cast(deque, state["failed_tap_hashes"])
                                 if failed_hash not in failed_hashes:
                                     failed_hashes.append(failed_hash)
                                 logger.info(f"[{session_id}] Recorded failed tap hash: {failed_hash} for selector: {failed_selector}")
                            except Exception as hash_exc:
                                 logger.error(f"[{session_id}] Error hashing/recording failed selector: {hash_exc}")

            elif action_name == "wait":
                wait_seconds = action_params.get("duration_seconds", WAIT_DEFAULT_SECONDS)
                wait_seconds = max(1, min(int(wait_seconds), 10))
                logger.debug(f"[{session_id}] Starting wait for {wait_seconds} seconds.") # log before wait
                await asyncio.sleep(wait_seconds)
                logger.debug(f"[{session_id}] Finished wait for {wait_seconds} seconds.") # log after wait
                action_result = {"success": True, "message": f"Waited {wait_seconds} second(s)."}
                consecutive_failures = 0

            elif action_name == "done":
                final_message = action_params.get("message", "Task finished.")
                logger.info(f"[{session_id}] Task marked as done by agent. Message: {final_message}")
                await comms.send_status_update(f"Task finished: {final_message}")
                return {"status": "completed", "task_id": session_id, "message": final_message, "steps_taken": steps_taken, "duration_seconds": round(time.time() - start_time, 2)}

            elif action_name == "request_clarification":
                 try:
                     clarification_params = RequestClarificationParams.model_validate(action_params)
                     question = clarification_params.question; options = clarification_params.options
                     logger.info(f"[{session_id}] Agent requested clarification: {question} Options: {options}")
                     await comms.send_clarification_request(question, options)
                     action_result = {"success": False, "message": "Clarification requested, but feature not fully implemented."}
                     logger.warning(f"[{session_id}] Clarification requested, but handling is not implemented. Ending task.")
                     await comms.send_error_update("Task cannot proceed: Agent needs clarification (feature pending).")
                     return {"status": "failed_clarification_needed", "task_id": session_id, "message": question, "steps_taken": steps_taken, "duration_seconds": round(time.time() - start_time, 2)}
                 except ValidationError as e:
                     logger.error(f"[{session_id}] Invalid parameters for request_clarification: {e}")
                     action_result = {"success": False, "message": "Invalid clarification request format from agent."}
                     consecutive_failures += 1

            else:
                logger.warning(f"[{session_id}] Unknown action type received from CoreAgent: {action_name}")
                action_result = {"success": False, "message": f"Unknown action type: {action_name}"}
                consecutive_failures += 1


            # 4. Update State & History
            state["last_action_result"] = action_result
            history_entry = {
                 "step": steps_taken, "goal": state["goal"],
                 "sub_goal": parsed_output.reasoning.next_sub_goal if parsed_output else "N/A",
                 "action_name": action_name, "action_params": action_params,
                 "node_request_type": node_request_type,
                 "action_result": action_result, "timestamp": time.time()
            }
            state["history"].append(history_entry)
            await session_service.save_session(session)
            logger.debug(f"[{session_id}] Session state saved after step {steps_taken}")


            # 5. Check Loop Termination Conditions
            if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                logger.error(f"[{session_id}] Exceeded max consecutive failures ({MAX_CONSECUTIVE_FAILURES}).")
                await comms.send_error_update(f"Task failed: Too many consecutive errors ({consecutive_failures}).")
                return {"status": "failed_max_failures", "task_id": session_id, "steps_taken": steps_taken, "duration_seconds": round(time.time() - start_time, 2)}

            step_duration = round(time.time() - step_start_time, 2)
            logger.info(f"[{session_id}] Step {steps_taken} finished in {step_duration} seconds.")


            # --- MODIFICATION START ---
            # Add a small delay after specific actions to allow UI to settle
            if action_name in ["tap_by_selector", "launch_app"]:
                logger.debug(f"[{session_id}] Waiting 1 second after {action_name} for UI to settle...")
                await asyncio.sleep(1)
            # --- MODIFICATION END ---


        except asyncio.TimeoutError:
            logger.error(f"[{session_id}] Timeout occurred during step {steps_taken}.")
            await comms.send_error_update("Task failed: Action timed out.")
            return {"status": "failed_timeout", "task_id": session_id, "steps_taken": steps_taken, "duration_seconds": round(time.time() - start_time, 2)}
        except Exception as e:
            logger.exception(f"[{session_id}] Unhandled exception during step {steps_taken}: {e}")
            await comms.send_error_update(f"Task failed: Internal server error during step {steps_taken}.")
            return {"status": "failed_exception", "task_id": session_id, "error": str(e), "steps_taken": steps_taken, "duration_seconds": round(time.time() - start_time, 2)}

    # Loop finished (max steps reached)
    logger.warning(f"[{session_id}] Max steps ({MAX_STEPS}) reached. Ending task.")
    final_result = {"status": "max_steps_reached", "task_id": session_id, "message": f"Task stopped after reaching max steps ({MAX_STEPS})."}
    logger.debug(f"[{session_id}] Starting 1s sleep after max steps.") # log before sleep
    await asyncio.sleep(1) # Allow final messages to be sent
    logger.debug(f"[{session_id}] Finished 1s sleep after max steps.") # log after sleep
    return final_result