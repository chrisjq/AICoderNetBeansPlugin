[← Back to README](../README.md)

# Get started

In a few minutes, you will install AI Coder, open a session and accept one small change.

1. Build the AI Coder `.nbm` ([Installation](../README.md#installation)), then choose **Tools > Plugins > Downloaded > Add Plugins** and install it.
2. Open the NetBeans project you want the assistant to work on.
3. Choose **Tools > Options > Miscellaneous > AI Coder**. A backend is the AI service that answers in a session. Open the tab for one you have installed or can sign in to.
4. Configure that backend and use its **Test** button (OpenAI Compatible: **Test connection**). For example, if the `claude` CLI (Claude Code) is installed and signed in, choose the **Claude** tab and use **Test**.
5. Choose **Tools > AI Manager > Create Session**, enter a name, choose the open project and AI type, then choose **Create & Open**.
6. Ask for one small, reviewable change, such as a comment or a test name.
7. Read the proposed diff in NetBeans' **Accept**/**Reject** panel. Choose **Accept** to save it; **Reject** leaves the file unchanged.
8. Open **⚙** above Send to see **Session Configuration**.

Next: [How-to guides](../README.md#how-to-guides) · [UI guide](UI_GUIDE.md) · [Change review](CHANGE_REVIEW.md).
