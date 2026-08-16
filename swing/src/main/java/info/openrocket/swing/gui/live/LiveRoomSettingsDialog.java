package info.openrocket.swing.gui.live;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import info.openrocket.core.live.LiveProjectLink;

/** Host-only settings for a persistent OpenRocket Live room. */
public final class LiveRoomSettingsDialog extends JDialog {
	private static final long serialVersionUID = 1L;

	public record Result(LiveProjectLink.EditPolicy editPolicy, boolean autoOpen, String newHostId) {
	}

	private final JCheckBox requireConnection;
	private final JCheckBox autoOpen;
	private final JCheckBox transferHost = new JCheckBox("Transfer host when settings are saved");
	private final JComboBox<LiveSessionManager.Participant> newHost;
	private Result result;

	private LiveRoomSettingsDialog(Window parent, LiveSessionManager manager, boolean shouldAutoOpen) {
		super(parent, "Live Room Settings", ModalityType.APPLICATION_MODAL);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));

		JLabel title = new JLabel(manager.getRoomName());
		title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 4));
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(title);
		content.add(Box.createVerticalStrut(12));

		requireConnection = new JCheckBox("Require a connection to edit the shared design",
				manager.getEditPolicy() == LiveProjectLink.EditPolicy.REQUIRE_CONNECTION);
		JPanel editing = section("Editing", requireConnection,
				"When this is off, disconnected collaborators can save an offline branch for the host to review.");
		content.add(editing);
		content.add(Box.createVerticalStrut(10));

		autoOpen = new JCheckBox("Automatically host this room when the design opens", shouldAutoOpen);
		content.add(section("Opening", autoOpen,
				"The link is stored beside this design in its local .orklive file."));
		content.add(Box.createVerticalStrut(10));

		content.add(section("Activity Log", null,
				"This version keeps a text modification log only. It does not store rollback snapshots or restore points."));
		content.add(Box.createVerticalStrut(10));

		List<LiveSessionManager.Participant> candidates = new ArrayList<>();
		for (LiveSessionManager.Participant participant : manager.getParticipants()) {
			if (!participant.id().equals(manager.getParticipantId())) {
				candidates.add(participant);
			}
		}
		newHost = new JComboBox<>(candidates.toArray(LiveSessionManager.Participant[]::new));
		newHost.setEnabled(!candidates.isEmpty() && transferHost.isSelected());
		transferHost.setEnabled(!candidates.isEmpty());
		transferHost.addActionListener(event -> newHost.setEnabled(transferHost.isSelected()));
		JPanel hostControls = new JPanel();
		hostControls.setLayout(new BoxLayout(hostControls, BoxLayout.Y_AXIS));
		transferHost.setAlignmentX(Component.LEFT_ALIGNMENT);
		newHost.setAlignmentX(Component.LEFT_ALIGNMENT);
		newHost.setMaximumSize(new Dimension(Integer.MAX_VALUE, newHost.getPreferredSize().height));
		hostControls.add(transferHost);
		hostControls.add(Box.createVerticalStrut(6));
		hostControls.add(newHost);
		content.add(section("Host", hostControls,
				candidates.isEmpty() ? "Someone else must be connected before you can transfer host."
						: "The selected person becomes the host. You give up host settings and moderation controls."));

		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(event -> dispose());
		JButton save = new JButton("Save Settings");
		save.addActionListener(event -> save(manager));
		getRootPane().setDefaultButton(save);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(cancel);
		buttons.add(save);

		add(content, BorderLayout.CENTER);
		add(buttons, BorderLayout.SOUTH);
		setMinimumSize(new Dimension(560, 500));
		pack();
		setLocationRelativeTo(parent);
	}

	private static JPanel section(String title, Component control, String description) {
		JPanel panel = new JPanel(new BorderLayout(4, 6));
		panel.setBorder(BorderFactory.createTitledBorder(title));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		if (control != null) {
			panel.add(control, BorderLayout.NORTH);
		}
		JLabel help = new JLabel("<html>" + description + "</html>", SwingConstants.LEFT);
		panel.add(help, BorderLayout.CENTER);
		return panel;
	}

	private void save(LiveSessionManager manager) {
		LiveSessionManager.Participant selected = transferHost.isSelected()
				? (LiveSessionManager.Participant) newHost.getSelectedItem() : null;
		if (transferHost.isSelected() && selected == null) {
			JOptionPane.showMessageDialog(this, "Choose a connected participant to become host.",
					"Live Room Settings", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (selected != null) {
			int choice = JOptionPane.showConfirmDialog(this,
					"Make " + selected.name() + " the host?\n\n"
							+ "You will give up room settings and moderation controls.",
					"Transfer Host?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice != JOptionPane.YES_OPTION) {
				return;
			}
		}
		LiveProjectLink.EditPolicy policy = requireConnection.isSelected()
				? LiveProjectLink.EditPolicy.REQUIRE_CONNECTION
				: LiveProjectLink.EditPolicy.ALLOW_OFFLINE_BRANCHES;
		result = new Result(policy, autoOpen.isSelected(), selected == null ? null : selected.id());
		dispose();
	}

	public static Result showDialog(Window parent, LiveSessionManager manager, boolean autoOpen) {
		LiveRoomSettingsDialog dialog = new LiveRoomSettingsDialog(parent, manager, autoOpen);
		dialog.setVisible(true);
		return dialog.result;
	}
}
