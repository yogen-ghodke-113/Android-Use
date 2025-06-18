import logging
from typing import Dict, Any
from fastapi import Depends, HTTPException, status, Request, WebSocket # Added WebSocket
from fastapi.security import OAuth2PasswordBearer

# Singletons stored in app state (initialized during lifespan)
from main_files.connection_manager import ConnectionManager
from google.adk.sessions import SessionService
from google.adk.artifacts import BaseArtifactService

# --- REMOVE Top-level agent/tool imports --- #
# from android_use_agent.agent import agents as initialized_agents, global_tools_dict as initialized_tools
# from android_use_agent.sub_agents.base_agent import BaseAgent

logger = logging.getLogger(__name__)

def get_connection_manager(request: Request) -> ConnectionManager:
    manager = getattr(request.app.state, 'connection_manager', None)
    if manager is None:
        logger.error("ConnectionManager not found in app state!")
        raise HTTPException(status_code=500, detail="Internal Server Error: Connection Manager not initialized")
    return manager

def get_adk_session_service(request: Request) -> SessionService:
    service = getattr(request.app.state, 'adk_session_service', None)
    if service is None:
        logger.error("SessionService not found in app state!")
        raise HTTPException(status_code=500, detail="Internal Server Error: Session Service not initialized")
    return service

def get_adk_artifact_service(request: Request) -> BaseArtifactService:
    service = getattr(request.app.state, 'adk_artifact_service', None)
    if service is None:
        logger.error("ArtifactService not found in app state!")
        logger.warning("ArtifactService not found, returning None.")
    return service

# --- Agent and Tool Dependency Providers --- #

def get_agents(request: Request) -> Dict[str, Any]: # Use Any for agent type hint now
    """Dependency provider for initialized agents. Imports agents lazily."""
    try:
        from android_use_agent.agent import agents as initialized_agents
        from android_use_agent.sub_agents.base_agent import BaseAgent # Keep BaseAgent import if needed for type hint
    except ImportError as e:
        logger.error(f"get_agents: Failed lazy import: {e}")
        return {}

    if not initialized_agents:
        logger.warning("get_agents called but agents failed to initialize or import.")
        return {}
    # TODO: Still need to refactor agent initialization regarding services.
    return initialized_agents

def get_tools(request: Request) -> Dict[str, Any]: # Keep Any for tool type hint
    """Dependency provider for initialized global tools. Imports tools lazily."""
    try:
        from android_use_agent.agent import global_tools_dict as initialized_tools
    except ImportError as e:
        logger.error(f"get_tools: Failed lazy import: {e}")
        return {}

    if not initialized_tools:
        logger.warning("get_tools called but tools failed to initialize or import.")
        return {}
    return initialized_tools

# --- Task Loop (Lazy Import) --- #
def get_task_loop(request: Request):
    try:
        from android_use_agent.task_manager import run_task_loop
        return run_task_loop
    except ImportError as e:
        logger.error(f"get_task_loop: Failed lazy import of run_task_loop: {e}")
        raise HTTPException(status_code=500, detail="Internal Server Error: Task manager component failed to load.")

# --- Firebase Auth (Likely Unused/Deprecated) --- #
# ... (no changes needed) ... 