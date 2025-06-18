# adk-droid/main_files/connection_manager.py
"""
Manages active WebSocket connections.
"""
import logging
import json
import asyncio
import uuid
from typing import Dict, Any, Optional
from fastapi import WebSocket, WebSocketDisconnect
from asyncio import Future, TimeoutError as AsyncTimeoutError # Import Future and alias TimeoutError

logger = logging.getLogger(__name__)

class ConnectionManager:
    """Manages active WebSocket connections using session IDs."""
    def __init__(self):
        self.active_connections: Dict[str, WebSocket] = {}
        # --- NEW: Pending request tracking ---
        self._pending_requests: Dict[str, Future] = {}
        self._pending_requests_lock = asyncio.Lock()
        # --- END NEW ---
        logger.info("ConnectionManager initialized.")

    async def connect(self, websocket: WebSocket, session_id: str):
        """Accepts and stores a new WebSocket connection."""
        await websocket.accept()
        self.active_connections[session_id] = websocket
        logger.info(f"Accepted and stored connection for session {session_id}")

    async def disconnect(self, session_id: str):
        """Removes a WebSocket connection."""
        websocket = self.active_connections.pop(session_id, None)
        if websocket:
            logger.info(f"WebSocket disconnected and removed for session {session_id}")
        # No warning needed if already removed, pop handles KeyError implicitly if needed

    async def send_personal_message(self, session_id: str, message: Dict[str, Any]):
        """Sends a JSON message to a specific WebSocket connection."""
        websocket = self.active_connections.get(session_id)
        if websocket:
            try:
                # Ensure message includes session_id (client might need it)
                if "session_id" not in message:
                    message["session_id"] = session_id

                message_str = json.dumps(message)
                # Log message type for easier debugging without logging sensitive data
                log_msg_type = message.get("type", "unknown")
                logger.debug(f"Attempting to send [type={log_msg_type}] to {session_id}...")
                await websocket.send_text(message_str)
                logger.debug(f"Successfully sent [type={log_msg_type}] to {session_id}.")
            except WebSocketDisconnect:
                logger.warning(f"WebSocket for session {session_id} disconnected before message [type={message.get('type', 'N/A')}] could be sent.")
                # No need to call self.disconnect here, as the disconnect handler in the WS endpoint will call it.
            except RuntimeError as e:
                 # Handles cases like sending on a closed connection
                 if "cannot call send() on a closed websocket connection" in str(e).lower():
                      logger.warning(f"Attempted to send on closed WebSocket for session {session_id}. Connection likely closing.")
                      # Don't disconnect here either, let the main handler manage it.
                 else:
                      # Log other runtime errors but avoid disconnecting here
                      logger.exception(f"Runtime exception during websocket.send_text for {session_id}: {e}")
            except Exception as e:
                logger.exception(f"Unhandled exception during websocket.send_text for {session_id}: {e}")
        else:
            # This might happen if disconnect occurs between check and send, usually benign.
            logger.warning(f"WebSocket for session {session_id} no longer found in active connections for sending.")

    # --- NEW: Request-Response Handling --- #
    async def send_request_and_wait_for_response(
        self,
        session_id: str,
        request_payload: Dict[str, Any],
        request_type: str,
        expected_response_type: str, # Keep for potential validation
        timeout: float
    ) -> Optional[Dict[str, Any]]:
        """Sends a request and waits for a correlated response."""
        websocket = self.active_connections.get(session_id)
        if not websocket:
            logger.error(f"Cannot send request '{request_type}': WebSocket for session {session_id} not found.")
            return None

        correlation_id = str(uuid.uuid4())
        future = asyncio.get_event_loop().create_future()

        async with self._pending_requests_lock:
            self._pending_requests[correlation_id] = future

        # Add correlation_id and type to the payload being sent
        request_payload["type"] = request_type
        request_payload["correlation_id"] = correlation_id
        # session_id is added by send_personal_message if not present

        logger.debug(f"[{session_id}] Sending request '{request_type}' with correlation_id: {correlation_id}")
        await self.send_personal_message(session_id, request_payload)

        try:
            logger.debug(f"[{session_id}] Waiting ({timeout}s) for response with correlation_id: {correlation_id}")
            # Wait for the future to be resolved by the message handler
            result = await asyncio.wait_for(future, timeout=timeout)
            logger.debug(f"[{session_id}] Received response for correlation_id: {correlation_id}")
            return result
        except AsyncTimeoutError:
            logger.error(f"[{session_id}] Timeout waiting for response for correlation_id: {correlation_id} (request_type: '{request_type}')")
            # Clean up the pending request on timeout
            async with self._pending_requests_lock:
                self._pending_requests.pop(correlation_id, None)
            return None # Indicate timeout
        except Exception as e:
            logger.exception(f"[{session_id}] Error waiting for response (correlation_id: {correlation_id}): {e}")
            # Clean up the pending request on error
            async with self._pending_requests_lock:
                self._pending_requests.pop(correlation_id, None)
            return None # Indicate error

    async def resolve_pending_request(self, correlation_id: str, response_data: Dict[str, Any]):
        """Resolves the Future for a pending request when a response arrives."""
        future: Optional[Future] = None
        # --- Use async lock correctly --- #
        async with self._pending_requests_lock:
            # Pop the future inside the lock to ensure atomicity
            future = self._pending_requests.pop(correlation_id, None)

        if future and not future.done():
            logger.debug(f"Found pending future for correlation_id: {correlation_id}")
            try:
                # Get the running event loop
                loop = asyncio.get_running_loop()
                # Schedule the set_result call safely onto the loop's thread
                loop.call_soon_threadsafe(future.set_result, response_data)
                # Log that scheduling happened, not immediate success
                logger.debug(f"Scheduled set_result for future {correlation_id} via call_soon_threadsafe.")
            except RuntimeError as e:
                # Handle case where loop might not be running (e.g., during shutdown)
                logger.error(f"Error getting running loop or scheduling set_result for {correlation_id}: {e}")
                # Attempt to set result directly as a fallback, though it might fail
                try: future.set_exception(e) # Set exception if scheduling failed
                except: pass # Ignore errors setting exception
            except Exception as e:
                logger.error(f"Error scheduling set_result for {correlation_id}: {e}", exc_info=True)
                # Attempt to set exception directly as a fallback
                try: future.set_exception(e)
                except: pass # Ignore errors setting exception
        elif future and future.done():
             logger.warning(f"Attempted to resolve future for {correlation_id}, but it was already done.")
        else:
             logger.warning(f"No pending future found for correlation_id: {correlation_id}. Response discarded.")

# Global instance (simple approach for this structure)
# If using a factory pattern or more complex app structure, consider dependency injection.
manager = ConnectionManager()
