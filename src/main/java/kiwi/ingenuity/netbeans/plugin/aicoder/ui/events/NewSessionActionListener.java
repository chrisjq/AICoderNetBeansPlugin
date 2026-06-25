package kiwi.ingenuity.netbeans.plugin.aicoder.ui.events;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SessionPickerDialog;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;

@ActionID(id = StringConst.MENU_ITEM_ACTION_ID, category = StringConst.MENU_ITEM_CATEGORY)
@ActionRegistration(displayName = StringConst.MENU_ITEM, iconInMenu = false)
@ActionReference(path = StringConst.MENU_ITEM_REFERENCE, position = 5000)
public class NewSessionActionListener implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
        SessionPickerDialog.show(new SessionPersistenceManager());
    }
}
