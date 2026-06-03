import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.awt.event.*;
import java.util.*;

/**
 * Gomoku — гра "П'ять в ряд" (хрестики та нулики).
 * Один файл, один клас. Запуск: javac Gomoku.java && java Gomoku
 *
 * Алгоритм ШІ: жадібна евристика з оцінкою загрози.
 * Перебираються клітинки у радіусі 2 від існуючих каменів.
 * Кожна клітинка оцінюється для атаки та захисту:
 *   score = attackScore + 0.9 * defenseScore
 * Таблиця пріоритетів: 5 в ряд → 1 000 000, відкрита 4 → 50 000, і т.д.
 * Це забезпечує якісну гру без повного перебору (Minimax занадто повільний
 * на полі 15×15 без глибокого відсікання).
 */
public class FiveInARow extends JFrame {

    // ── Константи ──────────────────────────────────────────────────────────
    private static final int N       = 15;   // розмір поля
    private static final int CELL    = 42;   // розмір клітинки в пікселях
    private static final int PAD     = 10;   // відступ поля
    private static final int EMPTY   = 0;
    private static final int HUMAN   = 1;    // X
    private static final int AI      = 2;    // O

    // ── Стан гри ───────────────────────────────────────────────────────────
    private final int[][]         board   = new int[N][N];
    private final Deque<int[]>    history = new ArrayDeque<>();
    private boolean               humanTurn = true;
    private boolean               gameOver  = false;
    private int[][]               winLine   = null;

    // ── Рахунок ────────────────────────────────────────────────────────────
    private int scoreHuman = 0, scoreAI = 0, scoreDraw = 0;

    // ── UI ─────────────────────────────────────────────────────────────────
    private final BoardPanel boardPanel;
    private final JLabel     statusLabel;
    private final JLabel     scoreLabel;

    // ── Шрифт для X/O ──────────────────────────────────────────────────────
    private static final Font SYMBOL_FONT = new Font("SansSerif", Font.BOLD, 22);

    // ══════════════════════════════════════════════════════════════════════
    public static void main(String[] args) {
        SwingUtilities.invokeLater(FiveInARow::new);
    }

    // ══════════════════════════════════════════════════════════════════════
    public FiveInARow() {
        super("П'ять в ряд — Gomoku");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(4, 4));

        statusLabel = new JLabel("Ваш хід (X)", SwingConstants.CENTER);
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
        add(statusLabel, BorderLayout.NORTH);

        boardPanel = new BoardPanel();
        add(boardPanel, BorderLayout.CENTER);

        // Кнопки та рахунок
        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 6));
        JButton btnNew  = new JButton("Нова гра");
        JButton btnUndo = new JButton("Відмінити хід");
        scoreLabel = new JLabel(scoreText());
        scoreLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
        btnNew.addActionListener(e -> resetGame());
        btnUndo.addActionListener(e -> undoMove());
        south.add(btnNew);
        south.add(btnUndo);
        south.add(scoreLabel);
        add(south, BorderLayout.SOUTH);

        pack();
        setResizable(false);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ── Скидання гри ───────────────────────────────────────────────────────
    private void resetGame() {
        for (int[] row : board) Arrays.fill(row, EMPTY);
        history.clear();
        gameOver  = false;
        humanTurn = true;
        winLine   = null;
        setStatus("Ваш хід (X)");
        boardPanel.repaint();
    }

    // ── Відміна ходу ───────────────────────────────────────────────────────
    private void undoMove() {
        if (gameOver || history.size() < 2) return;
        for (int i = 0; i < 2; i++) {
            int[] m = history.pollLast();
            if (m != null) board[m[0]][m[1]] = EMPTY;
        }
        winLine   = null;
        humanTurn = true;
        setStatus("Ваш хід (X)");
        boardPanel.repaint();
    }

    // ── Хід гравця ─────────────────────────────────────────────────────────
    private void humanMove(int r, int c) {
        if (gameOver || !humanTurn || board[r][c] != EMPTY) return;
        applyMove(r, c, HUMAN);
        if (gameOver) return;
        humanTurn = false;
        setStatus("Думає ШІ...");
        // ШІ ходить у фоновому потоці, щоб не блокувати UI
        SwingWorker<int[], Void> worker = new SwingWorker<>() {
            @Override protected int[] doInBackground() { return aiBestMove(); }
            @Override protected void done() {
                try {
                    int[] pos = get();
                    applyMove(pos[0], pos[1], AI);
                    if (!gameOver) { humanTurn = true; setStatus("Ваш хід (X)"); }
                } catch (Exception ex) { ex.printStackTrace(); }
            }
        };
        worker.execute();
    }

    // ── Застосувати хід і перевірити результат ─────────────────────────────
    private void applyMove(int r, int c, int who) {
        board[r][c] = who;
        history.addLast(new int[]{r, c, who});
        boardPanel.repaint();
        int[][] win = checkWin(r, c, who);
        if (win != null) {
            winLine  = win;
            gameOver = true;
            boardPanel.repaint();
            if (who == HUMAN) { scoreHuman++; setStatus("Ви виграли!"); }
            else               { scoreAI++;    setStatus("ШІ виграло!"); }
            scoreLabel.setText(scoreText());
            return;
        }
        if (isFull()) {
            gameOver = true;
            scoreDraw++;
            scoreLabel.setText(scoreText());
            setStatus("Нічия!");
        }
    }

    // ── Перевірка 5 в ряд ──────────────────────────────────────────────────
    private int[][] checkWin(int r, int c, int who) {
        int[][] dirs = {{0,1},{1,0},{1,1},{1,-1}};
        for (int[] d : dirs) {
            List<int[]> line = new ArrayList<>();
            line.add(new int[]{r, c});
            for (int s = 1; s < 5; s++) {
                int nr = r + d[0]*s, nc = c + d[1]*s;
                if (!inBounds(nr,nc) || board[nr][nc] != who) break;
                line.add(new int[]{nr, nc});
            }
            for (int s = 1; s < 5; s++) {
                int nr = r - d[0]*s, nc = c - d[1]*s;
                if (!inBounds(nr,nc) || board[nr][nc] != who) break;
                line.add(0, new int[]{nr, nc});
            }
            if (line.size() >= 5) return line.subList(0, 5).toArray(new int[0][]);
        }
        return null;
    }

    // ── Алгоритм ШІ ────────────────────────────────────────────────────────
    /**
     * Повертає координати найкращого ходу ШІ.
     *
     * Метод: жадібна евристика з оцінкою загрози.
     * 1. Кандидати — лише клітинки у радіусі 2 від існуючих каменів
     *    (скорочує перебір з ~225 до ~30-60 клітинок).
     * 2. Кожна клітинка отримує дві оцінки:
     *    - atk: вигода для ШІ (атака)
     *    - def: небезпека для ШІ (блок загрози людини)
     * 3. Фінальна оцінка: score = atk + 0.9 * def
     *    (легкий пріоритет атаки).
     *
     * Чому не Minimax?
     * Повний Minimax на полі 15×15 навіть з Alpha-Beta відсіканням
     * потребує глибокого обмеження і складних оптимізацій для
     * прийнятної швидкості. Жадібна евристика дає якісний результат
     * за < 50 мс, що цілком достатньо для навчального проєкту.
     */
    private int[] aiBestMove() {
        List<int[]> cands = getCandidates();
        if (cands.isEmpty()) return new int[]{N/2, N/2};
        int bestScore = Integer.MIN_VALUE;
        int[] best = cands.get(0);
        for (int[] pos : cands) {
            int s = scoreCell(pos[0], pos[1], AI)
                    + (int)(scoreCell(pos[0], pos[1], HUMAN) * 0.9);
            if (s > bestScore) { bestScore = s; best = pos; }
        }
        return best;
    }

    /** Порожні клітинки у радіусі 2 від будь-якого каменя. */
    private List<int[]> getCandidates() {
        boolean[][] seen = new boolean[N][N];
        List<int[]> list = new ArrayList<>();
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
            if (board[r][c] == EMPTY) continue;
            for (int dr = -2; dr <= 2; dr++) for (int dc = -2; dc <= 2; dc++) {
                int nr = r+dr, nc = c+dc;
                if (inBounds(nr,nc) && board[nr][nc] == EMPTY && !seen[nr][nc]) {
                    seen[nr][nc] = true;
                    list.add(new int[]{nr, nc});
                }
            }
        }
        return list;
    }

    /**
     * Евристична оцінка клітинки (r,c) для гравця who.
     * Рахує кількість власних каменів і відкритих кінців
     * у кожному з 4 напрямків і повертає числовий бал.
     */
    private int scoreCell(int r, int c, int who) {
        if (board[r][c] != EMPTY) return Integer.MIN_VALUE;
        int total = 0;
        board[r][c] = who;
        int[][] dirs = {{0,1},{1,0},{1,1},{1,-1}};
        for (int[] d : dirs) {
            int cnt = 1, open = 0;
            for (int sign : new int[]{1, -1}) {
                for (int s = 1; s < 5; s++) {
                    int nr = r + d[0]*sign*s, nc = c + d[1]*sign*s;
                    if (!inBounds(nr,nc)) break;
                    if (board[nr][nc] == who) cnt++;
                    else { if (board[nr][nc] == EMPTY) open++; break; }
                }
            }
            if      (cnt >= 5)              total += 1_000_000;
            else if (cnt == 4 && open >= 1) total +=    50_000;
            else if (cnt == 4)              total +=     5_000;
            else if (cnt == 3 && open == 2) total +=     5_000;
            else if (cnt == 3 && open == 1) total +=     1_000;
            else if (cnt == 2 && open == 2) total +=       200;
            else if (cnt == 2 && open == 1) total +=        50;
            else                            total +=     cnt*5;
        }
        board[r][c] = EMPTY;
        return total;
    }

    // ── Допоміжні ──────────────────────────────────────────────────────────
    private boolean inBounds(int r, int c) { return r>=0 && r<N && c>=0 && c<N; }
    private boolean isFull() {
        for (int[] row : board) for (int v : row) if (v == EMPTY) return false;
        return true;
    }
    private void setStatus(String msg) { SwingUtilities.invokeLater(() -> statusLabel.setText(msg)); }
    private String scoreText() {
        return String.format("  Ви (X): %d     ШІ (O): %d     Нічия: %d", scoreHuman, scoreAI, scoreDraw);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Внутрішній клас — панель малювання поля
    // ══════════════════════════════════════════════════════════════════════
    private class BoardPanel extends JPanel {

        BoardPanel() {
            int size = N * CELL + PAD * 2;
            setPreferredSize(new Dimension(size, size));
            setBackground(new Color(240, 240, 240));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    int c = (e.getX() - PAD) / CELL;
                    int r = (e.getY() - PAD) / CELL;
                    if (inBounds(r, c)) humanMove(r, c);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // Фон поля
            g2.setColor(new Color(250, 250, 250));
            g2.fillRect(PAD, PAD, N * CELL, N * CELL);

            // Сітка
            g2.setStroke(new BasicStroke(0.7f));
            g2.setColor(new Color(200, 200, 200));
            for (int i = 0; i <= N; i++) {
                int x = PAD + i * CELL, y = PAD + i * CELL;
                g2.drawLine(x, PAD, x, PAD + N * CELL);
                g2.drawLine(PAD, y, PAD + N * CELL, y);
            }

            // Символи X і O у клітинках
            g2.setFont(SYMBOL_FONT);
            FontMetrics fm = g2.getFontMetrics();

            // Знаходимо виграшні клітинки
            Set<String> winSet = new HashSet<>();
            if (winLine != null)
                for (int[] pos : winLine) winSet.add(pos[0]+","+pos[1]);

            for (int r = 0; r < N; r++) {
                for (int c = 0; c < N; c++) {
                    if (board[r][c] == EMPTY) continue;
                    int x = PAD + c * CELL;
                    int y = PAD + r * CELL;
                    boolean isWin = winSet.contains(r+","+c);

                    // Підсвітка виграшних клітинок
                    if (isWin) {
                        g2.setColor(board[r][c] == HUMAN
                                ? new Color(255, 220, 220)
                                : new Color(210, 230, 255));
                        g2.fillRect(x + 1, y + 1, CELL - 1, CELL - 1);
                    }

                    // Символ
                    String sym = board[r][c] == HUMAN ? "X" : "O";
                    g2.setColor(board[r][c] == HUMAN
                            ? (isWin ? new Color(180, 0, 0) : new Color(200, 40, 40))
                            : (isWin ? new Color(0, 60, 180) : new Color(30, 100, 200)));
                    int sw = fm.stringWidth(sym);
                    int sh = fm.getAscent();
                    g2.drawString(sym,
                            x + (CELL - sw) / 2,
                            y + (CELL + sh) / 2 - 2);
                }
            }

            // Рамка поля
            g2.setColor(new Color(180, 180, 180));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(PAD, PAD, N * CELL, N * CELL);
        }
    }
}