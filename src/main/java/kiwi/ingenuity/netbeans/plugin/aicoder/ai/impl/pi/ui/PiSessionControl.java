package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal contract {@link PiAiInfoBarExtension} needs from a running pi session, implemented by WP-2's
 * {@code PiAiProcessManager}. Deliberately narrow: rather than have the info bar parse WP-1's raw pi RPC events itself,
 * the process manager translates whatever it receives internally (get_state, get_available_models,
 * get_available_thinking_levels, get_session_stats, set_model/set_thinking_level responses) into calls on
 * {@link Listener}, and the info bar only ever talks to this interface. That keeps this UI-owned file decoupled from
 * WP-1's exact RPC event class shapes.
 *
 * <p>
 * Every method that talks to the pi process is asynchronous and must not block the caller (typically the EDT).
 */
public interface PiSessionControl {

    /**
     * Registers the info bar as the (sole) listener for model/thinking-level/usage/turn-state updates pushed from the
     * pi process. There is at most one listener; a later call replaces the previous one. Pass {@code null} to remove it
     * (e.g. when the info bar is disposed).
     */
    void setListener(Listener listener);

    /**
     * Sends {@code set_model {provider, modelId}}. {@code modelId} is the bare model id (no provider prefix);
     * {@code provider} is the provider segment of the "provider/id" string shown in the combo.
     */
    CompletableFuture<Void> setModel(String provider, String modelId);

    /**
     * Sends {@code set_thinking_level {level}}.
     */
    CompletableFuture<Void> setThinkingLevel(String level);

    /**
     * True while a turn is running — both combos are disabled while this is true, per the spec.
     */
    boolean isTurnRunning();

    interface Listener {

        /**
         * The full {@code get_available_models} result (or a live update of it), each entry formatted
         * {@code "provider/id"}.
         */
        void onAvailableModelsChanged(List<String> providerSlashId);

        /**
         * The {@code get_available_thinking_levels} result for the currently selected model.
         */
        void onAvailableThinkingLevelsChanged(List<String> levels);

        /**
         * The model/level pi is actually using right now — from {@code get_state} at startup, or echoed back after a
         * {@code set_model}/{@code set_thinking_level} call. {@code providerSlashId} is formatted
         * {@code "provider/id"}; either argument may be null if that part of the state is not yet known.
         */
        void onCurrentSelectionChanged(String providerSlashId, String thinkingLevel);

        /**
         * {@code get_session_stats.contextUsage} after a turn completes, for {@code ContextGaugePanel}.
         */
        void onContextUsageChanged(int usedTokens, int contextWindowTokens);

        /**
         * Turn-running state changed; the info bar enables/disables its combos accordingly.
         */
        void onTurnRunningChanged(boolean running);
    }
}
