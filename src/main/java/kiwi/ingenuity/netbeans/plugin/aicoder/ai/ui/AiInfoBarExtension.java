package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.List;
import javax.swing.JComponent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEventListener;

public interface AiInfoBarExtension extends AiProcessImplEventListener {

    List<JComponent> createComponents();

    void onPropertyEvent(AiPropertyEvent event);

    default void onSessionPct(double pct) {
    }

    default void dispose() {
    }
}
