package info.openrocket.swing.gui.live;

import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ChangeListener;

import info.openrocket.core.live.LiveSessionEvent;

/** Sends and paints tab-aware remote cursors without intercepting local mouse input. */
public final class LiveCursorController implements LiveSessionManager.Listener, AutoCloseable {
	private static final long SEND_INTERVAL_NANOS = 50_000_000L;
	private static final long CURSOR_TIMEOUT_NANOS = 2_500_000_000L;

	private final JFrame frame;
	private final JTabbedPane tabs;
	private final LiveSessionManager sessionManager;
	private final CursorOverlay overlay = new CursorOverlay();
	private final AWTEventListener mouseListener = this::mouseEvent;
	private final ChangeListener tabListener = event -> surfaceChanged();
	private final Timer cleanupTimer;
	private long lastSent;

	public LiveCursorController(JFrame frame, JTabbedPane tabs, LiveSessionManager sessionManager) {
		this.frame = frame;
		this.tabs = tabs;
		this.sessionManager = sessionManager;
		frame.setGlassPane(overlay);
		overlay.setVisible(true);
		sessionManager.addListener(this);
		tabs.addChangeListener(tabListener);
		Toolkit.getDefaultToolkit().addAWTEventListener(mouseListener, AWTEvent.MOUSE_MOTION_EVENT_MASK);
		cleanupTimer = new Timer(500, event -> overlay.removeExpired());
		cleanupTimer.start();
	}

	public void setCursorsVisible(boolean visible) {
		overlay.setCursorsVisible(visible);
	}

	private void mouseEvent(AWTEvent event) {
		if (!(event instanceof MouseEvent mouseEvent)
				|| (mouseEvent.getID() != MouseEvent.MOUSE_MOVED && mouseEvent.getID() != MouseEvent.MOUSE_DRAGGED)
				|| sessionManager.getRole() == LiveSessionManager.Role.IDLE
				|| !(mouseEvent.getSource() instanceof Component source)
				|| !SwingUtilities.isDescendingFrom(source, frame)) {
			return;
		}
		long now = System.nanoTime();
		if (now - lastSent < SEND_INTERVAL_NANOS) {
			return;
		}
		lastSent = now;
		Point point = SwingUtilities.convertPoint(source, mouseEvent.getPoint(), frame.getContentPane());
		int width = frame.getContentPane().getWidth();
		int height = frame.getContentPane().getHeight();
		if (width <= 0 || height <= 0) {
			return;
		}
		double x = Math.max(0, Math.min(1, point.x / (double) width));
		double y = Math.max(0, Math.min(1, point.y / (double) height));
		sessionManager.sendCursor(currentSurface(), x, y);
	}

	private void surfaceChanged() {
		overlay.setSurface(currentSurface());
		sessionManager.sendPresence(currentSurface());
	}

	private String currentSurface() {
		return "main:" + tabs.getSelectedIndex();
	}

	@Override
	public void stateChanged(LiveSessionManager.Role role, String status) {
		if (role == LiveSessionManager.Role.IDLE) {
			overlay.clear();
		} else {
			surfaceChanged();
		}
	}

	@Override
	public void eventReceived(LiveSessionEvent event) {
	}

	@Override
	public void error(String message, Throwable cause) {
	}

	@Override
	public void presenceChanged(String participantId, String participantName, String surfaceId) {
		if (!currentSurface().equals(surfaceId)) {
			overlay.remove(participantId);
		}
	}

	@Override
	public void cursorMoved(String participantId, String participantName, String surfaceId, double x, double y) {
		overlay.update(participantId, participantName, surfaceId, x, y);
	}

	@Override
	public void close() {
		cleanupTimer.stop();
		Toolkit.getDefaultToolkit().removeAWTEventListener(mouseListener);
		tabs.removeChangeListener(tabListener);
		sessionManager.removeListener(this);
		overlay.clear();
	}

	private static final class CursorOverlay extends JComponent {
		private static final long serialVersionUID = 1L;
		private final Map<String, RemoteCursor> cursors = new LinkedHashMap<>();
		private String surface = "main:0";
		private boolean cursorsVisible = true;

		@Override
		public boolean contains(int x, int y) {
			return false;
		}

		private void setSurface(String surface) {
			this.surface = surface;
			repaint();
		}

		private void setCursorsVisible(boolean visible) {
			this.cursorsVisible = visible;
			repaint();
		}

		private void update(String id, String name, String cursorSurface, double x, double y) {
			cursors.put(id, new RemoteCursor(name, cursorSurface, x, y, System.nanoTime()));
			repaint();
		}

		private void remove(String id) {
			if (cursors.remove(id) != null) {
				repaint();
			}
		}

		private void removeExpired() {
			long now = System.nanoTime();
			if (cursors.entrySet().removeIf(entry -> now - entry.getValue().updated > CURSOR_TIMEOUT_NANOS)) {
				repaint();
			}
		}

		private void clear() {
			cursors.clear();
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			if (!cursorsVisible) {
				return;
			}
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				for (Map.Entry<String, RemoteCursor> entry : cursors.entrySet()) {
					RemoteCursor cursor = entry.getValue();
					if (!surface.equals(cursor.surface)) {
						continue;
					}
					int x = (int) Math.round(cursor.x * getWidth());
					int y = (int) Math.round(cursor.y * getHeight());
					Color color = colorFor(entry.getKey());
					g.setColor(color);
					g.setStroke(new BasicStroke(2));
					g.fillOval(x - 5, y - 5, 11, 11);
					FontMetrics metrics = g.getFontMetrics();
					int labelWidth = metrics.stringWidth(cursor.name) + 10;
					g.fillRoundRect(x + 8, y + 8, labelWidth, metrics.getHeight() + 4, 8, 8);
					g.setColor(Color.WHITE);
					g.drawString(cursor.name, x + 13, y + 10 + metrics.getAscent());
				}
			} finally {
				g.dispose();
			}
		}

		private static Color colorFor(String participantId) {
			float hue = (participantId.hashCode() & 0xffff) / 65535.0f;
			return Color.getHSBColor(hue, 0.75f, 0.8f);
		}
	}

	private record RemoteCursor(String name, String surface, double x, double y, long updated) {
	}
}
