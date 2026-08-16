package info.openrocket.swing.gui.live;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

import info.openrocket.core.live.LiveSessionEvent;

/** Compact activity timeline for one OpenRocket Live session. */
public final class LiveSidebar extends JPanel implements LiveSessionManager.Listener {
	private static final long serialVersionUID = 1L;
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm:ss a")
			.withZone(ZoneId.systemDefault());

	private final Window parent;
	private final JLabel statusLabel = new JLabel("Not in a Live session");
	private final DefaultListModel<String> activityModel = new DefaultListModel<>();
	private final DefaultListModel<String> peopleModel = new DefaultListModel<>();
	private final Map<String, String> people = new LinkedHashMap<>();
	private final JTextArea chatArea = new JTextArea();
	private final JTextField chatInput = new JTextField();
	private LiveSessionManager sessionManager;

	public LiveSidebar(Window parent) {
		super(new BorderLayout(8, 8));
		this.parent = parent;
		setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 1, 0, 0, getForeground()),
				BorderFactory.createEmptyBorder(10, 10, 10, 10)));
		setPreferredSize(new Dimension(320, 400));

		JPanel header = new JPanel(new BorderLayout(0, 4));
		JLabel title = new JLabel("OpenRocket Live");
		title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, title.getFont().getSize2D() + 2));
		header.add(title, BorderLayout.NORTH);
		header.add(statusLabel, BorderLayout.SOUTH);
		add(header, BorderLayout.NORTH);

		JTabbedPane tabs = new JTabbedPane();
		JList<String> activityList = new JList<>(activityModel);
		activityList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		tabs.addTab("Activity", new JScrollPane(activityList));

		JPanel chatPanel = new JPanel(new BorderLayout(4, 4));
		chatArea.setEditable(false);
		chatArea.setLineWrap(true);
		chatArea.setWrapStyleWord(true);
		chatPanel.add(new JScrollPane(chatArea), BorderLayout.CENTER);
		chatInput.setToolTipText("Message everyone in this Live session");
		chatInput.addActionListener(event -> sendChat());
		chatPanel.add(chatInput, BorderLayout.SOUTH);
		tabs.addTab("Chat", chatPanel);

		JList<String> peopleList = new JList<>(peopleModel);
		tabs.addTab("People", new JScrollPane(peopleList));
		add(tabs, BorderLayout.CENTER);
		setVisible(false);
	}

	public void setSessionManager(LiveSessionManager sessionManager) {
		this.sessionManager = sessionManager;
	}

	private void sendChat() {
		String text = chatInput.getText();
		if (sessionManager != null && !text.isBlank()) {
			sessionManager.sendChat(text);
			chatInput.setText("");
		}
	}

	@Override
	public void stateChanged(LiveSessionManager.Role role, String status) {
		statusLabel.setText(status);
	}

	@Override
	public void eventReceived(LiveSessionEvent event) {
		String time;
		try {
			time = TIME_FORMAT.format(Instant.parse(event.getTimestamp()));
		} catch (RuntimeException e) {
			time = event.getTimestamp();
		}
		activityModel.addElement(time + "  " + event.getSummary());
	}

	@Override
	public void chatReceived(String participantId, String participantName, String timestamp, String text) {
		String time;
		try {
			time = TIME_FORMAT.format(Instant.parse(timestamp));
		} catch (RuntimeException e) {
			time = timestamp;
		}
		chatArea.append(time + "  " + participantName + ": " + text + "\n");
		chatArea.setCaretPosition(chatArea.getDocument().getLength());
	}

	@Override
	public void presenceChanged(String participantId, String participantName, String surfaceId) {
		people.put(participantId, participantName + " — " + displaySurface(surfaceId));
		peopleModel.clear();
		for (String person : people.values()) {
			peopleModel.addElement(person);
		}
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

	@Override
	public void error(String message, Throwable cause) {
		String detail = cause == null || cause.getMessage() == null ? message
				: message + "\n\n" + cause.getMessage();
		JOptionPane.showMessageDialog(parent, detail, "OpenRocket Live", JOptionPane.ERROR_MESSAGE);
	}
}
