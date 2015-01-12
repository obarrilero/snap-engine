package org.esa.beam.opendap;

import org.esa.beam.framework.gpf.ui.DefaultAppContext;
import org.esa.beam.framework.ui.command.CommandEvent;
import org.esa.beam.opendap.ui.OpendapAccessPanel;
import org.esa.beam.visat.actions.AbstractVisatAction;

import javax.swing.Action;
import javax.swing.JDialog;
import java.awt.Dimension;
import java.util.Map;

public class ShowOpendapClientAction extends AbstractVisatAction {

    @Override
    public void actionPerformed(CommandEvent event) {
        super.actionPerformed(event);
        final OpendapAccessPanel opendapAccessPanel = new OpendapAccessPanel(getAppContext(), event.getCommand().getHelpId());
        final JDialog jDialog = new JDialog(getAppContext().getApplicationWindow(), "OPeNDAP Access");
        jDialog.setContentPane(opendapAccessPanel);
        jDialog.pack();
        final Dimension size = jDialog.getSize();
        jDialog.setPreferredSize(size);
        jDialog.setVisible(true);
    }

    void static create(Map<String, ?> params) {
        final OpendapAccessPanel opendapAccessPanel = new OpendapAccessPanel(????, params.get("helpId"));
        final JDialog jDialog = new JDialog(getAppContext().getApplicationWindow(), "OPeNDAP Access");
        jDialog.setContentPane(opendapAccessPanel);
        jDialog.pack();
        final Dimension size = jDialog.getSize();
        jDialog.setPreferredSize(size);
        jDialog.setVisible(true);

    }
}
