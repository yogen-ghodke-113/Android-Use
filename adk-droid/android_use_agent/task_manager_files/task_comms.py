# android_use_agent/task_manager_files/task_comms.py
"""
Helper functions for communication with the client during a task.
"""
import asyncio
import base64
import logging
from typing import Dict, Any, Optional

# Import ADK Session for type hint
from google.adk.sessions import Session

# Import constants from the same directory
from . import task_constants as const # Adjusted import

logger = logging.getLogger(__name__)

# --- NEW Accessibility Node Types ---
TYPE_REQUEST_NODES_BY_TEXT = "request_nodes_by_text"
TYPE_REQUEST_INTERACTIVE_NODES = "request_interactive_nodes"
TYPE_REQUEST_ALL_NODES = "request_all_nodes"
TYPE_NODES_BY_TEXT_RESULT = "nodes_by_text_result"
TYPE_INTERACTIVE_NODES_RESULT = "interactive_nodes_result"
TYPE_ALL_NODES_RESULT = "all_nodes_result"

# --- NEW List Packages Types ---
TYPE_REQUEST_LIST_PACKAGES = "request_list_packages"
TYPE_LIST_PACKAGES_RESULT = "list_packages_result"

# --- Message Types for Request/Response ---
TYPE_EXECUTE = "execute"
TYPE_EXECUTION_RESULT = "execution_result"
TYPE_REQUEST_SCREENSHOT = "request_screenshot"
TYPE_SCREENSHOT_RESULT = "screenshot_result"
TYPE_REQUEST_INDEXED_UI_DUMP = "request_indexed_ui_dump"
TYPE_INDEXED_UI_DUMP_RESULT = "indexed_ui_dump_result"

# --- Sending Updates to Client --- #

async def send_status_update(status_text: str, session_id: str, manager: Any, dialog_agent: Optional[Any] = None):
    """Sends a status update message to the client."""
    if not manager:
        logger.warning(f"[{session_id}] Cannot send status update: ConnectionManager missing.")
        return
    logger.info(f"[{session_id}] Status: {status_text}")
    try:
        # Optional: Format using DialogAgent if available
        # formatted_text = await dialog_agent.format_message(status_text) if dialog_agent else status_text
        await manager.send_personal_message(session_id, {"type": "status", "message": status_text})
    except Exception as e:
        logger.exception(f"[{session_id}] Failed to send status update: {e}")

async def send_warning_update(warning_text: str, session_id: str, manager: Any, dialog_agent: Optional[Any] = None):
    """Sends a warning message to the client."""
    if not manager:
        logger.warning(f"[{session_id}] Cannot send warning update: ConnectionManager missing.")
        return
    logger.warning(f"[{session_id}] Warning: {warning_text}")
    try:
        # Optional: Format using DialogAgent if available
        # formatted_text = await dialog_agent.format_message(warning_text) if dialog_agent else warning_text
        await manager.send_personal_message(session_id, {"type": "warning", "message": warning_text})
    except Exception as e:
        logger.exception(f"[{session_id}] Failed to send warning update: {e}")

async def send_error_update(error_text: str, session_id: str, manager: Any, dialog_agent: Optional[Any] = None):
    """Sends an error message to the client."""
    if not manager:
        logger.warning(f"[{session_id}] Cannot send error update: ConnectionManager missing.")
        return
    logger.error(f"[{session_id}] Error Update: {error_text}")
    try:
        # Optional: Format using DialogAgent if available
        # formatted_text = await dialog_agent.format_message(error_text) if dialog_agent else error_text
        await manager.send_personal_message(session_id, {"type": "error", "message": error_text})
    except Exception as e:
        logger.exception(f"[{session_id}] Failed to send error update: {e}")

# --- Requesting Data from Client (Using ConnectionManager Request/Response) --- #

async def request_and_process_screenshot(session_id: str, session: Session, manager: Any) -> bool:
    """
    Requests a screenshot via ConnectionManager, waits for the result,
    decodes it, and stores it in session.state.

    Returns True if successful, False otherwise.
    """
    if not manager:
        logger.error(f"[{session_id}] Cannot request screenshot: ConnectionManager missing.")
        if session.state: session.state["latest_screenshot_bytes"] = None
        return False

    logger.info(f"[{session_id}] Requesting screenshot via ConnectionManager.")
    if session.state: session.state["latest_screenshot_bytes"] = None

    try:
        screenshot_result = await manager.send_request_and_wait_for_response(
            session_id=session_id,
            request_payload={}, # No specific payload needed for screenshot request
            request_type=TYPE_REQUEST_SCREENSHOT,
            expected_response_type=TYPE_SCREENSHOT_RESULT,
            timeout=const.CLIENT_RESPONSE_TIMEOUT
        )

        if screenshot_result is None:
            logger.error(f"[{session_id}] Did not receive screenshot_result (timeout or error).")
            return False

        # Extract content and check for client-side errors first
        content = screenshot_result.get("content", {})
        if not content:
             logger.error(f"[{session_id}] Received screenshot_result with missing 'content'.")
             return False
        if not content.get("success", True): # Assume success if key missing, but check if False
             error_msg = content.get("message", "Client indicated screenshot failure.")
             logger.error(f"[{session_id}] Client-side error during screenshot: {error_msg}")
             return False

        # Extract and decode image data
        image_base64 = content.get("image_base64")
        if not image_base64:
            logger.error(f"[{session_id}] Received screenshot_result with missing 'image_base64' in content.")
            return False

        try:
            image_bytes = base64.b64decode(image_base64)
            if session.state: session.state["latest_screenshot_bytes"] = image_bytes
            logger.info(f"[{session_id}] Decoded and stored screenshot ({len(image_bytes)} bytes).")
            return True
        except base64.binascii.Error as b64_error:
            logger.error(f"[{session_id}] Failed to decode base64 screenshot data: {b64_error}")
            return False
        except Exception as decode_error:
            logger.exception(f"[{session_id}] Unexpected error decoding screenshot: {decode_error}")
            return False

    except Exception as e:
        logger.exception(f"[{session_id}] Error during request_and_process_screenshot: {e}")
        if session.state: session.state["latest_screenshot_bytes"] = None
        return False

async def send_action_to_client(
    session_id: str,
    session: Session,
    manager: Any,
    action_type: str,
    parameters: Dict[str, Any]
) -> Optional[Dict[str, Any]]:
    """
    Sends an action execution request to the client via ConnectionManager
    and waits for the execution result, storing it in session.state.
    (Payload structure REVERTED BACK to include 'content' key)

    Args:
        session_id: The session ID.
        session: The ADK Session object (used for storing last result in session.state).
        manager: The ConnectionManager instance.
        action_type: The type of action to execute (e.g., 'tap', 'launch_app').
        parameters: Dictionary of parameters for the action.

    Returns:
        The execution result dictionary from the client, or None if timeout/error.
    """
    if not manager:
        logger.error(f"[{session_id}] Cannot send action '{action_type}': ConnectionManager missing.")
        return {"success": False, "message": "Internal server error: ConnectionManager missing."}

    logger.info(f"[{session_id}] Sending action '{action_type}' to client with params: {parameters}")

    # Prepare the payload for the 'execute' request
    # *** REVERTED BACK: Put action_type/parameters back inside a 'content' key ***
    request_payload = {
        "content": {
            "action_type": action_type,
            "parameters": parameters
        }
        # 'type' and 'correlation_id' will be added by send_request_and_wait_for_response
    }

    try:
        timeout = const.CLIENT_RESPONSE_TIMEOUT # Use configured timeout
        action_result = await manager.send_request_and_wait_for_response(
            session_id=session_id,
            request_payload=request_payload, # Send the payload containing the 'content' key
            request_type=TYPE_EXECUTE,       # The message type is 'execute'
            expected_response_type=TYPE_EXECUTION_RESULT,
            timeout=timeout
        )

        if action_result is None:
            logger.error(f"[{session_id}] Did not receive execution_result for action '{action_type}' (timeout or error).")
            # Synthesize a failure result if None is returned
            result_to_store = {"success": False, "message": f"Timeout or error waiting for '{action_type}' result from client."}
        else:
            # Log the received result
            result_content = action_result.get('content', {})
            log_success = result_content.get('success', 'N/A') if isinstance(result_content, dict) else 'Invalid Content'
            log_message = result_content.get('message', 'No message') if isinstance(result_content, dict) else 'Invalid Content'
            logger.info(f"[{session_id}] Received execution_result for '{action_type}': Success={log_success}, Msg='{log_message[:100]}...'" )
            result_to_store = action_result # Use the actual received result

        # Update session state with the result (even if it's a synthesized failure)
        if session.state: session.state['last_action_result'] = result_to_store
        return result_to_store

    except Exception as e:
        logger.exception(f"[{session_id}] Unexpected error in send_action_to_client for '{action_type}': {e}")
        # Synthesize a failure result on unexpected exception
        error_result = {"success": False, "message": f"Server error sending/waiting for '{action_type}' result: {e}"}
        if session.state: session.state['last_action_result'] = error_result
        return error_result

# WebSocket Message Types
TYPE_SESSION_CONNECT = "session_connect"
TYPE_CLASSIFY_INPUT = "classify_input"
TYPE_CLIENT_ERROR = "client_error"

# Accessibility Data Request/Result Types (Client <-> Server)
TYPE_REQUEST_NODES_BY_TEXT = "request_nodes_by_text"
TYPE_NODES_BY_TEXT_RESULT = "nodes_by_text_result"
TYPE_REQUEST_INTERACTIVE_NODES = "request_interactive_nodes"
TYPE_INTERACTIVE_NODES_RESULT = "interactive_nodes_result"
TYPE_REQUEST_ALL_NODES = "request_all_nodes"
TYPE_ALL_NODES_RESULT = "all_nodes_result"

# Status Update Message Type (Server -> Client)
TYPE_STATUS_UPDATE = "status_update"

# New Request and Result Types
TYPE_REQUEST_CLICKABLE_NODES = "request_clickable_nodes"
TYPE_CLICKABLE_NODES_RESULT = "clickable_nodes_result"
