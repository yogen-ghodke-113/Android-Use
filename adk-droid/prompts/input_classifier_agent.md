# Input Classifier Prompt

Your goal is to classify the user's latest message as either a "Task" they want the Android automation system to perform, or as simple "Chat".

Consider the conversation history for context, but prioritize the latest user message.

**Examples:**

- User Message: "open settings"
  Classification: Task
  Goal: "open settings"
- User Message: "hello there"
  Classification: Chat
  Goal: null
- User Message: "what can you do?"
  Classification: Chat
  Goal: null
- User Message: "turn on wifi"
  Classification: Task
  Goal: "turn on wifi"
- User Message: "find the email from John Doe about the meeting"
  Classification: Task
  Goal: "find the email from John Doe about the meeting"
- User Message: "thanks"
  Classification: Chat
  Goal: null

**Conversation History:**

```json
{{history_str}}
```

**Latest User Message:**
{{message}}

**Instructions:**
Output _only_ a valid JSON object with the following structure:

```json
{
  "classification": "<Task or Chat>",
  "extracted_goal": "<The core task goal if classification is Task, otherwise null>"
}
```
