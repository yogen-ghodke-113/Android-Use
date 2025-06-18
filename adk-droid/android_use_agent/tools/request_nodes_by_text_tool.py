from google.adk.tools import BaseTool
from pydantic import BaseModel, Field
from typing import Dict, Any, List

class _Input(BaseModel):
    text: str = Field(..., description="Case-insensitive text to search for.")

class RequestNodesByTextTool(BaseTool):
    name = "request_nodes_by_text"
    description = (
        "Return accessibility nodes whose text or contentDescription "
        "contains the given string (case-insensitive)."
    )
    args_schema = _Input  # ← lets the LLM fill arguments

    def __init__(self) -> None:
        super().__init__(name=self.name, description=self.description)

    async def _run(self, text: str) -> Dict[str, Any]:
        return {
            "tool_name": self.name,
            "tool_args": {"text": text},
            "status": "pending_task_manager_execution",
            "message": "TaskManager will issue WebSocket request.",
        }
