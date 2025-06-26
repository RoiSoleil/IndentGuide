/******************************************************************************
 * Copyright (c) 2006-2024 The IndentGuide Authors.
 * All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the MIT License.  A copy of the MIT License is included this
 * distribution and is available at https://opensource.org/licenses/MIT.
 *****************************************************************************/
package net.certiv.tools.indentguide.painter;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.custom.StyledText;

import net.certiv.tools.indentguide.util.MsgBuilder;
import net.certiv.tools.indentguide.util.Utils;

/**
 * Describes a single code line in a source file. Contains a {@link LineInfo} that details
 * tab stop positions.
 * <p>
 * The info is nominally computed for the current line.
 * <p>
 * If the currrent line is blank, the info is computed from the first prior real
 * (non-blank/non-column zero comment) line. If no such line exists, the info is computed
 * for the current blank line.
 * <p>
 * Another info is also computed from the first next real line in order to determine a
 * delta dentation change {@code [- <- 0 -> +]} between the prior and next real lines.
 */
public class Line implements Iterable<Pos> {

	/** Block comment pattern. */
	private static final Pattern COMMENT = Pattern.compile( //
			"^(?:\\h*(?:" 					// $NON-NLS-1$
					+ "/\\*.*|"				// $NON-NLS-1$ -> ^'/*'.*$
					+ " \\*|"				// $NON-NLS-1$ -> ^' *'$
					+ " \\* .*|"			// $NON-NLS-1$ -> ^' * '.*$
					+ " \\*/.*|"			// $NON-NLS-1$ -> ^' */'.*$
					+ " (?:\\*.*)?\\*/" 	// $NON-NLS-1$ -> ^' *'.*'*/'.*$
					+ "))$" 				// $NON-NLS-1$
	);

	// ---- Global data ---------------

	/** Viewer */
	final ITextViewer viewer;
	/** Control */
	final StyledText widget;
	/** Partition specific line prefixes by partition type */
	final Map<String, List<String>> prefixMap;
	/** Defined tab width */
	final int tabWidth;

	// ---- Current Line Data ---------

	/** Line number (0..n) */
	final int lnNum;
	/** Blank line */
	final boolean blank;
	/** In block comment */
	final boolean block;
	/** Column 0 line comment */
	final boolean cmt0;
	/** Current line text; may be blank, etc. */
	final String txt;

	/** Target Line Info */
	LineInfo info;
	/** No indent stops (excluding column 0 stop) */
	boolean noDents;

	/** Delta dentation for this line */
	int delta;
	/** Zero delta */
	boolean delta0;

	// --------------------------------

	/**
	 * Describes one line, including the state of the line itself, and the reference lines
	 * before and after this line.
	 *
	 * @param viewer    containing text viewer
	 * @param widget    containing widget control
	 * @param prefixMap line comment prefixes by partition type
	 * @param lnNum     line number (0..n) within the widget
	 * @param tabWidth  tab width
	 */
	public Line(ITextViewer viewer, StyledText widget, Map<String, List<String>> prefixMap, int lnNum,
			int tabWidth) {
		this.viewer = viewer;
		this.widget = widget;
		this.prefixMap = prefixMap;
		this.lnNum = lnNum;
		this.tabWidth = tabWidth;

		txt = widget.getLine(lnNum);
		blank = txt.isBlank();
		block = !blank && COMMENT.matcher(txt).matches();
		cmt0 = isCol0Comment(lnNum, txt);

		processLine();

		noDents = stopCnt() == 1;
		delta0 = delta == 0;
	}

	private void processLine() {
		int num = blank ? findPrev(lnNum) : lnNum;
		info = new LineInfo(widget, num, tabWidth);
		if (blank) {
			LineInfo next = new LineInfo(widget, findNext(lnNum), tabWidth);
			delta = next.stopCnt() - info.stopCnt();
			for (int dec = delta; dec < 0; dec++) {
				info.removeLast(); // shift out (-) by one
			}
		}
	}

	/**
	 * Find the next real (non-blank/non-col0 comment) line num starting after the given
	 * line number. If none exists, returns the given line number.
	 *
	 * @param num reference line number
	 */
	private int findNext(int num) {
		for (int next = num + 1, end = widget.getLineCount(); next < end; next++) {
			String txt = widget.getLine(next);
			if (!txt.isBlank() && !isCol0Comment(next, txt)) return next;
		}
		return num;
	}

	/**
	 * Find the prior real (non-blank/non-col0 comment) line num starting before the given
	 * line number. If none exists, returns the given line number.
	 *
	 * @param num reference line number
	 */
	private int findPrev(int num) {
		for (int prev = num - 1; prev >= 0; prev--) {
			String txt = widget.getLine(prev);
			if (!txt.isBlank() && !isCol0Comment(prev, txt)) return prev;
		}
		return num;
	}

	private boolean isCol0Comment(int num, String txt) {
		if (txt.isBlank()) return false;

		List<String> prefixes = prefixMap.get(Utils.partitionType(viewer, num));
		return prefixes != null && prefixes.stream().anyMatch(txt::startsWith);
	}

	/**
	 * Return the column of the first text character.
	 *
	 * @return first text column
	 */
	int textCol() {
		return info.beg;
	}

	Pos firstStop() {
		return info.stops.peekFirst();
	}

	Pos lastStop() {
		return info.stops.peekLast();
	}

	/**
	 * Return the column of the last stop.
	 *
	 * @return the last stop column
	 */
	int lastStopCol() {
		return info.lastStop().col;
	}

	/**
	 * Returns the stop position at the given index in the stop list.
	 *
	 * @param idx stop position index
	 * @return stop position
	 * @throws IndexOutOfBoundsException
	 */
	Pos stop(int idx) {
		return info.stops.get(idx);
	}

	/**
	 * Return the total number of stop positions.
	 *
	 * @return stop position count
	 */
	int stopCnt() {
		return info.stopCnt();
	}

	/**
	 * Return {@code true} if the number of tab stops equals the given {@code cnt}.
	 *
	 * @return {@code true} if the tab stop count is {@code cnt}.
	 */
	boolean stopCntEquals(int cnt) {
		return stopCnt() == cnt;
	}

	// --------------------------------

	@Override
	public Iterator<Pos> iterator() {
		return info.iterator();
	}

	@Override
	public String toString() {
		MsgBuilder mb = new MsgBuilder() //
				.append("Line %d", lnNum) //
				.append(lnNum != info.num, "(%d)", info.num) //
				.append(": %s", info.stops) //
				.append(blank, " @0\t<blank>") //
				.append(!blank, " @%d\t'%s'", info.beg, Utils.encode(info.txt));
		return mb.toString();
	}
}
