package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;

public class AiSessionSettings {

    // volatile: this object is shared by reference across the EDT, broker and
    // message-delivery threads and is mutated in place, so each field needs
    // safe cross-thread visibility.
    private volatile Integer maxHistory;
    private volatile Boolean restrictToProjectFiles;
    private volatile Boolean allowInterAiComms;
    private volatile Boolean autoNotifyInbox;
    private volatile Boolean allowImportantMessages;
    private volatile String sessionInstructions;
    private volatile Boolean autoAccept;
    private volatile Boolean allowWebRequests;
    private volatile Boolean allowWebRequestGet;
    private volatile Boolean allowWebRequestPost;
    private volatile Boolean allowWebRequestPut;
    private volatile Boolean allowWebRequestPatch;
    private volatile Boolean allowWebRequestDelete;
    private volatile Boolean allowWebRequestHead;
    private volatile Boolean allowWebRequestOptions;
    private volatile Boolean allowWebRequestHeaders;
    private volatile Boolean allowWebRequestBody;

    public AiSessionSettings() {
    }

    public AiSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles,
            Boolean allowInterAiComms, Boolean autoNotifyInbox,
            Boolean allowImportantMessages, String sessionInstructions,
            Boolean autoAccept, Boolean allowWebRequests) {
        this(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox,
                allowImportantMessages, sessionInstructions, autoAccept,
                allowWebRequests, null, null, null, null, null, null, null, null,
                null);
    }

    public AiSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles,
            Boolean allowInterAiComms, Boolean autoNotifyInbox,
            Boolean allowImportantMessages, String sessionInstructions,
            Boolean autoAccept, Boolean allowWebRequests,
            Boolean allowWebRequestGet, Boolean allowWebRequestPost,
            Boolean allowWebRequestPut, Boolean allowWebRequestPatch,
            Boolean allowWebRequestDelete, Boolean allowWebRequestHead,
            Boolean allowWebRequestOptions, Boolean allowWebRequestHeaders,
            Boolean allowWebRequestBody) {
        this.maxHistory = maxHistory;
        this.restrictToProjectFiles = restrictToProjectFiles;
        this.allowInterAiComms = allowInterAiComms;
        this.autoNotifyInbox = autoNotifyInbox;
        this.allowImportantMessages = allowImportantMessages;
        this.sessionInstructions = sessionInstructions;
        this.autoAccept = autoAccept;
        this.allowWebRequests = allowWebRequests;
        this.allowWebRequestGet = allowWebRequestGet;
        this.allowWebRequestPost = allowWebRequestPost;
        this.allowWebRequestPut = allowWebRequestPut;
        this.allowWebRequestPatch = allowWebRequestPatch;
        this.allowWebRequestDelete = allowWebRequestDelete;
        this.allowWebRequestHead = allowWebRequestHead;
        this.allowWebRequestOptions = allowWebRequestOptions;
        this.allowWebRequestHeaders = allowWebRequestHeaders;
        this.allowWebRequestBody = allowWebRequestBody;
    }

    public Integer maxHistory() {
        return maxHistory;
    }

    public Boolean restrictToProjectFiles() {
        return restrictToProjectFiles;
    }

    public Boolean allowInterAiComms() {
        return allowInterAiComms;
    }

    public Boolean autoNotifyInbox() {
        return autoNotifyInbox;
    }

    public Boolean allowImportantMessages() {
        return allowImportantMessages;
    }

    public String sessionInstructions() {
        return sessionInstructions;
    }

    public Boolean autoAccept() {
        return autoAccept;
    }

    public Boolean allowWebRequests() {
        return allowWebRequests;
    }

    public Boolean allowWebRequestAccess(WebRequestAccessOptionEnum option) {
        return switch (option) {
            case GET ->
                allowWebRequestGet;
            case POST ->
                allowWebRequestPost;
            case PUT ->
                allowWebRequestPut;
            case PATCH ->
                allowWebRequestPatch;
            case DELETE ->
                allowWebRequestDelete;
            case HEAD ->
                allowWebRequestHead;
            case OPTIONS ->
                allowWebRequestOptions;
            case HEADERS ->
                allowWebRequestHeaders;
            case BODY ->
                allowWebRequestBody;
        };
    }

    public int effectiveMaxHistory() {
        return maxHistory != null ? maxHistory : PluginSettings.getMaxHistory();
    }

    public boolean effectiveRestrictToProjectFiles() {
        return restrictToProjectFiles != null ? restrictToProjectFiles : PluginSettings.isRestrictToProjectFiles();
    }

    public boolean effectiveAllowInterAiComms() {
        return allowInterAiComms != null ? allowInterAiComms : PluginSettings.isAllowInterAiComms();
    }

    public boolean effectiveAutoNotifyInbox() {
        return autoNotifyInbox != null ? autoNotifyInbox : PluginSettings.isAutoNotifyInbox();
    }

    public boolean effectiveAllowImportantMessages() {
        return allowImportantMessages != null ? allowImportantMessages : PluginSettings.isAllowImportantMessages();
    }

    public boolean effectiveAutoAccept() {
        return autoAccept != null ? autoAccept : PluginSettings.isAutoAccept();
    }

    public boolean effectiveAllowWebRequests() {
        return allowWebRequests != null ? allowWebRequests : PluginSettings.isAllowWebRequests();
    }

    public boolean effectiveAllowWebRequestAccess(WebRequestAccessOptionEnum option) {
        Boolean value = allowWebRequestAccess(option);
        return value != null ? value : PluginSettings.isAllowWebRequestAccess(option);
    }

    public void setAutoAccept(Boolean newAutoAccept) {
        this.autoAccept = newAutoAccept;
    }

    public void setMaxHistory(Integer newMaxHistory) {
        this.maxHistory = newMaxHistory;
    }

    public void setRestrictToProjectFiles(Boolean newRestrictToProjectFiles) {
        this.restrictToProjectFiles = newRestrictToProjectFiles;
    }

    public void setAllowInterAiComms(Boolean newAllowInterAiComms) {
        this.allowInterAiComms = newAllowInterAiComms;
    }

    public void setAutoNotifyInbox(Boolean newAutoNotifyInbox) {
        this.autoNotifyInbox = newAutoNotifyInbox;
    }

    public void setAllowImportantMessages(Boolean newAllowImportantMessages) {
        this.allowImportantMessages = newAllowImportantMessages;
    }

    public void setSessionInstructions(String newSessionInstructions) {
        this.sessionInstructions = newSessionInstructions;
    }

    public void setAllowWebRequests(Boolean newAllowWebRequests) {
        this.allowWebRequests = newAllowWebRequests;
    }

    public void setAllowWebRequestAccess(WebRequestAccessOptionEnum option, Boolean allowed) {
        switch (option) {
            case GET ->
                allowWebRequestGet = allowed;
            case POST ->
                allowWebRequestPost = allowed;
            case PUT ->
                allowWebRequestPut = allowed;
            case PATCH ->
                allowWebRequestPatch = allowed;
            case DELETE ->
                allowWebRequestDelete = allowed;
            case HEAD ->
                allowWebRequestHead = allowed;
            case OPTIONS ->
                allowWebRequestOptions = allowed;
            case HEADERS ->
                allowWebRequestHeaders = allowed;
            case BODY ->
                allowWebRequestBody = allowed;
        }
    }

    public String getAdditionalInfo() {
        return "";
    }

    /**
     * Populates a JSON object with this settings' configuration. Only non-null
     * values are added to the JSON object.
     */
    public void populateJsonObject(JsonObject cfgObj) {
        if (maxHistory != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.MAX_HISTORY.key(), maxHistory);
        }
        if (restrictToProjectFiles != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.RESTRICT_TO_PROJECT_FILES.key(), restrictToProjectFiles);
        }
        if (allowInterAiComms != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.ALLOW_INTER_AI_COMMS.key(), allowInterAiComms);
        }
        if (autoNotifyInbox != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.AUTO_NOTIFY_INBOX.key(), autoNotifyInbox);
        }
        if (allowImportantMessages != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.ALLOW_IMPORTANT_MESSAGES.key(), allowImportantMessages);
        }
        if (sessionInstructions != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.SESSION_INSTRUCTIONS.key(), sessionInstructions);
        }
        if (autoAccept != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.AUTO_ACCEPT.key(), autoAccept);
        }
        if (allowWebRequests != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.ALLOW_WEB_REQUESTS.key(), allowWebRequests);
        }
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            Boolean value = allowWebRequestAccess(option);
            if (value != null) {
                cfgObj.addProperty(AiSessionSettingsKeyEnum.forWebRequestAccessOption(option).key(), value);
            }
        }
    }
}
