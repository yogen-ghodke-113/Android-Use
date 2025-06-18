# adk-droid/main_files/api_models.py
"""
Pydantic models for API request and response validation.
Ensures data consistency between client and server for HTTP endpoints.
"""
from pydantic import BaseModel, Field
from typing import Dict, Any, Optional, List, Literal

# --- HTTP Request/Response Models ---

# class CommandRequest(BaseModel):
#     """Model for incoming user commands via HTTP POST /api/command."""
#     session_id: str = Field(..., description="Unique identifier for the user session.")
#     message: str = Field(..., description="The user's command or message.")
#     device_context: Optional[Dict[str, Any]] = Field(None, description="Optional device context information.")

# class CommandResponse(BaseModel):
#     """Generic response model for many API endpoints."""
#     success: bool = Field(..., description="Indicates if the operation was successful.")
#     message: Optional[str] = Field(None, description="A message providing details about the outcome.")
#     action: Optional[Dict[str, Any]] = Field(None, description="Deprecated? For returning direct commands if needed.")
#     data: Optional[Dict[str, Any]] = Field(None, description="Additional data related to the response (e.g., description, clarification flag).")

# class ScreenshotRequest(BaseModel):
#     """Model for receiving Base64 screenshot data via HTTP POST /api/screenshot."""
#     session_id: str = Field(..., description="Session identifier.")
#     screenshot_data: str = Field(..., description="Base64 encoded PNG image data.")

# class ClientActionResult(BaseModel):
#     """Model for receiving action results from the client via HTTP POST /api/result."""
#     session_id: str = Field(..., description="Session identifier.")
#     success: bool = Field(..., description="Whether the action succeeded on the client.")
#     message: Optional[str] = Field(None, description="Result message or error details from the client.")
#     original_action: Optional[Dict[str, Any]] = Field(None, description="The original action request this result corresponds to.")

# --- WebSocket Message Models (Optional but Recommended) ---
# These help validate the structure of messages received over WebSocket.

class BaseWebSocketMessage(BaseModel):
    """Base model for WebSocket messages, ensuring 'type' and 'session_id'."""
    type: str = Field(..., description="The type of the WebSocket message.")
    # session_id: str # Usually handled by the connection context, but can be included for robustness

class WebSocketConnectMessage(BaseWebSocketMessage):
    type: Literal["connect"] = "connect"
    # Add any specific connection payload if needed

class WebSocketDisconnectRequestMessage(BaseWebSocketMessage):
    type: Literal["disconnect_request"] = "disconnect_request"

class WebSocketCommandMessage(BaseWebSocketMessage):
    type: Literal["command"] = "command" # Example type for WS commands
    message: str = Field(..., description="User command sent via WebSocket.")

class WebSocketClarificationResponseMessage(BaseWebSocketMessage):
    type: Literal["clarification_response"] = "clarification_response"
    message: str = Field(..., description="User's response to a clarification request.")

class WebSocketExecutionResultMessage(BaseWebSocketMessage):
    type: Literal["execution_result"] = "execution_result"
    success: bool = Field(..., description="Whether the action succeeded on the client.")
    message: Optional[str] = Field(None, description="Result message or error details.")
    original_action: Optional[Dict[str, Any]] = Field(None, description="Original action request.")

# class WebSocketUiDumpResultMessage(BaseWebSocketMessage): # Legacy
#     type: Literal["ui_dump_result"] = "ui_dump_result"
#     ui_elements: List[Dict[str, Any]] = Field(..., description="List of UI elements from legacy dump.")

# class WebSocketIndexedUiDumpResultMessage(BaseWebSocketMessage):
#     type: Literal["indexed_ui_dump_result"] = "indexed_ui_dump_result"
#     # Expecting a JSON string from the client which we parse later
#     indexed_dump_json: str = Field(..., description="JSON string representing the indexed UI elements.")

# class WebSocketIndexedUiDumpErrorMessage(BaseWebSocketMessage):
#     type: Literal["indexed_ui_dump_error"] = "indexed_ui_dump_error"
#     error: str = Field(..., description="Error message from the client during indexed dump generation.")

class WebSocketPingMessage(BaseWebSocketMessage):
    type: Literal["ping"] = "ping"

# Consider using a Union type for received messages if parsing based on 'type'
# from typing import Union
# WebSocketReceivedMessage = Union[WebSocketConnectMessage, WebSocketDisconnectRequestMessage, ...]
