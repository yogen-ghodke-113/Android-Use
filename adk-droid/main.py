# adk-droid/main.py
"""
Main FastAPI application entry point for the Android-Use ADK Server.

Initializes the FastAPI app, loads configuration, sets up middleware,
imports agents/tools, includes API routes from main_files, adds the
WebSocket endpoint, and runs the Uvicorn server.
"""

# --- 1. Configuration and Logging (Crucial to run first) ---
# Import from the config module within main_files. This executes setup_logging()
# and load_environment() immediately upon import.
from main_files import config # Corrected: Use implicit relative import if main.py is run as a script in adk-droid/
# Or use absolute import if running as a module: from android_use_agent.main_files import config
logger = config.logger # Use the logger configured in config.py
# No longer need to import ensure_adk_session since we're handling session creation directly

# Define consistent identifiers for ADK sessions - one source of truth
APP_NAME = "android-use-agent"  # Must match config.py
USER_ID = "default-user"        # Must match config.py

# --- Standard Library Imports ---
import os
import asyncio # Keep if used directly (e.g., future top-level async tasks)
import json # Import json for parsing messages
import logging
import uvicorn # <<< ADDED IMPORT
import base64
import types
from typing import Dict, Any, List, Optional # Import List and Optional
from contextlib import asynccontextmanager # <<< ADDED IMPORT
import re # Add re import for stripping markdown

# --- FastAPI Imports ---
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Request, Depends
from fastapi.middleware.cors import CORSMiddleware

# --- ADK Services (needed for lifespan event) ---
from google.adk.sessions import InMemorySessionService, Session # Import Session for type hints
from google.adk.artifacts import InMemoryArtifactService

# --- Project Tool Imports (Place specific tool imports needed for type hints here) ---
# REMOVE the try/except - it's misleading as the tool loads fine later.
# try:
#     # **FIX:** Use explicit imports relative to adk-droid base or project root
#     # Use relative import from the directory containing main.py
#     from android_use_agent.tools.coordinate_calibrator import CoordinateCalibrationTool
# except ImportError:
#     logger.error("Failed to import CoordinateCalibrationTool. Calibration functionality will be limited.")
#     # Define a dummy class to prevent NameError if import fails, but functionality is broken
#     class CoordinateCalibrationTool: pass

# --- Lifespan Event Handler (NEW using asynccontextmanager) ---
@asynccontextmanager
async def lifespan(app: FastAPI):
    # Code to run on startup
    logger.info("Executing FastAPI startup via lifespan manager: Initializing singletons...")

    # Initialize ConnectionManager
    from main_files.connection_manager import ConnectionManager # Import class locally
    app.state.connection_manager = ConnectionManager()
    logger.info(f"ConnectionManager created. ID: {id(app.state.connection_manager)}")

    # Initialize ADK Services
    app.state.session_service = InMemorySessionService()
    app.state.artifact_service = InMemoryArtifactService()
    logger.info("ADK Services created. SessionService ID: %s, ArtifactService ID: %s", id(app.state.session_service), id(app.state.artifact_service))

    # --- MONKEY PATCH FOR DEBUGGING SESSION STATE ---
    # Add a helper to dump the internal session dictionary
    def _dump_sessions(self, label="State"):
        # Access the internal dictionary (specific to InMemorySessionService v0.2)
        sessions_dict = getattr(self, '_sessions', {})
        print(f"--- SESSION DUMP ({label}) ---")
        # Use repr for unambiguous key representation
        print(f"{sessions_dict!r}")
        print(f"--- END DUMP ({label}) ---")

    # Bind the method to the singleton instance
    app.state.session_service.dump = types.MethodType(_dump_sessions, app.state.session_service)
    logger.info("Monkey-patched .dump() method onto SessionService instance for debugging.")
    # --- END MONKEY PATCH ---

    # Initialize services
    # ---> FIX: Call get_tools without the removed 'request' argument <---
    tools_dict = get_tools() # REMOVED request=None
    if not tools_dict:
         logger.warning("Lifespan: No tools dictionary available from dependencies.")
         # Initialize with fallback if tools failed completely
         tools_dict = {FallbackVisionTool().name: FallbackVisionTool()}
    # Fallback Vision Tool (Optional - Check if needed based on config/plan)
    # if VisionAnalysisTool.name not in tools_dict: # CORRECTED
    #     logger.warning("VisionAnalysisTool not found, adding FallbackVisionTool.")
    #     tools_dict[VisionAnalysisTool.name] = FallbackVisionTool() # CORRECTED

    # Store services and managers in app state
    # ... (rest of lifespan) ...

    yield # Application runs here

    # Code to run on shutdown (optional)
    logger.info("Executing FastAPI shutdown via lifespan manager.")
    # Add any necessary cleanup here, e.g.:
    # if hasattr(app.state, 'connection_manager'):
    #     await app.state.connection_manager.shutdown() # Assuming a shutdown method exists

# --- FastAPI App Initialization (using lifespan) ---
app = FastAPI(
    title="Android-Use ADK Server (Refactored)",
    description="Modular FastAPI server for Android automation agent.",
    version="1.1.0",
    lifespan=lifespan # Use the lifespan manager
)
logger.info(f"FastAPI app initialized: {app.title} v{app.version}")

# --- Project Imports (after app creation) ---
# Import managers, dependencies, models, routes etc. from main_files sub-package
# REMOVE Import the global manager instance - it's now in app.state
# from main_files.connection_manager import manager as connection_manager
# REMOVE: Import the router object itself
# from main_files.api_routes import router as api_router # Import the APIRouter instance
# ADD: Import the module so its code (including router registration) runs
from main_files import api_routes
# Import dependencies needed for the WebSocket route registration
# Import ConnectionManager class for type hinting
from main_files.connection_manager import ConnectionManager
from main_files.dependencies import (
    get_connection_manager,
    get_tools,
    get_agents,
    get_adk_session_service, # Import ADK service getters
    get_adk_artifact_service,
    get_task_loop
)
# Import the model for type hint
#from android_use_agent.models import CalibrationDataModel, DeviceMetricsModel
# --- Import Task Loop --- (NEW)
from android_use_agent.task_manager import run_task_loop

# --- Import Agent Logic, Tools, and Task Loop ---
# Define fallback tool classes here for robustness during import errors

# Fallback for the NEW VisionAnalysisTool
class FallbackVisionTool:
    """Simple fallback implementation if the real VisionAnalysisTool fails to import."""
    def __init__(self):
        self.name = "vision_analysis_tool"
        logger.warning(f"Using FallbackVisionTool ({self.name}) - vision functionality will be limited.")

    async def run_async(self, image_bytes: bytes, session_id: str | None = None):
        logger.warning(f"FallbackVisionTool ({self.name}).run_async called - this is a placeholder.")
        return {"status": "error", "message": f"Tool {self.name} failed to load", "rois": []}

# Keep old fallback only if screenshot_descriptor is still used somewhere temporarily
# class FallbackScreenshotTool:
#     """Simple fallback implementation if the real tool fails to import."""
#     def __init__(self):
#         self.name = "screenshot_descriptor"
#         logger.warning("Using FallbackScreenshotTool - screenshot functionality will be limited.")
#
#     async def run_async(self, args: dict, tool_context=None):
#         logger.warning("FallbackScreenshotTool.run_async called - this is a placeholder.")
#         # Return minimal valid structure to avoid breaking task loop expecting a dict
#         return {"description": {"context": "Fallback description (tool import error)", "sections": []}}

# --- Add Middleware ---
# CORS Middleware (Allow all origins for development, restrict in production)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], # Example: ["http://localhost:3000", "https://your-frontend-domain.com"]
    allow_credentials=True,
    allow_methods=["*"], # Allows all standard methods
    allow_headers=["*"], # Allows all headers
)
logger.info("CORS middleware added (allow_origins='*'). Restrict in production.")

# --- Include API Routes ---
# REMOVE: Router is now included within api_routes.py upon import
# app.include_router(api_router, prefix="/api") # Add /api prefix to all routes in the router
# logger.info("API router included under /api prefix.")

# --- Add WebSocket Route --- (Includes Helper for Background Task)

# --- WebSocket Task Definitions ---

async def _receiver(ws: WebSocket, command_q: asyncio.Queue, result_q: Optional[asyncio.Queue], session_id: str, manager_instance: ConnectionManager, session_service: InMemorySessionService):
    """Task to receive messages and distribute them to command queue or ConnectionManager futures."""
    try:
        while True:
            try:
                data = await ws.receive_text()
                logger.debug(f"[{session_id}] Raw message received: {data[:150]}...")
                message = json.loads(data)
                msg_type = message.get("type")
                correlation_id = message.get("correlation_id") # Check for correlation ID

                # --- UPDATED ROUTING LOGIC --- #
                if correlation_id:
                    # This is a response to a specific request
                    logger.debug(f"[{session_id}] Received response for correlation_id {correlation_id}, type '{msg_type}'. Resolving future...")
                    # --- ADDED LOGGING --- #
                    logger.debug(f"[{session_id}] Attempting to await resolve_pending_request for {correlation_id}...")
                    # --- END ADDED LOGGING --- #
                    # Resolve the pending request using the manager instance
                    await manager_instance.resolve_pending_request(correlation_id, message)
                elif msg_type in ["classify_input", "disconnect_request", "ping"]:
                    # Original command queue messages (exclude calibration_data if handled elsewhere or deprecated)
                    await command_q.put(message)
                    logger.debug(f"[{session_id}] Message type '{msg_type}' put on command_q.")
                elif msg_type == "clarification_response":
                    response_text = message.get("content", {}).get("message")
                    logger.info(f"[{session_id}] Received clarification response: '{response_text}'. Signalling task loop.")
                    try:
                        session = session_service.get_session(app_name=APP_NAME, user_id=USER_ID, session_id=session_id)
                        if session and session.state:
                            session.state['clarification_response'] = response_text
                            event = session.state.get('clarification_event')
                            if event and isinstance(event, asyncio.Event):
                                event.set()
                                logger.debug(f"[{session_id}] Clarification event set.")
                            else:
                                logger.warning(f"[{session_id}] Received clarification response, but no valid event found in session state to signal.")
                        else:
                            logger.warning(f"[{session_id}] Received clarification response, but no session/state found to store it.")
                    except Exception as e_clarify:
                        logger.error(f"[{session_id}] Error handling clarification response: {e_clarify}", exc_info=True)
                # --- NEW: Handle session_connect --- #
                elif msg_type == "session_connect":
                    logger.info(f"[{session_id}] Received session_connect message. Client connection confirmed.")
                    # No specific action needed, just acknowledge the message type
                # --- END NEW --- #
                elif msg_type in ["execution_result", "ui_dump_result", "indexed_ui_dump_result", "client_error", "screenshot_result"]:
                    # Results/Data expected to have correlation_id, handled above by resolve_pending_request
                    # If they arrive without one, it's unexpected.
                    if not correlation_id:
                         logger.warning(f"[{session_id}] Received message type '{msg_type}' WITHOUT correlation_id. Discarding. Content: {str(message)[:200]}...")
                # REMOVE Calibration Data Handling if fully deprecated
                # elif msg_type == "calibration_data":
                #     await command_q.put(message)
                #     logger.debug(f"[{session_id}] Message type '{msg_type}' put on command_q.")
                else:
                    logger.warning(f"[{session_id}] Received message with unknown/unrouted type OR unhandled correlation: {msg_type}, CorID: {correlation_id}. Discarding.")
                # --- END UPDATED ROUTING LOGIC --- #

            except json.JSONDecodeError:
                logger.error(f"[{session_id}] Received invalid JSON, discarding: {data[:150]}...")
            except Exception as e:
                logger.error(f"[{session_id}] Error in receiver task: {e}", exc_info=True)
                await command_q.put({"type": "_internal_error", "error": str(e)})
                break

    except WebSocketDisconnect:
        logger.info(f"[{session_id}] WebSocket disconnected (Receiver Task).")
        await command_q.put({"type": "_disconnect"})
    except Exception as e:
        logger.error(f"[{session_id}] Unhandled exception in receiver task: {e}", exc_info=True)
        try:
            await command_q.put({"type": "_disconnect"})
        except Exception:
            pass # Ignore errors during shutdown signalling
    finally:
        logger.info(f"[{session_id}] Receiver task finished.")

# Import constants needed for timeout
from android_use_agent.task_manager_files import task_constants as const

@app.websocket("/ws/{session_id}")
async def websocket_endpoint(
    websocket: WebSocket,
    session_id: str,
    manager_instance: ConnectionManager = Depends(get_connection_manager),
    tools_instance: Dict[str, Any] = Depends(get_tools),
    agents_instance: Dict[str, Any] = Depends(get_agents),
    session_service: InMemorySessionService = Depends(get_adk_session_service),
    artifact_service: InMemoryArtifactService = Depends(get_adk_artifact_service)
):
    """Handles WebSocket connections, message routing, and task execution."""
    await manager_instance.connect(websocket, session_id)
    # Ensure ADK session exists or is created
    # Use ensure_adk_session_dependency or manually handle it here
    try:
        # 1. Try to get existing session first - USE CONSISTENT KEYS
        session = session_service.get_session(app_name=APP_NAME, user_id=USER_ID, session_id=session_id)

        if session:
            logger.info(f"[{session_id}] Existing ADK session retrieved. State keys: {list(session.state or {})}. State: {session.state}")
            # Ensure state dict exists if session was retrieved but state is None (edge case?)
            if session.state is None:
                session.state = {}
                logger.warning(f"[{session_id}] Retrieved session had None state, initialized to empty dict.")
        else:
            # 2. If not found, create a new one using the factory method - USE CONSISTENT KEYS
            logger.info(f"[{session_id}] No existing session found. Creating new ADK session with ID: {session_id}")
            session = session_service.create_session(
                app_name=APP_NAME,
                user_id=USER_ID,
                session_id=session_id, # Explicitly pass the ID from the path
                state={} # Start with empty state
            )
            logger.info(f"[{session_id}] New ADK Session object created by service. ID: {session.id}. State keys: {list(session.state or {})}. State: {session.state}")

    except Exception as e:
        logger.error(f"[{session_id}] Failed to get/create ADK session: {e}", exc_info=True)
        # Corrected disconnect call signature - only needs session_id
        await manager_instance.disconnect(session_id)
        return

    logger.info(f"WebSocket connected for session: {session_id}")

    # Create queues for this connection
    command_q = asyncio.Queue()

    # Start receiver task
    receiver_task = asyncio.create_task(
        _receiver(websocket, command_q, None, session_id, manager_instance, session_service)
    )

    # Main processing loop for this connection
    current_task_loop = None # Track the active task loop (if any)

    try:
        logger.debug(f"[{session_id}] Main handler waiting for message from command_q...")
        while True:
            message = await command_q.get()
            msg_type = message.get("type")
            logger.debug(f"[{session_id}] Main handler processing command type: {msg_type}")

            if msg_type == "_disconnect" or msg_type == "disconnect_request":
                logger.info(f"[{session_id}] Received disconnect signal ({msg_type}).")
                break
            elif msg_type == "_internal_error":
                logger.error(f"[{session_id}] Internal error signal received: {message.get('error')}")
                break
            elif msg_type == "ping":
                await websocket.send_text(json.dumps({"type": "pong", "session_id": session_id}))
            elif msg_type == "classify_input":
                content = message.get("content", {})
                user_input = content.get("text", "") # CORRECTED: Use "text" key
                logger.info(f"[{session_id}] Received classify_input: '{user_input}'")

                # Use the InputClassifierAgent
                classifier_agent = agents_instance.get("input_classifier")
                if not classifier_agent:
                    logger.error(f"[{session_id}] InputClassifierAgent not found!")
                    # Fallback: Assume TASK if classifier missing
                    task_type = "TASK"
                    extracted_goal = user_input # <-- Assign goal in error path
                else:
                    try:
                        # ---> CORRECTED INVOCATION for custom BaseAgent <---
                        # Use the classify_input method provided by the InputClassifierAgent
                        classification_result_dict = await classifier_agent.classify_input(
                            message=user_input,
                            conversation_history=None # Pass history if available/needed
                        )

                        # Parse the result from the dictionary returned by classify_input
                        # (Assuming it returns {'classification': '...', 'extracted_goal': '...'})
                        classification = classification_result_dict.get("classification", "Chat") # Default to Chat
                        extracted_goal = classification_result_dict.get("extracted_goal") # Goal is None for Chat
                        task_type = classification.strip().upper() if classification else "CHAT"

                        # TODO: Pass extracted_goal to run_task_loop if classification is TASK
                        logger.info(f"[{session_id}] Input classified as: {task_type}, Goal: {extracted_goal}")

                    except AttributeError as e:
                        logger.error(f"[{session_id}] Error during input classification: {e}")
                        # Fallback if classification fails
                        task_type = "TASK"
                        extracted_goal = user_input # Pass original input as goal on error
                    except Exception as e:
                        logger.error(f"[{session_id}] Unexpected error during classification: {e}")
                        task_type = "TASK"
                        extracted_goal = user_input # Pass original input as goal on error

                # --- Route based on classification --- #
                if task_type == "TASK":
                    if current_task_loop and not current_task_loop.done():
                        logger.warning(f"[{session_id}] Received new goal while a task is already running. Ignoring new goal.")
                        await manager_instance.send_personal_message(session_id, {
                            "type": "status",
                            "message": "Task already in progress. Please wait for it to complete."
                        })
                    else:
                        goal_to_use = extracted_goal or user_input # Use extracted goal if available
                        logger.info(f"[{session_id}] Starting run_task_loop for classified TASK goal: {goal_to_use[:50]}...")
                        # Send initial status update
                        await manager_instance.send_personal_message(session_id, {
                            "type": "status",
                            "message": f"Starting task for goal: '{goal_to_use}'..."
                        })

                        # Create the background task
                        current_task_loop = asyncio.create_task(
                            run_task_loop(
                                goal=goal_to_use, # Pass the potentially extracted goal
                                session_id=session_id,
                                connection_manager=manager_instance,
                                agents=agents_instance,
                                tools=tools_instance,
                                session_service=session_service,
                                artifact_service=artifact_service,
                            )
                        )
                        # Store task reference in ADK session state
                        session.state['current_task_loop'] = current_task_loop # <-- STORE IN session.state
                        logger.info(f"[{session_id}] Task loop created: {current_task_loop.get_name()} and stored in session state.")
                elif task_type == "CHAT":
                    logger.info(f"[{session_id}] Routing input to DialogAgent.")
                    dialog_agent = agents_instance.get("dialog")
                    if dialog_agent:
                        dialog_result = await dialog_agent.generate_dialog_response(
                            user_message=user_input,
                            conversation_history=None
                        )
                        if dialog_result.get("success"):
                            final_reply = dialog_result.get("message", "Sorry, I could not process that.")
                            await manager_instance.send_personal_message(session_id, {
                                "type": "dialog_response",
                                "message": final_reply # Send the extracted message
                            })
                        else:
                            error_msg = dialog_result.get("message", "Dialog agent failed.")
                            logger.error(f"[{session_id}] DialogAgent failed: {error_msg}")
                            await manager_instance.send_personal_message(session_id, {
                                "type": "client_error", 
                                "message": f"Dialog agent error: {error_msg}"
                            })
                    else:
                        logger.error(f"[{session_id}] DialogAgent not found!")
                        await manager_instance.send_personal_message(session_id, {"type": "client_error", "message": "Dialog agent is unavailable."})
                elif task_type == "CLARIFICATION":
                    logger.warning(f"[{session_id}] Received input classified as CLARIFICATION, but task loop isn't waiting for it. Treating as DIALOG for now.")
                    # Fallback to dialog agent for now
                    dialog_agent = agents_instance.get("dialog")
                    if dialog_agent:
                        dialog_result = await dialog_agent.generate_dialog_response(
                            user_message=user_input,
                            conversation_history=None
                        )
                        if dialog_result.get("success"):
                            final_reply = dialog_result.get("message", "Sorry, I could not process that.")
                            await manager_instance.send_personal_message(session_id, {
                                "type": "dialog_response",
                                "message": final_reply # Send the extracted message
                            })
                        else:
                            error_msg = dialog_result.get("message", "Clarification agent failed.")
                            logger.error(f"[{session_id}] Clarification/DialogAgent fallback failed: {error_msg}")
                            await manager_instance.send_personal_message(session_id, {
                                "type": "client_error", 
                                "message": f"Clarification agent error: {error_msg}"
                            })
                    else:
                        logger.error(f"[{session_id}] DialogAgent (as fallback for Clarification) not found!")
                        await manager_instance.send_personal_message(session_id, {"type": "client_error", "message": "Clarification agent is unavailable."})
                # Add handling for the client's initial connection message
                elif msg_type == "session_connect":
                    logger.info(f"[{session_id}] Received session_connect message from client. Acknowledged.")
                    # No specific action needed here as session is handled on connect
                    pass 
                else:
                    # Log unhandled message types
                    logger.warning(f"[{session_id}] Received message with unknown/unrouted type OR unhandled correlation: {msg_type}, CorID: {message.get('correlation_id')}. Discarding.")

            command_q.task_done()
            logger.debug(f"[{session_id}] Main handler waiting for message from command_q...")

    except asyncio.CancelledError:
        logger.info(f"[{session_id}] Main handler task cancelled.")
    except Exception as e:
        # Catch any unexpected errors during the main loop
        logger.error(f"[{session_id}] Unexpected error in WebSocket handler: {e}", exc_info=True)
    finally:
        logger.info(f"[{session_id}] WebSocket handler finalizing. Cleaning up...")
        # Cancel receiver task
        if receiver_task and not receiver_task.done():
            receiver_task.cancel()
            try:
                await receiver_task # Allow cancellation to propagate
            except asyncio.CancelledError:
                logger.debug(f"[{session_id}] Receiver task cancelled successfully.")
            except Exception as final_recv_err:
                logger.error(f"[{session_id}] Error awaiting cancelled receiver task: {final_recv_err}")

        # Cancel running task loop if it exists
        task_to_cancel = session.state.get('current_task_loop') # <-- GET FROM session.state
        if task_to_cancel and not task_to_cancel.done():
            logger.info(f"[{session_id}] Cancelling active task loop...")
            task_to_cancel.cancel()
            try:
                await task_to_cancel # Wait for cancellation
            except asyncio.CancelledError:
                logger.info(f"[{session_id}] Task loop cancelled successfully.")
            except Exception as task_cancel_err:
                logger.error(f"[{session_id}] Error awaiting cancelled task loop: {task_cancel_err}")
        # Remove task reference from session state
        session.state.pop('current_task_loop', None) # <-- REMOVE FROM session.state

        # Disconnect WebSocket from manager
        await manager_instance.disconnect(session_id)
        # clear_session(session_id) # REMOVE Call to old session manager
        logger.info(f"[{session_id}] WebSocket handler cleanup complete.")


logger.info("WebSocket endpoint '/ws/{session_id}' added.")

# --- Run Server ---
if __name__ == "__main__":
    logger.info("Starting Uvicorn server...")
    # Determine reload status (example, might come from env var)
    # Defaulting reload to False if not set or invalid, safer for production/testing
    reload_status_str = os.environ.get("UVICORN_RELOAD", "false").lower()
    reload_status = reload_status_str == "true"
    logger.info(f"Uvicorn reload status: {reload_status}")

    # Make sure port is an integer
    try:
        server_port = int(os.environ.get("PORT", 8080)) # Default to 8080 if not set
    except ValueError:
        logger.warning("Invalid PORT environment variable. Defaulting to 8080.")
        server_port = 8080
    logger.info(f"Uvicorn server port: {server_port}")

    # Get the string name of the log level for Uvicorn (e.g., 'info', 'debug')
    # Ensure logger has been initialized in config.py before this point
    # uvicorn_log_level = logging.getLevelName(logger.level).lower() # Incorrect: Gets level of specific logger (NOTSET)
    # Get the effective level from the root logger after basicConfig has run
    root_logger_level = logging.getLogger().getEffectiveLevel()
    uvicorn_log_level = logging.getLevelName(root_logger_level).lower()
    logger.info(f"Uvicorn log level set to: {uvicorn_log_level}")

    uvicorn.run(
        "main:app",             # Reference the app instance in this file (module_name:app_instance_name)
        host="0.0.0.0",         # Listen on all available network interfaces
        port=server_port,       # Use the integer port
        reload=reload_status,   # Enable/disable reload based on env var
        log_level=uvicorn_log_level # Use the string log level name for uvicorn
        # Consider adding ssl_keyfile and ssl_certfile for HTTPS if needed later
        # ssl_keyfile="./key.pem",
        # ssl_certfile="./cert.pem"
    )