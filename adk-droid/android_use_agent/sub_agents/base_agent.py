# adk-droid/android_use_agent/sub_agents/base_agent.py
import os
import json
import logging
import asyncio
import time
import traceback
from typing import Optional, Dict, Any, List, Callable, Union
from pathlib import Path
import base64 # For multimodal content

# --- LiteLLM Imports ---
import litellm
from litellm import acompletion
from litellm.utils import ModelResponse
from litellm import exceptions as litellm_exceptions

# --- Project Imports ---
try:
    from . import model_config # Shared model configuration
except ImportError:
    # Dummy class if import fails
    class model_config:
        @staticmethod
        def get_model_config(agent_type):
            return {'model_name': 'gemini/gemini-1.5-flash-latest', 'generation_config': {}}

logger = logging.getLogger(__name__)

# --- LiteLLM Error Mapping (Example) ---
# Map LiteLLM exceptions (and potentially others) to categories
# See: https://docs.litellm.ai/docs/exception_mapping
ERROR_CATEGORIES = {
    litellm_exceptions.RateLimitError: "RateLimitError",
    litellm_exceptions.APIConnectionError: "APIConnectionError",
    litellm_exceptions.AuthenticationError: "AuthenticationError",
    litellm_exceptions.BadRequestError: "BadRequestError", # Includes InvalidRequestError
    litellm_exceptions.Timeout: "Timeout", # Use the correct Timeout class
    litellm_exceptions.ServiceUnavailableError: "ServiceUnavailableError",
    litellm_exceptions.ContextWindowExceededError: "ContextWindowExceeded",
    litellm_exceptions.ContentPolicyViolationError: "ContentPolicyViolation",
    json.JSONDecodeError: "JsonDecodeError", # From parsing
    ValueError: "ValueError", # General value errors
    asyncio.TimeoutError: "Timeout", # Outer asyncio.wait_for timeout
    # Add others as needed
}


class BaseAgent:
    """Base class for LLM-based agents using LiteLLM, with retry logic and performance tracking."""
    AGENT_TYPE: str = "base" # Should be overridden by subclasses
    MAX_RETRIES: int = 2
    RETRY_DELAY: float = 1.0 # seconds
    DEFAULT_TIMEOUT: float = 60.0 # Increased default timeout for LiteLLM potentially wrapping slower models

    # Performance tracking
    _call_count: int = 0
    _success_count: int = 0
    _total_latency: float = 0.0
    _error_counts: Dict[str, int] = {}

    def __init__(self, prompts_base_dir: Optional[Union[str, Path]] = None):
        """
        Initializes the BaseAgent using LiteLLM.

        Args:
            prompts_base_dir: The base directory containing prompt markdown files.
        """
        self.name = self.AGENT_TYPE
        self.prompt_template_text: Optional[str] = None
        self._prompts_base_dir = None
        self.model_name: str = "NotInitialized"
        self.generation_config: Dict[str, Any] = {}
        self.api_key: Optional[str] = None # Store API key if needed

        # --- Determine prompt path --- #
        if prompts_base_dir:
            self._prompts_base_dir = Path(prompts_base_dir)
        else:
            script_dir = Path(__file__).parent
            self._prompts_base_dir = script_dir.parent.parent / "prompts"
            logger.debug(f"Defaulted prompts base dir to: {self._prompts_base_dir}")

        # Prompt loading deferred to specific agent subclass __init__

        # --- Get Model Config & API Key --- #
        try:
            model_cfg = model_config.get_model_config(self.AGENT_TYPE)
            self.model_name = model_cfg["model_name"]
            self.generation_config = model_cfg.get("generation_config", {})

            # Load API key based on model provider prefix
            if self.model_name.startswith("gemini/"):
                self.api_key = os.getenv("GEMINI_API_KEY")
                if not self.api_key:
                    logger.warning(f"GEMINI_API_KEY not found in environment for model {self.model_name}. Calls may fail.")
            # Example for OpenAI (add elif for other providers as needed)
            # elif self.model_name.startswith("openai/"):
            #     self.api_key = os.getenv("OPENAI_API_KEY")
            #     if not self.api_key:
            #         logger.warning(f"OPENAI_API_KEY not found for model {self.model_name}.")

            logger.info(f"{self.name} configured to use LiteLLM model '{self.model_name}'. Generation config: {self.generation_config}")
            # No model object initialization needed for LiteLLM SDK calls

        except Exception as init_err:
            logger.exception(f"Failed to configure LiteLLM model/key for {self.name}: {init_err}")
            self.model_name = "ERROR"
            self.generation_config = {}
            self.api_key = None

    def _load_prompt_template(self, filename: str) -> Optional[str]:
        """Loads prompt text from a file in the prompts directory."""
        if not self._prompts_base_dir:
            logger.error(f"Cannot load prompt '{filename}', prompts base directory not set.")
            return "ERROR: Prompts base directory not set."
        try:
            prompt_path = self._prompts_base_dir / filename
            if prompt_path.is_file():
                logger.info(f"Loaded prompt template from: {prompt_path}")
                return prompt_path.read_text(encoding="utf-8")
            else:
                logger.error(f"Prompt file not found: {prompt_path}")
                return f"ERROR: Prompt file not found at {prompt_path}"
        except Exception as e:
            logger.exception(f"Error loading prompt file {filename}: {e}")
            return f"ERROR: Could not load prompt file {filename}: {e}"

    def _format_prompt_for_litellm(
        self,
        prompt_content: Union[str, List[Union[str, Dict]]],
        system_prompt: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """Formats input into LiteLLM's expected messages list.

        Handles simple strings, lists of strings/dicts (for multimodal),
        and prepends an optional system prompt.
        """
        messages = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})

        if isinstance(prompt_content, str):
            messages.append({"role": "user", "content": prompt_content})
        elif isinstance(prompt_content, list):
            # Assume list contains history or multimodal content parts
            # Basic check: if first item is a dict with 'role', assume it's history
            if prompt_content and isinstance(prompt_content[0], dict) and "role" in prompt_content[0]:
                 messages.extend(prompt_content) # Assume it's already formatted message history
            else:
                 # Assume it's content for a *single* user message (possibly multimodal)
                 user_content_parts = []
                 for item in prompt_content:
                     if isinstance(item, str):
                         user_content_parts.append({"type": "text", "text": item})
                     elif isinstance(item, dict):
                          # Basic check for multimodal dict (e.g., image)
                          # LiteLLM follows OpenAI format: dicts with type, text/image_url
                          if "type" in item and ("text" in item or "image_url" in item):
                              user_content_parts.append(item)
                          # Heuristic for simple {mime_type: ..., data: ...} structure -> convert
                          elif "mime_type" in item and "data" in item:
                              try:
                                  # Assume data is base64 encoded string
                                  image_url = f"data:{item['mime_type']};base64,{item['data']}"
                                  user_content_parts.append({"type": "image_url", "image_url": {"url": image_url}})
                              except Exception as e:
                                  logger.warning(f"Failed to format dict item for LiteLLM multimodal: {e}. Item: {item}")
                          else:
                               logger.warning(f"Unsupported item type/structure in prompt_content list for single user message: {item}")
                     else:
                         logger.warning(f"Unsupported item type in prompt_content list: {type(item)}")
                 if user_content_parts:
                      messages.append({"role": "user", "content": user_content_parts})

        else:
            logger.error(f"Unsupported prompt_content type for LiteLLM formatting: {type(prompt_content)}")
            # Return a minimal valid message list to potentially trigger an error later
            messages.append({"role": "user", "content": "ERROR: Invalid prompt format."})

        return messages


    async def process_with_retry(
        self,
        prompt_content: Union[str, List[Union[str, Dict]]], # Input format
        parser_func: Callable[[str], Any],
        system_prompt: Optional[str] = None, # Optional system prompt
        timeout: Optional[float] = None,
        force_json: bool = False, # <-- ADDED JSON mode flag
        # Pass other litellm params directly if needed, e.g., tools, tool_choice
        **litellm_kwargs
    ) -> Dict[str, Any]:
        """Processes the prompt with LiteLLM, including retry logic, JSON mode, and parsing."""
        if self.model_name == "NotInitialized" or self.model_name == "ERROR":
            logger.error(f"{self.name}: Cannot process, model not configured correctly.")
            return {"success": False, "error": "Model not configured", "raw_text": "", "retry_count": 0}

        # Specific check for Gemini API key
        if self.model_name.startswith("gemini/") and not self.api_key:
             logger.error(f"{self.name}: Cannot process, GEMINI_API_KEY is missing for model {self.model_name}.")
             return {"success": False, "error": "GEMINI_API_KEY missing", "raw_text": "", "retry_count": 0}

        retries = 0
        last_exception = None
        # Use DEFAULT_TIMEOUT if timeout is None or 0, LiteLLM uses 'timeout' param
        effective_timeout = timeout if timeout else self.DEFAULT_TIMEOUT

        # Format the prompt input into LiteLLM's message format
        messages = self._format_prompt_for_litellm(prompt_content, system_prompt)
        if not messages or messages[-1]['content'] == "ERROR: Invalid prompt format.":
             logger.error(f"{self.name}: Failed to format prompt for LiteLLM. Input: {prompt_content}")
             return {"success": False, "error": "Prompt formatting failed", "raw_text": "", "retry_count": 0}

        # --- Prepare LiteLLM arguments --- #
        current_litellm_kwargs = {
            "model": self.model_name,
            "messages": messages,
            "api_key": self.api_key,
            "timeout": effective_timeout,
            **self.generation_config,
            **litellm_kwargs # Include any other args passed in (like tools)
        }
        # Add JSON mode if requested
        if force_json:
            current_litellm_kwargs["response_format"] = {"type": "json_object"}
            logger.debug(f"{self.name}: JSON mode requested.")
        # --- End Prepare Args --- #

        while retries <= self.MAX_RETRIES:
            start_time = time.monotonic()
            raw_response_text = ""
            response: Optional[ModelResponse] = None # LiteLLM response type

            try:
                log_args_summary = {k: v for k, v in current_litellm_kwargs.items() if k != 'messages' and k != 'api_key'}
                logger.debug(
                    f"{self.name}: Attempt {retries + 1}/{self.MAX_RETRIES + 1} - "
                    f"Calling LiteLLM acompletion. Args summary: {log_args_summary}"
                 )
                # Start the LLM call in a separate task
                llm_call_task = asyncio.create_task(acompletion(**current_litellm_kwargs))

                # Wait for the LLM call with a timeout
                logger.debug(f"{self.name}: Waiting ({effective_timeout}s) for LLM completion.") # Log before wait
                response = await asyncio.wait_for(llm_call_task, timeout=effective_timeout + 10.0) # Add buffer
                logger.debug(f"{self.name}: LLM completion received.") # Log after wait

                latency = time.monotonic() - start_time
                self._total_latency += latency
                self._call_count += 1

                # Extract text response from LiteLLM ModelResponse structure
                if response and response.choices and response.choices[0].message:
                    # Handle both string content and potential tool calls
                    message_content = response.choices[0].message.content
                    if isinstance(message_content, str):
                        raw_response_text = message_content
                    elif message_content is None and response.choices[0].message.tool_calls:
                        # If content is None but there are tool calls, we consider it "successful" for parsing
                        # The parser_func will need to handle the raw ModelResponse object
                        logger.debug(f"{self.name}: Received tool calls. Passing raw response object to parser.")
                        raw_response_text = "TOOL_CALL" # Placeholder, parser needs the full object
                    else:
                        # Content might be structured (e.g., list of parts), try to concat text parts
                        try:
                             if isinstance(message_content, list):
                                 raw_response_text = "".join(part.get("text", "") for part in message_content if isinstance(part, dict) and part.get("type") == "text")
                             else:
                                 logger.warning(f"Unexpected message content type: {type(message_content)}. Content: {message_content}")
                                 raw_response_text = ""
                        except Exception:
                             logger.warning(f"Could not extract text from structured message content: {message_content}", exc_info=True)
                             raw_response_text = ""
                else:
                    logger.warning(f"Could not extract valid message content from LiteLLM response structure. Response: {response}")
                    raw_response_text = ""

                # Check finish reason if text is empty and no tool calls
                finish_reason = response.choices[0].finish_reason if response and response.choices else "unknown"
                if not raw_response_text and not (response and response.choices and response.choices[0].message.tool_calls):
                    error_msg = f"LiteLLM returned empty content. Finish Reason: {finish_reason}"
                    logger.warning(error_msg)
                    # Attempt to map LiteLLM finish reasons to errors
                    if finish_reason == "length":
                        raise litellm_exceptions.ContextWindowExceededError("Model response stopped due to max tokens.", model=self.model_name, llm_provider="") # ContextWindowExceededError might be more accurate sometimes
                    elif finish_reason == "content_filter":
                        raise litellm_exceptions.ContentPolicyViolationError("Model response stopped due to content policy.", model=self.model_name, llm_provider="")
                    elif finish_reason == "tool_calls":
                         # This case should have been handled above, but as a fallback
                         logger.warning("Finish reason is tool_calls, but no tool calls found in message. Treating as empty.")
                         raise ValueError(error_msg + " (Unexpected tool_calls finish reason)")
                    elif finish_reason not in ["stop", None, ""]: # Check for other non-standard "error" reasons
                         raise ValueError(error_msg)
                    # else: # Reason is stop, None, or "", but content is empty. Treat as success but parser will get empty string.
                    #    pass


                logger.debug(f"{self.name}: Received response (len={len(raw_response_text)}), latency={latency:.2f}s. Parsing...")

                # --- Parsing Step ---
                # If it was a tool call, pass the whole response object, else pass text
                input_to_parser = response if raw_response_text == "TOOL_CALL" else raw_response_text
                try:
                    parsed_content = parser_func(input_to_parser) # Parser must handle ModelResponse or str
                    self._success_count += 1
                    logger.debug(f"{self.name}: Parsing successful.")
                    # Include raw text in success response even if parser handled ModelResponse
                    output_raw_text = "" if raw_response_text == "TOOL_CALL" else raw_response_text
                    return {"success": True, "content": parsed_content, "raw_text": output_raw_text, "retry_count": retries}
                except Exception as parse_error:
                    logger.error(f"{self.name}: Parser function failed! Error: {parse_error}. Raw text was: '{raw_response_text}'", exc_info=True)
                    # Treat parser failure as an overall failure for this attempt
                    raise ValueError(f"Parser function failed: {parse_error}") from parse_error

            except Exception as e:
                latency = time.monotonic() - start_time
                logger.warning(
                    f"{self.name}: Attempt {retries + 1} failed after {latency:.2f}s. "
                    f"Error Type: {type(e).__name__}, Error: {e}"
                 )
                last_exception = e
                error_category = self._categorize_error(e) # Use updated categorization
                self._error_counts[error_category] = self._error_counts.get(error_category, 0) + 1

                # --- Retry logic based on LiteLLM exceptions ---
                should_retry = isinstance(e, (
                    litellm_exceptions.RateLimitError,
                    litellm_exceptions.APIConnectionError,
                    litellm_exceptions.Timeout, # Includes APITimeoutError
                    litellm_exceptions.ServiceUnavailableError,
                    litellm_exceptions.InternalServerError, # <-- ADDED THIS
                    asyncio.TimeoutError # Outer timeout
                    # Potentially add others? Be careful with BadRequestError
                ))

                if should_retry and retries < self.MAX_RETRIES:
                    retries += 1
                    delay = self.RETRY_DELAY * (2 ** (retries - 1)) # Exponential backoff
                    logger.info(f"{self.name}: Retrying ({retries}/{self.MAX_RETRIES}) after {delay:.2f}s delay due to {type(e).__name__}...")
                    logger.debug(f"{self.name}: Starting retry delay sleep for {delay:.1f}s.") # log before sleep
                    await asyncio.sleep(delay)
                    logger.debug(f"{self.name}: Finished retry delay sleep.") # log after sleep
                else:
                    logger.error(f"{self.name}: Non-retryable error or max retries reached. Failing permanently. Last Error ({type(last_exception).__name__}): {last_exception}")
                    # Log traceback for truly unexpected errors
                    if not isinstance(last_exception, tuple(ERROR_CATEGORIES.keys())): # If not a known/mapped error
                        logger.exception("Unexpected error caused permanent failure:")
                    # Determine raw text to return in case of failure
                    output_raw_text = "" if raw_response_text == "TOOL_CALL" else raw_response_text
                    return {"success": False, "error": str(last_exception), "raw_text": output_raw_text, "retry_count": retries}

        # Should only be reached if loop completes unexpectedly (e.g., all retries fail)
        logger.error(f"{self.name}: Max retries ({self.MAX_RETRIES}) exceeded. Last error: {last_exception}")
        output_raw_text = "" if raw_response_text == "TOOL_CALL" else raw_response_text
        return {"success": False, "error": f"Max retries exceeded. Last error: {last_exception}", "raw_text": output_raw_text, "retry_count": retries}


    def _categorize_error(self, error: Exception) -> str:
        """Categorizes known LiteLLM (and other) exceptions for metrics."""
        for error_type, category_name in ERROR_CATEGORIES.items():
            if isinstance(error, error_type):
                # Log specific details for certain errors
                if isinstance(error, litellm_exceptions.APIError):
                     logger.warning(f"LiteLLM APIError Detail: Code={error.status_code}, Message={error.message}, Type={error.type}")
                return category_name
        # Fallback for unmapped errors
        logger.warning(f"Encountered an unmapped error type for categorization: {type(error).__name__}")
        return "UnknownError"

    # _log_error is effectively integrated into process_with_retry logging

    def get_performance_metrics(self) -> Dict[str, Any]:
        """Returns simple performance metrics."""
        avg_latency = (self._total_latency / self._call_count) if self._call_count > 0 else 0
        success_rate = (self._success_count / self._call_count) if self._call_count > 0 else 0
        return {
            "agent": self.name,
            "total_calls": self._call_count,
            "successful_calls": self._success_count,
            "success_rate": f"{success_rate:.2%}",
            "average_latency_s": f"{avg_latency:.3f}",
            "error_counts_by_category": self._error_counts
        }

# --- REMOVED Vertex-specific logic ---
