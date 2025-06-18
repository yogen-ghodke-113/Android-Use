# adk-droid/android_use_agent/task_manager.py
"""
Task Manager: Orchestrates the execution loop for an Android automation task
using ADK Runner and shared services.
(Vision-First Architecture)
"""
import logging
import asyncio
from typing import Dict, Any, Optional, List
import json
import base64
from fastapi import APIRouter, WebSocket, Depends, HTTPException, UploadFile, File, Form, Path, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

# --- Import Project Modules ---
# Import using absolute paths from the project root (assuming adk-droid is accessible)
# NOTE: These imports might be removed if this file only contains HTTP routes
# from android_use_agent.task_manager_files import task_constants as const
# from android_use_agent.task_manager_files import task_comms as comms
# from android_use_agent.task_manager_files import task_actions as actions
# from android_use_agent.models import CoreAgentOutput, ActionModel # Assuming these are still needed
# from android_use_agent.agent import get_agent, get_tool # Use helpers to get instances


# --- ADK Runner and Services --- # (Imports might only be needed if types are used directly)
# from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.adk.artifacts import InMemoryArtifactService
# from google.genai import types as genai_types

# --- Standard Library Imports ---
# (Keep os, json, Path if still directly used here, otherwise remove)

# --- Ensure session manager is imported --- # (Needed for Depends(get_session))
# from .session_manager import get_session # Import get_session directly <-- REMOVE THIS IMPORT
from .connection_manager import ConnectionManager # Might need this if we need to interact with WS
# --- Re-add necessary dependency imports for Depends() ---\
# Add get_adk_session_service for task_status route
from .dependencies import get_connection_manager, get_adk_session_service 
# --- REMOVE ALL Dependency Imports - Rely on Depends() in routes ---\
# from .dependencies import get_adk_artifact_service # REMOVED get_session_manager
# from .dependencies import get_connection_manager # Keep this one if manager is used directly here

# --- Import Tool (If directly used) --- #
# from ..android_use_agent.tools.vision_analyzer import VisionAnalysisTool # Import the tool
# ^^^ Commented out as it might not be used directly here

# --- Import app instance from main for router registration ---\
from main import app 

# --- Router Definition ---\
router = APIRouter()
logger = logging.getLogger(__name__)

# --- Pydantic Models ---\
class ScreenshotResponse(BaseModel):
    status: str
    message: str
    screenshot_id: Optional[str] = None

# class TaskStatusResponse(BaseModel): <-- REMOVE START
#     status: str
#     message: str
#     result: Optional[Dict[str, Any]] = None
# <-- REMOVE END

# --- API Routes ---\

@router.get("/health")
async def health_check():
    return {"status": "OK"}

# @router.post("/result/{session_id}") <-- REMOVE START
# async def post_result(session_id: str, result: Dict[str, Any]):
#     logger.info(f"Received REST result for session {session_id}: {result}")
#     session_data = get_session(session_id)
#     session_data["last_action_result"] = result
#     # Signal the task loop that the result has arrived
#     if "result_event" in session_data:
#         session_data["result_event"].set()
#         logger.debug(f"Set result_event for session {session_id}")
#     else:
#         logger.warning(f"result_event not found in session data for {session_id}")
#     return {"status": "result received"}
# <-- REMOVE END

# @router.post("/screenshot/{session_id}", response_model=ScreenshotResponse) <-- REMOVE START
# async def upload_screenshot(
#     session_id: str,
#     screenshot_file: UploadFile = File(...),
#     manager: ConnectionManager = Depends(get_connection_manager), # Correct: Use Depends for manager
#     session_manager_dep: dict = Depends(get_session) # Example if get_session is a dependency
# ):
#     """Receive screenshot upload from client."""
#     logger.info(f"Screenshot upload received for session {session_id}")
#
#     session_data = get_session(session_id) # Use the direct import
#     if not session_data:
#         logger.error(f"Session {session_id} not found via get_session")
#         raise HTTPException(status_code=404, detail="Session not found")
#
#     try:
#         # Read file bytes
#         file_bytes = await screenshot_file.read()
#         if not file_bytes:
#              raise ValueError("Uploaded file is empty")
#         logger.info(f"Successfully read {len(file_bytes)} bytes from screenshot_file")
#
#         # --- Save bytes using artifact service (Optional - Example) ---
#         # filename = f"screenshot_{datetime.now().isoformat()}.jpg"
#         # try:
#         #     await artifact_service.save_artifact(APP_NAME, USER_ID, session_id, filename, file_bytes)
#         #     logger.info(f"[{session_id}] Saved screenshot as artifact: {filename}")
#         # except Exception as artifact_err:
#         #      logger.error(f"[{session_id}] Failed to save screenshot artifact: {artifact_err}")
#               # Decide if this is critical - maybe continue anyway?
#
#         # --- Put bytes into the queue using session_data obtained via get_session ---
#         queue = session_data.get("screenshot_queue")
#         if queue:
#             await queue.put(file_bytes)
#             logger.info(f"Successfully put screenshot ({len(file_bytes)} bytes) in queue for session {session_id}")
#             return {"status": "success", "message": "Screenshot received and queued", "session_id": session_id, "size": len(file_bytes)}
#         else:
#             logger.error(f"Screenshot queue not found for session {session_id} in session_data")
#             raise HTTPException(status_code=500, detail="Internal error: Screenshot queue missing")
#
#     except Exception as e:
#         logger.exception(f"[{session_id}] Error processing/saving screenshot: {e}")
#         raise HTTPException(status_code=500, detail=f"Error processing screenshot: {e}")
# <-- REMOVE END

# @router.get("/task_status/{session_id}", response_model=TaskStatusResponse) <-- REMOVE START
# async def get_task_status(
#     session_id: str,
#     session_service: InMemorySessionService = Depends(get_adk_session_service) 
# ): 
#     \"\"\"Check the status of a running task using ADK Session state.\"\"\"
#     # ... (Implementation removed)
# <-- REMOVE END


# --- Include Router in App (at the end of the file) ---
app.include_router(router, prefix="/api")
