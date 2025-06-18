"""
resolver_agent.py – Resolves high-level semantic UI intents into executable,
                   index-based commands using multi-turn tool calls for
                   accessibility data retrieval. Now uses LiteLLM.
"""

from __future__ import annotations
import os
import json
import logging
from pathlib import Path
from typing import Any, Dict, Optional, List, Union, ClassVar, Tuple

# --- LiteLLM Imports ---
import litellm
from litellm import acompletion
from litellm.utils import ModelResponse
from litellm import exceptions as litellm_exceptions

# Assuming ADK v0.2.0+ structure (if BaseAgent is still used)
# --- MODIFIED: Inherit from BaseAgent ---
from .base_agent import BaseAgent # Keep inheritance for common setup

# Import the tool schemas and base tool (adjust path if needed)
# Import individual tools for type hints if needed, or just BaseTool
from ..tools.request_nodes_by_text_tool import RequestNodesByTextTool
from ..tools.request_interactive_nodes_tool import RequestInteractiveNodesTool
from ..tools.request_all_nodes_tool import RequestAllNodesTool
from ..tools.request_clickable_nodes_tool import RequestClickableNodesTool # Import the new tool class
from google.adk.tools import BaseTool # Import BaseTool for general type hint

logger = logging.getLogger(__name__)
# logger.setLevel(logging.INFO) # Set level in main config

_DEFAULT_PROMPT_FILE = Path(__file__).parent.parent.parent / "prompts/resolver_agent.md" # Go up 3 levels

# Define a type for the agent's response
ResolverResponse = Dict[str, Any] # e.g., {"success": True, "command": {...}} or {"success": False, "error": ...}
ToolCallRequest = Dict[str, Any] # e.g., {"tool_name": "...", "tool_args": {...}}

# --- MODIFIED: Inherit from BaseAgent for common init/prompt loading --- #
class ResolverAgent(BaseAgent):
    """
    Compiles semantic intents into concrete AccessibilityService commands
    using multi-turn interactions and specific tools via LiteLLM.
    Inherits common logic (model config, prompt loading, API key) from BaseAgent.
    """
    AGENT_TYPE: ClassVar[str] = "resolver"
    description: str = (
        "Turns natural-language accessibility intents into executable commands using accessibility data tools via LiteLLM."
    )

    def __init__(
        self,
        tools: List[BaseTool], # Expect tools list during init
        prompts_base_dir: Optional[Union[str, Path]] = None,
        prompt_file: Optional[str] = None, # Filename relative to prompts_base_dir
        **kwargs: Any, # Catch unused kwargs like model_name (handled by BaseAgent)
    ) -> None:
        # --- Call BaseAgent's __init__ FIRST ---
        # BaseAgent uses self.AGENT_TYPE ('resolver') to get the model config
        super().__init__(prompts_base_dir=prompts_base_dir)
        logger.info(f"ResolverAgent: BaseAgent initialized. LiteLLM Model: {self.model_name}, API Key Loaded: {self.api_key is not None}, Prompts Base: {self._prompts_base_dir}")

        # --- Load the prompt *after* BaseAgent init ---*
        effective_prompt_filename = prompt_file if prompt_file else f"{self.AGENT_TYPE}_agent.md"
        self.prompt_template_text = self._load_prompt_template(effective_prompt_filename)
        if not self.prompt_template_text or "ERROR:" in self.prompt_template_text:
             logger.error(f"ResolverAgent prompt '{effective_prompt_filename}' was NOT loaded successfully.")
             self.prompt_template_text = "ERROR: ResolverAgent prompt could not be loaded."
             prompt_loaded_ok = False
        else:
             logger.info(f"ResolverAgent prompt '{effective_prompt_filename}' loaded successfully.")
             prompt_loaded_ok = True
        # --- End Prompt Loading --- #

        # --- Prepare Tools for LiteLLM (OpenAI format) --- #
        if not tools:
             raise ValueError("ResolverAgent requires tools to be provided during initialization.")
        self.tool_registry: Dict[str, BaseTool] = {tool.name: tool for tool in tools}
        self._litellm_tools = []
        for tool_instance in tools:
            # Check if the tool instance is one of the expected types (optional but good practice)
            if not isinstance(tool_instance, (RequestNodesByTextTool, RequestInteractiveNodesTool, RequestAllNodesTool, RequestClickableNodesTool)):
                logger.warning(f"ResolverAgent received unexpected tool type: {type(tool_instance)}. Skipping LiteLLM preparation for it.")
                continue
                
            args_schema_dict = None
            if hasattr(tool_instance, 'args_schema') and tool_instance.args_schema:
                 try:
                    # Ensure we are using Pydantic v2 compatible methods if BaseTool uses them
                    args_schema_dict = tool_instance.args_schema.model_json_schema()
                 except AttributeError:
                     # Fallback for older Pydantic or different schema structure
                     try:
                         args_schema_dict = tool_instance.args_schema.schema()
                     except Exception as schema_err:
                         logger.error(f"Error generating JSON schema for tool {tool_instance.name} using .schema(): {schema_err}", exc_info=True)
                         args_schema_dict = {"type": "object", "properties": {}} # Fallback
                 except Exception as schema_err:
                      logger.error(f"Error generating JSON schema for tool {tool_instance.name} using .model_json_schema(): {schema_err}", exc_info=True)
                      args_schema_dict = {"type": "object", "properties": {}} # Fallback
            else:
                 logger.debug(f"Tool {tool_instance.name} has no args_schema. Using empty schema.")
                 args_schema_dict = {"type": "object", "properties": {}}

            if args_schema_dict:
                 tool_definition = {
                     "type": "function",
                     "function": {
                         "name": tool_instance.name,
                         "description": tool_instance.description,
                         "parameters": args_schema_dict
                     }
                 }
                 self._litellm_tools.append(tool_definition)
                 logger.debug(f"Prepared LiteLLM tool: {tool_instance.name} with schema: {str(args_schema_dict)[:100]}...")
            else:
                 logger.warning(f"Skipping registration of tool {tool_instance.name} due to schema generation failure and no fallback.")

        logger.info(f"ResolverAgent prepared {len(self._litellm_tools)} tools for LiteLLM.")
        # --- End Tool Preparation --- #

        # Log final status
        logger.info("ResolverAgent ready – Using LiteLLM model=%s, Tools=%s, Prompt Loaded: %s",
                    self.model_name, list(self.tool_registry.keys()), prompt_loaded_ok)

    def _prepare_litellm_messages(
        self,
        semantic_intent: Optional[Dict[str, Any]],
        history: Optional[List[Dict]] = None
    ) -> List[Dict]:
        """Prepares the messages list for LiteLLM based on history and current intent."""
        messages = []

        # 1. Add System Prompt
        if self.prompt_template_text and "ERROR:" not in self.prompt_template_text:
            messages.append({"role": "system", "content": self.prompt_template_text})
        else:
            logger.error("ResolverAgent system prompt is missing or invalid. Proceeding without it.")

        # 2. Add existing history (if any)
        if history:
            messages.extend(history)

        # 3. Add the current semantic intent as the latest user message (only if history is empty)
        #    If history exists, the latest turn should already contain the relevant context.
        if not history and semantic_intent:
            intent_prompt = (
                f"**Semantic Intent to Resolve:**\n```json\n{json.dumps(semantic_intent, indent=2)}\n```"
            )
            messages.append({"role": "user", "content": intent_prompt})

        return messages

    async def resolve_intent(
        self,
        session_id: str,
        semantic_intent: Optional[Dict[str, Any]] = None,
        history: Optional[List[Dict[str, Any]]] = None
    ) -> Union[ResolverResponse, List[ToolCallRequest]]: # Return type clarifies possibilities
        """Resolves a semantic intent using multi-turn conversation with tool use."""
        if not history:
            history = []

        # Construct messages for LiteLLM
        messages = []
        # Add system prompt if not already in history (or if history is empty)
        if not history or history[0].get("role") != "system":
            messages.append({"role": "system", "content": self.prompt_template_text})

        # Add existing history
        messages.extend(history)

        # Add the new semantic intent if provided (only for the first turn)
        if semantic_intent:
            intent_content = (
                f"Resolve the following semantic intent:\n" +
                f"```json\n{json.dumps(semantic_intent, indent=2)}\n```\n"
                f"Use the available tools to gather node information. Start by requesting nodes relevant to the target description."
            )
            messages.append({"role": "user", "content": intent_content})

        logger.debug(f"[{session_id}] Sending {len(messages)} messages to resolver model ({self.model_name}) with {len(self._litellm_tools)} tools...")
        # logger.debug(f"Messages: {messages}") # Optional: Log full messages for deep debug

        try:
            response: ModelResponse = await litellm.acompletion(
                model=self.model_name,
                messages=messages,
                tools=self._litellm_tools, # Use _litellm_tools
                tool_choice="auto", # Let the model decide
                max_tokens=self.generation_config.get('max_tokens', 2048), # Use generation_config
                temperature=self.generation_config.get('temperature', 0.2), # Use generation_config
                api_key=self.api_key, # Pass API key if needed by LiteLLM config
                timeout=self.generation_config.get("timeout", 60.0), # Use generation_config
                # Optional: Add safety settings if needed
                # safety_settings=...
            )

            # --- Process LiteLLM Response --- #
            choice = response.choices[0]
            message = choice.message

            # --- REVISED LOGIC: Handle Tool Calls OR Text Response --- #
            tool_calls_were_processed = False # Flag to track if we handled tool calls
            
            # 1. Check for Tool Calls FIRST
            if message.tool_calls and len(message.tool_calls) > 0:
                tool_call_requests = []
                for tool_call in message.tool_calls:
                     if tool_call.function:
                         tool_name = tool_call.function.name
                         tool_args_str = tool_call.function.arguments
                         try:
                             if isinstance(tool_args_str, str) and tool_args_str.strip():
                                 tool_args = json.loads(tool_args_str)
                             else:
                                 tool_args = {}
                             
                             tool_call_requests.append({
                                 "tool_call_id": tool_call.id, 
                                 "tool_name": tool_name,
                                 "tool_args": tool_args
                             })
                         except json.JSONDecodeError:
                             logger.error(f"[{session_id}] Failed to parse tool arguments JSON: {tool_args_str}")
                     else:
                         logger.warning(f"[{session_id}] Received tool_call without function attribute: {tool_call}")
                
                if tool_call_requests:
                     logger.info(f"[{session_id}] Resolver requested {len(tool_call_requests)} tool(s): {[req['tool_name'] for req in tool_call_requests]}")
                     tool_calls_were_processed = True # Mark as handled
                     return tool_call_requests # Return the list of tool requests
                else:
                     logger.warning(f"[{session_id}] Received tool_calls message but couldn't extract valid function calls.")
                     # If extraction failed, allow falling through to check message.content

            # 2. Process Text Response (ONLY if no valid tool calls were processed)
            elif message.content and isinstance(message.content, str):
                response_text = message.content.strip()
                logger.debug(f"[{session_id}] Resolver raw text response:\n>>>\n{response_text}\n<<<")
                # Attempt to parse as JSON (The new prompt shouldn't produce this, but handle defensively)
                try:
                    if response_text.startswith("```json"):
                         response_text = response_text[7:-3].strip()
                    elif response_text.startswith("```"):
                         response_text = response_text[3:-3].strip()

                    response_json = json.loads(response_text)
                    
                    # Check if it looks like the *intended* final response format
                    if isinstance(response_json, dict) and "success" in response_json:
                        logger.warning(f"[{session_id}] Resolver unexpectedly returned a final response dict instead of tool call: {response_json}")
                        return response_json # Return the parsed dict anyway
                    # Check if it *looks like* the tool call format (should have been caught above)
                    elif isinstance(response_json, dict) and "tool_calls" in response_json:
                        logger.error(f"[{session_id}] Resolver returned tool call JSON as text content. Parsing error likely occurred earlier. Content: {response_text}")
                        return {"success": False, "error": "Resolver returned tool call as text.", "raw_response": response_text}
                    else:
                        logger.error(f"[{session_id}] Resolver response text parsed to JSON, but has unexpected structure: {response_json}")
                        return {"success": False, "error": "Resolver response JSON structure invalid.", "raw_response": response_json}

                except json.JSONDecodeError as e:
                    # If text content is not JSON, treat as an error (as per the current prompt)
                    logger.error(f"[{session_id}] Resolver returned non-JSON text content when tool call was expected: {e}. Raw text: {response_text}")
                    return {"success": False, "error": f"Resolver returned non-JSON text: {e}", "raw_response": response_text}
            
            # 3. Handle No Usable Output
            else:
                logger.error(f"[{session_id}] Resolver returned no usable text content or tool calls. Message: {message}")
                return {"success": False, "error": "Resolver returned no usable content.", "raw_response": str(message)}
            # --- END REVISED LOGIC ---

            # Process the final text response
            final_text = response.text
            logger.debug(f"ResolverAgent final text response: {final_text}")

            try:
                # Attempt to parse the JSON response
                parsed_json = json.loads(final_text)
                if not isinstance(parsed_json, dict):
                    raise json.JSONDecodeError("Expected a JSON object.", final_text, 0)

                # *** ADDED LOGGING HERE ***
                logger.info(f"ResolverAgent final decision: {parsed_json}")
                return ResolverResponse(**parsed_json) # Validate and return

            except json.JSONDecodeError as e:
                logger.error(f"ResolverAgent failed to parse final JSON response: {e}. Raw: {final_text}")
                return ResolverResponse(
                    success=False,
                    error=f"Failed to parse final JSON response: {final_text}"
                )
            except Exception as e:
                logger.error(f"ResolverAgent error processing final response: {e}. Raw: {final_text}", exc_info=True)
                return ResolverResponse(
                    success=False,
                    error=f"Error processing final response: {e}"
                )

        except litellm.exceptions.BadRequestError as e:
            logger.error(f"[{session_id}] LiteLLM bad request error for ResolverAgent: {e}")
            return {"success": False, "error": f"Resolver bad request (check prompt/params?): {e}"}
        except Exception as e:
            logger.exception(f"[{session_id}] Unexpected error during resolver agent call: {e}")
            return {"success": False, "error": f"Unexpected error in resolver: {e}"}
