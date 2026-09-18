package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;

/**
 * Fired on {@link kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus} whenever {@code PiVersionWarningDialog}
 * writes type-global version-verification state — {@code PiPluginSettings}'s persisted verified version ("Yes"), or
 * {@code PiVersionCheck}'s process-wide (static) not-working-this-session set ("No") — so every open pi tab's info bar
 * can refresh its warning button, not just the one the dialog was opened from.
 */
public record PiVersionVerifiedEvent() implements AiPropertyEvent {

}
