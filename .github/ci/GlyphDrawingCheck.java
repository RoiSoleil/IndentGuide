import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Region;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Draws colored parentheses over a StyledText the way IndentGuidePainter does, with the GC in the advanced engine
 * (as in 1.2.5, after the alpha of the guides) and back in the plain engine (the change), and compares both to the
 * reference: the same parentheses colored by the StyledText itself. Prints the time of the drawing of the glyphs
 * and the number of pixels that differ from the reference, and saves the three pictures.
 */
public class GlyphDrawingCheck {

	public static void main(String[] args) throws Exception {
		Display display = new Display();
		Shell shell = new Shell(display);
		shell.setLayout(new FillLayout());
		StyledText text = new StyledText(shell, SWT.NONE);
		Font font = new Font(display, "Consolas", 10, SWT.NORMAL);
		text.setFont(font);
		StringBuilder content = new StringBuilder();
		for (int i = 0; i < 60; i++) {
			content.append("\t\tresult = combine(first(a, b), second(c, d), third(e(f(g)))) + (x * (y - z));\n");
		}
		text.setText(content.toString());
		shell.setSize(1000, 1000);
		shell.open();
		Color red = new Color(display, 200, 30, 30);
		List<Integer> parentheses = new ArrayList<>();
		for (int i = 0; i < content.length(); i++) {
			char ch = content.charAt(i);
			if (ch == '(' || ch == ')') {
				parentheses.add(i);
			}
		}
		settle(display);

		// the reference: the StyledText draws the parentheses in red itself
		for (int offset : parentheses) {
			text.setStyleRange(new StyleRange(offset, 1, red, null));
		}
		Image reference = capture(display, text);
		text.setStyleRanges(new StyleRange[0]);

		// each mode twice, alternated, so that the warm-up of the JVM does not favour the second
		String[] modes = { "advanced", "plain", "advanced", "plain" };
		boolean failed = false;
		int advancedDifferences = 0;
		Image[] images = new Image[2];
		for (int m = 0; m < modes.length; m++) {
			boolean plain = m % 2 == 1;
			long[] nanos = new long[1];
			int[] paints = new int[1];
			int[] glyphs = new int[1];
			boolean[] advancedUsed = new boolean[1];
			PaintListener listener = event -> {
				GC gc = event.gc;
				// the guides set an alpha, which switches the GC to the advanced engine
				gc.setAlpha(120);
				gc.setAlpha(255);
				advancedUsed[0] = gc.getAdvanced();
				long start = System.nanoTime();
				// as IndentGuidePainter.usePlainEngine, the GC is left plain afterwards
				if (plain && gc.getAdvanced()) {
					Region clipping = new Region(display);
					try {
						gc.getClipping(clipping);
						gc.setAdvanced(false);
						gc.setClipping(clipping);
					} finally {
						clipping.dispose();
					}
				}
				int startLine = text.getLineIndex(event.y);
				int endLine = text.getLineIndex(event.y + event.height - 1);
				Point selection = text.getSelectionRange();
				for (int offset : parentheses) {
					int line = text.getLineAtOffset(offset);
					if (line < startLine || line > endLine) {
						continue;
					}
					if (selection.y > 0 && offset >= selection.x && offset < selection.x + selection.y) {
						continue;
					}
					gc.setForeground(red);
					gc.setBackground(text.getBackground());
					Point location = text.getLocationAtOffset(offset);
					gc.drawText(String.valueOf(content.charAt(offset)), location.x, location.y, false);
					glyphs[0]++;
				}
				nanos[0] += System.nanoTime() - start;
				paints[0]++;
			};
			text.addPaintListener(listener);
			if (images[m % 2] != null) {
				images[m % 2].dispose();
			}
			images[m % 2] = capture(display, text);
			// the time is measured on more paints of the whole widget
			nanos[0] = 0;
			paints[0] = 0;
			glyphs[0] = 0;
			for (int i = 0; i < 20; i++) {
				capture(display, text).dispose();
			}
			text.removePaintListener(listener);
			int differences = differences(reference, images[m % 2],
					text.getSize().y / text.getLineHeight() * text.getLineHeight());
			System.out.printf("%-8s: %.2f ms per paint for %d glyphs, %d pixels differ from the reference%n", modes[m],
					nanos[0] / 1e6 / Math.max(1, paints[0]), glyphs[0] / Math.max(1, paints[0]), differences);
			int glyphCount = glyphs[0] / Math.max(1, paints[0]);
			if (plain) {
				// A glyph drawn alone, on an opaque background, cuts the
				// ClearType fringe of its neighbours that the StyledText draws
				// with the whole line: the plain engine is not pixel-identical,
				// but stays within that fringe and closer to the StyledText than
				// the advanced engine.
				if (differences > 2 * glyphCount
						|| (differences > 0 && differences >= advancedDifferences)) {
					System.out.printf("FAILED: the plain engine differs by %d pixels for %d glyphs, the advanced one by %d%n",
							differences, glyphCount, advancedDifferences);
					failed = true;
				}
			} else {
				advancedDifferences = differences;
				if (!advancedUsed[0]) {
					System.out.println("FAILED: the GC did not switch to the advanced engine, nothing is compared");
					failed = true;
				}
			}
		}
		save(reference, "reference.png");
		save(images[0], "advanced.png");
		save(images[1], "plain.png");
		display.dispose();
		if (failed) {
			System.exit(1);
		}
	}

	private static void settle(Display display) {
		long end = System.currentTimeMillis() + 500;
		while (System.currentTimeMillis() < end) {
			while (display.readAndDispatch()) {
			}
		}
	}

	/** Paints the StyledText, with its paint listeners, into a new image. */
	private static Image capture(Display display, StyledText text) {
		Point size = text.getSize();
		Image image = new Image(display, size.x, size.y);
		GC gc = new GC(image);
		text.print(gc);
		gc.dispose();
		return image;
	}

	/** The number of pixels that differ, above the given height: the last line, cut by the bottom, is left out. */
	private static int differences(Image a, Image b, int height) {
		ImageData da = a.getImageData();
		ImageData db = b.getImageData();
		int count = 0;
		for (int y = 0; y < Math.min(height, Math.min(da.height, db.height)); y++) {
			for (int x = 0; x < Math.min(da.width, db.width); x++) {
				if (!da.palette.getRGB(da.getPixel(x, y)).equals(db.palette.getRGB(db.getPixel(x, y)))) {
					count++;
				}
			}
		}
		return count;
	}

	private static void save(Image image, String name) {
		ImageLoader loader = new ImageLoader();
		loader.data = new ImageData[] { image.getImageData() };
		loader.save(name, SWT.IMAGE_PNG);
	}
}
