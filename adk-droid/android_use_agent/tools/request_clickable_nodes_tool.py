# adk-droid/android_use_agent/tools/request_clickable_nodes_tool.py

from google.adk.tools import BaseTool
from pydantic import Field
from typing import Annotated, List, Dict, Any, Type

class RequestClickableNodesTool(BaseTool):
    """Requests a list of clickable (or long-clickable) nodes from the client.

    This tool asks the Android client to retrieve all accessibility nodes
    on the current screen that are marked as clickable or long-clickable.
    The client will return a list of these nodes with their properties.
    This is useful for identifying interactive elements for potential tap actions.
    """

    name: str = "request_clickable_nodes"
    description: str = "Requests a list of clickable or long-clickable nodes from the client."

    def __init__(self) -> None:
        super().__init__(name=self.name, description=self.description)

    # This tool doesn't execute directly; it signals the TaskManager.
    async def _run(self, **kwargs: Any) -> Dict[str, Any]:
        return {
            "tool_name": self.name,
            "tool_args": {},
            "status": "pending_task_manager_execution",
            "message": "TaskManager will request clickable nodes.",
        }

    # This tool doesn't need specific output processing here; handled by TaskManager.
    # def _process_output(self, output: Any) -> Any:
    #     return output 