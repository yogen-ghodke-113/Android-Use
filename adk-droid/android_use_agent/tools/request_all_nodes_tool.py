from google.adk.tools import BaseTool
from typing import Dict, Any

class RequestAllNodesTool(BaseTool):
    name = "request_all_nodes"
    description = "Return the full accessibility node hierarchy."

    def __init__(self) -> None:
        super().__init__(name=self.name, description=self.description)

    async def _run(self) -> Dict[str, Any]:
        return {
            "tool_name": self.name,
            "tool_args": {},
            "status": "pending_task_manager_execution",
            "message": "TaskManager will fetch full node tree.",
        }
