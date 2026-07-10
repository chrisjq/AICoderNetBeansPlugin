package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;

public class AbstractAiSessionSettings {

    public static AbstractAiSessionSettings defaults() {
        return new AbstractAiSessionSettings(null, null, null, null, null, null);
    }

    private final Integer maxHistory;
    private final Boolean restrictToProjectFiles;
    private final Boolean allowInterAiComms;
    private final Boolean autoNotifyInbox;
    private final Boolean allowImportantMessages;
    private final String sessionInstructions;
    private final Boolean autoAccept;
    private final Boolean allowWebRequests;

    public AbstractAiSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles,
            Boolean allowInterAiComms, Boolean autoNotifyInbox,
            Boolean allowImportantMessages, String sessionInstructions) {
        this(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox,
                allowImportantMessages, sessionInstructions, null);
    }

    public AbstractAiSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles,
            Boolean allowInterAiComms, Boolean autoNotifyInbox,
            Boolean allowImportantMessages, String sessionInstructions, Boolean autoAccept) {
        this(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox,
                allowImportantMessages, sessionInstructions, autoAccept, null);
    }

    public AbstractAiSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles,
            Boolean allowInterAiComms, Boolean autoNotifyInbox,
            Boolean allowImportantMessages, String sessionInstructions, Boolean autoAccept,
            Boolean allowWebRequests) {
        this.maxHistory = maxHistory;
        this.restrictToProjectFiles = restrictToProjectFiles;
        this.allowInterAiComms = allowInterAiComms;
        this.autoNotifyInbox = autoNotifyInbox;
        this.allowImportantMessages = allowImportantMessages;
        this.sessionInstructions = sessionInstructions;
        this.autoAccept = autoAccept;
        this.allowWebRequests = allowWebRequests;
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

    /**
     * Returns a copy of these settings with the autoAccept field replaced. Used
     * by the info bar checkbox to persist its state back to the session without
     * touching any other settings.
     */
    public AbstractAiSessionSettings withAutoAccept(Boolean newAutoAccept) {
        return new AbstractAiSessionSettings(maxHistory, restrictToProjectFiles, allowInterAiComms,
                autoNotifyInbox, allowImportantMessages, sessionInstructions, newAutoAccept, allowWebRequests);
    }

    public String getAdditionalInfo() {
        return "";
    }
}
