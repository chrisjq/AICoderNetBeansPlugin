[← Back to README](../../README.md)

# Run a local model

Connect LM Studio to an OpenAI Compatible session.

1. Load a model in LM Studio, start its local server and note its URL.
2. Open the NetBeans project you want to use, then choose **Tools > Options > Miscellaneous > AI Coder > OpenAI Compatible**.
3. Choose **LM Studio** in **Server type**, check **Base URL**, then choose **Test connection**. If it reports **N/A**, start the server, correct **Base URL** and test again.
4. Choose **Tools > AI Manager > Create Session**, enter a name, select the open project and **OpenAI Compatible**, choose the model, then choose **Create & Open**.
5. For Ollama, llama.cpp or vLLM, follow the same steps and choose that **Server type** instead.

Related: [OpenAI Compatible backend reference](../ai/openai_compatible/README.md) · [Settings](../SETTINGS.md).
