package dev.incusspawn.tui;

import dev.tamboui.buffer.DiffResult;

import java.util.Arrays;

/**
 * A terminal screen in memory: it starts out holding what was there before the TUI (every cell
 * {@code X}), {@code clear()} blanks it, and a draw writes exactly the cells the diff sends.
 */
final class ScreenBackend extends HeadlessBackend {
    static final String STALE = "X";

    private final int width;
    private final int height;
    private final char[] cells;

    ScreenBackend(int width, int height) {
        super(width, height);
        this.width = width;
        this.height = height;
        this.cells = new char[width * height];
        scribble();
    }

    /** Fills the screen with stale output, as another program writing to the terminal would. */
    void scribble() {
        Arrays.fill(cells, STALE.charAt(0));
    }

    /** The screen as text, one line per row. */
    String text() {
        var sb = new StringBuilder();
        for (int y = 0; y < height; y++) sb.append(cells, y * width, width).append('\n');
        return sb.toString();
    }

    @Override
    public void draw(DiffResult diff) {
        for (int i = 0; i < diff.size(); i++) {
            var cell = diff.getCell(i);
            if (cell.isContinuation()) continue;
            var symbol = cell.symbol();
            cells[diff.getY(i) * width + diff.getX(i)] = symbol.isEmpty() ? ' ' : symbol.charAt(0);
        }
    }

    @Override
    public void clear() {
        Arrays.fill(cells, ' ');
    }
}
