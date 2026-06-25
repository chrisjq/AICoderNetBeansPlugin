package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.beans.PropertyChangeListener;
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;

public interface SettingsTab {

    String PROP_CHANGED = "changed";

    String getTabTitle();

    JPanel getComponent();

    void load();

    void store();

    boolean isValid();

    void addPropertyChangeListener(PropertyChangeListener l);

    void removePropertyChangeListener(PropertyChangeListener l);

    AiTypeEnum getAiType();
}
