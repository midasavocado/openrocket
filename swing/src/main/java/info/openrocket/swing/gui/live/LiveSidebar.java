package info.openrocket.swing.gui.live;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

import info.openrocket.core.live.LiveSessionEvent;

/** One vertically stacked activity, people, and chat sidebar for a Live room. */
public final class LiveSidebar extends JPanel implements LiveSessionManager.Listener {
	private static final long serialVersionUID = 1L;
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")
			.withZone(ZoneId.systemDefault());

	private final Window parent;
	private final JLabel roomLabel = new JLabel("No room");
	private final JLabel statusLabel = new JLabel("Not in a Live session");
	private final JTextArea activityArea = createReadOnlyArea();
	private final DefaultListModel<PersonEntry> peopleModel = new DefaultListModel<>();
	private final JList<PersonEntry> peopleList = new JList<>(peopleModel);
	private final Map<String, PersonEntry> people = new LinkedHashMap<>();
	private final JButton kickButton = new JButton("Remove");
	private final JButton banButton = new JButton("Ban");
	private final JTextArea chatArea = createReadOnlyArea();
	private final JTextField chatInput = new JTextField();
	private final JButton sendButton = new JButton("Send");
	private LiveSessionManager sessionManager;

	public LiveSidebar(Window parent) {
		super(new BorderLayout(8, 8));
		this.parent = parent;
		setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 1, 0, 0, getForeground()),
				BorderFactory.createEmptyBorder(10, 10, 10, 10)));
		setPreferredSize(new Dimension(380, 600));

		JPanel header = new JPanel(new GridBagLayout());
		GridBagConstraints headerConstraints = constraints(0, 0, 1);
		headerConstraints.fill = GridBagConstraints.HORIZONTAL;
		JLabel title = new JLabel("OpenRocket Live");
		title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2));
		header.add(title, headerConstraints);
		headerConstraints.gridy = 1;
		roomLabel.setFont(roomLabel.getFont().deriveFont(Font.BOLD));
		header.add(roomLabel, headerConstraints);
		headerConstraints.gridy = 2;
		header.add(statusLabel, headerConstraints);
		add(header, BorderLayout.NORTH);

		peopleList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		peopleList.addListSelectionListener(event -> updateParticipantButtons());
		kickButton.addActionListener(event -> removeSelectedParticipant(false));
		banButton.addActionListener(event -> removeSelectedParticipant(true));

		chatInput.setToolTipText("Message everyone in this Live room");
		chatInput.addActionListener(event -> sendChat());
		sendButton.addActionListener(event -> sendChat());

		JPanel sections = new JPanel(new GridBagLayout());
		GridBagConstraints sectionConstraints = constraints(0, 0, 0.22);
		sections.add(createSection("People", createPeoplePanel()), sectionConstraints);
		sectionConstraints.gridy = 1;
		sectionConstraints.weighty = 0.43;
		sections.add(createSection("Activity", new JScrollPane(activityArea)), sectionConstraints);
		sectionConstraints.gridy = 2;
		sectionConstraints.weighty = 0.35;
		sections.add(createSection("Chat", createChatPanel()), sectionConstraints);
		add(sections, BorderLayout.CENTER);

		setConnectedControls(false);
		setVisible(false);
	}

	public void setSessionManager(LiveSessionManager sessionManager) {
		this.sessionManager = sessionManager;
		updateParticipantButtons();
	}

	private JPanel createPeoplePanel() {
		JPanel panel = new JPanel(new BorderLayout(4, 4));
		panel.add(new JScrollPane(peopleList), BorderLayout.CENTER);
		JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		controls.add(kickButton);
		controls.add(banButton);
		panel.add(controls, BorderLayout.SOUTH);
		return panel;
	}

	private JPanel createChatPanel() {
		JPanel panel = new JPanel(new BorderLayout(4, 4));
		panel.add(new JScrollPane(chatArea), BorderLayout.CENTER);
		JPanel composer = new JPanel(new BorderLayout(4, 0));
		composer.add(chatInput, BorderLayout.CENTER);
		composer.add(sendButton, BorderLayout.EAST);
		panel.add(composer, BorderLayout.SOUTH);
		return panel;
	}

	private static JPanel createSection(String title, java.awt.Component content) {
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBorder(BorderFactory.createTitledBorder(title));
		panel.add(content, BorderLayout.CENTER);
		return panel;
	}

	private static GridBagConstraints constraints(int x, int y, double weightY) {
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = x;
		constraints.gridy = y;
		constraints.weightx = 1;
		constraints.weighty = weightY;
		constraints.fill = GridBagConstraints.BOTH;
		constraints.insets = new Insets(0, 0, 6, 0);
		return constraints;
	}

	private static JTextArea createReadOnlyArea() {
		JTextArea area = new JTextArea();
		area.setEditable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setMargin(new Insets(5, 5, 5, 5));
		return area;
	}

	private void sendChat() {
		String text = chatInput.getText();
		if (sessionManager != null && sessionManager.isConnected() && !text.isBlank()) {
			sessionManager.sendChat(text);
			chatInput.setText("");
			chatInput.requestFocusInWindow();
		}
	}

	private void removeSelectedParticipant(boolean ban) {
		PersonEntry selected = peopleList.getSelectedValue();
		if (selected == null || selected.local() || sessionManager == null
				|| sessionManager.getRole() != LiveSessionManager.Role.HOST) {
			return;
		}
		String action = ban ? "Ban" : "Remove";
		String detail = ban ? "They will not be able to rejoin while this room is hosted from this window."
				: "They can use the invite to join again.";
		int result = JOptionPane.showConfirmDialog(parent,
				action + " " + selected.name() + " from this room?\n\n" + detail,
				action + " Participant?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (result == JOptionPane.YES_OPTION) {
			sessionManager.removeParticipant(selected.id(), ban);
		}
	}

	@Override
	public void stateChanged(LiveSessionManager.Role role, String status) {
		statusLabel.setText(status);
		String currentRoom = sessionManager == null ? null : sessionManager.getRoomName();
		roomLabel.setText(currentRoom == null || currentRoom.isBlank() ? "No room" : currentRoom);
		setConnectedControls(sessionManager != null && sessionManager.isConnected());
		if (role == LiveSessionManager.Role.IDLE) {
			activityArea.setText("");
			people.clear();
			peopleModel.clear();
			chatArea.setText("");
			chatInput.setText("");
		}
		updateParticipantButtons();
	}

	private void setConnectedControls(boolean connected) {
		chatInput.setEnabled(connected);
		sendButton.setEnabled(connected);
	}

	@Override
	public void eventReceived(LiveSessionEvent event) {
		if (event.getType() == LiveSessionEvent.Type.CHAT_MESSAGE) {
			appendChat(event.getParticipantId(), event.getParticipantName(), event.getTimestamp(),
					event.getNewValue());
			return;
		}
		activityArea.append(formatTime(event.getTimestamp()) + "  " + event.getSummary() + "\n\n");
		activityArea.setCaretPosition(activityArea.getDocument().getLength());
	}

	@Override
	public void chatReceived(String participantId, String participantName, String timestamp, String text) {
		appendChat(participantId, participantName, timestamp, text);
	}

	private void appendChat(String participantId, String participantName, String timestamp, String text) {
		boolean local = sessionManager != null && participantId.equals(sessionManager.getParticipantId());
		chatArea.append(participantName + (local ? " (You)" : "") + "  " + formatTime(timestamp) + "\n");
		chatArea.append(text + "\n\n");
		chatArea.setCaretPosition(chatArea.getDocument().getLength());
	}

	@Override
	public void presenceChanged(String participantId, String participantName, String surfaceId) {
		if (surfaceId == null) {
			people.remove(participantId);
		} else {
			boolean local = sessionManager != null && participantId.equals(sessionManager.getParticipantId());
			people.put(participantId, new PersonEntry(participantId, participantName, displaySurface(surfaceId), local));
		}
		rebuildPeopleModel();
	}

	private void rebuildPeopleModel() {
		peopleModel.clear();
		for (PersonEntry person : people.values()) {
			peopleModel.addElement(person);
		}
		updateParticipantButtons();
	}

	private void updateParticipantButtons() {
		PersonEntry selected = peopleList.getSelectedValue();
		boolean canManage = sessionManager != null
				&& sessionManager.getRole() == LiveSessionManager.Role.HOST
				&& selected != null && !selected.local();
		kickButton.setEnabled(canManage);
		banButton.setEnabled(canManage);
	}

	private static String displaySurface(String surfaceId) {
		if ("main:0".equals(surfaceId)) {
			return "Rocket Design";
		}
		if ("main:1".equals(surfaceId)) {
			return "Flight Configuration";
		}
		if ("main:2".equals(surfaceId)) {
			return "Flight Simulations";
		}
		return "OpenRocket";
	}

	private static String formatTime(String timestamp) {
		try {
			return TIME_FORMAT.format(Instant.parse(timestamp));
		} catch (RuntimeException e) {
			return timestamp;
		}
	}

	@Override
	public void error(String message, Throwable cause) {
		String detail = cause == null || cause.getMessage() == null ? message
				: message + "\n\n" + cause.getMessage();
		JOptionPane.showMessageDialog(parent, detail, "OpenRocket Live", JOptionPane.ERROR_MESSAGE);
	}

	@Override
	public void offlineBranchProposed(String participantId, String participantName,
			Runnable accept, Runnable keepHostVersion) {
		Object[] options = { "Accept Offline Branch", "Keep Host Version" };
		int result = JOptionPane.showOptionDialog(parent,
				participantName + " edited while disconnected and has proposed an offline branch.\n\n"
						+ "Accepting replaces the shared design with their complete branch. "
						+ "OpenRocket does not merge individual rocket parts yet.",
				"Review Offline Branch", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
				null, options, options[1]);
		if (result == 0) {
			accept.run();
		} else {
			keepHostVersion.run();
		}
	}

	private record PersonEntry(String id, String name, String surface, boolean local) {
		@Override
		public String toString() {
			return name + (local ? " (You)" : "") + "  •  " + surface;
		}
	}
}
