# adk-droid/main_files/dependencies.py
"""FastAPI dependency injection setup."""
import logging
from typing import Dict, Any, Callable
from typing_extensions import Annotated
from fastapi import Depends, Request, HTTPException, WebSocket
from starlette.websockets import WebSocket
from starlette.requests import Request
from starlette.requests import HTTPConnection
import google.auth.transport.requests
import google.oauth2.id_token
from google.cloud import firestore
from fastapi.security import OAuth2PasswordBearer

# --- ADK Services --- #
from google.adk.sessions import InMemorySessionService # Use concrete implementation
from google.adk.artifacts import InMemoryArtifactService, BaseArtifactService # Use concrete implementation

# --- Project Components --- #
# Imports are now done lazily inside the getter functions below

logger = logging.getLogger(__name__) # Define logger after removing imports that might use it

# Don't import task_manager here - it will be imported on-demand
# Removed: from android_use_agent.task_manager import run_task_loop

# --- Connection & Session Managers (Existing) ---
from .connection_manager import ConnectionManager
# from .session_manager import get_session # Keep this if still used for simple session dict access

# --- Import the FastAPI app instance from main ---
# REMOVE: Import at top level causes circular dependency
# from main import app 

# Singletons (Existing) - REMOVE Global Instantiation
# connection_manager = ConnectionManager()

# --- Remove Singleton Instantiation Here ---

# --- Dependency Provider Functions ---

# Existing Providers
def get_agents() -> Dict[str, Any]:
    """Dependency provider for initialized agents. Imports agents lazily."""
    try:
        # Import within the function to avoid top-level circular imports
        from android_use_agent.agent import agents as initialized_agents
        if not initialized_agents:
             logger.warning("get_agents called, but agent initialization might have failed earlier. Returning empty dict.")
             return {}
        return initialized_agents
    except ImportError as e:
        logger.error(f"get_agents: Failed to import agents: {e}. Returning empty dict.", exc_info=True)
        return {}
    except Exception as e:
        logger.error(f"get_agents: Unexpected error: {e}. Returning empty dict.", exc_info=True)
        return {}

def get_tools() -> Dict[str, Any]:
    """Dependency provider for initialized global tools. Imports tools lazily."""
    try:
        # Import within the function
        from android_use_agent.agent import global_tools_dict as initialized_tools
        if not initialized_tools:
             logger.warning("get_tools called, but tool initialization might have failed earlier. Returning empty dict.")
             return {}
        return initialized_tools
    except ImportError as e:
        logger.error(f"get_tools: Failed to import tools: {e}. Returning empty dict.", exc_info=True)
        return {}
    except Exception as e:
        logger.error(f"get_tools: Unexpected error: {e}. Returning empty dict.", exc_info=True)
        return {}

# --- Correct Definition (Using HTTPConnection for Compatibility) ---
def get_connection_manager(conn: HTTPConnection) -> ConnectionManager:
    """Retrieves the singleton ConnectionManager instance from app.state via HTTPConnection."""
    # Use conn.app.state
    if not hasattr(conn.app.state, 'connection_manager'):
        # This should be initialized by the startup event
        logger.critical("ConnectionManager not found in app.state! Startup event likely failed.")
        raise RuntimeError("ConnectionManager not initialized.")
    
    manager = conn.app.state.connection_manager
    logger.debug(f"Accessing connection_manager: id={id(manager)} from conn.app.state")
    return manager

# --- Modified Providers for ADK Services (Using HTTPConnection for wider compatibility) ---
def get_adk_session_service(conn: HTTPConnection) -> InMemorySessionService:
    """Retrieves the singleton SessionService instance from app.state via HTTPConnection."""
    # Use conn.app.state
    if not hasattr(conn.app.state, 'session_service'):
        raise RuntimeError("SessionService not initialized in app state. Ensure startup event ran.")
    logger.debug(f"Accessing session_service: id={id(conn.app.state.session_service)} from conn.app.state")
    return conn.app.state.session_service

def get_adk_artifact_service(conn: HTTPConnection) -> BaseArtifactService:
    """Retrieves the singleton ArtifactService instance from app.state via HTTPConnection."""
    # Use conn.app.state
    if not hasattr(conn.app.state, 'artifact_service'):
        raise RuntimeError("ArtifactService not initialized in app state. Ensure startup event ran.")
    logger.debug(f"Accessing artifact_service: id={id(conn.app.state.artifact_service)} from conn.app.state")
    return conn.app.state.artifact_service

# Function to import and return run_task_loop on demand
def get_task_loop(request: Request) -> Callable:
    """Import and return the run_task_loop function from task_manager.
    
    This lazy import breaks circular dependencies.
    """
    try:
        from android_use_agent.task_manager import run_task_loop
        return run_task_loop
    except ImportError as e:
        logger.error(f"Failed to import run_task_loop: {e}")
        # Return a dummy function that logs the error
        async def dummy_task_loop(*args, **kwargs):
            logger.error("Task loop unavailable - import failed")
            return {"status": "error", "error": "Task loop unavailable", "task_id": kwargs.get("session_id", "unknown")}
        return dummy_task_loop

# --- Annotated Dependencies --- (Signatures of getters changed, but names are the same)
AgentsDep = Annotated[Dict[str, Any], Depends(get_agents)]
ToolsDep = Annotated[Dict[str, Any], Depends(get_tools)]
ManagerDep = Annotated[ConnectionManager, Depends(get_connection_manager)]

# NEW Annotated Dependencies for ADK Services
ADKSessionServiceDep = Annotated[InMemorySessionService, Depends(get_adk_session_service)]
ADKArtifactServiceDep = Annotated[BaseArtifactService, Depends(get_adk_artifact_service)]

# === Connection Manager Singleton (via app.state) ===

# REMOVE THIS LATER DEFINITION
# def get_connection_manager(request: Request) -> ConnectionManager:
#     \"\"\"Retrieves the singleton ConnectionManager instance from app.state.\"\"\"
#     if not hasattr(request.app.state, 'connection_manager'):
#         raise RuntimeError(\"ConnectionManager not initialized in app state. Ensure startup event ran.\")
#     logger.debug(f\"Accessing connection_manager: id={id(request.app.state.connection_manager)}\")
#     return request.app.state.connection_manager

# === Agent/Tool Loading (If needed as dependency, but usually accessed via manager/registry) ===

# Example: If you needed the Core Agent directly in a route
# def get_core_agent(request: Request) -> BaseAgent: # Assuming BaseAgent type
#     if not hasattr(request.app.state, 'agents') or 'core' not in request.app.state.agents:
#          raise RuntimeError(\"Core Agent not initialized in app state.\")
#     return request.app.state.agents['core']