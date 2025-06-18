# adk-droid/android_use_agent/sub_agents/core_agent_files/core_agent_prompting.py
"""
Functions for building the prompt context for the CoreAgent LLM call.
(Vision-First Architecture)
"""
import json
import logging
from typing import Dict, Any, Optional, List

# Import config constants
from . import core_agent_config as config

logger = logging.getLogger(__name__)

def _format_conversation_history(conversation_history: List[Dict[str, Any]]) -> str:
    """Formats conversation history for the prompt, limiting its size."""
    history_section = "# Conversation History\n"
    if conversation_history:
        limit = config.CONVERSATION_HISTORY_LIMIT
        limited_history = conversation_history[-limit:]
        if len(conversation_history) > limit:
            history_section += f"(Showing last {limit} messages)\n"
        for message in limited_history:
            role = message.get("sender", message.get("role", "unknown")).upper() # Handle 'sender' or 'role'
            content = message.get("content", "")
            # Sanitize/Summarize complex content (like agent thoughts or results)
            if isinstance(content, dict):
                 content_str = json.dumps(content, indent=1, ensure_ascii=False)[:200] + "..." # Compact + limit
            elif isinstance(content, list):
                 content_str = json.dumps(content, ensure_ascii=False)[:200] + "..." # Compact + limit
            else:
                 content_str = str(content)[:200] # Limit length
            history_section += f"{role}: {content_str}\n"
    else:
        history_section += "No conversation history available.\n"
    return history_section

def _format_last_action_result(last_action_result: Optional[Dict[str, Any]]) -> str:
    """Formats the last action result for the prompt."""
    result_section = "# Last Action Result\n"
    if last_action_result and isinstance(last_action_result, dict):
        try:
            # Format nicely, limit length
            result_str = json.dumps(last_action_result, indent=1, ensure_ascii=False)
            result_section += result_str[:500] + ('...' if len(result_str) > 500 else '') + "\n"
        except Exception:
            result_section += str(last_action_result)[:500] + "...\n" # Fallback
    elif last_action_result:
         result_section += f"Result (non-dict format): {str(last_action_result)[:200]}\n"
    else:
        result_section += "No previous action results available.\n"
    return result_section

def _format_vision_analysis(vision_analysis_result: Optional[List[Dict[str, Any]]]) -> str:
    """Formats the visual ROI list for the prompt."""
    vision_section = "# Current Screen Visual Analysis (Detected Elements)\n"
    if not vision_analysis_result or not isinstance(vision_analysis_result, list):
        vision_section += "No visual element data available or data is not a list.\n"
        return vision_section

    total_elements = len(vision_analysis_result)
    limit = config.MAX_VISION_ELEMENTS_IN_PROMPT
    limited_rois = vision_analysis_result[:limit]

    vision_section += f"Detected {total_elements} potential elements (showing top {len(limited_rois)}):\n"
    vision_section += "```json\n"
    try:
        # Ensure bounding boxes are lists, not strings, if necessary before dump
        formatted_rois = []
        for roi in limited_rois:
            formatted_roi = roi.copy() # Avoid modifying original
            # bounding_box should already be a list from VisionAnalysisTool
            # Example validation/cleanup (optional):
            if 'bounding_box' in formatted_roi and isinstance(formatted_roi['bounding_box'], list) and len(formatted_roi['bounding_box']) == 4:
                formatted_roi['center_uv'] = (
                    (formatted_roi['bounding_box'][0] + formatted_roi['bounding_box'][2]) // 2,
                    (formatted_roi['bounding_box'][1] + formatted_roi['bounding_box'][3]) // 2
                )
            else:
                formatted_roi['bounding_box'] = '[Invalid Box]' # Indicate invalid format
                formatted_roi['center_uv'] = '[N/A]'
            formatted_rois.append(formatted_roi)
        
        vision_section += json.dumps(formatted_rois, ensure_ascii=False, indent=1)
    except Exception as json_err:
        logger.error(f"Error dumping vision ROI elements: {json_err}")
        vision_section += "[Error serializing vision elements]"
    vision_section += "\n```\n"
    return vision_section

def build_prompt_context(
    base_instruction: str,
    goal: str,
    conversation_history: Optional[List[Dict[str, Any]]] = None,
    last_action_result: Optional[Dict[str, Any]] = None,
    vision_analysis_result: Optional[List[Dict[str, Any]]] = None,
) -> str:
    """
    Build the full prompt context string for the Vision-First CoreAgent.
    """
    logger.debug("Building vision-first prompt context...")
    # Format the dynamic context sections
    goal_section = f"# User Goal\n{goal}"
    conversation_section = _format_conversation_history(conversation_history or [])
    action_result_section = _format_last_action_result(last_action_result)
    visual_analysis_section = _format_vision_analysis(vision_analysis_result)

    # Combine all sections
    # Ensure base_instruction already includes the role, rules, action definitions, and JSON format requirement.
    full_prompt = f"""{base_instruction}

{goal_section}

{conversation_section}
{action_result_section}
{visual_analysis_section}
## Task

Based ONLY on the information provided above (User Goal, History, Last Result, Visual Analysis), analyze the current visual state, evaluate the last action, determine the next sub-goal, and generate the necessary action(s) in the required JSON format below. Focus on using the visual elements for targeting actions.

Respond ONLY with the JSON structure. Do not add any explanations outside the JSON.
```json
{{
  "reasoning": {{
    "evaluation_previous_action": "...",
    "memory_update": "...",
    "next_sub_goal": "..."
  }},
  "actions": [
    {{
      "<action_type>": {{ ... }} // e.g., "tap_image_coordinates": {{...}} or "done": {{...}}
    }}
  ]
}}
```"""
    # logger.debug(f"Built vision prompt context (length: {len(full_prompt)}): {full_prompt[:500]}...")
    return full_prompt

