package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

public class AbstractAiModelSessionSettings extends AbstractAiSessionSettings {

    private final String model;
    private final String fallbackModel;

    public AbstractAiModelSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles,
            Boolean allowInterAiComms, Boolean autoNotifyInbox,
            Boolean allowImportantMessages, String sessionInstructions, String model,
            String defaultModel) {
        super(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox,
                allowImportantMessages, sessionInstructions);
        this.model = model != null && !model.trim().isEmpty() ? model : defaultModel;
        this.fallbackModel = defaultModel;
    }

    public AbstractAiModelSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles,
            Boolean allowInterAiComms, Boolean autoNotifyInbox,
            Boolean allowImportantMessages, String sessionInstructions, String model,
            Boolean autoAccept) {
        super(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox,
                allowImportantMessages, sessionInstructions, autoAccept);
        this.model = model;
        this.fallbackModel = model;
    }

    @Override
    public AbstractAiSessionSettings withAutoAccept(Boolean newAutoAccept) {
        return new AbstractAiModelSessionSettings(maxHistory(), restrictToProjectFiles(),
                allowInterAiComms(), autoNotifyInbox(), allowImportantMessages(),
                sessionInstructions(), model(), newAutoAccept);
    }

    public String model() {
        return model != null ? model : fallbackModel;
    }

    @Override
    public String getAdditionalInfo() {
        return ", model: " + model();
    }
}
