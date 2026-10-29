[← Back to README](../../README.md)

# Run a local model

Connect LM Studio to an OpenAI Compatible session.

1. Load a model in LM Studio, start its local server and note its URL.
2. Open the NetBeans project you want to use, then choose **Tools > Options > Miscellaneous > AI Coder > OpenAI Compatible**.
3. In the **Servers** list, choose **Add** and enter a name (optional), the LM Studio URL, **LM Studio** as the server type and an optional API key. Select the entry and choose **Test connection**. If it reports **N/A**, start the server, correct the URL and test again.
4. Choose **Tools > AI Manager > Create Session**, enter a name, select the open project and **OpenAI Compatible**, choose the saved server from **Base URL** (or type a URL), choose the model, then choose **Create & Open**. The **…** button opens **Manage servers**.
5. For Ollama, llama.cpp or vLLM, add a saved entry with that server type instead. Each session keeps only its own key; changes to a saved entry affect new selections, not existing sessions.

Related: [OpenAI Compatible backend reference](../ai/openai_compatible/README.md) · [Settings](../SETTINGS.md).
