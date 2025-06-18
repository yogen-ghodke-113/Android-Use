# android_use_agent/task_manager_files/task_constants.py
"""
Constants used by the task management loop.
"""

# --- Task Loop Constants --- #
MAX_TASK_STEPS = 25 # Max steps before task automatically stops
CLIENT_RESPONSE_TIMEOUT = 30.0 # Seconds to wait for client responses (screenshots, UI dumps, action results)
CLARIFICATION_TIMEOUT_MULTIPLIER = 3 # Multiplier for client response timeout when waiting for user clarification

# --- Failure Thresholds --- #
MAX_CORE_AGENT_FAILURES = 3 # Max consecutive failures allowed for the CoreAgent reasoning step
MAX_ACTION_FAILURES_PER_SEQUENCE = 2 # Max failures allowed within a single sequence of actions from the CoreAgent
MAX_CONSECUTIVE_STEP_FAILURES = 3   # Max consecutive failures of entire steps (Observe-Reason-Act cycle) before aborting task
