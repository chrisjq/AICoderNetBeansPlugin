[← Back to README](../README.md)

# Get started

In a few minutes, you will install AI Coder, open a session and accept one small change.

1. Build the AI Coder `.nbm` ([Installation](../README.md#installation)), then choose **Tools > Plugins > Downloaded > Add Plugins** and install it.
2. Open the NetBeans project you want the assistant to work on.
3. Choose **Tools > Options > Miscellaneous > AI Coder**. It has a **General** tab and one tab per backend (the AI service that answers in a session). Open the tab for one you have installed or can sign in to.
4. Configure that backend and use its **Test** button (OpenAI Compatible: choose **Add** in **Servers**, enter the URL and any API key, select the entry, then choose **Test connection**). For example, if the `claude` CLI (Claude Code) is installed and signed in, choose the **Claude** tab and use **Test**.
5. Choose **Tools > AI Manager**, open the **Create Session** tab, enter a **Name**, choose the open **Project** and the **AI type**, then choose **Create & Open**. For OpenAI Compatible the default saved server and its key are filled in and the connection is tested when you choose a **Base URL**.
6. In the chat tab that opens, type a request for one small, reviewable change, such as a comment or a test name, and choose **Send**.
7. A diff window shows the proposed change. Choose **Accept** to save it; **Reject** leaves the file unchanged.
8. In the chat tab, choose **⚙** (Session configuration) to open **Session Configuration**. Its tabs are **Session**, **General** and **MCP Tools**.

Next: [How-to guides](../README.md#how-to-guides) · [UI guide](UI_GUIDE.md) · [Change review](CHANGE_REVIEW.md).
