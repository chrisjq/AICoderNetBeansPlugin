package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiVersionCheck;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * The result of the {@code pi --version} probe this session's {@code start()} ran; {@code check} is null when
 * the probe failed, which clears any warning the info bar was showing. Per-session because each start probes
 * and owns its own result; the type-wide half of the picture (a version the user has verified) travels
 * separately as {@link PiVersionVerifiedEvent}.
 */
public record PiVersionCheckedEvent(PiVersionCheck check) implements AiProcessImplEvent {

}
