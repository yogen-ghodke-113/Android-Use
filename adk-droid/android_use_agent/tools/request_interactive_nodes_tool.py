from google.adk.tools import BaseTool
from typing import Dict, Any

class RequestInteractiveNodesTool(BaseTool):
    name = "request_interactive_nodes"
    description = "Return all clickable / editable / scrollable nodes."

    def __init__(self) -> None:
        super().__init__(name=self.name, description=self.description)

    async def _run(self) -> Dict[str, Any]:
        return {
            "tool_name": self.name,
            "tool_args": {},
            "status": "pending_task_manager_execution",
            "message": "TaskManager will request interactive nodes.",
        }
